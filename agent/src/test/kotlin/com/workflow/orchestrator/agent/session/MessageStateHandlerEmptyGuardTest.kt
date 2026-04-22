package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MessageStateHandlerEmptyGuardTest {

    @TempDir lateinit var tempDir: File

    private fun newHandler(id: String = "s1"): MessageStateHandler =
        MessageStateHandler(baseDir = tempDir, sessionId = id, taskText = "test task")

    private fun emptyAssistant() = ApiMessage(
        role = ApiRole.ASSISTANT,
        content = emptyList(),
        ts = 1_000L,
    )

    private fun assistantWithText(text: String) = ApiMessage(
        role = ApiRole.ASSISTANT,
        content = listOf(ContentBlock.Text(text = text)),
        ts = 1_000L,
    )

    private fun assistantWithToolUse() = ApiMessage(
        role = ApiRole.ASSISTANT,
        content = listOf(ContentBlock.ToolUse(id = "t1", name = "read_file", input = "{}")),
        ts = 1_000L,
    )

    private fun userMsg(text: String) = ApiMessage(
        role = ApiRole.USER,
        content = listOf(ContentBlock.Text(text = text)),
        ts = 1_000L,
    )

    @Test
    fun `addToApiConversationHistory drops empty assistant writes`() = runTest {
        val handler = newHandler()
        handler.addToApiConversationHistory(userMsg("start"))
        handler.addToApiConversationHistory(emptyAssistant())
        val saved = handler.getApiConversationHistory()
        assertEquals(1, saved.size)
        assertEquals(ApiRole.USER, saved[0].role)
    }

    @Test
    fun `addToApiConversationHistory keeps assistant with text`() = runTest {
        val handler = newHandler()
        handler.addToApiConversationHistory(userMsg("start"))
        handler.addToApiConversationHistory(assistantWithText("thinking aloud"))
        val saved = handler.getApiConversationHistory()
        assertEquals(2, saved.size)
        assertEquals(ApiRole.ASSISTANT, saved[1].role)
    }

    @Test
    fun `addToApiConversationHistory keeps assistant with tool use`() = runTest {
        val handler = newHandler()
        handler.addToApiConversationHistory(userMsg("start"))
        handler.addToApiConversationHistory(assistantWithToolUse())
        val saved = handler.getApiConversationHistory()
        assertEquals(2, saved.size)
    }

    @Test
    fun `pruneTrailingEmptyAssistants removes trailing empties and rewrites file`() = runTest {
        val handler = newHandler()
        // Seed disk directly (bypassing the guard) to simulate pre-fix pollution.
        handler.setApiConversationHistory(listOf(
            userMsg("start"),
            assistantWithText("first step"),
            userMsg("next"),
            emptyAssistant(),
            emptyAssistant(),
        ))
        handler.saveBoth()

        val pruned = handler.pruneTrailingEmptyAssistants()
        assertEquals(2, pruned)

        val saved = handler.getApiConversationHistory()
        assertEquals(3, saved.size)

        // Verify on-disk file was rewritten.
        val apiFile = File(tempDir, "sessions/s1/api_conversation_history.json")
        assertTrue(apiFile.exists())
        val contents = apiFile.readText()
        // Empty-assistant has an empty content list; assert it's gone by message count.
        // (Simple string check — empty content serializes to `"content":[]`.)
        val emptyBlockCount = Regex("\"content\"\\s*:\\s*\\[\\s*\\]").findAll(contents).count()
        assertEquals(0, emptyBlockCount)
    }

    @Test
    fun `pruneTrailingEmptyAssistants returns 0 when tail is clean`() = runTest {
        val handler = newHandler()
        handler.addToApiConversationHistory(userMsg("start"))
        handler.addToApiConversationHistory(assistantWithText("reply"))
        assertEquals(0, handler.pruneTrailingEmptyAssistants())
    }
}
