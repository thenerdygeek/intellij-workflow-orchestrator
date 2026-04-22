package com.workflow.orchestrator.core.ai.prompts

import com.workflow.orchestrator.core.workflow.TicketContext

object PrTitlePromptBuilder {

    private const val DESC_CAP = 1_500
    private const val MAX_COMMITS = 10

    fun build(ticket: TicketContext, commitMessages: List<String> = emptyList()): String = buildString {
        appendLine("Generate a single-line pull request title.")
        appendLine()
        appendLine("RULES:")
        appendLine("- Max 100 characters")
        appendLine("- Imperative mood (e.g., \"Add\", \"Fix\", \"Refactor\"), not \"Added\" / \"Adding\"")
        appendLine("- Start with the ticket key: ${ticket.key}")
        appendLine("- Format: \"${ticket.key}: {concise summary}\"")
        appendLine("- No trailing punctuation, no quotes, no preamble")
        appendLine("- Output ONLY the title")
        appendLine()
        appendLine("CONTEXT:")
        appendLine()
        appendLine("Ticket: ${ticket.key} — ${ticket.summary}")
        appendLine("Type: ${ticket.issueType ?: "?"} · Status: ${ticket.status ?: "?"}")

        val desc = ticket.description?.ifBlank { null }
        if (desc != null) {
            appendLine("Description:")
            appendLine(desc.truncateTo(DESC_CAP))
        }

        if (commitMessages.isNotEmpty()) {
            appendLine()
            appendLine("COMMITS (top ${minOf(commitMessages.size, MAX_COMMITS)}):")
            commitMessages.take(MAX_COMMITS).forEach { appendLine("  - $it") }
        }

        appendLine()
        appendLine("Output the title only.")
    }
}
