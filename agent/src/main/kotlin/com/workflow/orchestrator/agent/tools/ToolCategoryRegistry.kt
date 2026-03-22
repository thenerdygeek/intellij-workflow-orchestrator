package com.workflow.orchestrator.agent.tools

/**
 * Central registry mapping all 86 agent tools to logical categories.
 * Used by: DynamicToolSelector (tool filtering), Tools panel UI (categorization),
 * RequestToolsTool (category activation), ToolPreferences (grouping).
 */
object ToolCategoryRegistry {

    data class ToolCategory(
        val id: String,
        val displayName: String,
        val color: String,        // CSS hex color for UI badges
        val badgePrefix: String,  // Short label for tool badges (READ, EDIT, CMD, etc.)
        val tools: List<String>,
        val alwaysActive: Boolean = false,
        val description: String = ""  // For request_tools meta-tool
    )

    val CATEGORIES = listOf(
        ToolCategory(
            id = "core",
            displayName = "Core",
            color = "#22C55E",
            badgePrefix = "",
            alwaysActive = true,
            description = "Essential coding tools: read, edit, search, command, diagnostics, format, imports, PSI navigation",
            tools = listOf(
                "read_file", "edit_file", "search_code", "run_command", "glob_files",
                "diagnostics", "format_code", "optimize_imports",
                "file_structure", "find_definition", "find_references",
                "type_hierarchy", "call_hierarchy",
                "delegate_task",
                "think"
            )
        ),
        ToolCategory(
            id = "ide",
            displayName = "IDE Intelligence",
            color = "#F59E0B",
            badgePrefix = "IDE",
            description = "Code inspections, refactoring, quick fixes, compilation, test execution, find implementations",
            tools = listOf("run_inspections", "refactor_rename", "list_quickfixes", "compile_module", "run_tests", "find_implementations")
        ),
        ToolCategory(
            id = "vcs",
            displayName = "VCS",
            color = "#06B6D4",
            badgePrefix = "GIT",
            description = "Git status, blame, diff, log, branches, show-file, show-commit, stash, merge-base, file-history",
            tools = listOf("git_status", "git_blame", "git_diff", "git_log", "git_branches", "git_show_file", "git_show_commit", "git_stash_list", "git_merge_base", "git_file_history")
        ),
        ToolCategory(
            id = "framework",
            displayName = "Spring & Framework",
            color = "#10B981",
            badgePrefix = "SPR",
            description = "Spring beans, endpoints, bean graph, config, JPA, Maven dependencies/properties/plugins/profiles, version info, Spring profiles, repositories, security, scheduled tasks, events",
            tools = listOf(
                "spring_context", "spring_endpoints", "spring_bean_graph", "spring_config",
                "jpa_entities", "project_modules",
                "maven_dependencies", "maven_properties", "maven_plugins", "maven_profiles",
                "spring_version_info", "spring_profiles", "spring_repositories",
                "spring_security_config", "spring_scheduled_tasks", "spring_event_listeners"
            )
        ),
        ToolCategory(
            id = "jira",
            displayName = "Jira",
            color = "#A855F7",
            badgePrefix = "JIRA",
            description = "Ticket details, transitions, comments, time logging, worklogs, sprints, boards, search, dev-status, start work",
            tools = listOf(
                "jira_get_ticket", "jira_get_transitions", "jira_transition", "jira_comment", "jira_get_comments", "jira_log_work",
                "jira_get_worklogs", "jira_get_sprints", "jira_get_linked_prs", "jira_get_boards",
                "jira_get_sprint_issues", "jira_get_board_issues", "jira_search_issues",
                "jira_get_dev_branches", "jira_start_work"
            )
        ),
        ToolCategory(
            id = "bamboo",
            displayName = "CI/CD — Bamboo",
            color = "#EF4444",
            badgePrefix = "CI",
            description = "Build status, trigger builds, build logs, test results, stop/cancel builds, artifacts, plans, branches, variables",
            tools = listOf(
                "bamboo_build_status", "bamboo_get_build", "bamboo_trigger_build", "bamboo_get_build_log", "bamboo_get_test_results",
                "bamboo_stop_build", "bamboo_cancel_build", "bamboo_get_artifacts", "bamboo_recent_builds",
                "bamboo_get_plans", "bamboo_get_project_plans", "bamboo_search_plans", "bamboo_get_plan_branches",
                "bamboo_get_running_builds", "bamboo_get_build_variables", "bamboo_get_plan_variables",
                "bamboo_rerun_failed_jobs", "bamboo_trigger_stage"
            )
        ),
        ToolCategory(
            id = "sonar",
            displayName = "Quality — SonarQube",
            color = "#EC4899",
            badgePrefix = "QA",
            description = "Code issues, quality gate status, coverage metrics, project search, branches, measures, source lines",
            tools = listOf(
                "sonar_issues", "sonar_quality_gate", "sonar_coverage", "sonar_search_projects", "sonar_analysis_tasks",
                "sonar_branches", "sonar_project_measures", "sonar_source_lines", "sonar_issues_paged"
            )
        ),
        ToolCategory(
            id = "runtime_debug",
            displayName = "Runtime & Debug",
            color = "#E91E63",
            badgePrefix = "DBG",
            description = "Run output, test results, breakpoints, interactive debugging, expression evaluation, run configuration management",
            tools = listOf(
                "get_run_configurations", "get_running_processes", "get_run_output", "get_test_results",
                "add_breakpoint", "remove_breakpoint", "list_breakpoints",
                "start_debug_session", "get_debug_state",
                "debug_step_over", "debug_step_into", "debug_step_out",
                "debug_resume", "debug_pause", "debug_run_to_cursor", "debug_stop",
                "evaluate_expression", "get_stack_frames", "get_variables",
                "create_run_config", "modify_run_config", "delete_run_config"
            )
        ),
        ToolCategory(
            id = "bitbucket",
            displayName = "Pull Requests — Bitbucket",
            color = "#3B82F6",
            badgePrefix = "PR",
            description = "Pull requests: create, review, merge, decline, approve, comment, diff, branches, users, build status",
            tools = listOf(
                "bitbucket_create_pr", "bitbucket_get_pr_commits", "bitbucket_add_inline_comment",
                "bitbucket_reply_to_comment", "bitbucket_set_reviewer_status", "bitbucket_get_file_content",
                "bitbucket_add_reviewer", "bitbucket_update_pr_title", "bitbucket_get_branches",
                "bitbucket_create_branch", "bitbucket_search_users", "bitbucket_get_my_prs",
                "bitbucket_get_reviewing_prs", "bitbucket_get_pr_detail", "bitbucket_get_pr_activities",
                "bitbucket_get_pr_changes", "bitbucket_get_pr_diff", "bitbucket_get_build_statuses",
                "bitbucket_approve_pr", "bitbucket_merge_pr", "bitbucket_decline_pr",
                "bitbucket_update_pr_description", "bitbucket_add_pr_comment", "bitbucket_check_merge_status",
                "bitbucket_remove_reviewer", "bitbucket_list_repos"
            )
        ),
        ToolCategory(
            id = "planning",
            displayName = "Planning",
            color = "#F59E0B",
            badgePrefix = "PLAN",
            description = "Create and update implementation plans",
            tools = listOf("create_plan", "update_plan_step", "ask_questions", "save_memory", "activate_skill", "deactivate_skill")
        )
    )

    fun getCategoryForTool(toolName: String): ToolCategory? =
        CATEGORIES.find { toolName in it.tools }

    fun getActivatableCategories(): List<ToolCategory> =
        CATEGORIES.filter { !it.alwaysActive }

    fun getToolsInCategory(categoryId: String): List<String> =
        CATEGORIES.find { it.id == categoryId }?.tools ?: emptyList()

    fun getAllToolNames(): Set<String> =
        CATEGORIES.flatMap { it.tools }.toSet()

    fun getAlwaysActiveTools(): Set<String> =
        CATEGORIES.filter { it.alwaysActive }.flatMap { it.tools }.toSet()
}
