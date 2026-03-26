package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SessionCheckpointTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `save and load round-trips all fields`() {
        val checkpoint = SessionCheckpoint(
            sessionId = "test-session-123",
            phase = "executing",
            iteration = 15,
            tokensUsed = 45000,
            lastToolCall = "edit_file",
            touchedFiles = listOf("/src/Main.kt"),
            rollbackCheckpointId = "rollback-abc",
            editedFiles = listOf("/src/Main.kt", "/src/App.kt"),
            persistedMessageCount = 42,
            hasPlan = true,
            lastActivity = "Iteration 15: edit_file, diagnostics"
        )

        SessionCheckpoint.save(checkpoint, tempDir)
        val loaded = SessionCheckpoint.load(tempDir)

        assertNotNull(loaded)
        assertEquals("test-session-123", loaded!!.sessionId)
        assertEquals("executing", loaded.phase)
        assertEquals(15, loaded.iteration)
        assertEquals(45000, loaded.tokensUsed)
        assertEquals("edit_file", loaded.lastToolCall)
        assertEquals(listOf("/src/Main.kt", "/src/App.kt"), loaded.editedFiles)
        assertEquals(42, loaded.persistedMessageCount)
        assertTrue(loaded.hasPlan)
        assertEquals("Iteration 15: edit_file, diagnostics", loaded.lastActivity)
        assertEquals("rollback-abc", loaded.rollbackCheckpointId)
    }

    @Test
    fun `load returns null for missing checkpoint`() {
        assertNull(SessionCheckpoint.load(tempDir))
    }

    @Test
    fun `delete removes checkpoint file`() {
        val checkpoint = SessionCheckpoint(sessionId = "s1", phase = "executing")
        SessionCheckpoint.save(checkpoint, tempDir)
        assertTrue(File(tempDir, "checkpoint.json").exists())

        SessionCheckpoint.delete(tempDir)
        assertFalse(File(tempDir, "checkpoint.json").exists())
    }

    @Test
    fun `load handles corrupt file gracefully`() {
        File(tempDir, "checkpoint.json").writeText("not valid json {{{")
        assertNull(SessionCheckpoint.load(tempDir))
    }

    @Test
    fun `new fields default correctly when loading old format`() {
        // Simulate an old checkpoint without new fields
        val oldJson = """
            {
                "sessionId": "old-session",
                "phase": "executing",
                "iteration": 5,
                "tokensUsed": 10000,
                "timestamp": 1234567890
            }
        """.trimIndent()
        File(tempDir, "checkpoint.json").writeText(oldJson)

        val loaded = SessionCheckpoint.load(tempDir)
        assertNotNull(loaded)
        assertEquals("old-session", loaded!!.sessionId)
        assertEquals(5, loaded.iteration)
        // New fields should default
        assertEquals(emptyList<String>(), loaded.editedFiles)
        assertEquals(0, loaded.persistedMessageCount)
        assertFalse(loaded.hasPlan)
        assertNull(loaded.lastActivity)
    }

    @Test
    fun `save overwrites previous checkpoint`() {
        val first = SessionCheckpoint(sessionId = "s1", phase = "executing", iteration = 1)
        SessionCheckpoint.save(first, tempDir)

        val second = SessionCheckpoint(sessionId = "s1", phase = "executing", iteration = 10)
        SessionCheckpoint.save(second, tempDir)

        val loaded = SessionCheckpoint.load(tempDir)
        assertEquals(10, loaded!!.iteration)
    }

    @Test
    fun `save creates directory if missing`() {
        val nested = File(tempDir, "deep/nested/dir")
        assertFalse(nested.exists())

        val checkpoint = SessionCheckpoint(sessionId = "s1", phase = "executing")
        SessionCheckpoint.save(checkpoint, nested)

        assertTrue(nested.exists())
        assertNotNull(SessionCheckpoint.load(nested))
    }
}
