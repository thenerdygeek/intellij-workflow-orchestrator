package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.service.ImageExtractionService
import org.apache.poi.xslf.usermodel.XSLFComment
import org.apache.poi.xslf.usermodel.XSLFPictureData
import org.apache.poi.xslf.usermodel.XSLFPictureShape
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTable
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xslf.usermodel.XMLSlideShow
import java.io.InputStream

/**
 * Extracts slide content from PPTX files into [DocumentBlock] lists via Apache POI XSLF.
 *
 * For each slide, the extractor emits:
 * 1. A [DocumentBlock.Heading] (`level=2`) carrying the slide number and title.
 * 2. [DocumentBlock.Paragraph] blocks for each non-title text frame on the slide.
 * 3. A [DocumentBlock.Table] for each [XSLFTable] on the slide.
 * 4. A speaker-notes [DocumentBlock.Paragraph] (prefixed `> Notes: …`) when the slide
 *    has non-empty speaker notes.
 * 5. [DocumentBlock.Comment] blocks (kind=REVIEW, anchorText=null) for each slide-level
 *    review comment — emitted after speaker notes, in POI list order.
 * 6. [DocumentBlock.EmbeddedFileRef] for each [XSLFPictureShape] on the slide (P2T4) —
 *    only when [imageService] is supplied. Bytes are streamed via
 *    [XSLFPictureData.getInputStream] so PoiHardening's 50 MB IOUtils cap is bypassed
 *    entirely; the [maxBytesPerImage] limit is enforced locally.
 *
 * ## Thread safety
 *
 * Per-call instantiation only. `XMLSlideShow` is NOT thread-safe. Never cache.
 *
 * ## Title extraction
 *
 * `XSLFSlide.title` returns the text of the first title-placeholder shape, or `null`.
 * When `null` or blank the heading is `"Slide N"` (no colon suffix).
 *
 * ## Speaker notes
 *
 * `XSLFSlide.notes` returns the `XSLFNotes` object. Notes have two placeholders: index 0
 * is the slide-number placeholder (a small numeric string), index 1 is the body. We skip
 * any placeholder whose text is numeric-only (slide-number placeholder) and emit the rest.
 */
