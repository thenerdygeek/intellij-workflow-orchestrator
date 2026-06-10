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
