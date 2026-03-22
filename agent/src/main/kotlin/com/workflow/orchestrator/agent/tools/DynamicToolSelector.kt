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
        "agent",
        "delegate_task",
        "think"
    )

    /** Keyword patterns that trigger tool group injection. */
    private val TOOL_TRIGGERS = mapOf(
        // Jira tools triggered by ticket/issue/jira keywords
        "jira" to setOf("jira_get_ticket", "jira_get_transitions", "jira_transition", "jira_comment", "jira_get_comments", "jira_log_work", "jira_get_worklogs", "jira_get_sprints", "jira_get_linked_prs", "jira_get_boards", "jira_get_sprint_issues", "jira_get_board_issues", "jira_search_issues", "jira_get_dev_branches", "jira_start_work"),
        "ticket" to setOf("jira_get_ticket", "jira_get_transitions", "jira_transition", "jira_comment", "jira_get_comments", "jira_log_work", "jira_get_worklogs"),
        "issue" to setOf("jira_get_ticket", "jira_get_transitions", "jira_transition", "jira_search_issues"),
        "sprint" to setOf("jira_get_ticket", "jira_get_sprints", "jira_get_sprint_issues"),
        "transition" to setOf("jira_get_ticket", "jira_get_transitions", "jira_transition"),
        "log work" to setOf("jira_log_work", "jira_get_worklogs"),
        "log time" to setOf("jira_log_work", "jira_get_worklogs"),
        "worklog" to setOf("jira_get_worklogs", "jira_log_work"),
        "board" to setOf("jira_get_boards", "jira_get_board_issues"),
        "start work" to setOf("jira_start_work"),
        "linked pr" to setOf("jira_get_linked_prs"),
        "dev status" to setOf("jira_get_dev_branches", "jira_get_linked_prs"),
        "search issue" to setOf("jira_search_issues"),

        // Bamboo tools triggered by build/ci/deploy keywords
        "bamboo" to setOf("bamboo_build_status", "bamboo_get_build", "bamboo_trigger_build", "bamboo_get_build_log", "bamboo_get_test_results", "bamboo_stop_build", "bamboo_cancel_build", "bamboo_get_artifacts", "bamboo_recent_builds", "bamboo_get_plans", "bamboo_get_plan_branches", "bamboo_get_running_builds", "bamboo_get_build_variables", "bamboo_get_plan_variables"),
        "build" to setOf("compile_module", "bamboo_build_status", "bamboo_get_build", "bamboo_trigger_build", "bamboo_recent_builds"),
        "compile" to setOf("compile_module", "semantic_diagnostics"),
        "ci" to setOf("bamboo_build_status", "bamboo_trigger_build", "bamboo_get_plans"),
        "pipeline" to setOf("bamboo_build_status", "bamboo_trigger_build", "bamboo_get_plans"),
        "test results" to setOf("bamboo_get_test_results"),
        "build log" to setOf("bamboo_get_build_log"),
        "deploy" to setOf("bamboo_trigger_build", "bamboo_trigger_stage"),
        "artifact" to setOf("bamboo_get_artifacts"),
        "stop build" to setOf("bamboo_stop_build"),
        "cancel build" to setOf("bamboo_cancel_build"),
        "running build" to setOf("bamboo_get_running_builds"),
        "plan" to setOf("bamboo_get_plans", "bamboo_get_plan_branches", "bamboo_search_plans"),
        "rerun" to setOf("bamboo_rerun_failed_jobs"),
        "stage" to setOf("bamboo_trigger_stage"),
        "variable" to setOf("bamboo_get_plan_variables", "bamboo_get_build_variables"),

        // Sonar tools triggered by quality/coverage/sonar keywords
        "sonar" to setOf("sonar_issues", "sonar_quality_gate", "sonar_coverage", "sonar_search_projects", "sonar_analysis_tasks", "sonar_project_health", "sonar_branches", "sonar_project_measures", "sonar_source_lines", "sonar_issues_paged"),
        "quality" to setOf("sonar_issues", "sonar_quality_gate", "sonar_project_measures"),
        "coverage" to setOf("sonar_coverage", "sonar_source_lines"),
        "code smell" to setOf("sonar_issues", "sonar_issues_paged"),
        "vulnerability" to setOf("sonar_issues", "sonar_issues_paged"),
        "quality gate" to setOf("sonar_quality_gate", "sonar_branches"),
        "analysis" to setOf("sonar_analysis_tasks"),
        "compute engine" to setOf("sonar_analysis_tasks"),
        "tech debt" to setOf("sonar_project_health", "sonar_project_measures"),
        "technical debt" to setOf("sonar_project_health", "sonar_project_measures"),
        "rating" to setOf("sonar_project_health", "sonar_project_measures"),
        "health" to setOf("sonar_project_health"),
        "duplication" to setOf("sonar_project_health", "sonar_project_measures"),
        "source line" to setOf("sonar_source_lines"),
        "measures" to setOf("sonar_project_measures"),

        // Bitbucket tools triggered by PR/pull request keywords
        "bitbucket" to setOf("bitbucket_create_pr", "bitbucket_get_pr_commits", "bitbucket_add_inline_comment", "bitbucket_reply_to_comment", "bitbucket_set_reviewer_status", "bitbucket_get_file_content", "bitbucket_add_reviewer", "bitbucket_update_pr_title", "bitbucket_get_branches", "bitbucket_create_branch", "bitbucket_search_users", "bitbucket_get_my_prs", "bitbucket_get_reviewing_prs", "bitbucket_get_pr_detail", "bitbucket_get_pr_activities", "bitbucket_get_pr_changes", "bitbucket_get_pr_diff", "bitbucket_get_build_statuses", "bitbucket_approve_pr", "bitbucket_merge_pr", "bitbucket_decline_pr", "bitbucket_update_pr_description", "bitbucket_add_pr_comment", "bitbucket_check_merge_status", "bitbucket_remove_reviewer"),
        "pull request" to setOf("bitbucket_create_pr", "bitbucket_get_pr_detail", "bitbucket_get_pr_commits", "bitbucket_get_pr_changes", "bitbucket_get_pr_diff", "bitbucket_get_my_prs", "bitbucket_get_reviewing_prs", "bitbucket_approve_pr", "bitbucket_merge_pr"),
        "pr" to setOf("bitbucket_create_pr", "bitbucket_get_pr_detail", "bitbucket_get_my_prs", "bitbucket_get_reviewing_prs", "bitbucket_approve_pr", "bitbucket_merge_pr"),
        "review" to setOf("bitbucket_get_reviewing_prs", "bitbucket_add_reviewer", "bitbucket_set_reviewer_status", "bitbucket_remove_reviewer", "bitbucket_approve_pr"),
        "reviewer" to setOf("bitbucket_add_reviewer", "bitbucket_remove_reviewer", "bitbucket_set_reviewer_status", "bitbucket_search_users"),
        "approve" to setOf("bitbucket_approve_pr", "bitbucket_set_reviewer_status"),
        "inline comment" to setOf("bitbucket_add_inline_comment"),
        "code review" to setOf("bitbucket_get_pr_diff", "bitbucket_get_pr_changes", "bitbucket_add_inline_comment", "bitbucket_get_pr_activities"),
        "merge status" to setOf("bitbucket_check_merge_status"),
        "decline" to setOf("bitbucket_decline_pr"),
        "build status" to setOf("bitbucket_get_build_statuses"),

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
        "git" to setOf("git_status", "git_blame", "git_diff", "git_log", "git_branches", "git_show_file", "git_show_commit", "git_stash_list", "git_merge_base", "git_file_history"),
        "blame" to setOf("git_blame"),
        "who changed" to setOf("git_blame"),
        "branch" to setOf("git_status", "git_branches"),
        "commit" to setOf("git_status", "git_log", "git_show_commit"),
        "diff" to setOf("git_diff", "git_status"),
        "changed files" to setOf("git_status", "git_diff"),
        "log" to setOf("git_log"),
        "history" to setOf("git_log", "git_file_history"),
        "stash" to setOf("git_stash_list"),
        "merge" to setOf("bitbucket_create_pr", "bitbucket_merge_pr", "bitbucket_check_merge_status", "git_merge_base"),
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
     * Select tools relevant to the conversation.
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
        // agent + delegate_task are always available — delegation escape hatches
        selectedNames.add("agent")
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