class PptxExtractor(
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
     * Extracts [DocumentBlock] values from a PPTX [stream], one set of blocks per slide.
     *
     * @param stream Raw bytes of the `.pptx` file. The caller is responsible for closing the stream.
     * @return Ordered list of blocks in slide order. Within each slide, content appears in
     *         shape-index order followed by speaker notes and then slide-level review comments.
     */
    fun extract(stream: InputStream): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()

        XMLSlideShow(stream).use { presentation ->
            presentation.slides.forEachIndexed { idx, slide ->
                val slideNumber = idx + 1
                blocks += slideToBlocks(slide, slideNumber)
            }
        }

        return blocks
    }

    // ── Per-slide conversion ───────────────────────────────────────────────────

    private fun slideToBlocks(slide: XSLFSlide, slideNumber: Int): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()

        // Slide heading
        val title = slide.title?.trim()
        val headingText = if (title.isNullOrEmpty()) "Slide $slideNumber" else "Slide $slideNumber: $title"
        blocks += DocumentBlock.Heading(2, headingText)

        // Shapes — skip the title shape itself to avoid duplicate text
        val titleShape = slide.shapes.firstOrNull { it is XSLFTextShape && isTitlePlaceholder(it) }

        for (shape: XSLFShape in slide.shapes) {
            if (shape === titleShape) continue

            when (shape) {
                is XSLFTable -> {
                    val tableBlock = tableToBlock(shape) ?: continue
                    blocks += tableBlock
                }
                is XSLFTextShape -> {
                    val text = shape.text?.trim() ?: continue
                    if (text.isNotEmpty()) {
                        blocks += DocumentBlock.Paragraph(text)
                    }
                }
                is XSLFPictureShape -> {
                    if (imageService != null) {
                        extractPictureBlock(shape)?.let { blocks += it }
                    }
                }
            }
        }

        // Speaker notes
        val notesBlock = extractNotes(slide)
        if (notesBlock != null) {
            blocks += notesBlock
        }

        // Slide-level review comments (PPTX comments are slide-level, not text-anchored)
        val slideComments = extractSlideComments(slide)
        blocks += slideComments

        return blocks
    }

    // ── Title placeholder detection ────────────────────────────────────────────

    /**
     * Returns `true` if [shape] is a title-type placeholder.
     *
     * PPTX placeholder types for titles include TITLE (15) and CENTER_TITLE (3).
     * POI exposes `XSLFTextShape.textType` for this purpose.
     */
    private fun isTitlePlaceholder(shape: XSLFTextShape): Boolean {
        return try {
            val placeholder = shape.textType ?: return false
            placeholder.name.contains("TITLE", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    // ── Table conversion ───────────────────────────────────────────────────────

    private fun tableToBlock(table: XSLFTable): DocumentBlock? {
        val rowCount = table.numberOfRows
        if (rowCount == 0) return null

        val colCount = table.numberOfColumns
        if (colCount == 0) return null

        // First row → headers
        val headers = (0 until colCount).map { col ->
            table.getCell(0, col)?.text?.trim() ?: ""
        }

        if (headers.all { it.isEmpty() }) return null

        // Subsequent rows → data rows
        val dataRows = (1 until rowCount).map { row ->
            (0 until colCount).map { col ->
                table.getCell(row, col)?.text?.trim() ?: ""
            }
        }

        return DocumentBlock.Table(headers, dataRows)
    }

    // ── Speaker notes extraction ───────────────────────────────────────────────

    /**
     * Extracts speaker notes from [slide], returning a [DocumentBlock.Paragraph] prefixed
     * with `"> Notes: "`, or `null` if the slide has no non-empty notes.
     *
     * The notes slide has two placeholders:
     * - Index 0: the slide-number placeholder (usually a small digit string).
     * - Index 1+: the actual note body text.
     *
     * We skip any placeholder whose text consists solely of digits (the slide-number
     * placeholder). We also skip blank placeholders.
     */
    private fun extractNotes(slide: XSLFSlide): DocumentBlock.Paragraph? {
        val notes = try { slide.notes } catch (_: Exception) { return null } ?: return null

        val notesText = notes.placeholders
            .filter { placeholder ->
                val text = placeholder.text?.trim() ?: return@filter false
                text.isNotEmpty() && !text.all { it.isDigit() }
            }
            .joinToString("\n") { it.text?.trim() ?: "" }
            .trim()

        if (notesText.isEmpty()) return null
        return DocumentBlock.Paragraph("> Notes: $notesText")
    }

    // ── Slide-level review comments ────────────────────────────────────────────

    /**
     * Returns `Comment(REVIEW, author, anchorText=null, text)` blocks for each slide-level
     * review comment on [slide]. PPTX comments don't anchor to specific text — they apply
     * to the whole slide — so [DocumentBlock.Comment.anchorText] is always null.
     *
     * Empty/blank comments are skipped. If the POI call throws (malformed comments part),
     * returns an empty list rather than failing the whole extraction.
     *
     * POI 5.4.1 exposes `XSLFSlide.getComments()` which returns `List<XSLFComment>`. Each
     * [XSLFComment] carries `getAuthor()` (resolved from the comment-authors part) and
     * `getText()`.
     */
    private fun extractSlideComments(slide: XSLFSlide): List<DocumentBlock.Comment> {
        val comments: List<XSLFComment> = try {
            slide.comments
        } catch (_: Exception) {
            return emptyList()
        } ?: return emptyList()
        return comments.mapNotNull { c ->
            val text = c.text?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            DocumentBlock.Comment(
                author = c.author?.takeIf { it.isNotBlank() },
                anchorText = null,
                text = text,
                kind = DocumentBlock.Comment.Kind.REVIEW,
            )
        }
    }

    // ── Picture shape extraction (P2T4) ───────────────────────────────────────

    /**
     * Saves the picture's bytes via [imageService] and returns a
     * [DocumentBlock.EmbeddedFileRef] with the on-disk path. Returns null when no service
     * is wired (gated above too, defensive). Returns an [EmbeddedFileRef] with path=null
     * when the picture exceeds [maxBytesPerImage] or when the save fails.
     *
     * Uses [XSLFPictureData.getInputStream] so byte materialisation respects the cap and
     * avoids the PoiHardening IOUtils 50 MB allocation limit.
     */
    private fun extractPictureBlock(shape: XSLFPictureShape): DocumentBlock.EmbeddedFileRef? {
        val service = imageService ?: return null
        val pictureData = shape.pictureData ?: return null

        val mime = pictureMime(pictureData)
        val name = pictureData.fileName?.takeIf { it.isNotBlank() }
            ?: "image.${pictureData.suggestFileExtension()?.lowercase() ?: "bin"}"

        // Use streaming getInputStream + readNBytes(cap+1) to honour the size cap without
        // ever loading more than `cap+1` bytes into memory.
        val limit = (maxBytesPerImage + 1).toInt()
        val bytes = try {
            pictureData.inputStream.use { it.readNBytes(limit) }
        } catch (_: Exception) {
            return DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null)
        }

        if (bytes.size > maxBytesPerImage) {
            // Oversize — emit placeholder so the LLM still sees an image existed.
            return DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null)
        }
        if (bytes.isEmpty()) return null  // empty stream — skip entirely

        val saved = try {
            service.save(bytes, docKey, name, mime)
        } catch (_: Exception) {
            return DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null)
        }
        return DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = saved.toString())
    }

    /**
     * Returns the MIME type string for an [XSLFPictureData].
     *
     * POI 5.4.1's `XSLFPictureData` exposes `getContentType()` (declared on
     * `POIXMLDocumentPart`), which returns the OPC content-type string. We use that as
     * the primary source; if it's empty or the generic octet-stream fallback, we derive
     * from `suggestFileExtension()`.
     *
     * NOTE: `XSLFPictureData` does NOT have `getMimeType()` (that lives on `XSSFPictureData`
     * via the `PictureData` interface's XSSF implementation). Use `getContentType()` here.
     */
    private fun pictureMime(pictureData: XSLFPictureData): String {
        val raw = pictureData.contentType?.takeIf { it.isNotBlank() } ?: ""
        if (raw.isNotEmpty() && raw != "application/octet-stream") return raw
        // Fallback: derive from suggestFileExtension().
        val ext = pictureData.suggestFileExtension()?.lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "tiff", "tif" -> "image/tiff"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }
}
