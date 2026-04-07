package com.workflow.orchestrator.handover.service

import app.cash.turbine.test
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.handover.model.BuildSummary
import com.workflow.orchestrator.handover.model.HandoverState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HandoverStateServiceTest {

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
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        service = HandoverStateService(eventBus, settings, scope)
    }

    @AfterEach
    fun tearDown() {
        service.dispose()
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
    fun `TicketChanged event resets state for new ticket`() = runTest {
        service.stateFlow.test {
            skipItems(1)

            // First accumulate some state
            service.markCopyrightFixed()
            awaitItem()

            // Emit ticket change
            eventBus.emit(WorkflowEvent.TicketChanged("PROJ-456", "New feature"))

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
