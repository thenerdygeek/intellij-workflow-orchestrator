package com.workflow.orchestrator.agent.loop.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hardening behavioral tests for [UnifiedMessageQueue] — edge cases and invariants that go
 * beyond the basic coverage in [UnifiedMessageQueueTest].
 */
class UnifiedMessageQueueBehaviorTest {

    // ─── helpers ────────────────────────────────────────────────────────────────

    private fun q(persistence: QueuePersistence? = null, sessionId: String = "s1") =
        UnifiedMessageQueue(sessionId, persistence)

    private fun m(
        id: String,
        kind: QueueSourceKind,
        ts: Long,
        prio: Int = kind.defaultPriority(),
        body: String = id,
        key: String? = null,
        meta: Map<String, String> = emptyMap(),
    ) = QueuedMessage(id, kind, body, ts, prio, key, meta)

    private fun QueueSourceKind.defaultPriority() = when (this) {
        QueueSourceKind.USER -> 100
        QueueSourceKind.DELEGATION -> 70
        QueueSourceKind.BACKGROUND -> 50
        QueueSourceKind.MONITOR -> 30
    }

    // ─── Scenario 1: Four-source single drain ────────────────────────────────────

    /**
     * Enqueue USER(ts=20), DELEGATION(ts=5), BACKGROUND(ts=15), MONITOR(ts=10) — interleaved
     * timestamps so timestamp order != static priority order.
     *
     * Expected drain order (by earliest item timestamp per group):
     *   DELEGATION (ts=5) → MONITOR (ts=10) → BACKGROUND (ts=15) → USER (ts=20)
     *
     * Verifies:
     *   (a) one DrainGroup per kind
     *   (b) groups ordered by earliest-timestamp, NOT static priority
     *   (c) each group's framedText uses the correct per-source framing (anti-mislabeling)
     *   (d) resetsUserSilenceCounter / defersCompletion flags match policies
     */
    @Test
    fun `four-source single drain orders by earliest-timestamp not by static priority`() {
        val q = q()
        // Interleaved: DELEGATION earliest, USER latest — reverses static priority order
        q.enqueue(m("d1", QueueSourceKind.DELEGATION, ts = 5,  body = "delegation result"))
        q.enqueue(m("mo1", QueueSourceKind.MONITOR,    ts = 10, body = "monitor event"))
        q.enqueue(m("bg1", QueueSourceKind.BACKGROUND, ts = 15, body = "bg complete"))
        q.enqueue(m("u1", QueueSourceKind.USER,        ts = 20, body = "user text"))

        val groups = q.drainGrouped()

        // (a) one DrainGroup per kind
        assertEquals(4, groups.size, "Expected exactly 4 drain groups, one per kind")
        assertEquals(
            setOf(QueueSourceKind.USER, QueueSourceKind.DELEGATION, QueueSourceKind.BACKGROUND, QueueSourceKind.MONITOR),
            groups.map { it.kind }.toSet(),
        )

        // (b) ordered by earliest-timestamp — DELEGATION(5) < MONITOR(10) < BACKGROUND(15) < USER(20)
        assertEquals(
            listOf(QueueSourceKind.DELEGATION, QueueSourceKind.MONITOR, QueueSourceKind.BACKGROUND, QueueSourceKind.USER),
            groups.map { it.kind },
            "Groups must be ordered by earliest-item timestamp, not static priority",
        )

        val byKind = groups.associateBy { it.kind }

        // (c) correct per-source framing — no mislabeling
        val userFrame = byKind[QueueSourceKind.USER]!!.framedText
        val delFrame  = byKind[QueueSourceKind.DELEGATION]!!.framedText
        val bgFrame   = byKind[QueueSourceKind.BACKGROUND]!!.framedText
        val monFrame  = byKind[QueueSourceKind.MONITOR]!!.framedText

        assertTrue(
            userFrame.startsWith(UserQueuePolicy.STEERING_MESSAGE_PREFIX),
            "USER frame must start with the canonical steering prefix",
        )
        assertTrue(userFrame.contains("user text"))

        assertTrue(delFrame.contains("[DELEGATION RESULTS]"), "DELEGATION frame must contain its header")
        assertTrue(delFrame.contains("delegation result"))

        assertTrue(bgFrame.contains("bg complete"))
        assertTrue(bgFrame.contains("Decide whether"), "BACKGROUND frame must contain action directive")

        assertTrue(monFrame.contains("# Monitor notifications"), "MONITOR frame must contain its header")
        assertTrue(monFrame.contains("monitor event"))

        // Anti-mislabeling: non-USER frames must NOT contain the user steering prefix
        val steeringPrefix = UserQueuePolicy.STEERING_MESSAGE_PREFIX
        assertFalse(delFrame.contains(steeringPrefix), "DELEGATION frame must not contain user steering prefix")
        assertFalse(bgFrame.contains(steeringPrefix),  "BACKGROUND frame must not contain user steering prefix")
        assertFalse(monFrame.contains(steeringPrefix), "MONITOR frame must not contain user steering prefix")

        // (d) resetsUserSilenceCounter / defersCompletion per policy
        assertTrue(byKind[QueueSourceKind.USER]!!.resetsUserSilenceCounter)
        assertTrue(byKind[QueueSourceKind.USER]!!.defersCompletion)

        assertFalse(byKind[QueueSourceKind.DELEGATION]!!.resetsUserSilenceCounter)
        assertTrue(byKind[QueueSourceKind.DELEGATION]!!.defersCompletion)

        assertFalse(byKind[QueueSourceKind.BACKGROUND]!!.resetsUserSilenceCounter)
        assertTrue(byKind[QueueSourceKind.BACKGROUND]!!.defersCompletion)

        assertFalse(byKind[QueueSourceKind.MONITOR]!!.resetsUserSilenceCounter)
        assertFalse(byKind[QueueSourceKind.MONITOR]!!.defersCompletion)
    }

