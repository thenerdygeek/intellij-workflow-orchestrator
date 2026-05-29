package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock
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
 * Continuation-merge accuracy tests for [PdfTableExtractor.mergeContinuations].
 *
 * Reproduces the agent-reported limitation: tables that span page boundaries (e.g. a spec's
 * "Table 45. Fare Parameters" continuing as "Table 45 ...continued" on the next page, or a
 * silent split with the header repeated) must be stitched into ONE complete table — all rows
 * concatenated in order, the repeated header dropped — not returned as N separate fragments.
 *
 * Two layers are exercised:
 *  - **Literal "(continued)" caption** is driven through [PdfTableExtractor.mergeContinuations]
 *    directly with hand-built [PositionedBlock]s, because the Tabula converter does not attach a
 *    caption (captions live in the prose stream), so an end-to-end PDF cannot carry one.
 *  - **Silent split** (the common case — no "continued" marker) and the **anti-fusion guard**
 *    (two genuinely distinct tables must NOT be fused) run end-to-end through real in-memory PDFs.
 */
class PdfTableExtractorContinuationMergeTest {

    // ── (a) Literal "(continued)" caption → ONE merged table, header once ──

    @Test
    fun `literal Table N continued caption merges fragments into one table`() {
        val parent = PositionedBlock(
            page = 1,
            top = 500.0,
            bottom = 720.0,
            block = DocumentBlock.Table(
                headers = listOf("Code", "Name", "Value"),
                rows = listOf(listOf("A", "Alpha", "1"), listOf("B", "Beta", "2")),
                caption = "Table 45. Fare Parameters",
            ),
        )
        // Continuation page: same caption marked "(continued)", DIFFERENT first row (no repeated
        // header) so only the literal-caption signal can drive the merge.
        val cont = PositionedBlock(
            page = 2,
            top = 80.0,
            bottom = 300.0,
            block = DocumentBlock.Table(
                headers = listOf("C", "Gamma", "3"),
                rows = listOf(listOf("D", "Delta", "4")),
                caption = "Table 45. Fare Parameters (continued)",
            ),
        )

        val merged = PdfTableExtractor().mergeContinuations(listOf(parent, cont))

        assertEquals(1, merged.size, "literal-continued fragments must merge into 1 table; got ${merged.size}")
        val t = merged[0].block
        assertEquals(listOf("Code", "Name", "Value"), t.headers, "parent headers must be kept")
        assertEquals(
            listOf(
                listOf("A", "Alpha", "1"),
                listOf("B", "Beta", "2"),
                listOf("C", "Gamma", "3"),
                listOf("D", "Delta", "4"),
            ),
            t.rows,
            "all rows from both fragments must be concatenated in order",
        )
        assertEquals(1, merged[0].page, "merged block keeps the parent's start page")
        assertEquals(300.0, merged[0].bottom, "merged block must extend to the continuation's bottom")
    }

    // ── (b) Silent split — same column count, headerless continuation at page top → merged ──

