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
}
