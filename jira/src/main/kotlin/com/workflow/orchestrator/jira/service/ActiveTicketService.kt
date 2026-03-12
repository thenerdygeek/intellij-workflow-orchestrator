package com.workflow.orchestrator.jira.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveTicketState(
    val ticketId: String,
    val summary: String
)

class ActiveTicketService {

    private val _activeTicketFlow = MutableStateFlow<ActiveTicketState?>(null)
    val activeTicketFlow: StateFlow<ActiveTicketState?> = _activeTicketFlow.asStateFlow()

    val activeTicketId: String? get() = _activeTicketFlow.value?.ticketId
    val activeTicketSummary: String? get() = _activeTicketFlow.value?.summary

    fun setActiveTicket(ticketId: String, summary: String) {
        _activeTicketFlow.value = ActiveTicketState(ticketId, summary)
    }

    fun clearActiveTicket() {
        _activeTicketFlow.value = null
    }

    companion object {
        private val TICKET_PATTERN = Regex("([A-Z][A-Z0-9]+-\\d+)")

        fun extractTicketIdFromBranch(branchName: String): String? {
            return TICKET_PATTERN.find(branchName)?.groupValues?.get(1)
        }
    }
}
