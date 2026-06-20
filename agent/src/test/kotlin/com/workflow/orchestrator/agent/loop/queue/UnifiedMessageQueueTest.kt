package com.workflow.orchestrator.agent.loop.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedMessageQueueTest {
    private fun q() = UnifiedMessageQueue("s1", persistence = null)
    private fun m(id: String, kind: QueueSourceKind, ts: Long, prio: Int, body: String = id, key: String? = null, meta: Map<String, String> = emptyMap()) =
        QueuedMessage(id, kind, body, ts, prio, key, meta)

    @Test
    fun `empty queue drains to nothing`() {
        assertTrue(q().isEmpty())
        assertEquals(emptyList<DrainGroup>(), q().drainGrouped())
    }

    @Test
    fun `drain groups by source in earliest-timestamp order`() {
        val q = q()
        q.enqueue(m("b1", QueueSourceKind.BACKGROUND, ts = 10, prio = 50))   // earliest
        q.enqueue(m("u1", QueueSourceKind.USER, ts = 20, prio = 100))         // later, higher prio
        val groups = q.drainGrouped()
        // earliest-first: BACKGROUND group precedes USER group despite USER's higher priority
        assertEquals(listOf(QueueSourceKind.BACKGROUND, QueueSourceKind.USER), groups.map { it.kind })
        assertTrue(q.isEmpty())
    }

    @Test
    fun `priority breaks ties at equal timestamp`() {
        val q = q()
        q.enqueue(m("mon", QueueSourceKind.MONITOR, ts = 5, prio = 30))
        q.enqueue(m("usr", QueueSourceKind.USER, ts = 5, prio = 100))
        // same ts → higher priority first → USER group before MONITOR group
        assertEquals(listOf(QueueSourceKind.USER, QueueSourceKind.MONITOR), q.drainGrouped().map { it.kind })
    }

    @Test
    fun `coalesce replaces an earlier same-key item latest-wins`() {
        val q = q()
        q.enqueue(m("b1", QueueSourceKind.BACKGROUND, ts = 1, prio = 50, body = "old", key = "bg7"))
        q.enqueue(m("b2", QueueSourceKind.BACKGROUND, ts = 2, prio = 50, body = "new", key = "bg7"))
        val groups = q.drainGrouped()
        assertEquals(1, groups.size)
        assertTrue(groups[0].framedText.contains("new"))
        assertFalse(groups[0].framedText.contains("old"))
    }

    @Test
    fun `drain group carries the policy reset and defer flags`() {
        val q = q()
        q.enqueue(m("u", QueueSourceKind.USER, ts = 1, prio = 100))
        q.enqueue(m("mo", QueueSourceKind.MONITOR, ts = 2, prio = 30))
        val byKind = q.drainGrouped().associateBy { it.kind }
        assertTrue(byKind[QueueSourceKind.USER]!!.resetsUserSilenceCounter)
        assertTrue(byKind[QueueSourceKind.USER]!!.defersCompletion)
        assertFalse(byKind[QueueSourceKind.MONITOR]!!.resetsUserSilenceCounter)
        assertFalse(byKind[QueueSourceKind.MONITOR]!!.defersCompletion)
    }

    @Test
    fun `remove deletes a single pending item`() {
        val q = q()
        q.enqueue(m("u1", QueueSourceKind.USER, ts = 1, prio = 100))
        q.enqueue(m("u2", QueueSourceKind.USER, ts = 2, prio = 100))
        assertTrue(q.remove("u1"))
        assertFalse(q.remove("nope"))
        assertEquals(listOf("u2"), q.pendingIds())
    }

    @org.junit.jupiter.api.Test
    fun `durable items reload into a fresh queue and user items do not persist`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val p = QueuePersistence(dir)
        val q1 = UnifiedMessageQueue("s1", p)
        q1.enqueue(m("bg", QueueSourceKind.BACKGROUND, ts = 1, prio = 50, key = "k"))
        q1.enqueue(m("usr", QueueSourceKind.USER, ts = 2, prio = 100))  // durable=false
        val q2 = UnifiedMessageQueue("s1", p)                            // reconstruct from disk
        assertEquals(listOf("bg"), q2.pendingIds())                     // only the durable item survives
    }
}
