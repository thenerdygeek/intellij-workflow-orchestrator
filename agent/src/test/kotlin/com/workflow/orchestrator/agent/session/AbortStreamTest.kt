package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for the abort/interrupt persistence path (Task 7, I5 fix).
 *
 * Exercises the same MessageStateHandler operations that AgentLoop.abortStream()
 * performs: flipping the last partial UI message to non-partial and appending a
 * synthetic assistant turn with an interrupt marker.
 */
class AbortStreamTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `abortStream flips last partial to false and appends interrupt marker`() = runTest {
        val handler = MessageStateHandler(baseDir = tempDir.toFile(), sessionId = "abort-test", taskText = "test")

        // Simulate streaming: add a partial message
        handler.addToClineMessages(UiMessage(
            ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT,
            text = "I'll edit the fi", partial = true
        ))

        // Simulate abort (mirrors AgentLoop.abortStream logic)
        val msgs = handler.getClineMessages()
        val lastIdx = msgs.lastIndex
        handler.updateClineMessage(lastIdx, msgs[lastIdx].copy(partial = false))
        handler.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text("I'll edit the fi\n\n[Response interrupted by user]"))
        ))
        handler.saveBoth()

        // Verify: last UI message is NOT partial
        val savedUi = MessageStateHandler.loadUiMessages(File(tempDir.toFile(), "sessions/abort-test"))
        assertFalse(savedUi.last().partial)

        // Verify: api_history ends with assistant turn containing interrupt marker
        val savedApi = MessageStateHandler.loadApiHistory(File(tempDir.toFile(), "sessions/abort-test"))
        val lastApi = savedApi.last()
        assertEquals(ApiRole.ASSISTANT, lastApi.role)
        val text = (lastApi.content.first() as ContentBlock.Text).text
        assertTrue(text.contains("[Response interrupted by user]"))
    }

    @Test
    fun `abortStream with streaming_failed uses API Error marker`() = runTest {
        val handler = MessageStateHandler(baseDir = tempDir.toFile(), sessionId = "abort-api-err", taskText = "test")
        handler.addToClineMessages(UiMessage(
            ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT,
            text = "partial", partial = true
        ))

        // Simulate abort with streaming_failed reason
        val msgs = handler.getClineMessages()
        handler.updateClineMessage(msgs.lastIndex, msgs.last().copy(partial = false))
        handler.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text("partial\n\n[Response interrupted by API Error]"))
        ))
        handler.saveBoth()

        // Verify: api_history ends with assistant turn containing API Error marker
        val savedApi = MessageStateHandler.loadApiHistory(File(tempDir.toFile(), "sessions/abort-api-err"))
        val lastApiText = (savedApi.last().content.first() as ContentBlock.Text).text
        assertTrue(lastApiText.contains("[Response interrupted by API Error]"))

        // Verify: UI message is not partial
        val savedUi = MessageStateHandler.loadUiMessages(File(tempDir.toFile(), "sessions/abort-api-err"))
        assertFalse(savedUi.last().partial)
    }
}
