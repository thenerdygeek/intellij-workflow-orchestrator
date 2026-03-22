package com.workflow.orchestrator.core.model.jira

import kotlinx.serialization.Serializable

/**
 * Simplified Jira ticket domain model shared between UI panels and AI agent.
 * Contains only the fields both consumers need, not the 50+ fields from the raw API response.
 */
@Serializable
data class JiraTicketData(
    val key: String,
    val summary: String,
    val status: String,
    val assignee: String?,
    val type: String,
    val priority: String?,
    val description: String?,
    val labels: List<String> = emptyList(),
    val transitions: List<JiraTransitionData> = emptyList()
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
)

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
)

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
