package com.workflow.orchestrator.agent.tools

/**
 * Selects which tools to send to the LLM based on conversation context.
 * Saves tokens by not sending all 86 tools when only a subset is needed.
 *
 * Strategy:
 * - Core tools (read, edit, search, command, diagnostics) always included
 * - PSI tools (file_structure, find_definition, etc.) always included — they're small and useful
 * - Post-edit tools (format, imports, semantic diagnostics) always included
 * - Integration tools injected when user message mentions relevant keywords
 *
 * Tool selection uses semantic groups instead of a flat keyword map.
 * Each group bundles related keywords with their corresponding tools,
 * making it easy to add new integrations and reason about coverage.
 * Tools only expand across messages in a session — they never shrink.
 */
object DynamicToolSelector {

    /**
     * A semantic group of related tools triggered by a set of keywords.
     * When any keyword in the group matches the conversation context,
     * all tools in the group are included.
     */
    data class ToolGroup(
        val name: String,
        val keywords: Set<String>,
        val tools: Set<String>
    )

    /** Core tools always available (small, essential for any coding task). */
    private val ALWAYS_INCLUDE = setOf(
        "read_file", "edit_file", "search_code", "run_command", "glob_files",
        "file_structure", "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
        "diagnostics", "format_code", "optimize_imports",
        "agent",
        "delegate_task",
        "think"
    )

    // --- Tool name sets for each integration ---

    private val JIRA_TOOL_NAMES = setOf(
        "jira_get_ticket", "jira_get_transitions", "jira_transition", "jira_comment",
        "jira_get_comments", "jira_log_work", "jira_get_worklogs", "jira_get_sprints",
        "jira_get_linked_prs", "jira_get_boards", "jira_get_sprint_issues",
        "jira_get_board_issues", "jira_search_issues", "jira_get_dev_branches", "jira_start_work"
    )

    private val BAMBOO_TOOL_NAMES = setOf(
        "bamboo_build_status", "bamboo_get_build", "bamboo_trigger_build",
        "bamboo_get_build_log", "bamboo_get_test_results", "bamboo_stop_build",
        "bamboo_cancel_build", "bamboo_get_artifacts", "bamboo_recent_builds",
        "bamboo_get_plans", "bamboo_get_plan_branches", "bamboo_get_running_builds",
        "bamboo_get_build_variables", "bamboo_get_plan_variables",
        "bamboo_get_project_plans", "bamboo_search_plans",
        "bamboo_rerun_failed_jobs", "bamboo_trigger_stage"
    )

    private val SONAR_TOOL_NAMES = setOf(
        "sonar_issues", "sonar_quality_gate", "sonar_coverage",
        "sonar_search_projects", "sonar_analysis_tasks", "sonar_branches",
        "sonar_project_measures", "sonar_source_lines", "sonar_issues_paged"
    )

    private val BITBUCKET_TOOL_NAMES = setOf(
        "bitbucket_create_pr", "bitbucket_get_pr_commits", "bitbucket_add_inline_comment",
        "bitbucket_reply_to_comment", "bitbucket_set_reviewer_status",
        "bitbucket_get_file_content", "bitbucket_add_reviewer", "bitbucket_update_pr_title",
        "bitbucket_get_branches", "bitbucket_create_branch", "bitbucket_search_users",
        "bitbucket_get_my_prs", "bitbucket_get_reviewing_prs", "bitbucket_get_pr_detail",
        "bitbucket_get_pr_activities", "bitbucket_get_pr_changes", "bitbucket_get_pr_diff",
        "bitbucket_get_build_statuses", "bitbucket_approve_pr", "bitbucket_merge_pr",
        "bitbucket_decline_pr", "bitbucket_update_pr_description", "bitbucket_add_pr_comment",
        "bitbucket_check_merge_status", "bitbucket_remove_reviewer", "bitbucket_list_repos"
    )

    private val DEBUG_TOOL_NAMES = setOf(
        "add_breakpoint", "remove_breakpoint", "list_breakpoints",
        "start_debug_session", "get_debug_state", "debug_step_over",
        "debug_step_into", "debug_step_out", "debug_resume", "debug_pause",
        "debug_run_to_cursor", "debug_stop", "evaluate_expression",
        "get_stack_frames", "get_variables"
    )

    private val VCS_TOOL_NAMES = setOf(
        "git_status", "git_blame", "git_diff", "git_log", "git_branches",
        "git_show_file", "git_show_commit", "git_stash_list", "git_merge_base",
        "git_file_history"
    )

