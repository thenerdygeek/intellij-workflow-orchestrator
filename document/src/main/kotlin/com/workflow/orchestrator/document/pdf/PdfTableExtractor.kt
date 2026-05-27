package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.normaliseRow
import org.apache.pdfbox.Loader
import technology.tabula.ObjectExtractor
import technology.tabula.RectangularTextContainer
import technology.tabula.Table
import technology.tabula.extractors.BasicExtractionAlgorithm
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm
import java.nio.file.Path

/**
 * Extracts tables from a PDF file using Tabula-java.
 *
 * ## Mode
 *
 * - **Lattice mode** (default, always tried first): [SpreadsheetExtractionAlgorithm].
 *   Detects ruled tables (cells bounded by visible lines). False-positive rate for
 *   spec PDFs is essentially zero.
 * - **Stream mode** (opt-in only): [BasicExtractionAlgorithm]. Detects whitespace-aligned
 *   tables. Disabled by default because it routinely produces phantom tables on multi-column
 *   prose pages. Enable via `enableStreamMode = true` only for documents known to contain
 *   whitespace-aligned tables.
 *
 * ## Per-call instantiation
 *
 * `PDDocument` and `ObjectExtractor` are instantiated fresh on every [extract] call.
 * Neither is cached. This is intentional — both classes are mutable and not thread-safe;
 * caching would require external synchronization and would retain large heap objects
 * across GC cycles. The [Loader.loadPDF] / `.use {}` pattern ensures the document is
 * closed even on exception.
 *
 * ## Continuation detection
 *
 * After all pages are processed, [mergeContinuations] joins table fragments that span page
 * boundaries. A fragment is detected when: the next block is on page N+1, starts near the
 * top of that page (top < 100 PDF units), and either shares the same headers as the
 * previous block or has no header row (repeated `repeatRows=1` scenario).
 *
 * @param enableStreamMode When `true`, stream mode is tried as a fallback on pages where
 *                         lattice extraction finds no tables. Default: `false`.
 */
class PdfTableExtractor(private val enableStreamMode: Boolean = false) {

    private companion object {
        /** Tables with more than this fraction of empty cells are treated as chart artefacts. */
        const val PHANTOM_EMPTY_FRACTION = 0.8

        /** Tables with fewer than this many distinct non-empty cell values are phantom. */
        const val PHANTOM_MIN_UNIQUE_VALUES = 3
    }

