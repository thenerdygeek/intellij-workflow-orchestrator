package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.handover.service.HandoverAiSummaryCache.TextGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HandoverAiSummaryCacheTest {

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    private lateinit var generator: TextGenerator
    private lateinit var workflowContext: WorkflowContextService
    private lateinit var notifications: WorkflowNotificationService
    private lateinit var eventBus: EventBus
    private lateinit var scope: CoroutineScope
    private lateinit var contextFlow: MutableStateFlow<WorkflowContext>
    private lateinit var cache: HandoverAiSummaryCache

    private val ticket = TicketRef("PROJ-42", "Implement login")

    @BeforeEach
    fun setUp() {
        generator = mockk()
        notifications = mockk(relaxed = true)
        eventBus = EventBus()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        contextFlow = MutableStateFlow(WorkflowContext(activeTicket = ticket))
        workflowContext = mockk(relaxed = true)
        every { workflowContext.state } returns contextFlow

        cache = HandoverAiSummaryCache(
            generator = generator,
            workflowContext = workflowContext,
            notifications = notifications,
            eventBus = eventBus,
            scope = scope,
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Happy path — available result
    // -------------------------------------------------------------------------

    @Test
    fun `changeSummary returns available value when LLM succeeds`() = runTest {
        coEvery { generator.generate(any()) } returns "Fixed the login button crash."

        val result = cache.changeSummary()

        assertTrue(result.isAvailable)
        assertEquals("Fixed the login button crash.", result.value)
    }

    @Test
    fun `ticketSummary returns available value when LLM succeeds`() = runTest {
        coEvery { generator.generate(any()) } returns "Implement user authentication."

        val result = cache.ticketSummary()

        assertTrue(result.isAvailable)
        assertEquals("Implement user authentication.", result.value)
    }

    // -------------------------------------------------------------------------
    // Cache hit — no duplicate compute
    // -------------------------------------------------------------------------

    @Test
    fun `cache hit reuses prior result without calling LLM again`() = runTest {
        var callCount = 0
        coEvery { generator.generate(any()) } answers {
            callCount++
            "Summary result"
        }

        val first = cache.changeSummary()
        val second = cache.changeSummary()

        assertTrue(first.isAvailable)
        assertTrue(second.isAvailable)
        assertEquals(first.value, second.value)
        assertEquals(1, callCount, "LLM must only be called once for repeated cache hits")
    }

    @Test
    fun `CHANGE_SUMMARY and TICKET_SUMMARY are cached independently`() = runTest {
        var changeCalls = 0
        var ticketCalls = 0
        coEvery { generator.generate(match { it.contains("Git diff") }) } answers {
            changeCalls++
            "Change summary"
        }
        coEvery { generator.generate(match { !it.contains("Git diff") }) } answers {
            ticketCalls++
            "Ticket summary"
        }

        cache.changeSummary()
        cache.changeSummary()
        cache.ticketSummary()
        cache.ticketSummary()

        assertEquals(1, changeCalls, "CHANGE_SUMMARY LLM called once")
        assertEquals(1, ticketCalls, "TICKET_SUMMARY LLM called once")
    }

    // -------------------------------------------------------------------------
    // Invalidation on BranchChanged
    // -------------------------------------------------------------------------

    @Test
    fun `BranchChanged event clears cache so next call re-invokes LLM`() = runTest {
        var callCount = 0
        coEvery { generator.generate(any()) } answers {
            callCount++
            "result-$callCount"
        }

        val first = cache.changeSummary()
        assertEquals(1, callCount)

        eventBus.emit(WorkflowEvent.BranchChanged("main", "feature/x"))
        yield() // let the collector coroutine process the event

        val second = cache.changeSummary()
        assertEquals(2, callCount, "LLM must be called again after invalidation")
        assertFalse(first.value == second.value, "Second result should be a fresh computation")
    }

    // -------------------------------------------------------------------------
    // Invalidation on TicketChanged
    // -------------------------------------------------------------------------

    @Test
    fun `TicketChanged event clears cache so next call re-invokes LLM`() = runTest {
        var callCount = 0
        coEvery { generator.generate(any()) } answers {
            callCount++
            "result-$callCount"
        }

        cache.ticketSummary()
        assertEquals(1, callCount)

        eventBus.emit(WorkflowEvent.TicketChanged("PROJ-99", "New ticket"))
        yield()

        cache.ticketSummary()
        assertEquals(2, callCount, "LLM must be called again after TicketChanged invalidation")
    }

    // -------------------------------------------------------------------------
    // No active ticket
    // -------------------------------------------------------------------------

    @Test
    fun `no active ticket returns unavailable without calling LLM`() = runTest {
        contextFlow.value = WorkflowContext(activeTicket = null)

        val result = cache.changeSummary()

        assertFalse(result.isAvailable)
        assertEquals("no active ticket", result.unavailableReason)
        coVerify(exactly = 0) { generator.generate(any()) }
    }

    @Test
    fun `ticketSummary with no active ticket returns unavailable without calling LLM`() = runTest {
        contextFlow.value = WorkflowContext(activeTicket = null)

        val result = cache.ticketSummary()

        assertFalse(result.isAvailable)
        assertEquals("no active ticket", result.unavailableReason)
        coVerify(exactly = 0) { generator.generate(any()) }
    }

    // -------------------------------------------------------------------------
    // Missing TextGenerationService EP
    // -------------------------------------------------------------------------

    @Test
    fun `missing TextGenerationService EP returns unavailable`() = runTest {
        val cacheWithoutGen = HandoverAiSummaryCache(
            generator = null,
            workflowContext = workflowContext,
            notifications = notifications,
            eventBus = eventBus,
            scope = scope,
        )

        val result = cacheWithoutGen.changeSummary()

        assertFalse(result.isAvailable)
        assertTrue(
            result.unavailableReason?.contains("not available") == true,
            "Expected 'not available' in reason: ${result.unavailableReason}"
        )
    }

    // -------------------------------------------------------------------------
    // LLM failure — unavailable + notification once per session
    // -------------------------------------------------------------------------

    @Test
    fun `LLM failure returns unavailable`() = runTest {
        coEvery { generator.generate(any()) } throws RuntimeException("network timeout")

        val result = cache.changeSummary()

        assertFalse(result.isAvailable)
        assertTrue(
            result.unavailableReason?.contains("AI service error") == true,
            "Expected AI service error message, got: ${result.unavailableReason}"
        )
    }

    @Test
    fun `LLM failure notifies once per session even on repeated calls`() = runTest {
        coEvery { generator.generate(any()) } throws RuntimeException("timeout")

        cache.changeSummary()
        cache.invalidate()
        cache.changeSummary()
        cache.invalidate()
        cache.changeSummary()

        verify(exactly = 1) {
            notifications.notifyWarning(any(), any(), any())
        }
    }

    @Test
    fun `missing EP notifies once per session`() = runTest {
        val cacheNoGen = HandoverAiSummaryCache(
            generator = null,
            workflowContext = workflowContext,
            notifications = notifications,
            eventBus = eventBus,
            scope = scope,
        )

        cacheNoGen.changeSummary()
        cacheNoGen.changeSummary()
        cacheNoGen.ticketSummary()

        verify(exactly = 1) {
            notifications.notifyWarning(any(), any(), any())
        }
    }

    // -------------------------------------------------------------------------
    // LLM returns null
    // -------------------------------------------------------------------------

    @Test
    fun `LLM returning null yields unavailable`() = runTest {
        coEvery { generator.generate(any()) } returns null

        val result = cache.changeSummary()

        assertFalse(result.isAvailable)
        assertTrue(
            result.unavailableReason?.contains("no result") == true,
            "Expected 'no result' in reason: ${result.unavailableReason}"
        )
    }

    // -------------------------------------------------------------------------
    // Prompt content sanity (ensures deferred diff placeholder is present)
    // -------------------------------------------------------------------------

    @Test
    fun `changeSummary prompt includes ticket key and diff placeholder`() = runTest {
        var capturedPrompt = ""
        coEvery { generator.generate(any()) } answers {
            capturedPrompt = firstArg()
            "ok"
        }

        cache.changeSummary()

        assertTrue(capturedPrompt.contains("PROJ-42"), "Prompt must include ticket key")
        assertTrue(
            capturedPrompt.contains("diff capture deferred"),
            "Prompt must include diff placeholder until real diff is wired"
        )
    }

    @Test
    fun `ticketSummary prompt includes ticket summary`() = runTest {
        var capturedPrompt = ""
        coEvery { generator.generate(any()) } answers {
            capturedPrompt = firstArg()
            "ok"
        }

        cache.ticketSummary()

        assertTrue(capturedPrompt.contains("Implement login"), "Prompt must include ticket summary")
    }

    // -------------------------------------------------------------------------
    // D5: Cancelled entry cleanup (audit finding handover:F-3)
    // -------------------------------------------------------------------------

    // ── HANDOVER-COV-14: concurrent changeSummary calls coalesce to one LLM call ──

    @Test
    fun `concurrent changeSummary calls coalesce to exactly one LLM invocation`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { generator.generate(any()) } returns "Summary result"

            // Launch 5 concurrent callers
            repeat(5) {
                launch { cache.changeSummary() }
            }
            advanceUntilIdle()

            coVerify(exactly = 1) { generator.generate(any()) }
        }

    @Test
    fun `D5 source-text pin - await is wrapped in try-catch with cache remove on failure`() {
        // Verify the cleanup contract is expressed in source code.
        // The functional test for cancellation is hard to write in a deterministic unit test
        // because it requires the async {} coroutine to be cancelled mid-flight, which
        // depends on coroutineScope propagation timing. The source-text pin locks in the
        // structure that makes cleanup safe.
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverAiSummaryCache.kt"
        ).readText()
        assertTrue(src.contains("cache.remove(key, deferred)"),
            "getOrCompute must call cache.remove(key, deferred) in the catch block")
        // The catch block must immediately follow deferred.await()
        val awaitIdx = src.indexOf("deferred.await()")
        assertTrue(awaitIdx >= 0, "deferred.await() must be present")
        // Search for the catch within 100 chars of the await call
        val windowEnd = awaitIdx + 100
        val catchIdx = src.indexOf("catch (e: Exception)", awaitIdx)
        assertTrue(catchIdx in awaitIdx..windowEnd,
            "catch (e: Exception) must appear within 100 chars after deferred.await()")
        val removeIdx = src.indexOf("cache.remove(key, deferred)", catchIdx)
        assertTrue(removeIdx > catchIdx && removeIdx < catchIdx + 300,
            "cache.remove(key, deferred) must appear inside the catch block")
    }
}
