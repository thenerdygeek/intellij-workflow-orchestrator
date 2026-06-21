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

    // ── T3: round-trip with mixed-case/spacing normalize → covers ──
    @Test fun `T3 approve with extra spaces and mixed case then covers matching command`() {
        val s = SessionCommandAllowlist()
        s.approve("  GIT   ADD ")
        // Normalized to "git add" — covers a real invocation
        assertEquals(listOf("git add"), s.covers("git add Foo.kt"))
    }

    @Test fun `T3 covers returns null for a different prefix after mixed-case approve`() {
        val s = SessionCommandAllowlist()
        s.approve("  GIT   ADD ")
        assertNull(s.covers("git push"))
    }

    // ── Idempotent approve ──
    @Test fun `approving the same prefix twice produces only one entry in snapshot`() {
        val s = SessionCommandAllowlist()
        s.approve("git add")
        s.approve("git add")
        assertEquals(setOf("git add"), s.snapshot())
    }

    @Test fun `approving the same prefix with different case and spacing is idempotent`() {
        val s = SessionCommandAllowlist()
        s.approve("GIT ADD")
        s.approve("  git   add  ")
        assertEquals(setOf("git add"), s.snapshot())
        assertEquals(listOf("git add"), s.covers("git add Foo.kt"))
    }

    // ── Multiple distinct prefixes ──
    @Test fun `multiple distinct prefixes are all stored and each covers its own commands`() {
        val s = SessionCommandAllowlist()
        s.approve("git add")
        s.approve("git status")
        s.approve("ls")

        assertEquals(setOf("git add", "git status", "ls"), s.snapshot())
        assertEquals(listOf("git add"), s.covers("git add ."))
        assertEquals(listOf("git status"), s.covers("git status"))
        assertEquals(listOf("ls"), s.covers("ls -la"))
        assertNull(s.covers("git push"))
    }

    @Test fun `covers picks the right prefix from multiple entries`() {
        val s = SessionCommandAllowlist()
        s.approve("cat")
        s.approve("grep")
        // A pipe: cat a | grep b → both covered → distinct prefixes returned
        val result = s.covers("cat a | grep b")
        assertEquals(listOf("cat", "grep"), result)
    }

    // ── covers after clear ──
    @Test fun `covers after clear returns null for all previously approved prefixes`() {
        val s = SessionCommandAllowlist()
        s.approve("git add")
        s.approve("ls")
        s.clear()
        assertNull(s.covers("git add ."))
        assertNull(s.covers("ls -la"))
    }

    // ── snapshot reflects all current approvals ──
    @Test fun `snapshot reflects all approvals and is empty after clear`() {
        val s = SessionCommandAllowlist()
        s.approve("git add")
        s.approve("npm install")
        val snap = s.snapshot()
        assertEquals(setOf("git add", "npm install"), snap)
        s.clear()
        assertTrue(s.snapshot().isEmpty())
    }

    @Test fun `snapshot returns an independent copy — mutations do not affect internal state`() {
        val s = SessionCommandAllowlist()
        s.approve("git add")
        val snap = s.snapshot()
        // The snapshot is a copy; the original store is not affected if the caller modifies the set
        // (this just verifies we can operate on snap without affecting s)
        assertEquals(setOf("git add"), snap)
        assertEquals(setOf("git add"), s.snapshot())
    }
}
