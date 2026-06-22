package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.extensions.ExtensionPointName

/**
 * Interface for cross-module Jira ticket access.
 * Implemented by :jira module, consumed by :core / :pullrequest without compile-time dependency.
 *
 * Transition operations live on [com.workflow.orchestrator.core.services.jira.TicketTransitionService]
 * (rich [com.workflow.orchestrator.core.model.jira.TransitionMeta] payload, dialog routing via
 * [com.workflow.orchestrator.core.services.jira.TransitionDialogOpener]). The legacy
 * `transitionTicket` / `getAvailableTransitions` / `showTransitionDialog` methods on this
 * interface were removed in the unified-transition-UX redesign.
 */
interface JiraTicketProvider {

    /** Lower runs first; the shipped impl sits at the lowest priority (Int.MAX_VALUE). */
    val order: Int get() = 0

    suspend fun getTicketDetails(ticketId: String): TicketDetails?

    /**
     * Fetches a rich [TicketContext] for the given ticket key, including status,
     * priority, comments, components, fix versions, and acceptance criteria.
     * Returns null if the ticket cannot be fetched (network error, not found, etc.).
     */
    suspend fun getTicketContext(key: String): TicketContext?

    companion object {
        val EP_NAME = ExtensionPointName.create<JiraTicketProvider>(
            "com.workflow.orchestrator.jiraTicketProvider"
        )

        fun getInstance(): JiraTicketProvider? = lowestOrderOf(EP_NAME.extensionList)

        /** Pure selection: the lowest-[order] provider, or null if none. Split out from the platform
         *  extension-list fetch in [getInstance] so the ordering rule is unit-testable without a fixture. */
        internal fun lowestOrderOf(providers: List<JiraTicketProvider>): JiraTicketProvider? =
            providers.minByOrNull { it.order }
    }
}

data class TicketDetails(
    val key: String,
    val summary: String,
    val description: String?,
    val type: String?,
    val labels: List<String> = emptyList(),
    val components: List<String> = emptyList()
)
