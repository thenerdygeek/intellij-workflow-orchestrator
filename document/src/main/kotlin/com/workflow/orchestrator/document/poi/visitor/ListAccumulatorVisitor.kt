package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import java.math.BigInteger

/**
 * Accumulates consecutive DOCX list-item paragraphs (where [XWPFParagraph.getNumID] is
 * non-null) and emits them as one [DocumentBlock.ListBlock] when the run breaks — i.e.
 * when the next paragraph is not a list item, when the numID changes to a new list, or
 * when the document body ends.
 *
 * Implements both [ParagraphVisitor] (per-paragraph accumulation + flush-on-numId-change)
 * and [PostBodyVisitor] (final flush at end of body). Register the SAME instance in both
 * the paragraph and post-body visitor lists inside [DocxTableExtractor].
 *
 * ## Ordered vs unordered detection
 *
 * Uses [XWPFNumbering.getAbstractNumID] to resolve the numId to an abstract-num entry,
 * then reads `CTAbstractNum.getLvlArray()[0].getNumFmt().getVal()`. A value of "BULLET"
 * maps to `ordered = false`; anything else ("DECIMAL", "LOWER_LETTER", "UPPER_ROMAN", …)
 * maps to `ordered = true`. Any lookup failure (null numbering, missing abstract-num,
 * empty lvl array) defaults to `ordered = false` — the safer user-visible choice.
 *
 * ## Nesting
 *
 * [XWPFParagraph.getNumIlvl] (0-indexed) gives the nesting level. Nesting is NOT a
 * model concept in [DocumentBlock.ListBlock] — it is encoded as leading `"  "` (two
 * spaces) per level inside the item text. Level 0 → `"item"`, level 1 → `"  item"`.
 *
 * ## Statefulness
 *
 * This visitor is stateful across paragraph visits within one extract call. The
 * [DocxTableExtractor] convenience constructor creates one new instance per call and
 * registers it in both default-visitor lists. Do NOT share a single instance across
 * multiple extract() calls.
 */
class ListAccumulatorVisitor : ParagraphVisitor, PostBodyVisitor {

    private val items = mutableListOf<String>()
    private var currentNumId: BigInteger? = null
    private var currentOrdered: Boolean = false

    /**
     * Comments anchored to list-item paragraphs (paragraph.numID != null) that have been
     * deferred via [addPendingComment] and are waiting for the next list flush. Drained
     * (and cleared) inside [flush] so they emit AFTER the [DocumentBlock.ListBlock] in
     * the output stream, preserving the sequential reading order.
     */
    private val pendingListItemComments = mutableListOf<DocumentBlock.Comment>()

    // ── ParagraphVisitor ──────────────────────────────────────────────────────

    override fun visit(paragraph: XWPFParagraph, doc: XWPFDocument): List<DocumentBlock> {
        val numId = paragraph.numID
        val text = paragraph.text.trim()

        return when {
            numId == null && items.isEmpty() -> {
                // Normal paragraph, no active accumulator — nothing to do here.
                emptyList()
            }
            numId == null -> {
                // Run ended: flush the current list and let the next visitors handle this paragraph.
                flush()
            }
            currentNumId == null || numId == currentNumId -> {
                // Continue (or start) the current list.
                if (currentNumId == null) {
                    currentNumId = numId
                    currentOrdered = lookupOrdered(doc, numId)
                }
                if (text.isNotEmpty()) {
                    items += indented(text, paragraph.numIlvl)
                }
                emptyList()
            }
            else -> {
                // numId changed — flush the previous list, start a new one.
                val flushed = flush()
                currentNumId = numId
                currentOrdered = lookupOrdered(doc, numId)
                if (text.isNotEmpty()) {
                    items += indented(text, paragraph.numIlvl)
                }
                flushed
            }
        }
    }

    // ── PostBodyVisitor ───────────────────────────────────────────────────────

    override fun visit(doc: XWPFDocument): List<DocumentBlock> = flush()

    // ── Cross-visitor side channel ────────────────────────────────────────────

    /**
     * Accepts a Comment block that anchors to a list-item paragraph. The comment is held
     * until the current list run flushes, then emitted AFTER the [DocumentBlock.ListBlock]
     * so the block stream reads ListBlock → Comment(s) → next-content, preserving the
     * sequential contract of the list.
     *
     * Called by [CommentExtractionVisitor] (and conceptually by any other future visitor
     * that wants to anchor adjunct content to a list item) instead of returning the
     * Comment directly from its `visit()` method.
     */
    fun addPendingComment(comment: DocumentBlock.Comment) {
        pendingListItemComments += comment
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun indented(text: String, ilvl: BigInteger?): String {
        val level = ilvl?.toInt()?.coerceAtLeast(0) ?: 0
        return "  ".repeat(level) + text
    }

    /**
     * Flushes the current accumulator and any deferred list-item comments. Returns
     * `[ListBlock, Comment*]` when items were buffered, or an empty list when the
     * accumulator was empty. In both cases ALL state (items, numId, ordered flag,
     * pending comments) is reset.
     *
     * Pending comments are only emitted when there is a ListBlock to anchor them to;
     * when items is empty (no list run was in progress) any pending comments are
     * dropped along with the rest of the state. In practice this never happens because
     * [CommentExtractionVisitor] only routes comments here when `paragraph.numID != null`,
     * which always coincides with a list run being active.
     */
    private fun flush(): List<DocumentBlock> {
        if (items.isEmpty()) {
            currentNumId = null
            currentOrdered = false
            pendingListItemComments.clear()
            return emptyList()
        }
        val block = DocumentBlock.ListBlock(ordered = currentOrdered, items = items.toList())
        items.clear()
        currentNumId = null
        currentOrdered = false
        val emitted = buildList<DocumentBlock> {
            add(block)
            addAll(pendingListItemComments)
        }
        pendingListItemComments.clear()
        return emitted
    }

    /**
     * Returns true when [numId] resolves to an ordered (non-bullet) list definition.
     *
     * Chain: `XWPFNumbering.getAbstractNumID(numId)` → `getAbstractNum(abstractNumId)` →
     * `CTAbstractNum.getLvlArray()[0].getNumFmt().getVal()`.
     *
     * Defaults to false (unordered/bulleted) on any failure — the safe fallback for
     * malformed or absent numbering metadata.
     */
    private fun lookupOrdered(doc: XWPFDocument, numId: BigInteger): Boolean {
        return try {
            val numbering = doc.numbering ?: return false
            val abstractNumId = numbering.getAbstractNumID(numId) ?: return false
            val abstractNum = numbering.getAbstractNum(abstractNumId) ?: return false
            val ctAbstractNum = abstractNum.ctAbstractNum ?: return false
            val firstLevel = ctAbstractNum.lvlArray?.firstOrNull() ?: return false
            val numFmt = firstLevel.numFmt?.`val`?.toString()?.lowercase() ?: return false
            numFmt != "bullet"
        } catch (_: Exception) {
            false
        }
    }
}
