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
        /**
         * Tools that are ALWAYS NONE risk — auto-approved, no dialog.
         * These are read-only tools, meta-tools, and reasoning tools.
         */
        private val NONE_RISK_TOOLS = setOf(
            // Meta-tools (no side effects at all)
            "request_tools", "think", "activate_skill", "deactivate_skill",

            // Planning (creates plan context, doesn't modify files)
            "create_plan", "update_plan_step", "ask_questions",

            // Memory (writes to .workflow/ dir, not project files)
            "save_memory",

            // Core read-only
            "read_file", "search_code", "glob_files", "file_structure",
            "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
            "find_implementations", "diagnostics",

            // Git read-only
            "git_status", "git_diff", "git_log", "git_blame",
            "git_branches", "git_show_file", "git_show_commit",
            "git_stash_list", "git_merge_base", "git_file_history",

            // PSI/Spring read-only
            "spring_context", "spring_endpoints", "spring_bean_graph",
            "spring_config", "jpa_entities", "project_modules",
            "maven_dependencies", "maven_properties", "maven_plugins",
            "maven_profiles", "spring_version_info", "spring_profiles",
            "spring_repositories", "spring_security_config",
            "spring_scheduled_tasks", "spring_event_listeners",

            // Enterprise read-only (Jira)
            "jira_get_ticket", "jira_get_transitions", "jira_get_comments",
            "jira_get_worklogs", "jira_get_sprints", "jira_get_linked_prs",
            "jira_get_boards", "jira_get_sprint_issues", "jira_get_board_issues",
            "jira_search_issues", "jira_get_dev_branches",

            // Enterprise read-only (Bamboo)
            "bamboo_build_status", "bamboo_get_build", "bamboo_get_build_log",
            "bamboo_get_test_results", "bamboo_recent_builds",
            "bamboo_get_plans", "bamboo_get_project_plans", "bamboo_search_plans",
            "bamboo_get_plan_branches", "bamboo_get_running_builds",
            "bamboo_get_build_variables", "bamboo_get_plan_variables", "bamboo_get_artifacts",

            // Enterprise read-only (Sonar)
            "sonar_issues", "sonar_quality_gate", "sonar_coverage",
            "sonar_search_projects", "sonar_analysis_tasks", "sonar_project_health",
            "sonar_branches", "sonar_project_measures", "sonar_source_lines", "sonar_issues_paged",

            // Enterprise read-only (Bitbucket)
            "bitbucket_get_pr_commits", "bitbucket_get_file_content",
            "bitbucket_get_branches", "bitbucket_get_my_prs",
            "bitbucket_get_reviewing_prs", "bitbucket_get_pr_detail",
            "bitbucket_get_pr_activities", "bitbucket_get_pr_changes",
            "bitbucket_get_pr_diff", "bitbucket_get_build_statuses",
            "bitbucket_check_merge_status", "bitbucket_search_users",

            // Subagent spawning (runs in isolated context)
            "agent", "delegate_task"
        )

        /**
         * Tools that are LOW risk — auto-approved unless user has strict settings.
         * These create content but don't modify existing project files.
         */
        private val LOW_RISK_TOOLS = setOf(
            // Jira writes (non-destructive)
            "jira_comment", "jira_log_work",

            // Bitbucket comments (non-destructive)
            "bitbucket_add_inline_comment", "bitbucket_reply_to_comment",
            "bitbucket_add_reviewer",

            // IDE non-destructive
            "format_code", "optimize_imports",

            // Bamboo read-like (getting artifacts, not modifying)
            "jira_start_work"
        )

        /**
         * Tools that are MEDIUM risk — require approval when setting is enabled.
         * These modify project files or code.
         */
        private val MEDIUM_RISK_TOOLS = setOf(
            "edit_file",
            "refactor_rename",
            "bitbucket_update_pr_title", "bitbucket_update_pr_description",
            "bitbucket_remove_reviewer",
            "jira_transition"
        )

        // Everything else is HIGH: run_command, bitbucket_create_pr, bitbucket_merge_pr,
        // bitbucket_approve_pr, bitbucket_decline_pr, bamboo_trigger_build,
        // bamboo_stop_build, bamboo_cancel_build, etc.

        /** Determine risk level for a given tool. */
        fun riskLevelFor(toolName: String): RiskLevel = when {
            toolName in NONE_RISK_TOOLS -> RiskLevel.NONE
            toolName in LOW_RISK_TOOLS -> RiskLevel.LOW
            toolName in MEDIUM_RISK_TOOLS -> RiskLevel.MEDIUM
            else -> RiskLevel.HIGH
        }
    }
}
