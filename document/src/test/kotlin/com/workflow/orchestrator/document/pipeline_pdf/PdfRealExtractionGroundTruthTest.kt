package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.core.model.DocumentIndex
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Real-PDF end-to-end extraction tests whose expected values were established by reading
 * the fixture PDFs DIRECTLY (independent ground truth), then cross-checked against the
 * extractor's actual output. They run the FULL pipeline (PdfPipeline.extract →
 * MarkdownAssembler.assembleIndexed) and exercise the documented `section` navigation
 * contract (DocumentIndex.offsetForSection case-insensitive SUBSTRING match) on headings
 * that the extractor really produces — closing the gap where only hand-built indexes and
 * mocked stores were tested.
 *
 * Ground-truth source: spec-with-tables.pdf (2pp, 4 numbered sections, 3 tables),
 * multi-page-table.pdf (cross-page table, 40 data rows), nist-cybersecurity-framework.pdf
 * (55pp real-world doc). Verbatim headings/first-sentences confirmed by reading each PDF.
 */
class PdfRealExtractionGroundTruthTest {

    private val pipeline = PdfPipeline()
    private val assembler = MarkdownAssembler()

    private data class Extracted(val blocks: List<DocumentBlock>, val markdown: String, val index: DocumentIndex)

    private fun extract(name: String): Extracted {
        val path = Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())
        val blocks = pipeline.extract(path)
        val assembled = assembler.assembleIndexed(blocks)
        return Extracted(blocks, assembled.markdown, assembled.index)
    }

    /** Resolve a section label, then return the markdown window starting at its offset. */
    private fun sliceAtSection(e: Extracted, label: String, len: Int = 120): String {
        val offset = e.index.offsetForSection(label)
        assertNotNull(offset, "section '$label' must resolve via the index (substring match); sections=${e.index.sections.map { it.key }}")
        return e.markdown.substring(offset!!, minOf(offset + len, e.markdown.length))
    }

    // ── spec-with-tables.pdf ─────────────────────────────────────────────────

    @Test
    fun `spec-with-tables — structure matches the PDF read directly`() {
        val e = extract("spec-with-tables.pdf")
        assertEquals(2, e.blocks.count { it is DocumentBlock.PageMarker }, "2-page document")

        val headings = e.blocks.filterIsInstance<DocumentBlock.Heading>().map { it.text }
        assertEquals(
            listOf("1. Introduction", "2. Functional Requirements", "3. Non-functional Requirements", "4. Acceptance"),
            headings,
            "the 4 numbered section headings, in order",
        )

        val tables = e.blocks.filterIsInstance<DocumentBlock.Table>()
        assertEquals(3, tables.size, "FR matrix + NFR bounds + Acceptance matrix")
        assertEquals(listOf("ReqId", "Priority", "Status"), tables[0].headers)
        assertEquals(4, tables[0].rows.size, "FR matrix has 4 data rows")
        assertEquals(listOf("Metric", "Bound", "Measured"), tables[1].headers)
        assertEquals(3, tables[1].rows.size, "NFR bounds has 3 data rows")
        assertEquals(listOf("Test", "Expected", "Actual"), tables[2].headers)
        assertEquals(3, tables[2].rows.size, "Acceptance matrix has 3 data rows")
    }

    @Test
    fun `spec-with-tables — partial section labels navigate to the right heading`() {
        val e = extract("spec-with-tables.pdf")

        // "Introduction" is a substring of the "1. Introduction" heading (number dropped).
        val intro = sliceAtSection(e, "introduction")
        assertTrue(intro.startsWith("# 1. Introduction"), "navigates to the Introduction heading; got: $intro")
        assertTrue(
            intro.contains("This specification describes the read_document"),
            "content under Introduction matches the PDF; got: $intro",
        )

        // "Acceptance" (no number) must hit "4. Acceptance" on page 2.
        val acceptance = sliceAtSection(e, "Acceptance")
        assertTrue(acceptance.startsWith("# 4. Acceptance"), "navigates to the Acceptance heading; got: $acceptance")
        assertTrue(
            acceptance.contains("acceptance matrix is reproduced below", ignoreCase = true),
            "content under Acceptance matches the PDF; got: $acceptance",
        )
    }

    // ── multi-page-table.pdf ─────────────────────────────────────────────────

    @Test
    fun `multi-page-table — cross-page table merges to one block with 40 data rows`() {
        val e = extract("multi-page-table.pdf")
        assertEquals(2, e.blocks.count { it is DocumentBlock.PageMarker }, "2-page document")

        val tables = e.blocks.filterIsInstance<DocumentBlock.Table>()
        assertEquals(1, tables.size, "page-1 and page-2 fragments must merge into ONE logical table")
        assertEquals(listOf("BugId", "Severity", "Status"), tables[0].headers)
        // The header repeats on page 2 in the PDF; the merge must NOT count it as a data row.
        assertEquals(40, tables[0].rows.size, "BUG-001..BUG-040 = 40 data rows, header not duplicated")
    }

    // ── nist-cybersecurity-framework.pdf (real-world, 55pp) ──────────────────

    @Test
    fun `nist — real-world headings extracted and partial labels navigate correctly`() {
        val e = extract("nist-cybersecurity-framework.pdf")
        assertEquals(55, e.blocks.count { it is DocumentBlock.PageMarker }, "55-page document")

        val headings = e.blocks.filterIsInstance<DocumentBlock.Heading>().map { it.text }
        assertTrue(headings.contains("Executive Summary"), "got: $headings")
        assertTrue(headings.contains("1.0 Framework Introduction"), "TOC's multi-space title normalized to single space; got: $headings")
        assertTrue(headings.contains("Appendix B: Glossary"), "got: $headings")

        // "framework introduction" (lower-case, number dropped) → "1.0 Framework Introduction".
        val intro = sliceAtSection(e, "framework introduction")
        assertTrue(intro.contains("1.0 Framework Introduction"), "navigates to the section heading; got: $intro")
        assertTrue(
            intro.contains("United States depends on the reliable functioning"),
            "content under the intro matches the PDF; got: $intro",
        )

        // Single-word "glossary" → "Appendix B: Glossary" near the end of a 55-page doc.
        val glossary = sliceAtSection(e, "glossary")
        assertTrue(glossary.contains("Appendix B: Glossary"), "navigates to the glossary appendix; got: $glossary")
        assertTrue(
            glossary.contains("This appendix defines selected terms"),
            "content under the glossary matches the PDF; got: $glossary",
        )

        // Exact (case-insensitive) match still wins for an unnumbered heading.
        val exec = sliceAtSection(e, "executive summary")
        assertTrue(exec.startsWith("# Executive Summary"), "exact CI match resolves; got: $exec")
        assertTrue(
            exec.contains("United States depends on the reliable functioning of critical infrastructure"),
            "Executive Summary first sentence matches the PDF; got: $exec",
        )
    }
}
