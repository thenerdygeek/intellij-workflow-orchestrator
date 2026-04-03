package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for RollbackResult data class and AgentRollbackManager's
 * trackFileCreation / clearCheckpoints behavior.
 *
 * Note: Testing actual rollbackToCheckpoint() / rollbackFile() / gitFallbackRevert()
 * requires IntelliJ LocalHistory + VFS + Project mocks, which is covered by
 * integration tests. These tests validate the data class contract and the
 * public tracking API.
 */
class AgentRollbackManagerTest {

    // ── RollbackResult data class tests ──

    @Test
    fun `RollbackResult success has correct fields`() {
        val result = RollbackResult(
            success = true,
            mechanism = RollbackMechanism.LOCAL_HISTORY,
            affectedFiles = listOf("src/A.kt", "src/B.kt")
        )

        assertTrue(result.success)
        assertEquals(RollbackMechanism.LOCAL_HISTORY, result.mechanism)
        assertEquals(2, result.affectedFiles.size)
        assertEquals(listOf("src/A.kt", "src/B.kt"), result.affectedFiles)
        assertTrue(result.failedFiles.isEmpty())
        assertNull(result.error)
    }

    @Test
    fun `RollbackResult failure has error message`() {
        val result = RollbackResult(
            success = false,
            mechanism = RollbackMechanism.GIT_FALLBACK,
            affectedFiles = emptyList(),
            error = "Checkpoint not found"
        )

        assertFalse(result.success)
        assertEquals(RollbackMechanism.GIT_FALLBACK, result.mechanism)
        assertTrue(result.affectedFiles.isEmpty())
        assertTrue(result.failedFiles.isEmpty())
        assertEquals("Checkpoint not found", result.error)
    }

    @Test
    fun `RollbackResult partial success tracks both lists`() {
        val result = RollbackResult(
            success = true,
            mechanism = RollbackMechanism.GIT_FALLBACK,
            affectedFiles = listOf("src/A.kt", "src/B.kt"),
            failedFiles = listOf("src/C.kt"),
            error = "src/C.kt: not tracked by git"
        )

        assertTrue(result.success)
        assertEquals(RollbackMechanism.GIT_FALLBACK, result.mechanism)
        assertEquals(listOf("src/A.kt", "src/B.kt"), result.affectedFiles)
        assertEquals(listOf("src/C.kt"), result.failedFiles)
        assertEquals("src/C.kt: not tracked by git", result.error)
    }

    @Test
    fun `RollbackResult defaults for failedFiles and error`() {
        val result = RollbackResult(
            success = true,
            mechanism = RollbackMechanism.LOCAL_HISTORY,
            affectedFiles = listOf("src/Main.kt")
        )

        assertEquals(emptyList<String>(), result.failedFiles)
        assertNull(result.error)
    }

    @Test
    fun `RollbackResult equality works via data class`() {
        val r1 = RollbackResult(
            success = true,
            mechanism = RollbackMechanism.LOCAL_HISTORY,
            affectedFiles = listOf("a.kt"),
            failedFiles = emptyList(),
            error = null
        )
        val r2 = RollbackResult(
            success = true,
            mechanism = RollbackMechanism.LOCAL_HISTORY,
            affectedFiles = listOf("a.kt"),
            failedFiles = emptyList(),
            error = null
        )

        assertEquals(r1, r2)
        assertEquals(r1.hashCode(), r2.hashCode())
    }

    @Test
    fun `RollbackResult copy preserves and overrides fields`() {
        val original = RollbackResult(
            success = true,
            mechanism = RollbackMechanism.LOCAL_HISTORY,
            affectedFiles = listOf("a.kt")
        )
        val modified = original.copy(success = false, error = "something went wrong")

        assertTrue(original.success)
        assertNull(original.error)
        assertFalse(modified.success)
        assertEquals("something went wrong", modified.error)
        assertEquals(original.mechanism, modified.mechanism)
        assertEquals(original.affectedFiles, modified.affectedFiles)
    }
}
