package com.workflow.orchestrator.jira.service

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveTicketState(
    val ticketId: String,
    val summary: String
)

class ActiveTicketService {
    private val log = Logger.getInstance(ActiveTicketService::class.java)

    private val _activeTicketFlow = MutableStateFlow<ActiveTicketState?>(null)
    val activeTicketFlow: StateFlow<ActiveTicketState?> = _activeTicketFlow.asStateFlow()

    val activeTicketId: String? get() = _activeTicketFlow.value?.ticketId
    val activeTicketSummary: String? get() = _activeTicketFlow.value?.summary

    fun setActiveTicket(ticketId: String, summary: String) {
        val previous = _activeTicketFlow.value?.ticketId
        _activeTicketFlow.value = ActiveTicketState(ticketId, summary)
        if (previous != null && previous != ticketId) {
            log.info("[Jira:Ticket] Active ticket changed from $previous to $ticketId")
        } else if (previous == null) {
            log.info("[Jira:Ticket] Active ticket set to $ticketId")
        }
    }

    fun clearActiveTicket() {
        val previous = _activeTicketFlow.value?.ticketId
        _activeTicketFlow.value = null
        if (previous != null) {
            log.info("[Jira:Ticket] Active ticket cleared (was $previous)")
        }
    }

    companion object {
        private val companionLog = Logger.getInstance(ActiveTicketService::class.java)
        private val TICKET_PATTERN = Regex("([A-Z][A-Z0-9]+-\\d+)")

        fun extractTicketIdFromBranch(branchName: String): String? {
            val ticketId = TICKET_PATTERN.find(branchName)?.groupValues?.get(1)
            if (ticketId != null) {
                companionLog.info("[Jira:Ticket] Detected ticket $ticketId from branch '$branchName'")
            } else {
                companionLog.debug("[Jira:Ticket] No ticket ID found in branch '$branchName'")
            }
            return ticketId
        }
    }
}
