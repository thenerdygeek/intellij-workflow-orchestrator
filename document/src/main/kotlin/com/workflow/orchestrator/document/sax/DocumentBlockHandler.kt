package com.workflow.orchestrator.document.sax

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.normaliseRow
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
 * - `<ul>`/`<ol>` start (outermost): flush pending body text; enter list state.
 * - `<ul>`/`<ol>` start (nested): commit any buffered outer-item text; increment depth.
 * - `<li>` start: clear item buffer; set `inListItem = true`.
 * - `</li>` end: commit [currentItemBuffer] to [currentListItems] with `"  ".repeat(depth)` prefix.
 * - `</ul>`/`</ol>` end (depth > 0): decrement depth; continue outer list.
 * - `</ul>`/`</ol>` end (depth == 0): emit [DocumentBlock.ListBlock]; reset all list state.
 * - `characters()`: route text to the active buffer (cell, heading, list item, or body).
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

    // ── List state ────────────────────────────────────────────────────────────

    /**
     * True while inside the outermost `<ul>` or `<ol>` element.
     * Nested lists increment [listNestingDepth] rather than re-entering this state.
     */
    private var inList: Boolean = false

    /** True when the outermost list element is `<ol>` (ordered). */
    private var currentListOrdered: Boolean = false

    /** Items committed so far for the current list. */
    private val currentListItems = mutableListOf<String>()

    /**
     * Text buffer for the `<li>` element currently being accumulated.
     * Reused across items; cleared on every `<li>` start and committed on `</li>`.
     */
    private val currentItemBuffer = StringBuilder()

    /**
     * True while inside any `<li>` element (including nested list items).
     * Drives routing of character data to [currentItemBuffer].
     */
    private var inListItem: Boolean = false

    /**
     * 0 = inside the outermost list, 1 = one level of nesting, etc.
     * Items emitted at depth > 0 are prefixed with `"  ".repeat(listNestingDepth)`.
     */
    private var listNestingDepth: Int = 0

    // ── Anchor / hyperlink state ──────────────────────────────────────────────

    /**
     * True while inside an `<a href="…">` element.
     * Character data while in this state is routed to [currentAnchorTextBuffer] instead
     * of [currentBuffer] to avoid double-counting.
     */
    private var inAnchor: Boolean = false

    /** The `href` attribute value captured when the `<a>` was opened. */
    private var currentAnchorHref: String? = null

    /** Accumulates the visible text of the current anchor element. */
    private val currentAnchorTextBuffer = StringBuilder()

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

            name == "a" -> {
                // Capture the href so we can append it as a parenthetical on </a>.
                val hrefRaw = attrs?.getValue("href")?.trim()
                if (!hrefRaw.isNullOrBlank()) {
                    inAnchor = true
                    currentAnchorHref = hrefRaw
                    currentAnchorTextBuffer.clear()
                }
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

            name == "img" -> {
                // Flush any pending body text first — the image is its own block.
                flushBufferAsParagraph()
                handleImage(attrs)
            }

            name == "ul" || name == "ol" -> {
                if (!inList) {
                    // Entering the outermost list — flush any pending body text and
                    // initialise list state.
                    flushBufferAsParagraph()
                    inList = true
                    currentListOrdered = (name == "ol")
                    currentListItems.clear()
                    currentItemBuffer.clear()
                    listNestingDepth = 0
                    inListItem = false
                } else {
                    // Nested list inside an outer <li>: commit the outer item's text
                    // accumulated so far (before the nested list opened), then increase
                    // depth so subsequent <li>s are indented.
                    val outerText = currentItemBuffer.toString().trim()
                    if (outerText.isNotEmpty()) {
                        currentListItems += outerText
                    }
                    currentItemBuffer.clear()
                    listNestingDepth++
                }
            }

            name == "li" -> {
                if (inList) {
                    // Flush any leftover text from the previous item (defensive; shouldn't
                    // normally be non-empty at the start of a new sibling <li>).
                    currentItemBuffer.clear()
                    inListItem = true
                }
            }
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = (localName ?: qName ?: "").lowercase()

        when {
            name == "a" -> {
                if (inAnchor) {
                    val anchorText = currentAnchorTextBuffer.toString()
                    val href = currentAnchorHref
                    // Append "visible text (url)" to the appropriate active buffer.
                    val assembled = if (!href.isNullOrBlank()) "$anchorText ($href)" else anchorText
                    when {
                        inTable && inCell -> currentCell.append(assembled)
                        headingLevel > 0 -> currentBuffer.append(assembled)
                        inListItem -> currentItemBuffer.append(assembled)
                        !inTable && !inList -> currentBuffer.append(assembled)
                    }
                    inAnchor = false
                    currentAnchorHref = null
                    currentAnchorTextBuffer.clear()
                }
            }

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

            name == "li" -> {
                if (inList && inListItem) {
                    val itemText = currentItemBuffer.toString().trim()
                    if (itemText.isNotEmpty()) {
                        val indent = "  ".repeat(listNestingDepth)
                        currentListItems += indent + itemText
                    }
                    currentItemBuffer.clear()
                    inListItem = false
                }
            }

            name == "ul" || name == "ol" -> {
                if (inList) {
                    if (listNestingDepth > 0) {
                        // Closing a nested list — pop the depth; the outer <li> continues.
                        listNestingDepth--
                        inListItem = true // back inside the outer <li>
                    } else {
                        // Closing the outermost list — emit the ListBlock.
                        if (currentListItems.isNotEmpty()) {
                            _blocks += DocumentBlock.ListBlock(
                                ordered = currentListOrdered,
                                items = currentListItems.toList(),
                            )
                        }
                        inList = false
                        inListItem = false
                        currentListItems.clear()
                        currentItemBuffer.clear()
                        listNestingDepth = 0
                    }
                }
            }
        }
    }

    override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (ch == null) return
        val text = String(ch, start, length)
        when {
            // Anchor text is buffered separately to avoid double-counting; it will be
            // appended to the active context buffer (with the URL postfix) on </a>.
            inAnchor -> currentAnchorTextBuffer.append(text)
            inTable && inCell -> currentCell.append(text)
            headingLevel > 0 -> currentBuffer.append(text)
            inListItem -> currentItemBuffer.append(text)
            !inTable && !inList -> currentBuffer.append(text)
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
     * Emits a [DocumentBlock.EmbeddedFileRef] for an HTML `<img>` element.
     *
     * The display name prefers [alt]; if absent, the last path segment of [src] is used;
     * if both are empty the fallback is `"image"`. The MIME type is guessed from the [src]
     * extension (`.png` → `image/png`, etc.) or extracted from a `data:` URI prefix.
     *
     * [DocumentBlock.EmbeddedFileRef.path] is **always null** for HTML img tags because
     * the document reader does not fetch URI bytes — only the tag's attributes are
     * available from the Tika XHTML SAX stream.
     *
     * TODO Phase 2 follow-up: for RTF/ODT/EPUB, wire Tika's EmbeddedDocumentExtractor so
     * embedded image bytes flow to ImageExtractionService and `path` becomes non-null.
     */
    private fun handleImage(attrs: Attributes?) {
        if (attrs == null) return
        val src = attrs.getValue("src")?.trim().orEmpty()
        val alt = attrs.getValue("alt")?.trim().orEmpty()

        // Determine the display name — prefer alt for human readability, fall back to a
        // sanitised src (last URL path segment) or "image" if both empty.
        // Note: Tika's HtmlParser truncates data: URIs to just "data:" in the src attribute,
        // so we treat a bare "data:" prefix as equivalent to an empty src for display purposes.
        val srcForDisplay = if (src.startsWith("data:", ignoreCase = true)) "" else src
        val displayName = when {
            alt.isNotEmpty() -> alt
            srcForDisplay.isNotEmpty() -> {
                val lastSlash = srcForDisplay.indexOfLast { it == '/' || it == '\\' }
                if (lastSlash >= 0 && lastSlash < srcForDisplay.length - 1) srcForDisplay.substring(lastSlash + 1) else srcForDisplay
            }
            else -> "image"
        }

        val mime = guessImageMimeFromSrc(src)

        _blocks += DocumentBlock.EmbeddedFileRef(name = displayName, mimeType = mime, path = null)
    }

    /**
     * Guesses the MIME type for an image from its [src] URL or `data:` URI.
     *
     * - `data:image/webp;base64,…` → `"image/webp"` (extracts the declared type)
     * - `photo.jpg?v=2#anchor` → `"image/jpeg"` (strips query + fragment before extension lookup)
     * - Unknown or missing extension → `"application/octet-stream"`
     *
     * Visible as `internal` (module-scoped) so a same-module test can exercise the
     * `data:` URI branch directly. Tika's HtmlParser truncates `data:` URI src
     * attributes before they reach this handler, so the pipeline-level test cannot
     * cover that branch — see `DocumentBlockHandlerHelpersTest` for direct coverage.
     */
    internal fun guessImageMimeFromSrc(src: String): String {
        if (src.isBlank()) return "application/octet-stream"
        // data: URIs encode the MIME type — extract it.
        if (src.startsWith("data:", ignoreCase = true)) {
            val semi = src.indexOf(';')
            val comma = src.indexOf(',')
            val end = when {
                semi > 0 && comma > 0 -> minOf(semi, comma)
                semi > 0 -> semi
                comma > 0 -> comma
                else -> -1
            }
            if (end > 5) {
                val candidate = src.substring(5, end).trim().lowercase()
                if (candidate.isNotEmpty()) return candidate
            }
            return "application/octet-stream"
        }
        // Strip query string + fragment so file.png?v=2 still maps to .png.
        val cleaned = src.substringBefore('?').substringBefore('#')
        val dot = cleaned.lastIndexOf('.')
        if (dot <= 0 || dot == cleaned.length - 1) return "application/octet-stream"
        return when (cleaned.substring(dot + 1).lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "tiff", "tif" -> "image/tiff"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
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

        val dataRows = tableRows.drop(1).map { row -> normaliseRow(row, headers.size) }

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
         * Matches a non-whitespace, non-paren character immediately followed by a URL scheme,
         * i.e. the boundary where Tika ate the space around a link annotation. Only the
         * leading direction is fixed — URL-followed-by-word is unsafe (URL-end detection
         * is fragile) and is left as a known v1.x limitation.
         *
         * `(` is excluded from the match so that hyperlink parentheticals like
         * `"anchor text (https://...)"` are not rewritten to `"anchor text ( https://...)"`.
         */
        val URL_BOUNDARY = Regex("([^\\s(])(https?://)")

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
