package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Behavioural pin for the mirror replay-log ring (audit P2-2 + bug B7).
 *
 * The old `CopyOnWriteArrayList` + `if (size < max) add(...)` log had its cap INVERTED:
 * once full it stopped ADDING, so a late-opened editor-tab mirror replayed the OLDEST
 * 5000 actions and silently missed everything newer (B7). The ring must do the
 * opposite — evict the OLDEST entry on overflow so the NEWEST content is what replays.
 */
class ReplayRingTest {

    @Test
    fun `under capacity keeps all items in insertion order`() {
        val ring = ReplayRing<Int>(5)
        (1..3).forEach { ring.add(it) }
        assertEquals(listOf(1, 2, 3), ring.snapshot())
    }

    @Test
    fun `overflow evicts the OLDEST entries and keeps the newest (B7)`() {
        val ring = ReplayRing<Int>(3)
        (1..5).forEach { ring.add(it) }
        assertEquals(
            listOf(3, 4, 5),
            ring.snapshot(),
            "on overflow the ring must drop the oldest items — keeping the oldest and " +
                "dropping the newest is the B7 stale-replay bug"
        )
    }

    @Test
    fun `size never exceeds capacity`() {
        val ring = ReplayRing<Int>(10)
        repeat(100) { ring.add(it) }
        assertEquals(10, ring.size())
        assertEquals((90..99).toList(), ring.snapshot())
    }

    @Test
    fun `clear empties the ring`() {
        val ring = ReplayRing<Int>(3)
        (1..3).forEach { ring.add(it) }
        ring.clear()
        assertEquals(0, ring.size())
        assertTrue(ring.snapshot().isEmpty())
    }

    @Test
    fun `snapshot is a defensive copy`() {
        val ring = ReplayRing<Int>(3)
        ring.add(1)
        val snap = ring.snapshot()
        ring.add(2)
        assertEquals(listOf(1), snap)
    }

    @Test
    fun `capacity below one is coerced to one`() {
        val ring = ReplayRing<Int>(0)
        ring.add(1)
        ring.add(2)
        assertEquals(listOf(2), ring.snapshot())
    }

    @Test
    fun `concurrent adds never corrupt the ring`() {
        val ring = ReplayRing<Int>(64)
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> ring.add(t * perThread + i) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(64, ring.size())
        assertEquals(64, ring.snapshot().size)
    }
}
