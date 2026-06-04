package com.workflow.orchestrator.agent.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ShellCommandSourceTest {
    @Test
    fun `non-matching lines produce no event`() {
        assertNull(ShellCommandSource.classify("m1", "routine progress line", Regex("ERROR|FAILED")))
    }

    @Test
    fun `matching line produces a NOTABLE event by default`() {
        val e = ShellCommandSource.classify("m1", "build step done", Regex("step done"))!!
        assertEquals(Severity.NOTABLE, e.severity)
        assertEquals("build step done", e.line)
        assertEquals("m1", e.monitorId)
    }

    @Test
    fun `failure signatures escalate to ALERT even when they also match the user filter`() {
        val e = ShellCommandSource.classify("m1", "Exception: boom FAILED", Regex("FAILED"))!!
        assertEquals(Severity.ALERT, e.severity)
    }
}
