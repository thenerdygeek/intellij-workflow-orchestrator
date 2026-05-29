package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.service.ImageExtractionService
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph

/**
 * Emits [DocumentBlock.EmbeddedFileRef] blocks for each embedded picture inside a paragraph's
 * runs. Picture bytes are written to disk via [imageService] and the resulting absolute
 * path is carried on the emitted block so the LLM has an actionable file path for the
 * `view_image` agent tool.
 *
 * ## Per-image flow
 *
 * For each `XWPFPicture` in `paragraph.runs[*].embeddedPictures`:
 * 1. Read the picture's `XWPFPictureData` for filename + MIME + raw bytes.
 * 2. Check `packagePart.size`: if larger than [maxBytesPerImage] (default 25 MB), emit
 *    `EmbeddedFileRef(name, mime, path=null)` so the LLM still knows the image exists,
 *    but skip the disk write (PoiHardening caps allocations at 50 MB).
 * 3. Otherwise, call [imageService.save] with `(bytes, docKey, suggestedName=filename, mime)`
 *    and emit `EmbeddedFileRef(name=filename, mime, path=savedPath.toString())`.
 *
 * If `paragraph.runs` is empty or no run has embedded pictures, returns empty list.
 *
 * @param imageService     Service that owns per-doc directories and per-bytes hashing.
 * @param docKey           Stable per-document key (typically the source path). The service
 *                         uses this to pick a per-doc subdirectory; reusing the same key
 *                         across saves within one extraction call deduplicates identical
 *                         image bytes.
 * @param maxBytesPerImage Soft cap above which the image is NOT written to disk (a
 *                         placeholder `EmbeddedFileRef(path=null)` is emitted instead).
 *                         Default 25 MB.
 */
