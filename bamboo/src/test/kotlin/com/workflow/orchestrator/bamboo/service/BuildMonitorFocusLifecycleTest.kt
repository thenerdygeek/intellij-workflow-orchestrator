package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.workflow.BuildRef
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for T-B2/B3-a: [BuildMonitorService] subscribes to a [focusBuildFlow] and drives
 * its own polling lifecycle ([startPolling]/[stopPolling]) in response to focus changes.
 *
 * ## Design rationale
 *
 * ### Recording subclass instead of spyk
 * The focus subscription is wired via [BuildMonitorService.wireFocusBuildSubscription] which
 * is called from the constructor. `spyk` wraps only **after** construction; by then the
 * subscription coroutine is already launched against the original method references, not the
 * spy's. Overriding via subclass ensures the subscription calls the overridden methods.
 *
 * ### backgroundScope for subscription lifetime
 * [kotlinx.coroutines.test.TestScope] has two coroutine scopes:
 *  - The main scope: `runTest` waits for ALL child coroutines to complete before returning.
 *    A `collectLatest` on an infinite flow (like a `StateFlow`) would cause `runTest` to
 *    hang indefinitely with `UncompletedCoroutinesError`.
 *  - `backgroundScope`: `runTest` cancels this scope when the test body finishes, so
 *    infinite collection coroutines launched here are auto-cancelled at test end.
 *
 * We pass `backgroundScope` as the `scope` argument to the service constructor so
 * `wireFocusBuildSubscription`'s `cs.launch { collectLatest {...} }` runs on the
 * background scope and does not block `runTest` from completing.
 *
 * ### yield() for scheduling
 * `yield()` suspends the test coroutine and lets other ready coroutines (including the
 * subscription coroutine) run. One `yield()` is sufficient for each flow emission because
 * `StateFlow.collectLatest` delivers the new value synchronously once the coroutine is
 * scheduled. Multiple `yield()` calls ensure the subscription has processed the emission
 * before the assertion.
 *
 * ### RecordingBuildMonitorService intercepts startPolling / stopPolling
 * The overridden `startPolling` records arguments without starting [SmartPoller]. This avoids
 * the poller's infinite `while (isActive) { delay(interval) }` loop under virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuildMonitorFocusLifecycleTest {

    /**
     * Recording subclass of [BuildMonitorService].
     *
     * Overrides [startPolling] and [stopPolling] to record invocations without starting
     * a [com.workflow.orchestrator.core.polling.SmartPoller] — which would spin
     * indefinitely under virtual time.
     */
    private class RecordingBuildMonitorService(
        apiClient: BambooApiClient,
        eventBus: EventBus,
        scope: kotlinx.coroutines.CoroutineScope,
        focusBuildFlow: kotlinx.coroutines.flow.StateFlow<BuildRef?>? = null,
    ) : BuildMonitorService(
        apiClient = apiClient,
        eventBus = eventBus,
        scope = scope,
        focusBuildFlow = focusBuildFlow,
    ) {
        data class StartCall(val planKey: String, val branch: String, val intervalMs: Long)

        val startCalls = mutableListOf<StartCall>()
        var stopPollingCount = 0

        override fun startPolling(planKey: String, branch: String, intervalMs: Long) {
            startCalls += StartCall(planKey, branch, intervalMs)
            // Do NOT delegate to super — that would launch an infinite SmartPoller coroutine.
        }

        override fun stopPolling() {
            stopPollingCount++
            // Do NOT delegate to super — no active SmartPoller to stop.
        }
    }

    private val apiClient: BambooApiClient = mockk<BambooApiClient>().also { client ->
        coEvery { client.getLatestResult(any()) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "lifecycle-test stub")
        coEvery { client.getRunningAndQueuedBuilds(any()) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "lifecycle-test stub")
        coEvery { client.getBuildLog(any()) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "lifecycle-test stub")
    }
    private val eventBus = EventBus()

    // ----------------------------------------------------------------------------------
    // Scenario 1 — null focus: no polling started
    // ----------------------------------------------------------------------------------

    @Test
    fun `null focusBuildFlow — service does not call startPolling`() = runTest {
        val focusFlow = MutableStateFlow<BuildRef?>(null)
        val service = RecordingBuildMonitorService(
            apiClient = apiClient,
            eventBus = eventBus,
            // backgroundScope: the collectLatest coroutine is cancelled at test end,
            // preventing UncompletedCoroutinesError.
            scope = backgroundScope,
            focusBuildFlow = focusFlow,
        )

        // Yield so the subscription coroutine can process the initial null value.
        yield()

        assertEquals(0, service.startCalls.size, "startPolling must not be called for null focus")
    }

    // ----------------------------------------------------------------------------------
    // Scenario 2 — non-null BuildRef with chainKey: startPolling uses chainKey
    // ----------------------------------------------------------------------------------

    @Test
    fun `non-null focusBuild with chainKey — startPolling called with chainKey and branch`() = runTest {
        val focusFlow = MutableStateFlow<BuildRef?>(null)
        val service = RecordingBuildMonitorService(
            apiClient = apiClient,
            eventBus = eventBus,
            scope = backgroundScope,
            focusBuildFlow = focusFlow,
        )

        val ref = BuildRef(
            planKey = "P",
            chainKey = "P-CK",
            branch = "feature/x",
            buildNumber = 0,
            selectedJobKey = null,
        )
        focusFlow.value = ref
        // Two yields: first lets the subscription coroutine wake up; second ensures the
        // collectLatest body has executed the startPolling call.
        yield()
        yield()

        val calls = service.startCalls
        assertTrue(calls.isNotEmpty(), "startPolling must be called when focus is non-null")
        // chainKey must take precedence over planKey
        assertEquals("P-CK", calls.last().planKey,
            "startPolling must use chainKey (P-CK) not planKey (P)")
        assertEquals("feature/x", calls.last().branch)
    }

    // ----------------------------------------------------------------------------------
    // Scenario 3 — non-null BuildRef without chainKey: startPolling uses planKey
    // ----------------------------------------------------------------------------------

    @Test
    fun `non-null focusBuild without chainKey — startPolling called with planKey fallback`() = runTest {
        val focusFlow = MutableStateFlow<BuildRef?>(null)
        val service = RecordingBuildMonitorService(
            apiClient = apiClient,
            eventBus = eventBus,
            scope = backgroundScope,
            focusBuildFlow = focusFlow,
        )

        val ref = BuildRef(
            planKey = "PROJ-PLAN",
            chainKey = null,
            branch = "develop",
            buildNumber = 0,
            selectedJobKey = null,
        )
        focusFlow.value = ref
        yield()
        yield()

        val calls = service.startCalls
        assertTrue(calls.isNotEmpty(), "startPolling must be called when focus is non-null")
        assertEquals("PROJ-PLAN", calls.last().planKey,
            "startPolling must use planKey when chainKey is null")
        assertEquals("develop", calls.last().branch)
    }

    // ----------------------------------------------------------------------------------
    // Scenario 4 — focus then null: stopPolling called after second emit
    // ----------------------------------------------------------------------------------

    @Test
    fun `emit non-null then null — stopPolling called after null emit`() = runTest {
        val focusFlow = MutableStateFlow<BuildRef?>(null)
        val service = RecordingBuildMonitorService(
            apiClient = apiClient,
            eventBus = eventBus,
            scope = backgroundScope,
            focusBuildFlow = focusFlow,
        )

        val ref = BuildRef(
            planKey = "PROJ-PLAN",
            chainKey = "PROJ-PLAN-CK",
            branch = "main",
            buildNumber = 5,
            selectedJobKey = null,
        )
        focusFlow.value = ref
        yield()
        yield()  // subscription processes the non-null emit → startPolling recorded

        val startCountAfterFocus = service.startCalls.size
        assertTrue(startCountAfterFocus >= 1, "startPolling must have been called before null emit")

        focusFlow.value = null
        yield()
        yield()  // subscription processes the null emit → stopPolling recorded

        assertTrue(service.stopPollingCount >= 1, "stopPolling must be called after null focus emit")
    }

    // ----------------------------------------------------------------------------------
    // Scenario 5 — stateFlow stays null because startPolling is intercepted (sanity)
    // ----------------------------------------------------------------------------------

    @Test
    fun `stateFlow stays null when startPolling is intercepted`() = runTest {
        val focusFlow = MutableStateFlow<BuildRef?>(null)
        val service = RecordingBuildMonitorService(
            apiClient = apiClient,
            eventBus = eventBus,
            scope = backgroundScope,
            focusBuildFlow = focusFlow,
        )

        val ref = BuildRef(
            planKey = "PLAN-A",
            chainKey = "PLAN-A-CK",
            branch = "feature/a",
            buildNumber = 1,
            selectedJobKey = null,
        )
        focusFlow.value = ref
        yield()
        yield()

        // stateFlow must be null — startPolling was intercepted so SmartPoller never ran
        assertNull(service.stateFlow.value,
            "stateFlow must remain null when startPolling is intercepted (no SmartPoller launched)")
    }

    // ----------------------------------------------------------------------------------
    // Scenario 6 — rapid A then B: B's planKey wins when StateFlow coalesces
    // StateFlow keeps only the latest value; when the subscription first gets CPU time,
    // only B's value is visible (A was overwritten before yield).
    // ----------------------------------------------------------------------------------

    @Test
    fun `rapid A then B focus — B startPolling target is observed after StateFlow coalescing`() = runTest {
        val focusFlow = MutableStateFlow<BuildRef?>(null)
        val service = RecordingBuildMonitorService(
            apiClient = apiClient,
            eventBus = eventBus,
            scope = backgroundScope,
            focusBuildFlow = focusFlow,
        )

        val refA = BuildRef(
            planKey = "PLAN-A",
            chainKey = "PLAN-A-CK",
            branch = "feature/a",
            buildNumber = 1,
            selectedJobKey = null,
        )
        val refB = BuildRef(
            planKey = "PLAN-B",
            chainKey = "PLAN-B-CK",
            branch = "feature/b",
            buildNumber = 2,
            selectedJobKey = null,
        )

        // Emit A then B before yielding — StateFlow coalesces; subscription sees only B.
        focusFlow.value = refA
        focusFlow.value = refB
        yield()
        yield()

        // Last recorded startPolling call must target B's chain key.
        assertTrue(service.startCalls.isNotEmpty(), "startPolling must have been called at least once")
        val lastCall = service.startCalls.last()
        assertEquals("PLAN-B-CK", lastCall.planKey,
            "Last startPolling must target PLAN-B-CK (StateFlow coalesced A→B)")
        assertEquals("feature/b", lastCall.branch)
    }
}
