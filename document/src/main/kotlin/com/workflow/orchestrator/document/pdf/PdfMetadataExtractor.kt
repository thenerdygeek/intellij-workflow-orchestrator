package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import java.nio.file.Path

/**
 * Extracts PDF markup annotations (sticky notes, free-text, highlights, etc.) as typed
 * [DocumentBlock.Comment] blocks with [DocumentBlock.Comment.Kind.PDF_ANNOTATION].
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
 * via `readingTop = mediaBoxHeight - pdfUpperRightY` so annotations near the page top get
 * small `top` values and sort before annotations near the bottom.
 *
 * @see PdfPipeline for the merge/sort step that consumes these blocks.
 */
class PdfMetadataExtractor {

    /**
     * Returns one [PositionedBlock] wrapping a [DocumentBlock.Comment] per markup annotation
     * found across all pages of [file]. Pages are walked in document order (0-indexed internally,
     * reported as 1-based page numbers). Annotations with an empty `contents` string are skipped.
     *
     * @param file Absolute path to the PDF file (random-access required by PDFBox).
     * @return Ordered list of positioned annotation blocks. Order within a page follows annotation
     *         array order in the PDF dictionary; the merge sort in [PdfPipeline] reorders by
     *         `(page, top)` across all sources.
     * @throws org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException for encrypted PDFs.
     */
    fun extract(file: Path): List<PositionedBlock<DocumentBlock>> {
        val result = mutableListOf<PositionedBlock<DocumentBlock>>()
        Loader.loadPDF(file.toFile()).use { doc ->
            doc.pages.forEachIndexed { pageIdx, page ->
                val pageNumber = pageIdx + 1
                val annotations = try {
                    page.annotations
                } catch (_: Exception) {
                    emptyList()
                }
                val mediaTop = page.mediaBox.upperRightY.toDouble()
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
                    // Translate from PDF bottom-up Y to reading-order top-down Y.
                    // PDF: origin at bottom-left, y increases upward.
                    // Pipeline: top < bottom, small top = earlier in reading order.
                    val readingTop = mediaTop - rect.upperRightY.toDouble()
                    val readingBottom = mediaTop - rect.lowerLeftY.toDouble()
                    result += PositionedBlock(
                        page = pageNumber,
                        top = readingTop,
                        bottom = readingBottom,
                        block = comment,
                    )
                }
            }
        }
        return result
    }
}
