package com.workflow.orchestrator.document.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression tests for **T-1 grouped-header sub-case: two-row (spanning) table headers**.
 *
 * ## Root cause
 * Some ruled tables carry a TWO-ROW header: a top "group" row whose cells span several
 * sub-columns (a non-empty group label followed by empty continuation cells), and a second
 * "leaf" row that names each sub-column. Examples: the arxiv "Attention Is All You Need"
 * Table 2 (`BLEU` / `Training Cost (FLOPs)` group labels spanning `EN-DE | EN-FR` leaves) and
 * the Fed SCF income/net-worth tables (`Median income` / `Mean income` spanning
 * `2019 | 2022 | Percent change`).
 *
 * Treating only Tabula row 0 as the header destroys this structure: the group labels and the
 * leaf labels land on two separate rows, the leaf labels misalign with the data, and the group
 * label's span is lost. The converter must detect the grouped header and flatten it to a SINGLE
 * header row of **composite leaf headers** (`<group> <leaf>`), aligned to the data columns.
 *
 * These fixtures reproduce the clean, contained 2-row-header case with a controlled ruled table.
 */
class PdfTableExtractorGroupedHeaderTest {

    // ── T-1 grouped: a clean 2-row spanning header is flattened to composite leaf headers ──

    @Test
    fun `two-row grouped header is flattened to composite leaf headers aligned to data`() {
        val pdf = writeTempPdf(buildRuledTableWithGroupedHeader())
        try {
            val tables = PdfTableExtractor().extract(pdf)
            assertEquals(1, tables.size, "Expected exactly 1 ruled table; got ${tables.map { it.block.headers }}")
            val t = tables[0].block

            // Five composite leaf headers, aligned to the five data columns.
            assertEquals(
                listOf("Model", "BLEU EN-DE", "BLEU EN-FR", "Cost EN-DE", "Cost EN-FR"),
                t.headers,
                "Grouped header must flatten to composite leaf headers; got ${t.headers}",
            )

            // The leaf header row must NOT survive as a phantom data row.
            assertTrue(
                t.rows.none { row -> row.contains("EN-DE") || row.contains("EN-FR") },
                "Leaf-header row leaked into the data rows: ${t.rows}",
            )

            // Data row width must match the composite header width, and values must align.
            val gnmt = t.rows.firstOrNull { it.firstOrNull() == "GNMT" }
            assertTrue(gnmt != null, "Expected a 'GNMT' data row; rows=${t.rows}")
            assertEquals(5, gnmt!!.size, "Data row width must match the 5 composite headers")
            assertEquals(listOf("GNMT", "24.6", "39.92", "2.3", "1.4"), gnmt)
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    // ── GUARD: a normal single-row-header table is UNCHANGED ──

    @Test
    fun `normal single-row header table is not altered by grouped-header detection`() {
        val pdf = writeTempPdf(buildNormalSingleHeaderTable())
        try {
            val tables = PdfTableExtractor().extract(pdf)
            assertEquals(1, tables.size, "Expected exactly 1 ruled table; got ${tables.size}")
            val t = tables[0].block

            assertEquals(
                listOf("Label", "Number", "Note"),
                t.headers,
                "Single-row header must be unchanged; got ${t.headers}",
            )
            // The first data row must remain a data row (not consumed as a leaf-header row).
            assertEquals(2, t.rows.size, "Both data rows must survive; rows=${t.rows}")
            assertEquals(listOf("Withdrawn", "100", "foo"), t.rows[0])
            assertEquals(listOf("Active", "200", "bar"), t.rows[1])
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a fully-ruled 5-column table with a TWO-ROW grouped header mirroring arxiv Table 2:
     *
     * ```
     * | Model | BLEU          | Training Cost   |
     * |       | EN-DE | EN-FR | EN-DE  | EN-FR  |
     * | GNMT  | 24.6  | 39.92 | 2.3    | 1.4    |
     * | MoE   | 26.03 | 40.56 | 2.0    | 1.2    |
     * ```
     *
     * The lattice grid has 5 leaf columns. The group row's `BLEU` text sits in the FIRST of its
     * two spanned cells (col 1), leaving col 2 empty; `Cost` sits in col 3, leaving col 4 empty.
     * The leaf row leaves col 0 (under `Model`) empty and names cols 1..4.
     */
    private fun buildRuledTableWithGroupedHeader(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        // Five leaf columns → six vertical rulings.
        val x = floatArrayOf(72f, 150f, 220f, 290f, 370f, 450f)
        val left = x.first()
        val right = x.last()

        // Three horizontal row bands: group header, leaf header, then two data rows.
        val top = 700f
        val groupRule = 680f
        val leafRule = 660f
        val data1Rule = 640f
        val bottom = 620f

        PDPageContentStream(doc, page).use { cs ->
            cs.setLineWidth(0.7f)
            for (y in listOf(top, groupRule, leafRule, data1Rule, bottom)) {
                cs.moveTo(left, y); cs.lineTo(right, y); cs.stroke()
            }
            for (xi in x) {
                cs.moveTo(xi, bottom); cs.lineTo(xi, top); cs.stroke()
            }

            // Group row (band [groupRule..top]): Model | BLEU (span col1+2) | Cost (span col3+4).
            drawText(cs, font, "Model", x[0] + 3f, 686f)
            drawText(cs, font, "BLEU", x[1] + 3f, 686f)
            drawText(cs, font, "Cost", x[3] + 3f, 686f)

            // Leaf row (band [leafRule..groupRule]): empty | EN-DE | EN-FR | EN-DE | EN-FR.
            drawText(cs, font, "EN-DE", x[1] + 3f, 666f)
            drawText(cs, font, "EN-FR", x[2] + 3f, 666f)
            drawText(cs, font, "EN-DE", x[3] + 3f, 666f)
            drawText(cs, font, "EN-FR", x[4] + 3f, 666f)

            // Data row 1 (band [data1Rule..leafRule]).
            drawText(cs, font, "GNMT", x[0] + 3f, 646f)
            drawText(cs, font, "24.6", x[1] + 3f, 646f)
            drawText(cs, font, "39.92", x[2] + 3f, 646f)
            drawText(cs, font, "2.3", x[3] + 3f, 646f)
            drawText(cs, font, "1.4", x[4] + 3f, 646f)

            // Data row 2 (band [bottom..data1Rule]).
            drawText(cs, font, "MoE", x[0] + 3f, 626f)
            drawText(cs, font, "26.03", x[1] + 3f, 626f)
            drawText(cs, font, "40.56", x[2] + 3f, 626f)
            drawText(cs, font, "2.0", x[3] + 3f, 626f)
            drawText(cs, font, "1.2", x[4] + 3f, 626f)
        }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    /**
     * A plain 3-column ruled table with a SINGLE header row and two data rows — the negative
     * control for grouped-header detection. The header row is fully filled (no spanning gaps),
     * so the detector must leave it untouched.
     */
    private fun buildNormalSingleHeaderTable(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        val x = floatArrayOf(72f, 220f, 380f, 520f)
        val left = x.first()
        val right = x.last()
        val top = 700f
        val headerRule = 678f
        val row1Rule = 656f
        val bottom = 634f

        PDPageContentStream(doc, page).use { cs ->
            cs.setLineWidth(0.7f)
            for (y in listOf(top, headerRule, row1Rule, bottom)) {
                cs.moveTo(left, y); cs.lineTo(right, y); cs.stroke()
            }
            for (xi in x) {
                cs.moveTo(xi, bottom); cs.lineTo(xi, top); cs.stroke()
            }
            drawText(cs, font, "Label", x[0] + 4f, 684f)
            drawText(cs, font, "Number", x[1] + 4f, 684f)
            drawText(cs, font, "Note", x[2] + 4f, 684f)

            drawText(cs, font, "Withdrawn", x[0] + 4f, 662f)
            drawText(cs, font, "100", x[1] + 4f, 662f)
            drawText(cs, font, "foo", x[2] + 4f, 662f)

            drawText(cs, font, "Active", x[0] + 4f, 640f)
            drawText(cs, font, "200", x[1] + 4f, 640f)
            drawText(cs, font, "bar", x[2] + 4f, 640f)
        }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun writeTempPdf(bytes: ByteArray): Path {
        val f = Files.createTempFile("ruled-grouped-", ".pdf")
        Files.write(f, bytes)
        return f
    }

    private fun drawText(cs: PDPageContentStream, font: PDType1Font, text: String, x: Float, y: Float) {
        cs.beginText()
        cs.setFont(font, 11f)
        cs.newLineAtOffset(x, y)
        cs.showText(text)
        cs.endText()
    }
}
