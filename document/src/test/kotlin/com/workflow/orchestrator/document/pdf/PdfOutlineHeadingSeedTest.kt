package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * TDD for **NAV-4 / NAV-6**: PDF outline-seeded section anchors.
 *
 * Real spec PDFs (NIST SP 800-63-3, 800-53r5) carry an authoritative bookmark/outline tree whose
 * nesting depth encodes the section hierarchy (`INTRODUCTION` → H1, `1.1 PURPOSE` → H2,
 * `AC-1 …` → H3). The heuristic standalone-heading promotion in
 * [com.workflow.orchestrator.document.sax.DocumentBlockHandler] both *drops* real numbered
 * sections (NAV-4) and *inverts* the hierarchy (NAV-6 — `# AC-24` outranking `## 3.1 ACCESS
 * CONTROL`). Harvesting the outline as the authoritative anchor set fixes both.
 *
 * These tests build an in-memory PDF with a [PDDocumentOutline] (depth 0/1/2) and assert
 * [PdfMetadataExtractor] emits [DocumentBlock.Heading] blocks at the correct levels and pages.
 */
class PdfOutlineHeadingSeedTest {

    /**
     * Builds a 3-page PDF with this outline tree:
     *
     *  - "Introduction"            (depth 0 → H1)  → page 1
     *    - "1.1 Purpose"           (depth 1 → H2)  → page 1
     *    - "1.2 Scope"             (depth 1 → H2)  → page 2
     *  - "The Controls"            (depth 0 → H1)  → page 3
     *    - "3.1 Access Control"    (depth 1 → H2)  → page 3
     *      - "AC-1 Policy"         (depth 2 → H3)  → page 3
     */
    private fun buildPdfWithOutline(): ByteArray {
        val doc = PDDocument()
        val pages = (0 until 3).map { PDPage(PDRectangle.LETTER).also { doc.addPage(it) } }

        fun item(title: String, pageIdx: Int): PDOutlineItem =
            PDOutlineItem().apply {
                this.title = title
                destination = PDPageFitDestination().apply { page = pages[pageIdx] }
            }

        val outline = PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline

        val intro = item("Introduction", 0)
        outline.addLast(intro)
        intro.addLast(item("1.1 Purpose", 0))
        intro.addLast(item("1.2 Scope", 1))

        val controls = item("The Controls", 2)
        outline.addLast(controls)
        val accessControl = item("3.1 Access Control", 2)
        controls.addLast(accessControl)
        accessControl.addLast(item("AC-1 Policy", 2))

        outline.openNode()

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun extractHeadings(): List<Pair<Int, String>> {
        val pdfBytes = buildPdfWithOutline()
        val tempFile = Files.createTempFile("outline-seed-", ".pdf")
        Files.write(tempFile, pdfBytes)
        return try {
            PdfMetadataExtractor().extract(tempFile)
                .map { it.block }
                .filterIsInstance<DocumentBlock.Heading>()
                .map { it.level to it.text }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `outline is harvested into Heading blocks with depth-derived levels`() {
        val headings = extractHeadings()
        assertTrue(headings.isNotEmpty(), "outline must produce Heading blocks; got: $headings")

        fun levelOf(titlePart: String): Int? =
            headings.firstOrNull { it.second.contains(titlePart) }?.first

        assertEquals(1, levelOf("Introduction"), "depth-0 outline node → H1; got: $headings")
        assertEquals(2, levelOf("1.1 Purpose"), "depth-1 outline node → H2; got: $headings")
        assertEquals(2, levelOf("1.2 Scope"), "depth-1 outline node → H2; got: $headings")
        assertEquals(1, levelOf("The Controls"), "depth-0 outline node → H1; got: $headings")
        assertEquals(2, levelOf("3.1 Access Control"), "depth-1 outline node → H2; got: $headings")
        assertEquals(3, levelOf("AC-1 Policy"), "depth-2 outline node → H3; got: $headings")
    }

    @Test
    fun `outline headings preserve document order`() {
        val titles = extractHeadings().map { it.second }
        val introIdx = titles.indexOfFirst { it.contains("Introduction") }
        val controlsIdx = titles.indexOfFirst { it.contains("The Controls") }
        val acIdx = titles.indexOfFirst { it.contains("AC-1") }
        assertTrue(introIdx in 0 until controlsIdx, "Introduction before The Controls; got: $titles")
        assertTrue(controlsIdx < acIdx, "The Controls before AC-1; got: $titles")
    }

    @Test
    fun `inverted hierarchy is fixed — a code-prefixed leaf outranks (deeper than) its parent section`() {
        // NAV-6: "AC-1 Policy" (a control code) must be DEEPER than "3.1 Access Control",
        // never the reverse. The heuristic produced `# AC-24` / `## 3.1`; the outline fixes it.
        val headings = extractHeadings()
        val acLevel = headings.first { it.second.contains("AC-1") }.first
        val sectionLevel = headings.first { it.second.contains("3.1 Access Control") }.first
        assertTrue(
            acLevel > sectionLevel,
            "control code 'AC-1' (H$acLevel) must be deeper than section '3.1' (H$sectionLevel); got: $headings",
        )
    }
}
