package com.workflow.orchestrator.jira.service

import com.intellij.openapi.diagnostic.Logger

object BranchNameValidator {

    private val log = Logger.getInstance(BranchNameValidator::class.java)

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
