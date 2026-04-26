package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.TicketRef
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One-way bridge: legacy [WorkflowEvent]s on [EventBus] -> [WorkflowContextService] mutators.
 *
 * Loop prevention is structural: the service's mutators never emit [WorkflowEvent]s. Plus
 * a defense-in-depth state-equality guard: the mirror checks whether the event's payload
 * already matches `state.value` before invoking the mutator (covers the migration case in
 * spec §5.3 where a migrated panel calls the mutator AND re-emits the legacy event).
 *
 * Installed at startup by [WorkflowContextProjectActivity] — guarantees subscription
 * before any panel construction (R8).
 */
class WorkflowEventMirror(
    private val project: Project,
    private val service: WorkflowContextService,
) {
    private val log = Logger.getInstance(WorkflowEventMirror::class.java)
    private var collectorJob: Job? = null

    fun install() {
        collectorJob?.cancel()
        collectorJob = service.serviceCs.launch {
            project.service<EventBus>().events.collect { event ->
                when (event) {
                    is WorkflowEvent.PrSelected -> handlePrSelected(event)
                    is WorkflowEvent.TicketChanged -> handleTicketChanged(event)
                    else -> { /* not mirrored */ }
                }
            }
        }
        log.info("[Workflow:Mirror] Installed")
    }

    // No explicit uninstall: collectorJob runs on service.serviceCs which the platform
    // cancels at project close. The idempotent install() above is sufficient to handle
    // any rare ProjectActivity re-run.

    private suspend fun handlePrSelected(event: WorkflowEvent.PrSelected) {
        val incoming = PrRef(
            prId = event.prId,
            fromBranch = event.fromBranch,
            toBranch = event.toBranch,
            repoName = event.repoName,
            bambooPlanKey = event.bambooPlanKey,
            sonarProjectKey = event.sonarProjectKey,
        )
        if (service.state.value.focusPr == incoming) return
        service.focusPr(incoming)
    }

    private suspend fun handleTicketChanged(event: WorkflowEvent.TicketChanged) {
        val incoming = if (event.ticketId.isBlank()) null
                       else TicketRef(event.ticketId, event.ticketSummary)
        if (service.state.value.activeTicket == incoming) return
        service.setActiveTicket(incoming)
    }
}
