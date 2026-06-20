package com.workflow.orchestrator.agent.security

sealed interface AutoApproveReason {
    data object Safe : AutoApproveReason
    data class SessionRule(val prefixes: List<String>) : AutoApproveReason
}

sealed interface ApprovalDecision {
    data class Skip(val reason: AutoApproveReason) : ApprovalDecision
    data object Prompt : ApprovalDecision
}

/**
 * The single decision point for run_command auto-approval. Part A (SAFE toggle) and
 * Part B (session prefix allowlist) are both inputs here. This is also the future
 * insertion point for user-editable allow/deny lists (add deny params + longest-prefix
 * tie-break in CommandShape.coveringPrefixes; no other call site changes).
 */
object CommandApprovalDecision {
    fun evaluate(
        command: String,
        risk: CommandRisk,
        autoApproveSafe: Boolean,
        sessionAllowedPrefixes: Set<String>,
    ): ApprovalDecision {
        if (command.isBlank()) return ApprovalDecision.Prompt
        if (risk == CommandRisk.DANGEROUS) return ApprovalDecision.Prompt
        if (!CommandShape.isAutoApprovable(command)) return ApprovalDecision.Prompt
        if (autoApproveSafe && risk == CommandRisk.SAFE) {
            return ApprovalDecision.Skip(AutoApproveReason.Safe)
        }
        val matched = CommandShape.coveringPrefixes(command, sessionAllowedPrefixes)
        if (matched != null) return ApprovalDecision.Skip(AutoApproveReason.SessionRule(matched))
        return ApprovalDecision.Prompt
    }
}
