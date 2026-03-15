package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.extensions.ExtensionPointName

/**
 * Interface for cross-module Jira ticket access.
 * Implemented by :jira module, consumed by :bamboo (PR dialog) without compile-time dependency.
 */
interface JiraTicketProvider {

    suspend fun getTicketDetails(ticketId: String): TicketDetails?

    suspend fun getAvailableTransitions(ticketId: String): List<TicketTransition>

    suspend fun transitionTicket(ticketId: String, transitionId: String): Boolean

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
