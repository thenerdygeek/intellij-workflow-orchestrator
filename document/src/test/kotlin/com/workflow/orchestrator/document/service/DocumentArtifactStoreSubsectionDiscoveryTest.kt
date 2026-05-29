package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentIndex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * TDD for the **discoverability** fix (root cause a).
 *
 * On a big spec the available-sections list was hard-capped at 30 — the agent only ever saw the
 * first 30 (top-level, early) sections, so deep subsections looked absent even when anchored. The
 * fix: (1) raise the cap substantially so deep subsections are surfaced, (2) report the true total
 * so truncation is explicit (never silent), and (3) on a `section=` MISS, bias the surfaced list
 * toward the query's number-prefix neighborhood (query `2.4.4.1` → surface the `2.4.x` family).
 */
class DocumentArtifactStoreSubsectionDiscoveryTest {

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
    fun `deep subsections beyond the old 30-cap are still discoverable`() = runTest {
        val md = "x".repeat(10)
        // 40 top-level sections then a deep subsection — under the OLD cap of 30 it was invisible.
        val sections = (1..40).map { DocumentIndex.Anchor("$it Top Level Section", 0) } +
            DocumentIndex.Anchor("2.4.4.1 Request Context", 5)
        val index = DocumentIndex(pages = emptyList(), sections = sections)
        val art = materialize(md, index)

        val slice = store.slice(art, index, DocumentCursor.Offset(0), maxChars = 5)
        assertTrue(
            slice.availableSections.any { it.startsWith("2.4.4.1") },
            "a deep subsection past the first 30 must be surfaced; got: ${slice.availableSections}",
        )
    }

    @Test
    fun `truncation of the section list is explicit — total count is reported`() = runTest {
        val md = "x".repeat(10)
        val total = DocumentArtifactStore.MAX_AVAILABLE_SECTIONS + 14
        val sections = (1..total).map { DocumentIndex.Anchor("Section $it", 0) }
        val index = DocumentIndex(pages = emptyList(), sections = sections)
        val art = materialize(md, index)

        val slice = store.slice(art, index, DocumentCursor.Offset(0), maxChars = 5)
        assertEquals(DocumentArtifactStore.MAX_AVAILABLE_SECTIONS, slice.availableSections.size)
        assertEquals(total, slice.totalSectionCount, "the FULL section count must be reported so truncation is never silent")
        assertTrue(
            slice.totalSectionCount > slice.availableSections.size,
            "the list is genuinely truncated here; got total=${slice.totalSectionCount}, shown=${slice.availableSections.size}",
        )
    }

    @Test
    fun `a section MISS biases the surfaced list toward the query's number-prefix neighborhood`() = runTest {
        val md = "x".repeat(10)
        // Many unrelated early sections to push the 2.4.x family past the cap window, then the family.
        val noise = (1..DocumentArtifactStore.MAX_AVAILABLE_SECTIONS).map {
            DocumentIndex.Anchor("$it Unrelated Section", 0)
        }
        val family = listOf(
            DocumentIndex.Anchor("2.4.4 Common Parameters", 1),
            DocumentIndex.Anchor("2.4.4.2 Response Context", 2),
            DocumentIndex.Anchor("2.4.4.3 Error Handling", 3),
        )
        val index = DocumentIndex(pages = emptyList(), sections = noise + family)
        val art = materialize(md, index)

        // Query a sibling that does NOT exist; the surfaced list should bias toward the 2.4.x family.
        val slice = store.slice(art, index, DocumentCursor.Section("2.4.4.1"), maxChars = 5)
        assertEquals(false, slice.sectionMatched)
        assertTrue(
            slice.availableSections.any { it.startsWith("2.4.4") },
            "a miss on '2.4.4.1' must surface its '2.4.x' neighborhood; got: ${slice.availableSections}",
        )
    }
}
