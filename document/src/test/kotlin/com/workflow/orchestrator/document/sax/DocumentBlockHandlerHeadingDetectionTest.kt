package com.workflow.orchestrator.document.sax

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DocumentBlockHandler]'s standalone-heading detection.
 *
 * Real spec PDFs (RFC 7230, NIST SP 800-63B) emit section headings as their OWN paragraph
 * with NO glued body — Tika's PDFParser does not wrap them in `<h1>`–`<h6>`. The original
 * [DocumentBlockHandler.tryEmitNumberedHeadingSplit] only fired when a numbered heading was
 * glued directly to its body, so standalone numbered headings ("1. Introduction") and
 * standalone unnumbered headings ("Abstract", "Executive Summary", "Appendix A: …") got NO
 * `Heading` block and therefore NO section anchor. These tests pin the broadened detection.
 *
 * Headings are driven through the public SAX surface by feeding a `<p>` element: `startElement
 * "p"`, `characters`, `endElement "p"` — which routes through the same private
 * `flushBufferAsParagraph` path the real Tika stream uses.
 */
class DocumentBlockHandlerHeadingDetectionTest {

    /** Feeds one paragraph through the SAX surface and returns the resulting blocks. */
    private fun emitParagraph(text: String): List<DocumentBlock> {
        val handler = DocumentBlockHandler()
        handler.startElement(null, "p", "p", null)
        handler.characters(text.toCharArray(), 0, text.length)
        handler.endElement(null, "p", "p")
        handler.endDocument()
        return handler.blocks
    }

    // ── A: standalone numbered headings (no glued body) ────────────────────────

    @Test
    fun `standalone numbered heading on its own line becomes a Heading`() {
        val blocks = emitParagraph("3 Definitions and Abbreviations")
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()
        assertEquals(1, headings.size, "got: $blocks")
        assertEquals("3 Definitions and Abbreviations", headings[0].text)
        assertEquals(1, headings[0].level, "single-segment number → level 1")
        assertTrue(
            blocks.none { it is DocumentBlock.Paragraph },
            "a standalone heading must NOT also leave a paragraph; got: $blocks",
        )
    }

    @Test
    fun `standalone dotted numbered heading uses dot-depth for level`() {
        val blocks = emitParagraph("1.1 Overview of the Framework")
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()
        assertEquals(1, headings.size, "got: $blocks")
        assertEquals("1.1 Overview of the Framework", headings[0].text)
        assertEquals(2, headings[0].level, "two dot-separated segments → level 2")
    }

    @Test
    fun `RFC-style numbered heading with trailing dot on number becomes a Heading`() {
        // RFC 7230 emits "1.  Introduction" and "1.1.  Requirements Notation" as standalone lines.
        val blocks = emitParagraph("1.1.  Requirements Notation")
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()
        assertEquals(1, headings.size, "got: $blocks")
        assertEquals(2, headings[0].level)
    }

    @Test
    fun `glued numbered heading still splits into heading plus paragraph`() {
        // Regression: the original glued-split behaviour must survive the broadening.
        val blocks = emitParagraph("1.2 Scope of the WorkThis section describes the scope in detail.")
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()
        val paras = blocks.filterIsInstance<DocumentBlock.Paragraph>()
        assertEquals(1, headings.size, "got: $blocks")
        assertEquals(1, paras.size, "glued body must remain a separate paragraph; got: $blocks")
        assertEquals("1.2 Scope of the Work", headings[0].text)
        assertTrue(paras[0].text.startsWith("This section describes"), "got: ${paras[0].text}")
    }

    // ── A: standalone UNNUMBERED headings ──────────────────────────────────────

    @Test
    fun `short Title-Case standalone line becomes a Heading`() {
        val blocks = emitParagraph("Executive Summary")
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()
        assertEquals(1, headings.size, "got: $blocks")
        assertEquals("Executive Summary", headings[0].text)
    }

    @Test
    fun `single Title-Case word standalone line becomes a Heading`() {
        val blocks = emitParagraph("Abstract")
        assertEquals(1, blocks.filterIsInstance<DocumentBlock.Heading>().size, "got: $blocks")
        assertEquals("Abstract", (blocks.first() as DocumentBlock.Heading).text)
    }

    @Test
    fun `ALL-CAPS standalone line becomes a Heading`() {
        val blocks = emitParagraph("ACKNOWLEDGEMENTS")
        assertEquals(1, blocks.filterIsInstance<DocumentBlock.Heading>().size, "got: $blocks")
    }

    @Test
    fun `appendix heading with colon and label becomes a Heading`() {
        val blocks = emitParagraph("Appendix A: Framework Core")
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()
        assertEquals(1, headings.size, "got: $blocks")
        assertEquals("Appendix A: Framework Core", headings[0].text)
    }

    // ── A: CONSERVATISM — ordinary prose must NOT be detected ──────────────────

    @Test
    fun `ordinary sentence paragraph is NOT a heading`() {
        val prose = "The Framework focuses on using business drivers to guide cybersecurity " +
            "activities and considering cybersecurity risks as part of the organization's risk " +
            "management processes."
        val blocks = emitParagraph(prose)
        assertTrue(
            blocks.none { it is DocumentBlock.Heading },
            "a full sentence (ends with '.', many words) must stay a Paragraph; got: $blocks",
        )
        assertEquals(1, blocks.filterIsInstance<DocumentBlock.Paragraph>().size)
    }

    @Test
    fun `short sentence ending in a period is NOT a heading`() {
        val blocks = emitParagraph("This is short.")
        assertTrue(blocks.none { it is DocumentBlock.Heading }, "terminal period → prose; got: $blocks")
    }

    @Test
    fun `short lowercase fragment is NOT a heading`() {
        // Not Title-Case, not ALL-CAPS → not a heading.
        val blocks = emitParagraph("see section 4 below")
        assertTrue(blocks.none { it is DocumentBlock.Heading }, "lowercase fragment → prose; got: $blocks")
    }

    @Test
    fun `long Title-Case line that is really a sentence is NOT a heading`() {
        // Many Title-ish words but clearly a sentence-length run — must not be promoted.
        val line = "The United States Depends On The Reliable Functioning Of Critical " +
            "Infrastructure Such As Electricity Banking And Water Across Every Region"
        val blocks = emitParagraph(line)
        assertTrue(blocks.none { it is DocumentBlock.Heading }, "over word-count cap → prose; got: $blocks")
    }

    @Test
    fun `numbered list item with trailing prose is NOT promoted to a heading`() {
        // "1) Describe their current cybersecurity posture;" — list item, not a section heading.
        val blocks = emitParagraph("1) Describe their current cybersecurity posture;")
        assertTrue(blocks.none { it is DocumentBlock.Heading }, "list item → prose; got: $blocks")
    }
}
