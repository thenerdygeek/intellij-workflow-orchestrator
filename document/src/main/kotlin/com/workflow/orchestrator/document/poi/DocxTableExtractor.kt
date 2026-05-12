package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.poi.visitor.CommentExtractionVisitor
import com.workflow.orchestrator.document.poi.visitor.DefaultHeadingParagraphVisitor
import com.workflow.orchestrator.document.poi.visitor.ImageExtractionVisitor
import com.workflow.orchestrator.document.poi.visitor.TrackedChangeVisitor
import com.workflow.orchestrator.document.poi.visitor.DefaultTableVisitor
import com.workflow.orchestrator.document.poi.visitor.ParagraphVisitor
import com.workflow.orchestrator.document.poi.visitor.PostBodyVisitor
import com.workflow.orchestrator.document.poi.visitor.TableVisitor
import com.workflow.orchestrator.document.service.ImageExtractionService
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
 * it via `use { }`. Visitor instances may be shared across calls if they are stateless
 * (the defaults are).
 *
 * @param paragraphVisitors Visitors run for every body paragraph. Default chain includes
 *                          [DefaultHeadingParagraphVisitor], [CommentExtractionVisitor],
 *                          [TrackedChangeVisitor], and (when [imageService] is non-null)
 *                          [ImageExtractionVisitor].
 * @param tableVisitors     Visitors run for every body table. Default: a single
 *                          [DefaultTableVisitor] preserving pre-refactor behaviour.
 * @param postBodyVisitors  Visitors run once after body iteration. Default: empty.
 */
class DocxTableExtractor(
    private val paragraphVisitors: List<ParagraphVisitor>,
    private val tableVisitors: List<TableVisitor> = listOf(DefaultTableVisitor()),
    private val postBodyVisitors: List<PostBodyVisitor> = emptyList(),
) {

    /**
     * Convenience constructor preserving the Phase 0/1 default visitor set. When
     * [imageService] is non-null, an [ImageExtractionVisitor] is appended to the chain.
     */
    constructor(
        imageService: ImageExtractionService? = null,
        docKey: String = "anonymous",
        tableVisitors: List<TableVisitor> = listOf(DefaultTableVisitor()),
        postBodyVisitors: List<PostBodyVisitor> = emptyList(),
    ) : this(
        paragraphVisitors = buildList {
            add(DefaultHeadingParagraphVisitor())
            add(CommentExtractionVisitor())
            add(TrackedChangeVisitor())
            if (imageService != null) {
                add(ImageExtractionVisitor(imageService, docKey))
            }
        },
        tableVisitors = tableVisitors,
        postBodyVisitors = postBodyVisitors,
    )

    init {
        PoiHardening.applyOnce()
    }

    /**
     * Extracts [DocumentBlock] values from a DOCX [stream] in document order.
     *
     * @param stream Raw bytes of the `.docx` file. The caller is responsible for closing the stream.
     * @return Ordered list of blocks in document order, with post-body blocks appended.
     */
    fun extract(stream: InputStream): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()

        XWPFDocument(stream).use { doc ->
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
        }

        return blocks
    }
}
