package com.workflow.orchestrator.jira.service

object CommitPrefixService {

    private val CONVENTIONAL_COMMIT_PATTERN = Regex("^(\\w+)(\\([^)]+\\))?:\\s*")
    private val TICKET_ID_PATTERN = Regex("^[A-Z][A-Z0-9]+-\\d+$")

    fun addPrefix(message: String, ticketId: String, useConventionalCommits: Boolean): String {
        // Validate ticket ID format to prevent header keys or garbage from corrupting commits
        if (!TICKET_ID_PATTERN.matches(ticketId)) return message
        if (message.contains(ticketId)) return message

        return if (useConventionalCommits) {
            addConventionalPrefix(message, ticketId)
        } else {
            addStandardPrefix(message, ticketId)
        }
    }

    private fun addStandardPrefix(message: String, ticketId: String): String {
        return "$ticketId: $message"
    }

    private fun addConventionalPrefix(message: String, ticketId: String): String {
        val match = CONVENTIONAL_COMMIT_PATTERN.find(message)
        return if (match != null) {
            val type = match.groupValues[1]
            val rest = message.removePrefix(match.value)
            "$type($ticketId): $rest"
        } else {
            "feat($ticketId): $message"
        }
    }
}
