package com.workflow.orchestrator.handover.service

import app.cash.turbine.test
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.BuildRef
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.QualityScope
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase 7 T-Handover-a: PR-scoped event filtering and focus-change reset.
 *
 * Tests that:
 * 1. Status slices reset when [focusPr] changes within the same ticket.
 * 2. [WorkflowEvent.BuildFinished] is accepted only when planKey matches [focusBuild.planKey].
 * 3. [WorkflowEvent.QualityGateResult] is accepted only when projectKey matches [focusQualityScope.sonarProjectKey].
 * 4. [WorkflowEvent.PullRequestCreated] is accepted only when ticketId matches [activeTicket.key].
 * 5. [WorkflowEvent.HealthCheckFinished] is always accepted (no key in payload).
 * 6. [WorkflowEvent.AutomationFinished] is always accepted (no direct chainKey link — limitation
 *    tracked in phase7-handover-context-plan.md queue item #3).
 */
class HandoverStateServicePrScopingTest {

    private lateinit var eventBus: EventBus
    private lateinit var settings: PluginSettings
    private lateinit var contextFlow: MutableStateFlow<WorkflowContext>
    private lateinit var activeTicketFlow: MutableStateFlow<TicketRef?>
    private lateinit var workflowService: WorkflowContextService
    private lateinit var scope: CoroutineScope
    private lateinit var service: HandoverStateService

    private val ticket = TicketRef("PROJ-123", "Add login feature")

    private val focusPrA = PrRef(
        prId = 1,
        fromBranch = "feature/PROJ-123-a",
        toBranch = "main",
        repoName = "my-repo",
        bambooPlanKey = "PROJ-PLAN",
        sonarProjectKey = "proj-key",
    )
    private val focusPrB = PrRef(
        prId = 2,
        fromBranch = "feature/PROJ-123-b",
        toBranch = "main",
        repoName = "my-repo",
        bambooPlanKey = "PROJ-PLAN",
        sonarProjectKey = "proj-key",
    )

    private val focusBuild = BuildRef(
        planKey = "PROJ-BUILD",
        buildNumber = 100,
        branch = "feature/PROJ-123-a",
        selectedJobKey = null,
    )
    private val focusQuality = QualityScope(
        sonarProjectKey = "proj-sonar-key",
        branchName = "feature/PROJ-123-a",
        moduleKey = null,
    )

    @BeforeEach
    fun setUp() {
        eventBus = EventBus()
        settings = mockk(relaxed = true)
        every { settings.state } returns PluginSettings.State().apply {
            activeTicketId = "PROJ-123"
            activeTicketSummary = "Add login feature"
        }
        every { settings.connections.bambooUrl } returns "https://bamboo.example.com"

        contextFlow = MutableStateFlow(
            WorkflowContext(
                activeTicket = ticket,
                focusPr = focusPrA,
                focusBuild = focusBuild,
                focusQualityScope = focusQuality,
            )
        )
        activeTicketFlow = MutableStateFlow(ticket)
        workflowService = mockk(relaxed = true)
        every { workflowService.state } returns contextFlow
        every { workflowService.activeTicketFlow } returns activeTicketFlow

        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        service = HandoverStateService(workflowService, eventBus, settings, scope)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    // ── Focus-change reset ────────────────────────────────────────────────────

    @Test
    fun `focus-change reset clears status slices but preserves action slices`() = runTest {
        // Seed some status and action state.
        service.markCopyrightFixed()
        service.markWorkLogged()
        service.markJiraTransitioned("Done")
        eventBus.emit(WorkflowEvent.BuildFinished("PROJ-BUILD", 42, WorkflowEvent.BuildEventStatus.SUCCESS))
        eventBus.emit(WorkflowEvent.QualityGateResult("proj-sonar-key", true))
        eventBus.emit(WorkflowEvent.AutomationTriggered("PROJ-REGR", "PROJ-REGR-42", "{}", "user"))
        eventBus.emit(WorkflowEvent.PullRequestCreated("https://bitbucket.example.com/pr/42", 42, "PROJ-123"))
        yield()

        // Verify state is seeded before the reset.
        val beforeReset = service.stateFlow.value
        assertNotNull(beforeReset.buildStatus)
        assertTrue(beforeReset.qualityGatePassed!!)
        assertEquals(1, beforeReset.suiteResults.size)
        assertTrue(beforeReset.prCreated)
        assertTrue(beforeReset.copyrightFixed)
        assertTrue(beforeReset.todayWorkLogged)
        assertTrue(beforeReset.jiraTransitioned)

        service.stateFlow.test {
            skipItems(1) // consume current snapshot

            // Trigger a focusPr change within the same ticket.
            contextFlow.value = contextFlow.value.copy(focusPr = focusPrB)

            val afterReset = awaitItem()

            // Status slices cleared.
            assertNull(afterReset.buildStatus)
            assertNull(afterReset.qualityGatePassed)
            assertNull(afterReset.healthCheckPassed)
            assertTrue(afterReset.suiteResults.isEmpty())
            assertFalse(afterReset.prCreated)
            assertNull(afterReset.prUrl)
            assertFalse(afterReset.jiraCommentPosted)

            // Action slices (ticket-level) preserved.
            assertTrue(afterReset.copyrightFixed)
            assertTrue(afterReset.todayWorkLogged)
            assertTrue(afterReset.jiraTransitioned)
            assertEquals("Done", afterReset.currentStatusName)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `focus-change to null clears status slices`() = runTest {
        eventBus.emit(WorkflowEvent.BuildFinished("PROJ-BUILD", 42, WorkflowEvent.BuildEventStatus.SUCCESS))
        yield()

        service.stateFlow.test {
            skipItems(1)

            contextFlow.value = contextFlow.value.copy(focusPr = null)

            val afterReset = awaitItem()
            assertNull(afterReset.buildStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── BuildFinished PR-scoping ──────────────────────────────────────────────

    @Test
    fun `BuildFinished accepted when planKey matches focusBuild`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.BuildFinished("PROJ-BUILD", 99, WorkflowEvent.BuildEventStatus.SUCCESS))

            val state = awaitItem()
            assertNotNull(state.buildStatus)
            assertEquals(99, state.buildStatus!!.buildNumber)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BuildFinished rejected when planKey does not match focusBuild`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.BuildFinished("OTHER-BUILD", 99, WorkflowEvent.BuildEventStatus.SUCCESS))

            // No new emission expected — state unchanged.
            expectNoEvents()
            assertNull(service.stateFlow.value.buildStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BuildFinished rejected when focusBuild is null`() = runTest {
        // Clear focusBuild on the context.
        contextFlow.value = contextFlow.value.copy(focusBuild = null)
        yield()

        service.stateFlow.test {
            skipItems(1) // consume focusPr-change reset emission

            eventBus.emit(WorkflowEvent.BuildFinished("PROJ-BUILD", 99, WorkflowEvent.BuildEventStatus.SUCCESS))

            expectNoEvents()
            assertNull(service.stateFlow.value.buildStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── QualityGateResult PR-scoping ─────────────────────────────────────────

    @Test
    fun `QualityGateResult accepted when projectKey matches focusQualityScope`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.QualityGateResult("proj-sonar-key", true))

            val state = awaitItem()
            assertTrue(state.qualityGatePassed!!)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `QualityGateResult rejected when projectKey does not match focusQualityScope`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.QualityGateResult("other-sonar-key", true))

            expectNoEvents()
            assertNull(service.stateFlow.value.qualityGatePassed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `QualityGateResult rejected when focusQualityScope is null`() = runTest {
        contextFlow.value = contextFlow.value.copy(focusQualityScope = null)
        yield()

        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.QualityGateResult("proj-sonar-key", true))

            expectNoEvents()
            assertNull(service.stateFlow.value.qualityGatePassed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── PullRequestCreated PR-scoping ─────────────────────────────────────────

    @Test
    fun `PullRequestCreated accepted when ticketId matches activeTicket`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.PullRequestCreated("https://bb.example.com/pr/1", 1, "PROJ-123"))

            val state = awaitItem()
            assertTrue(state.prCreated)
            assertEquals("https://bb.example.com/pr/1", state.prUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `PullRequestCreated rejected when ticketId does not match activeTicket`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.PullRequestCreated("https://bb.example.com/pr/99", 99, "OTHER-456"))

            expectNoEvents()
            assertFalse(service.stateFlow.value.prCreated)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `JiraCommentPosted accepted when ticketId matches activeTicket`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.JiraCommentPosted("PROJ-123", "comment-1"))

            val state = awaitItem()
            assertTrue(state.jiraCommentPosted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `JiraCommentPosted rejected when ticketId does not match activeTicket`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.JiraCommentPosted("OTHER-456", "comment-99"))

            expectNoEvents()
            assertFalse(service.stateFlow.value.jiraCommentPosted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── HealthCheckFinished unfiltered ────────────────────────────────────────

    @Test
    fun `HealthCheckFinished accepted regardless of focus state`() = runTest {
        // Clear all focus — HealthCheckFinished has no key in payload so it must always pass.
        contextFlow.value = WorkflowContext(activeTicket = ticket)
        yield()

        service.stateFlow.test {
            skipItems(1) // consume focusPr-change reset emission

            eventBus.emit(WorkflowEvent.HealthCheckFinished(passed = true, results = emptyMap(), durationMs = 500L))

            val state = awaitItem()
            assertTrue(state.healthCheckPassed!!)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `HealthCheckFinished works with no active ticket`() = runTest {
        contextFlow.value = WorkflowContext() // no ticket, no focus
        yield()

        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.HealthCheckFinished(passed = false, results = mapOf("lint" to false), durationMs = 200L))

            val state = awaitItem()
            assertFalse(state.healthCheckPassed!!)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── HANDOVER-COV-3: PullRequestCreated dropped when activeTicket is null ────

    @Test
    fun `PullRequestCreated dropped when activeTicket is null`() = runTest {
        // Clear active ticket on context — simulates no active ticket
        contextFlow.value = WorkflowContext()
        activeTicketFlow.value = null
        yield()

        service.stateFlow.test {
            skipItems(1) // consume the focus-change reset emission

            eventBus.emit(
                WorkflowEvent.PullRequestCreated(
                    prUrl = "https://bb.example.com/pr/99",
                    prNumber = 99,
                    ticketId = "PROJ-123",
                )
            )

            // null activeTicket → null == "PROJ-123" is false → event is dropped
            expectNoEvents()
            assertFalse(service.stateFlow.value.prCreated, "prCreated must remain false when activeTicket is null")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── AutomationFinished unfiltered (known limitation) ─────────────────────

    @Test
    fun `AutomationFinished accepted regardless of focus state (limitation - no chainKey link)`() = runTest {
        // Populate a suite first so AutomationFinished has something to update.
        eventBus.emit(WorkflowEvent.AutomationTriggered("PROJ-REGR", "PROJ-REGR-42", "{}", "user"))
        yield()

        service.stateFlow.test {
            skipItems(1)

            // AutomationFinished from a different suitePlanKey family is still accepted because
            // automation suites lack a direct focusBuild.chainKey relationship.
            // This is a known limitation tracked in phase7-handover-context-plan.md queue item #3.
            eventBus.emit(WorkflowEvent.AutomationFinished(
                suitePlanKey = "PROJ-REGR",
                buildResultKey = "PROJ-REGR-42",
                passed = true,
                durationMs = 60_000L,
            ))

            val state = awaitItem()
            assertTrue(state.suiteResults.first { it.buildResultKey == "PROJ-REGR-42" }.passed!!)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
