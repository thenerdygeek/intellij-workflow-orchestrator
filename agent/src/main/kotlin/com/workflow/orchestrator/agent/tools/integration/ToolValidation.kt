package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.tools.ToolResult

/**
 * Input validation helpers for integration tools.
 * Returns a ToolResult error if validation fails, null if valid.
 *
 * Each validator provides actionable feedback so the LLM knows
 * exactly how to fix the issue.
 */
object ToolValidation {

    private val JIRA_KEY_PATTERN = Regex("[A-Z][A-Z0-9]+-\\d+")
    private val BAMBOO_PLAN_KEY_PATTERN = Regex("[A-Z][A-Z0-9]+-[A-Z][A-Z0-9]+")
    private val BAMBOO_BUILD_KEY_PATTERN = Regex("[A-Z][A-Z0-9]+-[A-Z][A-Z0-9]+-\\d+")

    fun validateJiraKey(key: String): ToolResult? {
        if (key.isBlank()) {
            return error("Jira key cannot be empty. Expected format: PROJ-123")
        }
        if (!JIRA_KEY_PATTERN.matches(key)) {
            return error("Invalid Jira key '$key'. Expected format: PROJ-123 (uppercase project, dash, number)")
        }
        return null
    }

    fun validateBambooPlanKey(planKey: String): ToolResult? {
        if (planKey.isBlank()) {
            return error("Bamboo plan key cannot be empty. Expected format: PROJ-PLAN")
        }
        if (!BAMBOO_PLAN_KEY_PATTERN.matches(planKey)) {
            return error("Invalid Bamboo plan key '$planKey'. Expected format: PROJ-PLAN (e.g., MYAPP-BUILD)")
        }
        return null
    }

    fun validateBambooBuildKey(buildKey: String): ToolResult? {
        if (buildKey.isBlank()) {
            return error("Bamboo build key cannot be empty. Expected format: PROJ-PLAN-123")
        }
        if (!BAMBOO_BUILD_KEY_PATTERN.matches(buildKey)) {
            return error("Invalid Bamboo build key '$buildKey'. Expected format: PROJ-PLAN-123 (e.g., MYAPP-BUILD-456)")
        }
        return null
    }

    fun validateNotBlank(value: String?, paramName: String): ToolResult? {
        if (value.isNullOrBlank()) {
            return error("'$paramName' cannot be empty.")
        }
        return null
    }

    fun validateTimeSpent(timeSpent: String): ToolResult? {
        if (timeSpent.isBlank()) {
            return error("'time_spent' cannot be empty. Use Jira format: '2h', '30m', '1d', '1h 30m'")
        }
        if (!timeSpent.matches(Regex("(\\d+[wdhm]\\s*)+", RegexOption.IGNORE_CASE))) {
            return error("Invalid time format '$timeSpent'. Use Jira format: '2h', '30m', '1d 4h', '1w 2d'")
        }
        return null
    }

    private fun error(message: String): ToolResult = ToolResult(
        content = "Validation error: $message",
        summary = message,
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
}
