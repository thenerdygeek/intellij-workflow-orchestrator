package com.workflow.orchestrator.document.assembler

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.core.model.DocumentIndex

/**
 * Converts a list of [DocumentBlock] values into a Markdown string, enforcing an
 * optional character-budget limit.
 *
 * This is the shared last stage of all document extraction pipelines. Every format-specific
 * pipeline (PDF, Office, Tika XHTML) produces a `List<DocumentBlock>`; this assembler
 * renders that list to the Markdown that ends up in `DocumentContent.markdown`.
 *
 * ## Conversion rules
 *
 * | Block type         | Markdown output                                      |
 * |--------------------|------------------------------------------------------|
 * | `Heading(n, text)` | `n` hash characters + space + text + `\n\n`          |
 * | `Paragraph(text)`  | text + `\n\n`                                        |
 * | `Table(...)`       | Optional caption line, then standard pipe table      |
 * | `PageMarker(n)`    | `<!-- page: n -->\n`                                 |
 * | `EmbeddedFileRef`  | image mime → `[image: alt — path] (mime)`; else `[embedded: name (mime)]` |
 * | `EmbeddedObjectRef`| `[SmartArt: name]` / `[Shape: name]` / `[Embedded object: name]` |
 *
 * ## Truncation policy
 *
 * Blocks are appended in order. The first block whose addition would push the running
 * length **over** `maxChars` is dropped; a truncation marker is appended instead and
 * `truncated = true` is returned. Tables are atomic — a table is never partially
 * serialised. If a single block by itself exceeds `maxChars`, it is still included
 * (dropping it would lose document content entirely) and the truncation marker is
 * appended after it.
 */
class MarkdownAssembler {

    /**
     * Result of [assemble]. Supports 2- or 3-component destructuring:
     * `val (md, truncated) = assemble(...)` and `val (md, truncated, len) = assemble(...)`.
     *
     * @param markdown      The assembled (possibly truncated) Markdown string.
     * @param truncated     True when one or more trailing blocks were dropped.
     * @param contentLength Length of the real content portion before any truncation marker.
     *                      When [truncated] is false this equals [markdown].length.
     *                      When [truncated] is true this is the char position at which the
     *                      dropped block would have started — the correct `offset` for a
     *                      subsequent read_document call to avoid any content gap.
     */
    data class AssemblerResult(
        val markdown: String,
        val truncated: Boolean,
        val contentLength: Int,
    )

    /**
     * Assembles [blocks] into a Markdown string, capped at [maxChars] characters.
     *
     * @param blocks   Ordered list of document blocks to render.
     * @param maxChars Maximum number of characters in the returned Markdown string
     *                 (excluding the truncation marker itself). Must be >= 0.
     * @return [AssemblerResult] with the markdown, truncation flag, and content length
     *         before any truncation marker.
     */
    fun assemble(blocks: List<DocumentBlock>, maxChars: Int): AssemblerResult {
        require(maxChars >= 0) { "maxChars must be >= 0, was $maxChars" }

        if (blocks.isEmpty()) {
            return AssemblerResult("", truncated = false, contentLength = 0)
        }

        val sb = StringBuilder()
        var renderedCount = 0

        for ((index, block) in blocks.withIndex()) {
            val serialized = serializeBlock(block)

            val wouldExceed = sb.length + serialized.length > maxChars
            val isFirstBlock = index == 0

            if (wouldExceed && !isFirstBlock) {
                // Compute the total size as if we had rendered everything.
                val totalIfNoTruncation = computeFullLength(blocks)
                val n = sb.length  // content length before the marker — the gap-free next offset
                val m = totalIfNoTruncation
                val x = renderedCount
                val y = blocks.size
                val droppedFootnotes = blocks.drop(x).count { it is DocumentBlock.Footnote }
                val footnoteClause = if (droppedFootnotes > 0) " ($droppedFootnotes footnotes dropped)" else ""
                sb.append("\n\n*[Document truncated at $n characters of $m total characters; $x of $y blocks rendered$footnoteClause]*\n")
                return AssemblerResult(sb.toString(), truncated = true, contentLength = n)
            }

            sb.append(serialized)
            renderedCount++

            // If this IS the first block and it alone is already over budget, append the
            // marker after it and return truncated=true (edge case: single oversize block).
            if (wouldExceed && isFirstBlock) {
                val totalIfNoTruncation = computeFullLength(blocks)
                val n = sb.length
                val m = totalIfNoTruncation
                // renderedCount is 1 here (the oversize block was just appended).
                val x = renderedCount
                val y = blocks.size
                val droppedFootnotes = blocks.drop(x).count { it is DocumentBlock.Footnote }
                val footnoteClause = if (droppedFootnotes > 0) " ($droppedFootnotes footnotes dropped)" else ""
                sb.append("\n\n*[Document truncated at $n characters of $m total characters; $x of $y blocks rendered$footnoteClause]*\n")
                return AssemblerResult(sb.toString(), truncated = true, contentLength = n)
            }
        }

        return AssemblerResult(sb.toString(), truncated = false, contentLength = sb.length)
    }

