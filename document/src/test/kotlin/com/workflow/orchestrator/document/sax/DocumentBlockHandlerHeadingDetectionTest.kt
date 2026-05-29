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

    // ── NAV-3: tightened promotion guards (false anchors must stay paragraphs) ──

    @Test
    fun `line ending in a trailing colon is NOT a heading`() {
        // nist-800-53r5 leaks "Control Enhancements:" + nist-800-63b "Cookies:" as anchors.
        // A trailing colon on a label-like run is a lead-in to a list/value, not a section.
        for (label in listOf("Control Enhancements:", "Cookies:")) {
            val blocks = emitParagraph(label)
            assertTrue(
                blocks.none { it is DocumentBlock.Heading },
                "trailing-colon label '$label' → prose; got: $blocks",
            )
        }
    }

    @Test
    fun `appendix-style colon heading is still allowed (interior colon)`() {
        // The colon guard must reject ONLY a trailing colon, not an interior one.
        val blocks = emitParagraph("Appendix A: Framework Core")
        assertEquals(1, blocks.filterIsInstance<DocumentBlock.Heading>().size, "got: $blocks")
    }

    @Test
    fun `bare acronym-plus-expansion line is NOT a heading`() {
        // nist-csf Appendix C emits "ANSI American National Standards Institute",
        // "CIS Center for Internet Security", … — a leading ALL-CAPS acronym then its
        // expansion. These are glossary rows, not section headings.
        for (line in listOf(
            "ANSI American National Standards Institute",
            "CIS Center for Internet Security",
            "NIST National Institute of Standards and Technology",
            "ISO International Organization for Standardization",
        )) {
            val blocks = emitParagraph(line)
            assertTrue(
                blocks.none { it is DocumentBlock.Heading },
                "acronym-expansion glossary row '$line' → prose; got: $blocks",
            )
        }
    }

    @Test
    fun `equation-glyph or symbol-heavy line is NOT a heading`() {
        // arxiv "QKT" (equation numerator) and "<EOS> <EOS>" (heatmap axis labels).
        for (line in listOf("QKT", "<EOS> <EOS>", "QK V")) {
            val blocks = emitParagraph(line)
            assertTrue(
                blocks.none { it is DocumentBlock.Heading },
                "equation/symbol fragment '$line' → prose; got: $blocks",
            )
        }
    }

    @Test
    fun `numbered list item with terminal semicolon clause is NOT a heading`() {
        // nist-800-53r5 "2. Group and role membership; and" — an enumerated list item
        // (period after a single digit, sentence-shaped body) wrongly promoted as an anchor.
        for (line in listOf(
            "2. Group and role membership; and",
            "2. Intended system usage; and",
        )) {
            val blocks = emitParagraph(line)
            assertTrue(
                blocks.none { it is DocumentBlock.Heading },
                "enumerated list item '$line' → prose; got: $blocks",
            )
        }
    }

    @Test
    fun `chart-axis fragment with leading number is NOT a heading`() {
        // fed-scf chart axis "100 Percent" — a number glued to a single word axis label.
        val blocks = emitParagraph("100 Percent")
        assertTrue(blocks.none { it is DocumentBlock.Heading }, "axis fragment → prose; got: $blocks")
    }

    @Test
    fun `real numbered section heading still survives the tightened guards`() {
        // Regression guard: the new rejections must not eat legitimate numbered headings.
        for (line in listOf("4.0 Self-Assessing Cybersecurity Risk", "5.1 Authenticator Assurance Levels")) {
            val blocks = emitParagraph(line)
            assertEquals(
                1, blocks.filterIsInstance<DocumentBlock.Heading>().size,
                "real numbered heading '$line' must stay a Heading; got: $blocks",
            )
        }
    }

    @Test
    fun `known generator tool-artifact title is NOT a heading`() {
        // SF-9: rfc7230's PDF /Title is "Enscript Output" (a GNU enscript tool artifact), which
        // the heuristic would promote to the document's first H1, demoting the real RFC title.
        val blocks = emitParagraph("Enscript Output")
        assertTrue(
            blocks.none { it is DocumentBlock.Heading },
            "tool-artifact title 'Enscript Output' → prose, not a heading; got: $blocks",
        )
    }

    @Test
    fun `real unnumbered section heading still survives the tightened guards`() {
        for (line in listOf("Executive Summary", "Purpose", "Abstract")) {
            val blocks = emitParagraph(line)
            assertEquals(
                1, blocks.filterIsInstance<DocumentBlock.Heading>().size,
                "real heading '$line' must stay a Heading; got: $blocks",
            )
        }
    }
}
