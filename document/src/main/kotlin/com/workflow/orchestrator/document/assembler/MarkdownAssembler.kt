package com.workflow.orchestrator.document.assembler

import com.workflow.orchestrator.core.model.DocumentBlock

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
 * | `EmbeddedFileRef`  | `[embedded: name (mime)]\n\n`                        |
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
     * Assembles [blocks] into a Markdown string, capped at [maxChars] characters.
     *
     * @param blocks   Ordered list of document blocks to render.
     * @param maxChars Maximum number of characters in the returned Markdown string
     *                 (excluding the truncation marker itself). Must be >= 0.
     * @return A pair of `(markdown, truncated)`. `truncated` is `true` when one or more
     *         trailing blocks were omitted because the budget was exhausted.
     */
    fun assemble(blocks: List<DocumentBlock>, maxChars: Int): Pair<String, Boolean> {
        require(maxChars >= 0) { "maxChars must be >= 0, was $maxChars" }

        if (blocks.isEmpty()) {
            return Pair("", false)
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
                val n = sb.length
                val m = totalIfNoTruncation
                val x = renderedCount
                val y = blocks.size
                sb.append("\n\n*[Document truncated at $n characters of $m total characters; $x of $y blocks rendered]*\n")
                return Pair(sb.toString(), true)
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
                sb.append("\n\n*[Document truncated at $n characters of $m total characters; $x of $y blocks rendered]*\n")
                return Pair(sb.toString(), true)
            }
        }

        return Pair(sb.toString(), false)
    }

    // ── Block serialisation ────────────────────────────────────────────────────

    private fun serializeBlock(block: DocumentBlock): String = when (block) {
        is DocumentBlock.Heading -> serializeHeading(block)
        is DocumentBlock.Paragraph -> serializeParagraph(block)
        is DocumentBlock.Table -> serializeTable(block)
        is DocumentBlock.PageMarker -> serializePageMarker(block)
        is DocumentBlock.EmbeddedFileRef -> serializeEmbeddedFileRef(block)
        is DocumentBlock.Comment -> serializeComment(block)
        is DocumentBlock.ListBlock -> serializeListBlock(block)
        else -> error("variant ${block::class.simpleName} not yet serialized")  // KEEP — replaced in Tasks 8/9/10
    }

    private fun serializeHeading(block: DocumentBlock.Heading): String {
        val hashes = "#".repeat(block.level)
        return "$hashes ${block.text}\n\n"
    }

    private fun serializeParagraph(block: DocumentBlock.Paragraph): String {
        return "${block.text}\n\n"
    }

    private fun serializePageMarker(block: DocumentBlock.PageMarker): String {
        return "<!-- page: ${block.pageNumber} -->\n"
    }

    private fun serializeEmbeddedFileRef(block: DocumentBlock.EmbeddedFileRef): String {
        return "[embedded: ${block.name} (${block.mimeType})]\n\n"
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
