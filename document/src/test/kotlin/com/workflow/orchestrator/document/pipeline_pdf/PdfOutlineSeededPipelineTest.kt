package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * NAV-4/NAV-6 end-to-end: when a PDF has an outline, the merged pipeline output's section
 * anchors come from the outline (authoritative hierarchy) and the noisy/inverted heuristic
 * anchors are suppressed.
 */
class PdfOutlineSeededPipelineTest {

    /**
     * One-page PDF that BOTH carries an outline (`3.1 Access Control` H2 → `AC-1 Policy` H3) AND
     * renders body text the prose heuristic would wrongly promote to a flat `# AC-1` H1 anchor
     * (the NAV-6 inversion). The pipeline must keep the outline's hierarchy and drop the
     * heuristic's inverted promotion.
     */
    private fun buildPdfWithOutlineAndPromotableProse(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(font, 12f)
            cs.newLineAtOffset(72f, 720f)
            cs.setLeading(16f)
            // Standalone lines the heuristic promotes: a section title and a control code.
            cs.showText("3.1 Access Control")
            cs.newLine()
            cs.showText("AC-1 Policy And Procedures")
            cs.newLine()
            cs.showText("The organization develops and documents an access control policy.")
            cs.endText()
        }

        val outline = PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline
        // Mirror the real 53r5 hierarchy: "The Controls" (H1) → "3.1 …" (H2) → "AC-1 …" (H3).
        val chapter = PDOutlineItem().apply {
            title = "The Controls"
            destination = PDPageFitDestination().apply { this.page = page }
        }
        outline.addLast(chapter)
        val section = PDOutlineItem().apply {
            title = "3.1 Access Control"
            destination = PDPageFitDestination().apply { this.page = page }
        }
        chapter.addLast(section)
        section.addLast(PDOutlineItem().apply {
            title = "AC-1 Policy And Procedures"
            destination = PDPageFitDestination().apply { this.page = page }
        })
        outline.openNode()

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    @Test
    fun `outline-seeded pipeline keeps authoritative hierarchy and drops inverted heuristic anchors`() {
        val pdfBytes = buildPdfWithOutlineAndPromotableProse()
        val tempFile = Files.createTempFile("outline-pipeline-", ".pdf")
        Files.write(tempFile, pdfBytes)
        try {
            val blocks = PdfPipeline().extract(tempFile)
            val headings = blocks.filterIsInstance<DocumentBlock.Heading>()

            val section = headings.filter { it.text.contains("Access Control") }
            val control = headings.filter { it.text.contains("AC-1") }

            assertEquals(1, section.size, "exactly one '3.1 Access Control' anchor; got: $headings")
            assertEquals(1, control.size, "exactly one 'AC-1' anchor; got: $headings")

            // NAV-6: the control code must be DEEPER than its parent section, never a flat H1.
            assertEquals(2, section.first().level, "section '3.1' → H2 from outline; got: $headings")
            assertEquals(3, control.first().level, "control 'AC-1' → H3 from outline; got: $headings")
            assertTrue(
                control.first().level > section.first().level,
                "AC-1 must be deeper than 3.1 (no inversion); got: $headings",
            )
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
