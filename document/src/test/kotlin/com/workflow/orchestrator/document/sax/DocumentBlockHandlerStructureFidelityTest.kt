package com.workflow.orchestrator.document.sax

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the G-9 structure-fidelity cleanups added to [DocumentBlockHandler]:
 *
 * - SF-4: glued function-word boundaries (`theUnited` → `the United`) and dropped bullet
 *   glyphs rendered as U+FFFD (`�\t Item` → `- Item`).
 * - SF-10 (within-paragraph): line-wrap hyphen rejoining inside a single buffered paragraph
 *   that carries an interior newline (`word-\nrest` → `wordrest`).
 * - SF-3: TOC dot-leader cleanup (`Entry .......... 5` → `Entry 5`) with a fused
 *   page-number→next-entry split.
 *
 * Conservatism guards (the "normal text must be UNTOUCHED" half of TDD) live alongside each
 * positive case: camelCase identifiers, hyphenated compounds mid-line, ordinary prose with
 * capitalised proper nouns, and short sentences with a period must all pass through verbatim.
 *
 * Inputs are driven through the public SAX surface (`startElement "p"` / `characters` /
 * `endElement "p"`) so they exercise the same `flushBufferAsParagraph` path the live Tika
 * stream uses.
 */
class DocumentBlockHandlerStructureFidelityTest {

    /**
     * Feeds one paragraph through the SAX surface and returns the resulting blocks.
     *
     * `restoreUrlBoundaries = true` mirrors the live PDF-prose configuration: the SF-4 glue
     * repair and SF-10 in-block de-hyphenation are PDF-only (the byte stream for HTML/text is
     * faithful), so they are gated behind the same PDF flag and only fire here.
     */
    private fun emitParagraph(text: String): List<DocumentBlock> {
        val handler = DocumentBlockHandler(restoreUrlBoundaries = true)
        handler.startElement(null, "p", "p", null)
        handler.characters(text.toCharArray(), 0, text.length)
        handler.endElement(null, "p", "p")
        handler.endDocument()
        return handler.blocks
    }

    private fun paragraphText(text: String): String {
        val blocks = emitParagraph(text)
        val paras = blocks.filterIsInstance<DocumentBlock.Paragraph>()
        assertEquals(1, paras.size, "expected exactly one paragraph; got: $blocks")
        return paras.single().text
    }

    // ── SF-4: glued function words ─────────────────────────────────────────────

    @Test
    fun `glued lowercase function word before a capitalised word is split`() {
        // fed-scf footnote 37: "Accounts of theUnited States" — the space was eaten at a
        // style/line boundary. "the" is a closed-class function word, so the split is safe.
        // Kept sentence-shaped (trailing period) so the heading heuristic leaves it as prose.
        assertEquals(
            "the Financial Accounts of the United States are published annually.",
            paragraphText("the Financial Accounts of theUnited States are published annually."),
        )
    }

    @Test
    fun `multiple glued function words in one line are all repaired`() {
        assertEquals(
            "a comparison of the Survey of Consumer Finances and Statistical Measures was made.",
            paragraphText("a comparison of the Survey ofConsumer Finances andStatistical Measures was made."),
        )
    }

    @Test
    fun `glued function word inside a longer prose sentence is repaired`() {
        assertEquals(
            "published by the Federal Reserve Board.",
            paragraphText("published by theFederal Reserve Board."),
        )
    }

    // SF-4 conservatism guards: normal text MUST be untouched.

    @Test
    fun `camelCase identifier is not split`() {
        // A schema/code identifier glued by design — not a function-word boundary.
        assertEquals("ourRepo and itemOptions stay glued", paragraphText("ourRepo and itemOptions stay glued"))
    }

    @Test
    fun `function word that is itself the start of a longer word is not split`() {
        // "theory", "often", "android" all START with a function-word substring but are single
        // words — the repair must only fire when the function word is a WHOLE token.
        assertEquals("theory often android Theory", paragraphText("theory often android Theory"))
    }

    @Test
    fun `proper noun following a function word with a real space is untouched`() {
        assertEquals("of the United States", paragraphText("of the United States"))
    }

    @Test
    fun `function word glued to a lowercase continuation is not split`() {
        // "theme", "anderson" — lowercase after the function word, NOT a word boundary; leave it.
        assertEquals("theme anderson information", paragraphText("theme anderson information"))
    }

    // ── SF-4: dropped bullet glyph (U+FFFD) ────────────────────────────────────

    @Test
    fun `replacement-char bullet glyph at line start becomes a markdown bullet`() {
        // bjs-cv22 HIGHLIGHTS: each bullet glyph was dropped to U+FFFD followed by a tab.
        assertEquals(
            "- About 10% of violent victimizations involved a firearm",
            paragraphText("�\t About 10% of violent victimizations involved a firearm"),
        )
    }

    @Test
    fun `replacement char NOT at line start is left untouched`() {
        // A stray U+FFFD mid-line is a decode artifact, not a bullet — do not rewrite it.
        val text = "rate increased � from 16.5"
        assertEquals(text, paragraphText(text))
    }

    // ── SF-10: within-paragraph line-wrap hyphen rejoin ────────────────────────

    @Test
    fun `hyphen at end of an interior line rejoins the wrapped word`() {
        assertEquals(
            "report with assistance from",
            paragraphText("report with assis-\ntance from"),
        )
    }

    @Test
    fun `hyphen before a digit across a newline keeps its hyphen`() {
        // "30-year" style numeric ranges: a hyphen followed by a DIGIT is NOT a soft line-wrap
        // hyphen. Collapse the newline to a space but keep the hyphen so the range survives.
        assertEquals(
            "rates from 30- 40 percent",
            paragraphText("rates from 30-\n40 percent"),
        )
    }

    @Test
    fun `hyphen before an uppercase letter across a newline keeps its hyphen`() {
        // A capitalised continuation ("Mort-\nGage" is implausible; "Loan-\nTo-Value" is a real
        // compound) is not a soft wrap of one lowercase word. Keep the hyphen.
        assertEquals(
            "the Loan- To-Value ratio",
            paragraphText("the Loan-\nTo-Value ratio"),
        )
    }

    // ── SF-3: TOC dot-leader cleanup ───────────────────────────────────────────

    @Test
    fun `TOC entry with a dot-leader run has the leader collapsed`() {
        assertEquals(
            "2.2 Considerations, Other Requirements, and Flexibilities 5",
            paragraphText("2.2 Considerations, Other Requirements, and Flexibilities ................................... 5"),
        )
    }

    @Test
    fun `TOC line with a page number fused to the next entry is split`() {
        // "...Flexibilities ... 52.3 A Few Limitations" — the page number "5" is fused to the
        // next entry "2.3 A Few Limitations". Split so each entry is its own line.
        val text =
            "2.2 Considerations and Flexibilities ................... 52.3 A Few Limitations ................... 5"
        val out = paragraphText(text)
        assertTrue(
            out.contains("Flexibilities 5\n2.3 A Few Limitations"),
            "page number 5 must end the first entry and 2.3 must start a new line; got: <<$out>>",
        )
        assertFalse(out.contains("52.3"), "the fused 5+2.3 must be separated; got: <<$out>>")
    }

    @Test
    fun `a normal sentence ending in an ellipsis is not treated as a TOC line`() {
        // Three dots is an ellipsis, not a dot leader; the run threshold avoids false positives.
        val text = "He paused... and then continued."
        assertEquals(text, paragraphText(text))
    }
}
