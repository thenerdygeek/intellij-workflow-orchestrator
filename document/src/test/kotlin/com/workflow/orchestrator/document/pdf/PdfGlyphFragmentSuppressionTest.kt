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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * TDD for **IMG-6**: PDF decorative glyph-fragment image noise.
 *
 * `nist-800-63b.pdf` emits ~58 sub-1KB rasterised glyph / section-number PNGs (~62×39 px) as
 * `[image: …]` markers though the document has no real body figures there. Those fragments PASS
 * the existing 32 px fragment filter (Task 3 deliberately kept ≥32 px so a captioned 62×39 figure
 * is not dropped outright) and clutter the output with meaningless markers.
 *
 * The principled discriminator (reusing the IMG-2 caption signal rather than a blind size bump):
 * a REAL figure has a caption; a glyph fragment does NOT. An image is suppressed only when ALL of:
 *   1. it is small — its smaller pixel dimension is below
 *      [PdfMetadataExtractor.GLYPH_FRAGMENT_MAX_SMALLER_DIM_PX], AND
 *   2. it has NO associated `Figure/Table N:` caption (the IMG-2 band lookup returned null).
 *
 * Conservative bias: a small image WITH a caption is always kept (caption protects it); a large
 * image is always kept regardless of caption. Keep if uncertain.
 *
 * These tests pin:
 * - (a) a small (~60×40) caption-less image → SUPPRESSED (no marker emitted).
 * - (b) a small (~60×40) image WITH a `Figure 1:` caption → KEPT (caption protects, alt-text set).
 * - (c) a large (~120×120) caption-less image → KEPT (size protects).
 */
class PdfGlyphFragmentSuppressionTest {

    /** A solid PNG of the given dimensions. */
    private fun pngBytes(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(40, 90, 160)
        g.fillRect(0, 0, w, h)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }

    /**
     * One-page LETTER PDF with a single image of the given pixel size drawn in the upper-middle
     * of the page, optionally with a "Figure 1: …" caption line directly below it.
     */
    private fun buildPdf(imgW: Int, imgH: Int, withCaption: Boolean): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER) // 612 × 792
        doc.addPage(page)
        val img = PDImageXObject.createFromByteArray(doc, pngBytes(imgW, imgH), "fig")
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        // Draw at native pixel size in user-space units; lower-left at (256, 500).
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(img, 256f, 500f, imgW.toFloat(), imgH.toFloat())
            if (withCaption) {
                cs.beginText()
                cs.setFont(font, 11f)
                cs.newLineAtOffset(256f, 485f) // ~15 pt below the image bottom edge
                cs.showText("Figure 1: A test caption")
                cs.endText()
            } else {
                // Unrelated body prose far from the image — no Figure/Table pattern near it.
                cs.beginText()
                cs.setFont(font, 11f)
                cs.newLineAtOffset(72f, 120f)
                cs.showText("This is ordinary body text with no caption nearby.")
                cs.endText()
            }
        }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun extractImageRefs(pdfBytes: ByteArray): List<DocumentBlock.EmbeddedFileRef> {
        val tempFile = Files.createTempFile("glyph-frag-", ".pdf")
        Files.write(tempFile, pdfBytes)
        val downloads = Files.createTempDirectory("glyph-frag-out-")
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
    fun `small caption-less image is suppressed as a glyph fragment`() {
        val refs = extractImageRefs(buildPdf(imgW = 62, imgH = 39, withCaption = false))
        assertTrue(
            refs.isEmpty(),
            "A small (~62×39) caption-less image is glyph noise and must be suppressed (no marker). Got: $refs",
        )
    }

    @Test
    fun `small image with a Figure caption is kept (caption protects)`() {
        val refs = extractImageRefs(buildPdf(imgW = 62, imgH = 39, withCaption = true))
        assertEquals(
            1,
            refs.size,
            "A small image WITH a Figure caption must be KEPT (caption protects). Got: $refs",
        )
        val ref = refs.first()
        assertNotNull(ref.altText, "Kept captioned fragment should carry the caption as alt-text. Ref: $ref")
        assertTrue(
            ref.altText!!.contains("Figure 1", ignoreCase = true),
            "alt-text should be the 'Figure 1:' caption line. Was: '${ref.altText}'",
        )
    }

    @Test
    fun `large caption-less image is kept (size protects)`() {
        val refs = extractImageRefs(buildPdf(imgW = 120, imgH = 120, withCaption = false))
        assertEquals(
            1,
            refs.size,
            "A large (120×120) image must be KEPT even with no caption (size protects). Got: $refs",
        )
    }
}
