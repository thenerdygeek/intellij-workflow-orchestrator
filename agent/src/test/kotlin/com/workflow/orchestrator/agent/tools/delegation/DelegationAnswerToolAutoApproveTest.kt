package com.workflow.orchestrator.agent.tools.delegation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source-text pin tests for the auto-approve gate added to [DelegationAnswerTool] (F1 fix).
 *
 * These tests verify structural invariants without needing a live IntelliJ Project.
 * They pin the presence of the approval-gate code paths so the dead-code regression
 * (autoApproveDelegationAnswers was declared but never read) cannot silently recur.
 */
class DelegationAnswerToolAutoApproveTest {

    private val source: String = Files.readString(
        Path.of("src/main/kotlin/com/workflow/orchestrator/agent/tools/delegation/DelegationAnswerTool.kt")
    )

    private val dialogSource: String = Files.readString(
        Path.of("src/main/kotlin/com/workflow/orchestrator/agent/delegation/ui/DelegationAnswerConfirmDialog.kt")
    )

    @Test
    fun `tool reads autoApproveDelegationAnswers setting`() {
        assertTrue(
            source.contains("autoApproveDelegationAnswers"),
            "DelegationAnswerTool must read the autoApproveDelegationAnswers setting"
        )
    }

    @Test
    fun `tool shows DelegationAnswerConfirmDialog when auto-approve is off`() {
        assertTrue(
            source.contains("DelegationAnswerConfirmDialog"),
            "DelegationAnswerTool must reference DelegationAnswerConfirmDialog for the manual confirm path"
        )
    }

    @Test
    fun `tool returns error when user declines the confirm dialog`() {
        assertTrue(
            source.contains("user declined to send the answer"),
            "DelegationAnswerTool must return a distinct error when the user cancels the dialog"
        )
    }

    @Test
    fun `tool calls sendAnswer with finalAnswer not raw answerText`() {
        // The gate may edit the answer — the variable forwarded to sendAnswer must
        // be the post-dialog value, not the raw input.
        assertTrue(
            source.contains("sendAnswer(handleId, questionId, finalAnswer"),
            "sendAnswer must be called with finalAnswer (post-dialog), not raw answerText"
        )
    }

    @Test
    fun `DelegationAnswerConfirmDialog exposes editedAnswer property`() {
        assertTrue(
            dialogSource.contains("editedAnswer"),
            "DelegationAnswerConfirmDialog must expose an editedAnswer property for the caller"
        )
    }

    @Test
    fun `DelegationAnswerConfirmDialog title contains confirm and delegated session`() {
        assertTrue(
            dialogSource.contains("Confirm Answer to Delegated Session"),
            "Dialog title must mention 'Confirm Answer to Delegated Session'"
        )
    }

    @Test
    fun `tool uses Dispatchers EDT to show the dialog`() {
        assertTrue(
            source.contains("Dispatchers.EDT"),
            "Dialog must be shown on the EDT"
        )
    }
}
