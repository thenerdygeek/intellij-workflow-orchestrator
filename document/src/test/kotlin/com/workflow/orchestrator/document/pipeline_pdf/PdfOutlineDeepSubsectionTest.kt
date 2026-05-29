package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * TDD for the **additive-demotion** fix (root cause b).
 *
 * The G-5 outline-seeding fix ([PdfPipeline.demoteProseHeadings]) demotes ALL heuristic-promoted
 * headings to paragraphs whenever the PDF carries a bookmark outline, so the outline is the sole
 * section-anchor source. But when the outline is SHALLOW (stops at `2.4.4`) and the body carries
 * DEEPER numbered headings (`2.4.4.1 Request Context`), those deeper headings get demoted →
 * never anchored → `section="2.4.4.1"` cannot resolve.
 *
 * The fix makes demotion ADDITIVE: a heuristic heading whose well-formed numbered label
 * (`^\d+(\.\d+){1,4}`) is NOT covered by any outline anchor (at any depth) is KEPT as a
 * supplemental anchor; everything the outline already covers (and every non-numbered noise
 * heading) is still demoted.
 */
class PdfOutlineDeepSubsectionTest {

    /**
     * Builds a one-page PDF whose outline reaches only level 3 (`2.4.4 Common Parameters`) but
     * whose body renders a deeper numbered heading (`2.4.4.1 Request Context`) plus a plain prose
     * line that must NOT be promoted.
     */
    private fun buildShallowOutlineDeepBody(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        // Each line is its own text block with a wide vertical gap so Tika keeps them as separate
        // paragraphs (consecutive showText lines get glued into one <p>).
        fun line(text: String, y: Float) {
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                cs.beginText()
                cs.setFont(font, 12f)
                cs.newLineAtOffset(72f, y)
                cs.showText(text)
                cs.endText()
            }
        }
        line("2.4.4 Common Parameters", 720f)
        line("2.4.4.1 Request Context", 680f)
        line("The request context describes the calling environment for the operation.", 640f)

        val outline = PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline
        // Outline reaches "2.4.4 Common Parameters" but NOT "2.4.4.1".
        val chapter = PDOutlineItem().apply {
            title = "2 Service Operations"
            destination = PDPageFitDestination().apply { this.page = page }
        }
        outline.addLast(chapter)
        chapter.addLast(PDOutlineItem().apply {
            title = "2.4.4 Common Parameters"
            destination = PDPageFitDestination().apply { this.page = page }
        })
        outline.openNode()

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    /**
     * Builds a one-page PDF whose outline ALREADY covers the deep subsection `2.4.4.1`. The body
     * renders the same line — the GUARD case: the heuristic must NOT add a second `2.4.4.1` anchor.
     */
    private fun buildDeepOutlineCoveringSubsection(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        fun line(text: String, y: Float) {
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                cs.beginText()
                cs.setFont(font, 12f)
                cs.newLineAtOffset(72f, y)
                cs.showText(text)
                cs.endText()
            }
        }
        line("2.4.4 Common Parameters", 720f)
        line("2.4.4.1 Request Context", 680f)
        line("The request context describes the calling environment for the operation.", 640f)

        val outline = PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline
        val chapter = PDOutlineItem().apply {
            title = "2.4.4 Common Parameters"
            destination = PDPageFitDestination().apply { this.page = page }
        }
        outline.addLast(chapter)
        chapter.addLast(PDOutlineItem().apply {
            title = "2.4.4.1 Request Context"
            destination = PDPageFitDestination().apply { this.page = page }
        })
        outline.openNode()

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun extract(pdfBytes: ByteArray): List<DocumentBlock> {
        val tempFile = Files.createTempFile("deep-subsection-", ".pdf")
        Files.write(tempFile, pdfBytes)
        return try {
            PdfPipeline().extract(tempFile)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `deep body subsection uncovered by a shallow outline is KEPT as a section anchor`() {
        val blocks = extract(buildShallowOutlineDeepBody())
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()

        // The shallow outline's "2.4.4 Common Parameters" must still anchor.
        assertTrue(
            headings.any { it.text.contains("2.4.4 Common Parameters") },
            "outline anchor '2.4.4' must survive; got: $headings",
        )
        // The deeper, uncovered "2.4.4.1" must be KEPT (additive demotion).
        assertTrue(
            headings.any { it.text.startsWith("2.4.4.1") },
            "deep subsection '2.4.4.1' uncovered by the outline must be anchored; got: $headings",
        )

        // Verify section= resolution via the real index the assembler produces.
        val index = MarkdownAssembler().assembleIndexed(blocks).index
        assertTrue(
            index.sections.any { it.key.startsWith("2.4.4.1") },
            "index.sections must contain a '2.4.4.1' anchor; got: ${index.sections.map { it.key }}",
        )
        assertNotNull(
            index.offsetForSection("2.4.4.1 Request Context"),
            "section='2.4.4.1 Request Context' must resolve non-null",
        )
        assertNotNull(
            index.offsetForSection("2.4.4.1"),
            "bare number section='2.4.4.1' must resolve non-null",
        )
        assertTrue(index.offsetForSection("2.4.4.1")!! > 0, "resolved offset must be non-zero")
    }

    @Test
    fun `prose line is still demoted — no false anchor`() {
        val blocks = extract(buildShallowOutlineDeepBody())
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()
        assertTrue(
            headings.none { it.text.contains("request context describes") },
            "ordinary prose must stay demoted (not promoted to a heading); got: $headings",
        )
    }

    @Test
    fun `GUARD — a subsection already covered by the outline is NOT double-anchored`() {
        val blocks = extract(buildDeepOutlineCoveringSubsection())
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()
        val deep = headings.filter { it.text.startsWith("2.4.4.1") }
        assertEquals(
            1, deep.size,
            "outline already covers '2.4.4.1' → exactly ONE anchor, no heuristic duplicate; got: $headings",
        )

        val index = MarkdownAssembler().assembleIndexed(blocks).index
        val deepAnchors = index.sections.filter { it.key.startsWith("2.4.4.1") }
        assertEquals(
            1, deepAnchors.size,
            "exactly one '2.4.4.1' section anchor; got: ${index.sections.map { it.key }}",
        )
    }
}