    /**
     * Like [assemble] but with NO character cap, additionally returning a [DocumentIndex]
     * of page-marker and heading char offsets. Used to build the persisted artifact: the
     * caller slices this full markdown itself, so truncation must not happen here.
     */
    fun assembleIndexed(blocks: List<DocumentBlock>): IndexedAssemblerResult {
        val sb = StringBuilder()
        val pages = mutableListOf<DocumentIndex.Anchor>()
        val sections = mutableListOf<DocumentIndex.Anchor>()
        for (block in blocks) {
            val offsetBefore = sb.length
            when (block) {
                is DocumentBlock.PageMarker ->
                    pages += DocumentIndex.Anchor(block.pageNumber.toString(), offsetBefore)
                is DocumentBlock.Heading ->
                    sections += DocumentIndex.Anchor(block.text, offsetBefore)
                else -> Unit
            }
            sb.append(serializeBlock(block))
        }
        val markdown = sb.toString()
        return IndexedAssemblerResult(
            markdown = markdown,
            contentLength = markdown.length,
            index = DocumentIndex(pages = pages, sections = sections),
        )
    }

    /** Test-only hook so tests can compute expected offsets without duplicating serialization. */
    internal fun serializeBlockForTest(block: DocumentBlock): String = serializeBlock(block)

    // ── Block serialisation ────────────────────────────────────────────────────

    private fun serializeBlock(block: DocumentBlock): String = when (block) {
        is DocumentBlock.Heading -> serializeHeading(block)
        is DocumentBlock.Paragraph -> serializeParagraph(block)
        is DocumentBlock.CodeBlock -> serializeCodeBlock(block)
        is DocumentBlock.Table -> serializeTable(block)
        is DocumentBlock.PageMarker -> serializePageMarker(block)
        is DocumentBlock.EmbeddedFileRef -> serializeEmbeddedFileRef(block)
        is DocumentBlock.EmbeddedObjectRef -> serializeEmbeddedObjectRef(block)
        is DocumentBlock.Comment -> serializeComment(block)
        is DocumentBlock.ListBlock -> serializeListBlock(block)
        is DocumentBlock.Footnote -> serializeFootnote(block)
        is DocumentBlock.KeyValueGroup -> serializeKeyValueGroup(block)
    }

    private fun serializeHeading(block: DocumentBlock.Heading): String {
        val hashes = "#".repeat(block.level)
        return "$hashes ${block.text}\n\n"
    }

    private fun serializeParagraph(block: DocumentBlock.Paragraph): String {
        return "${block.text}\n\n"
    }

    /**
     * Serialises a [DocumentBlock.CodeBlock] as a fenced Markdown code block (SF-2). Each source
     * line is emitted verbatim on its own output line between ``` fences, so the original wrapping —
     * the meaning of an ABNF grammar / pseudo-code / ASCII diagram — is preserved. A trailing blank
     * line separates the block from following content, matching the other block serializers.
     *
     * Trailing whitespace is stripped per line (it carries no meaning and would otherwise pollute
     * diffs), but leading indentation is preserved since it is significant for code/diagrams.
     */
    private fun serializeCodeBlock(block: DocumentBlock.CodeBlock): String {
        val sb = StringBuilder()
        sb.append("```\n")
        for (line in block.lines) {
            sb.append(line.trimEnd())
            sb.append("\n")
        }
        sb.append("```\n\n")
        return sb.toString()
    }

    private fun serializePageMarker(block: DocumentBlock.PageMarker): String {
        return "<!-- page: ${block.pageNumber} -->\n"
    }

