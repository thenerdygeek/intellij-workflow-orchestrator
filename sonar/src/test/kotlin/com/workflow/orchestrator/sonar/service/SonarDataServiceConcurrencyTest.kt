package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.model.SonarState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)

/**
 * Concurrency regression tests for SonarDataService — covers findings F-4, F-5, F-10.
 *
 * All tests use standalone harnesses (no IntelliJ platform) that mirror the exact
 * production logic under test.
 */
class SonarDataServiceConcurrencyTest {

    // ──────────────────────────────────────────────────────────────────────────
    // F-4: apiClient getter — synchronized check-then-act
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that when the URL changes and N coroutines race to build a new
     * [SonarApiClient], only one client is created and the old one is closed.
     *
     * The harness replicates the Mutex-guarded [resolveApiClient] logic from
     * [SonarDataService] with a controllable client factory that counts
     * construction calls and tracks which clients were closed.
     */
    @Test
    fun `F-4 concurrent url change creates exactly one new client and closes the old one`() = runTest {
        val harness = ClientRaceHarness()
        // Start with URL-A client already cached.
        harness.prime("http://sonar-a")
        assertEquals(1, harness.createdCount.get(), "one client for URL-A")

        // Simulate URL change: N coroutines all call resolveApiClient concurrently.
        val N = 8
        harness.currentUrl = "http://sonar-b"
        val jobs = (1..N).map {
            async(Dispatchers.Default) { harness.resolveApiClient() }
        }
        jobs.awaitAll()

        // Only one additional client should have been created (the URL-B client).
        assertEquals(2, harness.createdCount.get(),
            "exactly one new client for URL-B — the mutex prevented extra creation")

        // The old (URL-A) client must have been closed exactly once.
        assertEquals(1, harness.closedCount.get(),
            "old URL-A client closed exactly once when replaced")
    }

    /**
     * Source-text pin: confirms [SonarDataService.resolveApiClient] uses a [Mutex]
     * and calls [SonarApiClient.close] on the replaced client. This is a static
     * contract test — it exists alongside the concurrency test above to make
     * reviewer intent explicit in the test suite.
     */
    @Test
    fun `F-4 source pin - SonarDataService resolveApiClient uses Mutex and closes old client`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt"
        ).readText()
        assertTrue(src.contains("apiClientMutex"), "field apiClientMutex must be present")
        assertTrue(src.contains("apiClientMutex.withLock"), "must use Mutex.withLock")
        assertTrue(src.contains("cachedApiClient?.close()"),
            "must close old client before replacing (OkHttp pool eviction)")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // F-5: refreshWith children use coroutineScope, not scope.async
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Stale-write test: start a slow refresh for branch A, then immediately start
     * a refresh for branch B. Branch A's HTTP children must be cancelled before
     * they can write to _stateFlow. The final state must reflect branch B.
     *
     * Uses [ControllableRefreshHarness] with the test scheduler so [advanceUntilIdle]
     * fully drains all coroutine work. Branch A blocks on [httpGate]; branch B
     * cancels A's job (including its coroutineScope children) and then writes
     * the B state.
     */
    @Test
    fun `F-5 stale-write - slow branch-A refresh cancelled by branch-B, state reflects B`() = runTest {
        val harness = ControllableRefreshHarness(this)

        // Branch A refresh: suspended inside coroutineScope {} via suspendCancellableCoroutine,
        // simulating a slow HTTP call that never completes.
        harness.startRefresh("feature/branch-a", "proj-a")
        // Advance virtual time: A's delay(1) runs, A enters the blocking suspendCancellableCoroutine.
        advanceUntilIdle()

        // Branch B refresh starts: cancels A's job → A's coroutineScope children cancelled →
        // A never writes to _stateFlow. B runs its debounce delay, falls through (no blocking),
        // and writes the B state.
        val jobB = harness.startRefresh("main", "proj-b")
        // Wait for B to finish (it has no blocking gate, so delay(1) is the only virtual time).
        advanceUntilIdle()
        jobB.join()

        // Final state must be branch B.
        val state = harness.stateFlow.value
        assertEquals("proj-b", state.projectKey,
            "state must reflect branch-B, not stale branch-A data")
        assertEquals("main", state.branch,
            "branch must be 'main' (branch-B), not 'feature/branch-a'")
    }

    /**
     * Cancellation behavior test: confirms that the job launched for branch A is
     * cancelled when branch B starts, and that A's job is no longer active.
     */
    @Test
    fun `F-10 prior refresh job is cancelled when a newer refresh starts`() = runTest {
        val harness = ControllableRefreshHarness(this)

        // Start refresh A — it suspends indefinitely inside coroutineScope {}.
        val jobA = harness.startRefresh("feature/branch-a", "proj-a")
        // Run A's debounce delay; A is now suspended at suspendCancellableCoroutine.
        advanceUntilIdle()
        assertTrue(jobA.isActive, "job A should still be active before B starts")

        // Start refresh B — cancels A, then completes.
        harness.startRefresh("main", "proj-b")
        advanceUntilIdle()

        assertFalse(jobA.isActive, "job A must be cancelled after refresh B starts")
    }

    /**
     * Source-text pin: confirms [SonarDataService.refreshWith] uses `coroutineScope {`
     * (not `scope.async {`) for its parallel HTTP children.
     */
    @Test
    fun `F-5 source pin - refreshWith uses coroutineScope for parallel children`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt"
        ).readText()
        assertTrue(src.contains("coroutineScope {"),
            "refreshWith must use coroutineScope { } (not scope.async) for structured concurrency")
        // Negative: no bare `scope.async` inside refreshWith.
        // We check there's no `scope.async` on lines that are not comments.
        val nonCommentLines = src.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(nonCommentLines.contains("scope.async"),
            "refreshWith must not use scope.async (would escape structured cancellation)")
    }

    /**
     * Source-text pin: confirms [SonarDataService] tracks [activeRefreshJob] and
     * cancels it at the start of each new refresh (F-10).
     */
    @Test
    fun `F-10 source pin - activeRefreshJob tracked and cancelled on new refresh`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt"
        ).readText()
        assertTrue(src.contains("activeRefreshJob"),
            "field activeRefreshJob must be present")
        assertTrue(src.contains("activeRefreshJob?.cancel()"),
            "activeRefreshJob must be cancelled when a new refresh starts")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Test harnesses (no IntelliJ platform required)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Replicates the [SonarDataService.resolveApiClient] mutex-guarded creation logic.
 * Counts client construction and closure so the race test can assert invariants.
 */
