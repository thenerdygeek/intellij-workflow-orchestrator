package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class MessageStateHandlerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun handler(sessionId: String = "test-session"): MessageStateHandler {
        return MessageStateHandler(
            baseDir = tempDir.toFile(),
            sessionId = sessionId,
            taskText = "Fix the login bug"
        )
    }

    @Test
    fun `addToClineMessages persists to ui_messages json`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(
            ts = 1000L,
            type = UiMessageType.SAY,
            say = UiSay.TEXT,
            text = "Hello"
        ))
        val file = File(tempDir.toFile(), "sessions/test-session/ui_messages.json")
        assertTrue(file.exists())
        val content = file.readText()
        assertTrue(content.contains("Hello"))
    }

    @Test
    fun `addToApiConversationHistory persists to api_conversation_history json`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.Text("Fix the bug"))
        ))
        val file = File(tempDir.toFile(), "sessions/test-session/api_conversation_history.json")
        assertTrue(file.exists())
        val content = file.readText()
        assertTrue(content.contains("Fix the bug"))
    }

    @Test
    fun `conversationHistoryIndex is set correctly on addToClineMessages`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("msg1"))))
        h.addToApiConversationHistory(ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("msg2"))))
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "bubble"))
        val msgs = h.getClineMessages()
        assertEquals(1, msgs.last().conversationHistoryIndex) // apiHistory.size - 1 = 2 - 1 = 1
    }

    @Test
    fun `conversationHistoryIndex is null when apiHistory is empty`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "first"))
        val msgs = h.getClineMessages()
        assertNull(msgs.last().conversationHistoryIndex, "conversationHistoryIndex must be null when apiHistory is empty")
    }

    @Test
    fun `updateClineMessage updates and persists`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "partial", partial = true))
        h.updateClineMessage(0, h.getClineMessages()[0].copy(text = "partial complete", partial = false))
        val msgs = h.getClineMessages()
        assertEquals("partial complete", msgs[0].text)
        assertFalse(msgs[0].partial)
    }

    @Test
    fun `sessions json is updated with HistoryItem on every save`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "hello"))
        val indexFile = File(tempDir.toFile(), "sessions.json")
        assertTrue(indexFile.exists())
        val content = indexFile.readText()
        assertTrue(content.contains("test-session"))
        assertTrue(content.contains("Fix the login bug"))
    }

    @Test
    fun `concurrent writes do not corrupt files`() = runTest {
        val h = handler()
        val jobs = (1..50).map { i ->
            async {
                h.addToClineMessages(UiMessage(ts = i.toLong(), type = UiMessageType.SAY, say = UiSay.TEXT, text = "msg-$i"))
            }
        }
        awaitAll(*jobs.toTypedArray())
        assertEquals(50, h.getClineMessages().size)
    }

    @Test
    fun `overwriteApiConversationHistory replaces all messages`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("old"))))
        h.overwriteApiConversationHistory(listOf(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("new1"))),
            ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("new2")))
        ))
        assertEquals(2, h.getApiConversationHistory().size)
        val file = File(tempDir.toFile(), "sessions/test-session/api_conversation_history.json")
        val content = file.readText()
        assertFalse(content.contains("old"))
        assertTrue(content.contains("new1"))
    }

    @Test
    fun `overwriteClineMessages replaces all messages`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "old"))
        h.overwriteClineMessages(listOf(
            UiMessage(ts = 2000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "replaced")
        ))
        assertEquals(1, h.getClineMessages().size)
        assertEquals("replaced", h.getClineMessages()[0].text)
    }

    @Test
    fun `deleteClineMessage removes message and persists`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "keep"))
        h.addToClineMessages(UiMessage(ts = 2000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "remove"))
        h.deleteClineMessage(1)
        assertEquals(1, h.getClineMessages().size)
        assertEquals("keep", h.getClineMessages()[0].text)
    }

    @Test
    fun `setClineMessages is init-only and sets messages without persistence`() {
        val h = handler()
        h.setClineMessages(listOf(
            UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "loaded")
        ))
        assertEquals(1, h.getClineMessages().size)
        assertEquals("loaded", h.getClineMessages()[0].text)
    }

    @Test
    fun `setApiConversationHistory is init-only and sets messages without persistence`() {
        val h = handler()
        h.setApiConversationHistory(listOf(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("loaded")))
        ))
        assertEquals(1, h.getApiConversationHistory().size)
    }

    @Test
    fun `saveBoth persists both files and global index`() = runTest {
        val h = handler()
        h.setClineMessages(listOf(
            UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "ui-msg")
        ))
        h.setApiConversationHistory(listOf(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("api-msg")))
        ))
        h.saveBoth()
        val uiFile = File(tempDir.toFile(), "sessions/test-session/ui_messages.json")
        val apiFile = File(tempDir.toFile(), "sessions/test-session/api_conversation_history.json")
        val indexFile = File(tempDir.toFile(), "sessions.json")
        assertTrue(uiFile.exists())
        assertTrue(apiFile.exists())
        assertTrue(indexFile.exists())
        assertTrue(uiFile.readText().contains("ui-msg"))
        assertTrue(apiFile.readText().contains("api-msg"))
    }

    @Test
    fun `loadUiMessages returns empty list for missing file`() {
        val sessionDir = File(tempDir.toFile(), "sessions/nonexistent")
        assertEquals(emptyList<UiMessage>(), MessageStateHandler.loadUiMessages(sessionDir))
    }

    @Test
    fun `loadApiHistory returns empty list for missing file`() {
        val sessionDir = File(tempDir.toFile(), "sessions/nonexistent")
        assertEquals(emptyList<ApiMessage>(), MessageStateHandler.loadApiHistory(sessionDir))
    }

    @Test
    fun `loadGlobalIndex returns empty list for missing file`() {
        assertEquals(emptyList<HistoryItem>(), MessageStateHandler.loadGlobalIndex(tempDir.toFile()))
    }

    @Test
    fun `loadUiMessages round-trips persisted data`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "persist-test"))
        val sessionDir = File(tempDir.toFile(), "sessions/test-session")
        val loaded = MessageStateHandler.loadUiMessages(sessionDir)
        assertEquals(1, loaded.size)
        assertEquals("persist-test", loaded[0].text)
    }

    @Test
    fun `loadApiHistory round-trips persisted data`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("api-persist"))))
        val sessionDir = File(tempDir.toFile(), "sessions/test-session")
        val loaded = MessageStateHandler.loadApiHistory(sessionDir)
        assertEquals(1, loaded.size)
        assertEquals(ApiRole.USER, loaded[0].role)
    }

    @Test
    fun `loadGlobalIndex round-trips persisted data`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "index-test"))
        val loaded = MessageStateHandler.loadGlobalIndex(tempDir.toFile())
        assertEquals(1, loaded.size)
        assertEquals("test-session", loaded[0].id)
    }

    @Test
    fun `global index updates existing session instead of duplicating`() = runTest {
        val h = handler()
        h.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "first"))
        h.addToClineMessages(UiMessage(ts = 2000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "second"))
        val loaded = MessageStateHandler.loadGlobalIndex(tempDir.toFile())
        assertEquals(1, loaded.size, "Should not duplicate session entries in global index")
    }

    @Test
    fun `multiple sessions coexist in global index`() = runTest {
        val h1 = handler("session-1")
        val h2 = handler("session-2")
        h1.addToClineMessages(UiMessage(ts = 1000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "from-1"))
        h2.addToClineMessages(UiMessage(ts = 2000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "from-2"))
        val loaded = MessageStateHandler.loadGlobalIndex(tempDir.toFile())
        assertEquals(2, loaded.size)
        assertTrue(loaded.any { it.id == "session-1" })
        assertTrue(loaded.any { it.id == "session-2" })
    }

    // ---- rewriteMostRecentToolResult tests ----

    @Test
    fun `rewriteMostRecentToolResult rewrites matching tool result and returns true`() = runTest {
        val h = handler()
        // ASSISTANT message with a ToolUse
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.ToolUse(id = "t1", name = "plan_mode_respond", input = "{}"))
        ))
        // USER message with matching ToolResult
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.ToolResult(toolUseId = "t1", content = "Original plan content", isError = false))
        ))

        val rewritten = h.rewriteMostRecentToolResult("plan_mode_respond", "[discarded]")

        assertTrue(rewritten, "should return true when a matching tool result is found")
        val history = h.getApiConversationHistory()
        val userMsg = history.last { it.role == ApiRole.USER }
        val toolResult = userMsg.content.filterIsInstance<ContentBlock.ToolResult>().first()
        assertEquals("[discarded]", toolResult.content)
    }

    @Test
    fun `rewriteMostRecentToolResult returns false when no match found`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.Text("some user message"))
        ))

        val rewritten = h.rewriteMostRecentToolResult("nonexistent_tool", "[discarded]")

        assertFalse(rewritten, "should return false when no matching tool result exists")
    }

    @Test
    fun `rewriteMostRecentToolResult returns false on empty history`() = runTest {
        val h = handler()

        val rewritten = h.rewriteMostRecentToolResult("plan_mode_respond", "[discarded]")

        assertFalse(rewritten, "should return false on empty history")
        assertEquals(0, h.getApiConversationHistory().size, "history should remain empty")
    }

    @Test
    fun `rewriteMostRecentToolResult rewrites only the most recent match when multiple exist`() = runTest {
        val h = handler()
        // First assistant + user pair (id t1)
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.ToolUse(id = "t1", name = "plan_mode_respond", input = "{}"))
        ))
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.ToolResult(toolUseId = "t1", content = "First plan", isError = false))
        ))
        // Second assistant + user pair (id t2)
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.ToolUse(id = "t2", name = "plan_mode_respond", input = "{}"))
        ))
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.ToolResult(toolUseId = "t2", content = "Second plan", isError = false))
        ))

        val rewritten = h.rewriteMostRecentToolResult("plan_mode_respond", "[discarded]")

        assertTrue(rewritten, "should return true when a matching tool result is found")
        val history = h.getApiConversationHistory()

        // The most recent USER message (t2) should be rewritten
        val lastUserMsg = history.last { it.role == ApiRole.USER }
        val lastResult = lastUserMsg.content.filterIsInstance<ContentBlock.ToolResult>().first()
        assertEquals("[discarded]", lastResult.content, "most recent tool result should be rewritten")

        // The first USER message (t1) should be unchanged
        val firstUserMsg = history.first { it.role == ApiRole.USER }
        val firstResult = firstUserMsg.content.filterIsInstance<ContentBlock.ToolResult>().first()
        assertEquals("First plan", firstResult.content, "earlier tool result should NOT be rewritten")
    }
}