    // ─── Scenario 2: Coalescing only affects the keyed source ────────────────────

    /**
     * Enqueue USER + BACKGROUND(bgId=X, body="old") + MONITOR, then BACKGROUND(bgId=X, body="new").
     * After coalesce:
     *  - BACKGROUND group shows "new" only
     *  - USER and MONITOR untouched
     *  - Coalesced item's timestamp (from latest write) governs its order in the group
     */
    @Test
    fun `coalescing only affects the keyed source, USER and MONITOR untouched`() {
        val q = q()
        q.enqueue(m("u1",  QueueSourceKind.USER,       ts = 1,  body = "user input"))
        q.enqueue(m("bg1", QueueSourceKind.BACKGROUND, ts = 2,  body = "old bg",
            key = "bgX", meta = mapOf("bgId" to "bgX")))
        q.enqueue(m("mo1", QueueSourceKind.MONITOR,    ts = 3,  body = "monitor data",
            meta = mapOf("monitorId" to "mon1")))
        // Coalesce: same bgId → replaces "old bg" with "new bg"
        q.enqueue(m("bg2", QueueSourceKind.BACKGROUND, ts = 4,  body = "new bg",
            key = "bgX", meta = mapOf("bgId" to "bgX")))

        val groups = q.drainGrouped()
        assertEquals(3, groups.size, "Should have exactly 3 groups: USER, BACKGROUND, MONITOR")

        val byKind = groups.associateBy { it.kind }

        // BACKGROUND: only "new bg" survives
        val bgFrame = byKind[QueueSourceKind.BACKGROUND]!!.framedText
        assertTrue(bgFrame.contains("new bg"), "Coalesced BACKGROUND must contain 'new bg'")
        assertFalse(bgFrame.contains("old bg"), "Coalesced BACKGROUND must NOT contain 'old bg'")

        // BACKGROUND group has exactly one id (bg2 replaced bg1)
        assertEquals(listOf("bg2"), byKind[QueueSourceKind.BACKGROUND]!!.ids)

        // USER and MONITOR untouched
        assertTrue(byKind[QueueSourceKind.USER]!!.framedText.contains("user input"))
        assertTrue(byKind[QueueSourceKind.MONITOR]!!.framedText.contains("monitor data"))
    }

