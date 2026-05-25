package com.workflow.orchestrator.handover.service

import app.cash.turbine.test
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.BuildRef
import com.workflow.orchestrator.core.model.workflow.QualityScope
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.handover.model.BuildSummary
import com.workflow.orchestrator.handover.model.HandoverState
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

class HandoverStateServiceTest {

    private lateinit var workflowService: WorkflowContextService
    private lateinit var activeTicketFlow: MutableStateFlow<TicketRef?>
    private lateinit var eventBus: EventBus
    private lateinit var settings: PluginSettings
    private lateinit var scope: CoroutineScope
    private lateinit var service: HandoverStateService

    @BeforeEach
    fun setUp() {
        eventBus = EventBus()
        settings = mockk(relaxed = true)
        every { settings.state } returns PluginSettings.State().apply {
            activeTicketId = "PROJ-123"
            activeTicketSummary = "Add login feature"
        }
        every { settings.connections.bambooUrl } returns "https://bamboo.example.com"

        // Phase 5 T13: HandoverStateService now subscribes to WorkflowContextService.activeTicketFlow
        // for ticket changes. Stub the canonical service with the seeded ticket.
        val seededTicket = TicketRef("PROJ-123", "Add login feature")
        activeTicketFlow = MutableStateFlow(seededTicket)
        workflowService = mockk(relaxed = true)
        // Phase 7 T-Handover-a: seed a WorkflowContext that has focusBuild and focusQualityScope
        // set so existing event tests remain in-scope after the isInScope() guard was added.
        // focusBuild.planKey matches "PROJ-BUILD"; focusQualityScope.sonarProjectKey matches "proj-key".
        val seedContext = WorkflowContext(
            activeTicket = seededTicket,
            focusBuild = BuildRef(
                planKey = "PROJ-BUILD",
                buildNumber = 41,
                branch = "feature/PROJ-123",
                selectedJobKey = null,
            ),
            focusQualityScope = QualityScope(
                sonarProjectKey = "proj-key",
                branchName = "feature/PROJ-123",
                moduleKey = null,
            ),
        )
        every { workflowService.state } returns MutableStateFlow(seedContext)
        every { workflowService.activeTicketFlow } returns activeTicketFlow

        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        service = HandoverStateService(workflowService, eventBus, settings, scope)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `initial state has ticket info from settings`() = runTest {
        service.stateFlow.test {
            val state = awaitItem()
            assertEquals("PROJ-123", state.ticketId)
            assertEquals("Add login feature", state.ticketSummary)
            assertFalse(state.prCreated)
            assertTrue(state.suiteResults.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BuildFinished event updates buildStatus`() = runTest {
        service.stateFlow.test {
            skipItems(1) // initial

            eventBus.emit(WorkflowEvent.BuildFinished("PROJ-BUILD", 42, WorkflowEvent.BuildEventStatus.SUCCESS))

            val state = awaitItem()
            assertNotNull(state.buildStatus)
            assertEquals(42, state.buildStatus!!.buildNumber)
            assertEquals(WorkflowEvent.BuildEventStatus.SUCCESS, state.buildStatus!!.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `QualityGateResult event updates qualityGatePassed`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.QualityGateResult("proj-key", true))

            val state = awaitItem()
            assertTrue(state.qualityGatePassed!!)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `AutomationTriggered adds suite with null passed`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            // Yield to ensure the service's event collector coroutine is fully subscribed
            yield()

            eventBus.emit(WorkflowEvent.AutomationTriggered(
                suitePlanKey = "PROJ-REGR",
                buildResultKey = "PROJ-REGR-42",
                dockerTagsJson = """{"my-service":"1.2.3"}""",
                triggeredBy = "user"
            ))

            val state = awaitItem()
            assertEquals(1, state.suiteResults.size)
            assertEquals("PROJ-REGR", state.suiteResults[0].suitePlanKey)
            assertNull(state.suiteResults[0].passed)
            assertEquals("https://bamboo.example.com/browse/PROJ-REGR-42", state.suiteResults[0].bambooLink)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `AutomationFinished updates matching suite`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.AutomationTriggered(
                suitePlanKey = "PROJ-REGR",
                buildResultKey = "PROJ-REGR-42",
                dockerTagsJson = """{"my-service":"1.2.3"}""",
                triggeredBy = "user"
            ))
            awaitItem() // triggered

            eventBus.emit(WorkflowEvent.AutomationFinished(
                suitePlanKey = "PROJ-REGR",
                buildResultKey = "PROJ-REGR-42",
                passed = true,
                durationMs = 120_000
            ))

            val state = awaitItem()
            assertEquals(1, state.suiteResults.size)
            assertTrue(state.suiteResults[0].passed!!)
            assertEquals(120_000L, state.suiteResults[0].durationMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // automation:F-7 — out-of-order event handling (Finished before Triggered).
    // When AutomationFinished arrives without a prior AutomationTriggered (e.g. IDE
    // restarted while a Bamboo build was in progress), the service must upsert a
    // synthetic SuiteResult rather than silently dropping the event.

    @Test
    fun `AutomationFinished without prior Triggered creates synthetic suite row (F-7)`() = runTest {
        service.stateFlow.test {
            skipItems(1) // initial state

            // No Triggered event fired — simulate IDE restart / lost in-memory state.
            eventBus.emit(WorkflowEvent.AutomationFinished(
                suitePlanKey = "PROJ-REGR",
                buildResultKey = "PROJ-REGR-42",
                passed = true,
                durationMs = 90_000
            ))

            val state = awaitItem()
            assertEquals(1, state.suiteResults.size,
                "A synthetic SuiteResult must be created when Finished arrives without Triggered")
            val suite = state.suiteResults[0]
            assertEquals("PROJ-REGR", suite.suitePlanKey)
            assertEquals("PROJ-REGR-42", suite.buildResultKey)
            assertTrue(suite.passed!!, "passed must be set from the Finished event")
            assertEquals(90_000L, suite.durationMs)
            // dockerTagsJson is empty for out-of-order rows (no Triggered payload available)
            assertEquals("", suite.dockerTagsJson)
            // bambooLink must be assembled from the configured bambooUrl
            assertEquals("https://bamboo.example.com/browse/PROJ-REGR-42", suite.bambooLink)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `AutomationFinished out-of-order then Triggered replaces with canonical row (F-7)`() = runTest {
        service.stateFlow.test {
            skipItems(1) // initial state

            // 1. Out-of-order Finished arrives first
            eventBus.emit(WorkflowEvent.AutomationFinished(
                suitePlanKey = "PROJ-REGR",
                buildResultKey = "PROJ-REGR-99",
                passed = false,
                durationMs = 5_000
            ))
            awaitItem() // synthetic row inserted

            // 2. Triggered arrives late (e.g. event replay / ordering correction)
            //    The latest-run-wins deduplication in AutomationTriggered replaces the row.
            eventBus.emit(WorkflowEvent.AutomationTriggered(
                suitePlanKey = "PROJ-REGR",
                buildResultKey = "PROJ-REGR-99",
                dockerTagsJson = """{"svc":"1.0.0"}""",
                triggeredBy = "user"
            ))

            val state = awaitItem()
            // Still exactly one row — Triggered de-duplicates on suitePlanKey
            assertEquals(1, state.suiteResults.size)
            assertEquals("PROJ-REGR-99", state.suiteResults[0].buildResultKey)
            // Triggered resets passed to null (in-progress state)
            assertNull(state.suiteResults[0].passed)
            // dockerTagsJson is now populated from the Triggered event
            assertEquals("""{"svc":"1.0.0"}""", state.suiteResults[0].dockerTagsJson)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `PullRequestCreated updates PR state`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.PullRequestCreated(
                prUrl = "https://bitbucket.example.com/pr/42",
                prNumber = 42,
                ticketId = "PROJ-123"
            ))

            val state = awaitItem()
            assertTrue(state.prCreated)
            assertEquals("https://bitbucket.example.com/pr/42", state.prUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `JiraCommentPosted updates jiraCommentPosted`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.JiraCommentPosted(
                ticketId = "PROJ-123",
                commentId = "10042"
            ))

            val state = awaitItem()
            assertTrue(state.jiraCommentPosted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple suites accumulate correctly`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.AutomationTriggered("PROJ-REGR", "PROJ-REGR-42", "{}", "user"))
            awaitItem()
            eventBus.emit(WorkflowEvent.AutomationTriggered("PROJ-SMOKE", "PROJ-SMOKE-18", "{}", "user"))

            val state = awaitItem()
            assertEquals(2, state.suiteResults.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `latest run replaces older run for same suite`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            eventBus.emit(WorkflowEvent.AutomationTriggered("PROJ-REGR", "PROJ-REGR-42", "{}", "user"))
            awaitItem()
            eventBus.emit(WorkflowEvent.AutomationTriggered("PROJ-REGR", "PROJ-REGR-43", "{}", "user"))

            val state = awaitItem()
            assertEquals(1, state.suiteResults.size)
            assertEquals("PROJ-REGR-43", state.suiteResults[0].buildResultKey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ticket change from canonical service resets state for new ticket`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            // First accumulate some state
            service.markCopyrightFixed()
            awaitItem()

            // Phase 5 T13: ticket changes flow from WorkflowContextService.activeTicketFlow.
            activeTicketFlow.value = TicketRef("PROJ-456", "New feature")

            val state = awaitItem()
            assertEquals("PROJ-456", state.ticketId)
            assertEquals("New feature", state.ticketSummary)
            assertFalse(state.copyrightFixed) // reset
            assertFalse(state.prCreated) // reset
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markCopyrightFixed updates state`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            service.markCopyrightFixed()

            val state = awaitItem()
            assertTrue(state.copyrightFixed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markJiraTransitioned updates state`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            service.markJiraTransitioned()

            val state = awaitItem()
            assertTrue(state.jiraTransitioned)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markWorkLogged updates state`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            service.markWorkLogged()

            val state = awaitItem()
            assertTrue(state.todayWorkLogged)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
