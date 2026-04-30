package com.workflow.orchestrator.document.poi

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FormulaEvaluator
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
            val value = cell.numericCellValue
            // Render as integer when the value has no fractional component.
            if (value == kotlin.math.floor(value) && !value.isInfinite()) {
                value.toLong().toString()
            } else {
                value.toString()
            }
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
        if (evaluator == null) return cell.cellFormula

        return try {
            val result = evaluator.evaluate(cell) ?: return ""
            when (result.cellType) {
                CellType.STRING -> result.stringValue.trimEnd()
                CellType.NUMERIC -> {
                    val value = result.numberValue
                    if (value == kotlin.math.floor(value) && !value.isInfinite()) {
                        value.toLong().toString()
                    } else {
                        value.toString()
                    }
                }
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
}
