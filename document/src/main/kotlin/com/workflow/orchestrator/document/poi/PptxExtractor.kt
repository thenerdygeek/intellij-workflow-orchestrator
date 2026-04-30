package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTable
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xslf.usermodel.XMLSlideShow
import java.io.InputStream

/**
 * Extracts slide content from PPTX files into [DocumentBlock] lists via Apache POI XSLF.
 *
 * For each slide, the extractor emits:
 * 1. A [DocumentBlock.Heading] (`level=2`) carrying the slide number and title.
 * 2. [DocumentBlock.Paragraph] blocks for each non-title text frame on the slide.
 * 3. A [DocumentBlock.Table] for each [XSLFTable] on the slide.
 * 4. A speaker-notes [DocumentBlock.Paragraph] (prefixed `> Notes: …`) when the slide
 *    has non-empty speaker notes.
 *
 * ## Thread safety
 *
 * Per-call instantiation only. `XMLSlideShow` is NOT thread-safe. Never cache.
 *
 * ## Title extraction
 *
 * `XSLFSlide.title` returns the text of the first title-placeholder shape, or `null`.
 * When `null` or blank the heading is `"Slide N"` (no colon suffix).
 *
 * ## Speaker notes
 *
 * `XSLFSlide.notes` returns the `XSLFNotes` object. Notes have two placeholders: index 0
 * is the slide-number placeholder (a small numeric string), index 1 is the body. We skip
 * any placeholder whose text is numeric-only (slide-number placeholder) and emit the rest.
 */
class PptxExtractor {

    init {
        PoiHardening.applyOnce()
    }

    /**
     * Extracts [DocumentBlock] values from a PPTX [stream], one set of blocks per slide.
     *
     * @param stream Raw bytes of the `.pptx` file. The caller is responsible for closing the stream.
     * @return Ordered list of blocks in slide order. Within each slide, content appears in
     *         shape-index order followed by speaker notes.
     */
    fun extract(stream: InputStream): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()

        XMLSlideShow(stream).use { presentation ->
            presentation.slides.forEachIndexed { idx, slide ->
                val slideNumber = idx + 1
                blocks += slideToBlocks(slide, slideNumber)
            }
        }

        return blocks
    }

    // ── Per-slide conversion ───────────────────────────────────────────────────

    private fun slideToBlocks(slide: XSLFSlide, slideNumber: Int): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()

        // Slide heading
        val title = slide.title?.trim()
        val headingText = if (title.isNullOrEmpty()) "Slide $slideNumber" else "Slide $slideNumber: $title"
        blocks += DocumentBlock.Heading(2, headingText)

        // Shapes — skip the title shape itself to avoid duplicate text
        val titleShape = slide.shapes.firstOrNull { it is XSLFTextShape && isTitlePlaceholder(it) }

        for (shape: XSLFShape in slide.shapes) {
            if (shape === titleShape) continue

            when (shape) {
                is XSLFTable -> {
                    val tableBlock = tableToBlock(shape) ?: continue
                    blocks += tableBlock
                }
                is XSLFTextShape -> {
                    val text = shape.text?.trim() ?: continue
                    if (text.isNotEmpty()) {
                        blocks += DocumentBlock.Paragraph(text)
                    }
                }
            }
        }

        // Speaker notes
        val notesBlock = extractNotes(slide)
        if (notesBlock != null) {
            blocks += notesBlock
        }

        return blocks
    }

    // ── Title placeholder detection ────────────────────────────────────────────

    /**
     * Returns `true` if [shape] is a title-type placeholder.
     *
     * PPTX placeholder types for titles include TITLE (15) and CENTER_TITLE (3).
     * POI exposes `XSLFTextShape.textType` for this purpose.
     */
    private fun isTitlePlaceholder(shape: XSLFTextShape): Boolean {
        return try {
            val placeholder = shape.textType ?: return false
            placeholder.name.contains("TITLE", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    // ── Table conversion ───────────────────────────────────────────────────────

    private fun tableToBlock(table: XSLFTable): DocumentBlock? {
        val rowCount = table.numberOfRows
        if (rowCount == 0) return null

        val colCount = table.numberOfColumns
        if (colCount == 0) return null

        // First row → headers
        val headers = (0 until colCount).map { col ->
            table.getCell(0, col)?.text?.trim() ?: ""
        }

        if (headers.all { it.isEmpty() }) return null

        // Subsequent rows → data rows
        val dataRows = (1 until rowCount).map { row ->
            (0 until colCount).map { col ->
                table.getCell(row, col)?.text?.trim() ?: ""
            }
        }

        return DocumentBlock.Table(headers, dataRows)
    }

    // ── Speaker notes extraction ───────────────────────────────────────────────

    /**
     * Extracts speaker notes from [slide], returning a [DocumentBlock.Paragraph] prefixed
     * with `"> Notes: "`, or `null` if the slide has no non-empty notes.
     *
     * The notes slide has two placeholders:
     * - Index 0: the slide-number placeholder (usually a small digit string).
     * - Index 1+: the actual note body text.
     *
     * We skip any placeholder whose text consists solely of digits (the slide-number
     * placeholder). We also skip blank placeholders.
     */
    private fun extractNotes(slide: XSLFSlide): DocumentBlock.Paragraph? {
        val notes = try { slide.notes } catch (_: Exception) { return null } ?: return null

        val notesText = notes.placeholders
            .filter { placeholder ->
                val text = placeholder.text?.trim() ?: return@filter false
                text.isNotEmpty() && !text.all { it.isDigit() }
            }
            .joinToString("\n") { it.text?.trim() ?: "" }
            .trim()

        if (notesText.isEmpty()) return null
        return DocumentBlock.Paragraph("> Notes: $notesText")
    }
}
