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
 * P2-19 (2026-06-10 perf audit): slice()/search() must not re-materialize the entire content.md
 * per call. slice() now does a RANGED read; search() memoizes the full text via a SoftReference.
 *
 * The index stores CHAR (UTF-16 code-unit) offsets — `contentLength = markdown.length` and anchors
 * are used directly against the in-memory String — while content.md is persisted as UTF-8. A naive
 * BYTE-ranged read would therefore corrupt any document containing multi-byte characters. These
 * tests pin char-offset semantics across 2-byte (é), 3-byte (€/—) and 4-byte surrogate-pair (😀)
 * code points, plus the search-path memoization.
 *
 * Run: ./gradlew :document:test --tests "*DocumentArtifactStoreRangedReadTest*"
 */
class DocumentArtifactStoreRangedReadTest {

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
    fun `slice by char offset after a multi-byte UTF-8 prefix returns the exact substring`() = runTest {
        // Prefix mixes 2-byte (é, ü), 3-byte (—, €) and 4-byte surrogate-pair (😀, 🚀) code points,
        // so char offsets and byte offsets diverge sharply before the window of interest.
        val prefix = "## Überblick — naïve café 😀🚀 costs €5\n"
        val body = "SECTION-BODY " + "x".repeat(50)
        val tail = " trailing-😀-tail"
        val md = prefix + body + tail
        val bodyOffset = md.indexOf("SECTION-BODY")
        val index = DocumentIndex(
            pages = listOf(DocumentIndex.Anchor("1", 0), DocumentIndex.Anchor("2", bodyOffset)),
            sections = listOf(DocumentIndex.Anchor("Überblick", 0), DocumentIndex.Anchor("Body", bodyOffset)),
        )
        val art = materialize(md, index)

        val slice = store.slice(art, index, DocumentCursor.Offset(bodyOffset), maxChars = body.length)
        assertEquals(body, slice.content, "char-offset window must not be shifted/corrupted by multi-byte prefix")
        assertEquals(bodyOffset, slice.startOffset)
        assertEquals(bodyOffset + body.length, slice.endOffset)
        assertEquals(md.length - (bodyOffset + body.length), slice.remaining)
        assertEquals(2, slice.pageOfStart)
    }

    @Test
    fun `slice window containing surrogate pairs round-trips them intact`() = runTest {
        val md = "intro 😀 " + "padding ".repeat(10) + "EMOJI-ZONE 😀🚀😀 END"
        val zone = md.indexOf("EMOJI-ZONE")
        val expected = md.substring(zone, md.length)
        val index = DocumentIndex(pages = emptyList(), sections = emptyList())
        val art = materialize(md, index)

        val slice = store.slice(art, index, DocumentCursor.Offset(zone), maxChars = expected.length + 100)
        assertEquals(expected, slice.content)
        assertEquals(0, slice.remaining)
    }

    @Test
    fun `search offsets over multi-byte content navigate back to the hit via slice`() = runTest {
        val md = "Préambule — überall 😀 blah blah.\nThe Frobnicator threshold is 42.\nFin €."
        val matchPos = md.indexOf("Frobnicator")
        val index = DocumentIndex(
            pages = listOf(DocumentIndex.Anchor("1", 0)),
            sections = listOf(DocumentIndex.Anchor("Préambule", 0)),
        )
        val art = materialize(md, index)

        val res = store.search(art, index, "Frobnicator")
        assertEquals(1, res.totalHits)
        val hit = res.matches.first()
        assertEquals(matchPos, hit.offset, "search offset must be a char offset, not a byte offset")

        // The round trip: slicing at the reported offset must land exactly on the matched term.
        val slice = store.slice(art, index, DocumentCursor.Offset(hit.offset), maxChars = "Frobnicator".length)
        assertEquals("Frobnicator", slice.content)
    }

    @Test
    fun `repeat search serves the memoized content instead of re-reading the file`() = runTest {
        val md = "alpha beta gamma Frobnicator delta " + "filler ".repeat(20)
        val index = DocumentIndex(pages = emptyList(), sections = emptyList())
        val art = materialize(md, index)

        val first = store.search(art, index, "Frobnicator")
        assertEquals(1, first.totalHits)

        // Same artifact handle (same path + contentHash): a second search must be served from the
        // soft-ref memo, NOT from disk — overwriting the file on disk must not change the result.
        Files.writeString(art.contentPath, "completely different content, no match term here")
        val second = store.search(art, index, "Frobnicator")
        assertEquals(1, second.totalHits, "second search must hit the in-memory memo, not re-read disk")
        assertTrue(second.matches.first().snippet.contains("Frobnicator"))
    }
}
