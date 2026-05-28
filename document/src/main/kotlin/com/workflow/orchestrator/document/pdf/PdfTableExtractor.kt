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
 * - **Stream mode** ([BasicExtractionAlgorithm]): detects whitespace-aligned (borderless)
 *   tables — the norm in spec PDFs (e.g. NIST 800-63B Table 4-1). Tried as a **per-page
 *   fallback only on pages where lattice found nothing**, so ruled tables are never
 *   double-emitted. Stream candidates pass a stricter phantom guard ([isStreamPhantomTable])
 *   because whitespace-clustering routinely invents tables out of multi-column prose. Gated by
 *   `enableStreamMode`; [PdfPipeline] enables it by default.
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
 * @param enableStreamMode When `true`, stream mode is tried as a per-page fallback on pages
 *                         where lattice extraction finds no tables, with the strict
 *                         [isStreamPhantomTable] guard applied to its output. Default: `false`
 *                         (this constructor); [PdfPipeline] constructs with `true`.
 */
class PdfTableExtractor(private val enableStreamMode: Boolean = false) {

    private companion object {
        /** Tables with more than this fraction of empty cells are treated as chart artefacts. */
        const val PHANTOM_EMPTY_FRACTION = 0.8

        /** Tables with fewer than this many distinct non-empty cell values are phantom. */
        const val PHANTOM_MIN_UNIQUE_VALUES = 3

        /**
         * Minimum fraction of filled (non-blank) cells a STREAM-mode table must have.
         *
         * Stream mode (whitespace-clustering) is far more prone than lattice to inventing a
         * "table" out of a multi-column prose page where the columns are ragged. A genuine
         * grid is densely filled; a phantom from prose has scattered fills. This floor — applied
         * ONLY to stream-mode candidates — rejects the sparse ragged phantoms while keeping the
         * lattice phantom guards unchanged. Lower than [PHANTOM_EMPTY_FRACTION]'s complement so a
         * real table with a couple of empty cells survives.
         */
        const val STREAM_MIN_FILLED_RATIO = 0.7

        /** A real table needs at least this many columns (a single column is a prose list). */
        const val MIN_COLUMNS = 2

        /** A real table needs at least this many DATA rows beneath the header row. */
        const val MIN_DATA_ROWS = 2
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

                // Filter out lattice phantom detections — Tabula's lattice algorithm mistakes
                // chart gridlines / sparse bbox grids for tables, producing N overlapping
                // near-empty tables on a single chart page. See [isLikelyPhantomTable].
                tables = tables.filterNot { isLikelyPhantomTable(it) }

                // Stream fallback — opt-in only (see class KDoc). Tried per page ONLY when
                // lattice found nothing on that page, so borderless (ruling-free) tables are
                // still captured without double-emitting ruled tables. Stream candidates run a
                // STRICTER guard ([isStreamPhantomTable]) because whitespace-clustering routinely
                // invents tables out of multi-column prose.
                if (tables.isEmpty() && enableStreamMode) {
                    tables = BasicExtractionAlgorithm().extract(page)
                        .filterNot { isLikelyPhantomTable(it) }
                        .filterNot { isStreamPhantomTable(it) }
                }

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
     * Stricter phantom guard applied ONLY to stream-mode (whitespace-clustered) candidates.
     *
     * Stream mode is the documented source of phantom tables: on a multi-column PROSE page it
     * clusters runs of text into a "grid" that is really just narrative laid out in columns.
     * A genuine borderless table, by contrast, is rectangular and densely filled. Four cheap
     * structural signals separate the two; a candidate failing ANY of them is a phantom:
     *
     * 1. **≥ [MIN_COLUMNS] columns** — a single column is a bulleted/numbered prose list, not a table.
     * 2. **≥ [MIN_DATA_ROWS] data rows** (rows beyond the header) — one row is a caption or a stray line.
     * 3. **Rectangular** — every Tabula row reports the same cell count as the header. (Tabula
     *    always pads to a rectangle, so a mismatch means something pathological.)
     * 4. **Not ragged** — every row must fill at least `ceil(colCount * STREAM_MIN_FILLED_RATIO)`
     *    of its cells. A real grid populates (nearly) every cell on every row; a multi-column
     *    PROSE phantom has a ragged tail where the later columns run out of text (e.g. a 6-col
     *    numeric layout whose last rows fill only 3 cells). This per-row floor is the signal
     *    that separates a genuine borderless grid from prose mis-clustered into columns.
     * 5. **Filled-cell ratio ≥ [STREAM_MIN_FILLED_RATIO]** — global density backstop.
     *
     * Returns `true` when the candidate is a phantom and must be dropped.
     */
    private fun isStreamPhantomTable(t: Table): Boolean {
        val rows = t.getRows()
        if (rows.isEmpty()) return true

        // 1. Column count — use the header (first) row's cell count as the table width.
        val colCount = rows[0].size
        if (colCount < MIN_COLUMNS) return true

        // 2. Data-row count (rows beyond the header).
        val dataRowCount = rows.size - 1
        if (dataRowCount < MIN_DATA_ROWS) return true

        // 3. Rectangular — every row must match the header width.
        if (rows.any { it.size != colCount }) return true

        // 4. Raggedness — reject when any row fills fewer than the per-row floor. Catches the
        //    ragged tail of a multi-column prose layout (e.g. perRowFilled=[6,6,…,6,3,3,3,3]).
        val perRowFloor = Math.ceil(colCount * STREAM_MIN_FILLED_RATIO).toInt().coerceAtLeast(MIN_COLUMNS)
        val filledPerRow = rows.map { row -> row.count { it.text.isNotBlank() } }
        if (filledPerRow.any { it < perRowFloor }) return true

        // 5. Global filled-cell ratio backstop.
        val cells = rows.flatten()
        if (cells.isEmpty()) return true
        val filled = cells.count { it.text.isNotBlank() }
        val filledRatio = filled.toDouble() / cells.size
        if (filledRatio < STREAM_MIN_FILLED_RATIO) return true

        return false
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
