package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
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
 * @see PdfPipeline for the merge/sort step that consumes these blocks.
 */
class PdfMetadataExtractor {

    /**
     * Returns positioned [DocumentBlock] entries for the PDF:
     * - One [DocumentBlock.KeyValueGroup] for document properties (when present), at page 1, top=-2.0.
     * - One [DocumentBlock.KeyValueGroup] for bookmarks (when present), at page 1, top=-1.0.
     * - One [DocumentBlock.Comment] per markup annotation across all pages.
     * - One [DocumentBlock.KeyValueGroup] for AcroForm fields (when present), at last page, top=MAX.
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
}
