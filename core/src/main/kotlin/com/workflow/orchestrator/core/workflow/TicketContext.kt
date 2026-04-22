package com.workflow.orchestrator.core.workflow

/**
 * Rich ticket context used by the PR creation flow.
 * Contains all fields needed to generate a meaningful PR title and description.
 *
 * Intentionally separate from [TicketDetails] (which serves the lighter
 * branch-naming / commit-prefix use-case). Both coexist — do not remove [TicketDetails].
 */
data class TicketContext(
    val key: String,
    val summary: String,
    val description: String?,
    val status: String?,
    val priority: String?,
    val issueType: String?,
    val assignee: String?,
    val reporter: String?,
    val labels: List<String> = emptyList(),
    val components: List<String> = emptyList(),
    val fixVersions: List<String> = emptyList(),
    val comments: List<TicketComment> = emptyList(),
    val acceptanceCriteria: String? = null
)

/**
 * A single comment on a Jira ticket.
 * [created] is the ISO 8601 timestamp as returned by Jira — preserved as-is.
 */
data class TicketComment(
    val author: String,
    val created: String,
    val body: String
)
