package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.handover.model.HandoverPlaceholderValue
import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.service.HandoverAiSummaryCache.TextGenerator
import io.mockk.coEvery
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HandoverPlaceholderResolverTest {

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    private lateinit var scope: CoroutineScope
    private lateinit var workflowService: WorkflowContextService
    private lateinit var eventBus: EventBus
    private lateinit var settings: PluginSettings
    private lateinit var stateService: HandoverStateService
    private lateinit var aiCache: HandoverAiSummaryCache
    private lateinit var resolver: HandoverPlaceholderResolver

    private val contextFlow = MutableStateFlow(WorkflowContext())

    // Default AI generator — returns unavailable so the resolver test suite stays
    // focused on non-AI placeholders. AI-specific behaviour is tested in HandoverAiSummaryCacheTest.
    private val unavailableGenerator: TextGenerator = TextGenerator { null }

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        eventBus = EventBus()

        settings = mockk(relaxed = true)
        every { settings.state } returns PluginSettings.State()
        every { settings.connections.bambooUrl } returns "https://bamboo.example.com"

        workflowService = mockk(relaxed = true)
        every { workflowService.state } returns contextFlow
        every { workflowService.activeTicketFlow } returns MutableStateFlow(null)

        stateService = HandoverStateService(workflowService, eventBus, settings, scope)
        aiCache = HandoverAiSummaryCache(
            generator = unavailableGenerator,
            workflowContext = workflowService,
            notifications = null,
            eventBus = eventBus,
            scope = scope,
        )
        resolver = HandoverPlaceholderResolver(stateService, workflowService, aiCache)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private fun setContext(ctx: WorkflowContext) {
        contextFlow.value = ctx
    }

    // -------------------------------------------------------------------------
    // Unknown-key guard
    // -------------------------------------------------------------------------

    @Test
    fun `unknown placeholder is unavailable, not crashing`() = runTest {
        val v = resolver.resolve("nonsense.key", HandoverTemplateAction.JIRA)
        assertFalse(v.isAvailable)
        assertEquals("unknown placeholder", v.unavailableReason)
    }

    @Test
    fun `deeply dotted unknown key is also unavailable`() = runTest {
        val v = resolver.resolve("foo.bar.baz", HandoverTemplateAction.EMAIL)
        assertFalse(v.isAvailable)
    }

    // -------------------------------------------------------------------------
    // ticket.* placeholders
    // -------------------------------------------------------------------------

    @Test
    fun `ticket id resolves when active ticket set`() = runTest {
        setContext(WorkflowContext(activeTicket = TicketRef("PROJ-42", "My feature")))
        val v = resolver.resolve("ticket.id", HandoverTemplateAction.JIRA)
        assertTrue(v.isAvailable)
        assertEquals("PROJ-42", v.value)
    }

    @Test
    fun `ticket id is unavailable when no active ticket`() = runTest {
        setContext(WorkflowContext(activeTicket = null))
        val v = resolver.resolve("ticket.id", HandoverTemplateAction.JIRA)
        assertFalse(v.isAvailable)
    }

    @Test
    fun `ticket summary resolves when active ticket set`() = runTest {
        setContext(WorkflowContext(activeTicket = TicketRef("PROJ-42", "My feature")))
        val v = resolver.resolve("ticket.summary", HandoverTemplateAction.EMAIL)
        assertTrue(v.isAvailable)
        assertEquals("My feature", v.value)
    }

    @Test
    fun `ticket summary is unavailable when no active ticket`() = runTest {
        setContext(WorkflowContext(activeTicket = null))
        val v = resolver.resolve("ticket.summary", HandoverTemplateAction.JIRA)
        assertFalse(v.isAvailable)
    }

    @Test
    fun `ticket status resolves after markJiraTransitioned`() = runTest {
        stateService.markJiraTransitioned("In Review")
        val v = resolver.resolve("ticket.status", HandoverTemplateAction.JIRA)
        assertTrue(v.isAvailable)
        assertEquals("In Review", v.value)
    }

    @Test
    fun `ticket status is unavailable when not yet transitioned`() = runTest {
        // Fresh state — currentStatusName is null
        val v = resolver.resolve("ticket.status", HandoverTemplateAction.JIRA)
        assertFalse(v.isAvailable)
    }

    // -------------------------------------------------------------------------
    // pr.* placeholders
    // -------------------------------------------------------------------------

    @Test
    fun `pr id resolves from focusPr`() = runTest {
        val pr = PrRef(
            prId = 99,
            fromBranch = "feature/x",
            toBranch = "main",
            repoName = "my-repo",
            bambooPlanKey = null,
            sonarProjectKey = null,
        )
        setContext(WorkflowContext(focusPr = pr))
        val v = resolver.resolve("pr.id", HandoverTemplateAction.JIRA)
        assertTrue(v.isAvailable)
        assertEquals("99", v.value)
    }

    @Test
    fun `pr id is unavailable when no focusPr`() = runTest {
        setContext(WorkflowContext(focusPr = null))
        val v = resolver.resolve("pr.id", HandoverTemplateAction.JIRA)
        assertFalse(v.isAvailable)
    }

    @Test
    fun `pr url resolves from HandoverState prUrl`() = runTest {
        // Seed prUrl via PullRequestCreated event
        eventBus.emit(
            WorkflowEvent.PullRequestCreated(
                prUrl = "https://bitbucket.example.com/projects/P/repos/R/pull-requests/1",
                prNumber = 1,
                ticketId = "PROJ-42",
            )
        )
        // Allow Unconfined dispatcher to process the event
        yield()

        val v = resolver.resolve("pr.url", HandoverTemplateAction.EMAIL)
        assertTrue(v.isAvailable)
        assertTrue(v.value.contains("pull-requests/1"))
    }

    @Test
    fun `pr url is unavailable when no prUrl in state`() = runTest {
        val v = resolver.resolve("pr.url", HandoverTemplateAction.JIRA)
        assertFalse(v.isAvailable)
    }

    // -------------------------------------------------------------------------
    // build.url placeholder
    // -------------------------------------------------------------------------

    @Test
    fun `build url is unavailable because BuildSummary has no url field`() = runTest {
        val v = resolver.resolve("build.url", HandoverTemplateAction.JIRA)
        assertFalse(v.isAvailable)
        assertEquals("build URL not in state model", v.unavailableReason)
    }

    // -------------------------------------------------------------------------
    // docker.* placeholders
    // -------------------------------------------------------------------------

    @Test
    fun `docker tagsJson is unavailable when no suite results`() = runTest {
        val v = resolver.resolve("docker.tagsJson", HandoverTemplateAction.JIRA)
        assertFalse(v.isAvailable)
    }

    @Test
    fun `docker tagsJson returns raw json from last suite`() = runTest {
        val raw = """{"api":"1.2.3","web":"4.5.6"}"""
        eventBus.emit(
            WorkflowEvent.AutomationTriggered(
                suitePlanKey = "ORCH-SMOKE",
                buildResultKey = "ORCH-SMOKE-1",
                dockerTagsJson = raw,
                triggeredBy = "test",
            )
        )
        yield()

        val v = resolver.resolve("docker.tagsJson", HandoverTemplateAction.JIRA)
        assertTrue(v.isAvailable)
        assertEquals(raw, v.value)
    }

    @Test
    fun `docker tag returns first key-value pair`() = runTest {
        eventBus.emit(
            WorkflowEvent.AutomationTriggered(
                suitePlanKey = "ORCH-SMOKE",
                buildResultKey = "ORCH-SMOKE-1",
                dockerTagsJson = """{"api":"1.2.3","web":"4.5.6"}""",
                triggeredBy = "test",
            )
        )
        yield()

        val v = resolver.resolve("docker.tag", HandoverTemplateAction.JIRA)
        assertTrue(v.isAvailable)
        // First entry from the JSON object — key:value format
        assertTrue(v.value.matches(Regex("\\w+:\\S+")), "Expected key:value format, got: ${v.value}")
    }

    @Test
    fun `docker tag is unavailable when no suite results`() = runTest {
        val v = resolver.resolve("docker.tag", HandoverTemplateAction.JIRA)
        assertFalse(v.isAvailable)
    }

    // -------------------------------------------------------------------------
    // automation.suiteTable — empty state
    // -------------------------------------------------------------------------

    @Test
    fun `automation suiteTable is unavailable when no suite results`() = runTest {
        val v = resolver.resolve("automation.suiteTable", HandoverTemplateAction.JIRA)
        assertFalse(v.isAvailable)
        assertEquals("no automation suites", v.unavailableReason)
    }

    // -------------------------------------------------------------------------
    // automation.suiteTable — Jira format
    // -------------------------------------------------------------------------

    @Test
    fun `automation suiteTable JIRA format contains header row`() = runTest {
        eventBus.emit(
            WorkflowEvent.AutomationTriggered(
                suitePlanKey = "ORCH-SMOKE",
                buildResultKey = "ORCH-SMOKE-1",
                dockerTagsJson = "{}",
                triggeredBy = "test",
            )
        )
        eventBus.emit(
            WorkflowEvent.AutomationFinished(
                suitePlanKey = "ORCH-SMOKE",
                buildResultKey = "ORCH-SMOKE-1",
                passed = true,
                durationMs = 500L,
            )
        )
        yield()

        val v = resolver.resolve("automation.suiteTable", HandoverTemplateAction.JIRA)
        assertTrue(v.isAvailable)
        assertTrue(v.value.contains("|| Suite || Result ||"), "Expected Jira header row")
        assertTrue(v.value.contains("ORCH-SMOKE"), "Expected plan key in table")
        assertTrue(v.value.contains("{color:green}PASS{color}"), "Expected green PASS for passed suite")
    }

    @Test
    fun `automation suiteTable JIRA format shows red FAIL for failed suite`() = runTest {
        eventBus.emit(
            WorkflowEvent.AutomationTriggered(
                suitePlanKey = "ORCH-E2E",
                buildResultKey = "ORCH-E2E-1",
                dockerTagsJson = "{}",
                triggeredBy = "test",
            )
        )
        eventBus.emit(
            WorkflowEvent.AutomationFinished(
                suitePlanKey = "ORCH-E2E",
                buildResultKey = "ORCH-E2E-1",
                passed = false,
                durationMs = 200L,
            )
        )
        yield()

        val v = resolver.resolve("automation.suiteTable", HandoverTemplateAction.JIRA)
        assertTrue(v.isAvailable)
        assertTrue(v.value.contains("{color:red}FAIL{color}"), "Expected red FAIL for failed suite")
    }

    @Test
    fun `automation suiteTable JIRA format shows orange running for in-progress suite`() = runTest {
        eventBus.emit(
            WorkflowEvent.AutomationTriggered(
                suitePlanKey = "ORCH-PERF",
                buildResultKey = "ORCH-PERF-1",
                dockerTagsJson = "{}",
                triggeredBy = "test",
            )
        )
        // No AutomationFinished — suite is still running (passed == null)
        yield()

        val v = resolver.resolve("automation.suiteTable", HandoverTemplateAction.JIRA)
        assertTrue(v.isAvailable)
        assertTrue(v.value.contains("{color:orange}running{color}"), "Expected orange running for in-progress suite")
    }

    // -------------------------------------------------------------------------
    // automation.suiteTable — Email format
    // -------------------------------------------------------------------------

    @Test
    fun `automation suiteTable EMAIL format uses HTML table rows`() = runTest {
        eventBus.emit(
            WorkflowEvent.AutomationTriggered(
                suitePlanKey = "ORCH-SMOKE",
                buildResultKey = "ORCH-SMOKE-1",
                dockerTagsJson = "{}",
                triggeredBy = "test",
            )
        )
        eventBus.emit(
            WorkflowEvent.AutomationFinished(
                suitePlanKey = "ORCH-SMOKE",
                buildResultKey = "ORCH-SMOKE-1",
                passed = true,
                durationMs = 500L,
            )
        )
        yield()

        val v = resolver.resolve("automation.suiteTable", HandoverTemplateAction.EMAIL)
        assertTrue(v.isAvailable)
        assertTrue(v.value.contains("<tr><th>Suite</th><th>Result</th></tr>"), "Expected HTML header row")
        assertTrue(v.value.contains("<td>ORCH-SMOKE</td>"), "Expected plan key in HTML table")
        assertTrue(v.value.contains("color:#2e7d32"), "Expected green hex for PASS in email")
        assertTrue(v.value.contains("PASS"), "Expected PASS label")
    }

    @Test
    fun `automation suiteTable EMAIL format shows red for FAIL`() = runTest {
        eventBus.emit(
            WorkflowEvent.AutomationTriggered(
                suitePlanKey = "ORCH-E2E",
                buildResultKey = "ORCH-E2E-1",
                dockerTagsJson = "{}",
                triggeredBy = "test",
            )
        )
        eventBus.emit(
            WorkflowEvent.AutomationFinished(
                suitePlanKey = "ORCH-E2E",
                buildResultKey = "ORCH-E2E-1",
                passed = false,
                durationMs = 200L,
            )
        )
        yield()

        val v = resolver.resolve("automation.suiteTable", HandoverTemplateAction.EMAIL)
        assertTrue(v.isAvailable)
        assertTrue(v.value.contains("color:#c62828"), "Expected red hex for FAIL in email")
        assertTrue(v.value.contains("FAIL"), "Expected FAIL label")
    }

    @Test
    fun `automation suiteTable EMAIL format shows amber for running`() = runTest {
        eventBus.emit(
            WorkflowEvent.AutomationTriggered(
                suitePlanKey = "ORCH-PERF",
                buildResultKey = "ORCH-PERF-1",
                dockerTagsJson = "{}",
                triggeredBy = "test",
            )
        )
        yield()

        val v = resolver.resolve("automation.suiteTable", HandoverTemplateAction.EMAIL)
        assertTrue(v.isAvailable)
        assertTrue(v.value.contains("color:#b07c12"), "Expected amber hex for running in email")
        assertTrue(v.value.contains("running"), "Expected running label")
    }

    // -------------------------------------------------------------------------
    // AI placeholders — delegate to HandoverAiSummaryCache (T8)
    // -------------------------------------------------------------------------

    @Test
    fun `ai changeSummary is unavailable when no active ticket`() = runTest {
        // contextFlow has no active ticket — cache returns unavailable without calling LLM
        val v = resolver.resolve("ai.changeSummary", HandoverTemplateAction.JIRA)
        assertFalse(v.isAvailable)
        assertEquals("no active ticket", v.unavailableReason)
    }

    @Test
    fun `ai ticketSummary is unavailable when no active ticket`() = runTest {
        val v = resolver.resolve("ai.ticketSummary", HandoverTemplateAction.EMAIL)
        assertFalse(v.isAvailable)
        assertEquals("no active ticket", v.unavailableReason)
    }

    @Test
    fun `ai changeSummary returns LLM result when ticket and generator are present`() = runTest {
        setContext(WorkflowContext(activeTicket = TicketRef("PROJ-42", "My feature")))

        val liveGenerator = TextGenerator { "AI-generated change summary" }
        val liveCache = HandoverAiSummaryCache(
            generator = liveGenerator,
            workflowContext = workflowService,
            notifications = null,
            eventBus = eventBus,
            scope = scope,
        )
        val liveResolver = HandoverPlaceholderResolver(stateService, workflowService, liveCache)

        val v = liveResolver.resolve("ai.changeSummary", HandoverTemplateAction.JIRA)
        assertTrue(v.isAvailable)
        assertEquals("AI-generated change summary", v.value)
    }

    @Test
    fun `ai ticketSummary returns LLM result when ticket and generator are present`() = runTest {
        setContext(WorkflowContext(activeTicket = TicketRef("PROJ-42", "My feature")))

        val liveGenerator = TextGenerator { "AI-generated ticket summary" }
        val liveCache = HandoverAiSummaryCache(
            generator = liveGenerator,
            workflowContext = workflowService,
            notifications = null,
            eventBus = eventBus,
            scope = scope,
        )
        val liveResolver = HandoverPlaceholderResolver(stateService, workflowService, liveCache)

        val v = liveResolver.resolve("ai.ticketSummary", HandoverTemplateAction.EMAIL)
        assertTrue(v.isAvailable)
        assertEquals("AI-generated ticket summary", v.value)
    }
}
