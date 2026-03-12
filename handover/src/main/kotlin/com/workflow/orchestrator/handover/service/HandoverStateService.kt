package com.workflow.orchestrator.handover.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.handover.model.BuildSummary
import com.workflow.orchestrator.handover.model.HandoverState
import com.workflow.orchestrator.handover.model.SuiteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

@Service(Service.Level.PROJECT)
class HandoverStateService : Disposable {

    private val eventBus: EventBus
    private val settings: PluginSettings
    private val scope: CoroutineScope

    /** IntelliJ DI constructor. */
    constructor(project: Project) {
        this.settings = PluginSettings.getInstance(project)
        this.eventBus = project.getService(EventBus::class.java)
        this.scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        initialize()
    }

    /** Test constructor — allows injecting mocks. */
    constructor(eventBus: EventBus, settings: PluginSettings, scope: CoroutineScope) {
        this.eventBus = eventBus
        this.settings = settings
        this.scope = scope
        initialize()
    }

    private val _stateFlow = MutableStateFlow(HandoverState())
    val stateFlow: StateFlow<HandoverState> = _stateFlow.asStateFlow()

    private fun initialize() {
        // Set initial state from settings
        _stateFlow.value = HandoverState(
            ticketId = settings.state.activeTicketId.orEmpty(),
            ticketSummary = settings.state.activeTicketSummary.orEmpty(),
            startWorkTimestamp = settings.state.startWorkTimestamp
        )

        // Subscribe to EventBus (handles all cross-module events including ticket changes)
        scope.launch {
            eventBus.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: WorkflowEvent) {
        val current = _stateFlow.value
        _stateFlow.value = when (event) {
            is WorkflowEvent.BuildFinished -> current.copy(
                buildStatus = BuildSummary(
                    buildNumber = event.buildNumber,
                    status = event.status,
                    planKey = event.planKey
                )
            )

            is WorkflowEvent.QualityGateResult -> current.copy(
                qualityGatePassed = event.passed
            )

            is WorkflowEvent.HealthCheckFinished -> current.copy(
                healthCheckPassed = event.passed
            )

            is WorkflowEvent.AutomationTriggered -> {
                val bambooUrl = settings.state.bambooUrl.orEmpty().trimEnd('/')
                val newSuite = SuiteResult(
                    suitePlanKey = event.suitePlanKey,
                    buildResultKey = event.buildResultKey,
                    dockerTagsJson = event.dockerTagsJson,
                    passed = null,
                    durationMs = null,
                    triggeredAt = Instant.now(),
                    bambooLink = "$bambooUrl/browse/${event.buildResultKey}"
                )
                // Replace existing entry for same suite plan key (latest run wins)
                val updated = current.suiteResults
                    .filter { it.suitePlanKey != event.suitePlanKey } + newSuite
                current.copy(suiteResults = updated)
            }

            is WorkflowEvent.AutomationFinished -> {
                val updated = current.suiteResults.map { suite ->
                    if (suite.buildResultKey == event.buildResultKey) {
                        suite.copy(passed = event.passed, durationMs = event.durationMs)
                    } else suite
                }
                current.copy(suiteResults = updated)
            }

            is WorkflowEvent.PullRequestCreated -> current.copy(
                prUrl = event.prUrl,
                prCreated = true
            )

            is WorkflowEvent.JiraCommentPosted -> current.copy(
                jiraCommentPosted = true
            )

            is WorkflowEvent.TicketChanged -> {
                resetForNewTicket(event.ticketId, event.ticketSummary)
                _stateFlow.value // resetForNewTicket already updates _stateFlow
            }

            else -> current // Ignore events we don't care about
        }
    }

    fun markCopyrightFixed() {
        _stateFlow.value = _stateFlow.value.copy(copyrightFixed = true)
    }

    fun markJiraTransitioned(statusName: String? = null) {
        _stateFlow.value = _stateFlow.value.copy(
            jiraTransitioned = true,
            currentStatusName = statusName ?: _stateFlow.value.currentStatusName
        )
    }

    fun markWorkLogged() {
        _stateFlow.value = _stateFlow.value.copy(todayWorkLogged = true)
    }

    fun resetForNewTicket(ticketId: String, ticketSummary: String) {
        _stateFlow.value = HandoverState(
            ticketId = ticketId,
            ticketSummary = ticketSummary,
            startWorkTimestamp = settings.state.startWorkTimestamp
        )
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): HandoverStateService {
            return project.getService(HandoverStateService::class.java)
        }
    }
}
