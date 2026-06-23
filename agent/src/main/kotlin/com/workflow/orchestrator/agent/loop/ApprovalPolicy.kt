package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.tools.AgentTool

/**
 * Per-tool approval policy, derived from the tool's self-declared safety properties.
 *
 * `requiresApproval` trips the loop-level approval gate; `allowSessionApproval` decides whether
 * the user may "Allow for the session". (`run_command` declares `allowSessionApproval = false`
 * because each command is arbitrarily different — approving one `ls` must not auto-approve
 * `rm -rf /`.) The hardcoded ALWAYS_PER_INVOCATION / SESSION_APPROVABLE name sets were removed
 * in Phase 0b-3 — a tool now carries its own policy, so a depending plugin can contribute an
 * approval-gated write tool.
 */
data class ApprovalPolicy(
    val requiresApproval: Boolean,
    val allowSessionApproval: Boolean,
) {
    companion object {
        fun forTool(tool: AgentTool): ApprovalPolicy =
            ApprovalPolicy(
                requiresApproval = tool.requiresApproval,
                allowSessionApproval = tool.allowSessionApproval,
            )
    }
}
