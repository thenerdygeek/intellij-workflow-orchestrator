package com.workflow.orchestrator.document.sax

import com.workflow.orchestrator.core.model.DocumentBlock
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * SAX [DefaultHandler] that consumes Tika's XHTML output and accumulates a
 * [List<DocumentBlock>] from the event stream.
 *
 * Tika's [org.apache.tika.sax.XHTMLContentHandler] emits well-formed XHTML:
 * headings become `<h1>`–`<h6>`, body text becomes `<p>`, tables become
 * `<table><tr><td>/<th>`, and PDF page boundaries become
 * `<div class="page">`.
 *
 * ## Element handling
 *
 * - `<h1>`–`<h6>` start: flush any pending text as [DocumentBlock.Paragraph];
 *   switch to heading-collection mode.
 * - `</h1>`–`</h6>` end: emit [DocumentBlock.Heading] for the collected text;
 *   reset buffer.
 * - `<p>` start: flush any non-empty buffer as [DocumentBlock.Paragraph]
 *   (handles paragraphs that Tika opens without a prior close).
 * - `</p>` end: emit [DocumentBlock.Paragraph] for the collected text if
 *   non-empty; reset buffer.
 * - `<table>` start: enter table-collection state; reset row/cell buffers.
 * - `<tr>` end: commit [currentRow] to [tableRows]; reset cell buffer.
 * - `<td>`/`<th>` end: append trimmed [currentCell] to [currentRow]; reset cell.
 * - `<table>` end: emit [DocumentBlock.Table]; exit table state.
 * - `<div class="page">` start: emit [DocumentBlock.PageMarker].
 * - `characters()`: route text to the active buffer (cell, heading, or body).
 * - `endDocument()`: flush any remaining buffer as [DocumentBlock.Paragraph].
 *
 * ## Defensive design
 *
 * - Nested tables: a `<table>` open resets state; inner tables are flattened
 *   into the outer table's cell text — acceptable for Phase 3.
 * - Malformed / unclosed elements: unknown elements pass through; their
 *   character content goes into the current buffer. No assertions are thrown.
 * - Row-width mismatch: data rows are padded with empty strings or truncated to
 *   match the first-row header count before emitting the [DocumentBlock.Table].
 *
 * ## CSV/TSV detection
 *
 * Tika's `TextAndCSVParser` emits CSV file content as a single `<p>` element
 * rather than `<table>`. The CSV-to-Table heuristic in [tryParseAsTable] only
 * fires when [csvDetectionEnabled] is `true`, set by the pipeline only when
 * the source MIME is `text/csv` or `text/tab-separated-values`. Without this
 * gate, prose paragraphs containing commas (extremely common) would be
 * misclassified as tables.
 *
 * @param csvDetectionEnabled Set to `true` only when the source MIME is CSV/TSV.
 */
class DocumentBlockHandler(private val csvDetectionEnabled: Boolean = false) : DefaultHandler() {

    private val _blocks = mutableListOf<DocumentBlock>()

    /** Completed blocks accumulated during the parse. */
    val blocks: List<DocumentBlock> get() = _blocks

    // ── Shared text buffer ────────────────────────────────────────────────────

    /** Buffer for the current element's character content (heading or paragraph body). */
    private val currentBuffer = StringBuilder()

    // ── Heading state ──────────────────────────────────────────────────────────

    /** Non-zero while inside an `<h1>`–`<h6>` element. */
    private var headingLevel: Int = 0

    // ── Table state ───────────────────────────────────────────────────────────

    /** True while inside a `<table>` element. */
    private var inTable: Boolean = false

    /** True while inside a `<td>` or `<th>` element. */
    private var inCell: Boolean = false

    /** Accumulates text content for the current cell. */
    private val currentCell = StringBuilder()

    /** Cells collected so far for the row currently being built. */
    private val currentRow = mutableListOf<String>()

    /**
     * All rows collected since `<table>` was opened (including the header row
     * when it comes from `<th>` elements inside a `<thead><tr>`).
     */
    private val tableRows = mutableListOf<List<String>>()

