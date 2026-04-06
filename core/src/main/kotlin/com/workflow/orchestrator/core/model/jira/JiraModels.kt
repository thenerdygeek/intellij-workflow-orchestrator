package com.workflow.orchestrator.core.model.jira

import kotlinx.serialization.Serializable

/**
 * Enriched Jira ticket domain model shared between UI panels and AI agent.
 * Includes sprint context, epic/parent, time tracking, transitions, and counts.
 */
@Serializable
data class JiraTicketData(
    val key: String,
    val summary: String,
    val status: String,
    val assignee: String?,
    val reporter: String?,
    val type: String,
    val priority: String?,
    val description: String?,
    val labels: List<String> = emptyList(),
    val created: String? = null,
    val updated: String? = null,
    // Sprint context
    val sprintName: String? = null,
    val sprintState: String? = null,
    val closedSprints: List<String> = emptyList(),
    // Epic/parent
    val epicKey: String? = null,
    val epicSummary: String? = null,
    // Time tracking
    val originalEstimate: String? = null,
    val remainingEstimate: String? = null,
    val timeSpent: String? = null,
    // Counts
    val commentCount: Int = 0,
    val attachmentCount: Int = 0,
    // Detail lists
    val transitions: List<JiraTransitionData> = emptyList(),
    val attachments: List<JiraAttachmentData> = emptyList(),
    val subtasks: List<JiraSubtaskRef> = emptyList(),
    val linkedIssues: List<JiraLinkedIssueRef> = emptyList(),
    /** Ticket keys mentioned in summary, description, or comments (excludes self and linked/subtask keys) */
    val mentionedTickets: List<String> = emptyList()
) {
    override fun toString(): String = buildString {
        appendLine("$key: $summary")
        appendLine("Status: $status | Type: $type${if (priority != null) " | Priority: $priority" else ""}")
        if (assignee != null) append("Assignee: $assignee")
        if (reporter != null) {
            if (assignee != null) append(" | ")
            append("Reporter: $reporter")
        }
        if (assignee != null || reporter != null) appendLine()

        if (labels.isNotEmpty()) appendLine("Labels: ${labels.joinToString(", ")}")
        if (created != null || updated != null) {
            val parts = mutableListOf<String>()
            if (created != null) parts.add("Created: $created")
            if (updated != null) parts.add("Updated: $updated")
            appendLine(parts.joinToString(" | "))
        }

        // Sprint & Epic
        if (sprintName != null) appendLine("Sprint: $sprintName ($sprintState)")
        if (closedSprints.isNotEmpty()) appendLine("Previous Sprints: ${closedSprints.joinToString(", ")}")
        if (epicKey != null) appendLine("Epic: $epicKey${if (epicSummary != null) " — $epicSummary" else ""}")

        // Time tracking
        if (originalEstimate != null || timeSpent != null || remainingEstimate != null) {
            val parts = mutableListOf<String>()
            if (originalEstimate != null) parts.add("Estimate: $originalEstimate")
            if (timeSpent != null) parts.add("Logged: $timeSpent")
            if (remainingEstimate != null) parts.add("Remaining: $remainingEstimate")
            appendLine(parts.joinToString(" | "))
        }

        // Description (full, no truncation)
        if (!description.isNullOrBlank()) {
            appendLine()
            appendLine("Description:")
            appendLine(description)
        }

        // Transitions
        if (transitions.isNotEmpty()) {
            appendLine()
            appendLine("Available Transitions:")
            transitions.forEach { t -> appendLine("  [${t.id}] ${t.name} → ${t.toStatus}") }
        }

        // Subtasks with details
        if (subtasks.isNotEmpty()) {
            appendLine()
            appendLine("Subtasks (${subtasks.size}):")
            subtasks.forEach { st -> appendLine("  ${st.key} [${st.status}] ${st.summary}") }
        }

        // Linked issues with details
        if (linkedIssues.isNotEmpty()) {
            appendLine()
            appendLine("Linked Issues (${linkedIssues.size}):")
            linkedIssues.forEach { li -> appendLine("  ${li.key} [${li.status}] ${li.summary} (${li.relationship})") }
        }

        // Attachments
        if (attachments.isNotEmpty()) {
            appendLine()
            appendLine("Attachments ($attachmentCount):")
            attachments.forEach { att -> appendLine("  ${att.filename} (id: ${att.id}, ${formatSize(att.sizeBytes)})") }
        } else if (attachmentCount > 0) {
            appendLine()
            appendLine("Attachments: $attachmentCount")
        }

        // Comments count
        if (commentCount > 0) {
            appendLine()
            appendLine("Comments: $commentCount (use get_comments to view)")
        }

        // Mentioned tickets
        if (mentionedTickets.isNotEmpty()) {
            appendLine()
            appendLine("Mentioned Tickets (${mentionedTickets.size}):")
            appendLine("  ${mentionedTickets.joinToString(", ")}")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)}KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
    }
}

/**
 * Lightweight attachment metadata — enough for the LLM to know what's available
 * and call downloadAttachment with the correct ID.
 */
@Serializable
data class JiraAttachmentData(
    val id: String,
    val filename: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0
)

/**
 * Jira transition — an available status change for a ticket.
 */
@Serializable
data class JiraTransitionData(
    val id: String,
    val name: String,
    val toStatus: String
)

/**
 * Simplified Jira comment domain model shared between UI panels and AI agent.
 */
@Serializable
data class JiraCommentData(
    val id: String,
    val author: String,
    val body: String,
    val created: String
) {
    override fun toString(): String = "[$created] $author: $body"
}

/**
 * Worklog entry for a Jira ticket, shared between UI panels and AI agent.
 */
@Serializable
data class WorklogData(
    val author: String,
    val timeSpent: String,
    val timeSpentSeconds: Long,
    val comment: String?,
    val started: String
) {
    override fun toString(): String = "[$started] $author: $timeSpent${if (!comment.isNullOrBlank()) " — $comment" else ""}"
}

/**
 * Dev-status pull request linked to a Jira ticket.
 */
@Serializable
data class DevStatusPrData(
    val name: String,
    val url: String,
    val status: String,
    val lastUpdate: String?
)

/**
 * Sprint data shared between UI panels and AI agent.
 */
@Serializable
data class SprintData(
    val id: Int,
    val name: String,
    val state: String,
    val startDate: String?,
    val endDate: String?
)

/**
 * Jira board (scrum or kanban) shared between UI panels and AI agent.
 */
@Serializable
data class BoardData(
    val id: Int,
    val name: String,
    val type: String
)

/**
 * Branch linked to a Jira issue via the dev-status API.
 */
@Serializable
data class DevStatusBranchData(
    val name: String,
    val url: String
)

/**
 * Result of a "Start Work" operation: branch creation + ticket transition.
 */
@Serializable
data class StartWorkResultData(
    val branchName: String,
    val ticketKey: String,
    val transitioned: Boolean
)

/**
 * Lightweight subtask reference — key, summary, and current status.
 */
@Serializable
data class JiraSubtaskRef(
    val key: String,
    val summary: String,
    val status: String
)

/**
 * Lightweight linked issue reference — key, summary, status, and relationship type.
 */
@Serializable
data class JiraLinkedIssueRef(
    val key: String,
    val summary: String,
    val status: String,
    val relationship: String  // e.g., "blocks", "is blocked by", "relates to"
)

/**
 * Content and metadata of a downloaded Jira attachment.
 */
@Serializable
data class AttachmentContentData(
    val filename: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val content: String?,
    val filePath: String,
    val attachmentId: String
)
