package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.poi.visitor.CommentExtractionVisitor
import com.workflow.orchestrator.document.poi.visitor.DefaultHeadingParagraphVisitor
import com.workflow.orchestrator.document.poi.visitor.DefaultTableVisitor
import com.workflow.orchestrator.document.poi.visitor.FootnoteExtractionVisitor
import com.workflow.orchestrator.document.poi.visitor.ImageExtractionVisitor
import com.workflow.orchestrator.document.poi.visitor.ListAccumulatorVisitor
import com.workflow.orchestrator.document.poi.visitor.ParagraphVisitor
import com.workflow.orchestrator.document.poi.visitor.PostBodyVisitor
import com.workflow.orchestrator.document.poi.visitor.TableVisitor
import com.workflow.orchestrator.document.poi.visitor.TrackedChangeVisitor
import com.workflow.orchestrator.document.service.ImageExtractionService
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy
import org.apache.poi.xwpf.usermodel.IBodyElement
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.InputStream

/**
 * Extracts content from DOCX files into [DocumentBlock] lists via Apache POI XWPF.
 *
 * Drives a composable visitor chain over `XWPFDocument.bodyElements` so subsequent
 * phases (comments, images, lists) can add new visitors without touching the body
 * iteration. Phase 0 ships the default chain that produces byte-for-byte the same
 * output as the pre-refactor extractor — `DocxVisitorChainTest` and
 * `DocxTableExtractorTest` are the regression net.
 *
 * ## Iteration order
 *
 * 1. For each `IBodyElement` in document order:
 *    - `XWPFParagraph` → every visitor in [paragraphVisitors] runs in declaration order;
 *      each visitor's emitted blocks are appended.
 *    - `XWPFTable` → every visitor in [tableVisitors] runs the same way.
 *    - Other element types (SDT, drawing canvases) → skipped.
 * 2. After body iteration completes, every visitor in [postBodyVisitors] runs once;
 *    its emitted blocks are appended at the end. This is the canonical slot for
 *    footnotes / endnotes / doc-summary key-value groups that must surface AFTER body.
 *
 * ## Thread safety
 *
 * Per-call instantiation only. `XWPFDocument` is NOT thread-safe; the extractor closes
 * it via `use { }`. The default [ListAccumulatorVisitor] is stateful per-extract-call;
 * the convenience constructor creates fresh visitor instances for every call via
 * [defaultChains], so parallel calls are safe as long as each uses its own extractor.
 *
 * @param paragraphVisitors Visitors run for every body paragraph. Default chain includes
 *                          [ListAccumulatorVisitor] (first), [DefaultHeadingParagraphVisitor],
 *                          [CommentExtractionVisitor], [TrackedChangeVisitor], and (when
 *                          [imageService] is non-null) [ImageExtractionVisitor].
 * @param tableVisitors     Visitors run for every body table. Default: a single
 *                          [DefaultTableVisitor] preserving pre-refactor behaviour.
 * @param postBodyVisitors  Visitors run once after body iteration. Default chain includes
 *                          the same [ListAccumulatorVisitor] instance as [paragraphVisitors]
 *                          so the final list run (if any) is flushed at end-of-body.
 */
