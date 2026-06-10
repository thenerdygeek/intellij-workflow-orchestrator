package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.ConnectionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class AgentTabProvider : WorkflowTabProvider {

    override val tabTitle: String = "Agent"
    override val order: Int = 5

    override fun createPanel(project: Project): JComponent {
        val agentSettings = AgentSettings.getInstance(project)
        val connections = ConnectionSettings.getInstance()

        if (!agentSettings.state.agentEnabled) {
            return EmptyStatePanel(
                project,
                "Agent features are disabled.\nEnable them in Settings > Workflow Orchestrator > Agent."
            )
        }

        if (connections.state.sourcegraphUrl.isBlank()) {
            return EmptyStatePanel(
                project,
                "No Sourcegraph connection configured.\nConnect to Sourcegraph in Settings to use Agent features."
            )
        }

        // Create a self-parented dashboard (P0-6/B4): AgentDashboardPanel is Disposable
        // and owns the JCEF lifecycle. The tool-window factory wires
        // content.setDisposer(dashboard), so when this tab's Content is removed
        // (rebuild via "Refresh All Tabs"/settings change, or tool-window close) the
        // whole subtree -- Chromium browser, controller, EventBus scope -- dies with it
        // instead of surviving parented to the Project until project close.
        val dashboard = AgentDashboardPanel()
        try {
            val controller = AgentController(project, dashboard)

            // Chain controller disposal from the dashboard FIRST, so the failure path
            // below (Disposer.dispose(dashboard)) also tears the controller down if any
            // later wiring line throws. AgentController.dispose() cancels its
            // controllerScope, clears the auto-wake listener, and compare-and-nulls
            // itself in AgentControllerRegistry (race-safe: a newer controller that
            // already re-registered is never clobbered).
            Disposer.register(dashboard, controller)

            // Wire mention search provider for @mention and #ticket autocomplete.
            // Shared between dashboard (autocomplete + validation) and controller (context building)
            // so that pre-fetched ticket data from validation is reused on send.
            val mentionSearchProvider = MentionSearchProvider(project)
            dashboard.setMentionSearchProvider(mentionSearchProvider)
            controller.setMentionSearchProvider(mentionSearchProvider)

            // Subscribe to Sprint tab data so # ticket autocomplete is instant (no re-fetch).
            // The scope is chained to the dashboard panel (Content-scoped, NOT project-scoped):
            // on tab rebuild the old Content disposes the dashboard, which cancels this scope --
            // previously the collector kept firing into a detached browser until project close (B4).
            val eventBus = project.getService(EventBus::class.java)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope.launch {
                eventBus.events.collect { event ->
                    if (event is WorkflowEvent.SprintDataLoaded) {
                        mentionSearchProvider.onSprintDataLoaded(event.tickets)
                    }
                }
            }
            Disposer.register(dashboard, Disposable { scope.cancel() })

            // Register controller in registry for cross-module access (e.g., AgentChatRedirect).
            // On rebuild the OLD content is disposed first (its controller compare-and-nulls
            // the registry), then this fresh controller registers on first tab selection --
            // so the registry never points at a disposed controller. Callers already
            // null-check getController(); between rebuild and first selection it is null.
            AgentControllerRegistry.getInstance(project).controller = controller
        } catch (e: Exception) {
            // The dashboard is self-parented: if wiring fails after construction, nothing
            // else roots it (the factory only wires content.setDisposer on the RETURNED
            // panel), so the live Chromium would leak unrooted until JVM exit. Dispose the
            // whole subtree (browser + any already-registered children) and rethrow -- the
            // tool-window factory turns the exception into an EmptyStatePanel.
            Disposer.dispose(dashboard)
            throw e
        }

        return dashboard
    }
}
