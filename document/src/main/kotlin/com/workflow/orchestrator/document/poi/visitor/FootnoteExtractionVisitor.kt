package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.xwpf.usermodel.XWPFAbstractFootnoteEndnote
import org.apache.poi.xwpf.usermodel.XWPFDocument

/**
 * Emits [DocumentBlock.Footnote] blocks for every DOCX footnote and endnote in the
 * document. Runs once at the end of body iteration via the [PostBodyVisitor] slot,
 * so the emitted blocks land at the END of the extractor's `List<DocumentBlock>` —
 * matching the spec's "extractor's responsibility to order" contract.
 *
 * ## Marker semantics
 *
 * The `marker` field of [DocumentBlock.Footnote] is the footnote's `w:id` as a String.
 * Word generates these starting at id="1" for the first footnote/endnote.
 *
 * ## Footnotes + endnotes in one block
 *
 * Footnotes and endnotes are conceptually different (footnotes appear at the bottom of
 * the page they reference; endnotes at the very end of the document), but for LLM
 * reading purposes both become trailing `Footnote` blocks. Footnotes emit first, then
 * endnotes — both in id order.
 *
 * Empty footnotes (id with no body paragraphs, or all-blank text) are skipped.
 *
 * ## POI default skips
 *
 * Word auto-creates two "separator" footnotes (id=-1 and id=0) used for the page-bottom
 * separator line and continuation marker. These have no useful body content and are
 * automatically skipped: their id is negative or zero, OR their text is blank.
 */
class FootnoteExtractionVisitor : PostBodyVisitor {

    override fun visit(doc: XWPFDocument): List<DocumentBlock> {
        val out = mutableListOf<DocumentBlock>()

        // Footnotes
        val footnotes = try { doc.footnotes } catch (_: Exception) { null }
        if (footnotes != null) {
            out += footnotes.mapNotNull { toBlock(it) }
        }

        // Endnotes
        val endnotes = try { doc.endnotes } catch (_: Exception) { null }
        if (endnotes != null) {
            out += endnotes.mapNotNull { toBlock(it) }
        }

        return out
    }

    private fun toBlock(note: XWPFAbstractFootnoteEndnote): DocumentBlock.Footnote? {
        val id = try { note.id?.toString() } catch (_: Exception) { null } ?: return null
        // Skip Word's auto-generated separator footnotes (id <= 0).
        val idInt = id.toIntOrNull() ?: return null
        if (idInt <= 0) return null

        val text = try {
            note.paragraphs.joinToString("\n") { it.text.trim() }.trim()
        } catch (_: Exception) {
            return null
        }
        if (text.isEmpty()) return null

        return DocumentBlock.Footnote(marker = id, text = text)
    }
}
