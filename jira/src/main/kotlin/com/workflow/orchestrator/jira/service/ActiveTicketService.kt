package com.workflow.orchestrator.jira.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ActiveTicketState(
    val ticketId: String,
    val summary: String,
)

/**
 * Phase 5 T13 — facade over [WorkflowContextService.activeTicketFlow].
 *
 * Preserves the existing public synchronous-write API for legacy `:jira` callers
 * (BranchingService, JiraSearchContributorFactory, TicketDetectionPresenter,
 * SprintDashboardPanel.acceptDetectedTicket) which write then immediately read
 * `activeTicketId` on the same call stack. Local `_localFlow` is updated synchronously;
 * the canonical write to the service is dispatched on the platform-injected scope.
 *
 * Per spec §5.3, the legacy [WorkflowEvent.TicketChanged] event is re-emitted exactly
 * once here (in the facade) for back-compat subscribers. The mirror's state-equality
 * guard at `WorkflowEventMirror:62` no-ops since the canonical state.activeTicket
 * already matches.
 *
 * Will be deleted in Phase 5b once all callers migrate to `WorkflowContextService` directly.
 */
@Service(Service.Level.PROJECT)
class ActiveTicketService(
    private val project: Project,
    private val cs: CoroutineScope,
) {
    private val log = Logger.getInstance(ActiveTicketService::class.java)
    private val service get() = WorkflowContextService.getInstance(project)

    // Local synchronous cache — preserves the existing sync setActiveTicket -> read contract.
    // Initialized from the canonical service on construction.
    private val _localFlow = MutableStateFlow<ActiveTicketState?>(
        service.state.value.activeTicket?.let { ActiveTicketState(it.key, it.summary) }
    )

    val activeTicketFlow: StateFlow<ActiveTicketState?> = _localFlow.asStateFlow()
    val activeTicketId: String? get() = _localFlow.value?.ticketId
    val activeTicketSummary: String? get() = _localFlow.value?.summary

    init {
        // Mirror canonical state INTO the local cache so cross-tab updates (from the
        // event mirror or other writers) reflect here.
        cs.launch {
            service.activeTicketFlow.collect { ticket ->
                _localFlow.value = ticket?.let { ActiveTicketState(it.key, it.summary) }
            }
        }
    }

    /**
     * Synchronous: updates local cache immediately so callers reading on the next line
     * see the new value. Dispatches the canonical write + legacy event re-emit to background.
     */
    fun setActiveTicket(ticketId: String, summary: String) {
        val previous = _localFlow.value?.ticketId
        _localFlow.value = ActiveTicketState(ticketId, summary)
        if (previous != null && previous != ticketId) {
            log.info("[Jira:Ticket] Active ticket changed from $previous to $ticketId")
        } else if (previous == null) {
            log.info("[Jira:Ticket] Active ticket set to $ticketId")
        }
        cs.launch {
            service.setActiveTicket(TicketRef(ticketId, summary))
            // Per spec §5.3: re-emit legacy event for back-compat subscribers.
            // The mirror's state-equality guard no-ops since state.activeTicket already matches.
            project.getService(EventBus::class.java)
                .emit(WorkflowEvent.TicketChanged(ticketId, summary))
        }
    }

    fun clearActiveTicket() {
        val previous = _localFlow.value?.ticketId
        _localFlow.value = null
        if (previous != null) {
            log.info("[Jira:Ticket] Active ticket cleared (was $previous)")
        }
        cs.launch {
            service.setActiveTicket(null)
            project.getService(EventBus::class.java)
                .emit(WorkflowEvent.TicketChanged("", ""))
        }
    }

    companion object {
        private val companionLog = Logger.getInstance(ActiveTicketService::class.java)
        private val TICKET_PATTERN = Regex("([A-Z][A-Z0-9]+-\\d+)")

        @JvmStatic
        fun getInstance(project: Project): ActiveTicketService =
            project.getService(ActiveTicketService::class.java)

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
