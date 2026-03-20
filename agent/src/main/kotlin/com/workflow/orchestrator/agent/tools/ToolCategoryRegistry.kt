package com.workflow.orchestrator.agent.tools

/**
 * Central registry mapping all 44 agent tools to logical categories.
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
                "read_file", "edit_file", "search_code", "run_command",
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
            description = "Code inspections, refactoring, quick fixes, compilation, test execution",
            tools = listOf("run_inspections", "refactor_rename", "list_quickfixes", "compile_module", "run_tests")
        ),
        ToolCategory(
            id = "vcs",
            displayName = "VCS & Navigation",
            color = "#06B6D4",
            badgePrefix = "GIT",
            description = "Git status, blame, and method implementation search",
            tools = listOf("git_status", "git_blame", "find_implementations")
        ),
        ToolCategory(
            id = "framework",
            displayName = "Spring & Framework",
            color = "#10B981",
            badgePrefix = "SPR",
            description = "Spring beans, endpoints, bean graph, config, JPA entities, project modules",
            tools = listOf("spring_context", "spring_endpoints", "spring_bean_graph", "spring_config", "jpa_entities", "project_modules")
        ),
        ToolCategory(
            id = "jira",
            displayName = "Jira",
            color = "#A855F7",
            badgePrefix = "JIRA",
            description = "Ticket details, transitions, comments, time logging",
            tools = listOf("jira_get_ticket", "jira_get_transitions", "jira_transition", "jira_comment", "jira_get_comments", "jira_log_work")
        ),
        ToolCategory(
            id = "bamboo",
            displayName = "CI/CD — Bamboo",
            color = "#EF4444",
            badgePrefix = "CI",
            description = "Build status, trigger builds, build logs, test results",
            tools = listOf("bamboo_build_status", "bamboo_get_build", "bamboo_trigger_build", "bamboo_get_build_log", "bamboo_get_test_results")
        ),
        ToolCategory(
            id = "sonar",
            displayName = "Quality — SonarQube",
            color = "#EC4899",
            badgePrefix = "QA",
            description = "Code issues, quality gate status, coverage metrics, project search",
            tools = listOf("sonar_issues", "sonar_quality_gate", "sonar_coverage", "sonar_search_projects", "sonar_analysis_tasks", "sonar_project_health")
        ),
        ToolCategory(
            id = "bitbucket",
            displayName = "Pull Requests — Bitbucket",
            color = "#3B82F6",
            badgePrefix = "PR",
            description = "Create pull requests",
            tools = listOf("bitbucket_create_pr")
        ),
        ToolCategory(
            id = "planning",
            displayName = "Planning",
            color = "#F59E0B",
            badgePrefix = "PLAN",
            description = "Create and update implementation plans",
            tools = listOf("create_plan", "update_plan_step", "ask_questions", "save_memory")
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
