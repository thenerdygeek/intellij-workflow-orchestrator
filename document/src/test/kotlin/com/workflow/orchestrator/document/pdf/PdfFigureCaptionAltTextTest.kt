package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.service.ImageExtractionService
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * TDD for **IMG-2**: PDF image XObjects carry NO alt-text (unlike DOCX/PPTX `docPr descr`
 * handled by G-7), so the on-page "Figure N: …" caption is the figure's only description.
 *
 * [PdfMetadataExtractor.extract] historically emitted each image marker caption-less — the
 * marker rendered as an opaque temp path with no human-readable description, leaving the
 * caption (often hundreds of lines away in the merged prose, or only in the TOC) stranded
 * from its figure. These tests pin the additive behaviour:
 *
 * - An image drawn on a page with a `Figure N:` caption line directly below it → the emitted
 *   [DocumentBlock.EmbeddedFileRef] carries that caption as its `altText` (reusing the G-7
 *   alt-text mechanism), so the marker renders `[image: Figure 1: … — <path>]`.
 * - An image with NO caption pattern anywhere in the band → `altText` stays null. The
 *   extractor must NOT fabricate or misattribute a distant caption (conservative guard).
 */
class PdfFigureCaptionAltTextTest {

    /** A solid 120×120 PNG — comfortably above the 32 px fragment filter. */
    private fun pngBytes(): ByteArray {
        val img = BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(40, 90, 160)
        g.fillRect(0, 0, 120, 120)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }

    /**
     * One-page LETTER PDF with an image drawn in the upper-middle of the page and a
     * "Figure 1: A test caption" text line rendered directly below the image rectangle.
     */
    private fun buildPdfWithCaptionedFigure(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER) // 612 × 792
        doc.addPage(page)
        val img = PDImageXObject.createFromByteArray(doc, pngBytes(), "fig")
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        // Draw the image: lower-left at (256, 500), 100×100 user-space units.
        // The caption sits at y=485 — ~15 pt below the image's bottom edge (500).
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(img, 256f, 500f, 100f, 100f)
            cs.beginText()
            cs.setFont(font, 11f)
            cs.newLineAtOffset(256f, 485f)
            cs.showText("Figure 1: A test caption")
            cs.endText()
        }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    /**
     * One-page LETTER PDF with the same image but NO caption — only unrelated body prose far
     * from the image rectangle. The extractor must not attach any alt-text (guard).
     */
    private fun buildPdfWithNoCaption(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        val img = PDImageXObject.createFromByteArray(doc, pngBytes(), "fig")
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(img, 256f, 500f, 100f, 100f)
            // Unrelated prose far from the image, no Figure/Table pattern anywhere near it.
            cs.beginText()
            cs.setFont(font, 11f)
            cs.newLineAtOffset(72f, 120f)
            cs.showText("This is ordinary body text with no caption nearby.")
            cs.endText()
        }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun extractImageRefs(pdfBytes: ByteArray): List<DocumentBlock.EmbeddedFileRef> {
        val tempFile = Files.createTempFile("fig-caption-", ".pdf")
        Files.write(tempFile, pdfBytes)
        val downloads = Files.createTempDirectory("fig-caption-out-")
        return try {
            val service = ImageExtractionService(downloads)
            val extractor = PdfMetadataExtractor(imageService = service, docKey = tempFile.toString())
            extractor.extract(tempFile)
                .map { it.block }
                .filterIsInstance<DocumentBlock.EmbeddedFileRef>()
                .filter { it.mimeType.startsWith("image/") }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `image gets nearby Figure caption attached as alt-text`() {
        val refs = extractImageRefs(buildPdfWithCaptionedFigure())
        assertTrue(refs.isNotEmpty(), "Expected at least one image EmbeddedFileRef")
        val ref = refs.first()
        assertNotNull(ref.altText, "Image alt-text should carry the nearby Figure caption. Ref: $ref")
        assertTrue(
            ref.altText!!.contains("Figure 1", ignoreCase = true) &&
                ref.altText!!.contains("A test caption"),
            "alt-text should be the 'Figure 1: A test caption' line. Was: '${ref.altText}'",
        )
    }

    @Test
    fun `image with no nearby caption gets no fabricated alt-text`() {
        val refs = extractImageRefs(buildPdfWithNoCaption())
        assertTrue(refs.isNotEmpty(), "Expected at least one image EmbeddedFileRef")
        val ref = refs.first()
        assertNull(ref.altText, "No caption in band → alt-text must stay null (no fabrication). Was: '${ref.altText}'")
    }
}
