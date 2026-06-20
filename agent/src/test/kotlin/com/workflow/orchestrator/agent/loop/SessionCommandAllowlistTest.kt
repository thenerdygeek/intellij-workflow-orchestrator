package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionCommandAllowlistTest {
    @Test fun `approve normalizes case and spacing`() {
        val s = SessionCommandAllowlist()
        s.approve("  GIT   ADD  ")
        assertEquals(setOf("git add"), s.snapshot())
    }

    @Test fun `blank prefix is ignored`() {
        val s = SessionCommandAllowlist()
        s.approve("   ")
        assertTrue(s.snapshot().isEmpty())
    }

    @Test fun `covers delegates to CommandShape`() {
        val s = SessionCommandAllowlist()
        s.approve("git add")
        assertEquals(listOf("git add"), s.covers("git add Foo.kt"))
        assertNull(s.covers("git push"))
    }

    @Test fun `clear empties the store`() {
        val s = SessionCommandAllowlist()
        s.approve("ls")
        s.clear()
        assertNull(s.covers("ls -la"))
    }
}
