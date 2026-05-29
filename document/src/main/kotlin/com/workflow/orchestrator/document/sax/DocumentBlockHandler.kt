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
 * ## URL-boundary restoration (PDF-only)
 *
 * Tika's PDFParser does not insert spaces around link annotations when the underlying
 * PDF text stream lacks them, so URLs end up glued to the preceding word
 * (`"found at:https://…"`) or chained together. The [restoreUrlBoundaries] workaround
 * re-inserts a separating space. It is gated behind a flag because it is **only correct
 * for PDF byte streams** — for HTML/CSV/JSON/plain-text the byte stream is faithful and
 * a URL legitimately follows a string delimiter or markup opener (`"`, `` ` ``, `**`, `[`).
 * Running the workaround there silently injects a leading space into URL values (e.g.
 * `"url":" https://…"`), which is invisible to a JSON syntax check yet corrupts the URL.
 * Callers therefore set this `true` **only** for PDF prose extraction.
 *
 * @param csvDetectionEnabled Set to `true` only when the source MIME is CSV/TSV.
 * @param restoreUrlBoundaries Set to `true` **only** for PDF prose extraction, where
 *   Tika eats spaces around link annotations. Defaults to `false` so non-PDF content
 *   (HTML/CSV/JSON/text) is emitted verbatim.
 */
class DocumentBlockHandler(
    private val csvDetectionEnabled: Boolean = false,
    private val restoreUrlBoundaries: Boolean = false,
) : DefaultHandler() {

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
        //
        // PDF-ONLY: for HTML/CSV/JSON/plain-text the byte stream is faithful, and a URL
        // legitimately follows a string delimiter or markup opener (`"`, `` ` ``, `**`,
        // `[`). Applying this there silently injects a leading space into URL values
        // (e.g. `"url":" https://…"`) that the JSON syntax check cannot catch. So the
        // workaround only runs when the caller flags a PDF source.
        val urlFixed = if (restoreUrlBoundaries && "http" in rawText) {
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

        // Section headings also appear on their OWN line with no glued body — real spec
        // PDFs (RFC 7230, NIST) emit "1. Introduction", "Abstract", "Appendix A: …" as
        // standalone paragraphs. Detect those so they get a section anchor too. Conservative
        // (single-line, short, no terminal sentence punctuation, numbered OR Title-Case/ALL-CAPS).
        if (tryEmitStandaloneHeading(text)) return

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
     * Attempts to emit a [DocumentBlock.Heading] for a paragraph that IS a heading standing
     * on its own line, with no glued body — the dominant real-world case that
     * [tryEmitNumberedHeadingSplit] misses (it requires a glued body).
     *
     * Two flavours are recognised, both gated on a SINGLE line of text:
     *
     * 1. **Standalone numbered heading** — a leading section number ("1", "1.1", "1.1.")
     *    followed by a Title-Cased word and (optionally) more title words, e.g.
     *    `"3 Definitions and Abbreviations"`, `"1.1 Overview of the Framework"`,
     *    `"1.1.  Requirements Notation"` (RFC double-space after the number). The heading
     *    level is the dot-depth of the number (matching the glued-split convention).
     *
     * 2. **Standalone unnumbered heading** — a short Title-Case or ALL-CAPS line with no
     *    terminal sentence punctuation, e.g. `"Abstract"`, `"Executive Summary"`,
     *    `"Acknowledgements"`, `"Appendix A: Framework Core"`. Emitted at level 1.
     *
     * ## Conservatism
     *
     * Ordinary prose must never be promoted. The guards (rejected ⇒ NOT a heading):
     * - multi-line text (a real heading is a single line);
     * - ends in sentence punctuation `.`, `;`, `,`, `!`, `?` (a heading does not);
     * - more than [MAX_HEADING_WORDS] words (a heading is terse);
     * - longer than [MAX_HEADING_CHARS] characters;
     * - starts like a numbered/bulleted LIST item (`"1) …"`, `"a. …"`) rather than a
     *   section number — distinguished because list markers use `)` or single lowercase
     *   letters and carry sentence-shaped bodies;
     * - is not Title-Case (each significant word capitalised) nor ALL-CAPS — a lowercase
     *   fragment ("see section 4 below") is prose.
     *
     * A colon is allowed (so "Appendix A: Framework Core" passes) as long as it is not a
     * trailing colon on an otherwise sentence-like run.
     *
     * @return `true` (and emits a Heading) on a confident match, else `false`.
     */
    private fun tryEmitStandaloneHeading(text: String): Boolean {
        // Headings are a single line; a multi-line block is prose (or already-split content).
        if (text.contains('\n')) return false
        val line = text.trim()
        if (line.isEmpty() || line.length > MAX_HEADING_CHARS) return false
        // A heading does not end in sentence punctuation.
        if (line.last() in HEADING_TERMINAL_REJECT) return false

        // Flavour 1: standalone numbered heading ("3 Definitions", "1.1 Overview", "1.1.  Notation").
        STANDALONE_NUMBERED_HEADING.matchEntire(line)?.let { m ->
            val sectionNumber = m.groupValues[1]
            val level = sectionNumber.trimEnd('.').count { it == '.' } + 1
            _blocks += DocumentBlock.Heading(level.coerceIn(1, 6), line)
            return true
        }

        // Reject list-item shapes ("1) …", "a. …", "i. …") so they stay prose.
        if (LIST_ITEM_PREFIX.containsMatchIn(line)) return false

        // Flavour 2: standalone unnumbered heading — must be terse and Title-Case or ALL-CAPS.
        val words = line.split(WHITESPACE_SPLIT).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > MAX_HEADING_WORDS) return false
        // Reject lines containing alphanumeric data tokens (codes, versions, dates) — these are
        // table rows / title-page metadata that Tika leaks as prose, not section headings:
        // "BUG-001 MEDIUM RESOLVED", "Specificationv1.0 — 2026-04-30", "v1.0". A heading is made
        // of clean words (letters, optional trailing colon, optional single appendix letter).
        if (words.any { !isCleanHeadingWord(it) }) return false
        if (!isTitleCaseOrAllCaps(words)) return false

        _blocks += DocumentBlock.Heading(1, line)
        return true
    }

    /**
     * True when [word] is a "clean" heading token: alphabetic (with optional trailing `:` for
     * "Appendix A:" style labels, an internal hyphen for compound words like "Self-Assessing",
     * and a leading-uppercase-then-lowercase or ALL-CAPS shape). REJECTS:
     * - tokens mixing letters and digits ("BUG-001", "v1.0", "Specificationv1.0") — data codes;
     * - camelCase interior capitals ("ReqId", "itemOptions") — schema/column identifiers;
     * - bare numeric/symbol tokens ("2026-04-30", "—", "v1.0").
     * A single trailing appendix letter ("A", "B:") and roman numerals are allowed.
     */
    private fun isCleanHeadingWord(word: String): Boolean {
        val core = word.trimEnd(':', '.', ')')
        if (core.isEmpty()) return false
        // No digits anywhere in the core token — codes/versions/dates are data, not headings.
        if (core.any { it.isDigit() }) return false
        val cased = core.filter { it.isLetter() }
        if (cased.isEmpty()) return false
        // ALL-CAPS token (e.g. "ACKNOWLEDGEMENTS", "MUST") — accept.
        if (cased.all { it.isUpperCase() }) return true
        // Otherwise require a leading capital and NO interior capitals (rejects camelCase
        // identifiers like "ReqId" / "itemOptions"). Hyphenated compounds are checked per segment.
        return core.split('-').all { seg ->
            val letters = seg.filter { it.isLetter() }
            if (letters.isEmpty()) return@all true
            val first = seg.first { it.isLetter() }
            // First letter capital OR all-lowercase minor word; no interior uppercase.
            val interiorHasUpper = letters.drop(1).any { it.isUpperCase() }
            !interiorHasUpper && (first.isUpperCase() || seg.lowercase() in TITLE_CASE_MINOR_WORDS)
        }
    }

    /**
     * True when [words] read as a heading: either every alphabetic-leading word is capitalised
     * (Title Case, ignoring short connective words like "and"/"of"/"the"/"to"/"a"), or the whole
     * line is ALL-CAPS. The first word must always start with an uppercase letter.
     */
    private fun isTitleCaseOrAllCaps(words: List<String>): Boolean {
        val joined = words.joinToString(" ")
        val letters = joined.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        // ALL-CAPS: every cased letter is uppercase (allows digits, punctuation, spaces).
        if (letters.all { it.isUpperCase() }) return true

        // Title-Case: the first word starts uppercase, and no significant word starts lowercase.
        val first = words.first()
        if (!first.first().isUpperCase()) return false
        return words.all { w ->
            val c = w.first()
            when {
                !c.isLetter() -> true                                  // digits / punctuation (e.g. "A:")
                w.lowercase() in TITLE_CASE_MINOR_WORDS -> true        // "and", "of", "the", …
                c.isUpperCase() -> true
                else -> false                                          // a significant lowercase word ⇒ prose
            }
        }
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
         * A standalone numbered heading on its own line: a section number ("3", "1.1", "1.1.")
         * followed by whitespace and a Title-Cased word that opens the title. No glued body —
         * the whole remainder is the title. Group 1 is the section number (level = dot-depth).
         * Allows the RFC double-space after the number ("1.1.  Requirements Notation").
         */
        val STANDALONE_NUMBERED_HEADING =
            Regex("^(\\d+(?:\\.\\d+)*\\.?)\\s+[A-Z].*$")

        /**
         * Matches list/enumeration prefixes that must NOT be promoted to headings:
         * "1) ", "1. " when followed by a lowercase sentence, "a. ", "a) ", "i. ", "i) ".
         * Numbered SECTION headings ("1. Introduction") are NOT caught because their title
         * starts with an uppercase word and is handled by [STANDALONE_NUMBERED_HEADING] first;
         * this guard only fires for the leftover list-item shapes.
         */
        val LIST_ITEM_PREFIX = Regex("^(\\d+\\)|[a-zA-Z]\\.|[a-zA-Z]\\)|[ivx]+[.)])\\s")

        /** Characters that, at the end of a line, mark prose (a heading never ends in these). */
        val HEADING_TERMINAL_REJECT = setOf('.', ';', ',', '!', '?')

        /** Whitespace splitter for word counting / Title-Case analysis. */
        val WHITESPACE_SPLIT = Regex("\\s+")

        /** Upper bound on an unnumbered heading's word count — headings are terse. */
        const val MAX_HEADING_WORDS = 9

        /** Upper bound on a heading's character length. */
        const val MAX_HEADING_CHARS = 80

        /**
         * Minor connective words that may appear lowercase inside a Title-Case heading
         * ("Overview of the Framework", "How to Use the Framework") without disqualifying it.
         */
        val TITLE_CASE_MINOR_WORDS = setOf(
            "a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "into", "nor",
            "of", "on", "or", "per", "the", "to", "via", "vs", "with",
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