class DocxTableExtractor(
    private val paragraphVisitors: List<ParagraphVisitor>,
    private val tableVisitors: List<TableVisitor> = listOf(DefaultTableVisitor()),
    private val postBodyVisitors: List<PostBodyVisitor> = emptyList(),
) {

    companion object {
        /**
         * Builds the default Phase 0–3 visitor chains. [ListAccumulatorVisitor] is
         * created once and registered in BOTH the paragraph and post-body lists so that
         * its end-of-body flush uses the same accumulator state as the paragraph walk.
         */
        fun defaultChains(
            imageService: ImageExtractionService? = null,
            docKey: String = "anonymous",
        ): Triple<List<ParagraphVisitor>, List<TableVisitor>, List<PostBodyVisitor>> {
            val listAccumulator = ListAccumulatorVisitor()
            val paragraphVisitors = buildList<ParagraphVisitor> {
                add(listAccumulator)                     // FIRST: accumulate / flush list items
                add(DefaultHeadingParagraphVisitor())
                // Share the accumulator so comments on list-item paragraphs are deferred
                // until after the ListBlock flush (preserves [ListBlock, Comment] order).
                add(CommentExtractionVisitor(listAccumulator = listAccumulator))
                add(TrackedChangeVisitor())
                if (imageService != null) add(ImageExtractionVisitor(imageService, docKey))
            }
            val tableVisitors = listOf(DefaultTableVisitor())
            val postBodyVisitors = listOf<PostBodyVisitor>(
                listAccumulator,
                FootnoteExtractionVisitor(),
            )
            return Triple(paragraphVisitors, tableVisitors, postBodyVisitors)
        }
    }

    /**
     * Convenience constructor that delegates to [defaultChains] so that a single
     * [ListAccumulatorVisitor] instance is shared between paragraph and post-body lists.
     */
    private constructor(
        chains: Triple<List<ParagraphVisitor>, List<TableVisitor>, List<PostBodyVisitor>>,
    ) : this(chains.first, chains.second, chains.third)

    constructor(
        imageService: ImageExtractionService? = null,
        docKey: String = "anonymous",
    ) : this(defaultChains(imageService, docKey))

    init {
        PoiHardening.applyOnce()
    }

    /**
     * Extracts [DocumentBlock] values from a DOCX [stream] in document order.
     *
     * After body iteration and post-body visitors, any SmartArt data-model parts found
     * in the document's OPC package are extracted via [SmartArtExtractor] and appended
     * as flat [DocumentBlock.ListBlock] values. Visual hierarchy (arrows, levels) is
     * dropped — text-only extraction per the P5a-4 spec.
     *
     * @param stream Raw bytes of the `.docx` file. The caller is responsible for closing the stream.
     * @return Ordered list of blocks in document order, with post-body and SmartArt blocks appended.
     */
    fun extract(stream: InputStream): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()

        XWPFDocument(stream).use { doc ->
            // Phase 3 T4: prepend header / footer paragraphs (one each, deduplicated).
            blocks += extractHeaderFooter(doc)

            for (element: IBodyElement in doc.bodyElements) {
                when (element) {
                    is XWPFParagraph -> {
                        for (visitor in paragraphVisitors) {
                            blocks += visitor.visit(element, doc)
                        }
                    }
                    is XWPFTable -> {
                        for (visitor in tableVisitors) {
                            blocks += visitor.visit(element, doc)
                        }
                    }
                    else -> continue
                }
            }

            for (visitor in postBodyVisitors) {
                blocks += visitor.visit(doc)
            }

            // P5a-4: SmartArt text extraction. Each diagramData part in the package
            // becomes one flat ListBlock. Appended at end so body content comes first.
            try {
                blocks += SmartArtExtractor.extract(doc.getPackage())
            } catch (_: Exception) {
                // Package access failure — SmartArt extraction is non-critical.
            }
        }

        return blocks
    }

    /**
     * Extracts default header and footer text from [doc] via [XWPFHeaderFooterPolicy] and
     * returns them as [DocumentBlock.Paragraph] blocks prefixed with `"> Header: "` /
     * `"> Footer: "`. Both blocks land at the START of the output (before any body content)
     * so the LLM sees the document's identity/classification metadata first.
     *
     * Per-page repetition is NOT emitted — one block per document, deduplicated.
     *
     * If the document has no header/footer policy, or if the default header/footer paragraphs
     * are all blank, nothing is emitted.
     */
    private fun extractHeaderFooter(doc: XWPFDocument): List<DocumentBlock> {
        val policy: XWPFHeaderFooterPolicy = try {
            doc.headerFooterPolicy
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val out = mutableListOf<DocumentBlock>()

        val headerText = try {
            policy.defaultHeader?.paragraphs
                ?.joinToString("\n") { it.text.trim() }
                ?.trim()
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (headerText.isNotEmpty()) {
            out += DocumentBlock.Paragraph("> Header: $headerText")
        }

        val footerText = try {
            policy.defaultFooter?.paragraphs
                ?.joinToString("\n") { it.text.trim() }
                ?.trim()
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (footerText.isNotEmpty()) {
            out += DocumentBlock.Paragraph("> Footer: $footerText")
        }

        return out
    }
}
