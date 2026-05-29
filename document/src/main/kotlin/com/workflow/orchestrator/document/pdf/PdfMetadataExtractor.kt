package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.service.ImageExtractionService
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.text.PDFTextStripperByArea
import java.awt.geom.Rectangle2D
import java.nio.file.Path

/**
 * Extracts PDF markup annotations (sticky notes, free-text, highlights, etc.) as typed
 * [DocumentBlock.Comment] blocks with [DocumentBlock.Comment.Kind.PDF_ANNOTATION].
 *
 * Also extracts three additional metadata channels as [DocumentBlock.KeyValueGroup] blocks:
 * - **Document properties** (`PDDocumentInformation`: title, author, subject, etc.) — emitted at
 *   page 1, top=-2.0 so it sorts first in the merge stream.
 * - **Outline / bookmarks** (PDF outline via `PDDocumentOutline`) — recursively harvested into one
 *   [DocumentBlock.Heading] per node (NAV-4/NAV-6). The outline depth maps to the heading level
 *   (depth 0 → H1, …) and each heading is positioned at its destination page, making the outline
 *   the authoritative section-anchor set. When there is no outline, this contributes nothing and
 *   the prose heuristic supplies anchors.
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

    private companion object {
        /** Base `top` for outline-seeded headings: after the page marker (0.0), before prose (1.0). */
        const val OUTLINE_HEADING_TOP_BASE = 0.1

        /** Per-bookmark `top` step on the same page (keeps outline order; ~900 bookmarks/page). */
        const val OUTLINE_HEADING_TOP_STEP = 0.001

        /**
         * Points inset on every edge of a link annotation rect before the area-text query.
         * Link rects are routinely drawn 1–2 pt larger than the glyph run; without the inset
         * [PDFTextStripperByArea] bleeds adjacent words into the captured display text.
         */
        const val LINK_RECT_INSET = 1.0f

        /**
         * Generator/tool-artifact `/Title` values (lower-cased). When the title matches one of
         * these AND the producing tool's name appears in Creator/Producer, the title is dropped
         * from document properties (SF-9). Kept in sync with the same set in
         * [com.workflow.orchestrator.document.sax.DocumentBlockHandler].
         */
        val GENERATOR_TITLE_ARTIFACTS = setOf("enscript output", "untitled", "untitled document")
    }

    /**
     * Returns positioned [DocumentBlock] entries for the PDF:
     * - One [DocumentBlock.KeyValueGroup] for document properties (when present), at page 1, top=-2.0.
     * - One [DocumentBlock.Heading] per PDF outline node (when present), positioned at its
     *   destination page with a depth-derived level (NAV-4/NAV-6).
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

            // 2. Outline → authoritative section anchors. The PDF outline tree is the
            //    AUTHORITATIVE section-anchor set (NAV-4/NAV-6): its nesting depth encodes the real
            //    heading hierarchy, so we seed one DocumentBlock.Heading per node at its
            //    destination page (depth → level). This recovers numbered sections the prose
            //    heuristic drops and fixes the inverted hierarchy. When there is NO outline this is
            //    empty and the prose heuristic in DocumentBlockHandler supplies the anchors instead
            //    (rfc7230 / nist-csf path).
            result += extractOutlineHeadings(doc)

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

            // (Link annotations are NOT emitted as blocks — they are harvested separately by
            //  [extractLinks] and merged INLINE into prose by PdfPipeline. See that method.)

            // 6. P4T2: image XObjects per page (requires imageService).
            result += extractImageXObjects(doc)
        }
        return result
    }

    /**
     * Harvests every page's `PDAnnotationLink` into a flat list of [PdfLink] records (G-6 / HX-1
     * harvest). Unlike markup annotations (sticky notes / highlights → typed Comment blocks), link
     * annotations carry no body text of their own: their meaning is the *display text they overlay*
     * plus a *target*. We therefore emit link records — NOT [DocumentBlock]s — so that [PdfPipeline]
     * can splice them INLINE into the existing prose stream (`[display text](target)`), keeping
     * reading order intact and the visible text appearing exactly once. Emitting them as separate
     * floating blocks would either duplicate the display text or hoist it out of order (the HX-1
     * failure mode), so we deliberately do not.
     *
     * ## How display text is recovered
     *
     * The annotation's `getRectangle()` is in PDF user space (origin bottom-left). We feed each
     * rect to a per-page [PDFTextStripperByArea], which returns the text the rect overlays — that
     * is the link's display text. Rects are inset by [LINK_RECT_INSET] points on every edge before
     * the area query: link rects are routinely drawn 1–2 pt larger than the glyph run, and an
     * un-inset rect bleeds adjacent words into the captured text. When the captured text is blank
     * (image link, vector-drawn anchor, off-text rect) the record is dropped — we never emit an
     * empty `[]`.
     *
     * ## Targets
     *
     * - [PDActionURI] → the raw URI (external link).
     * - [PDActionGoTo] (and the document-catalog's named-destination tree) → resolved to the
     *   destination's 1-based page number. The page number alone is always recoverable; mapping it
     *   to the nearest section anchor is left to [PdfPipeline], which holds the outline headings.
     *
     * Links whose action is neither URI nor GoTo (Launch, JavaScript, …) are skipped — they are
     * not navigable references for an LLM reading the prose.
     *
     * @return Link records in (page, then rect reading-order) order. Empty when the PDF has no
     *         resolvable link annotations.
     */
    fun extractLinks(file: Path): List<PdfLink> {
        val result = mutableListOf<PdfLink>()
        Loader.loadPDF(file.toFile()).use { doc ->
            doc.pages.forEachIndexed { pageIdx, page ->
                val pageNumber = pageIdx + 1
                val annotations = try { page.annotations } catch (_: Exception) { return@forEachIndexed }
                val links = annotations.filterIsInstance<PDAnnotationLink>()
                if (links.isEmpty()) return@forEachIndexed

                // One stripper per page; register every link rect as a named region, then extract
                // once. Cheaper and more robust than re-stripping the page per annotation.
                val stripper = PDFTextStripperByArea()
                val regionNames = mutableListOf<Pair<String, PdfLink.PartialTarget>>()
                links.forEachIndexed { i, link ->
                    val rect = link.rectangle ?: return@forEachIndexed
                    val target = resolveLinkTarget(link, doc) ?: return@forEachIndexed
                    val regionName = "link_$i"
                    // PDFTextStripperByArea expects an AWT Rectangle in *device* (top-down) space
                    // matching how PDFBox lays out the page. The library's own examples build it
                    // from the rect's lowerLeftX / (pageHeight - upperRightY). We inset to avoid
                    // capturing neighbouring glyphs.
                    val mediaBox = page.mediaBox
                    val pageHeight = mediaBox.height
                    val llx = rect.lowerLeftX + LINK_RECT_INSET
                    val w = (rect.width - 2 * LINK_RECT_INSET).coerceAtLeast(1f)
                    val h = (rect.height - 2 * LINK_RECT_INSET).coerceAtLeast(1f)
                    // top-down y of the rect's upper edge
                    val topY = pageHeight - rect.upperRightY + LINK_RECT_INSET
                    stripper.addRegion(regionName, Rectangle2D.Float(llx, topY, w, h))
                    regionNames += regionName to target
                }
                if (regionNames.isEmpty()) return@forEachIndexed

                try {
                    stripper.extractRegions(page)
                } catch (_: Exception) {
                    return@forEachIndexed
                }

                for ((regionName, target) in regionNames) {
                    val display = try {
                        stripper.getTextForRegion(regionName)?.trim().orEmpty()
                    } catch (_: Exception) { "" }
                    // Collapse internal whitespace runs (the stripper can keep line breaks).
                    val displayText = display.replace(Regex("\\s+"), " ").trim()
                    if (displayText.isEmpty()) continue
                    result += PdfLink(page = pageNumber, displayText = displayText, target = target)
                }
            }
        }
        return result
    }

    /**
     * Resolves a link annotation's action into a [PdfLink.PartialTarget]:
     * `Uri` for [PDActionURI]; `InternalPage` (1-based) for [PDActionGoTo] / named destinations.
     * Returns null for unsupported actions or unresolvable destinations.
     */
    private fun resolveLinkTarget(link: PDAnnotationLink, doc: PDDocument): PdfLink.PartialTarget? {
        val action = try { link.action } catch (_: Exception) { null }
        when (action) {
            is PDActionURI -> {
                val uri = try { action.uri } catch (_: Exception) { null }
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                return PdfLink.PartialTarget.Uri(uri)
            }
            is PDActionGoTo -> {
                val dest = try { action.destination } catch (_: Exception) { null } ?: return null
                return resolveDestinationPage(dest, doc)?.let { PdfLink.PartialTarget.InternalPage(it) }
            }
        }
        // Some PDFs put the destination directly on the annotation (`/Dest`) instead of an action.
        val dest = try { link.destination } catch (_: Exception) { null }
        if (dest != null) {
            return resolveDestinationPage(dest, doc)?.let { PdfLink.PartialTarget.InternalPage(it) }
        }
        return null
    }

    /**
     * Resolves a [PDDestination] (explicit page destination OR named destination) to a 1-based
     * page number. Named destinations are looked up in the document catalog's dests name tree.
     * Returns null when the page cannot be resolved.
     */
    private fun resolveDestinationPage(dest: PDDestination, doc: PDDocument): Int? {
        val pageDest = when (dest) {
            is PDPageDestination -> dest
            is PDNamedDestination -> try { doc.documentCatalog?.findNamedDestinationPage(dest) } catch (_: Exception) { null }
            else -> null
        } ?: return null
        // PDPageDestination.retrievePageNumber() is 0-based (and -1 when the page ref is missing).
        val zeroBased = try { pageDest.retrievePageNumber() } catch (_: Exception) { -1 }
        if (zeroBased >= 0) return zeroBased + 1
        // Fall back to identity lookup against the page list.
        val page = try { pageDest.page } catch (_: Exception) { null } ?: return null
        val idx = try { doc.pages.indexOf(page) } catch (_: Exception) { -1 }
        return if (idx >= 0) idx + 1 else null
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
        // SF-9: some generators stamp a tool artifact (e.g. "Enscript Output") into /Title. Drop a
        // title that is a known artifact AND corroborated by the producing tool's name in
        // Creator/Producer, so the bogus title is not surfaced as a document property.
        val creatorProducer = listOfNotNull(info.creator, info.producer).joinToString(" ").lowercase()
        val title = info.title?.takeIf { it.isNotBlank() }?.takeUnless { t ->
            val lower = t.trim().lowercase()
            GENERATOR_TITLE_ARTIFACTS.any { artifact ->
                lower == artifact && artifact.substringBefore(' ') in creatorProducer
            }
        }
        val pairs = buildList {
            title?.let { add("Title" to it) }
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
     * Harvests the PDF outline (bookmarks) tree into one [DocumentBlock.Heading] per node,
     * positioned at the node's destination page. NAV-4/NAV-6.
     *
     * The outline nesting depth maps directly onto the heading level (depth 0 → H1, depth 1 → H2,
     * …, clamped to 1..6), so a tagged spec PDF recovers its real section hierarchy:
     * `INTRODUCTION` (H1) → `1.1 PURPOSE` (H2) → `AC-1 …` (H3). This is the authoritative anchor
     * set; [PdfPipeline] suppresses the prose heuristic's promoted headings when these exist so
     * the noisy/inverted heuristic anchors do not compete.
     *
     * Each heading is positioned at its destination page with a small fractional `top` (just after
     * the page marker at 0.0, before the first prose block at 1.0) and a monotonically increasing
     * sub-offset so multiple bookmarks landing on the same page keep their outline order. Nodes
     * whose destination cannot be resolved are anchored to page 1 (top of document) so the section
     * still appears in the index rather than being silently dropped.
     *
     * Returns an empty list when the document has no outline or every title is blank — the caller
     * then falls back to the Bookmarks key/value group + prose heuristic.
     */
    private fun extractOutlineHeadings(doc: PDDocument): List<PositionedBlock<DocumentBlock>> {
        val outline = try { doc.documentCatalog?.documentOutline } catch (_: Exception) { null }
            ?: return emptyList()
        val sink = mutableListOf<PositionedBlock<DocumentBlock>>()
        // Per-page running sub-offset so same-page bookmarks keep outline order and sort before
        // the first prose block (top=1.0). Capped well under 1.0.
        val perPageOrder = HashMap<Int, Int>()
        walkOutlineHeadings(outline.firstChild, doc, sink, depth = 0, perPageOrder = perPageOrder)
        return sink
    }

    private fun walkOutlineHeadings(
        node: PDOutlineItem?,
        doc: PDDocument,
        sink: MutableList<PositionedBlock<DocumentBlock>>,
        depth: Int,
        perPageOrder: HashMap<Int, Int>,
    ) {
        var n = node
        while (n != null) {
            val title = n.title?.trim().orEmpty()
            if (title.isNotEmpty()) {
                val targetPage = try {
                    n.findDestinationPage(doc)?.let { doc.pages.indexOf(it) + 1 }
                } catch (_: Exception) { null }
                val page = targetPage?.takeIf { it >= 1 } ?: 1
                val level = (depth + 1).coerceIn(1, 6)
                val order = perPageOrder.merge(page, 1, Int::plus)!!
                // top in (0.0, 1.0): after the page marker (0.0), before the first prose (1.0),
                // preserving same-page outline order. 0.001 step supports ~900 bookmarks/page.
                val top = OUTLINE_HEADING_TOP_BASE + order * OUTLINE_HEADING_TOP_STEP
                sink += PositionedBlock(
                    page = page,
                    top = top,
                    bottom = top + OUTLINE_HEADING_TOP_STEP,
                    block = DocumentBlock.Heading(level, title),
                )
            }
            val firstChild = try { n.firstChild } catch (_: Exception) { null }
            walkOutlineHeadings(firstChild, doc, sink, depth + 1, perPageOrder)
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

/**
 * A harvested PDF link annotation: the display text it overlays plus its (partly-resolved) target.
 *
 * "Partial" because the URI/page is resolved here, but the final Markdown URL — in particular the
 * choice between a section anchor and a bare `#page-N` for internal links — needs the outline
 * heading set, which lives in [com.workflow.orchestrator.document.pipeline.PdfPipeline]. The
 * pipeline calls [PartialTarget] → final URL there.
 *
 * @param page        1-based page the link annotation sits on.
 * @param displayText The text the link rectangle overlays (the visible anchor text).
 * @param target      Where the link points.
 */
data class PdfLink(
    val page: Int,
    val displayText: String,
    val target: PartialTarget,
) {
    sealed interface PartialTarget {
        /** External hyperlink. */
        data class Uri(val uri: String) : PartialTarget

        /** Internal GoTo / named destination, resolved to a 1-based page number. */
        data class InternalPage(val page: Int) : PartialTarget
    }
}
