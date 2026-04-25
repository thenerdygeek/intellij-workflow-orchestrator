package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.service.ActiveTicketService
import com.workflow.orchestrator.jira.service.DismissedBranchStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI subscriber that owns the `TicketDetectionPopup` launch path.
 *
 * Subscribes to [WorkflowEvent.TicketDetectedInteractive] on [EventBus] and,
 * on the EDT, shows the confirmation popup. On `onDismiss`, records the
 * branch in [DismissedBranchStore] and emits the banner-only
 * [WorkflowEvent.TicketDetected] (same signal the Sprint tab banner consumes).
 *
 * This service replaces the popup-launch code that previously lived inside
 * `BranchChangeTicketDetector`. The listener is now stateless and UI-free.
 */
@Service(Service.Level.PROJECT)
class TicketDetectionPresenter(
    private val project: Project,
    private val cs: CoroutineScope,
) {

    private val log = Logger.getInstance(TicketDetectionPresenter::class.java)

    init {
        startSubscription()
    }

    private fun startSubscription() {
        val eventBus = project.getService(EventBus::class.java)
        cs.launch(Dispatchers.IO) {
            eventBus.events.collect { event ->
                if (event is WorkflowEvent.TicketDetectedInteractive) {
                    showPopup(event)
                }
            }
        }
    }

    private suspend fun showPopup(event: WorkflowEvent.TicketDetectedInteractive) {
        withContext(Dispatchers.EDT) {
            val frame = WindowManager.getInstance().getFrame(project) ?: return@withContext

            val settings = PluginSettings.getInstance(project)

            TicketDetectionPopup(
                ticketKey = event.ticketKey,
                summary = event.ticketSummary,
                sprint = event.sprint,
                assignee = event.assignee,
                onAccept = {
                    settings.state.activeTicketId = event.ticketKey
                    settings.state.activeTicketSummary = event.ticketSummary
                    ActiveTicketService.getInstance(project)
                        .setActiveTicket(event.ticketKey, event.ticketSummary)
                    log.info("[Jira:Branch] User accepted ticket ${event.ticketKey} as active")
                },
                onDismiss = {
                    DismissedBranchStore.getInstance(project).markDismissed(event.branchName)
                    log.info("[Jira:Branch] User dismissed detection for branch '${event.branchName}'")
                    // Emit banner-only event so Sprint tab shows the detection banner.
                    cs.launch(Dispatchers.IO) {
                        project.getService(EventBus::class.java).emit(
                            WorkflowEvent.TicketDetected(
                                ticketKey = event.ticketKey,
                                ticketSummary = event.ticketSummary,
                                sprint = event.sprint,
                                assignee = event.assignee,
                                branchName = event.branchName
                            )
                        )
                    }
                }
            ).show(frame)
        }
    }

    companion object {
        fun getInstance(project: Project): TicketDetectionPresenter = project.service()
    }
}
