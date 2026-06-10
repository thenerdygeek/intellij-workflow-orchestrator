package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MessageStateHandlerStreamingCadenceTest {

    private fun handler(dir: File) = MessageStateHandler(dir, "cadence-test", "task")

    /** Deterministic-clock handler: throttle decisions driven by [nowMs], not wall-clock (issue #51 hygiene). */
    private fun handlerWithClock(dir: File, nowMs: () -> Long) =
        MessageStateHandler(dir, "cadence-test", "task", nowMs)

    private fun partialMsg(ts: Long, text: String) = UiMessage(
        ts = ts,
        type = UiMessageType.SAY,
        say = UiSay.TEXT,
        text = text,
        partial = true,
    )

    private fun apiMsg(ts: Long, tokensIn: Int, tokensOut: Int, cost: Double, modelId: String) = ApiMessage(
        role = ApiRole.ASSISTANT,
        content = listOf(ContentBlock.Text("assistant turn at ts=$ts")),
        ts = ts,
        modelInfo = ModelInfo(modelId = modelId),
        metrics = ApiRequestMetrics(inputTokens = tokensIn, outputTokens = tokensOut, cost = cost),
    )

    @Test
    fun `updateLastPartialMessage updates memory immediately but throttles disk writes`(
        @TempDir dir: File,
    ) = runBlocking {
        var now = 1_000L
        val h = handlerWithClock(dir) { now }
        h.addToClineMessages(partialMsg(1L, "a")) // immediate save stamps the window at now=1000
        val fileAfterAdd = File(dir, "sessions/cadence-test/ui_messages.json").readText()

        now = 1_100L
        h.updateLastPartialMessage("ab") // inside the 500ms window → memory only
        now = 1_200L
        h.updateLastPartialMessage("abc")

        assertEquals("abc", h.getClineMessages().last().text, "memory must be current")
        val fileNow = File(dir, "sessions/cadence-test/ui_messages.json").readText()
        assertEquals(fileAfterAdd, fileNow, "disk must NOT have been rewritten inside the throttle window")

        now = 1_600L
        h.updateLastPartialMessage("abcd") // window elapsed → writes
        val onDisk = MessageStateHandler.loadUiMessages(File(dir, "sessions/cadence-test"))
        assertEquals("abcd", onDisk.last().text, "post-window update must reach disk")
    }

    @Test
    fun `saveBoth flushes throttled partial text to disk`(@TempDir dir: File) = runBlocking {
        val h = handler(dir)
        h.addToClineMessages(partialMsg(1L, "a"))
        h.updateLastPartialMessage("abc") // throttled, memory-only
        h.saveBoth()
        val onDisk = MessageStateHandler.loadUiMessages(File(dir, "sessions/cadence-test"))
        assertEquals("abc", onDisk.last().text)
    }

    @Test
    fun `updateLastPartialMessage is a no-op when last message is not partial`(@TempDir dir: File) = runBlocking {
        val h = handler(dir)
        h.addToClineMessages(partialMsg(1L, "streaming").copy(partial = false))
        h.updateLastPartialMessage("should not land")
        assertEquals("streaming", h.getClineMessages().last().text)
    }

    @Test
    fun `global index token totals are correct after append, truncate, and overwrite`(
        @TempDir dir: File,
    ) = runBlocking {
        val h = handler(dir)
        h.addToApiConversationHistory(apiMsg(10L, tokensIn = 100, tokensOut = 10, cost = 1.0, modelId = "model-a"))
        h.addToApiConversationHistory(apiMsg(20L, tokensIn = 200, tokensOut = 20, cost = 2.0, modelId = "model-b"))
        h.addToApiConversationHistory(apiMsg(30L, tokensIn = 300, tokensOut = 30, cost = 4.0, modelId = "model-c"))
        h.saveBoth()
        val afterAppend = MessageStateHandler.loadGlobalIndex(dir).first()
        assertEquals(600L, afterAppend.tokensIn)
        assertEquals(60L, afterAppend.tokensOut)
        assertEquals(7.0, afterAppend.totalCost, 1e-9)
        assertEquals("model-c", afterAppend.modelId)

        // No UI messages have ts >= 25, so only the trailing api entry (ts=30) is dropped.
        h.truncateMessagesAtTs(targetMessageTs = 25L, droppedApiCount = 1)
        h.saveBoth()
        val afterTruncate = MessageStateHandler.loadGlobalIndex(dir).first()
        assertEquals(300L, afterTruncate.tokensIn, "P1-4: truncate must recompute, not keep stale counters")
        assertEquals(30L, afterTruncate.tokensOut)
        assertEquals(3.0, afterTruncate.totalCost, 1e-9)
        assertEquals("model-b", afterTruncate.modelId)

        h.overwriteApiConversationHistory(
            listOf(apiMsg(40L, tokensIn = 50, tokensOut = 5, cost = 0.25, modelId = "model-d")),
        )
        h.saveBoth()
        val afterOverwrite = MessageStateHandler.loadGlobalIndex(dir).first()
        assertEquals(50L, afterOverwrite.tokensIn, "P1-4: overwrite must recompute, not keep stale counters")
        assertEquals(5L, afterOverwrite.tokensOut)
        assertEquals(0.25, afterOverwrite.totalCost, 1e-9)
        assertEquals("model-d", afterOverwrite.modelId)
    }

    @Test
    fun `global index is fresh after addToApiConversationHistory inside the throttle window`(
        @TempDir dir: File,
    ) = runBlocking {
        var now = 10_000L
        val h = handlerWithClock(dir) { now }
        h.addToClineMessages(partialMsg(1L, "a")) // first save → index written, throttle window stamped at 10_000

        now = 10_700L // inside the 1s index throttle window
        h.addToClineMessages(partialMsg(2L, "b")) // index write skipped → globalIndexDirty = true

        // B15: the API append is the turn boundary — it must flush the dirty index immediately,
        // WITHOUT waiting for a later saveBoth().
        h.addToApiConversationHistory(apiMsg(30L, tokensIn = 100, tokensOut = 10, cost = 1.0, modelId = "model-a"))

        val item = MessageStateHandler.loadGlobalIndex(dir).first()
        assertEquals(100L, item.tokensIn, "B15: throttled-skipped index update must flush at the API turn boundary")
        assertEquals(10L, item.tokensOut)
        assertEquals(1.0, item.totalCost, 1e-9)
        assertEquals("model-a", item.modelId)
    }

    @Test
    fun `concurrent snapshot reads during mutation do not throw`(@TempDir dir: File) = runBlocking {
        val h = handler(dir)
        h.addToClineMessages(partialMsg(1L, "x"))
        val readers = (1..4).map {
            launch(Dispatchers.Default) {
                repeat(2_000) {
                    h.getClineMessages()
                    h.getApiConversationHistory()
                }
            }
        }
        repeat(500) { i -> h.updateLastPartialMessage("text-$i") }
        readers.forEach { it.join() }
        assertTrue(h.getClineMessages().isNotEmpty())
    }
}
