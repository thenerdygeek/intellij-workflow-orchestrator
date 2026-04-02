package com.workflow.orchestrator.jira.service

import com.intellij.openapi.diagnostic.Logger

object BranchNameValidator {

    private val log = Logger.getInstance(BranchNameValidator::class.java)

    private val TICKET_PATTERN = Regex("[A-Z][A-Z0-9]+-\\d+")
    private val INVALID_CHARS = Regex("[^a-z0-9/\\-]")

    /**
     * Maps Jira issue type names to conventional branch prefixes.
     * Unknown types default to "feature".
     */
    fun issueTypeToPrefix(issueTypeName: String?): String {
        if (issueTypeName == null) return "feature"
        return when (issueTypeName.lowercase()) {
            "bug" -> "bugfix"
            "story", "user story" -> "feature"
            "task", "sub-task", "subtask" -> "task"
            "improvement" -> "improvement"
            "epic" -> "epic"
            "spike" -> "spike"
            "hotfix" -> "hotfix"
            else -> "feature"
        }
    }

    /**
     * Generates a branch name from the pattern and issue details.
     *
     * Supported placeholders:
     * - {ticketId} — the Jira ticket key (e.g. PROJ-123)
     * - {summary} — sanitized ticket summary
     * - {type} — issue type prefix (bugfix, feature, task, improvement, etc.)
     * - {ai-summary} — replaced separately by AI-generated slug (caller must pre-replace)
     *
     * @param aiSummary If non-null, replaces {ai-summary} in the pattern
     */
    fun generateBranchName(
        pattern: String,
        ticketId: String,
        summary: String,
        maxSummaryLength: Int = 50,
        issueTypeName: String? = null,
        aiSummary: String? = null
    ): String {
        val sanitizedSummary = summary
            .lowercase()
            .replace(INVALID_CHARS, "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(maxSummaryLength)
            .trimEnd('-')

        val typePrefix = issueTypeToPrefix(issueTypeName)

        var result = pattern
            .replace("{ticketId}", ticketId)
            .replace("{summary}", sanitizedSummary)
            .replace("{type}", typePrefix)

        if (aiSummary != null) {
            result = result.replace("{ai-summary}", aiSummary)
        }

        return result
    }

    /**
     * Whether the given pattern contains the {ai-summary} placeholder.
     */
    fun requiresAiSummary(pattern: String): Boolean =
        pattern.contains("{ai-summary}")

    fun isValidBranchName(name: String): Boolean {
        if (name.isBlank()) {
            log.warn("[Jira:Branch] Validation failed: branch name is blank")
            return false
        }
        val valid = TICKET_PATTERN.containsMatchIn(name)
        if (!valid) {
            log.warn("[Jira:Branch] Validation failed: branch name '$name' does not contain a ticket ID pattern (e.g. PROJ-123)")
        }
        return valid
    }
}
