package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentIndex
import com.workflow.orchestrator.core.model.DocumentSlice
import com.workflow.orchestrator.document.service.DocumentArtifactStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class SessionDocumentArtifactServiceTest {

    @TempDir lateinit var cacheRoot: Path

    private fun fakeArtifact(dir: Path): DocumentArtifact {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("content.md"), "X".repeat(500))
        val meta = DocumentArtifactMeta("hash", "application/pdf", 500, 5, 0L)
        return DocumentArtifact(meta, dir.resolve("content.md"), dir.resolve("index.json"))
    }

    @Test
    fun `cold read then warm read extracts exactly once`() = runBlocking {
        val src = Files.createTempFile("doc", ".pdf").also { Files.writeString(it, "bytes") }
        val extractCount = AtomicInteger(0)
        val store = mockk<DocumentArtifactStore>(relaxed = true)
        coEvery { store.hashFile(src) } returns "hash"
        coEvery { store.loadArtifact(any()) } returns null andThen fakeArtifact(cacheRoot.resolve("hash"))
        coEvery { store.loadFailureIfFresh(any(), any(), any()) } returns null
        coEvery { store.extractAndPersist(any(), any(), any(), any()) } answers {
            extractCount.incrementAndGet(); fakeArtifact(cacheRoot.resolve("hash"))
        }
        coEvery { store.loadIndex(any()) } returns DocumentIndex(listOf(DocumentIndex.Anchor("1", 0)), emptyList())
        coEvery { store.slice(any(), any(), any(), any()) } answers {
            DocumentSlice("X".repeat(100), 0, 100, 400, 1, 5)
        }

        val svc = SessionDocumentArtifactService(
            store = store,
            cs = CoroutineScope(SupervisorJob()),
            cacheDirProvider = { cacheRoot },
            servingBudgetMs = 5_000,
            jobBudgetMs = 60_000,
        )

        val r1 = svc.read(src, DocumentCursor.Offset(0), 100)
        assertFalse(r1.isError)
        val r2 = svc.read(src, DocumentCursor.Offset(0), 100)
        assertFalse(r2.isError)
        assertEquals(1, extractCount.get())
    }

    @Test
    fun `slow extraction returns non-error in-progress within serving budget`() = runBlocking {
        val src = Files.createTempFile("doc", ".pdf").also { Files.writeString(it, "bytes") }
        val gate = CompletableDeferred<Unit>()
        val store = mockk<DocumentArtifactStore>(relaxed = true)
        coEvery { store.hashFile(src) } returns "hash"
        coEvery { store.loadArtifact(any()) } returns null
        coEvery { store.loadFailureIfFresh(any(), any(), any()) } returns null
        coEvery { store.extractAndPersist(any(), any(), any(), any()) } coAnswers {
            gate.await(); fakeArtifact(cacheRoot.resolve("hash"))
        }

        val svc = SessionDocumentArtifactService(
            store = store,
            cs = CoroutineScope(SupervisorJob()),
            cacheDirProvider = { cacheRoot },
            servingBudgetMs = 50,
            jobBudgetMs = 60_000,
        )

        val r = svc.read(src, DocumentCursor.Offset(0), 100)
        assertFalse(r.isError)
        assertTrue(r.summary.contains("in progress", ignoreCase = true))
        gate.complete(Unit)
    }

    @Test
    fun `cached failure short-circuits without extracting`() = runBlocking {
        val src = Files.createTempFile("doc", ".pdf").also { Files.writeString(it, "bytes") }
        val store = mockk<DocumentArtifactStore>(relaxed = true)
        coEvery { store.hashFile(src) } returns "hash"
        coEvery { store.loadArtifact(any()) } returns null
        coEvery { store.loadFailureIfFresh(any(), any(), any()) } returns "encrypted PDF"

        val svc = SessionDocumentArtifactService(
            store = store,
            cs = CoroutineScope(SupervisorJob()),
            cacheDirProvider = { cacheRoot },
            servingBudgetMs = 5_000,
            jobBudgetMs = 60_000,
        )

        val r = svc.read(src, DocumentCursor.Offset(0), 100)
        assertTrue(r.isError)
        assertTrue(r.summary.contains("encrypted", ignoreCase = true))
    }
}
