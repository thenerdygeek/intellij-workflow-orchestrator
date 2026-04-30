package com.workflow.orchestrator.document

/**
 * Pads or truncates [row] to exactly [targetSize] cells.
 *
 * `DocumentBlock.Table` requires every data row to have the same cell count as its headers.
 * Each format-specific extractor (Tabula PDF, POI DOCX, Tika SAX) produces row arrays whose
 * width may not match the table's header row — Tabula in particular often emits short final
 * rows for partial cells and sometimes oversized rows when adjacent cells share borders.
 * This helper enforces the invariant uniformly: extra cells are dropped from the right,
 * missing cells are added as empty strings on the right.
 */
internal fun normaliseRow(row: List<String>, targetSize: Int): List<String> = when {
    row.size == targetSize -> row
    row.size < targetSize -> row + List(targetSize - row.size) { "" }
    else -> row.take(targetSize)
}
