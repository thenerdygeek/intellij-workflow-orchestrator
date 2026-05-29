package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.xwpf.usermodel.XWPFAbstractFootnoteEndnote
import org.apache.poi.xwpf.usermodel.XWPFDocument

/**
 * Shared marker-prefix scheme for DOCX footnote and endnote references (SF-7 / HX-2).
 *
 * Word numbers footnotes and endnotes in two independent id spaces, so a footnote `w:id="2"`
 * and an endnote `w:id="2"` are different notes that would both render as the GFM label `[^2]`
 * — an invalid collision. We namespace them: footnotes get an `fn` prefix and endnotes get an
 * `en` prefix, yielding distinct `[^fn2]` / `[^en2]` markers.
 *
 * Both the inline reference rewrite ([DefaultHeadingParagraphVisitor.rewriteNoteReferences])
 * and the trailing definition emission ([FootnoteExtractionVisitor]) MUST use these prefixes so
 * a `[^fn2]` reference in body prose links to the `[^fn2]: …` definition at the end.
 */
internal object NoteMarkers {
    const val FOOTNOTE_PREFIX = "fn"
    const val ENDNOTE_PREFIX = "en"
}

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

        // Footnotes — markers namespaced with the "fn" prefix.
        val footnotes = try { doc.footnotes } catch (_: Exception) { null }
        if (footnotes != null) {
            out += footnotes.mapNotNull { toBlock(it, NoteMarkers.FOOTNOTE_PREFIX) }
        }

        // Endnotes — markers namespaced with the "en" prefix so they don't collide with
        // footnotes that share the same numeric id.
        val endnotes = try { doc.endnotes } catch (_: Exception) { null }
        if (endnotes != null) {
            out += endnotes.mapNotNull { toBlock(it, NoteMarkers.ENDNOTE_PREFIX) }
        }

        return out
    }

    private fun toBlock(note: XWPFAbstractFootnoteEndnote, markerPrefix: String): DocumentBlock.Footnote? {
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

        // SF-7 / HX-2: namespace the marker (fn<id> / en<id>) so footnote and endnote ids in
        // separate Word id spaces never collide on a single [^N] GFM label, and so the marker
        // matches the inline reference rewritten by DefaultHeadingParagraphVisitor.
        return DocumentBlock.Footnote(marker = "$markerPrefix$id", text = text)
    }
}
