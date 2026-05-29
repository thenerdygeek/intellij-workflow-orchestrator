package com.workflow.orchestrator.document.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression tests for **T-2: leading/trailing glyph clipping in ruled (lattice) tables**.
 *
 * ## Root cause
 * Tabula populates each ruled cell via `Page.getText(cellRect)`, which delegates to
 * `RectangleSpatialIndex.contains()` → `java.awt.geom.Rectangle2D.contains()`. That is a
 * **full-containment** test: a glyph whose bounding box straddles a cell boundary (a column
 * ruling) is contained in *neither* adjacent cell and is silently dropped. In real spec PDFs
 * the last digit of a right-aligned number (BJS `4,558,150` → `4,558,15`) or the first letter
 * of a left-aligned header (NIST 800-63B `Withdrawn` → `ithdrawn`) sits exactly on the ruling
 * and disappears — factually corrupting the data.
 *
 * These fixtures reproduce the defect with a controlled ruled table whose cell text is
 * positioned so a glyph straddles a vertical ruling, then assert the recovered cell text is
 * intact (no leading/trailing character lost).
 */
class PdfTableExtractorCellClipTest {

    // ── T-2a: trailing digit straddling the RIGHT ruling must not be clipped ──

    @Test
    fun `trailing digit straddling a column ruling is not clipped`() {
        val pdf = writeTempPdf(buildRuledTableStraddlingGlyphs())
        try {
            val tables = PdfTableExtractor().extract(pdf)
            assertEquals(1, tables.size, "Expected exactly 1 ruled table; got ${tables.map { it.block.headers }}")
            val t = tables[0].block

            // The number cell must retain every digit (the trailing '0' straddles the ruling).
            val allCells = t.headers + t.rows.flatten()
            assertEquals(
                "4,558,150",
                allCells.firstOrNull { it.matches(Regex("[\\d,]+")) },
                "Trailing '0' of 4,558,150 was clipped at the column ruling; cells=$allCells",
            )
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    // ── T-2b: leading letter straddling the LEFT ruling must not be clipped ──

    @Test
    fun `leading letter straddling the left ruling is not clipped`() {
        val pdf = writeTempPdf(buildRuledTableStraddlingGlyphs())
        try {
            val tables = PdfTableExtractor().extract(pdf)
            val t = tables[0].block
            val allText = (t.headers + t.rows.flatten())
            assertEquals(
                true,
                allText.any { it == "Withdrawn" },
                "Leading 'W' of 'Withdrawn' was clipped at the left ruling; cells=$allText",
            )
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    // ── T-1: ruled table whose first Tabula row is a blank spacer must not vanish ──

    @Test
    fun `ruled table with a leading blank spacer row is not dropped`() {
        // NIST 800-63B Table 4-1 (AAL Summary) is a fully-ruled table whose top header band
        // produces a leading ALL-BLANK Tabula row; the real header ("Requirement | AAL1 | ...")
        // is the second row. tabulaTableToDocumentBlock treated row[0] as the header row, found it
        // entirely blank, and returned null — silently discarding the whole table (it then leaked
        // as prose). The converter must skip leading blank rows and recover the table.
        val pdf = writeTempPdf(buildRuledTableWithLeadingBlankRow())
        try {
            val tables = PdfTableExtractor().extract(pdf)
            assertEquals(1, tables.size, "Table with a leading blank row must still be extracted; got ${tables.size}")
            val t = tables[0].block
            val flat = (t.headers + t.rows.flatten())
            assertEquals(true, flat.any { it == "Requirement" }, "Header 'Requirement' must survive; cells=$flat")
            assertEquals(true, flat.any { it == "MitM" }, "Body label 'MitM' must survive; cells=$flat")
            assertEquals(true, flat.any { it == "Yes" }, "Body value 'Yes' must survive; cells=$flat")
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a ruled table whose top horizontal ruling sits a few points above the header text,
     * so Tabula emits a leading ALL-BLANK row above the real header row (the NIST 800-63B
     * Table 4-1 geometry).
     */
    private fun buildRuledTableWithLeadingBlankRow(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        val left = 72f
        val c1 = 240f
        val right = 400f
        // Four horizontal rulings create three row bands. The TOP band [blankRule..top] is left
        // empty so Tabula emits an all-blank leading row; the real header sits in the next band.
        val top = 700f
        val blankRule = 686f
        val headerRule = 664f
        val row1Rule = 642f
        val bottom = 620f

        PDPageContentStream(doc, page).use { cs ->
            cs.setLineWidth(0.7f)
            for (y in listOf(top, blankRule, headerRule, row1Rule, bottom)) {
                cs.moveTo(left, y); cs.lineTo(right, y); cs.stroke()
            }
            for (x in listOf(left, c1, right)) {
                cs.moveTo(x, bottom); cs.lineTo(x, top); cs.stroke()
            }
            // NOTE: nothing drawn in the top band [blankRule..top] → leading all-blank row.
            // Header text in band [headerRule..blankRule].
            drawText(cs, font, "Requirement", left + 4f, 670f)
            drawText(cs, font, "Value", c1 + 4f, 670f)
            // Data rows.
            drawText(cs, font, "MitM", left + 4f, 648f)
            drawText(cs, font, "Yes", c1 + 4f, 648f)
            drawText(cs, font, "Replay", left + 4f, 626f)
            drawText(cs, font, "No", c1 + 4f, 626f)
        }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun writeTempPdf(bytes: ByteArray): Path {
        val f = Files.createTempFile("ruled-clip-", ".pdf")
        Files.write(f, bytes)
        return f
    }

    /**
     * Builds a one-page ruled table where cell text is deliberately placed so glyphs straddle
     * the vertical rulings — exactly the geometry that triggers Tabula's full-containment clip.
     *
     * Layout (3 columns, rulings at x = 72, 220, 380, 520):
     * ```
     * | Label              | Number    | Note |
     * | Withdrawn          | 4,558,150 | foo  |
     * ```
     * "Withdrawn" starts flush on the left ruling (x=72) so its 'W' straddles it; the number
     * "4,558,150" is positioned so its trailing '0' straddles the ruling at x=380.
     */
    private fun buildRuledTableStraddlingGlyphs(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        val left = 72f
        val c1 = 220f
        val c2 = 380f
        val right = 520f
        val top = 700f
        val midRule = 674f
        val bottom = 648f

        PDPageContentStream(doc, page).use { cs ->
            // Draw the ruling grid (horizontal: top/mid/bottom; vertical: left/c1/c2/right).
            cs.setLineWidth(0.7f)
            for (y in listOf(top, midRule, bottom)) {
                cs.moveTo(left, y); cs.lineTo(right, y); cs.stroke()
            }
            for (x in listOf(left, c1, c2, right)) {
                cs.moveTo(x, bottom); cs.lineTo(x, top); cs.stroke()
            }

            // Header row text.
            drawText(cs, font, "Label", left + 4f, 681f)
            drawText(cs, font, "Number", c1 + 4f, 681f)
            drawText(cs, font, "Note", c2 + 4f, 681f)

            // Data row — place glyphs to straddle rulings:
            // "Withdrawn" begins just left of the left ruling so its 'W' straddles x=72.
            drawText(cs, font, "Withdrawn", left - 3f, 655f)
            // Position "4,558,150" so the trailing '0' box *straddles* the ruling at x=c2 (380):
            // the glyph's center stays just left of the ruling (so it belongs to this cell) but
            // its right edge crosses it — exactly the BJS geometry where full-containment drops it.
            val number = "4,558,150"
            val w = font.getStringWidth(number) / 1000f * 11f
            val lastGlyphW = font.getStringWidth("0") / 1000f * 11f
            // End the string so the last glyph's center sits ~0.3*glyphWidth left of the ruling.
            val numberEnd = c2 + lastGlyphW * 0.2f
            drawText(cs, font, number, numberEnd - w, 655f)
            drawText(cs, font, "foo", c2 + 8f, 655f)
        }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun drawText(cs: PDPageContentStream, font: PDType1Font, text: String, x: Float, y: Float) {
        cs.beginText()
        cs.setFont(font, 11f)
        cs.newLineAtOffset(x, y)
        cs.showText(text)
        cs.endText()
    }
}
