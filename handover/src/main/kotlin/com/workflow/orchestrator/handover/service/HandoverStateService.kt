package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.handover.model.BuildSummary
import com.workflow.orchestrator.handover.model.HandoverState
import com.workflow.orchestrator.handover.model.SuiteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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

        // Phase 7 T-Handover-a: reset status slices when focusPr changes, even within the
        // same ticket. Switching focused PR means stale build/quality/suite data no longer
        // applies. The user-actioned slices (copyrightFixed, todayWorkLogged, jiraTransitioned)
        // are ticket-level, not PR-level, so they are preserved through focus changes.
        // See docs/architecture/phase7-handover-context-plan.md §5.4 and §T-Handover-a.
        cs.launch {
            workflowService.state
                .map { it.focusPr }
                .distinctUntilChanged()
                .drop(1) // skip initial value; ticket-reset / boot already covers it
                .collect { newFocusPr ->
                    log.info("[Handover:State] focusPr changed to ${newFocusPr?.prId ?: "<cleared>"} — resetting status slices")
                    resetStatusSlices()
                }
        }
    }

    /**
     * Resets only the status-derived slices of [HandoverState] when [WorkflowContextService.state]'s
     * [focusPr] changes. Ticket-level user-actioned slices ([HandoverState.copyrightFixed],
     * [HandoverState.todayWorkLogged], [HandoverState.jiraTransitioned]) are preserved because they
     * are bound to the active ticket, not to a specific PR.
     *
     * Design decision (T-Handover-a): copyrightFixed / todayWorkLogged / jiraTransitioned are NOT
     * cleared on PR focus change — copyright fix and time log are ticket-level actions a developer
     * performs once per ticket regardless of how many PRs are in flight. Clearing them on every
     * focus switch would cause spurious "action needed" dots every time the user switches tabs.
     */
    private fun resetStatusSlices() {
        _stateFlow.update { current ->
            current.copy(
                buildStatus = null,
                qualityGatePassed = null,
                healthCheckPassed = null,
                suiteResults = emptyList(),
                prCreated = false,
                prUrl = null,
                jiraCommentPosted = false,
            )
        }
    }

    /**
     * PR-scope filter for event handlers.
     *
     * Architecture decision (T-Handover-a, Option C): `:handover` depends only on `:core` and
     * therefore cannot import `BuildMonitorService` (`:bamboo`) or `QueueService` (`:automation`)
     * directly. A direct cross-module dependency would violate the module-graph rule. Instead,
     * status hydration uses the existing `EventBus` with PR-scoped filtering anchored to
     * `WorkflowContextService.state.value` (the focus snapshot). This is structurally correct for
     * this task; bridging via EPs is deferred to a future task if needed.
     *
     * Filtering rules:
     * - [WorkflowEvent.BuildFinished]: match `event.planKey` == `focusBuild.planKey`. Branch-level
     *   match is not possible because `BuildFinished` carries no branch field (limitation noted in
     *   the queue).
     * - [WorkflowEvent.QualityGateResult]: match `event.projectKey` == `focusQualityScope.sonarProjectKey`.
     * - [WorkflowEvent.PullRequestCreated], [WorkflowEvent.JiraCommentPosted]: match `event.ticketId`
     *   == `activeTicket.key`.
     * - [WorkflowEvent.HealthCheckFinished]: always in-scope — no key in payload.
     * - [WorkflowEvent.AutomationTriggered] / [WorkflowEvent.AutomationFinished]: always in-scope for
     *   now — automation suites are separate Bamboo plans with no direct chainKey link to `focusBuild`.
     *   This is a known limitation; see queue item in phase7-handover-context-plan.md.
     */
    private fun isInScope(event: WorkflowEvent): Boolean {
        val ctx = workflowService.state.value
        return when (event) {
            is WorkflowEvent.BuildFinished ->
                ctx.focusBuild?.planKey == event.planKey

            is WorkflowEvent.QualityGateResult ->
                ctx.focusQualityScope?.sonarProjectKey == event.projectKey

            is WorkflowEvent.PullRequestCreated ->
                ctx.activeTicket?.key == event.ticketId

            is WorkflowEvent.JiraCommentPosted ->
                ctx.activeTicket?.key == event.ticketId

            // HealthCheckFinished, AutomationTriggered, AutomationFinished — unfiltered.
            // HealthCheckFinished has no key in payload.
            // Automation events lack a direct focusBuild.chainKey link (tracked in queue).
            else -> true
        }
    }

    private fun handleEvent(event: WorkflowEvent) {
        if (!isInScope(event)) {
            log.debug("[Handover:State] Event out of scope, skipping: ${event::class.simpleName}")
            return
        }
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

    fun markJiraCommentPosted() {
        log.info("[Handover:State] Marked Jira comment as posted")
        _stateFlow.value = _stateFlow.value.copy(jiraCommentPosted = true)
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
