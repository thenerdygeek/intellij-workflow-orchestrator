package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.xmlbeans.XmlObject
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph

/**
 * Emits [DocumentBlock.Comment] blocks with [DocumentBlock.Comment.Kind.TRACKED_INSERTION]
 * or [DocumentBlock.Comment.Kind.TRACKED_DELETION] for DOCX tracked changes inside the
 * visited paragraph.
 *
 * Tracked changes are represented by `<w:ins>` and `<w:del>` child elements of `<w:p>`.
 * Both carry a `w:author` attribute and contain child `<w:r>` runs with the affected text:
 * - Insertions: runs contain `<w:t>` elements.
 * - Deletions: runs contain `<w:delText>` elements.
 *
 * ## Ordering
 *
 * Multiple `<w:ins>` and `<w:del>` elements inside a single paragraph can interleave in
 * arbitrary order. This visitor walks the CTP via [org.apache.xmlbeans.XmlCursor] in
 * document order so comment blocks are emitted in the order the tracked changes appear
 * in the XML — matching the author's edit sequence.
 *
 * ## Mapping to [DocumentBlock.Comment]
 *
 * | Tracked change | `author` | `anchorText` | `text` |
 * |---|---|---|---|
 * | `w:ins` | `w:author` | `null` | concatenated `<w:t>` content |
 * | `w:del` | `w:author` | concatenated `<w:delText>` content | `""` (blank) |
 *
 * The deleted text goes in `anchorText` so [com.workflow.orchestrator.document.assembler.MarkdownAssembler]
 * can render it inline in the header as *"Tom proposes deleting: "old text""*; the body
 * is left blank to trigger the no-body branch in the assembler.
 */
class TrackedChangeVisitor : ParagraphVisitor {

    override fun visit(paragraph: XWPFParagraph, doc: XWPFDocument): List<DocumentBlock> {
        val ctp = paragraph.ctp
        // Fast-path: skip paragraphs with no tracked changes at all.
        if (ctp.sizeOfInsArray() == 0 && ctp.sizeOfDelArray() == 0) return emptyList()

        val result = mutableListOf<DocumentBlock>()
        val cursor = ctp.newCursor()
        try {
            while (cursor.hasNextToken()) {
                cursor.toNextToken()
                if (!cursor.isStart) continue

                val name = cursor.name ?: continue
                if (name.namespaceURI != W_NS) continue

                when (name.localPart) {
                    "ins" -> {
                        val obj: XmlObject = cursor.`object`
                        if (obj !is CTRunTrackChange) continue
                        val text = obj.rArray.joinToString("") { run ->
                            run.tArray.joinToString("") { it.stringValue.orEmpty() }
                        }
                        result += DocumentBlock.Comment(
                            author = obj.author?.takeIf { it.isNotBlank() },
                            anchorText = null,
                            text = text,
                            kind = DocumentBlock.Comment.Kind.TRACKED_INSERTION,
                        )
                    }
                    "del" -> {
                        val obj: XmlObject = cursor.`object`
                        if (obj !is CTRunTrackChange) continue
                        val deletedText = obj.rArray.joinToString("") { run ->
                            run.delTextArray.joinToString("") { it.stringValue.orEmpty() }
                        }
                        result += DocumentBlock.Comment(
                            author = obj.author?.takeIf { it.isNotBlank() },
                            anchorText = deletedText.takeIf { it.isNotEmpty() },
                            text = "",
                            kind = DocumentBlock.Comment.Kind.TRACKED_DELETION,
                        )
                    }
                }
            }
        } finally {
            cursor.dispose()
        }
        return result
    }

    private companion object {
        /** Namespace URI for all WordprocessingML elements (`w:*`). */
        const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    }
}
