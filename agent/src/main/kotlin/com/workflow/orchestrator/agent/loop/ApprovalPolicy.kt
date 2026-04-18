package com.workflow.orchestrator.agent.loop

/**
 * Per-tool approval policy. Determines whether a tool requires user approval
 * and whether the user can grant session-wide approval for it.
 *
 * `run_command` is always per-invocation because each command can be arbitrarily
 * different — approving one `ls` doesn't mean `rm -rf /` should be auto-approved.
 */
data class ApprovalPolicy(
    val requiresApproval: Boolean,
    val allowSessionApproval: Boolean,
) {
    companion object {
        /** Tools that always require per-invocation approval (no "allow for session"). */
        private val ALWAYS_PER_INVOCATION = setOf("run_command")

        /** Tools that require approval but can be allowed for the session. */
        private val SESSION_APPROVABLE = setOf("edit_file", "create_file", "revert_file")

        /** All tools that go through the approval gate. */
        val APPROVAL_TOOLS = ALWAYS_PER_INVOCATION + SESSION_APPROVABLE

        fun forTool(toolName: String): ApprovalPolicy = when (toolName) {
            in ALWAYS_PER_INVOCATION -> ApprovalPolicy(requiresApproval = true, allowSessionApproval = false)
            in SESSION_APPROVABLE -> ApprovalPolicy(requiresApproval = true, allowSessionApproval = true)
            else -> ApprovalPolicy(requiresApproval = false, allowSessionApproval = false)
        }
    }
}
