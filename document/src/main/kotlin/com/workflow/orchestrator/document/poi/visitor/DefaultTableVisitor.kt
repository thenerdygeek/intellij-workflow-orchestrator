package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.normaliseRow
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFTable

/**
 * Ports the current `DocxTableExtractor.tableToBlock` logic verbatim.
 *
 * For each [XWPFTable]:
 * - Empty rows → no block.
 * - First row's cells become `headers`; subsequent rows are normalised to header arity
 *   via the existing `normaliseRow(...)` helper in the `:document` module.
 * - Empty header row → no block.
 *
 * Behaviour is byte-for-byte identical to pre-refactor; regression-pinned by
 * `DocxVisitorChainTest` (Task 18) and the existing `DocxTableExtractorTest`.
 */
class DefaultTableVisitor : TableVisitor {

    override fun visit(table: XWPFTable, doc: XWPFDocument): List<DocumentBlock> {
        val tableRows = table.rows
        if (tableRows.isEmpty()) return emptyList()

        val headerCells = tableRows[0].tableCells
        val headers = headerCells.map { it.text.trim() }
        if (headers.isEmpty()) return emptyList()

        val dataRows = tableRows.drop(1).map { row ->
            normaliseRow(row.tableCells.map { it.text.trim() }, headers.size)
        }

        return listOf(DocumentBlock.Table(headers, dataRows))
    }
}
