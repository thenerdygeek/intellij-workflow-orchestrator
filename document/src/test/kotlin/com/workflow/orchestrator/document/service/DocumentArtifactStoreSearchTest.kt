package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentIndex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Full-text search over the persisted content + index (G-10). Search is its own MODE: it returns
 * ranked matching snippets, each carrying offset/page/section so the LLM can navigate to read more.
 *
 * Semantics under test:
 *  - case-insensitive;
 *  - default = ALL query terms must appear (whitespace-split); single-token query = substring;
 *  - ranking: phrase (terms adjacent, in order) > all-terms (by span density) > document order;
 *  - cap top-N with a reported total-hit count so truncation is never silent;
 *  - snippet = ~context window trimmed to word boundaries, match delimited by «…»;
 *  - match → page via index.pageAt(offset); match → section via index.sectionAt(offset);
 *  - no-match returns empty matches + available sections (navigation guidance).
 *
 * Run: ./gradlew :document:test --tests "*DocumentArtifactStoreSearchTest*"
 */
class DocumentArtifactStoreSearchTest {

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
    fun `single term is case-insensitive and reports offset, page and section`() = runTest {
        // page1 @0..., section "Introduction" @0; page2 @120, section "Details" @120.
        val pre = "Intro text padding here. ".padEnd(120, '.')       // 120 chars, page 1
        val md = pre + "The widget Frobnicator is documented in DETAIL."
        val matchPos = md.indexOf("Frobnicator")
        val index = DocumentIndex(
            pages = listOf(DocumentIndex.Anchor("1", 0), DocumentIndex.Anchor("2", 120)),
            sections = listOf(DocumentIndex.Anchor("Introduction", 0), DocumentIndex.Anchor("Details", 120)),
        )
        val art = materialize(md, index)

        val res = store.search(art, index, "frobnicator", contextChars = 60, resultCap = 15)
        assertEquals(1, res.totalHits)
        assertEquals(1, res.matches.size)
        val m = res.matches.first()
        assertEquals(matchPos, m.offset, "offset must point at the match start")
        assertEquals(2, m.page, "match is past the page-2 anchor")
        assertEquals("Details", m.section, "nearest preceding section anchor")
        assertTrue(m.snippet.contains("Frobnicator"), "snippet must include the matched text; got: ${m.snippet}")
        assertTrue(m.snippet.contains("«") && m.snippet.contains("»"), "snippet must delimit the match; got: ${m.snippet}")
    }

    @Test
    fun `all-terms default requires every whitespace-split term present in the snippet window`() = runTest {
        // Two windows: one has both terms, one has only "alpha". Only the both-terms window matches.
        val both = "the alpha and the beta appear together right here in one place"
        val onlyAlpha = " ".repeat(300) + "alpha alone with no sibling term anywhere nearby"
        val md = both + onlyAlpha
        val index = DocumentIndex(pages = emptyList(), sections = emptyList())
        val art = materialize(md, index)

        val res = store.search(art, index, "alpha beta", contextChars = 40, resultCap = 15)
        assertEquals(1, res.totalHits, "only the window containing BOTH terms matches")
        assertTrue(res.matches.single().snippet.lowercase().contains("alpha"))
        assertTrue(res.matches.single().snippet.lowercase().contains("beta"))
    }

    @Test
    fun `phrase match outranks scattered all-terms match`() = runTest {
        // Doc order: scattered first, adjacent phrase second. Phrase must rank #1 despite later position.
        val scattered = "AAL2 is discussed, and later reauthentication is also discussed separately. "
        val phrase = "The policy mandates AAL2 reauthentication every twelve hours."
        val md = scattered + phrase
        val phrasePos = md.indexOf("AAL2 reauthentication")
        val index = DocumentIndex(pages = emptyList(), sections = emptyList())
        val art = materialize(md, index)

        val res = store.search(art, index, "AAL2 reauthentication", contextChars = 50, resultCap = 15)
        assertTrue(res.totalHits >= 2, "both the scattered window and the phrase window are hits")
        assertEquals(phrasePos, res.matches.first().offset, "the adjacent-phrase hit must rank first")
    }

    @Test
    fun `cap limits returned matches but totalHits reports the true count`() = runTest {
        // 20 separated occurrences of "needle"; cap at 5.
        val unit = "needle " + "x".repeat(50) + " "
        val md = unit.repeat(20)
        val index = DocumentIndex(pages = emptyList(), sections = emptyList())
        val art = materialize(md, index)

        val res = store.search(art, index, "needle", contextChars = 20, resultCap = 5)
        assertEquals(20, res.totalHits, "totalHits must reflect every hit, pre-cap")
        assertEquals(5, res.matches.size, "matches must be capped")
        assertEquals(5, res.resultCap)
    }

    @Test
    fun `no match returns empty matches and surfaces available sections`() = runTest {
        val md = "nothing relevant here at all"
        val index = DocumentIndex(
            pages = emptyList(),
            sections = listOf(DocumentIndex.Anchor("Introduction", 0), DocumentIndex.Anchor("Results", 10)),
        )
        val art = materialize(md, index)

        val res = store.search(art, index, "zebra", contextChars = 20, resultCap = 15)
        assertEquals(0, res.totalHits)
        assertTrue(res.matches.isEmpty())
        assertEquals(listOf("Introduction", "Results"), res.availableSections)
    }

    @Test
    fun `match before the first section anchor reports null section`() = runTest {
        val md = "preface line before any heading. Heading text starts here."
        val matchPos = md.indexOf("preface")
        val index = DocumentIndex(
            pages = emptyList(),
            // first section anchor is AFTER the match
            sections = listOf(DocumentIndex.Anchor("Heading", md.indexOf("Heading"))),
        )
        val art = materialize(md, index)

        val res = store.search(art, index, "preface", contextChars = 20, resultCap = 15)
        assertEquals(matchPos, res.matches.single().offset)
        assertNull(res.matches.single().section, "no section anchor precedes the match")
    }

    @Test
    fun `snippet is trimmed to word boundaries and bounded by the context window`() = runTest {
        val md = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi"
        val index = DocumentIndex(pages = emptyList(), sections = emptyList())
        val art = materialize(md, index)

        val res = store.search(art, index, "epsilon", contextChars = 12, resultCap = 15)
        val snip = res.matches.single().snippet
        // No partial leading/trailing word fragments: stripped of the «» markers, every token is whole.
        val plain = snip.replace("«", "").replace("»", "").trim().removePrefix("…").removeSuffix("…").trim()
        for (token in plain.split(Regex("\\s+"))) {
            assertTrue(md.contains(token), "token '$token' must be a whole word from the source; snippet=$snip")
        }
        assertTrue(snip.contains("«epsilon»"), "the exact match must be delimited; got: $snip")
    }

    @Test
    fun `blank query yields no matches without throwing`() = runTest {
        val md = "some content"
        val index = DocumentIndex(emptyList(), emptyList())
        val art = materialize(md, index)
        val res = store.search(art, index, "   ", contextChars = 20, resultCap = 15)
        assertEquals(0, res.totalHits)
        assertTrue(res.matches.isEmpty())
    }
}