    @Test
    fun `silent split with headerless continuation merges end-to-end into one table`() {
        val pdf = writeTempPdf(buildSilentSplitTwoPage())
        try {
            val tables = PdfTableExtractor().extract(pdf)
            assertEquals(
                1,
                tables.size,
                "silent-split table must merge into 1 table; got ${tables.size}: ${tables.map { it.block.headers }}",
            )
            val t = tables[0].block
            assertEquals(listOf("Sym", "Type", "Desc"), t.headers, "header from page 1 must be kept; got ${t.headers}")
            // Page 1 had 2 data rows; page 2 continuation had 2 more data rows (no repeated header).
            assertEquals(4, t.rows.size, "all 4 data rows must be concatenated; got ${t.rows}")
            assertTrue(t.rows.any { it.contains("clk") }, "page-1 data must be present: ${t.rows}")
            assertTrue(t.rows.any { it.contains("result") }, "page-2 continuation data must be present: ${t.rows}")
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    // ── (c) GUARD: two genuinely distinct tables must NOT be fused ──

    @Test
    fun `two distinct tables with different headers on consecutive pages are not fused`() {
        val pdf = writeTempPdf(buildTwoDistinctTablesTwoPage())
        try {
            val tables = PdfTableExtractor().extract(pdf)
            assertEquals(
                2,
                tables.size,
                "two distinct tables must stay separate; got ${tables.size}: ${tables.map { it.block.headers }}",
            )
            assertEquals(listOf("Sym", "Type", "Desc"), tables[0].block.headers)
            assertEquals(listOf("Code", "Status", "Note"), tables[1].block.headers)
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    // ── (c2) GUARD: different column counts on consecutive pages are not fused ──

    @Test
    fun `tables with different column counts on consecutive pages are not fused`() {
        val pdf = writeTempPdf(buildDifferentColumnCountsTwoPage())
        try {
            val tables = PdfTableExtractor().extract(pdf)
            assertEquals(
                2,
                tables.size,
                "tables of differing column count must stay separate; got ${tables.size}: " +
                    tables.map { it.block.headers },
            )
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Page 1: a ruled 3-col table with header [Sym|Type|Desc] + 2 data rows, sitting LOW on the
     * page. Page 2: a ruled 3-col table at the TOP of the page with NO repeated header and whose
     * first row is a row-spanning continuation — its leading cells are BLANK (the real DICOM
     * silent-split signal: Tabula's first row on a continuation page is blank-dominant, never a
     * fresh, fully-populated header). The two must merge into one 3-col table.
     */
    private fun buildSilentSplitTwoPage(): ByteArray {
        val doc = PDDocument()
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        // Page 1 — table near the BOTTOM (rows continue onto page 2).
        val p1 = PDPage(PDRectangle.LETTER)
        doc.addPage(p1)
        PDPageContentStream(doc, p1).use { cs ->
            drawRuledTable(
                cs, font,
                xs = floatArrayOf(72f, 220f, 360f, 540f),
                ys = floatArrayOf(180f, 158f, 136f, 114f), // header + 2 data rows
                rows = listOf(
                    listOf("Sym", "Type", "Desc"),
                    listOf("clk", "in", "clock"),
                    listOf("en", "in", "enable"),
                ),
            )
        }

        // Page 2 — continuation at the TOP. First row's leading cells are blank (blank-dominant
        // continuation header), the hallmark of a real silent split rather than a fresh table.
        val p2 = PDPage(PDRectangle.LETTER)
        doc.addPage(p2)
        val ph = PDRectangle.LETTER.height
        PDPageContentStream(doc, p2).use { cs ->
            drawRuledTable(
                cs, font,
                xs = floatArrayOf(72f, 220f, 360f, 540f),
                ys = floatArrayOf(ph - 50f, ph - 72f, ph - 94f), // 2 data rows, no header
                rows = listOf(
                    listOf("", "", "reset"),   // blank-dominant continuation first row
                    listOf("out", "out", "result"),
                ),
            )
        }

        return save(doc)
    }

    /**
     * Page 1: ruled 3-col table [Sym|Type|Desc]. Page 2: a DIFFERENT ruled 3-col table at the top
     * with its OWN fully-populated header [Code|Status|Note]. Same column count, adjacent, top of
     * page — but the populated, differing header marks it a distinct table, so they must NOT fuse.
     */
    private fun buildTwoDistinctTablesTwoPage(): ByteArray {
        val doc = PDDocument()
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        val p1 = PDPage(PDRectangle.LETTER)
        doc.addPage(p1)
        PDPageContentStream(doc, p1).use { cs ->
            drawRuledTable(
                cs, font,
                xs = floatArrayOf(72f, 220f, 360f, 540f),
                ys = floatArrayOf(180f, 158f, 136f, 114f),
                rows = listOf(
                    listOf("Sym", "Type", "Desc"),
                    listOf("clk", "in", "clock"),
                    listOf("en", "in", "enable"),
                ),
            )
        }

        val p2 = PDPage(PDRectangle.LETTER)
        doc.addPage(p2)
        val ph = PDRectangle.LETTER.height
        PDPageContentStream(doc, p2).use { cs ->
            drawRuledTable(
                cs, font,
                xs = floatArrayOf(72f, 220f, 360f, 540f),
                ys = floatArrayOf(ph - 50f, ph - 72f, ph - 94f),
                rows = listOf(
                    listOf("Code", "Status", "Note"), // fully-populated DIFFERENT header
                    listOf("E1", "open", "first"),
                    listOf("E2", "closed", "second"),
                ),
            )
        }

        return save(doc)
    }

    /**
     * Page 1: ruled 3-col table. Page 2: ruled 4-col table at the top. Different column counts →
     * never a continuation.
     */
    private fun buildDifferentColumnCountsTwoPage(): ByteArray {
        val doc = PDDocument()
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        val p1 = PDPage(PDRectangle.LETTER)
        doc.addPage(p1)
        PDPageContentStream(doc, p1).use { cs ->
            drawRuledTable(
                cs, font,
                xs = floatArrayOf(72f, 220f, 360f, 540f),
                ys = floatArrayOf(180f, 158f, 136f, 114f),
                rows = listOf(
                    listOf("Sym", "Type", "Desc"),
                    listOf("clk", "in", "clock"),
                    listOf("en", "in", "enable"),
                ),
            )
        }

        val p2 = PDPage(PDRectangle.LETTER)
        doc.addPage(p2)
        val ph = PDRectangle.LETTER.height
        PDPageContentStream(doc, p2).use { cs ->
            drawRuledTable(
                cs, font,
                xs = floatArrayOf(72f, 180f, 290f, 400f, 540f), // 4 columns
                ys = floatArrayOf(ph - 50f, ph - 72f, ph - 94f),
                rows = listOf(
                    listOf("A", "B", "C", "D"),
                    listOf("1", "2", "3", "4"),
                    listOf("5", "6", "7", "8"),
                ),
            )
        }

        return save(doc)
    }

    /**
     * Draws a fully-ruled table. [xs] are the vertical-ruling x-coordinates (N+1 for N columns);
     * [ys] are the horizontal-ruling y-coordinates top→bottom (M+1 for M rows). [rows] supplies
     * the text for each of the M cell bands, left→right.
     */
    private fun drawRuledTable(
        cs: PDPageContentStream,
        font: PDType1Font,
        xs: FloatArray,
        ys: FloatArray,
        rows: List<List<String>>,
    ) {
        val left = xs.first()
        val right = xs.last()
        val top = ys.first()
        val bottom = ys.last()
        cs.setLineWidth(0.7f)
        for (y in ys) { cs.moveTo(left, y); cs.lineTo(right, y); cs.stroke() }
        for (x in xs) { cs.moveTo(x, bottom); cs.lineTo(x, top); cs.stroke() }
        for (r in rows.indices) {
            val bandTop = ys[r]
            val textY = bandTop - 16f
            val cells = rows[r]
            for (c in cells.indices) {
                drawText(cs, font, cells[c], xs[c] + 4f, textY)
            }
        }
    }

    private fun drawText(cs: PDPageContentStream, font: PDType1Font, text: String, x: Float, y: Float) {
        if (text.isEmpty()) return
        cs.beginText()
        cs.setFont(font, 11f)
        cs.newLineAtOffset(x, y)
        cs.showText(text)
        cs.endText()
    }

    private fun save(doc: PDDocument): ByteArray {
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun writeTempPdf(bytes: ByteArray): Path {
        val f = Files.createTempFile("continuation-merge-", ".pdf")
        Files.write(f, bytes)
        return f
    }
}
