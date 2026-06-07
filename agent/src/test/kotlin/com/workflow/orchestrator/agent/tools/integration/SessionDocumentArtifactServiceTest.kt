package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentIndex
import com.workflow.orchestrator.core.model.DocumentSlice
import com.workflow.orchestrator.document.service.DocumentArtifactStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDocumentArtifactServiceTest {

    @TempDir lateinit var cacheRoot: Path

    private fun fakeArtifact(dir: Path): DocumentArtifact {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("content.md"), "X".repeat(500))
        val meta = DocumentArtifactMeta("hash", "application/pdf", 500, 5, 0L)
        return DocumentArtifact(meta, dir.resolve("content.md"), dir.resolve("index.json"))
    }

    @Test
    fun `cold read then warm read extracts exactly once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val src = Files.createTempFile("doc", ".pdf").also { Files.writeString(it, "bytes") }
        val extractCount = AtomicInteger(0)
        val store = mockk<DocumentArtifactStore>(relaxed = true)
        coEvery { store.hashFile(src) } returns "hash"
        coEvery { store.loadArtifact(any()) } returns null andThen fakeArtifact(cacheRoot.resolve("hash"))
        coEvery { store.loadFailureIfFresh(any(), any(), any()) } returns null
        coEvery { store.extractAndPersist(any(), any(), any(), any(), any()) } answers {
            extractCount.incrementAndGet(); fakeArtifact(cacheRoot.resolve("hash"))
        }
        coEvery { store.loadIndex(any()) } returns DocumentIndex(listOf(DocumentIndex.Anchor("1", 0)), emptyList())
        coEvery { store.slice(any(), any(), any(), any()) } answers {
            DocumentSlice("X".repeat(100), 0, 100, 400, 1, 5)
        }

        val svc = SessionDocumentArtifactService(
            store = store,
            cs = CoroutineScope(SupervisorJob() + dispatcher),
            cacheDirProvider = { cacheRoot },
            jobBudgetMs = 60_000,
            ioDispatcher = dispatcher,
        )

        val r1 = svc.read(src, DocumentCursor.Offset(0), 100)
        assertFalse(r1.isError)
        val r2 = svc.read(src, DocumentCursor.Offset(0), 100)
        assertFalse(r2.isError)
        assertEquals(1, extractCount.get())
    }

    @Test
    fun `read blocks until extraction completes then returns content`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val src = Files.createTempFile("doc", ".pdf").also { Files.writeString(it, "bytes") }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = io.mockk.mockk<com.workflow.orchestrator.document.service.DocumentArtifactStore>(relaxed = true)
        io.mockk.coEvery { store.hashFile(src) } returns "hash"
        io.mockk.coEvery { store.loadArtifact(any()) } returns null
        io.mockk.coEvery { store.loadFailureIfFresh(any(), any(), any()) } returns null
        io.mockk.coEvery { store.extractAndPersist(any(), any(), any(), any(), any()) } coAnswers {
            gate.await(); fakeArtifact(cacheRoot.resolve("hash"))
        }
        io.mockk.coEvery { store.loadIndex(any()) } returns com.workflow.orchestrator.core.model.DocumentIndex(
            listOf(com.workflow.orchestrator.core.model.DocumentIndex.Anchor("1", 0)), emptyList())
        io.mockk.coEvery { store.slice(any(), any(), any(), any()) } answers {
            com.workflow.orchestrator.core.model.DocumentSlice("X".repeat(100), 0, 100, 400, 1, 5)
        }
        val svc = SessionDocumentArtifactService(
            store = store,
            cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher),
            cacheDirProvider = { cacheRoot },
            jobBudgetMs = 60_000,
            ioDispatcher = dispatcher,
        )
        val readJob = async { svc.read(src, com.workflow.orchestrator.core.model.DocumentCursor.Offset(0), 100) }
        gate.complete(Unit)
        val r = readJob.await()
        assertFalse(r.isError)
        assertTrue(r.data!!.content.isNotEmpty())
    }

    @Test
    fun `extraction job inherits the SessionDownloadDir context from the caller`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val src = java.nio.file.Files.createTempFile("doc", ".pdf").also { java.nio.file.Files.writeString(it, "bytes") }
        val installedDir = cacheRoot.resolve("downloads")
        val observed = java.util.concurrent.atomic.AtomicReference<java.nio.file.Path?>(null)
        val seen = kotlinx.coroutines.CompletableDeferred<Unit>()

        val store = io.mockk.mockk<com.workflow.orchestrator.document.service.DocumentArtifactStore>(relaxed = true)
        io.mockk.coEvery { store.hashFile(src) } returns "hash"
        io.mockk.coEvery { store.loadArtifact(any()) } returns null
        io.mockk.coEvery { store.loadFailureIfFresh(any(), any(), any()) } returns null
        io.mockk.coEvery { store.extractAndPersist(any(), any(), any(), any(), any()) } coAnswers {
            observed.set(com.workflow.orchestrator.core.services.SessionDownloadDir.current())
            seen.complete(Unit)
            fakeArtifact(cacheRoot.resolve("hash"))
        }
        io.mockk.coEvery { store.loadIndex(any()) } returns com.workflow.orchestrator.core.model.DocumentIndex(
            listOf(com.workflow.orchestrator.core.model.DocumentIndex.Anchor("1", 0)), emptyList())
        io.mockk.coEvery { store.slice(any(), any(), any(), any()) } answers {
            com.workflow.orchestrator.core.model.DocumentSlice("X", 0, 1, 0, 1, 1)
        }

        val svc = SessionDocumentArtifactService(
            store = store,
            cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher),
            cacheDirProvider = { cacheRoot },
            jobBudgetMs = 60_000,
            ioDispatcher = dispatcher,
        )

        kotlinx.coroutines.withContext(com.workflow.orchestrator.core.services.SessionDownloadDir(installedDir)) {
            svc.read(src, com.workflow.orchestrator.core.model.DocumentCursor.Offset(0), 10)
        }
        seen.await()
        assertEquals(installedDir, observed.get())
    }

    @Test
    fun `extraction job is given a SessionDownloadDir derived from the cache dir when the caller has none`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Regression for the "embedded images land in java.io.tmpdir, view_image rejects them" bug.
        // When read() runs in a context WITHOUT a SessionDownloadDir (sub-agent, resume, or an
        // in-flight job first triggered outside the attachment scope), the extraction job must
        // still observe a download dir under the session tree — derived from the cache dir —
        // so ImageExtractionService never falls back to the unreadable system temp dir.
        val src = java.nio.file.Files.createTempFile("doc", ".pdf").also { java.nio.file.Files.writeString(it, "bytes") }
        val observed = java.util.concurrent.atomic.AtomicReference<java.nio.file.Path?>(null)
        val seen = kotlinx.coroutines.CompletableDeferred<Unit>()

        val store = mockk<DocumentArtifactStore>(relaxed = true)
        coEvery { store.hashFile(src) } returns "hash"
        coEvery { store.loadArtifact(any()) } returns null
        coEvery { store.loadFailureIfFresh(any(), any(), any()) } returns null
        coEvery { store.extractAndPersist(any(), any(), any(), any(), any()) } coAnswers {
            observed.set(com.workflow.orchestrator.core.services.SessionDownloadDir.current())
            seen.complete(Unit)
            fakeArtifact(cacheRoot.resolve("hash"))
        }
        coEvery { store.loadIndex(any()) } returns DocumentIndex(listOf(DocumentIndex.Anchor("1", 0)), emptyList())
        coEvery { store.slice(any(), any(), any(), any()) } answers { DocumentSlice("X", 0, 1, 0, 1, 1) }

        val svc = SessionDocumentArtifactService(
            store = store,
            cs = CoroutineScope(SupervisorJob() + dispatcher),
            cacheDirProvider = { cacheRoot },
            jobBudgetMs = 60_000,
            ioDispatcher = dispatcher,
        )

        // No SessionDownloadDir installed in this coroutine context.
        svc.read(src, DocumentCursor.Offset(0), 10)
        seen.await()

        // cacheRoot is {sessionDir}/document-cache; downloads is its sibling {sessionDir}/downloads.
        assertEquals(cacheRoot.parent.resolve("downloads"), observed.get())
    }

    @Test
    fun `a vanished file reports that it no longer exists with recovery guidance`() = runBlocking {
        // A document under java.io.tmpdir that the OS reaped mid-session previously surfaced a
        // bare "Cannot read '...'" — indistinguishable from a permissions error. The message
        // must name the specific cause (gone) and tell the LLM how to recover.
        val src = Files.createTempFile("doc", ".pdf").also { Files.writeString(it, "bytes") }
        val store = mockk<DocumentArtifactStore>(relaxed = true)
        coEvery { store.hashFile(src) } throws java.nio.file.NoSuchFileException(src.toString())

        val svc = SessionDocumentArtifactService(
            store = store,
            cs = CoroutineScope(SupervisorJob()),
            cacheDirProvider = { cacheRoot },
            jobBudgetMs = 60_000,
        )

        val r = svc.read(src, DocumentCursor.Offset(0), 100)
        assertTrue(r.isError)
        assertTrue(r.summary.contains("no longer exists", ignoreCase = true), "Names the gone-file cause: ${r.summary}")
        assertTrue(r.summary.contains("re-download", ignoreCase = true), "Offers recovery guidance: ${r.summary}")
    }

    @Test
    fun `serve propagates the section-miss signal and available sections from the slice`() = runBlocking {
        // The service is a thin pass-through: the explicit-miss flag and the available-section
        // list computed by the store must reach the tool unchanged (DocumentTool renders them).
        val src = Files.createTempFile("doc", ".pdf").also { Files.writeString(it, "bytes") }
        val store = mockk<DocumentArtifactStore>(relaxed = true)
        coEvery { store.hashFile(src) } returns "hash"
        coEvery { store.loadArtifact(any()) } returns fakeArtifact(cacheRoot.resolve("hash"))
        coEvery { store.loadIndex(any()) } returns DocumentIndex(emptyList(), emptyList())
        coEvery { store.slice(any(), any(), any(), any()) } answers {
            DocumentSlice(
                content = "TOP", startOffset = 0, endOffset = 3, remaining = 0,
                pageOfStart = 1, totalPages = 2,
                availableSections = listOf("Intro", "Body"), sectionMatched = false,
            )
        }
        val svc = SessionDocumentArtifactService(
            store = store,
            cs = CoroutineScope(SupervisorJob()),
            cacheDirProvider = { cacheRoot },
            jobBudgetMs = 60_000,
        )
        val r = svc.read(src, DocumentCursor.Section("missing"), 100)
        assertFalse(r.isError)
        assertEquals(false, r.data!!.sectionMatched)
        assertEquals(listOf("Intro", "Body"), r.data!!.availableSections)
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
            jobBudgetMs = 60_000,
        )

        val r = svc.read(src, DocumentCursor.Offset(0), 100)
        assertTrue(r.isError)
        assertTrue(r.summary.contains("encrypted", ignoreCase = true))
    }
}