    // ── Page-marker state ─────────────────────────────────────────────────────

    /** 1-based page counter; incremented on each `<div class="page">` opening. */
    private var pageNumber: Int = 0

    // ── SAX callbacks ─────────────────────────────────────────────────────────

    override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes?) {
        val name = (localName ?: qName ?: "").lowercase()

        when {
            name.isHeading() -> {
                // Flush any pending body text before starting the heading.
                flushBufferAsParagraph()
                headingLevel = name[1].digitToInt()
                currentBuffer.clear()
            }

            name == "p" -> {
                // Tika sometimes emits `<p>` without closing the previous one — flush first.
                flushBufferAsParagraph()
                currentBuffer.clear()
            }

            name == "table" -> {
                // Flush any pending body text before entering the table.
                flushBufferAsParagraph()
                inTable = true
                inCell = false
                currentCell.clear()
                currentRow.clear()
                tableRows.clear()
            }

            name == "td" || name == "th" -> {
                if (inTable) {
                    inCell = true
                    currentCell.clear()
                }
            }

            name == "div" -> {
                // Tika's PDF parser emits `<div class="page">` on each page boundary.
                val clazz = attrs?.getValue("class") ?: ""
                if (clazz == "page") {
                    pageNumber++
                    _blocks += DocumentBlock.PageMarker(pageNumber)
                }
            }
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = (localName ?: qName ?: "").lowercase()

        when {
            name.isHeading() -> {
                val text = currentBuffer.toString().trim()
                if (text.isNotEmpty()) {
                    _blocks += DocumentBlock.Heading(headingLevel, text)
                }
                headingLevel = 0
                currentBuffer.clear()
            }

            name == "p" -> {
                // Route through flushBufferAsParagraph so CSV/TSV text paragraphs
                // are detected and converted to Table blocks (Tika's TextAndCSVParser
                // emits CSV content as a single <p> element, not <table>).
                flushBufferAsParagraph()
            }

            name == "td" || name == "th" -> {
                if (inTable && inCell) {
                    currentRow += currentCell.toString().trim()
                    currentCell.clear()
                    inCell = false
                }
            }

            name == "tr" -> {
                if (inTable && currentRow.isNotEmpty()) {
                    tableRows += currentRow.toList()
                    currentRow.clear()
                }
            }

            name == "table" -> {
                if (inTable) {
                    emitTable()
                    inTable = false
                    inCell = false
                    currentCell.clear()
                    currentRow.clear()
                    tableRows.clear()
                    }
            }
        }
    }

    override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (ch == null) return
        val text = String(ch, start, length)
        when {
            inTable && inCell -> currentCell.append(text)
            headingLevel > 0 -> currentBuffer.append(text)
            !inTable -> currentBuffer.append(text)
        }
    }

    override fun endDocument() {
        // Flush any remaining text that was not closed by a `</p>`.
        flushBufferAsParagraph()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Emits either a [DocumentBlock.Paragraph] or [DocumentBlock.Table] from [currentBuffer].
     *
     * If the buffer contains multi-line comma- or tab-separated text that looks like a CSV or
     * TSV table (all rows have the same cell count ≥ 2, at least one data row beyond headers),
     * it is converted to a [DocumentBlock.Table]. This handles Tika's `TextAndCSVParser`
     * output, which emits CSV file content as a single `<p>` block rather than XHTML `<table>`.
     */
    private fun flushBufferAsParagraph() {
        val rawText = currentBuffer.toString().trim()
        currentBuffer.clear()
        if (rawText.isEmpty()) return
        // Tika's PDFParser emits unset XMP metadata fields (Author, Title, …) as
        // body text containing literal placeholders like "(anonymous)". Drop these
        // before they reach the LLM as bogus paragraph content.
        if (rawText.lowercase() in METADATA_LEAK_LINES) return

        // Restore lost whitespace around hyperlinks. Tika's PDFParser does not insert
        // spaces around link annotations when the underlying PDF text stream lacks them
        // — generators that draw the link as a separate text object typically omit the
        // surrounding space. Catches "found at:https://" and chained-URL run-ons.
        val urlFixed = if ("http" in rawText) {
            rawText.replace(URL_BOUNDARY) { match -> "${match.groupValues[1]} ${match.groupValues[2]}" }
        } else {
            rawText
        }

        // Normalise leading bullet glyphs at start of each line into Markdown "- ".
        // Only fires for PDFs whose generators emit bullet characters in the text stream;
        // PDFs that draw bullets as vector graphics (e.g. NIST CSF) keep no glyph in the
        // extracted text and are unaffected — that case is a documented v1.x limitation.
        val text = normaliseBulletMarkers(urlFixed)

        // Tika's PDFParser does not emit <h1>…<h6> for PDFs from generators that lack
        // semantic heading info (the common case for reportlab / LaTeX / many spec docs).
        // Section headings end up glued to the following body inside one <p>. Split
        // numbered-section paragraphs into Heading + Paragraph here so the structure
        // survives. The pattern is conservative — false positives need (a) a leading
        // section number, (b) a Title-Cased word, AND (c) a CapitalLowercase boundary.
        if (tryEmitNumberedHeadingSplit(text)) return

        // Only attempt CSV/TSV detection when the pipeline has confirmed via MIME
        // that the source is delimited text. Otherwise prose with commas would be
        // misclassified as tables (phantom-table failure mode per plan-review Q2).
        val tableBlock = if (csvDetectionEnabled) tryParseAsTable(text) else null
        if (tableBlock != null) {
            _blocks += tableBlock
        } else {
            _blocks += DocumentBlock.Paragraph(text)
        }
    }

    /**
     * Rewrites lines that begin with a Unicode bullet glyph into Markdown "- " syntax.
     *
     * Operates per-line: each line is `trimStart`-tested against [BULLET_PREFIX]; on match
     * the glyph + trailing whitespace is replaced with `- `. Unmatched lines are returned
     * verbatim. Numbered list prefixes ("1.", "2)", …) are already valid Markdown so we
     * leave them alone.
     */
    private fun normaliseBulletMarkers(text: String): String {
        if (BULLET_GLYPH_QUICK_CHECK.none { it in text }) return text
        return text.lines().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            val rewritten = BULLET_PREFIX.replaceFirst(trimmed, "- ")
            if (rewritten == trimmed) line else rewritten
        }
    }

    /**
     * Attempts to split [text] as `"<section-number> <Heading title><Body…>"`.
     *
     * Match requires:
     * - A leading section number ("1", "1.2", "1.2.3"), optionally followed by `.`
     * - A space
     * - At least one Title-Cased word
     * - A `[A-Z][a-z]` boundary that ends the heading and starts the body
     *
     * On match, emits a [DocumentBlock.Heading] (level = number of dot-separated
     * segments in the section number) followed by a [DocumentBlock.Paragraph] with
     * the remaining body text. Returns `true` if a split happened so the caller
     * can short-circuit. On no match, returns `false` and the caller emits the
     * paragraph normally.
     */
    private fun tryEmitNumberedHeadingSplit(text: String): Boolean {
        if (text.firstOrNull()?.isDigit() != true) return false
        val match = NUMBERED_HEADING_BODY.matchEntire(text) ?: return false
        val sectionNumber = match.groupValues[1]
        val titleSegment = match.groupValues[2].trimEnd()
        val body = match.groupValues[3].trimStart()
        if (titleSegment.isEmpty() || body.isEmpty()) return false

        val level = sectionNumber.trimEnd('.').count { it == '.' } + 1
        _blocks += DocumentBlock.Heading(
            level.coerceIn(1, 6),
            "$sectionNumber $titleSegment",
        )
        _blocks += DocumentBlock.Paragraph(body)
        return true
    }

    /**
     * Attempts to parse [text] as a delimited table (CSV or TSV).
     *
     * Returns a [DocumentBlock.Table] if:
     * - The text contains at least 2 lines (headers + 1 data row).
     * - All lines have the same number of cells (≥ 2 columns).
     * - The delimiter is consistent (comma or tab).
     *
     * Returns null otherwise (caller emits a Paragraph instead).
     */
    private fun tryParseAsTable(text: String): DocumentBlock.Table? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null

        // Detect delimiter: try comma first, then tab.
        val delimiter = when {
            lines.all { it.contains(',') } -> ','
            lines.all { it.contains('\t') } -> '\t'
            else -> return null
        }

        val rows = lines.map { line ->
            line.split(delimiter).map { cell -> cell.trim() }
        }

        // All rows must have the same cell count, and at least 2 columns.
        val colCount = rows[0].size
        if (colCount < 2) return null
        if (rows.any { it.size != colCount }) return null

        val headers = rows[0]
        val dataRows = rows.drop(1)
        return DocumentBlock.Table(headers = headers, rows = dataRows, caption = null)
    }

    /**
     * Converts [tableRows] into a [DocumentBlock.Table] and appends it to [_blocks].
     *
     * Row-width normalisation: the first row becomes headers. Every subsequent row is
     * padded with empty strings or truncated to match [headers].size, satisfying the
     * [DocumentBlock.Table] invariant (all rows must have exactly `headers.size` cells).
     */
    private fun emitTable() {
        if (tableRows.isEmpty()) return

        val headers = tableRows[0]
        if (headers.isEmpty()) return

        val dataRows = tableRows.drop(1).map { row ->
            when {
                row.size == headers.size -> row
                row.size < headers.size -> row + List(headers.size - row.size) { "" }
                else -> row.take(headers.size)
            }
        }

        _blocks += DocumentBlock.Table(headers = headers, rows = dataRows, caption = null)
    }

    /** Returns true if the element local name is a heading tag `h1`–`h6`. */
    private fun String.isHeading(): Boolean =
        length == 2 && this[0] == 'h' && this[1] in '1'..'6'

    private companion object {
        /**
         * Tika's PDFParser writes unset XMP metadata fields into the body when the source
         * PDF lacks Author / Title / Subject. Suppress these placeholder strings so they do
         * not surface as paragraphs.
         */
        val METADATA_LEAK_LINES = setOf("(anonymous)", "(unknown)", "(unspecified)")

        /**
         * Splits paragraphs of the form `"<num> <Title…><Body…>"` into a heading and a body.
         *
         * Group 1: section number (`1`, `1.2`, `1.2.3`), with optional trailing dot.
         * Group 2: heading title — starts with a capital, ends with a lowercase letter, may
         *          contain spaces / hyphens / apostrophes between (so multi-word Title-Case
         *          headings like "Functional Requirements" stay intact).
         * Group 3: full body (sentence-starting `[A-Z][a-z]` enforced via lookahead).
         *
         * Constraining group 2 to end in `[a-z]` makes the boundary a camelCase break
         * `[a-z][A-Z][a-z]` — i.e. fires only when a capital starts a new word with NO
         * preceding space. That is exactly the "heading glued to body" signal we want;
         * a title containing spaces (e.g. "Functional Requirements") spans them safely.
         */
        val NUMBERED_HEADING_BODY = Regex(
            "^(\\d+(?:\\.\\d+)*\\.?)\\s+([A-Z][A-Za-z\\s'\\-]*?[a-z])(?=[A-Z][a-z])(.+)$",
            RegexOption.DOT_MATCHES_ALL,
        )

        /**
         * Matches a non-whitespace character immediately followed by a URL scheme,
         * i.e. the boundary where Tika ate the space around a link annotation. Only the
         * leading direction is fixed — URL-followed-by-word is unsafe (URL-end detection
         * is fragile) and is left as a known v1.x limitation.
         */
        val URL_BOUNDARY = Regex("(\\S)(https?://)")

        /**
         * Common Unicode bullet glyphs we accept at the start of a line. Anything not in
         * this set is treated as prose. Excludes ASCII `*` and `-` because they collide
         * with prose punctuation and emphasis markup.
         */
        val BULLET_GLYPH_QUICK_CHECK = setOf('•', '●', '◦')

        /** Bullet glyph + at-least-one-whitespace at start-of-line. */
        val BULLET_PREFIX = Regex("^[\\u2022\\u25CF\\u25E6]\\s+")
    }
}
