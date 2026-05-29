package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Data-correctness regression tests for [XlsxTableExtractor] (audit findings P-2 + P-3).
 *
 * P-3: merged-region anchor values were duplicated into EVERY spanned cell, fabricating
 *      data points that do not exist in the source. The anchor value must appear ONLY in
 *      the top-left (anchor) cell; spanned cells must be blank.
 *
 * P-2: a formula cell's cached value (`<v>…</v>` written by the authoring tool) was being
 *      silently overwritten by POI's own re-evaluation. When the referenced cells are absent
 *      the re-evaluation yields a different (wrong) number, so the faithful cached value must
 *      be preferred.
 */
class XlsxDataFidelityTest {

    private val extractor = XlsxTableExtractor()

    // ── P-3: merged-cell anchor must NOT be spread across the span ─────────────

    @Test
    fun `merged region value appears only in anchor cell, spanned cells are blank`() {
        // Build a sheet mirroring xlsx-excelize-mergecell.xlsx:
        //   row0 (header): A,B,C,D
        //   merged block A2:C4 (anchor A2="ANCHOR")
        //   surrounding data so the span is unambiguous.
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            val h = sheet.createRow(0)
            h.createCell(0).setCellValue("A")
            h.createCell(1).setCellValue("B")
            h.createCell(2).setCellValue("C")
            h.createCell(3).setCellValue("D")

            // Rows 1..3 (0-indexed) form the merged block rows.
            val r1 = sheet.createRow(1)
            r1.createCell(0).setCellValue("ANCHOR")  // A2: anchor / top-left
            r1.createCell(3).setCellValue("d2")
            val r2 = sheet.createRow(2)
            r2.createCell(3).setCellValue("d3")
            val r3 = sheet.createRow(3)
            r3.createCell(3).setCellValue("d4")

            // Merge A2:C4 (rows 1-3, cols 0-2) — a 3x3 block, anchor A2.
            sheet.addMergedRegion(CellRangeAddress(1, 3, 0, 2))
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val table = blocks.filterIsInstance<DocumentBlock.Table>().first()

        // Anchor (first data row, first column) holds the value.
        assertEquals("ANCHOR", table.rows[0][0], "Anchor cell A2 should carry the merged value")

        // Every OTHER cell in the merged span must be blank — NOT "ANCHOR".
        val spannedCoords = listOf(
            0 to 1, 0 to 2,           // B2, C2
            1 to 0, 1 to 1, 1 to 2,   // A3, B3, C3
            2 to 0, 2 to 1, 2 to 2,   // A4, B4, C4
        )
        for ((rowIdx, colIdx) in spannedCoords) {
            assertEquals(
                "", table.rows[rowIdx][colIdx],
                "Spanned cell (row=$rowIdx,col=$colIdx) must be blank, not the fabricated anchor value. " +
                    "Full table: ${table.rows}"
            )
        }

        // Sanity: the anchor value must appear exactly once across the whole table body
        // (no fabricated duplicates).
        val anchorCount = table.rows.sumOf { row -> row.count { it == "ANCHOR" } }
        assertEquals(1, anchorCount, "Merged anchor value must appear exactly once, got $anchorCount")
    }

    @Test
    fun `merged ranges are recorded as a compact note so structure is not lost`() {
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            val h = sheet.createRow(0)
            h.createCell(0).setCellValue("A")
            h.createCell(1).setCellValue("B")
            sheet.createRow(1).createCell(0).setCellValue("ANCHOR")
            sheet.addMergedRegion(CellRangeAddress(1, 1, 0, 1)) // A2:B2
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val text = blocks.joinToString("\n") {
            when (it) {
                is DocumentBlock.Paragraph -> it.text
                else -> ""
            }
        }
        assertTrue(
            text.contains("A2:B2"),
            "Expected a merged-ranges note mentioning A2:B2 so the LLM still sees the merge structure. " +
                "Blocks: ${blocks.map { it::class.simpleName }}"
        )
    }

    // ── P-2: formula cached value must be reported faithfully ──────────────────

    @Test
    fun `formula cell with cached value reports the cached value, not a stale re-evaluation`() {
        // Mirror xlsx-excelize-calcchain.xlsx: B1 is =SUM(C1:D1) with cached <v>1</v>,
        // but C1/D1 do not exist so a fresh evaluation yields 0. The cached 1 must win.
        val bytes = buildCalcchainLikeXlsx(cachedValue = 1.0)

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val table = blocks.filterIsInstance<DocumentBlock.Table>().first()

        // The cell holding the cached value of 1 must render "1", not "0".
        val flat = table.rows.flatten()
        assertTrue(
            flat.contains("1"),
            "Expected the cached formula value '1' to be reported faithfully; got cells: $flat"
        )
        assertTrue(
            flat.none { it == "0" } || flat.count { it == "1" } >= 1,
            "The cell with cached <v>1</v> must not be reported as 0; cells: $flat"
        )
    }

    @Test
    fun `formula cell with cached string value reports the cached string`() {
        // Generality check: the cached-value preference must work for strings too, not just ints.
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            sheet.createRow(0).createCell(0).setCellValue("H")
            val cell = sheet.createRow(1).createCell(0)
            cell.cellFormula = "CONCATENATE(\"foo\",\"bar\")"
            // Stash a cached result that differs from a fresh eval would (force cache read path).
            cell.setCellValue("CACHED_STR")
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val flat = blocks.filterIsInstance<DocumentBlock.Table>().first().rows.flatten()
        assertTrue(
            flat.contains("CACHED_STR"),
            "Cached string formula result must be reported; got: $flat"
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds a workbook whose B1 cell is a formula `=SUM(C1:D1)` with an author-supplied
     * cached numeric value, while A1 is a plain literal. C1/D1 are intentionally absent so
     * POI's own re-evaluation would diverge from the cached value.
     */
    private fun buildCalcchainLikeXlsx(cachedValue: Double): ByteArray = buildXlsx { wb ->
        val sheet = wb.createSheet("Sheet1")
        val row = sheet.createRow(0)
        // A1: literal numeric so the row is recognised as a data row by the header walk.
        // We use a header row first so the table body picks up A1/B1 as data.
        // Layout: header row 0 = (h1, h2); data row 1 = (literal, formula-with-cache).
        wb.removeSheetAt(wb.getSheetIndex(sheet))
        val s2 = wb.createSheet("Sheet1")
        val header = s2.createRow(0)
        header.createCell(0).setCellValue("h1")
        header.createCell(1).setCellValue("h2")
        val data = s2.createRow(1)
        data.createCell(0).setCellValue(42.0)
        val formulaCell = data.createCell(1)
        formulaCell.cellFormula = "SUM(C2:D2)"
        // Author-supplied cached result (what excelize / Excel wrote into <v>).
        formulaCell.setCellValue(cachedValue)
        check(formulaCell.cellType == CellType.FORMULA) { "expected FORMULA cell" }
    }

    private fun buildXlsx(build: (XSSFWorkbook) -> Unit): ByteArray {
        val wb = XSSFWorkbook()
        try {
            build(wb)
            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        } finally {
            wb.close()
        }
    }
}
