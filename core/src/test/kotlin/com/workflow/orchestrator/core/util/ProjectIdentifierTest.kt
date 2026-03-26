package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectIdentifierTest {

    @Test
    fun `compute returns dirName-hash format`() {
        val id = ProjectIdentifier.compute("/Users/dev/Projects/MyPlugin")
        assertTrue(id.startsWith("MyPlugin-"), "Should start with dir name: $id")
        assertEquals(6, id.substringAfter("MyPlugin-").length, "Hash should be 6 hex chars")
    }

    @Test
    fun `compute is stable for same path`() {
        val a = ProjectIdentifier.compute("/some/path/Project")
        val b = ProjectIdentifier.compute("/some/path/Project")
        assertEquals(a, b)
    }

    @Test
    fun `compute differs for different paths with same dir name`() {
        val a = ProjectIdentifier.compute("/path1/Project")
        val b = ProjectIdentifier.compute("/path2/Project")
        assertNotEquals(a, b)
    }

    @Test
    fun `rootDir returns home-based path`() {
        val root = ProjectIdentifier.rootDir("/Users/dev/MyPlugin")
        assertTrue(root.absolutePath.contains(".workflow-orchestrator"))
        assertTrue(root.absolutePath.contains("MyPlugin-"))
    }

    @Test
    fun `sessionsDir returns agent sessions subdirectory`() {
        val sessions = ProjectIdentifier.sessionsDir("/Users/dev/MyPlugin")
        assertTrue(sessions.absolutePath.endsWith("agent/sessions"))
    }
}