    private val SPRING_TOOL_NAMES = setOf(
        "spring_context", "spring_endpoints", "spring_bean_graph", "spring_config",
        "spring_version_info", "spring_profiles", "spring_repositories",
        "spring_security_config", "spring_scheduled_tasks", "spring_event_listeners"
    )

    private val MAVEN_TOOL_NAMES = setOf(
        "maven_dependencies", "maven_properties", "maven_plugins", "maven_profiles",
        "project_modules"
    )

    private val RUNTIME_TOOL_NAMES = setOf(
        "get_run_configurations", "create_run_config", "modify_run_config",
        "delete_run_config", "get_running_processes", "get_run_output",
        "get_test_results", "run_tests", "compile_module"
    )

    private val IDE_TOOL_NAMES = setOf(
        "run_inspections", "refactor_rename", "list_quickfixes",
        "find_implementations", "semantic_diagnostics"
    )

    private val MEMORY_TOOL_NAMES = setOf("save_memory")

    private val SKILL_TOOL_NAMES = setOf("activate_skill", "deactivate_skill")

    private val JPA_TOOL_NAMES = setOf("jpa_entities")

    /** Semantic tool groups — each bundles related keywords with their tools. */
    internal val TOOL_GROUPS = listOf(
        ToolGroup(
            "jira",
            setOf("jira", "ticket", "issue", "sprint", "transition", "log work", "log time",
                "worklog", "board", "backlog", "story", "epic", "assignee", "start work",
                "linked pr", "dev status", "search issue"),
            JIRA_TOOL_NAMES
        ),
        ToolGroup(
            "bamboo",
            setOf("bamboo", "build", "ci", "pipeline", "deploy", "artifact", "stage",
                "compile", "test results", "build log", "stop build", "cancel build",
                "running build", "plan", "rerun", "variable"),
            BAMBOO_TOOL_NAMES + setOf("compile_module", "semantic_diagnostics")
        ),
        ToolGroup(
            "sonar",
            setOf("sonar", "quality", "coverage", "code smell", "vulnerability",
                "quality gate", "gate", "analysis", "compute engine", "tech debt",
                "technical debt", "rating", "duplication", "source line", "measures"),
            SONAR_TOOL_NAMES
        ),
        ToolGroup(
            "bitbucket",
            setOf("bitbucket", "pr", "pull request", "merge", "review", "reviewer",
                "approve", "decline", "inline comment", "code review", "merge status",
                "build status", "repo"),
            BITBUCKET_TOOL_NAMES
        ),
        ToolGroup(
            "debug",
            setOf("debug", "breakpoint", "step over", "step into", "step through",
                "step out", "evaluate", "stack trace", "stack frame", "watch",
                "console", "log output"),
            DEBUG_TOOL_NAMES + setOf("get_run_output", "get_running_processes")
        ),
        ToolGroup(
            "vcs",
            setOf("git", "commit", "diff", "blame", "who changed", "log", "stash",
                "rebase", "branch", "cherry", "changed files", "history",
                "implement", "implementation", "override"),
            VCS_TOOL_NAMES + setOf("find_implementations")
        ),
        ToolGroup(
            "spring",
            setOf("spring", "bean", "endpoint", "controller", "service", "repository",
                "autowired", "injection", "config", "application.properties",
                "application.yml", "security", "auth", "authentication", "authorization",
                "scheduled", "cron", "event", "listener"),
            SPRING_TOOL_NAMES
        ),
        ToolGroup(
            "maven",
            setOf("maven", "pom", "dependency", "dependencies", "plugin", "profile",
                "module", "version", "gradle"),
            MAVEN_TOOL_NAMES + setOf("spring_version_info")
        ),
        ToolGroup(
            "runtime",
            setOf("run", "execute", "process", "launch", "test result", "test output",
                "test fail", "run config", "run configuration", "test", "tests", "run test"),
            RUNTIME_TOOL_NAMES
        ),
        ToolGroup(
            "ide",
            setOf("format", "reformat", "import", "imports", "inspection", "inspect",
                "lint", "rename", "refactor", "quick fix", "intention"),
            IDE_TOOL_NAMES
        ),
        ToolGroup(
            "jpa",
            setOf("entity", "table", "jpa", "hibernate"),
            JPA_TOOL_NAMES + setOf("spring_repositories")
        ),
        ToolGroup(
            "memory",
            setOf("remember", "memory", "learn"),
            MEMORY_TOOL_NAMES
        ),
        ToolGroup(
            "skill",
            setOf("skill", "workflow"),
            SKILL_TOOL_NAMES
        ),
        ToolGroup(
            "file_discovery",
            setOf("find file", "list files", "what files", "file structure"),
            setOf("glob_files", "file_structure")
        )
    )