class ImageExtractionVisitor(
    private val imageService: ImageExtractionService,
    private val docKey: String,
    private val maxBytesPerImage: Long = 25L * 1024 * 1024,
) : ParagraphVisitor {

    init {
        // readNBytes((maxBytesPerImage + 1).toInt()) Int-clamps; values >= Int.MAX_VALUE would
        // silently truncate and let oversized streams slip through. Fail fast instead — 25 MB
        // default is fine, anyone bumping past 2 GB is doing something wrong.
        require(maxBytesPerImage < Int.MAX_VALUE.toLong()) {
            "maxBytesPerImage must fit in Int (< 2 GB); got $maxBytesPerImage"
        }
    }

    override fun visit(paragraph: XWPFParagraph, doc: XWPFDocument): List<DocumentBlock> {
        // Drawing-level alt-text + non-picture object placeholders (IMG-1 / IMG-3). Walk the
        // run drawings once: pictures get their docPr title/descr threaded onto the emitted
        // EmbeddedFileRef; SmartArt diagrams and wordprocessing shapes (text boxes, callouts)
        // — which POI's embeddedPictures never returns — get a presence placeholder so a
        // diagram-dominated paragraph no longer renders empty.
        val drawingInfo = collectDrawingInfo(paragraph)

        val pictures = paragraph.runs.flatMap { it.embeddedPictures.orEmpty() }
        if (pictures.isEmpty() && drawingInfo.objectPlaceholders.isEmpty()) return emptyList()

        val pictureAltTexts = drawingInfo.pictureAltTexts
        var pictureIndex = 0

        val imageBlocks = pictures.mapNotNull { picture ->
            // Align the drawing-level docPr alt-text to this picture by declaration order.
            // Falls back to the picture's own pic:cNvPr descr/title when no docPr text exists.
            val drawingAlt = pictureAltTexts.getOrNull(pictureIndex)
            pictureIndex++
            val pictureData = picture.pictureData ?: return@mapNotNull null
            val name = pictureData.fileName ?: "image"
            val mime = pictureMime(pictureData)
            val altText = drawingAlt ?: pictureCNvPrAlt(picture)

            val partSize = try { pictureData.packagePart.size } catch (_: Exception) { -1L }
            if (partSize > 0 && partSize > maxBytesPerImage) {
                // partSize is known and exceeds the cap — skip disk write, emit placeholder.
                return@mapNotNull DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null, altText = altText)
            }
            // Either size is unknown (-1 from PackagePart.getSize() — always -1 for OOXML parts)
            // or within cap. Read directly from the package part's stream to bypass
            // XWPFPictureData.getData()'s IOUtils.toByteArrayWithMaxLength check (which uses
            // getMaxImageSize()=100MB, conflicting with PoiHardening's 50MB override and throwing
            // RecordFormatException even for small images).
            // Limit the read to maxBytesPerImage+1 bytes so oversized streams are detected.
            val bytes: ByteArray
            try {
                bytes = pictureData.packagePart.inputStream.use { stream ->
                    val limited = stream.readNBytes((maxBytesPerImage + 1).toInt())
                    if (limited.size > maxBytesPerImage) {
                        // Stream turned out larger than cap — emit placeholder, do not save.
                        return@mapNotNull DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null, altText = altText)
                    }
                    limited
                }
            } catch (_: Exception) {
                return@mapNotNull null
            }
            if (bytes.isEmpty()) return@mapNotNull null
            // saveImage sniffs the real MIME from magic bytes (fixes MIME mismatch where POI
            // declares image/jpeg but the embedded bytes are PNG) and filters out fragment
            // glyphs whose decoded dimensions are below the 32px threshold. Returns null for
            // fragments — skip the block entirely so the LLM doesn't see meaningless slivers.
            val saveResult = try {
                imageService.saveImage(bytes, docKey, name, mime)
            } catch (_: Exception) {
                // Disk write failed (rare); fall back to no-path placeholder so the LLM still
                // sees that an image existed at this point in the document.
                return@mapNotNull DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null, altText = altText)
            }
            // null from saveImage = fragment filter — drop entirely.
            saveResult ?: return@mapNotNull null
            DocumentBlock.EmbeddedFileRef(name = name, mimeType = saveResult.mimeType, path = saveResult.path.toString(), altText = altText)
        }

        // IMG-3: append placeholders for non-picture drawing objects (SmartArt diagrams,
        // wordprocessing shapes) found in this paragraph's drawings. Emitted after the picture
        // blocks; their text (for SmartArt) still surfaces separately via SmartArtExtractor.
        return imageBlocks + drawingInfo.objectPlaceholders
    }

    /**
     * Holds the per-paragraph drawing scan results:
     * - [pictureAltTexts]: drawing-level docPr alt-text (title preferred, then descr) in the
     *   same declaration order as POI's `embeddedPictures`, so index N aligns to picture N.
     *   Entries are null when a picture's drawing carried no docPr text.
     * - [objectPlaceholders]: [DocumentBlock.EmbeddedObjectRef] markers for SmartArt diagrams
     *   and wordprocessing shapes (non-picture graphic objects) in declaration order.
     */
    private class DrawingInfo(
        val pictureAltTexts: List<String?>,
        val objectPlaceholders: List<DocumentBlock>,
    )

    /**
     * Walks every `<w:drawing>` directly under the paragraph's runs, classifying each contained
     * graphic object by its `a:graphicData/@uri`:
     * - picture URI → record the drawing-level docPr title/descr as an alt-text (for IMG-1).
     * - diagram URI → emit a `[SmartArt: <name>]` placeholder (IMG-3).
     * - wordprocessingShape / wordprocessingCanvas URI → emit a `[Shape: <name>]` placeholder.
     *
     * IMG-3 remainder: shapes authored inside `<mc:AlternateContent><mc:Choice>` (Word's
     * forward-compat wrapper for newer DrawingML, e.g. text-box / callout shapes) are NOT
     * children of the run via POI's `getDrawingList()`, so the direct-child scan above misses
     * them. [collectAlternateContentPlaceholders] recovers them with an `mc:`-namespace XmlCursor
     * walk over the run XML and emits the same `[Shape: …]` / `[SmartArt: …]` placeholders.
     */
    private fun collectDrawingInfo(paragraph: XWPFParagraph): DrawingInfo {
        val altTexts = mutableListOf<String?>()
        val placeholders = mutableListOf<DocumentBlock>()
        for (run in paragraph.runs) {
            val ctr = try { run.ctr } catch (_: Exception) { continue } ?: continue
            val drawings = try { ctr.drawingList } catch (_: Exception) { emptyList() } ?: emptyList()
            for (drawing in drawings) {
                // CTInline and CTAnchor share no supertype but expose identical getDocPr()/
                // getGraphic() — classify each separately into (alt-text?, name?, uri).
                for (inline in drawing.inlineList.orEmpty()) {
                    val docPr = try { inline.docPr } catch (_: Exception) { null }
                    val uri = try { inline.graphic?.graphicData?.uri } catch (_: Exception) { null } ?: ""
                    classifyGraphic(uri, docPr, altTexts, placeholders)
                }
                for (anchor in drawing.anchorList.orEmpty()) {
                    val docPr = try { anchor.docPr } catch (_: Exception) { null }
                    val uri = try { anchor.graphic?.graphicData?.uri } catch (_: Exception) { null } ?: ""
                    classifyGraphic(uri, docPr, altTexts, placeholders)
                }
            }
            // IMG-3 remainder: mc:AlternateContent-wrapped drawings POI's drawingList never returns.
            placeholders += collectAlternateContentPlaceholders(ctr)
        }
        return DrawingInfo(altTexts, placeholders)
    }

    /**
     * Walks the run's `<w:r>` XML for `<mc:AlternateContent>` blocks and emits a `[Shape: …]` /
     * `[SmartArt: …]` placeholder for each drawing found inside their `<mc:Choice>` (the
     * preferred newer-DrawingML branch). These drawings are siblings under `mc:Choice` rather
     * than direct run children, so POI's `getDrawingList()` does not return them.
     *
     * For each `<wp:docPr>` (the drawing-level non-visual props carrying the shape name) we look
     * forward to the nearest `<a:graphicData @uri>` within the same AlternateContent block to
     * classify the object via [classifyGraphic]; a missing/unknown uri is treated as a generic
     * shape so a named drawing is never silently dropped. We only descend `<mc:Choice>` (never
     * `<mc:Fallback>`) to avoid double-counting the legacy VML rendering of the same shape.
     *
     * Uses XmlBeans' [org.apache.xmlbeans.XmlCursor] (same pattern as [TrackedChangeVisitor]);
     * any parse/navigation failure degrades to no placeholder rather than throwing.
     */
    private fun collectAlternateContentPlaceholders(
        ctr: org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR,
    ): List<DocumentBlock> {
        val placeholders = mutableListOf<DocumentBlock>()
        val cursor = try { ctr.newCursor() } catch (_: Exception) { return emptyList() }
        try {
            // Fast-path: only walk when an AlternateContent element is present in the run XML.
            if (!cursor.xmlText().contains("AlternateContent")) return emptyList()

            var inChoiceDepth = 0          // >0 while inside an <mc:Choice> subtree.
            var pendingDocPrName: String? = null   // docPr name awaiting its graphicData uri.
            var pendingUri: String? = null

            while (cursor.hasNextToken()) {
                cursor.toNextToken()
                if (cursor.isStart) {
                    val name = cursor.name ?: continue
                    val local = name.localPart
                    val ns = name.namespaceURI
                    when {
                        local == "Choice" && ns == MC_NS -> inChoiceDepth++
                        inChoiceDepth > 0 && local == "docPr" -> {
                            // Flush any prior pending docPr (no uri found) as a generic shape.
                            flushPending(pendingDocPrName, pendingUri, placeholders)
                            pendingDocPrName = cursor.getAttributeText(NAME_QNAME)?.trim()?.takeIf { it.isNotEmpty() }
                            pendingUri = null
                        }
                        inChoiceDepth > 0 && local == "graphicData" -> {
                            pendingUri = cursor.getAttributeText(URI_QNAME)?.trim()
                        }
                    }
                } else if (cursor.isEnd) {
                    val name = cursor.name
                    if (name?.localPart == "Choice" && name.namespaceURI == MC_NS && inChoiceDepth > 0) {
                        inChoiceDepth--
                        if (inChoiceDepth == 0) {
                            flushPending(pendingDocPrName, pendingUri, placeholders)
                            pendingDocPrName = null
                            pendingUri = null
                        }
                    }
                }
            }
            flushPending(pendingDocPrName, pendingUri, placeholders)
        } catch (_: Exception) {
            // Cursor walk failed — return whatever was collected.
        } finally {
            cursor.dispose()
        }
        return placeholders
    }

    /**
     * Emits one placeholder for a buffered AlternateContent drawing: a diagram uri → SmartArt,
     * anything else (shape uri, missing, or unknown) → a generic shape. Skips when no docPr name
     * was captured (an unnamed drawing carries no useful identity to surface).
     */
    private fun flushPending(name: String?, uri: String?, out: MutableList<DocumentBlock>) {
        if (name == null) return
        val kind = if (uri == DIAGRAM_URI) {
            DocumentBlock.EmbeddedObjectRef.Kind.SMARTART
        } else {
            DocumentBlock.EmbeddedObjectRef.Kind.SHAPE
        }
        out += DocumentBlock.EmbeddedObjectRef(kind = kind, name = name)
    }

    /**
     * Classifies one graphic object by its [uri], appending either an alt-text (pictures) or a
     * placeholder block (SmartArt / shapes). [docPr] is the drawing-level
     * `CTNonVisualDrawingProps` carrying the human-authored title/descr/name.
     */
    private fun classifyGraphic(
        uri: String,
        docPr: org.openxmlformats.schemas.drawingml.x2006.main.CTNonVisualDrawingProps?,
        altTexts: MutableList<String?>,
        placeholders: MutableList<DocumentBlock>,
    ) {
        val docPrAlt = altOf(docPr?.title, docPr?.descr)
        val docPrName = docPr?.name?.takeIf { it.isNotBlank() }
        when (uri) {
            PICTURE_URI -> altTexts += docPrAlt
            DIAGRAM_URI -> placeholders += DocumentBlock.EmbeddedObjectRef(
                kind = DocumentBlock.EmbeddedObjectRef.Kind.SMARTART,
                name = docPrAlt ?: docPrName,
            )
            WPS_SHAPE_URI, WPC_CANVAS_URI -> placeholders += DocumentBlock.EmbeddedObjectRef(
                kind = DocumentBlock.EmbeddedObjectRef.Kind.SHAPE,
                name = docPrAlt ?: docPrName,
            )
            // Unknown graphic types: ignore — not a recognised image/diagram/shape.
        }
    }

    /**
     * Reads the picture's own `pic:cNvPr` title/descr as an alt-text fallback when the
     * drawing-level docPr carried none.
     */
    private fun pictureCNvPrAlt(picture: org.apache.poi.xwpf.usermodel.XWPFPicture): String? {
        val cNvPr = try {
            picture.ctPicture?.nvPicPr?.cNvPr
        } catch (_: Exception) { null } ?: return null
        return altOf(cNvPr.title, cNvPr.descr)
    }

    /** Prefers title over descr; trims; returns null when both are blank. */
    private fun altOf(title: String?, descr: String?): String? {
        return title?.trim()?.takeIf { it.isNotEmpty() }
            ?: descr?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Maps POI's [org.apache.poi.common.usermodel.PictureType] enum to a MIME string.
     * Uses `getPictureTypeEnum()` (available since POI 5.x) for a clean enum-based dispatch.
     *
     * For types not present in POI 5.4.1's `PictureType` enum (notably WebP — no
     * `PictureType.WEBP` constant exists in this POI version), falls back to
     * `XWPFPictureData.suggestFileExtension()` so newer formats embedded into a DOCX still
     * resolve to a sensible MIME. Vector/proprietary formats (WMF/EMF/PICT/WPG/EPS/WDP) and
     * truly unknown types degrade to `application/octet-stream` — the LLM can't render those
     * via `view_image`, but the placeholder still tells it an image existed.
     */
    private fun pictureMime(pictureData: org.apache.poi.xwpf.usermodel.XWPFPictureData): String {
        return when (pictureData.pictureTypeEnum) {
            org.apache.poi.common.usermodel.PictureType.PNG -> "image/png"
            org.apache.poi.common.usermodel.PictureType.JPEG,
            org.apache.poi.common.usermodel.PictureType.CMYKJPEG -> "image/jpeg"
            org.apache.poi.common.usermodel.PictureType.GIF -> "image/gif"
            org.apache.poi.common.usermodel.PictureType.BMP,
            org.apache.poi.common.usermodel.PictureType.DIB -> "image/bmp"
            org.apache.poi.common.usermodel.PictureType.TIFF -> "image/tiff"
            org.apache.poi.common.usermodel.PictureType.SVG -> "image/svg+xml"
            else -> {
                // Fall back to suggested filename extension for newer types (e.g. WebP — POI
                // 5.4.1 has no PictureType.WEBP). Vector/legacy formats end up here too.
                val ext = pictureData.suggestFileExtension()?.lowercase()
                when (ext) {
                    "webp" -> "image/webp"
                    "svg" -> "image/svg+xml"
                    else -> "application/octet-stream"
                }
            }
        }
    }

    private companion object {
        // a:graphicData/@uri values used inside <w:drawing> to discriminate graphic kinds.
        const val PICTURE_URI = "http://schemas.openxmlformats.org/drawingml/2006/picture"
        const val DIAGRAM_URI = "http://schemas.openxmlformats.org/drawingml/2006/diagram"
        const val WPS_SHAPE_URI = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
        const val WPC_CANVAS_URI = "http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas"

        // Markup-compatibility namespace (mc:AlternateContent / mc:Choice / mc:Fallback).
        const val MC_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006"

        // Unqualified attributes read off elements during the mc: cursor walk. wp:docPr@name and
        // a:graphicData@uri are both in the no-namespace attribute form.
        val NAME_QNAME: javax.xml.namespace.QName = javax.xml.namespace.QName("name")
        val URI_QNAME: javax.xml.namespace.QName = javax.xml.namespace.QName("uri")
    }
}
