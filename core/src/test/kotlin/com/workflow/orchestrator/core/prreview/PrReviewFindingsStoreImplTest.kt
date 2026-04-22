package com.workflow.orchestrator.core.prreview

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PrReviewFindingsStoreImplTest {

    private fun mkStore(tempDir: Path): PrReviewFindingsStoreImpl {
        System.setProperty("user.home", tempDir.toString())
        val project = mockk<Project>()
        every { project.name } returns "test-project"
        every { project.basePath } returns tempDir.toString()
        return PrReviewFindingsStoreImpl(project)
    }

    private fun sample(
        id: String = "",
        prId: String = "PROJ/repo/PR-1",
        sessionId: String = "s1",
    ): PrReviewFinding = PrReviewFinding(
        id = id,
        prId = prId,
        sessionId = sessionId,
        severity = FindingSeverity.NORMAL,
        message = "sample",
        createdAt = 1000,
    )

    @Test
    fun `add persists finding and generates id when blank`(@TempDir tempDir: Path) = runBlocking {
        val store = mkStore(tempDir)
        val result = store.add(sample())
        assertFalse(result.isError, "add should succeed")
        assertTrue(result.data.id.isNotBlank(), "id should be generated")
        assertEquals("sample", result.data.message)
    }

    @Test
    fun `list returns all added findings`(@TempDir tempDir: Path) = runBlocking {
        val store = mkStore(tempDir)
        store.add(sample())
        store.add(sample())
        val result = store.list("PROJ/repo/PR-1", "s1")
        assertFalse(result.isError)
        assertEquals(2, result.data.size)
    }

    @Test
    fun `discard marks finding as discarded`(@TempDir tempDir: Path) = runBlocking {
        val store = mkStore(tempDir)
        val added = store.add(sample())
        val id = added.data.id
        val discardResult = store.discard(id)
        assertFalse(discardResult.isError)
        val listed = store.list("PROJ/repo/PR-1", "s1")
        assertTrue(listed.data.first { it.id == id }.discarded)
    }

    @Test
    fun `archiveSession flags all findings archived and hides them by default`(@TempDir tempDir: Path) = runBlocking {
        val store = mkStore(tempDir)
        store.add(sample())
        store.add(sample())
        val archiveResult = store.archiveSession("PROJ/repo/PR-1", "s1")
        assertFalse(archiveResult.isError)
        val withoutArchived = store.list("PROJ/repo/PR-1", "s1", includeArchived = false)
        assertEquals(0, withoutArchived.data.size, "Archived findings should be hidden by default")
        val withArchived = store.list("PROJ/repo/PR-1", "s1", includeArchived = true)
        assertEquals(2, withArchived.data.size, "All findings should appear with includeArchived=true")
        assertTrue(withArchived.data.all { it.archived }, "All findings should have archived=true")
    }

    @Test
    fun `markPushed records bitbucket comment id and pushedAt`(@TempDir tempDir: Path) = runBlocking {
        val store = mkStore(tempDir)
        val added = store.add(sample())
        val id = added.data.id
        val pushResult = store.markPushed(id, "bb-123", 2000L)
        assertFalse(pushResult.isError)
        val listed = store.list("PROJ/repo/PR-1", "s1")
        val finding = listed.data.first { it.id == id }
        assertTrue(finding.pushed)
        assertEquals("bb-123", finding.pushedCommentId)
        assertEquals(2000L, finding.pushedAt)
    }

    @Test
    fun `concurrent adds preserve all findings via Mutex`(@TempDir tempDir: Path) = runBlocking {
        val store = mkStore(tempDir)
        val jobs = (1..20).map {
            async { store.add(sample()) }
        }
        jobs.awaitAll()
        val result = store.list("PROJ/repo/PR-1", "s1")
        assertFalse(result.isError)
        assertEquals(20, result.data.size, "All 20 concurrent adds should be persisted")
    }
}