    /**
     * After coalescing, the replacement item's timestamp (ts=4) governs drain order.
     * USER(ts=1) < coalesced-BG(ts=4).
     */
    @Test
    fun `coalesced item timestamp is the replacement items timestamp governing drain order`() {
        val q = q()
        // USER at ts=3, BACKGROUND original at ts=1, replacement at ts=5
        q.enqueue(m("u1",  QueueSourceKind.USER,       ts = 3, body = "user"))
        q.enqueue(m("bg1", QueueSourceKind.BACKGROUND, ts = 1, body = "old",
            key = "k1", meta = mapOf("bgId" to "k1")))
        q.enqueue(m("bg2", QueueSourceKind.BACKGROUND, ts = 5, body = "new",
            key = "k1", meta = mapOf("bgId" to "k1")))

        val kinds = q.drainGrouped().map { it.kind }
        // After coalesce bg1→bg2, the BACKGROUND item has ts=5 > USER ts=3
        // So USER(3) should come before BACKGROUND(5)
        assertEquals(listOf(QueueSourceKind.USER, QueueSourceKind.BACKGROUND), kinds,
            "Coalesced item takes replacement timestamp; USER(ts=3) precedes BACKGROUND(ts=5)")
    }

    // ─── Scenario 3: Coalesce across different keys does NOT merge ───────────────

    /**
     * Two BACKGROUND items with DIFFERENT bgId must stay as TWO items in one BACKGROUND group.
     */
    @Test
    fun `coalesce across different keys does NOT merge — two bgId produce two items in one group`() {
        val q = q()
        q.enqueue(m("bg1", QueueSourceKind.BACKGROUND, ts = 1, body = "process A done",
            key = "bgA", meta = mapOf("bgId" to "bgA")))
        q.enqueue(m("bg2", QueueSourceKind.BACKGROUND, ts = 2, body = "process B done",
            key = "bgB", meta = mapOf("bgId" to "bgB")))

        val groups = q.drainGrouped()
        assertEquals(1, groups.size, "Both BACKGROUND items share a single BACKGROUND group")
        assertEquals(QueueSourceKind.BACKGROUND, groups[0].kind)
        assertEquals(2, groups[0].ids.size, "Both bgId items must be present in the group")
        assertTrue(groups[0].framedText.contains("process A done"))
        assertTrue(groups[0].framedText.contains("process B done"))
    }

    // ─── Scenario 4: Concurrent enqueue is lossless ──────────────────────────────

