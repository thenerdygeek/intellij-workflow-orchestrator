package com.workflow.orchestrator.jira.service

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.MissingFieldsError
import com.workflow.orchestrator.core.model.jira.SelectSource
import com.workflow.orchestrator.core.model.jira.StatusCategory
import com.workflow.orchestrator.core.model.jira.StatusRef
import com.workflow.orchestrator.core.model.jira.TransitionError
import com.workflow.orchestrator.core.model.jira.TransitionField
import com.workflow.orchestrator.core.model.jira.TransitionInput
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import com.workflow.orchestrator.core.model.jira.TransitionOutcome
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.services.jira.TicketTransitionService
import com.workflow.orchestrator.jira.api.JiraApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Covers [JiraServiceImpl.startWork] and [JiraServiceImpl.transition] after the
 * 2026-05-08 write-ops audit (PR 4) rewrote both to delegate to
 * [TicketTransitionService.executeTransition] instead of POSTing to the
 * `/transitions` endpoint directly.
 *
 * Why this matters: when a Jira admin marks any field required on a transition
 * (e.g. "Story Points" required for "In Progress"), the previous direct-POST path
 * silently 400'd. Routing through `TicketTransitionService` runs the
 * `expand=transitions.fields` preflight and surfaces a structured `MissingFields`
 * error the caller can act on.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraServiceImplStartWorkDelegationTest {

    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)

    private lateinit var server: MockWebServer
    private lateinit var apiClient: JiraApiClient
    private lateinit var service: JiraServiceImpl
    private lateinit var transitionSvc: TicketTransitionService

    private val toDoStatus = StatusRef("1", "To Do", StatusCategory.TO_DO)
    private val inProgressStatus = StatusRef("3", "In Progress", StatusCategory.IN_PROGRESS)

    private val noFieldInProgress = TransitionMeta(
        id = "11",
        name = "Start Progress",
        toStatus = inProgressStatus,
        hasScreen = false,
        fields = emptyList()
    )

    private val requiredFieldInProgress = TransitionMeta(
        id = "11",
        name = "Start Progress",
        toStatus = inProgressStatus,
        hasScreen = true,
        fields = listOf(
            TransitionField(
                id = "customfield_10010",
                name = "Story Points",
                required = true,
                schema = FieldSchema.Number,
                allowedValues = emptyList(),
                autoCompleteUrl = null,
                defaultValue = null
            )
        )
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiClient = JiraApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
        transitionSvc = mockk()
        service = JiraServiceImpl(project).also {
            it.testClient = apiClient
            it.testTicketTransitionService = transitionSvc
        }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── startWork: delegates to TicketTransitionService ──────────────────────

    @Test
    fun `startWork transitions ticket via TicketTransitionService when In Progress has no required fields`() = runTest {
        coEvery { transitionSvc.getAvailableTransitions("PROJ-1") } returns
            ToolResult.success(listOf(noFieldInProgress), summary = "ok")
        coEvery { transitionSvc.executeTransition("PROJ-1", any()) } returns
            ToolResult.success(
                TransitionOutcome(
                    key = "PROJ-1",
                    fromStatus = toDoStatus,
                    toStatus = inProgressStatus,
                    transitionId = "11",
                    appliedFields = emptyMap()
                ),
                summary = "Transitioned PROJ-1"
            )

        val result = service.startWork("PROJ-1", "feature/PROJ-1", "main")

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        assertTrue(result.data!!.transitioned)
        assertEquals("feature/PROJ-1", result.data!!.branchName)
        assertTrue(
            result.summary.contains("transitioned to In Progress", ignoreCase = true),
            "Summary should mention the successful transition: ${result.summary}"
        )
        coVerify(exactly = 1) {
            transitionSvc.executeTransition(
                "PROJ-1",
                match<TransitionInput> { it.transitionId == "11" && it.fieldValues.isEmpty() }
            )
        }
    }

    @Test
    fun `startWork surfaces MissingFields error when In Progress requires a field`() = runTest {
        coEvery { transitionSvc.getAvailableTransitions("PROJ-1") } returns
            ToolResult.success(listOf(requiredFieldInProgress), summary = "ok")
        // executeTransition runs the preflight and returns a MissingFields-typed error
        // before posting. JiraServiceImpl must surface this — pre-fix it would have
        // posted with fields={} and got a silent 400.
        val missingFieldsErr = TransitionError.MissingFields(
            MissingFieldsError(
                transitionId = "11",
                transitionName = "Start Progress",
                fields = requiredFieldInProgress.fields,
                guidance = "Use the dialog."
            )
        )
        coEvery { transitionSvc.executeTransition("PROJ-1", any()) } returns
            ToolResult(
                data = TransitionOutcome(
                    key = "PROJ-1",
                    fromStatus = StatusRef("?", "?", StatusCategory.UNKNOWN),
                    toStatus = StatusRef("?", "?", StatusCategory.UNKNOWN),
                    transitionId = "",
                    appliedFields = emptyMap()
                ),
                summary = "missing_required_fields: transition 'Start Progress' requires: Story Points",
                isError = true,
                hint = "Use the dialog.",
                payload = missingFieldsErr
            )

        val result = service.startWork("PROJ-1", "feature/PROJ-1", "main")

        assertTrue(result.isError, "Expected error result, got success: ${result.summary}")
        assertFalse(result.data!!.transitioned, "transitioned flag must be false on a missing-fields block")
        assertEquals("feature/PROJ-1", result.data!!.branchName)
        assertTrue(
            result.summary.contains("Story Points"),
            "Summary should name the missing field: ${result.summary}"
        )
        assertTrue(
            result.summary.contains("Sprint-tab", ignoreCase = true) ||
                (result.hint ?: "").contains("Sprint-tab", ignoreCase = true),
            "Caller should be told to use the Sprint-tab dialog. summary=${result.summary} hint=${result.hint}"
        )
    }

    @Test
    fun `startWork surfaces server error from executeTransition without dropping it`() = runTest {
        coEvery { transitionSvc.getAvailableTransitions("PROJ-1") } returns
            ToolResult.success(listOf(noFieldInProgress), summary = "ok")
        coEvery { transitionSvc.executeTransition("PROJ-1", any()) } returns
            ToolResult(
                data = TransitionOutcome(
                    key = "PROJ-1",
                    fromStatus = StatusRef("?", "?", StatusCategory.UNKNOWN),
                    toStatus = StatusRef("?", "?", StatusCategory.UNKNOWN),
                    transitionId = "",
                    appliedFields = emptyMap()
                ),
                summary = "Transition 'Start Progress' failed for PROJ-1: Insufficient Jira permissions",
                isError = true,
                payload = TransitionError.InvalidTransition("Insufficient Jira permissions")
            )

        val result = service.startWork("PROJ-1", "feature/PROJ-1", "main")

        // For non-MissingFields errors the previous behaviour was preserved: branch
        // step is still reported as "started" (it's metadata — branch creation lives
        // elsewhere) but the transition message must include the underlying error.
        assertFalse(result.isError, "Generic transition errors should not block start_work")
        assertFalse(result.data!!.transitioned)
        assertTrue(
            result.summary.contains("Insufficient Jira permissions"),
            "Server error must be surfaced, not swallowed: ${result.summary}"
        )
    }

    @Test
    fun `startWork falls through with status-unchanged when no In Progress transition is available`() = runTest {
        // E.g. the ticket is already In Progress, or workflow has no In Progress state.
        coEvery { transitionSvc.getAvailableTransitions("PROJ-1") } returns
            ToolResult.success(emptyList(), summary = "ok")

        val result = service.startWork("PROJ-1", "feature/PROJ-1", "main")

        assertFalse(result.isError)
        assertFalse(result.data!!.transitioned)
        assertTrue(
            result.summary.contains("no In Progress transition", ignoreCase = true),
            "Expected 'no In Progress transition' message, got: ${result.summary}"
        )
        coVerify(exactly = 0) { transitionSvc.executeTransition(any(), any()) }
    }

    // The `client == null` path is identical pre- and post-fix (early-returns before
    // any transition is attempted) and would require mocking `PluginSettings` against
    // a real IntelliJ application instance to exercise. Skipped here — covered by
    // existing JiraServiceImplTest fixtures.

    // ── transition: delegates to TicketTransitionService ─────────────────────

    @Test
    fun `transition delegates to TicketTransitionService and surfaces MissingFields hint`() = runTest {
        val missingFieldsErr = TransitionError.MissingFields(
            MissingFieldsError(
                transitionId = "21",
                transitionName = "Close",
                fields = listOf(
                    TransitionField(
                        id = "resolution",
                        name = "Resolution",
                        required = true,
                        schema = FieldSchema.SingleSelect(SelectSource.AllowedValues),
                        allowedValues = emptyList(),
                        autoCompleteUrl = null,
                        defaultValue = null
                    )
                ),
                guidance = "Use ask_followup_question."
            )
        )
        coEvery { transitionSvc.executeTransition("PROJ-1", any()) } returns
            ToolResult(
                data = TransitionOutcome(
                    key = "PROJ-1",
                    fromStatus = StatusRef("?", "?", StatusCategory.UNKNOWN),
                    toStatus = StatusRef("?", "?", StatusCategory.UNKNOWN),
                    transitionId = "",
                    appliedFields = emptyMap()
                ),
                summary = "missing_required_fields: transition 'Close' requires: Resolution",
                isError = true,
                hint = "Use ask_followup_question.",
                payload = missingFieldsErr
            )

        val result = service.transition(
            key = "PROJ-1",
            transitionId = "21",
            fields = null,
            comment = null
        )

        assertTrue(result.isError, "Expected error, got success: ${result.summary}")
        assertNotNull(result.payload, "MissingFields payload must propagate")
        assertTrue(result.payload is TransitionError.MissingFields)
        assertTrue(
            result.summary.contains("Resolution") || (result.hint ?: "").contains("Resolution"),
            "Resolution must be named somewhere. summary=${result.summary} hint=${result.hint}"
        )
        coVerify(exactly = 1) {
            transitionSvc.executeTransition(
                "PROJ-1",
                match<TransitionInput> { it.transitionId == "21" }
            )
        }
    }

    @Test
    fun `transition succeeds when executeTransition succeeds`() = runTest {
        coEvery { transitionSvc.executeTransition("PROJ-1", any()) } returns
            ToolResult.success(
                TransitionOutcome(
                    key = "PROJ-1",
                    fromStatus = toDoStatus,
                    toStatus = inProgressStatus,
                    transitionId = "11",
                    appliedFields = emptyMap()
                ),
                summary = "Transitioned PROJ-1"
            )

        val result = service.transition(
            key = "PROJ-1",
            transitionId = "11",
            fields = null,
            comment = "Starting work"
        )

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        assertTrue(result.summary.contains("Transitioned PROJ-1"))
        coVerify {
            transitionSvc.executeTransition(
                "PROJ-1",
                match<TransitionInput> {
                    it.transitionId == "11" && it.comment == "Starting work" && it.fieldValues.isEmpty()
                }
            )
        }
    }
}
