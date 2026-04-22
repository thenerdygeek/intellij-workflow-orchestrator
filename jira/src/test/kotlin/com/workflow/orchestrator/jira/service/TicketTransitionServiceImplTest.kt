package com.workflow.orchestrator.jira.service

import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.TicketTransitioned
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.StatusCategory
import com.workflow.orchestrator.core.model.jira.StatusRef
import com.workflow.orchestrator.core.model.jira.TransitionError
import com.workflow.orchestrator.core.model.jira.TransitionField
import com.workflow.orchestrator.core.model.jira.TransitionInput
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.api.dto.JiraIssueFields
import com.workflow.orchestrator.jira.api.dto.JiraStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TicketTransitionServiceImplTest {

    private lateinit var api: JiraApiClient
    private lateinit var eventBus: EventBus
    private lateinit var svc: TicketTransitionServiceImpl

    // Shared fixtures
    private val toDoStatus = StatusRef("1", "To Do", StatusCategory.TO_DO)
    private val inProgressStatus = StatusRef("3", "In Progress", StatusCategory.IN_PROGRESS)

    private val noFieldTransition = TransitionMeta(
        id = "11",
        name = "Start Progress",
        toStatus = inProgressStatus,
        hasScreen = false,
        fields = emptyList()
    )

    private val requiredFieldTransition = TransitionMeta(
        id = "21",
        name = "Close",
        toStatus = StatusRef("6", "Done", StatusCategory.DONE),
        hasScreen = false,
        fields = listOf(
            TransitionField(
                id = "resolution",
                name = "Resolution",
                required = true,
                schema = FieldSchema.SingleSelect(com.workflow.orchestrator.core.model.jira.SelectSource.AllowedValues),
                allowedValues = emptyList(),
                autoCompleteUrl = null,
                defaultValue = null
            )
        )
    )

    private val screenTransition = TransitionMeta(
        id = "31",
        name = "In Review",
        toStatus = StatusRef("5", "In Review", StatusCategory.IN_PROGRESS),
        hasScreen = true,
        fields = emptyList()
    )

    private val stubIssue = JiraIssue(
        id = "10001",
        key = "PROJ-1",
        fields = JiraIssueFields(
            summary = "Fix login",
            status = JiraStatus(id = "1", name = "To Do")
        )
    )

    @BeforeEach
    fun setUp() {
        api = mockk()
        eventBus = EventBus()
        // Stub getIssue for fetchCurrentStatus (used in executeTransition)
        coEvery { api.getIssue(any()) } returns ApiResult.Success(stubIssue)
    }

    private fun buildSvc(clockMs: Long = System.currentTimeMillis()): TicketTransitionServiceImpl =
        TicketTransitionServiceImpl(api, eventBus, clock = { clockMs })

    // ── Test 1: getAvailableTransitions caches within TTL ────────────────────

    @Test
    fun `getAvailableTransitions caches within TTL and calls API only once`() = runTest {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(noFieldTransition))
        svc = buildSvc()

        val r1 = svc.getAvailableTransitions("PROJ-1")
        val r2 = svc.getAvailableTransitions("PROJ-1")

        assertFalse(r1.isError)
        assertFalse(r2.isError)
        assertEquals(1, r1.data.size)
        assertEquals(1, r2.data.size)
        coVerify(exactly = 1) { api.getTransitions("PROJ-1") }
    }

    // ── Test 2: cache invalidated on TicketTransitioned event ────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getAvailableTransitions invalidates cache on TicketTransitioned event`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(noFieldTransition))

            // backgroundScope is cancelled automatically when the test finishes, which allows
            // the infinite SharedFlow collector to be cleaned up without blocking test completion.
            svc = TicketTransitionServiceImpl(api, eventBus, clock = { System.currentTimeMillis() },
                parentScope = backgroundScope)

            // First call — populates cache
            svc.getAvailableTransitions("PROJ-1")

            // Emit TicketTransitioned to invalidate cache.
            // With UnconfinedTestDispatcher, the subscriber coroutine is already running;
            // tryEmit is non-blocking so the event is enqueued in the SharedFlow buffer and
            // picked up by the subscriber before the next getAvailableTransitions call.
            eventBus.emit(
                TicketTransitioned(
                    key = "PROJ-1",
                    fromStatus = toDoStatus,
                    toStatus = inProgressStatus,
                    transitionId = "11"
                )
            )

            // Yield once so the subscriber coroutine can process the event before we assert.
            kotlinx.coroutines.yield()

            // Second call — cache was invalidated, should hit API again
            svc.getAvailableTransitions("PROJ-1")

            coVerify(exactly = 2) { api.getTransitions("PROJ-1") }
        }

    // ── Test 3: prepareTransition returns error when id not in list ──────────

    @Test
    fun `prepareTransition returns error if transition id not in list`() = runTest {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(noFieldTransition))
        svc = buildSvc()

        val result = svc.prepareTransition("PROJ-1", "99")

        assertTrue(result.isError)
        assertTrue(result.summary.contains("not available"), "Expected 'not available' in: ${result.summary}")
    }

    @Test
    fun `prepareTransition returns matching TransitionMeta when id exists`() = runTest {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(noFieldTransition))
        svc = buildSvc()

        val result = svc.prepareTransition("PROJ-1", "11")

        assertFalse(result.isError)
        assertEquals("11", result.data.id)
        assertEquals("Start Progress", result.data.name)
    }

    // ── Test 4: executeTransition returns MissingFields for absent required ──

    @Test
    fun `executeTransition returns MissingFields payload when required field absent`() = runTest {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(requiredFieldTransition))
        svc = buildSvc()

        val input = TransitionInput(
            transitionId = "21",
            fieldValues = emptyMap(), // resolution not supplied
            comment = null
        )
        val result = svc.executeTransition("PROJ-1", input)

        assertTrue(result.isError)
        assertTrue(result.summary.contains("missing_required_fields") || result.summary.contains("Resolution"),
            "Expected missing-field signal in: ${result.summary}")
        val payload = result.payload
        assertNotNull(payload, "Expected non-null payload for MissingFields error")
        assertTrue(payload is TransitionError.MissingFields,
            "Expected TransitionError.MissingFields but got: ${payload?.javaClass?.simpleName}")
        val mf = payload as TransitionError.MissingFields
        assertEquals("21", mf.payload.transitionId)
        assertEquals(1, mf.payload.fields.size)
        assertEquals("resolution", mf.payload.fields[0].id)
    }

    // ── Test 5: executeTransition POSTs and emits event on success ───────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `executeTransition POSTs and emits TicketTransitioned on success`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(noFieldTransition))
        coEvery { api.transitionIssue("PROJ-1", any()) } returns ApiResult.Success(Unit)
        svc = buildSvc()

        // Collect the next event emitted on the bus
        var emittedEvent: TicketTransitioned? = null
        val collectJob = launch {
            eventBus.events.collect { event ->
                if (event is TicketTransitioned) {
                    emittedEvent = event
                    return@collect
                }
            }
        }

        val input = TransitionInput("11", emptyMap(), null)
        val result = svc.executeTransition("PROJ-1", input)

        collectJob.cancel()

        assertFalse(result.isError, "Expected success but got: ${result.summary}")
        assertEquals("PROJ-1", result.data.key)
        assertEquals("11", result.data.transitionId)
        assertEquals("In Progress", result.data.toStatus.name)

        assertNotNull(emittedEvent, "Expected TicketTransitioned event to be emitted")
        assertEquals("PROJ-1", emittedEvent!!.key)
        assertEquals("11", emittedEvent!!.transitionId)
        coVerify(exactly = 1) { api.transitionIssue("PROJ-1", any()) }
    }

    // ── Test 6: tryAutoTransition fires when no screen no required fields ────

    @Test
    fun `tryAutoTransition fires when no screen and no required fields`() = runTest {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(noFieldTransition))
        coEvery { api.transitionIssue("PROJ-1", any()) } returns ApiResult.Success(Unit)
        svc = buildSvc()

        val result = svc.tryAutoTransition("PROJ-1", "11")

        assertFalse(result.isError, "Expected success but got: ${result.summary}")
        assertEquals("PROJ-1", result.data.key)
        coVerify(exactly = 1) { api.transitionIssue("PROJ-1", any()) }
    }

    // ── Test 7: tryAutoTransition returns RequiresInteraction when screen ────

    @Test
    fun `tryAutoTransition returns RequiresInteraction when hasScreen is true`() = runTest {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(screenTransition))
        svc = buildSvc()

        val result = svc.tryAutoTransition("PROJ-1", "31")

        assertTrue(result.isError)
        val payload = result.payload
        assertNotNull(payload)
        assertTrue(payload is TransitionError.RequiresInteraction,
            "Expected TransitionError.RequiresInteraction but got: ${payload?.javaClass?.simpleName}")
        val ri = payload as TransitionError.RequiresInteraction
        assertEquals("31", ri.meta.id)
        coVerify(exactly = 0) { api.transitionIssue(any(), any()) }
    }

    // ── Test 8: tryAutoTransition returns RequiresInteraction for required fields

    @Test
    fun `tryAutoTransition returns RequiresInteraction when required fields present`() = runTest {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(requiredFieldTransition))
        svc = buildSvc()

        val result = svc.tryAutoTransition("PROJ-1", "21")

        assertTrue(result.isError)
        assertTrue(result.payload is TransitionError.RequiresInteraction)
        coVerify(exactly = 0) { api.transitionIssue(any(), any()) }
    }

    // ── Test 9: getAvailableTransitions returns error when API fails ─────────

    @Test
    fun `getAvailableTransitions returns error when API returns error`() = runTest {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Error(
            ErrorType.NETWORK_ERROR, "Cannot reach Jira"
        )
        svc = buildSvc()

        val result = svc.getAvailableTransitions("PROJ-1")

        assertTrue(result.isError)
        assertTrue(result.summary.contains("Cannot reach Jira"), "Unexpected summary: ${result.summary}")
        assertTrue(result.data.isEmpty())
    }

    // ── Test 10: executeTransition with required field supplied succeeds ──────

    @Test
    fun `executeTransition succeeds when all required fields supplied`() = runTest {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(requiredFieldTransition))
        coEvery { api.transitionIssue("PROJ-1", any()) } returns ApiResult.Success(Unit)
        svc = buildSvc()

        val input = TransitionInput(
            transitionId = "21",
            fieldValues = mapOf("resolution" to FieldValue.Option("10000")),
            comment = "Closing."
        )
        val result = svc.executeTransition("PROJ-1", input)

        assertFalse(result.isError, "Expected success but got: ${result.summary}")
        assertEquals("Done", result.data.toStatus.name)
    }

    // ── Test 11: executeTransition propagates API error as InvalidTransition ──

    @Test
    fun `executeTransition returns InvalidTransition payload on API error`() = runTest {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(noFieldTransition))
        coEvery { api.transitionIssue("PROJ-1", any()) } returns ApiResult.Error(
            ErrorType.FORBIDDEN, "Insufficient permissions"
        )
        svc = buildSvc()

        val input = TransitionInput("11", emptyMap(), null)
        val result = svc.executeTransition("PROJ-1", input)

        assertTrue(result.isError)
        assertTrue(result.summary.contains("Insufficient permissions"), "Unexpected summary: ${result.summary}")
        val payload = result.payload
        assertTrue(payload is TransitionError.InvalidTransition,
            "Expected TransitionError.InvalidTransition but got: ${payload?.javaClass?.simpleName}")
    }

    // ── Test 12: cache TTL expiry triggers fresh API call ────────────────────

    @Test
    fun `getAvailableTransitions calls API again after TTL expires`() = runTest {
        var fakeTime = 1000L
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(noFieldTransition))
        svc = TicketTransitionServiceImpl(api, eventBus, clock = { fakeTime })

        svc.getAvailableTransitions("PROJ-1") // populate cache at t=1000

        fakeTime = 62_000L // advance past 60s TTL

        svc.getAvailableTransitions("PROJ-1") // should miss cache

        coVerify(exactly = 2) { api.getTransitions("PROJ-1") }
    }

    // ── Test 13: fetchCurrentStatus falls back to sentinel on error ──────────

    @Test
    fun `executeTransition uses sentinel fromStatus when getIssue fails`() = runTest {
        coEvery { api.getTransitions("PROJ-1") } returns ApiResult.Success(listOf(noFieldTransition))
        coEvery { api.getIssue("PROJ-1") } returns ApiResult.Error(ErrorType.NOT_FOUND, "Not found")
        coEvery { api.transitionIssue("PROJ-1", any()) } returns ApiResult.Success(Unit)
        svc = buildSvc()

        val result = svc.executeTransition("PROJ-1", TransitionInput("11", emptyMap(), null))

        // Should still succeed even though current status could not be fetched
        assertFalse(result.isError, "Expected success but got: ${result.summary}")
        assertEquals("?", result.data.fromStatus.id, "Expected sentinel fromStatus id '?'")
    }
}
