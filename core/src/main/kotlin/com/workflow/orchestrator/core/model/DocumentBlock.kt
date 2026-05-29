package com.workflow.orchestrator.core.model

/**
 * Typed intermediate representation of a single structural unit within a document.
 *
 * Implementations of [com.workflow.orchestrator.core.api.DocumentExtractor] produce a
 * `List<DocumentBlock>` as the intermediate output of each pipeline. The
 * `MarkdownAssembler` in `:document` then converts this list to a Markdown string that
 * is placed in [DocumentContent.markdown].
 *
 * Using a sealed hierarchy rather than a flat string allows:
 * - Callers that want structured access (e.g. to count tables or locate headings) to
 *   pattern-match without re-parsing the Markdown.
 * - The assembler to apply per-type formatting rules deterministically.
 * - `init` blocks to catch malformed blocks at construction time rather than at output
 *   assembly time, where the source of the bug would be harder to trace.
 *
 * @see DocumentContent
 */
sealed class DocumentBlock {

    /**
     * A heading in the document hierarchy, corresponding to Markdown `#`–`######`.
     *
     * @param level  Heading depth in the range 1–6 (inclusive). Level 1 is the top-level title.
     * @param text   Plain text content of the heading (no Markdown markup).
     */
    data class Heading(val level: Int, val text: String) : DocumentBlock() {
        init {
            require(level in 1..6) { "Heading level must be in 1..6, was $level" }
        }
    }

    /**
     * A paragraph of prose text.
     *
     * @param text  Plain text content. May contain inline formatting characters (e.g. `*bold*`)
     *              if the source document preserved them, but no structural Markdown.
     */
    data class Paragraph(val text: String) : DocumentBlock()

