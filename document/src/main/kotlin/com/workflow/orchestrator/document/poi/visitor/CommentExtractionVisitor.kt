package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable

/**
 * Emits [DocumentBlock.Comment] blocks for DOCX review comments, covering both
 * top-level body paragraphs (via [ParagraphVisitor]) and paragraphs inside table
 * cells (via [TableVisitor]).
 *
 * A comment is emitted when the `w:commentRangeEnd` marker for that comment falls
 * inside the visited paragraph. Anchor text is the first 60 chars of the
 * paragraph's trimmed text. Multiple comments ending in the same paragraph are
 * emitted in ascending `w:id` order. Comments whose ID is not found in the
 * document (orphaned markers) are silently skipped.
 *
 * ## Why TableVisitor is required
 *
 * `doc.bodyElements` yields top-level `XWPFParagraph` and `XWPFTable` objects only.
 * Paragraphs inside table cells are NOT surfaced as direct body elements, so a
 * `ParagraphVisitor`-only approach silently drops any comment whose anchor text
 * lives in a table cell. [visit(XWPFTable)] walks every cell paragraph to close
 * this gap.
 *
 * ## List-item anchoring
 *
 * When [listAccumulator] is non-null AND the visited paragraph is a list item
 * (`paragraph.numID != null`), comments are routed into the accumulator's
 * pending-comment buffer instead of being returned immediately. The accumulator
 * emits them AFTER its [DocumentBlock.ListBlock] on flush, so the block stream
 * reads `[..., ListBlock, Comment, ...]` rather than `[..., Comment, ..., ListBlock, ...]`.
 * List-item paragraphs inside table cells follow the same routing.
 *
 * @param listAccumulator Optional accumulator shared with [ListAccumulatorVisitor];
 *                        when null (e.g. test fixtures that don't use the default
 *                        chain), comments emit synchronously as before.
 */
class CommentExtractionVisitor(
    private val listAccumulator: ListAccumulatorVisitor? = null,
) : ParagraphVisitor, TableVisitor {

    // ── ParagraphVisitor ─────────────────────────────────────────────────────

    override fun visit(paragraph: XWPFParagraph, doc: XWPFDocument): List<DocumentBlock> =
        commentsForParagraph(paragraph, doc)

    // ── TableVisitor ─────────────────────────────────────────────────────────

    /** Walks every cell paragraph in [table] and collects comments from each. */
    override fun visit(table: XWPFTable, doc: XWPFDocument): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()
        for (row in table.rows) {
            for (cell in row.tableCells) {
                for (paragraph in cell.paragraphs) {
                    blocks += commentsForParagraph(paragraph, doc)
                }
            }
        }
        return blocks
    }

    // ── Shared logic ──────────────────────────────────────────────────────────

    private fun commentsForParagraph(paragraph: XWPFParagraph, doc: XWPFDocument): List<DocumentBlock> {
        val endingCommentIds = collectCommentRangeEndIds(paragraph)
        if (endingCommentIds.isEmpty()) return emptyList()

        val anchorText = paragraph.text.trim().take(MAX_ANCHOR_LEN)

        val comments = endingCommentIds.mapNotNull { id ->
            val comment = doc.getCommentByID(id) ?: return@mapNotNull null
            DocumentBlock.Comment(
                author = comment.author?.takeIf { it.isNotBlank() },
                anchorText = anchorText.takeIf { it.isNotEmpty() },
                text = comment.text.orEmpty(),
                kind = DocumentBlock.Comment.Kind.REVIEW,
            )
        }

        // If this paragraph is a list item AND we have a listAccumulator, route the
        // comments through it so they emit AFTER the ListBlock instead of mid-stream.
        if (listAccumulator != null && paragraph.numID != null) {
            comments.forEach(listAccumulator::addPendingComment)
            return emptyList()
        }

        return comments
    }

    /**
     * Returns the `w:id` values of all `w:commentRangeEnd` elements in this paragraph,
     * sorted in ascending numeric order so multiple comments on the same paragraph are
     * emitted deterministically.
     *
     * Uses [org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP.getCommentRangeEndArray]
     * directly — faster than cursor walking and avoids XmlBeans token iteration.
     */
    private fun collectCommentRangeEndIds(paragraph: XWPFParagraph): List<String> {
        val ends = paragraph.ctp.commentRangeEndArray
        if (ends.isEmpty()) return emptyList()
        return ends
            .mapNotNull { range -> range.id?.let { it.toLong() to it.toString() } }
            .sortedBy { it.first }
            .map { it.second }
    }

    private companion object {
        const val MAX_ANCHOR_LEN = 60
    }
}
