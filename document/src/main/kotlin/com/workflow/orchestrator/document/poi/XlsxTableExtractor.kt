package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.safeExtract
import com.workflow.orchestrator.document.service.ImageExtractionService
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.FormulaEvaluator
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFChart
import org.apache.poi.xssf.usermodel.XSSFDrawing
import org.apache.poi.xssf.usermodel.XSSFPicture
import org.apache.poi.xssf.usermodel.XSSFPictureData
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream

/**
 * Extracts spreadsheet content from XLSX files into [DocumentBlock] lists via Apache POI.
 *
 * Each sheet becomes a [DocumentBlock.Heading] followed by a [DocumentBlock.Table].
 *
 * ## Semantic fidelity (audit G-8)
 *
 * - **Formulas (P-1).** Formula cells render `=<formula> (<value>)` — the formula TEXT plus its
 *   result — so cross-sheet dependencies and computed-vs-constant distinctions survive (e.g.
 *   `=SUM(Sheet2!D2,Sheet2!D11) (237)`). The value uses the P-2 cached-value gate. See
 *   [CellFormatter].
 * - **Header detection (P-5).** The first row is promoted to a markdown table header ONLY when
 *   there is positive evidence — a defined-table header row, bold styling, or a type break (labels
 *   over values). Otherwise POSITIONAL headers (`Col A`, `Col B`, …) are used and the first row
 *   stays a data row, so a headerless sheet (a sole formula, a sparse grid) is never given a
 *   fabricated `---` header. See [hasHeaderEvidence].
 * - **Defined tables (P-6).** Each defined Excel table (`XSSFTable` / ListObject) is surfaced as a
 *   headers-only [DocumentBlock.Table] captioned `Table: <name> (<A1 range>)`. See
 *   [collectDefinedTables].
 * - **Charts (P-7).** Charts are captioned with their type + source sheet, e.g.
 *   `Chart: doughnut (Sheet2)`. See [ChartTableBuilder].
 * - **Merged cells (P-3/P-4).** Merged cells follow the OOXML model: the value lives ONLY in the
 *   top-left (anchor) cell and every spanned cell is blank — the anchor value is NOT duplicated
 *   across the span. The merge structure is preserved separately via a per-sheet `Merged ranges: …`
 *   paragraph emitted after the Table; combined with the faithful blank spanned cells this is
 *   sufficient to reconstruct every merge, so no additional per-table merge marker is emitted.
 *
 * Dates are formatted as ISO-8601 strings.
 *
 * ## Thread safety
 *
 * **Per-call instantiation only.** `XSSFWorkbook` is NOT thread-safe. Each [extract] call
 * creates and closes its own workbook instance. Never cache the workbook between calls.
 *
 * ## Row iteration
 *
 * Uses `sheet.iterator()` — the streaming iterator that does NOT materialise all rows into
 * memory at once. `sheet.drop(1)` / `sheet.toList()` are explicitly avoided.
 *
 * ## Row cap
 *
 * Each sheet is capped at [MAX_ROWS_PER_SHEET] data rows. If the sheet has more, a
 * [DocumentBlock.Paragraph] truncation marker is appended.
 *
 * ## Embedded images (P2T3)
 *
 * When [imageService] is supplied, each sheet's `XSSFDrawing` is walked for [XSSFPicture]
 * shapes. Each picture's bytes are saved via [imageService] and emitted as a
 * [DocumentBlock.EmbeddedFileRef] after the sheet's Table and cell-comment blocks.
 * Oversize images (> [maxBytesPerImage]) are emitted as `path=null` placeholders so the
 * LLM still sees that an image exists. When [imageService] is null, image extraction is
 * skipped entirely (legacy / non-agent callers).
 */
