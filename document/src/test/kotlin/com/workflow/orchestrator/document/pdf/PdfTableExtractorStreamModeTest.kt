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
import java.nio.file.Paths

/**
 * Stream-mode (borderless / whitespace-aligned) table extraction tests for [PdfTableExtractor].
 *
 * The lattice algorithm only finds ruled (line-bounded) tables. Spec PDFs frequently lay out
 * tables purely with column whitespace — e.g. NIST 800-63B Table 4-1. With lattice-only those
 * grids vanish (only the caption survives as prose). Stream mode recovers them, at the cost of
 * a phantom-table risk on multi-column PROSE pages — which the strengthened phantom guards
 * must reject.
 */
class PdfTableExtractorStreamModeTest {

    private fun fixture(name: String) =
        Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    // ── A. Borderless table: lattice finds NOTHING, stream mode recovers the grid ──

    @Test
    fun `borderless whitespace-aligned table is dropped by lattice-only`() {
        val pdf = writeTempPdf(buildBorderlessTablePdf())
        try {
            val tables = PdfTableExtractor(enableStreamMode = false).extract(pdf)
            // Lattice keys on rulings; a ruling-free whitespace grid yields no lattice table.
            assertTrue(
                tables.isEmpty(),
                "Lattice-only must find no table in a borderless (ruling-free) PDF; got " +
                    tables.map { it.block.headers },
            )
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    @Test
    fun `borderless whitespace-aligned table is recovered by stream mode`() {
        val pdf = writeTempPdf(buildBorderlessTablePdf())
        try {
            val tables = PdfTableExtractor(enableStreamMode = true).extract(pdf)
            assertEquals(1, tables.size, "Stream mode must recover exactly 1 borderless table")

            val t = tables[0].block
            assertEquals(
                listOf("Req", "AAL1", "AAL2", "AAL3"),
                t.headers,
                "Borderless table headers must be [Req, AAL1, AAL2, AAL3]; got ${t.headers}",
            )
            assertEquals(3, t.rows.size, "Borderless table must have 3 data rows; got ${t.rows.size}")
            assertEquals(
                listOf("MFA", "No", "Yes", "Yes"),
                t.rows[0],
                "First data row must be [MFA, No, Yes, Yes]; got ${t.rows[0]}",
            )
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    // ── B. Multi-column PROSE must NOT sprout a phantom table under stream mode ──

    @Test
    fun `multi-column prose PDF yields no phantom table under stream mode`() {
        // tabula-multi-column.pdf is a ragged multi-column numeric layout (prose-like), the
        // documented stream-mode phantom risk. Strengthened phantom guards must reject it.
        val tables = PdfTableExtractor(enableStreamMode = true).extract(fixture("tabula-multi-column.pdf"))
        assertEquals(
            0,
            tables.size,
            "Multi-column prose must not produce a phantom table under stream mode; got " +
                tables.map { it.block.headers },
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun writeTempPdf(bytes: ByteArray): Path {
        val f = Files.createTempFile("borderless-table-", ".pdf")
        Files.write(f, bytes)
        return f
    }

    /**
     * Builds a one-page PDF with a single whitespace-aligned (borderless) table:
     *
     * ```
     * Req      AAL1   AAL2   AAL3
     * MFA      No     Yes    Yes
     * Reauth   30d    12h    12h
     * Records  1y     1y     1y
     * ```
     *
     * No rulings are drawn — columns are aligned purely by x-position, exactly as a
     * spec PDF (e.g. NIST 800-63B Table 4-1) lays out a borderless grid. The grid is
     * fully populated (every cell filled in every row) which is the structural hallmark
     * that separates it from a ragged multi-column prose layout.
     */
    private fun buildBorderlessTablePdf(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        // Column x-positions (PDF points). Wide gaps so Tabula's stream algorithm splits all
        // four columns; the first column's values are kept short so they don't bleed into col 2.
        val colX = floatArrayOf(72f, 200f, 300f, 400f)
        val rows = listOf(
            listOf("Req", "AAL1", "AAL2", "AAL3"),
            listOf("MFA", "No", "Yes", "Yes"),
            listOf("Reauth", "30d", "12h", "12h"),
            listOf("Records", "1y", "1y", "1y"),
        )

        PDPageContentStream(doc, page).use { cs ->
            var y = 690f
            for (row in rows) {
                for (c in row.indices) {
                    cs.beginText()
                    cs.setFont(font, 11f)
                    cs.newLineAtOffset(colX[c], y)
                    cs.showText(row[c])
                    cs.endText()
                }
                y -= 26f
            }
        }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }
}
