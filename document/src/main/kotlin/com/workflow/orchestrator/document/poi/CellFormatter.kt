package com.workflow.orchestrator.document.poi

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FormulaEvaluator
import org.apache.poi.xssf.usermodel.XSSFCell
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Stateless helper that converts a POI [Cell] to a clean [String].
 *
 * ## Conversion rules
 *
 * | Cell state / type | Output |
 * |---|---|
 * | `null` cell | `""` |
 * | `STRING` | `cell.stringCellValue` |
 * | `NUMERIC`, date-formatted | ISO-8601 date (`yyyy-MM-dd`) for whole-day values; ISO-8601 datetime for values with a non-zero time component |
 * | `NUMERIC`, plain | Integer string if the value is mathematically integral (e.g. `92` not `92.0`); otherwise `Double.toString()` |
 * | `BOOLEAN` | `"true"` or `"false"` |
 * | `FORMULA` | Evaluates via [FormulaEvaluator]; falls back to `cell.cellFormula` on error |
 * | `BLANK` / `ERROR` | `""` |
 *
 * Trailing whitespace is trimmed from every result.
 */
object CellFormatter {

    /**
     * Converts [cell] to a [String] value.
     *
     * @param cell      The POI cell to convert. Returns `""` when `null`.
     * @param evaluator Optional formula evaluator. When non-null, formula cells are evaluated
     *                  rather than returning the raw formula string. A missing evaluator causes
     *                  formula cells to fall back to `cell.cellFormula` immediately.
     */
    fun toCellString(cell: Cell?, evaluator: FormulaEvaluator?): String {
        if (cell == null) return ""

        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trimEnd()
            CellType.NUMERIC -> numericToString(cell)
            CellType.BOOLEAN -> if (cell.booleanCellValue) "true" else "false"
            CellType.FORMULA -> formulaToString(cell, evaluator)
            CellType.BLANK, CellType.ERROR -> ""
            else -> ""
        }.trimEnd()
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun numericToString(cell: Cell): String {
        return if (DateUtil.isCellDateFormatted(cell)) {
            dateToString(cell)
        } else {
            numericValueToString(cell.numericCellValue)
        }
    }

    private fun dateToString(cell: Cell): String {
        val localDateTime: LocalDateTime = cell.localDateTimeCellValue
            ?: return ""
        // If the time component is exactly midnight, emit just the date.
        return if (localDateTime.hour == 0 && localDateTime.minute == 0 &&
            localDateTime.second == 0 && localDateTime.nano == 0
        ) {
            localDateTime.toLocalDate().toString()   // ISO-8601: yyyy-MM-dd
        } else {
            localDateTime.toString()                 // ISO-8601: yyyy-MM-ddTHH:mm:ss[.nanos]
        }
    }

    private fun formulaToString(cell: Cell, evaluator: FormulaEvaluator?): String {
        // P-2 [data fidelity]: prefer the cached formula result the authoring tool wrote into
        // the file (`<v>…</v>`) over POI's own re-evaluation. Re-evaluating diverges from the
        // source whenever the workbook references cells/sheets the writer did not materialise
        // (e.g. excelize emits `=SUM(C1:D1)` with cached `1` even when C1/D1 are absent — POI
        // would recompute `0` and silently corrupt the value). The cached value is the ground
        // truth a spreadsheet reader sees on open, so report it faithfully.
        cachedFormulaResult(cell)?.let { return it }

        // No usable cached result on the cell — fall back to evaluation.
        if (evaluator == null) return cell.cellFormula

        return try {
            val result = evaluator.evaluate(cell) ?: return ""
            when (result.cellType) {
                CellType.STRING -> result.stringValue.trimEnd()
                CellType.NUMERIC -> numericValueToString(result.numberValue)
                CellType.BOOLEAN -> if (result.booleanValue) "true" else "false"
                CellType.BLANK, CellType.ERROR -> ""
                else -> ""
            }
        } catch (e: Exception) {
            // Formula evaluation failed (e.g. circular reference, missing function).
            // Fall back to the raw formula string so the caller still gets something useful.
            cell.cellFormula
        }
    }

    /**
     * Reads the cached result the authoring tool stored in the FILE for a formula [cell],
     * WITHOUT triggering re-evaluation. Returns `null` when the cell has no genuine cached
     * value, so the caller falls back to live evaluation.
     *
     * ## Why gate on a real `<v>` element
     *
     * POI synthesises a placeholder cached result (numeric `0`) for any formula set
     * programmatically via `setCellFormula(...)` that was never evaluated — but such a cell has
     * NO `<v>` element in its XML. A file authored with `fullCalcOnLoad`/recompute-on-open
     * (e.g. some LibreOffice / generator output) instead writes an EMPTY `<v></v>`, signalling
     * "recompute me". In both cases the cached value is meaningless and `numericCellValue` would
     * read `0`. We therefore only honour the cached value when the `<v>` element is both present
     * AND non-blank. That is precisely the calcchain corruption (P-2): the file stores a real
     * `<v>1</v>` but POI's re-evaluation of `=SUM(C1:D1)` (C1/D1 absent) yields `0` — so we report
     * the faithful `1`. A `<v></v>` recompute marker (e.g. the COUNTIF fixture) still evaluates.
     *
     * Mirrors the type dispatch of [toCellString], sourcing each value from the cached accessors
     * (`numericCellValue` / `stringCellValue` / `booleanCellValue` return the cached result for
     * formula cells in POI).
     */
    private fun cachedFormulaResult(cell: Cell): String? {
        // Only trust the cached value when the source XML genuinely carries a NON-BLANK `<v>`.
        // A missing `<v>` (POI-synthesised formula) or an empty `<v></v>` (recompute-on-open
        // marker) both mean "no real cached value" — fall through to live evaluation.
        val ctCell = (cell as? XSSFCell)?.ctCell ?: return null
        if (!ctCell.isSetV || ctCell.v.isNullOrBlank()) return null

        return try {
            when (cell.cachedFormulaResultType) {
                CellType.STRING -> cell.stringCellValue.trimEnd()
                CellType.NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        dateToString(cell)
                    } else {
                        numericValueToString(cell.numericCellValue)
                    }
                }
                CellType.BOOLEAN -> if (cell.booleanCellValue) "true" else "false"
                // No cached value of a reportable type — let the caller re-evaluate.
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Renders a numeric value as an integer string when integral, else `Double.toString()`. */
    private fun numericValueToString(value: Double): String =
        if (value == kotlin.math.floor(value) && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
}
