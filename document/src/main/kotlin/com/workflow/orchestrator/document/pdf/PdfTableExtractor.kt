package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.normaliseRow
import org.apache.pdfbox.Loader
import technology.tabula.ObjectExtractor
import technology.tabula.RectangularTextContainer
import technology.tabula.Table
import technology.tabula.TextElement
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
 * After all pages are processed, [mergeContinuations] JOINS table fragments that span page
 * boundaries into ONE whole table (all rows concatenated in order, any repeated header dropped).
 * A fragment continues the running table when it sits on the next page after the LAST page already
 * absorbed (so a table spanning N>2 pages chains into one block, not pairwise), starts near the
 * page top, has the same column count, and matches a continuation signal: a literal
 * "(continued)" caption naming the same table number, an exactly-repeated header row, or a
 * blank-dominant continuation header (the silent-split signal). A fully-populated header that
 * differs from the running table's header is treated as a NEW table and never fused.
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

        /**
         * Minimum table width to consider a two-row grouped header (see [detectGroupedHeader]).
         * A 2-column table cannot carry a meaningful spanning group.
         */
        const val GROUPED_MIN_COLUMNS = 3

        /**
         * Minimum fraction of columns the LEAF row of a grouped header must fill. A genuine leaf
         * row names (nearly) every sub-column; a sparse stray row is not a leaf row.
         */
        const val GROUPED_LEAF_MIN_FILLED_RATIO = 0.6

        /**
         * A cell that is purely numeric (integer/decimal, optional sign/grouping). Used to reject
         * a first DATA row masquerading as the leaf-header row in [detectGroupedHeader]: leaf
         * headers are labels, never bare numbers.
         */
        val NUMERIC_CELL = Regex("[-+]?[\\d,]+(?:\\.\\d+)?")

        /**
         * A continuation fragment must begin within this many PDF user-space points of the page
         * top. A table that continues onto the next page resumes at the top margin; anything lower
         * is a separate table later on the page.
         */
        const val TOP_OF_PAGE_THRESHOLD = 100.0

        /** Matches a "(continued)" / "…continued" marker in a table caption (case-insensitive). */
        val CONTINUED_MARKER = Regex("""\bcontinued\b""", RegexOption.IGNORE_CASE)

        /**
         * Extracts the table NUMBER from a caption such as "Table 45. …", "TABLE 1-2: …", or
         * "Table 45 (continued)". Group 1 is the number token (e.g. `45`, `1-2`), used to confirm
         * a continuation names the SAME table as its parent.
         */
        val TABLE_NUMBER = Regex("""\btable\s+([\d][\w.\-]*)""", RegexOption.IGNORE_CASE)
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
    ): List<PositionedBlock<DocumentBlock.Table>> = mergeContinuations(extractRaw(file, onPage))

    /**
     * Per-page table extraction WITHOUT continuation-merge — every table keeps its own page.
     *
     * [extract] post-processes this with [mergeContinuations], which joins a multi-page table's
     * fragments into a single block keyed on its FIRST page (the per-page rows are concatenated, not
     * lost). Callers that need the true set of pages a table occupies — e.g. the SF-1
     * Tabula-presence gate in
     * [com.workflow.orchestrator.document.pipeline.PdfPipeline], which must never column-split ANY
     * page of a multi-page table such as nist-csf's Framework Core — use this pre-merge view.
     *
     * @return Raw positioned table blocks, one per Tabula detection, in (page, top) order.
     */
    fun extractRaw(
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
                //
                // Per-page isolation: Tabula's SpreadsheetExtractionAlgorithm throws
                // IllegalArgumentException("lines must be orthogonal, vertical and horizontal")
                // when a page carries skewed/diagonal rulings (common in datasheet figures, e.g.
                // microchip PIC18F). An unguarded throw here aborts the WHOLE document's table
                // extraction — dropping every legitimate ruled table on every other page. We
                // isolate the failure to the offending page and continue, so one bad figure page
                // never costs the document its tables.
                var tables: List<Table> = if (enableStreamMode || pageHasRulings(page)) {
                    runCatching { SpreadsheetExtractionAlgorithm().extract(page) }
                        .getOrDefault(emptyList())
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
                // invents tables out of multi-column prose. Isolated per-page (see above) so a
                // pathological page cannot abort the document.
                if (tables.isEmpty() && enableStreamMode) {
                    tables = runCatching {
                        BasicExtractionAlgorithm().extract(page)
                            .filterNot { isLikelyPhantomTable(it) }
                            .filterNot { isStreamPhantomTable(it) }
                    }.getOrDefault(emptyList())
                }

                // Collect every text glyph on the page ONCE. Used to re-populate cell text by
                // center-point containment (see [tabulaTableToDocumentBlock]) so glyphs straddling
                // a column ruling are not dropped by Tabula's full-containment cell assignment.
                val pageText: List<TextElement> = if (tables.isNotEmpty()) page.text else emptyList()

                for (t in tables) {
                    val block = tabulaTableToDocumentBlock(t, pageText) ?: continue
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

        return positioned
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
     * ## Glyph-clip repair (T-2 / T-4)
     *
     * Tabula populates each ruled cell via `Page.getText(cellRect)`, which uses
     * `java.awt.geom.Rectangle2D.contains()` — a **full-containment** test. A glyph whose
     * bounding box straddles a column ruling is contained in *neither* adjacent cell and is
     * silently dropped (e.g. the trailing `0` of a right-aligned `4,558,150` → `4,558,15`, or
     * the leading `W` of a left-aligned `Withdrawn` → `ithdrawn`). This factually corrupts the
     * data. To repair it, when [pageText] is available we re-derive each cell's text by
     * **center-point containment**: every glyph is assigned to the single cell whose rectangle
     * contains the glyph's center, so a straddling glyph always lands in exactly one cell and is
     * never lost. Tabula's own [TextElement.mergeWords] reassembles the assigned glyphs into the
     * cell's text in reading order, preserving word/space joining. When [pageText] is empty
     * (defensive fallback) the original Tabula cell text is used unchanged.
     *
     * ## Leading blank spacer row (T-1)
     *
     * A fully-ruled table whose header sits in a tall header band (e.g. NIST 800-63B Table 4-1,
     * "AAL Summary of Requirements") yields a leading **all-blank** Tabula row for the empty space
     * above the header text; the real header row is the *second* row. Treating row 0 as the header
     * found it entirely blank and returned `null`, silently discarding the whole table (it then
     * leaked as unstructured prose). We therefore skip leading all-blank rows when choosing the
     * header row, and drop fully-blank interior spacer rows from the body, so such tables are
     * recovered with their real header and body intact.
     *
     * Returns `null` if the table has no rows or has no non-blank row to use as a header.
     *
     * ## Grouped (two-row spanning) header (T-1 grouped sub-case)
     *
     * Some ruled tables carry a TWO-ROW header: a top **group** row whose label spans several
     * sub-columns (a non-empty label followed by empty continuation cells) and a second **leaf**
     * row that names each sub-column (arxiv "Attention Is All You Need" Table 2: `BLEU` /
     * `Training Cost (FLOPs)` spanning `EN-DE | EN-FR`; Fed SCF `Median income` / `Mean income`
     * spanning `2019 | 2022 | Percent change`). Treating only the first content row as the header
     * leaves the leaf labels on a separate row that then misaligns with the data. When
     * [detectGroupedHeader] recognises this CONTAINED pattern, we flatten the two header rows to a
     * single row of **composite leaf headers** (`<group> <leaf>`, forward-filling the group label
     * across its span). The conservative guard in [detectGroupedHeader] leaves every ambiguous or
     * normal single-row-header table on the row-0 path unchanged.
     */
    private fun tabulaTableToDocumentBlock(t: Table, pageText: List<TextElement>): DocumentBlock.Table? {
        val rawRows = t.getRows()
        if (rawRows.isEmpty()) return null

        val repaired: List<List<String>> = rawRows.map { row ->
            row.map { cell -> cellText(cell, pageText).trim() }
        }

        // Skip leading all-blank spacer rows so the real header row (not an empty band above it)
        // is used as the header.
        val firstContentIdx = repaired.indexOfFirst { row -> row.any { it.isNotEmpty() } }
        if (firstContentIdx < 0) return null

        // Two-row grouped/spanning header → composite leaf headers (see KDoc). Conservative:
        // returns null unless the group/leaf pattern is unambiguous, in which case we keep the
        // existing row-0 behaviour.
        val groupRow = repaired[firstContentIdx]
        val leafRow = repaired.getOrNull(firstContentIdx + 1)
        val composite = if (leafRow != null) detectGroupedHeader(groupRow, leafRow) else null

        val headers: List<String>
        val bodyStartIdx: Int
        if (composite != null) {
            headers = composite
            bodyStartIdx = firstContentIdx + 2
        } else {
            headers = groupRow
            bodyStartIdx = firstContentIdx + 1
        }

        val dataRows = repaired.drop(bodyStartIdx)
            // Drop fully-blank interior spacer rows (header-band padding between header and body).
            .filter { row -> row.any { it.isNotEmpty() } }
            .map { row -> normaliseRow(row, headers.size) }

        return DocumentBlock.Table(headers = headers, rows = dataRows, caption = null)
    }

    /**
     * Detects a CONTAINED two-row (grouped/spanning) header and returns the flattened composite
     * leaf headers, or `null` when the pattern is absent or ambiguous (leaving the caller on the
     * unchanged row-0 path).
     *
     * [groupRow] is the candidate top row (group labels spanning sub-columns); [leafRow] is the
     * candidate row beneath it (leaf labels naming each sub-column). Both are already
     * cell-text-repaired and trimmed, and Tabula pads every row to the same width.
     *
     * ## Detection signals (ALL required — conservative bias)
     *
     * A wrong composite header is worse than a flat one, so we only flatten when the structure is
     * unmistakably a spanning group header and never when it could be a normal `header + first
     * data row`:
     *
     * 1. **Width ≥ 3.** A 2-column table cannot meaningfully carry a spanning group.
     * 2. **The group row has a spanning gap** — at least one EMPTY cell that follows a non-empty
     *    cell (a label `BLEU` then a blank continuation column). A normal single-row header is
     *    fully filled and has no such interior gap, so this alone rejects the common case.
     * 3. **The leaf row is strictly denser than the group row** — it fills more cells. A real leaf
     *    row names (nearly) every sub-column; a data row mistaken for a leaf row would not be
     *    *more* filled than a complete header above it.
     * 4. **The leaf row is well filled** (≥ [GROUPED_LEAF_MIN_FILLED_RATIO] of columns). Guards
     *    against treating a sparse stray row as leaf headers.
     * 5. **Forward-fill yields a genuine grouping** — after carrying each group label rightward
     *    across its span, at least one column has BOTH a group label and a leaf label (the
     *    `BLEU`+`EN-DE` composite). Without an actual overlap there is no group to flatten.
     * 6. **The leaf row carries no purely-numeric cell** — leaf headers are labels, not data. A
     *    first data row (which a grouped-header misfire would consume) almost always contains a
     *    bare number; rejecting numeric leaf cells is a strong anti-data-row guard.
     *
     * ## Composite rule
     *
     * For each column the group label is the nearest non-empty group-row cell at index ≤ the
     * column (forward-fill). The composite header is `"<group> <leaf>"` when both are present,
     * the group label alone when the leaf cell is empty (the row-label column under a group
     * heading, e.g. `Model`), and the leaf label alone when no group label has appeared yet.
     */
    private fun detectGroupedHeader(groupRow: List<String>, leafRow: List<String>): List<String>? {
        val width = groupRow.size
        if (width < GROUPED_MIN_COLUMNS || leafRow.size != width) return null

        // 2. Group row must have a spanning gap: an empty cell following a non-empty cell.
        var sawGroupLabel = false
        var hasSpanGap = false
        for (cell in groupRow) {
            if (cell.isNotEmpty()) {
                sawGroupLabel = true
            } else if (sawGroupLabel) {
                hasSpanGap = true
            }
        }
        if (!hasSpanGap) return null

        // 3 + 4. Leaf row must be strictly denser than the group row AND well filled.
        val groupFilled = groupRow.count { it.isNotEmpty() }
        val leafFilled = leafRow.count { it.isNotEmpty() }
        if (leafFilled <= groupFilled) return null
        if (leafFilled.toDouble() / width < GROUPED_LEAF_MIN_FILLED_RATIO) return null

        // 6. Leaf cells must be labels, not data — reject a purely-numeric leaf cell.
        if (leafRow.any { it.isNotEmpty() && it.matches(NUMERIC_CELL) }) return null

        // Forward-fill the group label across its span, then compose.
        var activeGroup = ""
        var sawOverlap = false
        val composite = ArrayList<String>(width)
        for (i in 0 until width) {
            if (groupRow[i].isNotEmpty()) activeGroup = groupRow[i]
            val leaf = leafRow[i]
            val cell = when {
                activeGroup.isNotEmpty() && leaf.isNotEmpty() -> {
                    sawOverlap = true
                    "$activeGroup $leaf"
                }
                leaf.isNotEmpty() -> leaf
                activeGroup.isNotEmpty() -> activeGroup
                else -> ""
            }
            composite += cell
        }

        // 5. A genuine grouping must produce at least one group+leaf composite.
        if (!sawOverlap) return null

        return composite
    }

    /**
     * Returns the text of a single Tabula [cell], repairing the boundary-glyph clip.
     *
     * When [pageText] is non-empty and the cell has a positive-area rectangle, the text is
     * re-derived by center-point containment (see [tabulaTableToDocumentBlock]); otherwise the
     * cell's original Tabula text is returned unchanged. Re-derivation falls back to the original
     * text when no glyph centers land inside the cell — that means Tabula's own assignment (which
     * may have used overlap rather than strict containment for that cell) found content our
     * stricter center test would discard, so we never lose text relative to the baseline.
     */
    private fun cellText(cell: RectangularTextContainer<*>, pageText: List<TextElement>): String {
        val original = cell.text
        if (pageText.isEmpty()) return original

        val left = cell.left
        val right = cell.right
        val top = cell.top
        val bottom = cell.bottom
        // Zero/negative-area cells (Tabula's `[0-0]` padding columns) can hold no glyphs.
        if (right - left <= 0f || bottom - top <= 0f) return original

        val inCell = pageText.filter { e ->
            val cx = e.centerX.toFloat()
            val cy = e.centerY.toFloat()
            cx >= left && cx < right && cy >= top && cy < bottom
        }
        if (inCell.isEmpty()) return original

        return TextElement.mergeWords(inCell).joinToString(" ") { it.text }.trim()
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
     * Joins table fragments that represent ONE logical table split across page boundaries into a
     * single whole table — all rows concatenated in order, any repeated header dropped.
     *
     * ## The page-chaining invariant
     *
     * A table can span more than two pages (e.g. microchip PIC18F TABLE 1-2 occupies four pages,
     * TABLE 1-3 six). Adjacency is therefore tested against the **last page already absorbed into
     * the running fragment** ([currentLastPage]), not the running fragment's *start* page — so a
     * 4-page table chains 14→15→16→17 into one block rather than collapsing into 14+15 / 16+17
     * pairs. (This pairwise collapse was the original defect: it compared against `current.page`,
     * which never advanced past the first fragment.)
     *
     * ## Continuation signals (see [isContinuation])
     *
     * 1. **Literal "(continued)" caption** — the next fragment's caption names the SAME table
     *    number as the current fragment and is marked "(continued)"/"…continued".
     * 2. **Repeated header** — the next fragment's header row equals the current header exactly
     *    (the `repeatRows=1` layout used by datasheets that re-print the header on every page).
     * 3. **Silent split (blank-dominant continuation header)** — same column count, next fragment
     *    at the top of the following page, and its detected "header" row is blank-dominant (a
     *    continuation page's first Tabula row, never a fresh table's fully-populated header).
     *
     * ## Anti-fusion guard
     *
     * A continuation whose first row is a *fully-populated header that differs* from the current
     * header is treated as a NEW table, never fused — this is structurally indistinguishable from
     * a genuine same-column-count table beginning on the next page, so the conservative choice is
     * to keep them separate. (Fully-populated headerless data continuations are the deferred
     * subset; see [isContinuation].)
     *
     * The merged block keeps the parent's headers/caption and start page, and extends `bottom` to
     * the last absorbed fragment's bottom.
     */
    internal fun mergeContinuations(
        blocks: List<PositionedBlock<DocumentBlock.Table>>,
    ): List<PositionedBlock<DocumentBlock.Table>> {
        if (blocks.size < 2) return blocks

        val result = mutableListOf<PositionedBlock<DocumentBlock.Table>>()
        var current = blocks[0]
        // The last page absorbed into `current` — adjacency chains against THIS, not current.page,
        // so a table spanning N>2 pages merges as one block instead of pairwise.
        var currentLastPage = blocks[0].page

        for (i in 1 until blocks.size) {
            val next = blocks[i]
            if (isContinuation(current, currentLastPage, next)) {
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
                currentLastPage = next.page
            } else {
                result += current
                current = next
                currentLastPage = next.page
            }
        }
        result += current
        return result
    }

    /**
     * Returns `true` when [next] is a continuation of the running fragment [current] (whose last
     * absorbed page is [currentLastPage]) across a page boundary.
     *
     * Adjacency: [next] must sit on `currentLastPage + 1` and start near the top of that page
     * ([TOP_OF_PAGE_THRESHOLD], PDF user-space points). Then one of three signals must hold —
     * see [mergeContinuations] for the rationale and the anti-fusion guard.
     */
    private fun isContinuation(
        current: PositionedBlock<DocumentBlock.Table>,
        currentLastPage: Int,
        next: PositionedBlock<DocumentBlock.Table>,
    ): Boolean {
        if (next.page != currentLastPage + 1) return false
        if (next.top >= TOP_OF_PAGE_THRESHOLD) return false

        val currentHeaders = current.block.headers
        val nextHeaders = next.block.headers

        // Signal 1: literal "(continued)" caption naming the same table number. Captions are not
        // populated by the Tabula converter today (they live in the prose stream), so this is a
        // forward-compatible path; it never fires for null captions.
        if (isContinuedCaption(current.block.caption, next.block.caption)) return true

        // Same column count is required for every structural (caption-less) signal: a continuation
        // never changes the grid width.
        if (nextHeaders.size != currentHeaders.size) return false

        // Signal 2: repeated header row (repeatRows=1 layout) — the strongest structural signal.
        if (currentHeaders == nextHeaders) return true

        // Signal 3: silent split — the next fragment's detected "header" is blank-dominant, i.e.
        // a continuation page's first Tabula row (which has empty leading cells), NOT a fresh
        // table's fully-populated header. This is the real signal in spec PDFs that repeat NO
        // header and print NO "continued" marker (e.g. DICOM PS3.18 multi-page tables).
        //
        // Anti-fusion guard: a fully-populated header that DIFFERS from the current header is a
        // distinct table beginning on the next page (it is structurally indistinguishable from a
        // genuine new same-width table), so we conservatively keep them separate.
        if (isBlankDominant(nextHeaders)) return true

        return false
    }

    /**
     * True when [next]'s caption marks it a literal continuation of [current]'s captioned table —
     * i.e. it carries a "(continued)"/"…continued" marker AND names the same `Table <N>` number.
     * Conservative: both captions must be present and the table numbers must match.
     */
    private fun isContinuedCaption(current: String?, next: String?): Boolean {
        if (current.isNullOrBlank() || next.isNullOrBlank()) return false
        if (!CONTINUED_MARKER.containsMatchIn(next)) return false
        val currentNum = TABLE_NUMBER.find(current)?.groupValues?.getOrNull(1) ?: return false
        val nextNum = TABLE_NUMBER.find(next)?.groupValues?.getOrNull(1) ?: return false
        return currentNum.equals(nextNum, ignoreCase = true)
    }

    /**
     * True when at least half of [header]'s cells are blank — the structural signature of a
     * continuation page's first Tabula row, as opposed to a fresh table's well-populated header.
     */
    private fun isBlankDominant(header: List<String>): Boolean {
        if (header.isEmpty()) return false
        val blank = header.count { it.isBlank() }
        return blank * 2 >= header.size
    }

    /**
     * Produces the combined data rows when merging [parent] and [continuation].
     *
     * - **Repeated header** (`parent.headers == continuation.headers`): the continuation's header
     *   row is a re-print and is discarded; only its data rows are appended.
     * - **Headerless / silent continuation**: the continuation's detected "header" row is really a
     *   data row, so it is prepended as a data row before the continuation's own rows — UNLESS it
     *   is fully blank (a spacer band), in which case it is dropped. All rows are normalised to
     *   `parent.headers.size`.
     */
    private fun mergeContinuationRows(
        parent: DocumentBlock.Table,
        continuation: DocumentBlock.Table,
    ): List<List<String>> {
        val extraRows = if (parent.headers == continuation.headers) {
            // Repeated header row — skip it.
            continuation.rows.map { normaliseRow(it, parent.headers.size) }
        } else {
            // Silent/headerless continuation — the "header" row is data. Keep it unless fully
            // blank (a header-band spacer carries no information).
            val headerRow = continuation.headers
            val prefix = if (headerRow.any { it.isNotBlank() }) {
                listOf(normaliseRow(headerRow, parent.headers.size))
            } else {
                emptyList()
            }
            prefix + continuation.rows.map { normaliseRow(it, parent.headers.size) }
        }
        return parent.rows + extraRows
    }
}
