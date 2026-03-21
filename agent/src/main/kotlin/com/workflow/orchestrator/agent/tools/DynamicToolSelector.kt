package com.workflow.orchestrator.agent.tools

/**
 * Selects which tools to send to the LLM based on conversation context.
 * Saves tokens by not sending all 63 tools when only a subset is needed.
 *
 * Strategy:
 * - Core tools (read, edit, search, command, diagnostics) always included
 * - PSI tools (file_structure, find_definition, etc.) always included — they're small and useful
 * - Post-edit tools (format, imports, semantic diagnostics) always included
 * - Integration tools injected when user message mentions relevant keywords
 */
object DynamicToolSelector {

    /** Core tools always available (small, essential for any coding task). */
    private val ALWAYS_INCLUDE = setOf(
        "read_file", "edit_file", "search_code", "run_command", "glob_files",
        "file_structure", "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
        "diagnostics", "format_code", "optimize_imports",
        "delegate_task",
        "think"
    )

    /** Keyword patterns that trigger tool group injection. */
    private val TOOL_TRIGGERS = mapOf(
        // Jira tools triggered by ticket/issue/jira keywords
        "jira" to setOf("jira_get_ticket", "jira_get_transitions", "jira_transition", "jira_comment", "jira_get_comments", "jira_log_work"),
        "ticket" to setOf("jira_get_ticket", "jira_get_transitions", "jira_transition", "jira_comment", "jira_get_comments", "jira_log_work"),
        "issue" to setOf("jira_get_ticket", "jira_get_transitions", "jira_transition"),
        "sprint" to setOf("jira_get_ticket"),
        "transition" to setOf("jira_get_ticket", "jira_get_transitions", "jira_transition"),
        "log work" to setOf("jira_log_work"),
        "log time" to setOf("jira_log_work"),

        // Bamboo tools triggered by build/ci/deploy keywords
        "bamboo" to setOf("bamboo_build_status", "bamboo_get_build", "bamboo_trigger_build", "bamboo_get_build_log", "bamboo_get_test_results"),
        "build" to setOf("compile_module", "bamboo_build_status", "bamboo_get_build", "bamboo_trigger_build"),
        "compile" to setOf("compile_module", "semantic_diagnostics"),
        "ci" to setOf("bamboo_build_status", "bamboo_trigger_build"),
        "pipeline" to setOf("bamboo_build_status", "bamboo_trigger_build"),
        "test results" to setOf("bamboo_get_test_results"),
        "build log" to setOf("bamboo_get_build_log"),
        "deploy" to setOf("bamboo_trigger_build"),

        // Sonar tools triggered by quality/coverage/sonar keywords
        "sonar" to setOf("sonar_issues", "sonar_quality_gate", "sonar_coverage", "sonar_search_projects", "sonar_analysis_tasks", "sonar_project_health"),
        "quality" to setOf("sonar_issues", "sonar_quality_gate"),
        "coverage" to setOf("sonar_coverage"),
        "code smell" to setOf("sonar_issues"),
        "vulnerability" to setOf("sonar_issues"),
        "quality gate" to setOf("sonar_quality_gate"),
        "analysis" to setOf("sonar_analysis_tasks"),
        "compute engine" to setOf("sonar_analysis_tasks"),
        "tech debt" to setOf("sonar_project_health"),
        "technical debt" to setOf("sonar_project_health"),
        "rating" to setOf("sonar_project_health"),
        "health" to setOf("sonar_project_health"),
        "duplication" to setOf("sonar_project_health"),

        // Bitbucket tools triggered by PR/pull request keywords
        "bitbucket" to setOf("bitbucket_create_pr"),
        "pull request" to setOf("bitbucket_create_pr"),
        "pr" to setOf("bitbucket_create_pr"),
        "merge" to setOf("bitbucket_create_pr"),

        // Spring tools triggered by spring/bean/endpoint keywords
        "spring" to setOf("spring_context", "spring_endpoints", "spring_bean_graph", "spring_config", "spring_version_info", "spring_profiles", "spring_repositories", "spring_security_config", "spring_scheduled_tasks", "spring_event_listeners"),
        "bean" to setOf("spring_context", "spring_bean_graph"),
        "endpoint" to setOf("spring_endpoints"),
        "controller" to setOf("spring_endpoints"),
        "service" to setOf("spring_context"),
        "repository" to setOf("spring_context", "spring_repositories"),
        "injection" to setOf("spring_bean_graph"),
        "autowired" to setOf("spring_bean_graph"),

        // IDE tools — triggered by code quality keywords
        "format" to setOf("format_code"),
        "reformat" to setOf("format_code"),
        "import" to setOf("optimize_imports"),
        "imports" to setOf("optimize_imports"),
        "inspection" to setOf("run_inspections"),
        "inspect" to setOf("run_inspections"),
        "lint" to setOf("run_inspections"),
        "rename" to setOf("refactor_rename"),
        "refactor" to setOf("refactor_rename"),
        "quick fix" to setOf("list_quickfixes"),
        "intention" to setOf("list_quickfixes"),
        "test" to setOf("run_tests", "bamboo_get_test_results"),
        "tests" to setOf("run_tests"),
        "run test" to setOf("run_tests"),

        // VCS tools — triggered by git keywords
        "git" to setOf("git_status", "git_blame"),
        "blame" to setOf("git_blame"),
        "who changed" to setOf("git_blame"),
        "branch" to setOf("git_status"),
        "commit" to setOf("git_status"),
        "diff" to setOf("git_status"),
        "changed files" to setOf("git_status"),
        "implement" to setOf("find_implementations"),
        "implementation" to setOf("find_implementations"),
        "override" to setOf("find_implementations"),

        // Framework tools
        "config" to setOf("spring_config"),
        "properties" to setOf("spring_config", "maven_properties"),
        "application.properties" to setOf("spring_config"),
        "application.yml" to setOf("spring_config"),
        "entity" to setOf("jpa_entities"),
        "table" to setOf("jpa_entities"),
        "jpa" to setOf("jpa_entities"),
        "hibernate" to setOf("jpa_entities"),
        "module" to setOf("project_modules"),
        "dependency" to setOf("maven_dependencies", "project_modules"),
        "dependencies" to setOf("maven_dependencies", "project_modules"),
        "pom" to setOf("maven_dependencies", "maven_properties", "maven_plugins", "project_modules"),

        // Maven tools
        "maven" to setOf("maven_dependencies", "maven_properties", "maven_plugins", "maven_profiles"),
        "version" to setOf("spring_version_info"),
        "plugin" to setOf("maven_plugins"),
        "profile" to setOf("spring_profiles", "maven_profiles"),

        // Spring advanced tools
        "security" to setOf("spring_security_config"),
        "auth" to setOf("spring_security_config"),
        "authentication" to setOf("spring_security_config"),
        "authorization" to setOf("spring_security_config"),
        "scheduled" to setOf("spring_scheduled_tasks"),
        "cron" to setOf("spring_scheduled_tasks"),
        "event" to setOf("spring_event_listeners"),
        "listener" to setOf("spring_event_listeners"),

        // Memory tools
        "remember" to setOf("save_memory"),
        "memory" to setOf("save_memory"),
        "learn" to setOf("save_memory"),

        // Skill tools
        "skill" to setOf("activate_skill", "deactivate_skill"),
        "workflow" to setOf("activate_skill"),

        // File discovery tools
        "find file" to setOf("glob_files"),
        "list files" to setOf("glob_files"),
        "what files" to setOf("glob_files"),
        "file structure" to setOf("glob_files", "file_structure")
    )

