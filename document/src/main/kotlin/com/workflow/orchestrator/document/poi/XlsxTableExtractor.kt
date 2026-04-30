package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.FormulaEvaluator
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream

/**
 * Extracts spreadsheet content from XLSX files into [DocumentBlock] lists via Apache POI.
 *
 * Each sheet becomes a [DocumentBlock.Heading] followed by a [DocumentBlock.Table].
 * Formulas are evaluated. Dates are formatted as ISO-8601 strings. Merged cells have the
 * merged value repeated across all cells in the merge range (row direction only — the spec
 * calls for left-to-right repetition within a row).
 *
 * ## Thread safety
 *
 * **Per-call instantiation only.** `XSSFWorkbook` is NOT thread-safe. Each [extract] call
 * creates and closes its own workbook instance. Never cache the workbook between calls.
 *
 * ## Row iteration
 *
 * Uses `sheet.iterator()` — the streaming iterator that does NOT materialise all rows into
 * memory at once. `sheet.drop(1)` / `sheet.toList()` are explicitly avoided.
 *
 * ## Row cap
 *
 * Each sheet is capped at [MAX_ROWS_PER_SHEET] data rows. If the sheet has more, a
 * [DocumentBlock.Paragraph] truncation marker is appended.
 */
class XlsxTableExtractor {

    init {
        PoiHardening.applyOnce()
    }

    /**
     * Extracts [DocumentBlock] values from an XLSX [stream].
     *
     * @param stream Raw bytes of the `.xlsx` file. The caller is responsible for closing the stream.
     * @return Ordered list of blocks: one [DocumentBlock.Heading] + one [DocumentBlock.Table]
     *         per sheet, in workbook sheet order.
     */
    fun extract(stream: InputStream): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()

        XSSFWorkbook(stream).use { wb ->
            val evaluator: FormulaEvaluator = wb.creationHelper.createFormulaEvaluator()

            for (sheet in wb) {
                val xssfSheet = sheet as? XSSFSheet ?: continue

                blocks += DocumentBlock.Heading(2, sheet.sheetName)

                val rowIter = sheet.iterator()
                if (!rowIter.hasNext()) continue

                // First row → headers
                val headerRow = rowIter.next()
                val lastCellNum = headerRow.lastCellNum.toInt().coerceAtLeast(0)
                val headers = (0 until lastCellNum).map { col ->
                    val cell = headerRow.getCell(col)
                    CellFormatter.toCellString(cellOrMergedValue(cell, col, headerRow, xssfSheet, evaluator), evaluator)
                }

                if (headers.isEmpty()) continue

                // Subsequent rows → data rows
                val rows = mutableListOf<List<String>>()
                var rowsRead = 0

                while (rowIter.hasNext() && rowsRead < MAX_ROWS_PER_SHEET) {
                    val row = rowIter.next()
                    val cells = headers.indices.map { col ->
                        val cell = row.getCell(col)
                        CellFormatter.toCellString(cellOrMergedValue(cell, col, row, xssfSheet, evaluator), evaluator)
                    }
                    rows += cells
                    rowsRead++
                }

                blocks += DocumentBlock.Table(headers, rows)

                if (rowIter.hasNext()) {
                    blocks += DocumentBlock.Paragraph("_(truncated at $MAX_ROWS_PER_SHEET rows)_")
                }
            }
        }

        return blocks
    }

    // ── Merged cell resolution ─────────────────────────────────────────────────

    /**
     * Returns the [Cell] whose value should be used for column [col] in [row].
     *
     * When the cell at ([row].rowNum, [col]) falls inside a merged region, returns the
     * top-left cell of that region so downstream code reads the actual value. This handles
     * horizontal merges (A2:C2 → all three columns show the same value) and the top row of
     * vertical merges (A2:A3 → both rows show A2's value).
     *
     * @param cell     The raw cell at [col] (may be `null` for blank cells inside a merged region).
     * @param col      Zero-based column index within the row.
     * @param row      The POI [Row] that [cell] belongs to.
     * @param sheet    The containing [XSSFSheet] (provides the merged region list).
     * @param evaluator Formula evaluator (passed through for type consistency; not used here).
     * @return The cell whose value should be displayed at ([row].rowNum, [col]).
     */
    private fun cellOrMergedValue(
        cell: Cell?,
        col: Int,
        row: Row,
        sheet: XSSFSheet,
        @Suppress("UNUSED_PARAMETER") evaluator: FormulaEvaluator,
    ): Cell? {
        // Fast path: cell exists and is not blank — no need to check merged regions.
        if (cell != null && cell.cellType != org.apache.poi.ss.usermodel.CellType.BLANK) {
            return cell
        }

        // Check whether (rowNum, col) falls inside a merged region whose top-left cell
        // holds the actual value.
        val rowNum = row.rowNum
        for (region: CellRangeAddress in sheet.mergedRegions) {
            if (region.isInRange(rowNum, col)) {
                // Return the top-left cell of the merged region.
                val firstRow = sheet.getRow(region.firstRow) ?: return cell
                return firstRow.getCell(region.firstColumn) ?: cell
            }
        }

        return cell
    }

    companion object {
        /** Maximum data rows extracted per sheet before emitting a truncation marker. */
        const val MAX_ROWS_PER_SHEET = 50_000
    }
}
