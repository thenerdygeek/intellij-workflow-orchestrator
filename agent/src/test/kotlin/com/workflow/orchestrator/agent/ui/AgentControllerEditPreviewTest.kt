// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Test

/**
 * Source-text pins for the approval-gate edit_file preview contract.
 *
 * The full behavioural test (mocking dashboard + project + EDT + suspend) requires a
 * dedicated seam in AgentController. These pins anchor the three invariants that the
 * production code MUST honor; they fail loudly if a future refactor deletes the
 * preview hook, reintroduces the naive snippet-only diff, or stops short-circuiting
 * on ValidationFailed.
 */
class AgentControllerEditPreviewTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
        ).readText()
    }

    @Test
    fun `approvalGate calls EditFileTool preview for edit_file`() {
        // The approval gate must call into EditFileTool.preview before showing the card,
        // so an unsatisfiable edit (path missing, no match, ambiguous match) never reaches
        // the user as a phantom diff.
        assert("EditFileTool.preview" in src) {
            "approvalGate must call EditFileTool.preview() to pre-validate edit_file before " +
                "showing the approval card. Without this, the user is asked to approve edits " +
                "that execute() will then reject as not-found / not-unique."
        }
    }

    @Test
    fun `approvalGate short-circuits with APPROVED on ValidationFailed`() {
        // When preview() returns ValidationFailed, the gate must NOT show a card and
        // must return APPROVED so execute() runs, fails, and surfaces the real error to
        // the LLM. (Showing the card would block on the user for a phantom approval.)
        assert("ValidationFailed" in src) {
            "approvalGate must handle EditFileTool.EditPreview.ValidationFailed."
        }
        assert("ApprovalResult.APPROVED" in src) {
            "approvalGate must return ApprovalResult.APPROVED on ValidationFailed " +
                "so execute() surfaces the error to the LLM without bothering the user."
        }
    }

    @Test
    fun `approvalGate uses real file-anchored diff when preview is Ready`() {
        // When preview() returns Ready, the gate must use Ready.realDiff for the
        // approval card — NOT the naive DiffUtil.unifiedDiff(oldString, newString, path)
        // snippet-only diff (which always renders @@ -1,N +1,M @@).
        assert(Regex("""EditPreview\.Ready""").containsMatchIn(src)) {
            "approvalGate must branch on EditFileTool.EditPreview.Ready and read its realDiff."
        }
        assert("realDiff" in src) {
            "approvalGate must use EditPreview.Ready.realDiff for the approval card so the @@ " +
                "hunk header carries the real match offset (not 1,N)."
        }
    }

    @Test
    fun `parsedArgs is hoisted above the pendingApproval reentry guard`() {
        // Critical ordering invariant per the spec: the preview check (which depends on
        // parsedArgs) must happen BEFORE `pendingApproval = deferred` is assigned.
        // Otherwise the reentry guard gets out of sync when preview returns ValidationFailed
        // and we short-circuit without ever installing a deferred.
        val parsedArgsIdx = src.indexOf("val parsedArgs")
        val pendingAssignIdx = src.indexOf("pendingApproval = deferred")
        assert(parsedArgsIdx > 0 && pendingAssignIdx > 0) {
            "Could not locate both `val parsedArgs` and `pendingApproval = deferred` " +
                "in AgentController.kt — has the structure changed?"
        }
        assert(parsedArgsIdx < pendingAssignIdx) {
            "`val parsedArgs` (idx=$parsedArgsIdx) must be hoisted ABOVE " +
                "`pendingApproval = deferred` (idx=$pendingAssignIdx). The preview check " +
                "depends on parsedArgs and must short-circuit before the reentry guard fires."
        }
    }
}
