package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun
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
 * Hyperlink URLs are preserved inline as `"<visible text> (<url>)"` by walking
 * `paragraph.runs` and resolving each [XWPFHyperlinkRun] via
 * `XWPFHyperlinkRun.getHyperlink(doc).getURL()`. Non-hyperlink runs are appended as-is.
 *
 * This visitor's behaviour must remain byte-for-byte identical to the pre-refactor
 * extractor for non-hyperlink content — `DocxVisitorChainTest` (Task 18) regression-tests
 * the round-trip against the existing `design-doc.docx` fixture.
 */
class DefaultHeadingParagraphVisitor : ParagraphVisitor {

    override fun visit(paragraph: XWPFParagraph, doc: XWPFDocument): List<DocumentBlock> {
        // List-item paragraphs are handled exclusively by ListAccumulatorVisitor so that
        // they do not double-emit as plain Paragraph blocks.
        if (paragraph.numID != null) return emptyList()

        val text = buildHyperlinkAwareText(paragraph, doc).trim()
        if (text.isEmpty()) return emptyList()

        val level = headingLevel(paragraph)
        val block = if (level != null) {
            DocumentBlock.Heading(level, text)
        } else {
            DocumentBlock.Paragraph(text)
        }
        return listOf(block)
    }

    /**
     * Walks [paragraph.runs] and appends `" (<url>)"` after each [XWPFHyperlinkRun]'s text.
     *
     * [XWPFHyperlinkRun.getHyperlink] resolves the relationship ID to the target URL via
     * the document's hyperlink table (no manual package-part navigation required). Returns
     * plain concatenated text for paragraphs that contain no hyperlinks — identical to the
     * previous `paragraph.text` output.
     */
    private fun buildHyperlinkAwareText(paragraph: XWPFParagraph, doc: XWPFDocument): String {
        val sb = StringBuilder()
        for (run in paragraph.runs) {
            val runText = run.text() ?: ""
            if (run is XWPFHyperlinkRun) {
                val url = try {
                    run.getHyperlink(doc)?.url
                } catch (_: Exception) {
                    null
                }
                if (!url.isNullOrBlank()) {
                    sb.append(runText).append(" (").append(url).append(")")
                } else {
                    sb.append(runText)
                }
            } else {
                sb.append(runText)
            }
        }
        return sb.toString()
    }

    private fun headingLevel(paragraph: XWPFParagraph): Int? {
        val styleId = paragraph.style ?: return null
        val normalized = styleId.lowercase().replace("_20_", " ").replace("_", " ")

        // Match "heading 1" through "heading 6".
        val withSpace = Regex("""^heading\s*(\d)$""").find(normalized)
        if (withSpace != null) {
            val level = withSpace.groupValues[1].toIntOrNull() ?: return null
            if (level in 1..6) return level
        }

        // Custom heading-equivalent styles. Tight allowlist — only the common Word built-ins.
        return when (normalized) {
            "title" -> 1
            "subtitle" -> 2
            "quote", "intensequote" -> 3
            else -> null
        }
    }
}