    /** Maven tools to auto-include when project is detected as Maven. */
    private val MAVEN_PROJECT_TOOLS = setOf(
        "maven_dependencies", "maven_properties", "maven_plugins", "maven_profiles",
        "spring_version_info", "project_modules"
    )

    /** Spring tools to auto-include when project is detected as Spring. */
    private val SPRING_PROJECT_TOOLS = setOf(
        "spring_context", "spring_endpoints", "spring_bean_graph", "spring_config",
        "spring_profiles", "spring_repositories", "spring_security_config",
        "spring_scheduled_tasks", "spring_event_listeners"
    )

    /** JPA tools to auto-include when JPA/Hibernate is detected. */
    private val JPA_PROJECT_TOOLS = setOf("jpa_entities", "spring_repositories")

    /**
     * Detect project type using IntelliJ APIs.
     * Called once at session creation and cached — not on every tool selection.
     *
     * @return Set of tool names that should always be included for this project type.
     */
    fun detectProjectTools(project: com.intellij.openapi.project.Project): Set<String> {
        val tools = mutableSetOf<String>()

        // Detect Maven project
        try {
            val mavenManagerClass = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
            val getInstance = mavenManagerClass.getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
            val manager = getInstance.invoke(null, project)
            val isMaven = mavenManagerClass.getMethod("isMavenizedProject").invoke(manager) as Boolean
            if (isMaven) {
                tools.addAll(MAVEN_PROJECT_TOOLS)
            }
        } catch (_: Exception) { /* Maven plugin not available */ }

        // Detect Spring project (check for spring-boot or spring-context in classpath)
        // PSI calls require ReadAction — without it, findClass() throws on background threads
        try {
            com.intellij.openapi.application.ReadAction.compute<Unit, Exception> {
                val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
                val scope = com.intellij.psi.search.GlobalSearchScope.allScope(project)
                val hasSpring = facade.findClass("org.springframework.context.ApplicationContext", scope) != null
                if (hasSpring) {
                    tools.addAll(SPRING_PROJECT_TOOLS)
                }
                val hasJpa = facade.findClass("javax.persistence.Entity", scope) != null ||
                    facade.findClass("jakarta.persistence.Entity", scope) != null
                if (hasJpa) {
                    tools.addAll(JPA_PROJECT_TOOLS)
                }
            }
        } catch (_: Exception) { /* PSI not available in dumb mode — will be picked up by keywords */ }

        return tools
    }

    /**
     * Select tools relevant to the conversation using semantic group matching.
     *
     * @param allTools All registered tools
     * @param conversationContext Recent user messages to scan for keywords
     * @param projectTools Tools auto-detected from project type (Maven/Spring/JPA). Pass from session cache.
     * @return Filtered list of tools to send to the LLM
     */
    fun selectTools(
        allTools: Collection<AgentTool>,
        conversationContext: String,
        disabledTools: Set<String> = emptySet(),
        activatedTools: Set<String> = emptySet(),
        preferredTools: Set<String> = emptySet(),
        projectTools: Set<String> = emptySet(),
        skillAllowedTools: Set<String>? = null
    ): List<AgentTool> {
        // If a skill with allowed-tools is active, ONLY those tools are available
        // This is a hard whitelist — overrides all other selection logic
        if (skillAllowedTools != null) {
            val allowed = skillAllowedTools.toMutableSet()
            // agent + delegate_task are escape hatches — request_tools could bypass the whitelist
            allowed.add("agent")
            allowed.add("delegate_task")
            return allTools.filter { it.name in allowed }
        }

        val lowerContext = conversationContext.lowercase()

        // Always include core + PSI tools
        val selectedNames = ALWAYS_INCLUDE.toMutableSet()

        // Include project-type-detected tools (Maven/Spring/JPA — detected at session start)
        selectedNames.addAll(projectTools)

        // Scan context against semantic groups — when any keyword in a group matches,
        // all tools in that group are included
        for (group in TOOL_GROUPS) {
            if (group.keywords.any { lowerContext.contains(it) }) {
                selectedNames.addAll(group.tools)
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
        // agent + delegate_task are always available — delegation escape hatches
        selectedNames.add("agent")
        selectedNames.add("delegate_task")

        return allTools.filter { it.name in selectedNames }
    }

    /**
     * Check if any integration tools are needed based on context.
     * Uses semantic groups for matching instead of flat keyword scan.
     */
    fun hasIntegrationTriggers(text: String): Boolean {
        val lower = text.lowercase()
        return TOOL_GROUPS.any { group ->
            group.keywords.any { lower.contains(it) }
        }
    }
}
