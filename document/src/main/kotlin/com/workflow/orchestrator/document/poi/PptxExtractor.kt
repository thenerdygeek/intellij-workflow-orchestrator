package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.safeExtract
import com.workflow.orchestrator.document.service.ImageExtractionService
import org.apache.poi.common.usermodel.HyperlinkType
import org.apache.poi.xslf.usermodel.XSLFComment
import org.apache.poi.xslf.usermodel.XSLFGraphicFrame
import org.apache.poi.xslf.usermodel.XSLFGroupShape
import org.apache.poi.xslf.usermodel.XSLFObjectShape
import org.apache.poi.xslf.usermodel.XSLFPictureData
import org.apache.poi.xslf.usermodel.XSLFPictureShape
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTable
import org.apache.poi.xslf.usermodel.XSLFTextRun
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
 * Shape iteration (P3T5): each shape on [XSLFSlide] is dispatched via [handleShape], which
 * recurses into [XSLFGroupShape] children at arbitrary depth so text/tables/pictures nested
 * inside grouped shapes are extracted in declaration order alongside top-level content.
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
     * After all slide blocks are emitted, any SmartArt data-model parts found in the
     * presentation's OPC package are extracted via [SmartArtExtractor] and appended as
     * flat [DocumentBlock.ListBlock] values. SmartArt in PPTX is stored in the same
     * diagramData content type as DOCX, so the extractor is shared. Visual hierarchy is
     * dropped — text-only extraction per the P5a-4 spec.
     *
     * @param stream Raw bytes of the `.pptx` file. The caller is responsible for closing the stream.
     * @return Ordered list of blocks in slide order. Within each slide, content appears in
     *         shape-index order followed by speaker notes and then slide-level review comments.
     *         SmartArt ListBlocks are appended after all slides.
     */
    fun extract(stream: InputStream): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()

        XMLSlideShow(stream).use { presentation ->
            presentation.slides.forEachIndexed { idx, slide ->
                val slideNumber = idx + 1
                // Per-slide isolation: one malformed slide (broken shape tree, corrupt notes part)
                // must not abort the deck — the remaining slides still extract.
                blocks += safeExtract("PPTX slide $slideNumber", emptyList()) {
                    slideToBlocks(slide, slideNumber)
                }
            }

            // P5a-4: SmartArt text extraction. Each diagramData part becomes one flat ListBlock.
            try {
                blocks += SmartArtExtractor.extract(presentation.getPackage())
            } catch (_: Exception) {
                // Package access failure — SmartArt extraction is non-critical.
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
            // Per-shape isolation: a single broken shape (corrupt table cell, picture-data fault,
            // group recursion failure) must not lose the slide's other shapes.
            blocks += safeExtract("PPTX slide $slideNumber shape '${shapeLabel(shape)}'", emptyList()) {
                handleShape(shape)
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

    /** Best-effort shape name for the per-shape isolation WARN log; never throws. */
    private fun shapeLabel(shape: XSLFShape): String =
        try { shape.shapeName?.takeIf { it.isNotBlank() } ?: shape::class.java.simpleName }
        catch (_: Exception) { shape::class.java.simpleName }

    // ── Per-shape dispatch (recursive for group shapes) ───────────────────────

    /**
     * Converts a single [shape] into zero or more [DocumentBlock] values.
     *
     * For [XSLFGroupShape] the method recurses into the group's children in their declared
     * order, supporting arbitrary nesting depth (group-inside-group). All other shape types
     * are handled by their respective extraction paths.
     */
    private fun handleShape(shape: XSLFShape): List<DocumentBlock> {
        return when (shape) {
            is XSLFTable -> {
                val tableBlock = tableToBlock(shape) ?: return emptyList()
                listOf(tableBlock)
            }
            is XSLFTextShape -> {
                val text = hyperlinkAwareText(shape).trim()
                if (text.isNotEmpty()) listOf(DocumentBlock.Paragraph(text)) else emptyList()
            }
            is XSLFPictureShape -> {
                if (imageService != null) extractPictureBlock(shape)?.let { listOf(it) } ?: emptyList()
                else emptyList()
            }
            // IMG-3: an OLE/embedded object (a linked spreadsheet, a PowerPoint.Slide object,
            // …) is an XSLFObjectShape — a subtype of XSLFGraphicFrame. Match it BEFORE the
            // generic graphicFrame branch and emit a presence placeholder so a slide dominated
            // by a full-canvas OLE object no longer renders empty.
            is XSLFObjectShape -> {
                val progId = try { shape.progId?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                val name = progId ?: try { shape.shapeName?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                listOf(DocumentBlock.EmbeddedObjectRef(kind = DocumentBlock.EmbeddedObjectRef.Kind.OLE, name = name))
            }
            is XSLFGraphicFrame -> {
                // P5a-3: chart extraction. PPTX charts are wrapped in XSLFGraphicFrame shapes;
                // XSLFGraphicFrame.hasChart() detects them, getChart() returns the XSLFChart.
                // XSLFChart extends XDDFChart, so ChartTableBuilder handles it uniformly.
                if (shape.hasChart()) {
                    try {
                        val chart = shape.chart ?: return emptyList()
                        ChartTableBuilder.toTable(chart)?.let { listOf(it) } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else if (frameHasDiagram(shape)) {
                    // IMG-3: SmartArt diagram. The diagram TEXT still surfaces via
                    // SmartArtExtractor (appended after all slides); this placeholder marks the
                    // diagram's on-slide position/identity so it isn't invisible.
                    val name = try { shape.shapeName?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                    listOf(DocumentBlock.EmbeddedObjectRef(kind = DocumentBlock.EmbeddedObjectRef.Kind.SMARTART, name = name))
                } else {
                    emptyList()
                }
            }
            is XSLFGroupShape -> {
                // Recurse into children in their declared order; handles arbitrary nesting depth.
                shape.shapes.flatMap { child -> handleShape(child) }
            }
            else -> emptyList()
        }
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

    // ── Hyperlink-aware text (HX-1 PPTX variant) ──────────────────────────────

    /**
     * Builds the visible text of a [shape] (a text box, placeholder, or table cell — all
     * [XSLFTextShape] subtypes), rewriting hyperlinked runs INLINE as Markdown links using the
     * G-6 convention `[display text](target)`. Identical output to `shape.getText()` for any
     * shape with no hyperlinks.
     *
     * In PPTX a hyperlink is carried by the `<a:hlinkClick>` element on a text *run*
     * ([XSLFTextRun]); the run's text is the link's display text. We therefore harvest at the
     * run level — the display text appears exactly once (no hoisting, no duplication), in its
     * original reading-order position. Paragraphs are joined with `\n`, runs are concatenated
     * with no separator (matching POI's `getText()`).
     *
     * Targets mirror [linkTarget]:
     * - external URL → `[text](url)`
     * - email → `[text](mailto:…)`
     * - slide-jump → `[text](#slide-N)` (or the `#slide` placeholder when the relationship
     *   cannot be resolved to a concrete slide number).
     *
     * Conservative fallbacks (never corrupt text): a run is left un-linked (its raw text is
     * emitted) when its display text is blank, already contains any of `[]()`, or its target
     * cannot be resolved to a non-blank string.
     */
    private fun hyperlinkAwareText(shape: XSLFTextShape): String {
        val paragraphs = try { shape.textParagraphs } catch (_: Exception) { null }
            ?: return shape.text ?: ""
        return paragraphs.joinToString("\n") { paragraph ->
            val runs = try { paragraph.textRuns } catch (_: Exception) { null }
            if (runs == null) return@joinToString try { paragraph.text ?: "" } catch (_: Exception) { "" }
            buildString {
                for (run in runs) {
                    val raw = try { run.rawText ?: "" } catch (_: Exception) { "" }
                    val target = linkTarget(run, shape)
                    if (target != null && raw.isNotBlank() && raw.none { it in "[]()" }) {
                        append('[').append(raw).append("](").append(target).append(')')
                    } else {
                        append(raw)
                    }
                }
            }
        }
    }

    /**
     * Resolves the hyperlink target on a single [run] to the inline-link target string, or
     * `null` when the run carries no usable hyperlink.
     *
     * - [HyperlinkType.URL] / [HyperlinkType.FILE] → the raw address (e.g. `http://…`).
     * - [HyperlinkType.EMAIL] → `mailto:` prefixed; POI already stores the address with the
     *   `mailto:` scheme, so we normalise to exactly one prefix.
     * - [HyperlinkType.DOCUMENT] → an internal slide-jump. We resolve the `<a:hlinkClick r:id>`
     *   relationship on the owning slide's sheet to the destination [XSLFSlide] and emit
     *   `#slide-N` (1-based via [XSLFSlide.getSlideNumber]). When the relationship cannot be
     *   cheaply resolved to a slide we fall back to the recoverable `#slide` placeholder.
     */
    private fun linkTarget(run: XSLFTextRun, shape: XSLFTextShape): String? {
        val hyperlink = try { run.hyperlink } catch (_: Exception) { null } ?: return null
        val type = try { hyperlink.type } catch (_: Exception) { null } ?: return null
        return when (type) {
            HyperlinkType.URL, HyperlinkType.FILE -> {
                try { hyperlink.address } catch (_: Exception) { null }?.takeIf { it.isNotBlank() }
            }
            HyperlinkType.EMAIL -> {
                val addr = try { hyperlink.address } catch (_: Exception) { null }?.takeIf { it.isNotBlank() }
                    ?: return null
                if (addr.startsWith("mailto:", ignoreCase = true)) addr else "mailto:$addr"
            }
            HyperlinkType.DOCUMENT -> resolveSlideJump(hyperlink, shape)
            HyperlinkType.NONE -> null
        }
    }

    /**
     * Resolves an internal slide-jump hyperlink to `#slide-N`, or the `#slide` placeholder when
     * the destination cannot be resolved cheaply.
     *
     * The `<a:hlinkClick>` carries the relationship id (`r:id`) of the destination slide on its
     * [org.openxmlformats.schemas.drawingml.x2006.main.CTHyperlink]. We look that relationship up
     * on the owning sheet ([XSLFTextShape.getSheet] → [org.apache.poi.ooxml.POIXMLDocumentPart.getRelationById])
     * and, when it resolves to an [XSLFSlide], read its 1-based [XSLFSlide.getSlideNumber].
     */
    private fun resolveSlideJump(
        hyperlink: org.apache.poi.xslf.usermodel.XSLFHyperlink,
        shape: XSLFTextShape,
    ): String {
        val placeholder = "#slide"
        val relId = try { hyperlink.xmlObject?.id } catch (_: Exception) { null }
            ?.takeIf { it.isNotBlank() } ?: return placeholder
        val sheet = try { shape.sheet } catch (_: Exception) { null } ?: return placeholder
        val target = try { sheet.getRelationById(relId) } catch (_: Exception) { null }
        val number = (target as? XSLFSlide)?.let { try { it.slideNumber } catch (_: Exception) { null } }
        return if (number != null && number > 0) "#slide-$number" else placeholder
    }

    // ── Table conversion ───────────────────────────────────────────────────────

    private fun tableToBlock(table: XSLFTable): DocumentBlock? {
        val rowCount = table.numberOfRows
        if (rowCount == 0) return null

        val colCount = table.numberOfColumns
        if (colCount == 0) return null

        // First row → headers. XSLFTableCell extends XSLFTextShape, so cell text goes through
        // the same hyperlink-aware run walk as standalone text shapes (HX-1 PPTX variant).
        val headers = (0 until colCount).map { col ->
            table.getCell(0, col)?.let { hyperlinkAwareText(it).trim() } ?: ""
        }

        if (headers.all { it.isEmpty() }) return null

        // Subsequent rows → data rows
        val dataRows = (1 until rowCount).map { row ->
            (0 until colCount).map { col ->
                table.getCell(row, col)?.let { hyperlinkAwareText(it).trim() } ?: ""
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
        val altText = pictureAltText(shape)

        // Use streaming getInputStream + readNBytes(cap+1) to honour the size cap without
        // ever loading more than `cap+1` bytes into memory.
        val limit = (maxBytesPerImage + 1).toInt()
        val bytes = try {
            pictureData.inputStream.use { it.readNBytes(limit) }
        } catch (_: Exception) {
            return DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null, altText = altText)
        }

        if (bytes.size > maxBytesPerImage) {
            // Oversize — emit placeholder so the LLM still sees an image existed.
            return DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null, altText = altText)
        }
        if (bytes.isEmpty()) return null  // empty stream — skip entirely

        // saveImage sniffs the real MIME from magic bytes and drops fragment images below
        // 32px in either dimension. Returns null for fragments — skip the block entirely.
        val saveResult = try {
            service.saveImage(bytes, docKey, name, mime)
        } catch (_: Exception) {
            // Disk write failed — emit placeholder so the LLM still sees the image existed.
            return DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null, altText = altText)
        }
        // null return = fragment filter fired — return null to suppress the block entirely.
        saveResult ?: return null
        return DocumentBlock.EmbeddedFileRef(name = name, mimeType = saveResult.mimeType, path = saveResult.path.toString(), altText = altText)
    }

    /**
     * Reads a picture shape's `p:cNvPr` title/descr as alt-text (IMG-1). PPTX images carry
     * the same human-authored figure description DOCX does; threading it onto the marker gives
     * the LLM the only machine-readable caption the figure has. Returns null when absent.
     */
    private fun pictureAltText(shape: XSLFPictureShape): String? {
        val cNvPr = try {
            (shape.xmlObject as? org.openxmlformats.schemas.presentationml.x2006.main.CTPicture)
                ?.nvPicPr?.cNvPr
        } catch (_: Exception) { null } ?: return null
        return (cNvPr.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: cNvPr.descr?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * True when [frame] wraps a SmartArt diagram. `XSLFGraphicFrame.hasDiagram()` exists in
     * POI 5.4.1; guarded for safety against any future signature drift.
     */
    private fun frameHasDiagram(frame: XSLFGraphicFrame): Boolean {
        return try { frame.hasDiagram() } catch (_: Throwable) { false }
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
