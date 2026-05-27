package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentExtractionProgress
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class DocumentArtifactStoreProgressTest {

    @TempDir lateinit var work: Path
    private val store = DocumentArtifactStore(TikaDocumentExtractor())

    @Test
    fun `extractAndPersist reports per-page progress for a PDF`() = runBlocking {
        val pdf = LargePdfFixtureFactory.create(work.resolve("p.pdf"), pages = 30)
        val hash = store.hashFile(pdf)
        val events = CopyOnWriteArrayList<DocumentExtractionProgress>()

        store.extractAndPersist(pdf, work.resolve(hash), hash, onProgress = { events.add(it) })

        assertTrue(events.isNotEmpty(), "expected progress events")
        val paged = events.filter { it.pagesTotal != null }
        assertTrue(paged.isNotEmpty(), "expected page-total-bearing events")
        assertEquals(30, paged.maxOf { it.pagesTotal!! }, "pagesTotal should equal the PDF page count")
        assertTrue(paged.maxOf { it.pagesDone } >= 1, "pagesDone should advance")
        assertTrue(events.all { it.elapsedMs >= 0 })
    }

    @Test
    fun `extractAndPersist still works with no progress callback`() = runBlocking {
        val pdf = LargePdfFixtureFactory.create(work.resolve("q.pdf"), pages = 12)
        val hash = store.hashFile(pdf)
        val artifact = store.extractAndPersist(pdf, work.resolve(hash), hash) // no onProgress
        assertEquals(12, artifact.meta.pageCount)
    }
}
