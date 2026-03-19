package com.workflow.orchestrator.agent.tools

/**
 * Selects which tools to send to the LLM based on conversation context.
 * Saves 10-15K tokens by not sending all 28 tools when only 5 are needed.
 *
 * Strategy:
 * - Core tools (read, edit, search, command, diagnostics) always included
 * - PSI tools (file_structure, find_definition, etc.) always included — they're small and useful
 * - Integration tools injected when user message mentions relevant keywords
 */
object DynamicToolSelector {

    /** Core tools always available (small, essential for any coding task). */
    private val ALWAYS_INCLUDE = setOf(
        "read_file", "edit_file", "search_code", "run_command", "diagnostics",
        "file_structure", "find_definition", "find_references", "type_hierarchy", "call_hierarchy"
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
        "build" to setOf("bamboo_build_status", "bamboo_get_build", "bamboo_trigger_build"),
        "ci" to setOf("bamboo_build_status", "bamboo_trigger_build"),
        "pipeline" to setOf("bamboo_build_status", "bamboo_trigger_build"),
        "test results" to setOf("bamboo_get_test_results"),
        "build log" to setOf("bamboo_get_build_log"),
        "deploy" to setOf("bamboo_trigger_build"),

        // Sonar tools triggered by quality/coverage/sonar keywords
        "sonar" to setOf("sonar_issues", "sonar_quality_gate", "sonar_coverage", "sonar_search_projects"),
        "quality" to setOf("sonar_issues", "sonar_quality_gate"),
        "coverage" to setOf("sonar_coverage"),
        "code smell" to setOf("sonar_issues"),
        "vulnerability" to setOf("sonar_issues"),
        "quality gate" to setOf("sonar_quality_gate"),

        // Bitbucket tools triggered by PR/pull request keywords
        "bitbucket" to setOf("bitbucket_create_pr"),
        "pull request" to setOf("bitbucket_create_pr"),
        "pr" to setOf("bitbucket_create_pr"),
        "merge" to setOf("bitbucket_create_pr"),

        // Spring tools triggered by spring/bean/endpoint keywords
        "spring" to setOf("spring_context", "spring_endpoints", "spring_bean_graph"),
        "bean" to setOf("spring_context", "spring_bean_graph"),
        "endpoint" to setOf("spring_endpoints"),
        "controller" to setOf("spring_endpoints"),
        "service" to setOf("spring_context"),
        "repository" to setOf("spring_context"),
        "injection" to setOf("spring_bean_graph"),
        "autowired" to setOf("spring_bean_graph")
    )

    /**
     * Select tools relevant to the conversation.
     * @param allTools All registered tools
     * @param conversationContext Recent user messages to scan for keywords
     * @return Filtered list of tools to send to the LLM
     */
    fun selectTools(
        allTools: Collection<AgentTool>,
        conversationContext: String
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
