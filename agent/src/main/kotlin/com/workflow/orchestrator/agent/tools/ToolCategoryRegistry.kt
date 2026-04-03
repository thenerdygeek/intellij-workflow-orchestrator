package com.workflow.orchestrator.agent.tools

/**
 * Central registry mapping all agent tools to logical categories.
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
                "diagnostics", "problem_view", "format_code", "optimize_imports",
                "file_structure", "find_definition", "find_references",
                "type_hierarchy", "call_hierarchy", "get_method_body", "get_annotations",
                "type_inference", "structural_search", "dataflow_analysis", "read_write_access", "test_finder",
                "think", "current_time"
            )
        ),
        ToolCategory(
            id = "ide",
            displayName = "IDE Intelligence",
            color = "#F59E0B",
            badgePrefix = "IDE",
            description = "Code inspections, refactoring, quick fixes, compilation, test execution, find implementations",
            tools = listOf("run_inspections", "refactor_rename", "list_quickfixes", "find_implementations")
        ),
        ToolCategory(
            id = "vcs",
            displayName = "VCS",
            color = "#06B6D4",
            badgePrefix = "GIT",
            description = "Git meta-tool: status, blame, diff, log, branches, show-file, show-commit, stash, merge-base, file-history, shelve",
            tools = listOf("git")
        ),
        ToolCategory(
            id = "framework",
            displayName = "Spring & Build",
            color = "#10B981",
            badgePrefix = "SPR",
            description = "Spring meta-tool (beans, endpoints, config, JPA, profiles, security, boot) + Build meta-tool (Maven, Gradle, modules)",
            tools = listOf("spring", "build")
        ),
        ToolCategory(
            id = "jira",
            displayName = "Jira",
            color = "#A855F7",
            badgePrefix = "JIRA",
            description = "Jira meta-tool: tickets, transitions, comments, time logging, sprints, boards, search, dev-status",
            tools = listOf("jira")
        ),
        ToolCategory(
            id = "bamboo",
            displayName = "CI/CD — Bamboo",
            color = "#EF4444",
            badgePrefix = "CI",
            description = "Bamboo tools: bamboo_builds (status, trigger, stop, cancel, logs, tests, artifacts) + bamboo_plans (list, search, variables, branches)",
            tools = listOf("bamboo_builds", "bamboo_plans")
        ),
        ToolCategory(
            id = "sonar",
            displayName = "Quality — SonarQube",
            color = "#EC4899",
            badgePrefix = "QA",
            description = "Sonar meta-tool: issues, quality gate, coverage, projects, branches, measures, source lines, branch quality report",
            tools = listOf("sonar")
        ),
        ToolCategory(
            id = "runtime_debug",
            displayName = "Runtime & Debug",
            color = "#E91E63",
            badgePrefix = "DBG",
            description = "Runtime tools (run configs, processes, tests, compile) + Debug tools (breakpoints, stepping, inspection, hotswap)",
            tools = listOf("runtime_config", "runtime_exec", "debug_breakpoints", "debug_step", "debug_inspect")
        ),
        ToolCategory(
            id = "bitbucket",
            displayName = "Pull Requests — Bitbucket",
            color = "#3B82F6",
            badgePrefix = "PR",
            description = "Bitbucket tools: bitbucket_pr (create, list, merge, decline) + bitbucket_review (comments, reviewers, approve) + bitbucket_repo (branches, files, repos)",
            tools = listOf("bitbucket_pr", "bitbucket_review", "bitbucket_repo")
        ),
        ToolCategory(
            id = "planning",
            displayName = "Planning",
            color = "#F59E0B",
            badgePrefix = "PLAN",
            description = "Create and update implementation plans",
            tools = listOf("create_plan", "update_plan_step", "ask_questions", "skill")
        ),
        ToolCategory(
            id = "memory",
            displayName = "Memory",
            color = "#8B5CF6",
            badgePrefix = "MEM",
            description = "Three-tier memory: core (always in prompt), archival (searchable, unlimited), conversation recall (past sessions)",
            tools = listOf("core_memory_read", "core_memory_append", "core_memory_replace",
                "archival_memory_insert", "archival_memory_search", "conversation_search")
        )
    )

    /**
     * Aliases for category IDs — maps natural names an LLM might guess
     * to the actual category ID. Prevents wasted iterations on "Unknown category".
     */
    private val CATEGORY_ALIASES = mapOf(
        // runtime_debug aliases
        "debug" to "runtime_debug",
        "debugging" to "runtime_debug",
        "runtime" to "runtime_debug",
        "breakpoint" to "runtime_debug",
        "breakpoints" to "runtime_debug",
        // vcs aliases
        "git" to "vcs",
        "version_control" to "vcs",
        // framework aliases
        "spring" to "framework",
        "maven" to "framework",
        "gradle" to "framework",
        "jpa" to "framework",
        // bamboo aliases
        "build" to "bamboo",
        "ci" to "bamboo",
        "cicd" to "bamboo",
        "ci_cd" to "bamboo",
        "pipeline" to "bamboo",
        // bitbucket aliases
        "pr" to "bitbucket",
        "pull_request" to "bitbucket",
        "pullrequest" to "bitbucket",
        "code_review" to "bitbucket",
        // sonar aliases
        "quality" to "sonar",
        "coverage" to "sonar",
        "sonarqube" to "sonar",
        // ide aliases
        "test" to "ide",
        "testing" to "ide",
        "refactor" to "ide",
        "inspect" to "ide",
        "inspection" to "ide",
        // planning aliases
        "plan" to "planning",
        // memory aliases
        "memory" to "memory",
        "remember" to "memory",
        "recall" to "memory",
        "forget" to "memory",
    )

    /** Resolve a category ID, trying aliases if the exact ID doesn't match. */
    fun resolveCategory(id: String): String {
        val lowered = id.lowercase().trim()
        if (CATEGORIES.any { it.id == lowered }) return lowered
        return CATEGORY_ALIASES[lowered] ?: lowered
    }

    fun getCategoryForTool(toolName: String): ToolCategory? =
        CATEGORIES.find { toolName in it.tools }

    fun getActivatableCategories(): List<ToolCategory> =
        CATEGORIES.filter { !it.alwaysActive }

    fun getToolsInCategory(categoryId: String): List<String> {
        val resolved = resolveCategory(categoryId)
        return CATEGORIES.find { it.id == resolved }?.tools ?: emptyList()
    }

    fun getAllToolNames(): Set<String> =
        CATEGORIES.flatMap { it.tools }.toSet()

    fun getAlwaysActiveTools(): Set<String> =
        CATEGORIES.filter { it.alwaysActive }.flatMap { it.tools }.toSet()
}
