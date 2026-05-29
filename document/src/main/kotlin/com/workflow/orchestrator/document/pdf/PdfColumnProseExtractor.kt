package com.workflow.orchestrator.document.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripperByArea
import java.awt.geom.Rectangle2D

/**
 * SF-1 — re-extracts a single PDF page that [PdfColumnDetector] classified as two-column, in
 * correct reading order: the **entire left column, then the entire right column**.
 *
 * ## Why this fixes the interleave
 *
 * Plain `PDFTextStripper(sortByPosition=true)` sorts glyphs y-then-x within a tolerance, so two
 * lines at the same y in different columns are emitted left-glyph, right-glyph, left, right … —
 * the bjs-cv22 line-by-line interleave (design §2.1). Splitting the page into a LEFT rectangle and
 * a RIGHT rectangle at the detected gutter and stripping each region independently
 * (`PDFTextStripperByArea`) reads each column top-to-bottom in isolation, so the columns never fuse.
 *
 * This uses the SAME PDFBox text layer as the G-6 link harvester
 * ([PdfMetadataExtractor.extractLinks] also uses `PDFTextStripperByArea`), so the glyphs/whitespace
 * model is identical — no new font/encoding surprises, and the link-splice match rate is preserved.
 */
class PdfColumnProseExtractor {

    /**
     * Extracts [page]'s prose as left-column-then-right-column text, split at [gutterX].
     *
     * @param page    The two-column page (must already be classified 2-col by [PdfColumnDetector]).
     * @param gutterX Gutter x-coordinate in PDF user space (left-origin), from the detector.
     * @return The page's text in correct reading order (left column fully, then right column),
     *         with each column's internal line breaks preserved as `\n`. Empty string on failure.
     */
    fun extractColumns(page: PDPage, gutterX: Double): String {
        val mediaBox = page.mediaBox
        val pageWidth = mediaBox.width
        val pageHeight = mediaBox.height
        val originX = mediaBox.lowerLeftX
        // PDFTextStripperByArea regions are AWT rectangles in TOP-DOWN device space (origin
        // top-left), matching how the library's own examples build link rects (see
        // PdfMetadataExtractor.extractLinks). y=0 is the top of the page; full page height tall.
        val gutter = gutterX.toFloat()
        val leftRect = Rectangle2D.Float(
            originX, 0f,
            (gutter - originX).coerceAtLeast(1f), pageHeight,
        )
        val rightRect = Rectangle2D.Float(
            gutter, 0f,
            (originX + pageWidth - gutter).coerceAtLeast(1f), pageHeight,
        )

        return try {
            // A throwaway single-page doc so the area-strip never mutates the caller's document
            // (mirrors PdfColumnDetector). importPage shares the page COS object read-only.
            PDDocument().use { single ->
                single.importPage(page)
                val onlyPage = single.getPage(0)
                val stripper = PDFTextStripperByArea()
                stripper.sortByPosition = true
                stripper.addRegion(REGION_LEFT, leftRect)
                stripper.addRegion(REGION_RIGHT, rightRect)
                stripper.extractRegions(onlyPage)
                val left = stripper.getTextForRegion(REGION_LEFT)?.trimEnd().orEmpty()
                val right = stripper.getTextForRegion(REGION_RIGHT)?.trimStart().orEmpty()
                listOf(left, right).filter { it.isNotBlank() }.joinToString("\n")
            }
        } catch (_: Exception) {
            ""
        }
    }

    private companion object {
        const val REGION_LEFT = "col_left"
        const val REGION_RIGHT = "col_right"
    }
}
