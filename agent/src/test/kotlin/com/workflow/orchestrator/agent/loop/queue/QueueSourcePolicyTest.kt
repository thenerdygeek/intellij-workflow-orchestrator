package com.workflow.orchestrator.agent.loop.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueueSourcePolicyTest {

    private fun msg(kind: QueueSourceKind, body: String, key: String? = null, meta: Map<String, String> = emptyMap()) =
        QueuedMessage("id-$body", kind, body, 0L, 0, key, meta)

    @Test
    fun `user frame reproduces the legacy steering prefix byte-for-byte`() {
        val legacy = "The user sent an additional message while you were working. " +
            "Incorporate their feedback while continuing your current task:\n\n"
        val out = UserQueuePolicy.frame(listOf(msg(QueueSourceKind.USER, "fix auth"), msg(QueueSourceKind.USER, "and logout")))
        assertEquals(legacy + "fix auth\n\nand logout", out)
    }

    @Test
    fun `user policy flags`() {
        assertEquals(QueueSourceKind.USER, UserQueuePolicy.kind)
        assertTrue(UserQueuePolicy.resetsUserSilenceCounter)
        assertFalse(UserQueuePolicy.durable)
        assertTrue(UserQueuePolicy.defersCompletion)
        assertNull(UserQueuePolicy.coalesceKey(msg(QueueSourceKind.USER, "x")))
    }

    @Test
    fun `background coalesces by bgId and never resets the user counter`() {
        assertEquals("bg-7", BackgroundQueuePolicy.coalesceKey(msg(QueueSourceKind.BACKGROUND, "b", meta = mapOf("bgId" to "bg-7"))))
        assertFalse(BackgroundQueuePolicy.resetsUserSilenceCounter)
        assertTrue(BackgroundQueuePolicy.durable)
    }

    @Test
    fun `background frame lists bodies and appends an action directive`() {
        val out = BackgroundQueuePolicy.frame(listOf(msg(QueueSourceKind.BACKGROUND, "[BACKGROUND COMPLETION] exit 0")))
        assertTrue(out.contains("[BACKGROUND COMPLETION] exit 0"))
        assertTrue(out.contains("Decide whether"), "live background framing must carry an action directive")
    }

    @Test
    fun `monitor coalesces by monitorId and does not defer completion`() {
        assertEquals("mon-3", MonitorQueuePolicy.coalesceKey(msg(QueueSourceKind.MONITOR, "m", meta = mapOf("monitorId" to "mon-3"))))
        assertFalse(MonitorQueuePolicy.defersCompletion)
    }

    @Test
    fun `delegation policy flags`() {
        assertEquals(QueueSourceKind.DELEGATION, DelegationQueuePolicy.kind)
        assertEquals(70, DelegationQueuePolicy.priority)
        assertTrue(DelegationQueuePolicy.autoWakesIdle)
        assertTrue(DelegationQueuePolicy.durable)
        assertTrue(DelegationQueuePolicy.defersCompletion)
        assertFalse(DelegationQueuePolicy.resetsUserSilenceCounter)
        assertNull(DelegationQueuePolicy.coalesceKey(msg(QueueSourceKind.DELEGATION, "x")))
    }

    @Test
    fun `registry resolves every kind`() {
        QueueSourceKind.values().forEach { assertEquals(it, QueueSourceRegistry.policyFor(it).kind) }
    }
}
