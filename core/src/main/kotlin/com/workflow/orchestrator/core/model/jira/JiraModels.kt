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
