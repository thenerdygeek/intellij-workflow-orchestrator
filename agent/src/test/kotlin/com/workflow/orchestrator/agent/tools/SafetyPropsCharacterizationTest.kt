package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.tools.builtin.CreateFileTool
import com.workflow.orchestrator.agent.tools.builtin.DeleteFileTool
import com.workflow.orchestrator.agent.tools.builtin.EditFileTool
import com.workflow.orchestrator.agent.tools.builtin.RevertFileTool
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Behavior-preservation lock for the safety-prop migration. The property values declared on
 * the concrete tools must reproduce EXACTLY the old hardcoded name sets in ApprovalPolicy /
 * AgentLoop. No-arg tools are instantiated directly; tools with heavy ctors are pinned by
 * source-text. Together these prove deleting the sets changes no behavior.
 */
class SafetyPropsCharacterizationTest {

    // ── Approval: requiresApproval + allowSessionApproval ──────────────────
    @Test
    fun `session-approvable file tools require approval and allow session`() {
        for (tool in listOf(EditFileTool(), CreateFileTool(), DeleteFileTool(), RevertFileTool())) {
            assertTrue(tool.requiresApproval, "${tool.name} requiresApproval")
            assertTrue(tool.allowSessionApproval, "${tool.name} allowSessionApproval")
        }
    }

    private fun src(rel: String) = File("src/main/kotlin/com/workflow/orchestrator/agent/$rel").readText()

    @Test
    fun `run_command requires per-invocation approval`() {
        val s = src("tools/builtin/RunCommandTool.kt")
        assertTrue(s.contains("override val requiresApproval = true"),
            "RunCommandTool must declare requiresApproval = true")
        assertTrue(s.contains("override val allowSessionApproval = false"),
            "RunCommandTool must declare allowSessionApproval = false (per-invocation only)")
    }

    // ── Hook exemption ─────────────────────────────────────────────────────
    @Test
    fun `task tools and ai_review declare hook exemption`() {
        for (rel in listOf(
            "tools/builtin/TaskCreateTool.kt",
            "tools/builtin/TaskUpdateTool.kt",
            "tools/builtin/TaskListTool.kt",
            "tools/builtin/TaskGetTool.kt",
            "tools/builtin/AiReviewTool.kt",
        )) {
            assertTrue(src(rel).contains("override val isHookExempt = true"),
                "$rel must declare isHookExempt = true")
        }
    }
}
