package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.pdf.PositionedBlock
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the G-9 structure-fidelity passes added to [PdfPipeline] that operate on the
 * merged positioned-block stream:
 *
 * - SF-5: repeating running-header/footer bands (`Fielding & Reschke … [Page N]` ×89) are
 *   stripped even when each repetition carries its own page number in a non-trailing position
 *   or a bracketed `[Page N]` form that the original bare-trailing-number normaliser missed.
 * - SF-10 (cross-paragraph): a paragraph ending in a soft line-wrap hyphen (`… Gov-`) is merged
 *   with the immediately-following same-stream paragraph (`ernors, …`) and de-hyphenated.
 *
 * These exercise `internal` helpers directly (mirroring the `guessImageMimeFromSrc` precedent in
 * [com.workflow.orchestrator.document.sax.DocumentBlockHandlerHelpersTest]) because the full
 * [PdfPipeline.extract] path needs a real PDF byte stream, which is out of scope for a focused
 * heuristic unit test.
 */
class PdfPipelineStructureFidelityTest {

    private val pipeline = PdfPipeline()

    private fun para(page: Int, top: Double, text: String): PositionedBlock<DocumentBlock> =
        PositionedBlock(page, top, top + 10.0, DocumentBlock.Paragraph(text))

    private fun texts(blocks: List<PositionedBlock<DocumentBlock>>): List<String> =
        blocks.mapNotNull { (it.block as? DocumentBlock.Paragraph)?.text }

    // ── SF-5: repeating header/footer bands with bracketed page numbers ────────

    @Test
    fun `bracketed page-number footer repeating on most pages is stripped`() {
        // RFC 7230: identical footer on every page, differing only by "[Page N]".
        val blocks = (1..8).map { p ->
            para(p, 100.0, "Fielding & Reschke           Standards Track                    [Page $p]")
        } + listOf(
            para(1, 1.0, "Real body content on page one."),
            para(5, 1.0, "Real body content on page five."),
        )
        val out = texts(pipeline.stripRepeatedPageChromeForTest(blocks))
        assertTrue(out.none { it.contains("Fielding & Reschke") }, "footer band must be gone; got: $out")
        assertTrue(out.any { it.contains("Real body content on page one") }, "body must survive; got: $out")
        assertEquals(2, out.size, "only the two body paragraphs remain; got: $out")
    }

    @Test
    fun `running header with an interior page number is stripped`() {
        // NIST-style header where the page number sits between the title halves rather than at
        // the trailing edge — the bare-trailing-number normaliser misses it.
        val blocks = (1..8).map { p ->
            para(p, 5.0, "NIST SP 800-53 Rev. 5    Page $p    Security and Privacy Controls")
        } + listOf(para(3, 50.0, "An actual sentence of body prose that should be kept."))
        val out = texts(pipeline.stripRepeatedPageChromeForTest(blocks))
        assertTrue(out.none { it.startsWith("NIST SP 800-53") }, "header band must be gone; got: $out")
        assertEquals(1, out.size, "only body remains; got: $out")
    }

    @Test
    fun `body prose that happens to repeat a few times is NOT stripped below threshold`() {
        // A short repeated phrase appearing on only 2 of 8 pages is below pages/2 — keep it.
        val blocks = (1..8).map { p -> para(p, 50.0, "Body sentence number $p with unique content.") } +
            listOf(para(1, 60.0, "See note."), para(2, 60.0, "See note."))
        val out = texts(pipeline.stripRepeatedPageChromeForTest(blocks))
        assertEquals(2, out.count { it == "See note." }, "below-threshold repeats are kept; got: $out")
    }

    // ── SF-10: cross-paragraph soft-hyphen rejoin ──────────────────────────────

    @Test
    fun `paragraph ending in a soft hyphen merges with the next and de-hyphenates`() {
        // fed-scf: "… Board of Gov-" then "ernors, the Federal …" as two separate paragraphs.
        val blocks = listOf(
            para(1, 1.0, "for use by the Board of Gov-"),
            para(1, 2.0, "ernors, the Federal Open Market Committee, and others."),
        )
        val out = texts(pipeline.rejoinHyphenatedParagraphsForTest(blocks))
        assertEquals(1, out.size, "the two paragraphs must merge into one; got: $out")
        assertTrue(
            out.single().startsWith("for use by the Board of Governors, the Federal"),
            "de-hyphenated join expected; got: <<${out.single()}>>",
        )
    }

    @Test
    fun `paragraph ending in a normal word is NOT merged with the next`() {
        val blocks = listOf(
            para(1, 1.0, "This is a complete sentence."),
            para(1, 2.0, "This is the next paragraph."),
        )
        val out = texts(pipeline.rejoinHyphenatedParagraphsForTest(blocks))
        assertEquals(2, out.size, "non-hyphen paragraphs stay separate; got: $out")
    }

    @Test
    fun `paragraph ending in a hyphen before a number-leading continuation keeps the hyphen`() {
        // A dangling hyphen before a digit continuation ("rates of 4.6 per-" / "30 cent") is a
        // numeric/odd case — keep the hyphen, only collapse the boundary to a space.
        val blocks = listOf(
            para(1, 1.0, "auto loans bottomed at 4.6 per-"),
            para(1, 2.0, "30 cent in November."),
        )
        val out = texts(pipeline.rejoinHyphenatedParagraphsForTest(blocks))
        assertEquals(1, out.size)
        assertTrue(out.single().contains("per- 30 cent"), "hyphen kept before digit; got: <<${out.single()}>>")
    }

    @Test
    fun `a hyphen paragraph at the very end of the stream is left as-is`() {
        val blocks = listOf(para(1, 1.0, "trailing dangling word ends here Gov-"))
        val out = texts(pipeline.rejoinHyphenatedParagraphsForTest(blocks))
        assertEquals(1, out.size, "no following paragraph to merge with; got: $out")
        assertEquals("trailing dangling word ends here Gov-", out.single())
    }
}
