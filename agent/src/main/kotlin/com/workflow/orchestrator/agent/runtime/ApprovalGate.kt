package com.workflow.orchestrator.agent.runtime

/**
 * Risk levels for agent actions. Determines whether user approval is needed.
 */
enum class RiskLevel {
    NONE,        // Read-only: auto-approve always
    LOW,         // Non-destructive write (Jira comment): configurable
    MEDIUM,      // File edit: show diff, require accept/reject
    HIGH,        // Shell command, PR creation: always require approval
    DESTRUCTIVE  // File deletion, force push: block + typed confirmation
}

/**
 * Result of an approval check.
 */
sealed class ApprovalResult {
    object Approved : ApprovalResult()
    object Rejected : ApprovalResult()
    data class Pending(val description: String, val riskLevel: RiskLevel) : ApprovalResult()
}

/**
 * Gates agent actions based on risk level and user preferences.
 *
 * The approval gate checks each tool action against the user's configured
 * autonomy level and either auto-approves, queues for approval, or blocks.
 */
class ApprovalGate(
    private val approvalRequired: Boolean = true,  // from AgentSettings
    private val onApprovalNeeded: ((String, RiskLevel) -> ApprovalResult)? = null  // callback for UI
) {
    /**
     * Check if an action should proceed.
     * @param toolName The tool being called
     * @param description Human-readable description of what the tool will do
     * @param riskLevel The risk level of this action
     * @return ApprovalResult indicating whether to proceed
     */
    fun check(toolName: String, description: String, riskLevel: RiskLevel): ApprovalResult {
        // Read-only actions always proceed
        if (riskLevel == RiskLevel.NONE) return ApprovalResult.Approved

        // If approval not required in settings, auto-approve LOW and MEDIUM
        if (!approvalRequired && riskLevel <= RiskLevel.MEDIUM) return ApprovalResult.Approved

        // HIGH and DESTRUCTIVE always require approval regardless of settings
        if (riskLevel >= RiskLevel.HIGH || (approvalRequired && riskLevel >= RiskLevel.MEDIUM)) {
            return onApprovalNeeded?.invoke(description, riskLevel)
                ?: ApprovalResult.Rejected  // BLOCK if no callback — safer default
        }

        return ApprovalResult.Approved
    }

    companion object {
        /** Determine risk level for a given tool. */
        fun riskLevelFor(toolName: String): RiskLevel = when (toolName) {
            // Read-only tools
            "read_file", "search_code", "find_references", "find_definition",
            "type_hierarchy", "call_hierarchy", "file_structure",
            "spring_context", "spring_endpoints", "spring_bean_graph" -> RiskLevel.NONE

            // File diagnostics (read-only)
            "diagnostics" -> RiskLevel.NONE

            // Enterprise read operations
            "jira_get_ticket", "bamboo_build", "sonar_issues" -> RiskLevel.NONE

            // Low risk writes
            "jira_comment" -> RiskLevel.LOW

            // Medium risk: file modifications
            "edit_file" -> RiskLevel.MEDIUM

            // High risk: status changes, PRs, commands
            "jira_transition", "bitbucket_create_pr", "run_command" -> RiskLevel.HIGH

            // Unknown tools default to HIGH
            else -> RiskLevel.HIGH
        }
    }
}
