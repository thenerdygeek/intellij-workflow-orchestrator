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

        // Create dashboard with project as parent disposable for JCEF lifecycle
        val dashboard = AgentDashboardPanel(parentDisposable = project as? Disposable)
        val controller = AgentController(project, dashboard)

        // Wire mention search provider for @mention and #ticket autocomplete.
        // Shared between dashboard (autocomplete + validation) and controller (context building)
        // so that pre-fetched ticket data from validation is reused on send.
        val mentionSearchProvider = MentionSearchProvider(project)
        dashboard.setMentionSearchProvider(mentionSearchProvider)
        controller.setMentionSearchProvider(mentionSearchProvider)

        // Subscribe to Sprint tab data so # ticket autocomplete is instant (no re-fetch).
        // The scope is tied directly to the project Disposable so it is always cancelled
        // on project close regardless of the cast path. Using Disposer.register on the
        // concrete project Disposable (never null for a live Project) avoids the silent
        // scope leak that the previous (project as? Disposable)?.let { … } guard caused
        // in test harnesses where the cast returns null. Closes audit finding agent-ui:F-6.
        val eventBus = project.getService(EventBus::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            eventBus.events.collect { event ->
                if (event is WorkflowEvent.SprintDataLoaded) {
                    mentionSearchProvider.onSprintDataLoaded(event.tickets)
                }
            }
        }
        Disposer.register(project as Disposable, Disposable { scope.cancel() })

        // Register controller in registry for cross-module access (e.g., AgentChatRedirect)
        AgentControllerRegistry.getInstance(project).controller = controller

        // Register controller for disposal (same direct-cast pattern as the scope above)
        Disposer.register(project as Disposable, Disposable { controller.dispose() })

        return dashboard
    }
}
