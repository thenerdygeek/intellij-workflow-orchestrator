package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Regression tests for the sub-agent sessions.json phantom-card bug.
 *
 * Phase 5 added per-sub-agent session persistence by constructing a
 * MessageStateHandler with sessionId = "$parentId/subagents/$agentId".
 * MessageStateHandler.updateGlobalIndex() must NOT write a HistoryItem
 * entry into sessions.json when the sessionId contains a slash — those
 * nested IDs would appear as phantom non-resumable cards in the history UI.
 *
 * Sub-agent conversation history (api_conversation_history.json + ui_messages.json)
 * is still persisted normally under sessions/$parentId/subagents/$agentId/ —
 * only the global index entry is suppressed.
 */
class MessageStateHandlerSubagentIndexFilterTest {

    @TempDir
    lateinit var tempDir: File

    private fun newHandler(sessionId: String, task: String = "task"): MessageStateHandler =
        MessageStateHandler(baseDir = tempDir, sessionId = sessionId, taskText = task)

    private fun userMsg(text: String) = ApiMessage(
        role = ApiRole.USER,
        content = listOf(ContentBlock.Text(text = text)),
        ts = System.currentTimeMillis(),
    )

    private fun assistantMsg(text: String) = ApiMessage(
        role = ApiRole.ASSISTANT,
        content = listOf(ContentBlock.Text(text = text)),
        ts = System.currentTimeMillis(),
    )

    private fun uiMsg(): UiMessage = UiMessage(
        ts = System.currentTimeMillis(),
        type = UiMessageType.SAY,
        say = UiSay.TEXT,
        text = "hello",
    )

    /**
     * Sub-agent sessions use sessionId = "$parentId/subagents/$agentId" (slash-containing).
     * updateGlobalIndex must skip the sessions.json write for such IDs.
     */
    @Test
    fun `updateGlobalIndex skips writing slash-containing sessionId entries`() = runTest {
        val handler = newHandler("parent-abc/subagents/agent-xyz", "sub-agent task")
        // Trigger both save paths that call updateGlobalIndex.
        handler.addToClineMessages(uiMsg())
        handler.addToApiConversationHistory(userMsg("start"))
        handler.addToApiConversationHistory(assistantMsg("done"))
        handler.saveBoth()

        val sessionsJson = File(tempDir, "sessions.json")
        if (sessionsJson.exists()) {
            val content = sessionsJson.readText()
            assertFalse(
                "parent-abc/subagents/agent-xyz" in content,
                "sessions.json must not contain the slash-ID. Content:\n$content"
            )
        }
        // sessions.json not existing is also acceptable (no write at all).
    }

    /**
     * Flat sessionIds (no slash) must still be written normally — no regression
     * for the main orchestrator agent.
     */
    @Test
    fun `updateGlobalIndex writes flat-id sessionId entries normally`() = runTest {
        val handler = newHandler("session-abc-123", "orchestrator task")
        handler.addToClineMessages(uiMsg())
        handler.addToApiConversationHistory(userMsg("start"))
        handler.addToApiConversationHistory(assistantMsg("reply"))
        handler.saveBoth()

        val sessionsJson = File(tempDir, "sessions.json")
        assertTrue(sessionsJson.exists(), "sessions.json should exist for a flat sessionId")
        assertTrue(
            "session-abc-123" in sessionsJson.readText(),
            "Flat sessionId must still appear in sessions.json (no regression)."
        )
    }

    /**
     * Source-text contract: updateGlobalIndex must contain a guard that returns
     * early when sessionId contains a slash character.
     */
    @Test
    fun `updateGlobalIndex source contains slash guard`() {
        val src = File(
            "src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt"
        ).readText()

        // Extract the body of updateGlobalIndex up to its closing brace.
        val fnStart = src.indexOf("private suspend fun updateGlobalIndex()")
        assertTrue(fnStart >= 0, "updateGlobalIndex not found in MessageStateHandler.kt")
        val fnBody = src.substring(fnStart, minOf(fnStart + 800, src.length))

        val hasSlashGuard = "contains('/')" in fnBody
                || "contains(\"/\")" in fnBody
                || ".indexOf('/')" in fnBody

        assertTrue(hasSlashGuard) {
            "updateGlobalIndex must guard against slash-containing sessionId. Snippet:\n$fnBody"
        }
    }
}