    /**
     * Select tools relevant to the conversation.
     * @param allTools All registered tools
     * @param conversationContext Recent user messages to scan for keywords
     * @return Filtered list of tools to send to the LLM
     */
    fun selectTools(
        allTools: Collection<AgentTool>,
        conversationContext: String,
        disabledTools: Set<String> = emptySet(),
        activatedTools: Set<String> = emptySet(),
        preferredTools: Set<String> = emptySet()
    ): List<AgentTool> {
        val lowerContext = conversationContext.lowercase()

        // Always include core + PSI tools
        val selectedNames = ALWAYS_INCLUDE.toMutableSet()

        // Scan context for trigger keywords
        for ((keyword, toolNames) in TOOL_TRIGGERS) {
            if (lowerContext.contains(keyword)) {
                selectedNames.addAll(toolNames)
            }
        }

        // Add LLM-activated tools from request_tools
        selectedNames.addAll(activatedTools)

        // Add skill-preferred tools
        selectedNames.addAll(preferredTools)

        // Remove user-disabled tools
        selectedNames.removeAll(disabledTools)

        // Always include request_tools meta-tool AFTER removing disabled tools
        // — this is the LLM's escape hatch; disabling it breaks the hybrid system
        selectedNames.add("request_tools")
        // delegate_task is always available — it's the LLM's delegation escape hatch
        selectedNames.add("delegate_task")

        return allTools.filter { it.name in selectedNames }
    }

    /**
     * Check if any integration tools are needed based on context.
     * Used to decide if we should include the full tool set.
     */
    fun hasIntegrationTriggers(text: String): Boolean {
        val lower = text.lowercase()
        return TOOL_TRIGGERS.keys.any { lower.contains(it) }
    }
}
