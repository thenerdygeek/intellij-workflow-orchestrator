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
                val flushed = flush()
                flushed?.let { listOf(it) } ?: emptyList()
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
                flushed?.let { listOf(it) } ?: emptyList()
            }
        }
    }

    // ── PostBodyVisitor ───────────────────────────────────────────────────────

    override fun visit(doc: XWPFDocument): List<DocumentBlock> {
        val flushed = flush()
        return flushed?.let { listOf(it) } ?: emptyList()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun indented(text: String, ilvl: BigInteger?): String {
        val level = ilvl?.toInt()?.coerceAtLeast(0) ?: 0
        return "  ".repeat(level) + text
    }

    /**
     * Flushes the current accumulator as a [DocumentBlock.ListBlock] and resets state.
     * Returns null (and still resets) when the accumulator is empty.
     */
    private fun flush(): DocumentBlock.ListBlock? {
        if (items.isEmpty()) {
            currentNumId = null
            currentOrdered = false
            return null
        }
        val block = DocumentBlock.ListBlock(ordered = currentOrdered, items = items.toList())
        items.clear()
        currentNumId = null
        currentOrdered = false
        return block
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
