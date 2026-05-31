package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path

class PrReviewSessionRegistryTest {

    private fun mk(tempDir: Path): PrReviewSessionRegistry {
        System.setProperty("user.home", tempDir.toString())
        val project = mockk<Project>()
        every { project.name } returns "test-project"
        every { project.basePath } returns tempDir.toString()
        return PrReviewSessionRegistry(project)
    }

    @Test
    fun `register persists mapping and survives re-read`(@TempDir tempDir: Path) {
        val r1 = mk(tempDir)
        r1.register("PROJ/repo/PR-1", "session-abc", "finished")

        val r2 = mk(tempDir)
        val entry = r2.get("PROJ/repo/PR-1")
        assertNotNull(entry)
        assertEquals("session-abc", entry!!.sessionId)
        assertEquals("finished", entry.status)
    }

    @Test
    fun `get returns null for unknown prId`(@TempDir tempDir: Path) {
        val r = mk(tempDir)
        assertNull(r.get("PROJ/repo/PR-99"))
    }

    @Test
    fun `register overwrites prior session for same prId`(@TempDir tempDir: Path) {
        val r = mk(tempDir)
        r.register("PROJ/repo/PR-1", "old", "finished")
        r.register("PROJ/repo/PR-1", "new", "running")
        assertEquals("new", r.get("PROJ/repo/PR-1")?.sessionId)
        assertEquals("running", r.get("PROJ/repo/PR-1")?.status)
    }

    @Test
    fun `updateStatus preserves sessionId and updates status`(@TempDir tempDir: Path) {
        val r = mk(tempDir)
        r.register("PROJ/repo/PR-1", "abc", "running")
        r.updateStatus("PROJ/repo/PR-1", "finished")
        assertEquals("abc", r.get("PROJ/repo/PR-1")?.sessionId)
        assertEquals("finished", r.get("PROJ/repo/PR-1")?.status)
    }

    // ── PULLREQUEST-COV-3: updateStatus no-op on unknown id + all() coverage ──

    @Test
    fun `updateStatus on unknown prId is a no-op and leaves registry unchanged`(@TempDir tempDir: Path) {
        val r = mk(tempDir)
        // Call updateStatus before any register — must not throw and must leave registry empty
        r.updateStatus("PROJ/repo/PR-99", "finished")
        assertNull(r.get("PROJ/repo/PR-99"), "get on a never-registered prId must still return null after updateStatus")
    }

    @Test
    fun `all() returns empty map on fresh registry`(@TempDir tempDir: Path) {
        val r = mk(tempDir)
        val result = r.all()
        assertTrue(result.isEmpty(), "all() must return empty map when nothing has been registered")
    }

    @Test
    fun `all() returns all registered entries`(@TempDir tempDir: Path) {
        val r = mk(tempDir)
        r.register("PROJ/repo/PR-1", "session-a", "running")
        r.register("PROJ/repo/PR-2", "session-b", "finished")
        r.register("PROJ/repo/PR-3", "session-c", "failed")

        val result = r.all()

        assertEquals(3, result.size, "all() must return all 3 registered entries")
        assertEquals("session-a", result["PROJ/repo/PR-1"]?.sessionId)
        assertEquals("session-b", result["PROJ/repo/PR-2"]?.sessionId)
        assertEquals("session-c", result["PROJ/repo/PR-3"]?.sessionId)
    }

    // ── PULLREQUEST-COV-4: corrupt JSON resilience ────────────────────────────

    @Test
    fun `get returns null and does not throw when registry file is corrupt JSON`(@TempDir tempDir: Path) {
        val r = mk(tempDir)
        // Write corrupt JSON directly to where the registry file would live
        val home = tempDir.toString()
        val projectPath = tempDir.toString()
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(projectPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(6)
        val registryFile = java.nio.file.Path.of(
            home, ".workflow-orchestrator", "test-project-$hash", "agent", "pr-review-sessions.json"
        )
        java.nio.file.Files.createDirectories(registryFile.parent)
        java.nio.file.Files.writeString(registryFile, "{ not valid json !! }")

        // Must not throw; corrupt file is silently ignored via runCatching
        val entry = r.get("PROJ/repo/PR-1")
        assertNull(entry, "get() must return null when the file is corrupt JSON")
    }

    @Test
    fun `register after corrupt file creates a fresh single-entry file`(@TempDir tempDir: Path) {
        val r = mk(tempDir)
        // Write corrupt JSON first
        val home = tempDir.toString()
        val projectPath = tempDir.toString()
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(projectPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(6)
        val registryFile = java.nio.file.Path.of(
            home, ".workflow-orchestrator", "test-project-$hash", "agent", "pr-review-sessions.json"
        )
        java.nio.file.Files.createDirectories(registryFile.parent)
        java.nio.file.Files.writeString(registryFile, "{ not valid json !! }")

        // Register a new entry — it should recover from the corrupt state and write a valid file
        r.register("PROJ/repo/PR-1", "session-xyz", "running")

        val entry = r.get("PROJ/repo/PR-1")
        assertNotNull(entry, "register after corrupt file must produce a readable entry")
        assertEquals("session-xyz", entry!!.sessionId)
        assertEquals("running", entry.status)
    }
}
