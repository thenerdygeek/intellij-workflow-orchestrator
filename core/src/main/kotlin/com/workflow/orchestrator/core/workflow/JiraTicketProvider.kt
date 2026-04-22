package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Interface for cross-module Jira ticket access.
 * Implemented by :jira module, consumed by :bamboo (PR dialog) without compile-time dependency.
 */
interface JiraTicketProvider {

    suspend fun getTicketDetails(ticketId: String): TicketDetails?

    /**
     * Fetches a rich [TicketContext] for the given ticket key, including status,
     * priority, comments, components, fix versions, and acceptance criteria.
     * Returns null if the ticket cannot be fetched (network error, not found, etc.).
     */
    suspend fun getTicketContext(key: String): TicketContext?

    suspend fun getAvailableTransitions(ticketId: String): List<TicketTransition>

    suspend fun transitionTicket(ticketId: String, transitionId: String): Boolean

    /**
     * Shows a transition dialog if the transition has mandatory fields,
     * or executes the transition immediately if no fields required.
     * @param onTransitioned callback after successful transition
     */
    fun showTransitionDialog(
        project: Project,
        ticketId: String,
        onTransitioned: () -> Unit = {}
    )

    companion object {
        val EP_NAME = ExtensionPointName.create<JiraTicketProvider>(
            "com.workflow.orchestrator.jiraTicketProvider"
        )

        fun getInstance(): JiraTicketProvider? =
            EP_NAME.extensionList.firstOrNull()
    }
}

data class TicketDetails(
    val key: String,
    val summary: String,
    val description: String?,
    val type: String?
)

data class TicketTransition(
    val id: String,
    val name: String,
    val targetStatus: String
)
