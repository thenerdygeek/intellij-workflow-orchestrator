package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.normaliseRow
import org.apache.poi.xwpf.usermodel.IBodyElement
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.InputStream

/**
 * Extracts content from DOCX files into [DocumentBlock] lists via Apache POI XWPF.
 *
 * Walks `XWPFDocument.bodyElements` in document order, preserving the interleaving of
 * paragraphs and tables. This is the key accuracy advantage over Tika's `BodyContentHandler`,
 * which separates prose from tables and loses positional context.
 *
 * ## Heading detection
 *
 * Paragraph styles are matched by the string prefix `"Heading "`. POI exposes the style ID
 * (e.g. `"Heading1"`, `"Heading 1"`) rather than the display name, and style IDs vary across
 * Word installations. The implementation checks both `paragraph.style` (the style ID) and the
 * numeric suffix (`paragraph.numID` is irrelevant here — we parse the style string directly).
 *
 * ## Thread safety
 *
 * Per-call instantiation only. `XWPFDocument` is NOT thread-safe. Never cache.
 *
 * ## Table row/cell handling
 *
 * First row of each table → headers. If a subsequent row has fewer cells than the header
 * count, the row is padded with empty strings. If it has more, it is truncated to header
 * count. This matches the behaviour of [XlsxTableExtractor] and satisfies the
 * [DocumentBlock.Table] invariant.
 */
class DocxTableExtractor {

    init {
        PoiHardening.applyOnce()
    }

    /**
     * Extracts [DocumentBlock] values from a DOCX [stream] in document order.
     *
     * @param stream Raw bytes of the `.docx` file. The caller is responsible for closing the stream.
     * @return Ordered list of blocks: headings, paragraphs, and tables in the order they appear
     *         in the document body.
     */
    fun extract(stream: InputStream): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()

        XWPFDocument(stream).use { doc ->
            for (element: IBodyElement in doc.bodyElements) {
                when (element) {
                    is XWPFParagraph -> {
                        val block = paragraphToBlock(element) ?: continue
                        blocks += block
                    }
                    is XWPFTable -> {
                        val block = tableToBlock(element) ?: continue
                        blocks += block
                    }
                    // SDT (Structured Document Tag), drawing canvases, etc. — skip.
                    else -> continue
                }
            }
        }

        return blocks
    }

    // ── Paragraph conversion ───────────────────────────────────────────────────

    private fun paragraphToBlock(paragraph: XWPFParagraph): DocumentBlock? {
        val text = paragraph.text.trim()
        if (text.isEmpty()) return null

        val level = headingLevel(paragraph)
        return if (level != null) {
            DocumentBlock.Heading(level, text)
        } else {
            DocumentBlock.Paragraph(text)
        }
    }

    /**
     * Returns the heading level (1–6) if [paragraph] has a heading style, or `null` otherwise.
     *
     * POI stores the style as the style ID (e.g. `"Heading1"`, `"Heading 1"`, `"heading1"`).
     * Word-generated DOCX files use `"Heading1"` through `"Heading6"`. LibreOffice-generated
     * files may use `"Heading_20_1"` (URL-encoded space). We handle the common forms.
     */
    private fun headingLevel(paragraph: XWPFParagraph): Int? {
        val styleId = paragraph.style ?: return null
        val normalized = styleId.lowercase().replace("_20_", " ").replace("_", " ")

        // Match "heading 1" through "heading 6", or "heading1" through "heading6".
        val withSpace = Regex("""^heading\s*(\d)$""").find(normalized)
        if (withSpace != null) {
            val level = withSpace.groupValues[1].toIntOrNull() ?: return null
            if (level in 1..6) return level
        }

        return null
    }

    // ── Table conversion ───────────────────────────────────────────────────────

    private fun tableToBlock(table: XWPFTable): DocumentBlock? {
        val tableRows = table.rows
        if (tableRows.isEmpty()) return null

        val headerCells = tableRows[0].tableCells
        val headers = headerCells.map { it.text.trim() }
        if (headers.isEmpty()) return null

        val dataRows = tableRows.drop(1).map { row ->
            normaliseRow(row.tableCells.map { it.text.trim() }, headers.size)
        }

        return DocumentBlock.Table(headers, dataRows)
    }
}
