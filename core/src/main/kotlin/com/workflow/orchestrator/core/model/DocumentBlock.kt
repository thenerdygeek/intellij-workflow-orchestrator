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
     * A reference to a file embedded inside the document (e.g. an image, attachment, or
     * OLE object). In v1, embedded files are not extracted; this block acts as a placeholder
     * so callers know that content exists but was not surfaced.
     *
     * @param name      Display name or filename of the embedded file.
     * @param mimeType  MIME type as detected by the extraction pipeline (e.g. `image/png`).
     */
    data class EmbeddedFileRef(val name: String, val mimeType: String) : DocumentBlock()
}
