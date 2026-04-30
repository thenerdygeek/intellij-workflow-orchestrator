package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock

/**
 * A [DocumentBlock] annotated with its physical location in a PDF document.
 *
 * Used by [PdfTableExtractor] (Tabula tables) and [PdfProseExtractor] (Tika prose) to carry
 * position information through the merge pipeline. The merge step in [PdfPipeline] sorts
 * all positioned blocks by `(page, top)` to produce a reading-order interleaved output.
 *
 * Coordinates are in **PDF user-space units** (points, 1/72 inch), origin at the top-left
 * of the page, y-axis increasing downward. This matches Tabula's coordinate system and
 * avoids a sign-flip when comparing Tabula table bboxes against the placeholder bboxes
 * produced by [PdfProseExtractor].
 *
 * @param page   1-based page number, matching Tabula's `Page.pageNumber`.
 * @param top    Y-coordinate of the top edge of the block (0.0 = top of page).
 * @param bottom Y-coordinate of the bottom edge of the block (> [top]).
 * @param block  The structured document block extracted from this position.
 */
data class PositionedBlock<out B : DocumentBlock>(
    val page: Int,
    val top: Double,
    val bottom: Double,
    val block: B,
)