    /**
     * Serialises a [DocumentBlock.EmbeddedFileRef].
     *
     * Marker vocabulary (G-7 reconciliation):
     * - Any block whose [DocumentBlock.EmbeddedFileRef.mimeType] is an image type uses the
     *   `[image: …]` token regardless of whether bytes were extracted to disk. This makes the
     *   corpus probe's `imageMarkers` count (which matches `[image:`) consistent with the body
     *   for every format — HTML `<img>` (path=null) no longer renders `[embedded:]` and so no
     *   longer contradicts the metric (IMG-4).
     * - The `[embedded: …]` token is reserved for genuine NON-image attachments (OLE blobs,
     *   PDF file attachments) that the vision path cannot render.
     *
     * Within the `[image: …]` token:
     * - The marker leads with [DocumentBlock.EmbeddedFileRef.altText] (the figure's
     *   human-authored description) when present, so the LLM sees "Photo of boulders…"
     *   instead of only an opaque temp path (IMG-1).
     * - When the bytes were extracted to disk ([block.path] non-null), the actionable file
     *   path follows so `view_image` can open it: `[image: <alt> — <path>] (<mime>)`.
     * - When there is no on-disk path (HTML `<img>`, oversize skip), the marker carries the
     *   alt-text or filename only: `[image: <alt-or-name>] (<mime>)`.
     */
    private fun serializeEmbeddedFileRef(block: DocumentBlock.EmbeddedFileRef): String {
        val isImage = block.mimeType.startsWith("image/", ignoreCase = true)
        if (!isImage && block.path == null) {
            // Genuine non-image attachment with no extracted bytes — keep the embedded token.
            return "[embedded: ${block.name} (${block.mimeType})]\n\n"
        }
        val alt = block.altText?.trim()?.takeIf { it.isNotEmpty() }
        val inner = when {
            block.path != null && alt != null -> "$alt — ${block.path}"
            block.path != null -> block.path
            alt != null -> alt
            else -> block.name
        }
        return "[image: $inner] (${block.mimeType})\n\n"
    }

    /**
     * Serialises a [DocumentBlock.EmbeddedObjectRef] placeholder (IMG-3). The bytes are not
     * viewable, so the marker only records the object's presence and identity:
     * `[SmartArt: <name>]`, `[Shape: <name>]`, `[Embedded object: <name>]`. A blank name
     * degrades to the bare label (e.g. `[Embedded object]`).
     */
    private fun serializeEmbeddedObjectRef(block: DocumentBlock.EmbeddedObjectRef): String {
        val label = when (block.kind) {
            DocumentBlock.EmbeddedObjectRef.Kind.SMARTART -> "SmartArt"
            DocumentBlock.EmbeddedObjectRef.Kind.SHAPE -> "Shape"
            DocumentBlock.EmbeddedObjectRef.Kind.OLE -> "Embedded object"
        }
        val name = block.name?.trim()?.takeIf { it.isNotEmpty() }
        return if (name != null) "[$label: $name]\n\n" else "[$label]\n\n"
    }

    /**
     * Serialises a [DocumentBlock.Comment] as a Markdown blockquote. The shape varies
     * by kind so the LLM can distinguish review comments from tracked-change suggestions.
     *
     * Multi-line text is rendered with `> ` on every continuation line so the entire
     * comment renders as one quoted block in standard Markdown viewers.
     */
    private fun serializeComment(block: DocumentBlock.Comment): String {
        val author = block.author ?: "Anonymous"
        val header = when (block.kind) {
            DocumentBlock.Comment.Kind.REVIEW -> {
                val anchor = block.anchorText?.let { " (anchor: \"$it\")" } ?: ""
                "**Comment by $author**$anchor"
            }
            DocumentBlock.Comment.Kind.TRACKED_INSERTION ->
                "**$author proposes inserting**"
            DocumentBlock.Comment.Kind.TRACKED_DELETION ->
                "**$author proposes deleting**: \"${block.anchorText ?: ""}\""
            DocumentBlock.Comment.Kind.PDF_ANNOTATION -> {
                val anchor = block.anchorText?.let { " (on: \"$it\")" } ?: ""
                "**PDF annotation**$anchor"
            }
        }
        val body = block.text.lineSequence().joinToString("\n") { "> $it" }
        return if (block.text.isBlank()) "> $header\n\n" else "> $header:\n$body\n\n"
    }

