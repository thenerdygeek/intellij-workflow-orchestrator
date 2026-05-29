package com.workflow.orchestrator.agent

import com.workflow.orchestrator.core.ai.ModelCatalogService
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase 7 followup F-P6FU-1 + F-P6FU-2 — pin the contract for the shared
 * `ModelCatalogService` cache key so the dead-tokenProvider half + missing
 * tests called out by the Phase 6 fix-up reviewer cannot regress.
 *
 * **Contract pinned by these tests:**
 *
 *  1. **Same `sgUrl` → same instance** — repeat calls with the same URL return
 *     the cached `ModelCatalogService`. This is the load-bearing performance
 *     property: AgentService warms it up exactly once per URL.
 *  2. **Different `sgUrl` → fresh instance** — switching the configured
 *     Sourcegraph URL evicts the cache and constructs a new catalog. Without
 *     this, a user reconfiguring `ConnectionSettings.sourcegraphUrl` would get
 *     stale catalog data forever.
 *  3. **Warm-up coroutine survives `getCatalog()` throw** — F-P6FU-1's "no
 *     propagation" guarantee. The warm-up runs in the injected scope
 *     fire-and-forget; an HTTP failure during warm-up must NOT bubble up to
 *     the caller of `get()` and must NOT cancel the scope.
 */
class SharedCatalogHolderTest {

    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `same sgUrl returns same cached instance across calls`() {
        val factoryCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val factory: (String, () -> String?) -> ModelCatalogService = { _, _ ->
            factoryCalls.incrementAndGet()
            mockk<ModelCatalogService>(relaxed = true)
        }
        val holder = SharedCatalogHolder(scope, factory) { /* no warm-up */ }

        val first = holder.get("https://sg.example.com") { "tok" }
        val second = holder.get("https://sg.example.com") { "tok" }
        val third = holder.get("https://sg.example.com") { "different-token" }

        assertSame(first, second, "same sgUrl + same token must return cached instance")
        assertSame(first, third, "same sgUrl ignores token-provider identity (1-hour TTL covers rotation)")
        assertEquals(1, factoryCalls.get(), "factory must run exactly once per URL")
    }

    @Test
    fun `different sgUrl evicts cache and creates new instance`() {
        val factoryCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val factory: (String, () -> String?) -> ModelCatalogService = { _, _ ->
            factoryCalls.incrementAndGet()
            mockk<ModelCatalogService>(relaxed = true)
        }
        val holder = SharedCatalogHolder(scope, factory) { /* no warm-up */ }

        val a1 = holder.get("https://sg.example.com") { "tok" }
        val b = holder.get("https://other.sourcegraph.com") { "tok" }
        val a2 = holder.get("https://sg.example.com") { "tok" }

        assertNotSame(a1, b, "different sgUrl must yield different instances")
        // After URL flip back, cache rebuilds — we don't promise to remember the original.
        assertNotSame(a2, a1, "URL flip evicted the original; second flip rebuilds")
        assertEquals(3, factoryCalls.get(), "factory ran on each URL transition")
    }

    @Test
    fun `warm-up coroutine survives a getCatalog throw without propagating`() {
        val warmUpRan = CountDownLatch(1)
        val warmUpExceptions = java.util.concurrent.atomic.AtomicInteger(0)
        val factory: (String, () -> String?) -> ModelCatalogService = { _, _ ->
            mockk<ModelCatalogService>(relaxed = true)
        }
        // SharedCatalogHolder.get() launches the warm-up on Dispatchers.IO (correct for prod —
        // warm-up performs network I/O). A StandardTestDispatcher + advanceUntilIdle therefore
        // CANNOT drive it: the coroutine runs on a real IO thread, so asserting on
        // warmUpExceptions right after advanceUntilIdle raced the thread and flaked under load.
        // Synchronize on a latch the warm-up counts down so the assertion is deterministic.
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val holder = SharedCatalogHolder(
            scope = testScope,
            factory = factory,
            warmUp = {
                warmUpExceptions.incrementAndGet()
                warmUpRan.countDown()
                throw RuntimeException("simulated catalog fetch failure")
            },
        )

        // Calling get() must not throw even though the warm-up will throw.
        val instance = holder.get("https://sg.example.com") { "tok" }

        assertNotNull(instance)
        assertTrue(warmUpRan.await(10, TimeUnit.SECONDS), "warm-up coroutine should have run")
        assertEquals(1, warmUpExceptions.get(), "warm-up was invoked")
        // Scope must still be active — runCatching swallows the throw and the scope is a
        // SupervisorJob, so a warm-up failure can never cancel the holder scope.
        assertTrue(testScope.coroutineContext[Job]!!.isActive,
            "warm-up exception must not cancel the holder scope")
        testScope.cancel()
    }
}
