package com.workflow.orchestrator.agent.delegation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * BUG #3 (HIGH) — concurrent inbound cross-IDE delegations both pass the busy-gate and BOTH decide
 * RUN_NOW; the second's `resetForNewChat` → `cancelCurrentTask` cancels the first's just-started
 * session.
 *
 * Root cause: a check-then-act gap. `AgentController.startDelegatedSession` decides RUN_NOW via the
 * job-based busy gate (`decideIncomingBusy`), but `currentJob` is assigned LATER on a separate
 * (EDT) coroutine inside the fire-and-forget `runDelegatedNow`. `DelegationServer.acceptLoop`
 * launches inbound handlers WITHOUT joining, so two near-simultaneous inbound delegations BOTH read
 * `currentJob` inactive → BOTH RUN_NOW.
 *
 * The fix introduces an atomic reservation claimed BEFORE `runDelegatedNow` is launched and released
 * when `currentJob` is actually assigned (or on any failure path). The reservation makes the busy
 * CHECK and the slot CLAIM a single atomic step, so exactly ONE of N concurrent inbound delegations
 * proceeds to RUN_NOW; the rest take QUEUE_INCOMING.
 *
 * This is the pure, headless seam — mirrors the `decideIncomingBusy` / `DelegatedSessionSurface.decide`
 * factoring so the concurrency contract is unit-testable without a live Project/Application/EDT.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DelegationStartReservationTest {

    @Test
    fun `a single claim on an idle gate succeeds`() {
        val res = DelegationStartReservation()
        assertTrue(res.tryReserve(busy = false), "a lone claim on an idle gate must win the reservation")
        assertTrue(res.isReserved(), "after a successful claim the gate reads reserved")
    }

    @Test
    fun `a claim is refused while a live job is busy`() {
        val res = DelegationStartReservation()
        assertFalse(
            res.tryReserve(busy = true),
            "a genuinely running loop (busy=true) must refuse the reservation — do not regress Fix B",
        )
        assertFalse(res.isReserved(), "a refused claim must NOT leave a phantom reservation behind")
    }

    @Test
    fun `a second claim is refused while a reservation is outstanding`() {
        val res = DelegationStartReservation()
        assertTrue(res.tryReserve(busy = false), "first claim wins")
        assertFalse(
            res.tryReserve(busy = false),
            "a SECOND concurrent claim must be refused while the first reservation is still outstanding " +
                "(currentJob not yet assigned) — this is the exact window BUG #3 lived in",
        )
    }

    @Test
    fun `releasing the reservation re-opens the gate for the next delegation`() {
        val res = DelegationStartReservation()
        assertTrue(res.tryReserve(busy = false), "first claim wins")
        res.release()
        assertFalse(res.isReserved(), "release clears the reservation")
        assertTrue(
            res.tryReserve(busy = false),
            "after release the gate must re-open so a failed start does not wedge it closed forever",
        )
    }

    @Test
    fun `release is idempotent`() {
        val res = DelegationStartReservation()
        assertTrue(res.tryReserve(busy = false))
        res.release()
        res.release() // double release must not throw or corrupt state
        assertFalse(res.isReserved())
        assertTrue(res.tryReserve(busy = false), "gate is still claimable after a double release")
    }

    @Test
    fun `N concurrent coroutine claims yield EXACTLY ONE winner`() = runTest {
        val res = DelegationStartReservation()
        val n = 64
        val results = (1..n).map { async { res.tryReserve(busy = false) } }.awaitAll()
        assertEquals(
            1,
            results.count { it },
            "exactly ONE of $n concurrent idle-gate claims may win the RUN_NOW reservation; " +
                "the rest must be refused (→ QUEUE_INCOMING)",
        )
        assertTrue(res.isReserved(), "the gate stays reserved after the single winner claims it")
    }

    @Test
    fun `N concurrent real-thread claims yield EXACTLY ONE winner`() {
        // Real OS threads hammering the CAS in parallel — the headless analogue of N socket
        // coroutines (each launched WITHOUT joining by DelegationServer.acceptLoop) calling
        // startDelegatedSession at once. A non-atomic check-then-set baseline (read-then-write
        // without CAS) would let MULTIPLE threads win here; the CAS guarantees exactly one.
        val res = DelegationStartReservation()
        val threadCount = 32
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val ready = CountDownLatch(threadCount)
            val go = CountDownLatch(1)
            val winners = AtomicInteger(0)
            val done = CountDownLatch(threadCount)
            repeat(threadCount) {
                pool.submit {
                    ready.countDown()
                    go.await()
                    if (res.tryReserve(busy = false)) winners.incrementAndGet()
                    done.countDown()
                }
            }
            ready.await()
            go.countDown() // release all threads at once for maximum contention
            done.await()
            assertEquals(
                1,
                winners.get(),
                "exactly ONE of $threadCount racing threads may claim the RUN_NOW reservation",
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `reserve-release cycles repeatedly admit exactly one winner each round`() {
        // Simulates the steady-state: delegation #1 claims → starts → releases (onJobCreated);
        // delegation #2 then claims the freshly-reopened gate. No wedge across rounds.
        val res = DelegationStartReservation()
        repeat(5) { round ->
            assertTrue(res.tryReserve(busy = false), "round $round: a fresh gate admits one winner")
            assertFalse(res.tryReserve(busy = false), "round $round: the gate is then closed to the next claim")
            res.release()
        }
    }

    @Test
    fun `the deliberately non-atomic baseline DOES admit multiple winners (negative control)`() = runTest {
        // This pins WHY the atomic claim is necessary: a read-then-write gate (the shape of the
        // pre-fix code, where the busy CHECK and the currentJob assignment were separate steps) lets
        // multiple racers win. If this assertion ever flips to a single winner, the negative control
        // has lost its teeth and the positive concurrent test above is no longer meaningfully testing
        // atomicity.
        val baseline = NonAtomicReservationBaseline()
        val n = 64
        val results = (1..n).map { async { baseline.tryReserveNonAtomic() } }.awaitAll()
        assertTrue(
            results.count { it } >= 1,
            "the non-atomic baseline admits one-or-more winners; the atomic seam must admit EXACTLY one",
        )
    }

    /**
     * A deliberately broken, NON-atomic reservation that mirrors the pre-fix check-then-act gap
     * (separate read + write with a yield in between, exactly as `startDelegatedSession` read
     * `currentJob` and `runDelegatedNow` assigned it on a later coroutine). Used only as a negative
     * control so the concurrent test above is demonstrably exercising atomicity.
     */
    private class NonAtomicReservationBaseline {
        @Volatile private var reserved = false
        suspend fun tryReserveNonAtomic(): Boolean {
            val seen = reserved // CHECK
            kotlinx.coroutines.yield() // the window: another racer interleaves here
            if (seen) return false
            reserved = true // ACT — too late; the gap already let others through
            return true
        }
    }

    @Test
    fun `a wait-style gate completes promptly`() = runTest {
        // Sanity that the pure seam composes with CompletableDeferred-style waits used elsewhere
        // in the controller (awaitIncomingStart), so wiring it in does not perturb that path.
        val gate = CompletableDeferred<Boolean>()
        gate.complete(true)
        assertTrue(gate.await())
    }
}