    /**
     * Serialises a [DocumentBlock.ListBlock] as a bulleted or numbered Markdown list.
     *
     * Empty `items` produces an empty string — callers that build empty lists get a
     * silent no-op rather than a stray `\n\n`.
     */
    private fun serializeListBlock(block: DocumentBlock.ListBlock): String {
        if (block.items.isEmpty()) return ""
        val sb = StringBuilder()
        for ((i, item) in block.items.withIndex()) {
            val marker = if (block.ordered) "${i + 1}. " else "- "
            sb.append(marker)
            sb.append(item)
            sb.append("\n")
        }
        sb.append("\n")
        return sb.toString()
    }

    /**
     * Serialises a [DocumentBlock.Footnote] in GitHub Flavored Markdown footnote
     * syntax: `[^marker]: text`. Single trailing newline so consecutive Footnote
     * blocks compact into a contiguous final block (per the extractor-emit-last
     * contract).
     */
    private fun serializeFootnote(block: DocumentBlock.Footnote): String {
        return "[^${block.marker}]: ${block.text}\n"
    }

    /**
     * Serialises a [DocumentBlock.KeyValueGroup] as a bold-titled section followed by
     * a flat dash-list of `key: value` pairs.
     */
    private fun serializeKeyValueGroup(block: DocumentBlock.KeyValueGroup): String {
        val sb = StringBuilder()
        sb.append("**").append(block.title).append("**\n")
        for ((k, v) in block.pairs) {
            sb.append("- ").append(k).append(": ").append(v).append("\n")
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun serializeTable(block: DocumentBlock.Table): String {
        // An empty table (no headers and no rows) produces no output.
        if (block.headers.isEmpty() && block.rows.isEmpty()) return ""

        val sb = StringBuilder()

        // Optional caption.
        if (block.caption != null) {
            sb.append("**${block.caption}**\n\n")
        }

        // Compute column widths for human-readable alignment.
        val colCount = block.headers.size
        val colWidths = IntArray(colCount) { col ->
            val headerWidth = escapeCellContent(block.headers[col]).length
            val maxDataWidth = block.rows.maxOfOrNull { row ->
                escapeCellContent(row[col]).length
            } ?: 0
            maxOf(headerWidth, maxDataWidth, 3) // minimum 3 to keep separator recognisable
        }

        // Header row.
        sb.append(buildRow(block.headers, colWidths))

        // Separator row: | --- | --- | ...
        sb.append("|")
        for (col in 0 until colCount) {
            sb.append(" ")
            sb.append("-".repeat(colWidths[col]))
            sb.append(" |")
        }
        sb.append("\n")

        // Data rows.
        for (row in block.rows) {
            sb.append(buildRow(row, colWidths))
        }

        sb.append("\n")
        return sb.toString()
    }

    /**
     * Builds a single pipe-table row from [cells], padding each cell to [colWidths].
     */
    private fun buildRow(cells: List<String>, colWidths: IntArray): String {
        val sb = StringBuilder("|")
        for ((col, cell) in cells.withIndex()) {
            val escaped = escapeCellContent(cell)
            sb.append(" ")
            sb.append(escaped)
            // Pad to column width.
            val padding = colWidths[col] - escaped.length
            if (padding > 0) sb.append(" ".repeat(padding))
            sb.append(" |")
        }
        sb.append("\n")
        return sb.toString()
    }

    /**
     * Escapes cell content for Markdown pipe-table embedding.
     *
     * Order matters:
     * 1. Escape backslashes first (`\` → `\\`) to avoid double-escaping the
     *    escape character we are about to introduce.
     * 2. Escape pipe characters (`|` → `\|`) using the backslash introduced in
     *    step 1 — if we did this in reverse, the `\` from pipe-escaping would
     *    itself be escaped in step 1, producing `\\|` instead of `\|`.
     * 3. Replace embedded newlines with `<br>`.
     */
    private fun escapeCellContent(cell: String): String {
        return cell
            .replace("\\", "\\\\")   // step 1: backslash → double-backslash
            .replace("|", "\\|")      // step 2: pipe → \|
            .replace("\n", "<br>")    // step 3: newline → <br>
            .replace("\r\n", "<br>")  // normalise Windows line endings too
            .replace("\r", "<br>")    // normalise old-Mac line endings too
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Computes the total serialised length of ALL blocks with no truncation.
     * Used only to populate the `M` value in the truncation marker.
     */
    private fun computeFullLength(blocks: List<DocumentBlock>): Int {
        return blocks.sumOf { serializeBlock(it).length }
    }
}

data class IndexedAssemblerResult(
    val markdown: String,
    val contentLength: Int,
    val index: DocumentIndex,
)
