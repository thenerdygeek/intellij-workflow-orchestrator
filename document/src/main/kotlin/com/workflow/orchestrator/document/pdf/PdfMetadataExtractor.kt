package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.service.ImageExtractionService
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.nio.file.Path

/**
 * Extracts PDF markup annotations (sticky notes, free-text, highlights, etc.) as typed
 * [DocumentBlock.Comment] blocks with [DocumentBlock.Comment.Kind.PDF_ANNOTATION].
 *
 * Also extracts three additional metadata channels as [DocumentBlock.KeyValueGroup] blocks:
 * - **Document properties** (`PDDocumentInformation`: title, author, subject, etc.) — emitted at
 *   page 1, top=-2.0 so it sorts first in the merge stream.
 * - **Bookmarks** (PDF outline via `PDDocumentOutline`) — recursive walk with depth-indented
 *   titles and `p.<num>` page labels; emitted at page 1, top=-1.0 (just after doc properties).
 * - **AcroForm fields** — fully-qualified field name → string value; emitted at the last page,
 *   top=MAX so it lands after all body content.
 *
 * ## P4T2 additions
 *
 * When [imageService] is wired, two additional extraction passes run after form fields:
 * - **Embedded file attachments** — `PDEmbeddedFilesNameTreeNode` entries are saved via
 *   [ImageExtractionService.save] and emitted as [DocumentBlock.EmbeddedFileRef] blocks near
 *   the end of the document (top ≈ `MAX_VALUE - 100`).
 * - **Image XObjects** — each page's resources are walked for [PDImageXObject] entries. Bytes
 *   are rendered to PNG via `PDImageXObject.image` + `ImageIO.write` (reliable across all
 *   PDFBox compression schemes: DCT/JBIG2/CCITT/etc.) and saved. Emitted as
 *   [DocumentBlock.EmbeddedFileRef] with page-relative position `top=0.5`.
 *
 * When [imageService] is null (no-service caller), both passes are skipped and no
 * [DocumentBlock.EmbeddedFileRef] blocks are emitted from this extractor.
 *
 * ## Why a separate extractor
 *
 * Tika's `PDFParserConfig.isExtractAnnotationText = true` (the default) bleeds annotation text
 * into the prose stream as untyped [DocumentBlock.Paragraph] blocks — no author, no anchor.
 * This extractor walks PDFBox annotations directly and emits richly typed blocks that carry
 * author (popup title) and comment text separately.
 *
 * ## Phase 1 coexistence
 *
 * In Phase 1, Tika's flag is **not** flipped. The old untyped leakage and the new typed
 * [DocumentBlock.Comment] blocks coexist for one release. Phase 4b will deduplicate by
 * annotation rectangle and flip the Tika flag.
 *
 * ## Coordinate model
 *
 * PDF user-space has origin at bottom-left; y increases upward. The merge pipeline expects
 * "reading order Y": smaller `top` = closer to the top of the page (read first). We translate
 * by measuring the rect's offset from the media box's *lower-left* edge and subtracting from
 * the media box's *height*, so PDFs with a non-zero `mediaBox.lowerLeftY` (cropped pages —
 * e.g. business exports with media-box `[0 72 612 720]`) are handled correctly.
 *
 * @param imageService  When non-null, embedded attachments and image XObjects are extracted and
 *                      saved to disk; [DocumentBlock.EmbeddedFileRef] blocks are emitted with
 *                      non-null [DocumentBlock.EmbeddedFileRef.path] values. When null, both
 *                      passes are skipped entirely.
 * @param docKey        Stable identifier for this document passed to [ImageExtractionService.save]
 *                      so that re-extractions land in the same per-doc directory.
 * @param maxBytesPerImage Upper bound for bytes that will be passed to [ImageExtractionService.save].
 *                         Blobs larger than this produce a [DocumentBlock.EmbeddedFileRef] with
 *                         `path=null` instead of being written to disk.
 * @see PdfPipeline for the merge/sort step that consumes these blocks.
 */