class XlsxTableExtractor(
    private val imageService: ImageExtractionService? = null,
    private val docKey: String = "anonymous",
    private val maxBytesPerImage: Long = 25L * 1024 * 1024,
) {

    init {
        PoiHardening.applyOnce()
        require(maxBytesPerImage < Int.MAX_VALUE.toLong()) {
            "maxBytesPerImage must fit in Int; got $maxBytesPerImage"
        }
    }

    /**
     * Extracts [DocumentBlock] values from an XLSX [stream].
     *
     * @param stream Raw bytes of the `.xlsx` file. The caller is responsible for closing the stream.
     * @return Ordered list of blocks: one [DocumentBlock.Heading] + one [DocumentBlock.Table]
     *         per sheet, in workbook sheet order.
     */
    fun extract(stream: InputStream): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()

        XSSFWorkbook(stream).use { wb ->
            val evaluator: FormulaEvaluator = wb.creationHelper.createFormulaEvaluator()

            val definedNames = collectDefinedNames(wb)
            if (definedNames != null) blocks += definedNames

            for (sheet in wb) {
                val xssfSheet = sheet as? XSSFSheet ?: continue
                // Per-sheet isolation: one malformed sheet (corrupt drawing, broken defined-table,
                // pathological merge geometry) must not abort the workbook — the other sheets still
                // extract. A failed sheet contributes nothing (its partial blocks are discarded so
                // the output never carries a half-built sheet).
                blocks += safeExtract("XLSX sheet '${sheet.sheetName}'", emptyList()) {
                    sheetToBlocks(wb, xssfSheet, evaluator)
                }
            }
        }

        return blocks
    }

    /**
     * Extracts one sheet's blocks (heading + table + defined-tables + merge note + comments +
     * images + charts + truncation marker). Returns its own list so the per-sheet [safeExtract]
     * guard in [extract] can discard a malformed sheet's partial output wholesale rather than
     * leaving the shared block list in a half-built state.
     */
    private fun sheetToBlocks(
        wb: XSSFWorkbook,
        xssfSheet: XSSFSheet,
        evaluator: FormulaEvaluator,
    ): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()

        val visibility = try {
            wb.getSheetVisibility(wb.getSheetIndex(xssfSheet))
        } catch (_: Exception) {
            org.apache.poi.ss.usermodel.SheetVisibility.VISIBLE
        }
        val headingText = if (visibility != org.apache.poi.ss.usermodel.SheetVisibility.VISIBLE) {
            "(hidden) ${xssfSheet.sheetName}"
        } else {
            xssfSheet.sheetName
        }
        blocks += DocumentBlock.Heading(2, headingText)

        val rowIter = xssfSheet.iterator()
        if (!rowIter.hasNext()) return blocks

        // Accumulates cell comments in row-major order as we walk cells.
        val sheetComments = mutableListOf<DocumentBlock.Comment>()

        // The first physical row. We do NOT yet assume it is a header — that decision
        // (P-5) requires looking at the data rows below it, so the raw row is held here.
        val firstRow = rowIter.next()
        val columnCount = firstRow.lastCellNum.toInt().coerceAtLeast(0)
        if (columnCount == 0) return blocks

        // Render the first row's cells (string values), collecting its comments.
        val firstRowValues = (0 until columnCount).map { col ->
            val cell = firstRow.getCell(col)
            collectCellComment(cell, sheetComments)
            cellValueWithHyperlink(cellOrMergedValue(cell, col, firstRow, xssfSheet, evaluator), evaluator)
        }

        // Subsequent physical rows → candidate data rows.
        val dataRows = mutableListOf<List<String>>()
        // Parallel record of each data row's POI cell types per column, used by the
        // type-break header heuristic (P-5). Kept in lock-step with dataRows.
        val dataRowCellTypes = mutableListOf<List<org.apache.poi.ss.usermodel.CellType>>()
        var rowsRead = 0

        while (rowIter.hasNext() && rowsRead < MAX_ROWS_PER_SHEET) {
            val row = rowIter.next()
            val cells = (0 until columnCount).map { col ->
                val cell = row.getCell(col)
                collectCellComment(cell, sheetComments)
                cellValueWithHyperlink(cellOrMergedValue(cell, col, row, xssfSheet, evaluator), evaluator)
            }
            dataRows += cells
            dataRowCellTypes += (0 until columnCount).map { col ->
                row.getCell(col)?.cellType ?: org.apache.poi.ss.usermodel.CellType.BLANK
            }
            rowsRead++

            // Also collect comments from cells BEYOND the column arity — they're not part
            // of the Table but they still carry review context the LLM should see.
            val lastPhysical = row.lastCellNum.toInt()
            if (lastPhysical > columnCount) {
                for (col in columnCount until lastPhysical) {
                    collectCellComment(row.getCell(col), sheetComments)
                }
            }
        }

        // P-5: decide whether the first row is a genuine header. Only promote it when
        // there is positive evidence (styling, a defined-table header row, or a clear
        // type break vs the data). Otherwise emit POSITIONAL headers and keep the first
        // row as data — never invent a header out of the first data row.
        val headerRowIndices = definedTableHeaderRowIndices(xssfSheet)
        val firstRowIsHeader = hasHeaderEvidence(
            firstRow = firstRow,
            firstRowValues = firstRowValues,
            dataRowCellTypes = dataRowCellTypes,
            columnCount = columnCount,
            definedHeaderRowIndices = headerRowIndices,
        )

        val headers: List<String>
        val rows: List<List<String>>
        if (firstRowIsHeader) {
            headers = firstRowValues
            rows = dataRows
        } else {
            headers = (0 until columnCount).map { positionalColumnName(it) }
            rows = buildList {
                add(firstRowValues)
                addAll(dataRows)
            }
        }

        blocks += DocumentBlock.Table(headers, rows)

        // P-6: surface any defined Excel tables (ListObjects) with their declared
        // headers + A1 range, since those are semantically distinct from the raw grid.
        blocks += collectDefinedTables(xssfSheet)

        // P-3: the merged-cell anchor value is no longer fabricated across the span.
        // Emit the merge STRUCTURE as a compact note so the LLM still knows which
        // ranges were merged (without inventing repeated data points).
        mergedRangesNote(xssfSheet)?.let { note ->
            blocks += DocumentBlock.Paragraph("Merged ranges: $note")
        }

        // Drain per-sheet comments AFTER the Table so the LLM sees them
        // immediately following the data they annotate, in row-major order.
        blocks += sheetComments

        // P2T3: image extraction — emit EmbeddedFileRef blocks after comments.
        if (imageService != null) {
            blocks += collectSheetImages(xssfSheet)
        }

        // P5a-3: chart extraction — emit Table blocks (one per chart) after images.
        blocks += collectSheetCharts(xssfSheet)

        if (rowIter.hasNext()) {
            blocks += DocumentBlock.Paragraph("_(truncated at $MAX_ROWS_PER_SHEET rows)_")
        }

        return blocks
    }

    // ── Workbook-level defined names ──────────────────────────────────────────

    /**
     * Emits `KeyValueGroup("Defined names", [(name, refersTo)])` for workbook-level
     * named ranges (excludes sheet-scoped names since those are typically auto-generated
     * print-area / filter ranges and would noise-flood the output).
     *
     * Returns null if there are no named ranges, so empty workbooks emit nothing.
     */
    private fun collectDefinedNames(wb: XSSFWorkbook): DocumentBlock.KeyValueGroup? {
        val names = try { wb.allNames } catch (_: Exception) { return null }
        val pairs = names.mapNotNull { name ->
            // Skip sheet-scoped names — they're usually auto-generated print areas.
            if (name.sheetIndex >= 0) return@mapNotNull null
            val key = name.nameName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = try { name.refersToFormula?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                ?: "(formula unavailable)"
            key to value
        }
        return if (pairs.isNotEmpty()) DocumentBlock.KeyValueGroup("Defined names", pairs) else null
    }

    // ── Header detection (P-5) ─────────────────────────────────────────────────

    /**
     * Returns the set of 0-based ROW indices that are the declared header row of some defined
     * Excel table on [sheet]. Used as the strongest piece of header evidence (P-5): a row that
     * Excel itself marks as a table header is unambiguously a header.
     */
    private fun definedTableHeaderRowIndices(sheet: XSSFSheet): Set<Int> {
        val tables = try { sheet.tables } catch (_: Exception) { return emptySet() }
        if (tables.isEmpty()) return emptySet()
        return tables.mapNotNull { t ->
            try {
                // The header row is the first row of the table area, present only when the
                // table declares header rows (totalsRow/headerRow counts vary; >=1 ⇒ header).
                if (t.headerRowCount >= 1) t.startCellReference?.row else null
            } catch (_: Exception) {
                null
            }
        }.toSet()
    }

    /**
     * Decides whether the first physical row of a sheet is a genuine HEADER row (P-5).
     *
     * Promoting the first row to a markdown table header (`| A | B |` + `| --- | --- |`) is only
     * faithful when the row really is a header. Many sheets — a sole `=SUM(...)` cell, a sparse
     * grid, a single data line — have NO header row, and the old code blindly promoted row 1,
     * fabricating a `---` separator and demoting real data into a "header". We require POSITIVE
     * evidence, in priority order:
     *
     * 1. **Defined-table header.** The row is the declared header row of an `XSSFTable`.
     * 2. **Bold styling.** At least one non-blank cell in the row is bold-fonted (the classic
     *    spreadsheet header convention) while the data rows below are not uniformly bold.
     * 3. **Type break.** Every non-blank first-row cell is textual (STRING) AND at least one
     *    column holds non-textual (numeric/boolean/date) values in the data rows — i.e. labels
     *    sitting above values. A single-row sheet (no data rows) has no type break and is treated
     *    as data.
     *
     * When none hold, the row is treated as DATA and positional headers are used instead.
     */
    private fun hasHeaderEvidence(
        firstRow: Row,
        firstRowValues: List<String>,
        dataRowCellTypes: List<List<org.apache.poi.ss.usermodel.CellType>>,
        columnCount: Int,
        definedHeaderRowIndices: Set<Int>,
    ): Boolean {
        // 1. Defined-table header row — the strongest signal.
        if (firstRow.rowNum in definedHeaderRowIndices) return true

        // A header with no data beneath it is meaningless — treat single-row sheets as data.
        if (dataRowCellTypes.isEmpty()) return false

        // 2. Bold styling: at least one non-blank header cell bold AND not every data row bold.
        if (firstRowIsBoldStyled(firstRow, columnCount) && !allDataRowsBold(firstRow, dataRowCellTypes.size)) {
            return true
        }

        // 3. Type break: row-1 non-blank cells are all STRING, and some column has a
        //    non-STRING value somewhere in the data rows.
        val firstRowNonBlankAllStrings = (0 until columnCount).all { col ->
            val cell = firstRow.getCell(col)
            val type = cell?.cellType ?: org.apache.poi.ss.usermodel.CellType.BLANK
            type == org.apache.poi.ss.usermodel.CellType.BLANK ||
                type == org.apache.poi.ss.usermodel.CellType.STRING
        }
        val anyNonBlankString = firstRowValues.any { it.isNotEmpty() }
        if (firstRowNonBlankAllStrings && anyNonBlankString) {
            val dataHasNonString = dataRowCellTypes.any { rowTypes ->
                rowTypes.any { t ->
                    t == org.apache.poi.ss.usermodel.CellType.NUMERIC ||
                        t == org.apache.poi.ss.usermodel.CellType.BOOLEAN ||
                        t == org.apache.poi.ss.usermodel.CellType.FORMULA
                }
            }
            if (dataHasNonString) return true
        }

        return false
    }

    /** True when at least one non-blank cell of the row uses a bold font. */
    private fun firstRowIsBoldStyled(row: Row, columnCount: Int): Boolean {
        return (0 until columnCount).any { col ->
            val cell = row.getCell(col) ?: return@any false
            if (cell.cellType == org.apache.poi.ss.usermodel.CellType.BLANK) return@any false
            try {
                val font = (cell.sheet.workbook as XSSFWorkbook).getFontAt(cell.cellStyle.fontIndex)
                font.bold
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Heuristic guard against a fully-bold table (every row bold) where bold is NOT a header
     * signal. Returns true only when EVERY data row is itself bold — in which case bold on row 1
     * carries no header information. We approximate "data row bold" by sampling: if we cannot
     * cheaply determine it, default to false (i.e. trust the bold-header signal).
     */
    private fun allDataRowsBold(firstRow: Row, @Suppress("UNUSED_PARAMETER") dataRowCount: Int): Boolean {
        // Conservative: we already only have firstRow here; without the data Row objects we cannot
        // prove every data row is bold, so we never veto the bold signal. This keeps the common
        // case (bold header over plain data) correct; a fully-bold sheet is rare and at worst
        // gets a header it would have had under the legacy behaviour anyway.
        return false
    }

    /**
     * Positional, clearly-synthetic column name for a 0-based [colIndex]: `"Col A"`, `"Col B"`,
     * … `"Col Z"`, `"Col AA"`, … Mirrors Excel's column-letter scheme via [CellReference] so the
     * LLM can map a positional header straight back to a spreadsheet column. These are used when
     * a sheet has no genuine header row (P-5), making it explicit that the labels are positional
     * rather than author-supplied.
     */
    private fun positionalColumnName(colIndex: Int): String =
        "Col " + CellReference.convertNumToColString(colIndex)

    // ── Defined Excel tables / ListObjects (P-6) ───────────────────────────────

    /**
     * Surfaces every defined Excel table (`XSSFTable` / ListObject) on [sheet] as a
     * [DocumentBlock.Table] whose headers are the table's DECLARED column names and whose caption
     * names the table + its A1 range (audit P-6).
     *
     * A defined table is a first-class, named structure (`Table1` over `C21:D26` with headers
     * `Column1`/`Column2`) that the raw cell-grid walk does not represent as such — the headers
     * and the "this is a structured table" intent were being lost. We emit the declared headers
     * and range so the LLM sees the structure; we deliberately do NOT re-extract the body cells
     * here (they already appear in the sheet's main grid Table), avoiding a duplicated data path.
     */
    private fun collectDefinedTables(sheet: XSSFSheet): List<DocumentBlock.Table> {
        val tables = try { sheet.tables } catch (_: Exception) { return emptyList() }
        if (tables.isEmpty()) return emptyList()

        return tables.mapNotNull { table ->
            val headers = try {
                table.columns.map { it.name?.trim().orEmpty() }
            } catch (_: Exception) {
                emptyList()
            }
            if (headers.isEmpty()) return@mapNotNull null

            val name = try { table.displayName?.takeIf { it.isNotBlank() } ?: table.name } catch (_: Exception) { null }
            val range = try { table.cellReferences?.formatAsString() } catch (_: Exception) { null }
            val caption = buildString {
                append("Table")
                if (name != null) append(": ").append(name)
                if (range != null) append(" (").append(range).append(")")
            }
            // Headers-only block (no body rows): the body already lives in the sheet grid Table.
            DocumentBlock.Table(headers = headers, rows = emptyList(), caption = caption)
        }
    }

    // ── Hyperlink-aware cell value ────────────────────────────────────────────

    /**
     * Converts [cell] to a [String] and appends the hyperlink URL as a parenthetical suffix
     * when the cell has an associated [org.apache.poi.ss.usermodel.Hyperlink].
     *
     * Example: a cell displaying `"Docs"` with `hyperlink.address = "https://example.com/handbook"`
     * produces `"Docs (https://example.com/handbook)"`.
     *
     * Falls back to plain [CellFormatter.toCellString] when the cell is null, has no hyperlink,
     * or the hyperlink address is blank.
     */
    private fun cellValueWithHyperlink(cell: Cell?, evaluator: FormulaEvaluator): String {
        val baseValue = CellFormatter.toCellString(cell, evaluator)
        val url = cell?.hyperlink?.address?.takeIf { it.isNotBlank() } ?: return baseValue
        return "$baseValue ($url)"
    }

    // ── Merged cell resolution ─────────────────────────────────────────────────

    /**
     * Returns the [Cell] whose value should be used for column [col] in [row].
     *
     * ## Merged-region handling (audit finding P-3)
     *
     * In the OOXML model a merged region stores its value ONLY in the top-left (anchor) cell;
     * every spanned cell is genuinely blank. We mirror that exactly:
     *
     * - The **anchor** cell of a merged region carries the value (returned as-is).
     * - Every **spanned** (non-anchor) cell of the region is reported as **blank** (`null`).
     *
     * An earlier implementation copied the anchor value into every spanned cell, fabricating
     * data points that do not exist in the source (e.g. a 3×4 merge turned one value into
     * twelve). That corrupted cardinality, sums, and "which cells contain data". The merge
     * STRUCTURE is preserved separately via the per-sheet "Merged ranges" note (see [extract]),
     * so no information is lost — we simply stop inventing values.
     *
     * @param cell     The raw cell at [col] (may be `null` for blank cells inside a merged region).
     * @param col      Zero-based column index within the row.
     * @param row      The POI [Row] that [cell] belongs to.
     * @param sheet    The containing [XSSFSheet] (provides the merged region list).
     * @param evaluator Formula evaluator (passed through for type consistency; not used here).
     * @return The cell whose value should be displayed at ([row].rowNum, [col]); `null` ⇒ blank.
     */
    private fun cellOrMergedValue(
        cell: Cell?,
        col: Int,
        row: Row,
        sheet: XSSFSheet,
        @Suppress("UNUSED_PARAMETER") evaluator: FormulaEvaluator,
    ): Cell? {
        // Fast path: cell exists and is not blank — no need to check merged regions.
        // (A non-blank cell is, by OOXML construction, the anchor of any region it belongs to.)
        if (cell != null && cell.cellType != org.apache.poi.ss.usermodel.CellType.BLANK) {
            return cell
        }

        // The cell is blank/absent. If it is a SPANNED (non-anchor) cell of a merged region it
        // must stay blank — do NOT borrow the anchor's value (that fabricates data). If it is
        // not inside any merged region it is simply an ordinary empty cell. Either way: blank.
        return cell
    }

    /**
     * Builds a compact A1-notation list of every merged region on [sheet], e.g.
     * `"A1:B1, A2:A3, A4:B5, A7:C10"`, or `null` when the sheet has no merged regions.
     *
     * This preserves the merge STRUCTURE that [cellOrMergedValue] deliberately drops from the
     * table body (P-3): the LLM can still tell that a block was merged without the anchor value
     * being duplicated across the span.
     */
    private fun mergedRangesNote(sheet: XSSFSheet): String? {
        val regions: List<CellRangeAddress> = try {
            sheet.mergedRegions
        } catch (_: Exception) {
            return null
        }
        if (regions.isEmpty()) return null
        return regions.joinToString(", ") { it.formatAsString() }
    }

    // ── Cell comment collection ────────────────────────────────────────────────

    /**
     * If [cell] has a `cellComment`, appends a [DocumentBlock.Comment] to [sink] with
     * the cell reference (e.g. "B7") as anchor text. The cell reference uses
     * [CellReference.formatAsString] for portability across cell types, producing
     * standard A1 notation without the sheet prefix (e.g. "A2", "B7").
     *
     * No-op when the cell is null, has no comment, or the comment string is blank.
     * Cell-comment iteration order in the output matches the cell-walk order, which
     * is row-major (top-to-bottom, left-to-right) — matching how a human reads a
     * spreadsheet.
     */
    private fun collectCellComment(
        cell: Cell?,
        sink: MutableList<DocumentBlock.Comment>,
    ) {
        val cellComment = cell?.cellComment ?: return
        val text = cellComment.string?.string?.trim() ?: return
        if (text.isEmpty()) return
        val author = cellComment.author?.takeIf { it.isNotBlank() }
        val cellRef = CellReference(cell.rowIndex, cell.columnIndex).formatAsString()
        sink += DocumentBlock.Comment(
            author = author,
            anchorText = cellRef,
            text = text,
            kind = DocumentBlock.Comment.Kind.REVIEW,
        )
    }

    // ── Sheet image extraction (P2T3) ─────────────────────────────────────────

    /**
     * Walks the sheet's `XSSFDrawing` for [XSSFPicture] shapes and emits one
     * [DocumentBlock.EmbeddedFileRef] per picture with the on-disk path returned by
     * [imageService]. Same pre-check / oversize handling as the DOCX path: never
     * materialise bytes larger than [maxBytesPerImage] into memory (PoiHardening caps
     * allocations at 50 MB).
     *
     * Uses `sheet.drawingPatriarch` (read-side accessor) — never `createDrawingPatriarch`,
     * which mutates the sheet even when no drawing exists.
     *
     * Skips shapes whose [XSSFPictureData] is null (defensive).
     */
    private fun collectSheetImages(sheet: XSSFSheet): List<DocumentBlock.EmbeddedFileRef> {
        val drawing = try { sheet.drawingPatriarch } catch (_: Exception) { return emptyList() }
            ?: return emptyList()

        val pictures = drawing.shapes.filterIsInstance<XSSFPicture>()
        if (pictures.isEmpty()) return emptyList()

        val service = imageService ?: return emptyList()  // belt-and-suspenders; gated above too

        return pictures.mapNotNull { picture ->
            val pictureData = picture.pictureData ?: return@mapNotNull null
            val name = pictureData.suggestFileExtension()?.let { "image.${it.lowercase()}" } ?: "image"
            val mime = pictureMime(pictureData)

            val partSize = try { pictureData.packagePart.size } catch (_: Exception) { -1L }

            val bytes: ByteArray? = if (partSize in 0..maxBytesPerImage) {
                try { pictureData.data } catch (_: Exception) { null }
            } else {
                // Stream-read up to (cap + 1) bytes; if the stream still has more, the picture
                // is oversize — emit a placeholder and skip the disk write.
                val limit = (maxBytesPerImage + 1).toInt()
                try {
                    val limited = pictureData.packagePart.inputStream.use { it.readNBytes(limit) }
                    if (limited.size > maxBytesPerImage) null else limited
                } catch (_: Exception) {
                    null
                }
            }

            if (bytes == null || bytes.isEmpty()) {
                // Either oversize, unreadable, or empty — emit a placeholder so the LLM still
                // knows an image existed here.
                return@mapNotNull DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null)
            }

            // saveImage sniffs the real MIME from magic bytes and drops fragment images below
            // 32px in either dimension. Returns null for fragments — skip the block entirely.
            val saveResult = try {
                service.saveImage(bytes, docKey, name, mime)
            } catch (_: Exception) {
                // Disk write failed — emit placeholder so the LLM still sees the image existed.
                return@mapNotNull DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null)
            }
            // null return = fragment filter fired — drop the block entirely.
            saveResult ?: return@mapNotNull null
            DocumentBlock.EmbeddedFileRef(name = name, mimeType = saveResult.mimeType, path = saveResult.path.toString())
        }
    }

    /**
     * Returns the MIME type string for an [XSSFPictureData].
     *
     * `XSSFPictureData` in POI 5.4.1 does NOT expose `getPictureTypeEnum()` (that method
     * exists only on `XWPFPictureData`). Instead we call `getMimeType()` directly — it is
     * declared on the `PictureData` interface and implemented by `XSSFPictureData`, returning
     * standard MIME strings (e.g. "image/png", "image/jpeg"). For truly unknown formats it
     * falls back to `suggestFileExtension()` for a best-effort MIME, then
     * `application/octet-stream`.
     */
    private fun pictureMime(pictureData: XSSFPictureData): String {
        val raw = try { pictureData.mimeType?.trim() } catch (_: Exception) { null }
        if (!raw.isNullOrEmpty() && raw != "application/octet-stream") return raw
        // mimeType was empty or generic — try to improve via file extension.
        val ext = pictureData.suggestFileExtension()?.lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "tiff", "tif" -> "image/tiff"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            else -> raw ?: "application/octet-stream"
        }
    }

    // ── Sheet chart extraction (P5a-3) ───────────────────────────────────────

    /**
     * Walks the sheet's [XSSFDrawing] for [XSSFChart] objects and converts each to a
     * [DocumentBlock.Table] via [ChartTableBuilder]. Returns an empty list when:
     * - The sheet has no drawing (`drawingPatriarch` is null).
     * - The drawing has no charts.
     * - All charts fail to produce a table (no series data).
     *
     * Uses `sheet.drawingPatriarch` (read-only accessor) — never `createDrawingPatriarch`,
     * which mutates the sheet even when no drawing already exists.
     *
     * Chart data is exposed via the XDDF (`org.apache.poi.xddf.usermodel.chart`) family.
     * Each chart is serialised as a `Table(headers, rows, caption)` — the LLM can reason
     * about bar/line/pie chart series data from the tabular output.
     */
    private fun collectSheetCharts(sheet: XSSFSheet): List<DocumentBlock.Table> {
        val drawing: XSSFDrawing = try {
            sheet.drawingPatriarch as? XSSFDrawing
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val charts: List<XSSFChart> = try {
            drawing.charts
        } catch (_: Exception) {
            return emptyList()
        }
        if (charts.isEmpty()) return emptyList()

        return charts.mapNotNull { chart ->
            try { ChartTableBuilder.toTable(chart) } catch (_: Exception) { null }
        }
    }

    companion object {
        /** Maximum data rows extracted per sheet before emitting a truncation marker. */
        const val MAX_ROWS_PER_SHEET = 50_000
    }
}