    /**
     * A tabular structure with named columns and zero or more data rows.
     *
     * Every row in [rows] must contain exactly [headers].size cells; the `init` block
     * enforces this invariant eagerly so callers get a clear error at construction time.
     *
     * @param headers  Column header labels. Must be non-empty if [rows] is non-empty.
     * @param rows     Data rows; each inner list has exactly [headers].size elements.
     * @param caption  Optional caption string (e.g. "Table 1: System requirements").
     */
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val caption: String? = null,
    ) : DocumentBlock() {
        init {
            require(rows.all { it.size == headers.size }) {
                "all rows must have headers.size (${headers.size}) cells; " +
                    "found a row with ${rows.firstOrNull { it.size != headers.size }?.size} cells"
            }
        }
    }

    /**
     * A logical page boundary within the document, emitted by PDF and paginated-PPTX pipelines.
     *
     * Page markers are serialised as HTML comments (`<!-- page: N -->`) in Markdown so they
     * are invisible when rendered but remain searchable. They are useful for citation
     * references (e.g. "§3.2, page 12") in v2.
     *
     * @param pageNumber  1-based page number. Must be greater than zero.
     */
    data class PageMarker(val pageNumber: Int) : DocumentBlock() {
        init {
            require(pageNumber > 0) { "pageNumber must be > 0, was $pageNumber" }
        }
    }

    /**
     * A reference to a file embedded inside the document (image, attachment, OLE object).
     *
     * @param name      Display name or filename of the embedded file.
     * @param mimeType  MIME type as detected by the extraction pipeline (e.g. `image/png`).
     * @param path      Absolute path under `{sessionDir}/downloads/document-{sha6}/` when
     *                  the bytes were extracted to disk by `ImageExtractionService`
     *                  (Phase 2+). Null when bytes were not extracted (size cap exceeded,
     *                  HTML `<img src=…>` references with no fetchable body, etc.).
     * @param altText   Machine-readable figure description carried by the source format
     *                  (DOCX `wp:docPr`/`title|descr`, PPTX `p:cNvPr`/`title|descr`, HTML
     *                  `<img alt>`). Null when the source provided no description. The
     *                  assembler leads the image marker with this text so the LLM has the
     *                  only human-authored caption the figure carries (G-7 / IMG-1).
     */
    data class EmbeddedFileRef(
        val name: String,
        val mimeType: String,
        val path: String? = null,
        val altText: String? = null,
    ) : DocumentBlock()

    /**
     * A reference to a non-rasterised embedded object whose bytes are NOT extracted to a
     * viewable image: SmartArt diagrams, drawing shapes (text boxes, callouts), and OLE /
     * embedded objects (a linked spreadsheet, a `PowerPoint.Slide` object, …).
     *
     * These previously vanished from the output entirely — a slide dominated by a SmartArt
     * diagram or an OLE object rendered near-empty (G-7 / IMG-3). Emitting a placeholder
     * marker keeps the object's *presence* visible to the LLM even though its pixels are not
     * available, e.g. `[SmartArt: AlternatingHexagons]`, `[Shape: Direct Access Storage 1]`,
     * `[Embedded object: PowerPoint.Slide.8]`.
     *
     * SmartArt *text* (when present) is still emitted separately as a [ListBlock] by
     * `SmartArtExtractor`; this block marks the diagram's position/identity, not its text.
     *
     * @param kind  Object category, drives the marker label prefix.
     * @param name  Best available human-readable identifier: the shape/diagram name
     *              (`docPr`/`cNvPr` name), the OLE `progId`, or a layout/category name.
     *              Blank names degrade to a generic marker (e.g. `[Embedded object]`).
     */
    data class EmbeddedObjectRef(
        val kind: Kind,
        val name: String?,
    ) : DocumentBlock() {
        enum class Kind { SMARTART, SHAPE, OLE }
    }

    /**
     * A review comment, tracked change, or PDF annotation. Emitted by extractors that
     * walk the document's comment/annotation channels in addition to the body.
     *
     * Ordering is the extractor's responsibility: a comment block appears immediately
     * after the paragraph/cell/slide it anchors to.
     *
     * @param author     Display name of the comment author. Null for PDF annotations
     *                   that lack a popup-title field.
     * @param anchorText First ~60 chars of the text the comment anchors to. Null when
     *                   the comment is doc-/slide-/sheet-level rather than text-anchored.
     * @param text       Plain-text body of the comment.
     * @param kind       Distinguishes review comments from tracked-change suggestions
     *                   and PDF annotations.
     */
    data class Comment(
        val author: String?,
        val anchorText: String?,
        val text: String,
        val kind: Kind,
    ) : DocumentBlock() {
        enum class Kind { REVIEW, TRACKED_INSERTION, TRACKED_DELETION, PDF_ANNOTATION }
    }

    /**
     * A flat single-level list. Bulleted (`ordered = false`) or numbered (`ordered = true`).
     *
     * Nesting is NOT a model concept: nested items are encoded in-string with leading
     * `  ` indents inside the item text. This keeps the model flat and makes
     * `MarkdownAssembler` serialization straightforward.
     *
     * @param ordered True for numbered (1. 2. 3.), false for bulleted (- - -).
     * @param items   Item text in document order. Non-empty for a valid list block.
     */
    data class ListBlock(
        val ordered: Boolean,
        val items: List<String>,
    ) : DocumentBlock()

    /**
     * A footnote or endnote. Extractors MUST emit all footnote blocks at the END of
     * their returned `List<DocumentBlock>` so they serialize as a contiguous final
     * Markdown block. The `MarkdownAssembler` does NOT reorder blocks — ordering is
     * the extractor's responsibility.
     *
     * @param marker Display marker (e.g. "1", "2", "a", "*").
     * @param text   Plain-text body of the footnote.
     */
    data class Footnote(
        val marker: String,
        val text: String,
    ) : DocumentBlock()

    /**
     * A titled list of key-value pairs. Used for flat metadata: PDF bookmarks, AcroForm
     * fields, document properties, XLSX defined names.
     *
     * Order is preserved as given. Empty `pairs` is allowed (renders as a title with
     * no body) but uncommon.
     *
     * @param title Section heading rendered above the pairs.
     * @param pairs Key-value pairs in display order. Values are plain text.
     */
    data class KeyValueGroup(
        val title: String,
        val pairs: List<Pair<String, String>>,
    ) : DocumentBlock()
}