private class ClientRaceHarness {
    var currentUrl: String = ""
    val createdCount = AtomicInteger(0)
    val closedCount = AtomicInteger(0)

    private val mutex = Mutex()
    private var cachedClient: TrackingClient? = null
    private var cachedUrl: String? = null

    /** Initializes with an already-cached client for [url]. */
    fun prime(url: String) {
        currentUrl = url
        cachedUrl = url
        cachedClient = TrackingClient(closedCount).also { createdCount.incrementAndGet() }
    }

    suspend fun resolveApiClient(): TrackingClient {
        val url = currentUrl
        return mutex.withLock {
            if (url != cachedUrl || cachedClient == null) {
                cachedClient?.close()
                cachedUrl = url
                cachedClient = TrackingClient(closedCount).also { createdCount.incrementAndGet() }
            }
            cachedClient!!
        }
    }

    inner class TrackingClient(private val closedCount: AtomicInteger) {
        var closed = false
        fun close() {
            if (!closed) {
                closed = true
                closedCount.incrementAndGet()
            }
        }
    }
}

/**
 * Harness that replicates [SonarDataService.refreshForBranch] + [SonarDataService.refreshWith]
 * with a controllable gate so tests can interleave two refreshes and observe cancellation.
 *
 * Uses the [testScope] (typically `backgroundScope` from `runTest`) so all launched
 * coroutines run on the test scheduler. [advanceUntilIdle] drains all work.
 *
 * Design:
 * - Branch A blocks forever inside `coroutineScope {}` via [suspendCancellableCoroutine].
 * - When branch B calls [startRefresh], it cancels A's job → A's `coroutineScope` propagates
 *   cancellation to the suspended child → `CancellationException` exits `coroutineScope {}` →
 *   A never reaches the `_stateFlow.value = ...` line.
 * - Branch B does NOT block (the blocking flag is only set for call index 0), so it
 *   writes its state and completes normally.
 */
private class ControllableRefreshHarness(private val testScope: TestScope) {
    private val _stateFlow = MutableStateFlow(SonarState.EMPTY)
    val stateFlow get() = _stateFlow

    private var activeJob: Job? = null
    private var callCount = 0

    /**
     * Starts a refresh for [branch]/[projectKey].
     *   - Call 0 (branch A): blocks inside `coroutineScope {}` until cancelled.
     *   - Call 1+ (branch B): cancels A's job, writes state immediately.
     *
     * Returns the launched [Job]. Launched as a *child* of [testScope] so the
     * test scheduler drives virtual time. Branch A's job is explicitly cancelled by
     * branch B's call, so [runTest] won't see a lingering incomplete coroutine.
     */
    fun startRefresh(branch: String, projectKey: String): Job {
        activeJob?.cancel()
        val isBlockingCall = callCount == 0
        callCount++
        val job = testScope.launch {
            delay(1) // minimal debounce (virtual time, cooperative with test scheduler)
            // Replicate coroutineScope {} structure from the F-5/F-10 fix in SonarDataService.
            coroutineScope {
                if (isBlockingCall) {
                    // Branch A: suspend indefinitely. Only exits via CancellationException.
                    async {
                        suspendCancellableCoroutine<Unit> { /* never completes normally */ }
                    }.await()
                }
                // Branch B (isBlockingCall=false): falls through immediately.
            }
            // Only reached when coroutineScope completes normally (i.e., NOT cancelled).
            _stateFlow.value = SonarState.EMPTY.copy(
                projectKey = projectKey,
                branch = branch,
            )
        }
        activeJob = job
        return job
    }
}
