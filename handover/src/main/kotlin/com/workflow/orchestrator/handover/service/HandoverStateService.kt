package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.handover.model.BuildSummary
import com.workflow.orchestrator.handover.model.HandoverState
import com.workflow.orchestrator.handover.model.SuiteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

@Service(Service.Level.PROJECT)
class HandoverStateService {

    private val log = Logger.getInstance(HandoverStateService::class.java)
    private val workflowService: WorkflowContextService
    private val eventBus: EventBus
    private val settings: PluginSettings
    private val cs: CoroutineScope

    /** IntelliJ DI constructor. */
    constructor(project: Project, cs: CoroutineScope) {
        this.workflowService = WorkflowContextService.getInstance(project)
        this.settings = PluginSettings.getInstance(project)
        this.eventBus = project.getService(EventBus::class.java)
        this.cs = cs
        initialize()
    }

    /** Test constructor — allows injecting mocks. */
    constructor(
        workflowService: WorkflowContextService,
        eventBus: EventBus,
        settings: PluginSettings,
        scope: CoroutineScope,
    ) {
        this.workflowService = workflowService
        this.eventBus = eventBus
        this.settings = settings
        this.cs = scope
        initialize()
    }

    private val _stateFlow = MutableStateFlow(HandoverState())
    val stateFlow: StateFlow<HandoverState> = _stateFlow.asStateFlow()

    private fun initialize() {
        // Initial state from canonical service (which already loaded from settings).
        val initialTicket = workflowService.state.value.activeTicket
        _stateFlow.value = HandoverState(
            ticketId = initialTicket?.key.orEmpty(),
            ticketSummary = initialTicket?.summary.orEmpty(),
            startWorkTimestamp = settings.state.startWorkTimestamp
        )

        // Subscribe to EventBus for non-ticket events (build/quality/health/automation/PR/comment).
        cs.launch {
            eventBus.events.collect { event ->
                handleEvent(event)
            }
        }

        // Phase 5 T13: ticket changes now come from the canonical WorkflowContextService.
        cs.launch {
            workflowService.activeTicketFlow.collect { ticket ->
                log.info("[Handover:State] Ticket changed to ${ticket?.key ?: "<cleared>"}")
                resetForNewTicket(ticket?.key.orEmpty(), ticket?.summary.orEmpty())
            }
        }
    }

    private fun handleEvent(event: WorkflowEvent) {
        log.debug("[Handover:State] Handling event: ${event::class.simpleName}")
        val current = _stateFlow.value
        val next = when (event) {
            is WorkflowEvent.BuildFinished -> current.copy(
                buildStatus = BuildSummary(
                    buildNumber = event.buildNumber,
                    status = event.status,
                    planKey = event.planKey
                )
            )

            is WorkflowEvent.QualityGateResult -> current.copy(qualityGatePassed = event.passed)

            is WorkflowEvent.HealthCheckFinished -> current.copy(healthCheckPassed = event.passed)

            is WorkflowEvent.AutomationTriggered -> {
                val bambooUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/')
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

            is WorkflowEvent.JiraCommentPosted -> current.copy(jiraCommentPosted = true)

            else -> return // Ignore events we don't care about (TicketChanged is now handled by activeTicketFlow)
        }
        _stateFlow.value = next
    }

    fun markCopyrightFixed() {
        log.info("[Handover:State] Marked copyright as fixed")
        _stateFlow.value = _stateFlow.value.copy(copyrightFixed = true)
    }

    fun markJiraTransitioned(statusName: String? = null) {
        log.info("[Handover:State] Marked Jira as transitioned${statusName?.let { " to $it" }.orEmpty()}")
        _stateFlow.value = _stateFlow.value.copy(
            jiraTransitioned = true,
            currentStatusName = statusName ?: _stateFlow.value.currentStatusName
        )
    }

    fun markWorkLogged() {
        log.info("[Handover:State] Marked work as logged")
        _stateFlow.value = _stateFlow.value.copy(todayWorkLogged = true)
    }

    fun resetForNewTicket(ticketId: String, ticketSummary: String) {
        log.info("[Handover:State] Resetting state for new ticket: $ticketId")
        _stateFlow.value = HandoverState(
            ticketId = ticketId,
            ticketSummary = ticketSummary,
            startWorkTimestamp = settings.state.startWorkTimestamp
        )
    }

    companion object {
        fun getInstance(project: Project): HandoverStateService =
            project.getService(HandoverStateService::class.java)
    }
}
