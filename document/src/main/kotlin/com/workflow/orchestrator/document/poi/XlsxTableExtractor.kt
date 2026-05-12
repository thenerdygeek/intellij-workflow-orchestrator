package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.service.ImageExtractionService
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.FormulaEvaluator
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFPicture
import org.apache.poi.xssf.usermodel.XSSFPictureData
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream

/**
 * Extracts spreadsheet content from XLSX files into [DocumentBlock] lists via Apache POI.
 *
 * Each sheet becomes a [DocumentBlock.Heading] followed by a [DocumentBlock.Table].
 * Formulas are evaluated. Dates are formatted as ISO-8601 strings. Merged cells have the
 * merged value repeated across all cells in the merge range (row direction only — the spec
 * calls for left-to-right repetition within a row).
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

            for (sheet in wb) {
                val xssfSheet = sheet as? XSSFSheet ?: continue

                val visibility = try {
                    wb.getSheetVisibility(wb.getSheetIndex(xssfSheet))
                } catch (_: Exception) {
                    org.apache.poi.ss.usermodel.SheetVisibility.VISIBLE
                }
                val headingText = if (visibility != org.apache.poi.ss.usermodel.SheetVisibility.VISIBLE) {
                    "(hidden) ${sheet.sheetName}"
                } else {
                    sheet.sheetName
                }
                blocks += DocumentBlock.Heading(2, headingText)

                val rowIter = sheet.iterator()
                if (!rowIter.hasNext()) continue

                // Accumulates cell comments in row-major order as we walk cells.
                val sheetComments = mutableListOf<DocumentBlock.Comment>()

                // First row → headers
                val headerRow = rowIter.next()
                val lastCellNum = headerRow.lastCellNum.toInt().coerceAtLeast(0)
                val headers = (0 until lastCellNum).map { col ->
                    val cell = headerRow.getCell(col)
                    collectCellComment(cell, sheetComments)
                    CellFormatter.toCellString(cellOrMergedValue(cell, col, headerRow, xssfSheet, evaluator), evaluator)
                }

                if (headers.isEmpty()) continue

                // Subsequent rows → data rows
                val rows = mutableListOf<List<String>>()
                var rowsRead = 0

                while (rowIter.hasNext() && rowsRead < MAX_ROWS_PER_SHEET) {
                    val row = rowIter.next()
                    val cells = headers.indices.map { col ->
                        val cell = row.getCell(col)
                        collectCellComment(cell, sheetComments)
                        CellFormatter.toCellString(cellOrMergedValue(cell, col, row, xssfSheet, evaluator), evaluator)
                    }
                    rows += cells
                    rowsRead++

                    // Also collect comments from cells BEYOND the header arity — they're not part
                    // of the Table but they still carry review context the LLM should see.
                    val lastPhysical = row.lastCellNum.toInt()
                    if (lastPhysical > headers.size) {
                        for (col in headers.size until lastPhysical) {
                            collectCellComment(row.getCell(col), sheetComments)
                        }
                    }
                }

                blocks += DocumentBlock.Table(headers, rows)

                // Drain per-sheet comments AFTER the Table so the LLM sees them
                // immediately following the data they annotate, in row-major order.
                blocks += sheetComments

                // P2T3: image extraction — emit EmbeddedFileRef blocks after comments.
                if (imageService != null) {
                    blocks += collectSheetImages(xssfSheet)
                }

                if (rowIter.hasNext()) {
                    blocks += DocumentBlock.Paragraph("_(truncated at $MAX_ROWS_PER_SHEET rows)_")
                }
            }
        }

        return blocks
    }

    // ── Merged cell resolution ─────────────────────────────────────────────────

    /**
     * Returns the [Cell] whose value should be used for column [col] in [row].
     *
     * When the cell at ([row].rowNum, [col]) falls inside a merged region, returns the
     * top-left cell of that region so downstream code reads the actual value. This handles
     * horizontal merges (A2:C2 → all three columns show the same value) and the top row of
     * vertical merges (A2:A3 → both rows show A2's value).
     *
     * @param cell     The raw cell at [col] (may be `null` for blank cells inside a merged region).
     * @param col      Zero-based column index within the row.
     * @param row      The POI [Row] that [cell] belongs to.
     * @param sheet    The containing [XSSFSheet] (provides the merged region list).
     * @param evaluator Formula evaluator (passed through for type consistency; not used here).
     * @return The cell whose value should be displayed at ([row].rowNum, [col]).
     */
    private fun cellOrMergedValue(
        cell: Cell?,
        col: Int,
        row: Row,
        sheet: XSSFSheet,
        @Suppress("UNUSED_PARAMETER") evaluator: FormulaEvaluator,
    ): Cell? {
        // Fast path: cell exists and is not blank — no need to check merged regions.
        if (cell != null && cell.cellType != org.apache.poi.ss.usermodel.CellType.BLANK) {
            return cell
        }

        // Check whether (rowNum, col) falls inside a merged region whose top-left cell
        // holds the actual value.
        val rowNum = row.rowNum
        for (region: CellRangeAddress in sheet.mergedRegions) {
            if (region.isInRange(rowNum, col)) {
                // Return the top-left cell of the merged region.
                val firstRow = sheet.getRow(region.firstRow) ?: return cell
                return firstRow.getCell(region.firstColumn) ?: cell
            }
        }

        return cell
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

            val saved = try {
                service.save(bytes, docKey, name, mime)
            } catch (_: Exception) {
                return@mapNotNull DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null)
            }
            DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = saved.toString())
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

    companion object {
        /** Maximum data rows extracted per sheet before emitting a truncation marker. */
        const val MAX_ROWS_PER_SHEET = 50_000
    }
}