class PdfMetadataExtractor(
    private val imageService: ImageExtractionService? = null,
    private val docKey: String = "anonymous",
    private val maxBytesPerImage: Long = 25L * 1024 * 1024,
) {

    init {
        require(maxBytesPerImage < Int.MAX_VALUE.toLong()) {
            "maxBytesPerImage must fit in Int; got $maxBytesPerImage"
        }
    }

    /**
     * Returns positioned [DocumentBlock] entries for the PDF:
     * - One [DocumentBlock.KeyValueGroup] for document properties (when present), at page 1, top=-2.0.
     * - One [DocumentBlock.KeyValueGroup] for bookmarks (when present), at page 1, top=-1.0.
     * - One [DocumentBlock.Comment] per markup annotation across all pages.
     * - One [DocumentBlock.KeyValueGroup] for AcroForm fields (when present), at last page, top=MAX.
     * - When [imageService] is wired: [DocumentBlock.EmbeddedFileRef] for embedded attachments and
     *   image XObjects (P4T2).
     *
     * @param file Absolute path to the PDF file (random-access required by PDFBox).
     * @return Ordered list of positioned blocks. The merge sort in [PdfPipeline] reorders by
     *         `(page, top)` across all sources.
     * @throws org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException for encrypted PDFs.
     */
    fun extract(file: Path): List<PositionedBlock<DocumentBlock>> {
        val result = mutableListOf<PositionedBlock<DocumentBlock>>()
        Loader.loadPDF(file.toFile()).use { doc ->
            // 1. Document properties — emitted at page 1, top=-2.0 so it sorts first.
            extractDocumentProperties(doc)?.let { kvg ->
                result += PositionedBlock(page = 1, top = -2.0, bottom = -1.0, block = kvg)
            }

            // 2. Bookmarks — emitted at page 1, top=-1.0 (just after doc properties).
            extractBookmarks(doc)?.let { kvg ->
                result += PositionedBlock(page = 1, top = -1.0, bottom = 0.0, block = kvg)
            }

            // 3. Annotations — one Comment block per markup annotation across all pages.
            doc.pages.forEachIndexed { pageIdx, page ->
                val pageNumber = pageIdx + 1
                val annotations = try {
                    page.annotations
                } catch (_: Exception) {
                    emptyList()
                }
                val mediaBox = page.mediaBox
                // PDF user space is bottom-up; convert to a top-down "reading order Y" relative
                // to the page's media box origin. Using `mediaBox.upperRightY - rect.upperRightY`
                // directly would be wrong when `mediaBox.lowerLeftY != 0` (cropped pages — e.g.
                // business exports with media-box `[0 72 612 720]`).
                val boxHeight = (mediaBox.upperRightY - mediaBox.lowerLeftY).toDouble()
                val boxBottom = mediaBox.lowerLeftY.toDouble()
                for (annotation in annotations) {
                    val markup = annotation as? PDAnnotationMarkup ?: continue
                    val text = markup.contents?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    val rect = markup.rectangle ?: continue
                    val author = markup.titlePopup?.takeIf { it.isNotBlank() }
                    val comment = DocumentBlock.Comment(
                        author = author,
                        anchorText = null, // Phase 1: anchor text left null — harder to derive from PDFBox
                        text = text,
                        kind = DocumentBlock.Comment.Kind.PDF_ANNOTATION,
                    )
                    // Translate rect coords (bottom-up, in PDF user space) to reading-order Y
                    // (top-down, relative to the media-box top). Measure each rect edge as an
                    // offset from the box's lower-left, then flip to top-down with boxHeight.
                    val rectTopFromBoxBottom = rect.upperRightY.toDouble() - boxBottom
                    val rectBottomFromBoxBottom = rect.lowerLeftY.toDouble() - boxBottom
                    val readingTop = boxHeight - rectTopFromBoxBottom
                    val readingBottom = boxHeight - rectBottomFromBoxBottom
                    result += PositionedBlock(
                        page = pageNumber,
                        top = readingTop,
                        bottom = readingBottom,
                        block = comment,
                    )
                }
            }

            // 4. AcroForm fields — emitted at last page, top=MAX so it lands after all body content.
            extractFormFields(doc)?.let { kvg ->
                val lastPage = doc.numberOfPages.coerceAtLeast(1)
                result += PositionedBlock(
                    page = lastPage,
                    top = Double.MAX_VALUE - 1.0,
                    bottom = Double.MAX_VALUE,
                    block = kvg,
                )
            }

            // 5. P4T2: embedded file attachments (requires imageService).
            result += extractEmbeddedFiles(doc)

            // 6. P4T2: image XObjects per page (requires imageService).
            result += extractImageXObjects(doc)
        }
        return result
    }

    /**
     * Extracts `PDDocumentInformation` fields (title, author, subject, keywords, creator,
     * producer, creationDate, modificationDate) as a [DocumentBlock.KeyValueGroup].
     *
     * Returns null when the document has no information dictionary or all fields are blank.
     * Note: `documentInformation` lives on [PDDocument] directly, NOT on `PDDocumentCatalog`.
     */
    private fun extractDocumentProperties(doc: PDDocument): DocumentBlock.KeyValueGroup? {
        val info = try { doc.documentInformation } catch (_: Exception) { null } ?: return null
        val pairs = buildList {
            info.title?.takeIf { it.isNotBlank() }?.let { add("Title" to it) }
            info.author?.takeIf { it.isNotBlank() }?.let { add("Author" to it) }
            info.subject?.takeIf { it.isNotBlank() }?.let { add("Subject" to it) }
            info.keywords?.takeIf { it.isNotBlank() }?.let { add("Keywords" to it) }
            info.creator?.takeIf { it.isNotBlank() }?.let { add("Creator" to it) }
            info.producer?.takeIf { it.isNotBlank() }?.let { add("Producer" to it) }
            info.creationDate?.let { add("Created" to it.time.toString()) }
            info.modificationDate?.let { add("Modified" to it.time.toString()) }
        }
        return if (pairs.isNotEmpty()) DocumentBlock.KeyValueGroup("Document properties", pairs) else null
    }

    /**
     * Walks the PDF document outline (bookmarks) via [PDDocument.documentCatalog.documentOutline]
     * and emits a [DocumentBlock.KeyValueGroup] whose pairs map indented bookmark titles to
     * `p.<num>` page labels.
     *
     * The outline is traversed depth-first via [PDOutlineItem.firstChild] / [PDOutlineItem.nextSibling].
     * [PDOutlineItem.findDestinationPage] resolves named destinations and GoTo actions alike —
     * it returns null for external links or when the destination cannot be resolved, in which
     * case we emit `p.?`.
     *
     * Returns null if there is no outline or all bookmark titles are blank.
     */
    private fun extractBookmarks(doc: PDDocument): DocumentBlock.KeyValueGroup? {
        val outline = try { doc.documentCatalog?.documentOutline } catch (_: Exception) { null } ?: return null
        val pairs = mutableListOf<Pair<String, String>>()
        walkOutline(outline.firstChild, doc, pairs, depth = 0)
        return if (pairs.isNotEmpty()) DocumentBlock.KeyValueGroup("Bookmarks", pairs) else null
    }

    /**
     * Depth-first traversal of the outline tree.
     *
     * [PDOutlineItem.firstChild] descends; [PDOutlineItem.nextSibling] advances the current level.
     * Both can throw (corrupt or encrypted PDFs), so each access is wrapped.
     */
    private fun walkOutline(
        node: PDOutlineItem?,
        doc: PDDocument,
        sink: MutableList<Pair<String, String>>,
        depth: Int,
    ) {
        var n = node
        while (n != null) {
            val title = n.title?.trim().orEmpty()
            if (title.isNotEmpty()) {
                val indent = "  ".repeat(depth.coerceAtLeast(0))
                val pageLabel = try {
                    val targetPage = n.findDestinationPage(doc)
                    if (targetPage != null) "p.${doc.pages.indexOf(targetPage) + 1}" else "p.?"
                } catch (_: Exception) {
                    "p.?"
                }
                sink += "$indent$title" to pageLabel
            }
            // Recurse into children before advancing to the next sibling.
            val firstChild = try { n.firstChild } catch (_: Exception) { null }
            walkOutline(firstChild, doc, sink, depth + 1)
            n = try { n.nextSibling } catch (_: Exception) { null }
        }
    }

    /**
     * Extracts AcroForm fields from `PDDocumentCatalog.acroForm` as a [DocumentBlock.KeyValueGroup].
     *
     * Uses `PDField.fullyQualifiedName` (dot-separated path, e.g. `section.fieldName`) as the key
     * and `PDField.valueAsString` as the value. Fields with a blank fully-qualified name are skipped.
     *
     * Returns null when there is no AcroForm, the field list is null/empty, or all fields have
     * blank names (so PDFs without forms never produce an empty block).
     */
    private fun extractFormFields(doc: PDDocument): DocumentBlock.KeyValueGroup? {
        val form = try { doc.documentCatalog?.acroForm } catch (_: Exception) { null } ?: return null
        val fields = try { form.fields } catch (_: Exception) { return null }
        val pairs = fields.mapNotNull { field ->
            val name = field.fullyQualifiedName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = field.valueAsString?.trim().orEmpty()
            name to value
        }
        return if (pairs.isNotEmpty()) DocumentBlock.KeyValueGroup("Form fields", pairs) else null
    }

    // ── P4T2: embedded attachments ────────────────────────────────────────────

    /**
     * Extracts PDF embedded file attachments from `PDDocumentCatalog.names.embeddedFiles`
     * (a `PDEmbeddedFilesNameTreeNode`) and emits one [DocumentBlock.EmbeddedFileRef] per entry.
     *
     * When [imageService] is wired, attachment bytes are saved to disk via
     * [ImageExtractionService.save] and the resulting path is stored on the block.
     * Attachments larger than [maxBytesPerImage], or those whose bytes cannot be read, emit
     * a block with `path=null` so the LLM still knows the attachment exists.
     *
     * PDFBox API note: `PDEmbeddedFilesNameTreeNode.names` returns a
     * `Map<String, PDComplexFileSpecification>`. `PDComplexFileSpecification.embeddedFile`
     * yields the `PDEmbeddedFile` whose `subtype` field carries the MIME type (may be null
     * for older PDFs) and whose `size` is the uncompressed byte count (-1 if unknown).
     * `PDEmbeddedFile.toByteArray()` decompresses the embedded stream on demand.
     *
     * Blocks are positioned near the end of the document (top ≈ `MAX_VALUE - 100`) so they
     * sort after all body content and form fields but before each other in emission order.
     */
    private fun extractEmbeddedFiles(doc: PDDocument): List<PositionedBlock<DocumentBlock>> {
        val names = try { doc.documentCatalog?.names?.embeddedFiles } catch (_: Exception) { null }
            ?: return emptyList()

        val results = mutableListOf<PositionedBlock<DocumentBlock>>()
        val nameMap: Map<String, org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification> =
            try { names.names ?: emptyMap() } catch (_: Exception) { emptyMap() }

        for ((displayName, fileSpec) in nameMap) {
            val embedded = try { fileSpec.embeddedFile } catch (_: Exception) { null } ?: continue
            val mime = try { embedded.subtype } catch (_: Exception) { null }
                ?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val size = try { embedded.size.toLong() } catch (_: Exception) { -1L }

            // For embedded file attachments (non-image formats like PDF, ZIP, etc.),
            // we call save() directly rather than saveImage() because:
            // - Dimension filtering must not suppress non-image attachments.
            // - MIME sniffing is still useful here but attachment MIME comes from PDComplexFileSpecification
            //   which is already format-declared; sniffing is applied via ImageExtractionService.sniffImageMime
            //   so the effective MIME on the EmbeddedFileRef is correct.
            val savedPath: String?
            val effectiveMime: String
            if (imageService != null && size in 0..maxBytesPerImage) {
                val bytes = try { embedded.toByteArray() } catch (_: Exception) { null }
                if (bytes != null && bytes.isNotEmpty()) {
                    val sniffed = ImageExtractionService.sniffImageMime(bytes)
                    effectiveMime = sniffed ?: mime
                    savedPath = try { imageService.save(bytes, docKey, displayName, effectiveMime).toString() } catch (_: Exception) { null }
                } else {
                    effectiveMime = mime
                    savedPath = null
                }
            } else {
                effectiveMime = mime
                savedPath = null
            }

            results += PositionedBlock(
                page = doc.numberOfPages.coerceAtLeast(1),
                top = Double.MAX_VALUE - 100.0 - results.size,
                bottom = Double.MAX_VALUE - 100.0 - results.size + 1.0,
                block = DocumentBlock.EmbeddedFileRef(name = displayName, mimeType = effectiveMime, path = savedPath),
            )
        }
        return results
    }

    // ── P4T2: image XObjects ──────────────────────────────────────────────────

    /**
     * Walks each page's `PDResources.xObjectNames` for [PDImageXObject] entries and emits one
     * [DocumentBlock.EmbeddedFileRef] per image found.
     *
     * Returns immediately (empty list) when [imageService] is null — image XObject extraction
     * only makes sense when there is somewhere to save the bytes.
     *
     * PDFBox API notes:
     * - `PDResources.xObjectNames` returns an `Iterable<COSName>` that may throw on corrupt
     *   resources dictionaries, so the call is guarded.
     * - `PDResources.getXObject(COSName)` may return `PDFormXObject` or `PDImageXObject`;
     *   we filter for the latter with `is PDImageXObject`.
     * - `PDImageXObject.suffix` gives the compression-scheme hint ("jpg", "png", etc.) and may
     *   be null for inline images or JBIG2/CCITT streams. When null, we default to PNG because
     *   `PDImageXObject.image` always renders to a `BufferedImage` which we encode as PNG via
     *   `ImageIO.write`. This is the most reliable approach across DCT, CCITT, JBIG2, and
     *   mixed-compression PDFs — the rendered path normalises everything to uncompressed ARGB.
     * - `xObjectNames` may contain duplicate names across pages (each page gets its own
     *   `PDResources`). Names are local to the page's resource dictionary, so `Im1` on page 1
     *   and `Im1` on page 2 are different images. The full `(pageIdx, objectName)` key is
     *   unique; the [ImageExtractionService] deduplicates by bytes content-hash, so identical
     *   images that appear on multiple pages produce a single on-disk file.
     */
    private fun extractImageXObjects(doc: PDDocument): List<PositionedBlock<DocumentBlock>> {
        val service = imageService ?: return emptyList()
        val results = mutableListOf<PositionedBlock<DocumentBlock>>()

        doc.pages.forEachIndexed { pageIdx, page ->
            val pageNumber = pageIdx + 1
            val resources = try { page.resources } catch (_: Exception) { null } ?: return@forEachIndexed
            val xobjectNames = try { resources.xObjectNames } catch (_: Exception) { return@forEachIndexed }

            for (objectName in xobjectNames) {
                val xobject = try { resources.getXObject(objectName) } catch (_: Exception) { continue }
                if (xobject !is PDImageXObject) continue

                val mime = try {
                    when (xobject.suffix?.lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "gif" -> "image/gif"
                        "tiff", "tif" -> "image/tiff"
                        // JBIG2, CCITT, and unknowns: rendered to PNG by ImageIO below.
                        else -> "image/png"
                    }
                } catch (_: Exception) { "image/png" }

                val ext = mime.substringAfter('/')
                val name = "image-${objectName.name}.$ext"

                // Render to PNG via BufferedImage — reliable across all compression schemes.
                val bytes = try {
                    val img = xobject.image
                    val out = java.io.ByteArrayOutputStream()
                    javax.imageio.ImageIO.write(img, "PNG", out)
                    out.toByteArray()
                } catch (_: Exception) { null }

                if (bytes == null || bytes.size.toLong() > maxBytesPerImage) {
                    results += PositionedBlock(
                        page = pageNumber, top = 0.5, bottom = 0.6,
                        block = DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null),
                    )
                    continue
                }

                // saveImage sniffs the true MIME from the rendered PNG bytes (fixes the probe
                // finding where all 8 .jpg files contained PNG bytes) and drops fragment images
                // below 32px in either dimension. Returns null for fragments (skip entirely);
                // throws IOException for disk write failures (emit placeholder).
                val saveResult = try {
                    service.saveImage(bytes, docKey, name, mime)
                } catch (_: Exception) {
                    // Disk write failed — emit path=null placeholder so the LLM sees the image existed.
                    results += PositionedBlock(
                        page = pageNumber, top = 0.5, bottom = 0.6,
                        block = DocumentBlock.EmbeddedFileRef(name = name, mimeType = mime, path = null),
                    )
                    continue
                }
                // null return = fragment filter fired — drop the block entirely.
                saveResult ?: continue
                results += PositionedBlock(
                    page = pageNumber, top = 0.5, bottom = 0.6,
                    block = DocumentBlock.EmbeddedFileRef(
                        name = name,
                        mimeType = saveResult.mimeType,
                        path = saveResult.path.toString(),
                    ),
                )
            }
        }
        return results
    }
}
