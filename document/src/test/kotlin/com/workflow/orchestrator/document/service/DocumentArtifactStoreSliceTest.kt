package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentIndex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocumentArtifactStoreSliceTest {

    @TempDir lateinit var dir: Path
    private val store = DocumentArtifactStore(TikaDocumentExtractor())
    private val json = Json { prettyPrint = false }

    private fun materialize(markdown: String, index: DocumentIndex): DocumentArtifact {
        Files.writeString(dir.resolve("content.md"), markdown)
        Files.writeString(dir.resolve("index.json"), json.encodeToString(DocumentIndex.serializer(), index))
        val meta = DocumentArtifactMeta("h", "text/plain", markdown.length, 3, 0L)
        Files.writeString(dir.resolve("meta.json"), json.encodeToString(DocumentArtifactMeta.serializer(), meta))
        return DocumentArtifact(meta, dir.resolve("content.md"), dir.resolve("index.json"))
    }

    @Test
    fun `slice by offset returns the window and computes remaining + pageOfStart`() = runTest {
        val md = "A".repeat(100) + "B".repeat(100) + "C".repeat(100) // 300 chars
        val index = DocumentIndex(
            pages = listOf(DocumentIndex.Anchor("1", 0), DocumentIndex.Anchor("2", 100), DocumentIndex.Anchor("3", 200)),
            sections = emptyList(),
        )
        val art = materialize(md, index)

        val slice = store.slice(art, index, DocumentCursor.Offset(100), maxChars = 50)
        assertEquals("B".repeat(50), slice.content)
        assertEquals(100, slice.startOffset)
        assertEquals(150, slice.endOffset)
        assertEquals(150, slice.remaining) // 300 - 150
        assertEquals(2, slice.pageOfStart)
        assertEquals(3, slice.totalPages)
    }

    @Test
    fun `cursor Page resolves via index to the page offset`() = runTest {
        val md = "A".repeat(100) + "B".repeat(100) + "C".repeat(100)
        val index = DocumentIndex(
            pages = listOf(DocumentIndex.Anchor("1", 0), DocumentIndex.Anchor("2", 100), DocumentIndex.Anchor("3", 200)),
            sections = emptyList(),
        )
        val art = materialize(md, index)
        val slice = store.slice(art, index, DocumentCursor.Page(3), maxChars = 10)
        assertEquals("C".repeat(10), slice.content)
        assertEquals(200, slice.startOffset)
    }

    @Test
    fun `offset at or beyond length yields empty content and zero remaining`() = runTest {
        val md = "hello"
        val index = DocumentIndex(emptyList(), emptyList())
        val art = materialize(md, index)
        val slice = store.slice(art, index, DocumentCursor.Offset(999), maxChars = 10)
        assertEquals("", slice.content)
        assertEquals(0, slice.remaining)
    }

    @Test
    fun `failure marker write+read round-trips and expiry is honored`() = runTest {
        store.writeFailure(dir, "encrypted PDF", nowEpochMs = 1_000_000L)
        assertEquals("encrypted PDF", store.loadFailureIfFresh(dir, nowEpochMs = 1_000_000L + 60_000L, ttlMs = 3_600_000L))
        assertEquals(null, store.loadFailureIfFresh(dir, nowEpochMs = 1_000_000L + 3_600_001L, ttlMs = 3_600_000L))
    }
}
