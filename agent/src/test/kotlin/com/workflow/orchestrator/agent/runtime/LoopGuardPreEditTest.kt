package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LoopGuardPreEditTest {

    private lateinit var guard: LoopGuard

    @BeforeEach
    fun setup() {
        guard = LoopGuard()
    }

    @Test
    fun `edit blocked when file not read`() {
        val warning = guard.checkPreEditRead("/src/Main.kt")
        assertNotNull(warning)
        assertTrue(warning!!.contains("Edit blocked"))
        assertTrue(warning.contains("Main.kt"))
    }

    @Test
    fun `edit allowed after file read`() {
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        val warning = guard.checkPreEditRead("/src/Main.kt")
        assertNull(warning)
    }

    @Test
    fun `edit blocked after clearFileRead`() {
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        guard.clearFileRead("/src/Main.kt")
        val warning = guard.checkPreEditRead("/src/Main.kt")
        assertNotNull(warning)
    }

    @Test
    fun `edit blocked after clearAllFileReads`() {
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        guard.clearAllFileReads()
        val warning = guard.checkPreEditRead("/src/Main.kt")
        assertNotNull(warning)
    }

    @Test
    fun `different file not affected by reading another`() {
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        val warning = guard.checkPreEditRead("/src/Other.kt")
        assertNotNull(warning)
    }

    @Test
    fun `edit allowed after reset and re-read`() {
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        guard.reset()
        assertNotNull(guard.checkPreEditRead("/src/Main.kt"))
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        assertNull(guard.checkPreEditRead("/src/Main.kt"))
    }
}