    /**
     * Extracts all tables from [file], merging cross-page continuations.
     *
     * Throws [org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException] or
     * [org.apache.pdfbox.io.RandomAccessReadBuffer]-related exceptions for encrypted PDFs —
     * these are intentionally not caught here and bubble up for Phase 6's error catalog.
     *
     * @param file   Absolute path to the PDF file.
     * @param onPage Optional per-page progress callback. Invoked after each page is processed
     *               with `(pagesDone, pagesTotal)` where `pagesDone` advances 1..total.
     *               Defaults to null so all existing callers are unaffected.
     * @return List of positioned table blocks in document order (page, then top-to-bottom).
     */
    fun extract(
        file: Path,
        onPage: ((done: Int, total: Int) -> Unit)? = null,
    ): List<PositionedBlock<DocumentBlock.Table>> {
        val positioned = mutableListOf<PositionedBlock<DocumentBlock.Table>>()

        Loader.loadPDF(file.toFile()).use { document ->
            val total = document.numberOfPages
            val objectExtractor = ObjectExtractor(document)
            val pageIterator = objectExtractor.extract()
            var processedCount = 0

            while (pageIterator.hasNext()) {
                val page = pageIterator.next()

                // Lazy lattice: skip the expensive SpreadsheetExtractionAlgorithm on pages with
                // no rulings — lattice only finds ruled tables, so a ruling-free page yields
                // nothing anyway. Bypassed when stream mode is on (borderless tables have no
                // rulings and must still be reached).
                var tables: List<Table> = if (enableStreamMode || pageHasRulings(page)) {
                    SpreadsheetExtractionAlgorithm().extract(page)
                } else {
                    emptyList()
                }

                // Stream fallback — opt-in only (see class KDoc).
                if (tables.isEmpty() && enableStreamMode) {
                    tables = BasicExtractionAlgorithm().extract(page)
                }

                // Filter out phantom detections — Tabula's lattice algorithm mistakes
                // chart gridlines / sparse bbox grids for tables, producing N overlapping
                // near-empty tables on a single chart page. See [isLikelyPhantomTable].
                tables = tables.filterNot { isLikelyPhantomTable(it) }

                for (t in tables) {
                    val block = tabulaTableToDocumentBlock(t) ?: continue
                    positioned += PositionedBlock(
                        page = page.pageNumber,
                        top = t.top.toDouble(),
                        bottom = (t.top + t.height).toDouble(),
                        block = block,
                    )
                }

                processedCount++
                onPage?.invoke(processedCount, total)
            }
        }

        return mergeContinuations(positioned)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * True when the Tabula [page] has any rulings. Rulings are computed by ObjectExtractor during
     * page extraction, so reading them here costs nothing extra. A page with no rulings cannot
     * contain a lattice (ruled) table.
     */
    private fun pageHasRulings(page: technology.tabula.Page): Boolean =
        page.rulings?.isNotEmpty() == true

    /**
     * Converts a Tabula [Table] to a [DocumentBlock.Table].
     *
     * The first row is treated as headers. Subsequent rows are normalised to
     * `headers.size` cells (padded with empty string or truncated).
     *
     * Returns `null` if the table has no rows or the header row is entirely blank.
     */
    private fun tabulaTableToDocumentBlock(t: Table): DocumentBlock.Table? {
        val rawRows = t.getRows()
        if (rawRows.isEmpty()) return null

        val headers = rawRows[0].map { it.text.trim() }
        if (headers.all { it.isEmpty() }) return null

        val dataRows = rawRows.drop(1).map { row ->
            normaliseRow(row.map { it.text.trim() }, headers.size)
        }

        return DocumentBlock.Table(headers = headers, rows = dataRows, caption = null)
    }

    /**
     * Heuristic: is this Tabula [Table] more likely a chart artefact than a real table?
     *
     * Tabula's lattice algorithm keys on horizontal + vertical ruling lines. A bar chart's
     * axis ticks + bar boundaries form a grid that matches that signal, producing a stack
     * of overlapping tables on the same page that are mostly empty. Two cheap signals
     * catch them with low false-positive risk for legitimate sparse tables:
     *
     * 1. **Empty fraction** — if more than [PHANTOM_EMPTY_FRACTION] of cells are blank,
     *    the "table" is a grid of whitespace, not data.
     * 2. **Unique values** — if fewer than [PHANTOM_MIN_UNIQUE_VALUES] distinct non-empty
     *    cell values exist, the "table" can't carry meaningful information. Catches the
     *    single-fat-cell phantom where all the chart text collapsed into one cell.
     */
    private fun isLikelyPhantomTable(t: Table): Boolean {
        val rows = t.getRows()
        if (rows.isEmpty()) return true
        val cells = rows.flatten()
        if (cells.isEmpty()) return true

        val nonEmpty = cells.count { it.text.isNotBlank() }
        val emptyFraction = 1.0 - (nonEmpty.toDouble() / cells.size)
        if (emptyFraction > PHANTOM_EMPTY_FRACTION) return true

        val uniqueValues = cells.mapTo(HashSet()) { it.text.trim() }
        uniqueValues.remove("")
        return uniqueValues.size < PHANTOM_MIN_UNIQUE_VALUES
    }

    /**
     * Merges adjacent table blocks that represent a single logical table split across pages.
     *
     * Detection criteria for a continuation at index `i+1`:
     * - `blocks[i+1].page == blocks[i].page + 1` (immediately next page)
     * - `blocks[i+1].top < 100.0` (starts near top of that page — in PDF user-space points)
     * - Headers match exactly (repeated header row, e.g. `repeatRows=1`), OR the continuation
     *   block's header row looks like a data row (all non-blank values that don't match the
     *   parent headers — i.e., a headerless continuation).
     *
     * When merged, the result keeps the parent's headers and concatenates all data rows.
     * The merged block spans `blocks[i].page` through `blocks[i+1].page`, with
     * `bottom = blocks[i+1].bottom`.
     */
    private fun mergeContinuations(
        blocks: List<PositionedBlock<DocumentBlock.Table>>,
    ): List<PositionedBlock<DocumentBlock.Table>> {
        if (blocks.size < 2) return blocks

        val result = mutableListOf<PositionedBlock<DocumentBlock.Table>>()
        var current = blocks[0]

        for (i in 1 until blocks.size) {
            val next = blocks[i]
            if (isContinuation(current, next)) {
                // Merge next into current.
                val mergedRows = mergeContinuationRows(current.block, next.block)
                val mergedBlock = DocumentBlock.Table(
                    headers = current.block.headers,
                    rows = mergedRows,
                    caption = current.block.caption,
                )
                current = PositionedBlock(
                    page = current.page,
                    top = current.top,
                    bottom = next.bottom,
                    block = mergedBlock,
                )
            } else {
                result += current
                current = next
            }
        }
        result += current
        return result
    }

    /**
     * Returns `true` when [next] is a continuation of [current] across a page boundary.
     */
    private fun isContinuation(
        current: PositionedBlock<DocumentBlock.Table>,
        next: PositionedBlock<DocumentBlock.Table>,
    ): Boolean {
        if (next.page != current.page + 1) return false
        if (next.top >= 100.0) return false

        val currentHeaders = current.block.headers
        val nextHeaders = next.block.headers

        // Case 1: repeated header row (repeatRows=1 scenario).
        if (currentHeaders == nextHeaders) return true

        // Case 2: headerless continuation — the "header" row of the next fragment is
        // actually a data row (no match with parent headers). We accept this continuation
        // when the next block has at least one data row OR its header row values are all
        // non-empty (a data row masquerading as headers).
        val nextLooksLikeData = nextHeaders.none { it.isEmpty() } &&
            nextHeaders != currentHeaders
        if (nextLooksLikeData && nextHeaders.size == currentHeaders.size) return true

        return false
    }

    /**
     * Produces the combined data rows when merging [parent] and [continuation].
     *
     * If the continuation's headers match the parent's (repeated header row), the
     * continuation's header row is discarded and only its data rows are appended.
     * Otherwise the continuation's "header row" is treated as a data row and prepended
     * before its data rows, all normalised to `parent.headers.size`.
     */
    private fun mergeContinuationRows(
        parent: DocumentBlock.Table,
        continuation: DocumentBlock.Table,
    ): List<List<String>> {
        val extraRows = if (parent.headers == continuation.headers) {
            // Repeated header row — skip it.
            continuation.rows
        } else {
            // Headerless continuation — treat the "header" as first data row.
            val headerAsRow = normaliseRow(continuation.headers, parent.headers.size)
            listOf(headerAsRow) + continuation.rows.map { normaliseRow(it, parent.headers.size) }
        }
        return parent.rows + extraRows
    }
}
