package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.normaliseRow
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.apache.poi.xwpf.usermodel.XWPFTableCell
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge

/**
 * Merge-aware table visitor.
 *
 * For each [XWPFTable]:
 * - Empty rows → no block.
 * - First row's cells become `headers`; subsequent rows are normalised to header arity
 *   via the existing `normaliseRow(...)` helper in the `:document` module.
 * - Empty header row → no block.
 * - Cells with `w:vMerge` (val omitted or `val="continue"`) inherit their value from
 *   the most recent `w:vMerge val="restart"` cell in the same column.  Previously such
 *   cells emitted as empty strings, breaking grouped tables where the first column is
 *   merged across rows (Phase 3, Task 6).
 */
class DefaultTableVisitor : TableVisitor {

    override fun visit(table: XWPFTable, doc: XWPFDocument): List<DocumentBlock> {
        val tableRows = table.rows
        if (tableRows.isEmpty()) return emptyList()

        val headerCells = tableRows[0].tableCells
        val headers = headerCells.map { it.text.trim() }
        if (headers.isEmpty()) return emptyList()

        // Track the "current restart value" per column index for vertical-merge propagation.
        // The header row itself can't have a vMerge that propagates down because it's the
        // first row, but defensive: still record its values as the starting point.
        val verticalMergeValues = MutableList(headers.size) { col -> headers.getOrNull(col).orEmpty() }

        val dataRows = tableRows.drop(1).map { row ->
            val rawCells = row.tableCells.map { it.text.trim() }
            val resolved = (0 until headers.size).map { col ->
                val cell = row.tableCells.getOrNull(col)
                if (cell == null) {
                    // Row has fewer cells than headers — same behaviour as before, blank pad.
                    return@map ""
                }
                val mergeKind = verticalMergeKind(cell)
                when (mergeKind) {
                    VerticalMergeKind.RESTART -> {
                        val value = rawCells.getOrNull(col).orEmpty()
                        verticalMergeValues[col] = value
                        value
                    }
                    VerticalMergeKind.CONTINUE -> {
                        // Inherit from the most recent restart in this column.
                        verticalMergeValues.getOrNull(col).orEmpty()
                    }
                    VerticalMergeKind.NONE -> {
                        // No vertical merge on this cell — use its own text, and reset the
                        // tracking value so a future restart starts fresh.
                        val value = rawCells.getOrNull(col).orEmpty()
                        verticalMergeValues[col] = value
                        value
                    }
                }
            }
            normaliseRow(resolved, headers.size)
        }

        return listOf(DocumentBlock.Table(headers, dataRows))
    }

    private fun verticalMergeKind(cell: XWPFTableCell): VerticalMergeKind {
        val tcPr = cell.ctTc.tcPr ?: return VerticalMergeKind.NONE
        val vMerge = tcPr.vMerge ?: return VerticalMergeKind.NONE
        // isSetVal() == false means <w:vMerge/> with no val attribute → treat as CONTINUE.
        if (!vMerge.isSetVal) return VerticalMergeKind.CONTINUE
        return when (vMerge.`val`) {
            STMerge.RESTART -> VerticalMergeKind.RESTART
            STMerge.CONTINUE -> VerticalMergeKind.CONTINUE
            else -> VerticalMergeKind.NONE
        }
    }

    private enum class VerticalMergeKind { NONE, RESTART, CONTINUE }
}