    /**
     * 8 threads each enqueue 50 USER messages with unique IDs.
     * Result: exactly 400 items delivered, no duplicates, no exception.
     * USER has coalesceKey=null so none collapse.
     */
    @Test
    fun `concurrent enqueue is lossless — N threads times M USER messages = N times M delivered`() {
        val q = q()
        val threads = 8
        val perThread = 50
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val exceptions = CopyOnWriteArrayList<Throwable>()

        repeat(threads) { t ->
            executor.submit {
                try {
                    repeat(perThread) { i ->
                        q.enqueue(
                            QueuedMessage(
                                id = "t${t}_m${i}",
                                kind = QueueSourceKind.USER,
                                body = "thread $t message $i",
                                timestamp = System.nanoTime(),
                                priority = 100,
                                coalesceKey = null,
                                meta = emptyMap(),
                            ),
                        )
                    }
                } catch (e: Throwable) {
                    exceptions.add(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All threads should complete within 30s")
        executor.shutdown()
        assertTrue(exceptions.isEmpty(), "No exceptions expected: $exceptions")

        val groups = q.drainGrouped()
        val allIds = groups.flatMap { it.ids }
        assertEquals(threads * perThread, allIds.size,
            "Expected exactly ${threads * perThread} items, no lost enqueues")

        // No duplicates
        assertEquals(allIds.size, allIds.toSet().size, "All delivered IDs must be unique — no duplicates")
    }

    // ─── Scenario 5: drainGrouped consumes durable persistence ──────────────────

    /**
     * With a real QueuePersistence(@TempDir):
     * - Enqueue durable items → pending_queue.json exists
     * - Drain → file is gone (empty save deletes)
     * - Fresh UnifiedMessageQueue over the same dir loads nothing
     */
    @Test
    fun `drainGrouped consumes durable persistence — file gone after drain, fresh queue loads nothing`(
        @TempDir dir: Path,
    ) {
        val p = QueuePersistence(dir)
        val q1 = q(p)

        q1.enqueue(m("bg1", QueueSourceKind.BACKGROUND, ts = 1, key = "k1", meta = mapOf("bgId" to "k1")))
        q1.enqueue(m("mo1", QueueSourceKind.MONITOR, ts = 2, meta = mapOf("monitorId" to "mon-1")))

        val pendingFile = dir.resolve("sessions").resolve("s1").resolve("pending_queue.json")
        assertTrue(Files.exists(pendingFile), "pending_queue.json must exist before drain")

        val groups = q1.drainGrouped()
        assertEquals(2, groups.size)

        // File deleted after empty save
        assertFalse(Files.exists(pendingFile), "pending_queue.json must be deleted after drain clears all durable items")

        // Fresh queue over same dir loads nothing
        val q2 = q(p)
        assertTrue(q2.isEmpty(), "A fresh queue constructed after drain must load nothing from disk")
        assertEquals(emptyList<DrainGroup>(), q2.drainGrouped())
    }

    // ─── Scenario 6: remove() rewrites persistence ───────────────────────────────

    /**
     * Enqueue 2 durable items (persisted), remove one.
     * Reconstruct a fresh queue from disk: only the other survives.
     */
    @Test
    fun `remove rewrites persistence — removed item does not survive fresh queue construction`(
        @TempDir dir: Path,
    ) {
        val p = QueuePersistence(dir)
        val q1 = q(p)

        q1.enqueue(m("bg1", QueueSourceKind.BACKGROUND, ts = 1, key = "k1", meta = mapOf("bgId" to "k1")))
        q1.enqueue(m("bg2", QueueSourceKind.BACKGROUND, ts = 2, key = "k2", meta = mapOf("bgId" to "k2")))

        assertTrue(q1.remove("bg1"), "remove() must return true for existing id")

        // Reconstruct from disk
        val q2 = q(p)
        assertEquals(listOf("bg2"), q2.pendingIds(),
            "After removing bg1, only bg2 should survive on disk")
    }

    // ─── Scenario 7: clear(ids) partial ──────────────────────────────────────────

    /**
     * Enqueue 3 items. Clear 1 id. Assert the other 2 remain in pendingIds() and persistence reflects it.
     */
    @Test
    fun `clear of partial id set leaves the others intact`(@TempDir dir: Path) {
        val p = QueuePersistence(dir)
        val q = q(p)

        q.enqueue(m("bg1", QueueSourceKind.BACKGROUND, ts = 1, key = "k1", meta = mapOf("bgId" to "k1")))
        q.enqueue(m("bg2", QueueSourceKind.BACKGROUND, ts = 2, key = "k2", meta = mapOf("bgId" to "k2")))
        q.enqueue(m("bg3", QueueSourceKind.BACKGROUND, ts = 3, key = "k3", meta = mapOf("bgId" to "k3")))

        q.clear(listOf("bg2"))

        val remaining = q.pendingIds()
        assertEquals(2, remaining.size, "Should have 2 items after clearing 1")
        assertTrue(remaining.containsAll(listOf("bg1", "bg3")), "bg1 and bg3 must survive")
        assertFalse(remaining.contains("bg2"), "bg2 must be cleared")

        // Persistence reflects the partial clear
        val q2 = q(p)
        val diskIds = q2.pendingIds()
        assertEquals(2, diskIds.size)
        assertTrue(diskIds.containsAll(listOf("bg1", "bg3")))
        assertFalse(diskIds.contains("bg2"))
    }

    // ─── Scenario 8: Mixed durable/non-durable persistence ───────────────────────

    /**
     * Enqueue USER (durable=false) + BACKGROUND (durable=true).
     * Reconstruct from disk → only BACKGROUND survives.
     * USER never persisted — this is the revert-invariant cornerstone.
     */
    @Test
    fun `USER items are never persisted — only durable BACKGROUND survives reconstruction`(
        @TempDir dir: Path,
    ) {
        val p = QueuePersistence(dir)
        val q1 = q(p)

        q1.enqueue(m("u1",  QueueSourceKind.USER,       ts = 1))
        q1.enqueue(m("bg1", QueueSourceKind.BACKGROUND, ts = 2, key = "k1", meta = mapOf("bgId" to "k1")))

        // Both pending in-memory
        val allIds = q1.pendingIds()
        assertTrue(allIds.contains("u1"),  "USER item must be in-memory pending")
        assertTrue(allIds.contains("bg1"), "BACKGROUND item must be in-memory pending")

        // Reconstruct — USER item must NOT survive
        val q2 = q(p)
        val diskIds = q2.pendingIds()
        assertFalse(diskIds.contains("u1"),  "USER item must NOT be persisted (revert invariant)")
        assertTrue(diskIds.contains("bg1"),  "BACKGROUND item MUST be persisted")
        assertEquals(1, diskIds.size)
    }

    /**
     * Same as above but also verifies DELEGATION (durable=true) and MONITOR (durable=true)
     * survive while USER (durable=false) does not.
     */
    @Test
    fun `DELEGATION and MONITOR are durable but USER is not`(@TempDir dir: Path) {
        val p = QueuePersistence(dir)
        val q1 = q(p)

        q1.enqueue(m("u1",  QueueSourceKind.USER,       ts = 1))
        q1.enqueue(m("d1",  QueueSourceKind.DELEGATION, ts = 2))
        q1.enqueue(m("mo1", QueueSourceKind.MONITOR,    ts = 3, meta = mapOf("monitorId" to "mon-1")))

        val q2 = q(p)
        val diskIds = q2.pendingIds()
        assertFalse(diskIds.contains("u1"),  "USER must not persist")
        assertTrue(diskIds.contains("d1"),   "DELEGATION must persist")
        assertTrue(diskIds.contains("mo1"),  "MONITOR must persist")
    }

    // ─── Scenario 9 (Policy framing shapes) ──────────────────────────────────────

    /**
     * DelegationQueuePolicy.frame(group) must:
     *  - contain its delegation header "[DELEGATION RESULTS]"
     *  - contain the "answer ... delegation" directive
     */
    @Test
    fun `DelegationQueuePolicy frame contains header and answer directive`() {
        val msg = QueuedMessage("d1", QueueSourceKind.DELEGATION, "IDE-B finished the task", 0L, 70)
        val framed = DelegationQueuePolicy.frame(listOf(msg))
        assertTrue(framed.contains("[DELEGATION RESULTS]"),
            "Delegation frame must contain [DELEGATION RESULTS] header")
        assertTrue(framed.contains("delegation"), "Delegation frame must reference delegation")
        assertTrue(framed.contains("IDE-B finished the task"))
    }

    /**
     * MonitorQueuePolicy.frame(group) must contain "Monitor notifications".
     */
    @Test
    fun `MonitorQueuePolicy frame contains Monitor notifications header`() {
        val msg = QueuedMessage("mo1", QueueSourceKind.MONITOR, "CPU > 90%", 0L, 30)
        val framed = MonitorQueuePolicy.frame(listOf(msg))
        assertTrue(framed.contains("Monitor notifications"),
            "Monitor frame must contain 'Monitor notifications'")
        assertTrue(framed.contains("CPU > 90%"))
    }

    /**
     * NONE of the non-USER frames (DELEGATION, BACKGROUND, MONITOR) must contain
     * the USER STEERING prefix text. This is the anti-mislabeling guarantee.
     */
    @Test
    fun `non-USER policy frames do NOT contain the user steering prefix`() {
        val steeringPrefix = UserQueuePolicy.STEERING_MESSAGE_PREFIX

        val bgMsg = QueuedMessage("b1", QueueSourceKind.BACKGROUND, "done", 0L, 50)
        val bgFrame = BackgroundQueuePolicy.frame(listOf(bgMsg))
        assertFalse(bgFrame.contains(steeringPrefix),
            "BACKGROUND frame must NOT contain user steering prefix")

        val delMsg = QueuedMessage("d1", QueueSourceKind.DELEGATION, "result", 0L, 70)
        val delFrame = DelegationQueuePolicy.frame(listOf(delMsg))
        assertFalse(delFrame.contains(steeringPrefix),
            "DELEGATION frame must NOT contain user steering prefix")

        val monMsg = QueuedMessage("m1", QueueSourceKind.MONITOR, "alert", 0L, 30)
        val monFrame = MonitorQueuePolicy.frame(listOf(monMsg))
        assertFalse(monFrame.contains(steeringPrefix),
            "MONITOR frame must NOT contain user steering prefix")
    }

    // ─── Additional edge cases ───────────────────────────────────────────────────

    /**
     * Drain of a queue that has only non-durable (USER) items leaves no persistence file.
     */
    @Test
    fun `draining only USER items leaves no persistence file`(@TempDir dir: Path) {
        val p = QueuePersistence(dir)
        val q = q(p)

        q.enqueue(m("u1", QueueSourceKind.USER, ts = 1))
        q.enqueue(m("u2", QueueSourceKind.USER, ts = 2))

        val groups = q.drainGrouped()
        assertEquals(1, groups.size)
        assertEquals(QueueSourceKind.USER, groups[0].kind)

        val pendingFile = dir.resolve("sessions").resolve("s1").resolve("pending_queue.json")
        assertFalse(Files.exists(pendingFile),
            "No persistence file expected when only non-durable USER items were enqueued")
    }

    /**
     * Multiple USER items in one group — all bodies concatenated in the frame with the steering prefix only once.
     */
    @Test
    fun `USER framing concatenates multiple messages with single steering prefix`() {
        val q = q()
        q.enqueue(m("u1", QueueSourceKind.USER, ts = 1, body = "first message"))
        q.enqueue(m("u2", QueueSourceKind.USER, ts = 2, body = "second message"))

        val groups = q.drainGrouped()
        assertEquals(1, groups.size)
        val frame = groups[0].framedText
        // Steering prefix appears exactly once
        val prefixCount = frame.split(UserQueuePolicy.STEERING_MESSAGE_PREFIX).size - 1
        assertEquals(1, prefixCount, "Steering prefix must appear exactly once even for multi-message USER groups")
        assertTrue(frame.contains("first message"))
        assertTrue(frame.contains("second message"))
    }

    /**
     * Multiple MONITOR items with different monitorIds stay as separate items in one group.
     */
    @Test
    fun `multiple MONITOR items with different monitorIds are all in one group`() {
        val q = q()
        q.enqueue(m("mo1", QueueSourceKind.MONITOR, ts = 1, body = "event A",
            meta = mapOf("monitorId" to "mon-1")))
        q.enqueue(m("mo2", QueueSourceKind.MONITOR, ts = 2, body = "event B",
            meta = mapOf("monitorId" to "mon-2")))

        val groups = q.drainGrouped()
        assertEquals(1, groups.size)
        assertEquals(QueueSourceKind.MONITOR, groups[0].kind)
        assertEquals(2, groups[0].ids.size)
        assertTrue(groups[0].framedText.contains("event A"))
        assertTrue(groups[0].framedText.contains("event B"))
    }

    /**
     * MONITOR coalesces same monitorId — latest wins across different drain cycles.
     */
    @Test
    fun `MONITOR coalesces by monitorId latest-wins`() {
        val q = q()
        q.enqueue(m("mo1", QueueSourceKind.MONITOR, ts = 1, body = "first",
            key = "mon-1", meta = mapOf("monitorId" to "mon-1")))
        q.enqueue(m("mo2", QueueSourceKind.MONITOR, ts = 2, body = "second",
            key = "mon-1", meta = mapOf("monitorId" to "mon-1")))

        val groups = q.drainGrouped()
        assertEquals(1, groups.size)
        val ids = groups[0].ids
        assertEquals(1, ids.size, "Same monitorId must coalesce to one item")
        assertEquals("mo2", ids[0], "Latest item id must survive coalesce")
        assertTrue(groups[0].framedText.contains("second"))
        assertFalse(groups[0].framedText.contains("first"))
    }

    /**
     * isEmpty() returns false after enqueue, true after drain.
     */
    @Test
    fun `isEmpty transitions correctly around enqueue and drain`() {
        val q = q()
        assertTrue(q.isEmpty())
        q.enqueue(m("u1", QueueSourceKind.USER, ts = 1))
        assertFalse(q.isEmpty())
        q.drainGrouped()
        assertTrue(q.isEmpty())
    }

    /**
     * pendingIds() returns the correct ids in any order.
     */
    @Test
    fun `pendingIds returns all enqueued ids`() {
        val q = q()
        q.enqueue(m("a", QueueSourceKind.USER,       ts = 1))
        q.enqueue(m("b", QueueSourceKind.BACKGROUND, ts = 2, key = "kb", meta = mapOf("bgId" to "kb")))
        q.enqueue(m("c", QueueSourceKind.MONITOR,    ts = 3, meta = mapOf("monitorId" to "mon-c")))

        val ids = q.pendingIds()
        assertEquals(3, ids.size)
        assertTrue(ids.containsAll(listOf("a", "b", "c")))
    }

    /**
     * clear(emptyList()) is a no-op.
     */
    @Test
    fun `clear of empty id list is a no-op`() {
        val q = q()
        q.enqueue(m("u1", QueueSourceKind.USER, ts = 1))
        q.clear(emptyList())
        assertEquals(listOf("u1"), q.pendingIds())
    }

    /**
     * clear() with ids that don't exist is a no-op (no exception).
     */
    @Test
    fun `clear of non-existent ids is a no-op`() {
        val q = q()
        q.enqueue(m("u1", QueueSourceKind.USER, ts = 1))
        q.clear(listOf("nonexistent"))
        assertEquals(listOf("u1"), q.pendingIds())
    }

    /**
     * Drain of an already-empty queue is idempotent and returns empty list (not an exception).
     */
    @Test
    fun `drainGrouped on empty queue is idempotent`() {
        val q = q()
        assertEquals(emptyList<DrainGroup>(), q.drainGrouped())
        assertEquals(emptyList<DrainGroup>(), q.drainGrouped())
    }

    /**
     * DrainGroup.ids preserves the order items appear in the group (timestamp-sorted within group).
     */
    @Test
    fun `drain group ids list reflects the intra-group timestamp order`() {
        val q = q()
        // Multiple USER messages: u3 arrives latest but u1 has earliest timestamp
        q.enqueue(m("u3", QueueSourceKind.USER, ts = 30, body = "third"))
        q.enqueue(m("u1", QueueSourceKind.USER, ts = 10, body = "first"))
        q.enqueue(m("u2", QueueSourceKind.USER, ts = 20, body = "second"))

        val groups = q.drainGrouped()
        assertEquals(1, groups.size)
        assertEquals(listOf("u1", "u2", "u3"), groups[0].ids,
            "Ids within a group must be in timestamp order")
    }
}
