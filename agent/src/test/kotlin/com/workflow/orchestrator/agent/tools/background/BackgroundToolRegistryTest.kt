package com.workflow.orchestrator.agent.tools.background

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class BackgroundToolRegistryTest {
    private fun handle(id: String, session: String, started: Long) =
        BackgroundToolHandle(id, session, "run_command", started)

    @Test
    fun `list returns this session's handles oldest-first and isolates other sessions`() {
        val reg = BackgroundToolRegistry()
        val a2 = handle("a2", "S1", 200L)
        val a1 = handle("a1", "S1", 100L)
        val b1 = handle("b1", "S2", 150L)
        reg.add(a2); reg.add(a1); reg.add(b1)

        assertEquals(listOf("a1", "a2"), reg.list("S1").map { it.toolCallId })
        assertEquals(listOf("b1"), reg.list("S2").map { it.toolCallId })
        assertEquals(2, reg.countForSession("S1"))
    }

    @Test
    fun `findByToolCallId resolves across sessions and remove drops it`() {
        val reg = BackgroundToolRegistry()
        val a1 = handle("a1", "S1", 100L)
        reg.add(a1)
        assertSame(a1, reg.findByToolCallId("a1"))
        reg.remove(a1)
        assertNull(reg.findByToolCallId("a1"))
        assertEquals(0, reg.countForSession("S1"))
    }
}
