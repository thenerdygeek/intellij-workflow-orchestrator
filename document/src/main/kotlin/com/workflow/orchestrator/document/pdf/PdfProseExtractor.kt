package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.pipeline.TikaXhtmlPipeline
import java.nio.file.Path
import java.nio.file.Files

/**
 * Extracts prose blocks from a PDF using [TikaXhtmlPipeline] and annotates each block
 * with its page number and a synthetic Y-coordinate so the merge step in [PdfPipeline]
 * can interleave prose with Tabula tables in reading order.
 *
 * ## Coordinate strategy
 *
 * Tika's XHTML output does not carry PDF bbox information — it only emits `<div class="page">`
 * boundaries via [DocumentBlock.PageMarker]. To participate in the `(page, top)` sort used by
 * the merge pipeline, prose blocks receive **placeholder bboxes**:
 *
 * - Within each page, blocks are assigned a monotonically increasing `top` counter
 *   (1.0, 2.0, 3.0, …). This preserves intra-page document order.
 * - `bottom = top + 10` (arbitrary height that is never used for overlap detection
 *   against other prose blocks — only Table bboxes are checked in [PdfPipeline.suppressOverlaps]).
 *
 * ## PageMarker blocks
 *
 * One [DocumentBlock.PageMarker] per page transition is emitted as a positioned block at
 * `top = 0.0` on the new page, so it appears at the start of each page in the merged output.
 * The PageMarkers themselves are content blocks (they render as `<!-- page: N -->` in
 * Markdown via [com.workflow.orchestrator.document.assembler.MarkdownAssembler]).
 *
 * ## Thread safety
 *
 * [TikaXhtmlPipeline] is instantiated per call to [PdfProseExtractor]. This is safe because
 * the pipeline itself creates fresh Tika instances per call. [PdfProseExtractor] itself holds
 * no mutable state.
 */
class PdfProseExtractor {

    /**
     * Extracts prose blocks from [file], tagged with page and synthetic Y position.
     *
     * @param file Absolute path to the PDF file.
     * @return Ordered list of positioned blocks. PageMarkers appear first on each page
     *         (`top = 0.0`); prose blocks follow in document order.
     */
    fun extract(file: Path): List<PositionedBlock<DocumentBlock>> {
        val rawBlocks: List<DocumentBlock> = Files.newInputStream(file).use { stream ->
            TikaXhtmlPipeline().extract(stream, "application/pdf")
        }

        val result = mutableListOf<PositionedBlock<DocumentBlock>>()
        var currentPage = 1
        // Counter within the current page — incremented for each prose block.
        // Start at 1 so prose blocks (top ≥ 1) never overlap with page-marker top = 0.
        var pageCounter = 1.0

        for (block in rawBlocks) {
            when (block) {
                is DocumentBlock.PageMarker -> {
                    currentPage = block.pageNumber
                    pageCounter = 1.0
                    // Emit the PageMarker itself at top=0 so it heads the page in the merge.
                    result += PositionedBlock(
                        page = currentPage,
                        top = 0.0,
                        bottom = 0.0,
                        block = block,
                    )
                }
                else -> {
                    result += PositionedBlock(
                        page = currentPage,
                        top = pageCounter,
                        bottom = pageCounter + 10.0,
                        block = block,
                    )
                    pageCounter += 1.0
                }
            }
        }

        return result
    }
}
