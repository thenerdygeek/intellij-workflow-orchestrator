package com.workflow.orchestrator.jira.service

object BranchNameValidator {

    private val TICKET_PATTERN = Regex("[A-Z][A-Z0-9]+-\\d+")
    private val INVALID_CHARS = Regex("[^a-z0-9/\\-]")

    fun generateBranchName(pattern: String, ticketId: String, summary: String, maxSummaryLength: Int = 50): String {
        val sanitizedSummary = summary
            .lowercase()
            .replace(INVALID_CHARS, "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(maxSummaryLength)
            .trimEnd('-')

        return pattern
            .replace("{ticketId}", ticketId)
            .replace("{summary}", sanitizedSummary)
    }

    fun isValidBranchName(name: String): Boolean {
        if (name.isBlank()) return false
        return TICKET_PATTERN.containsMatchIn(name)
    }
}
