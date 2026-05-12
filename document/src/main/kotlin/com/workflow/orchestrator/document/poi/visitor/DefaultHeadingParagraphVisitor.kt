package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph

/**
 * Ports the current `DocxTableExtractor.paragraphToBlock` logic verbatim.
 *
 * For each [XWPFParagraph]:
 * - Empty/whitespace-only text → no block.
 * - Style ID matches "heading 1".."heading 6" (with the LibreOffice `_20_` decoding) →
 *   [DocumentBlock.Heading] of the matching level.
 * - Otherwise → [DocumentBlock.Paragraph].
 *
 * This visitor's behaviour must remain byte-for-byte identical to the pre-refactor
 * extractor — `DocxVisitorChainTest` (Task 18) regression-tests the round-trip against
 * the existing `design-doc.docx` fixture.
 */
class DefaultHeadingParagraphVisitor : ParagraphVisitor {

    override fun visit(paragraph: XWPFParagraph, doc: XWPFDocument): List<DocumentBlock> {
        val text = paragraph.text.trim()
        if (text.isEmpty()) return emptyList()

        val level = headingLevel(paragraph)
        val block = if (level != null) {
            DocumentBlock.Heading(level, text)
        } else {
            DocumentBlock.Paragraph(text)
        }
        return listOf(block)
    }

    private fun headingLevel(paragraph: XWPFParagraph): Int? {
        val styleId = paragraph.style ?: return null
        val normalized = styleId.lowercase().replace("_20_", " ").replace("_", " ")

        val withSpace = Regex("""^heading\s*(\d)$""").find(normalized)
        if (withSpace != null) {
            val level = withSpace.groupValues[1].toIntOrNull() ?: return null
            if (level in 1..6) return level
        }

        return null
    }
}
