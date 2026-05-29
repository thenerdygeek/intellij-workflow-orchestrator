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
        // SF-4 also folds the U+FFFD replacement-char bullet here (see BULLET_PREFIX).
        val bulleted = normaliseBulletMarkers(urlFixed)

        // SF-10: rejoin a word split across an INTERIOR line-wrap hyphen within this single
        // buffered block ("assis-\ntance" → "assistance"). Cross-paragraph rejoin (the dominant
        // fed-scf case, where each visual line is its OWN <p>) is handled at the PdfPipeline
        // stream level. PDF-only: the byte stream for HTML/text is faithful, so we never inject
        // joins there (gate reuses restoreUrlBoundaries, which marks a PDF source).
        val deHyphenated = if (restoreUrlBoundaries) rejoinLineWrapHyphens(bulleted) else bulleted

        // SF-4: repair inter-word spacing lost at a style/link boundary, but ONLY where a
        // closed-class function word ("the", "of", "and", …) is glued as a WHOLE token to a
        // following Capitalised word ("theUnited" → "the United"). Restricting the left side to
        // a small function-word list keeps the repair from touching camelCase identifiers,
        // hyphenated compounds, or any ordinary glued token. PDF-only for the same reason.
        val text = if (restoreUrlBoundaries) repairGluedFunctionWords(deHyphenated) else deHyphenated

        // SF-3: a table-of-contents entry arrives as "Title <dot-leader run> <page-number>"
        // ("2.2 Considerations … ........... 5"). Collapse the dot-leader run, and split a page
        // number fused to the next entry onto its own line. Gated on a real dot-leader run so
        // ordinary prose (and a 3-dot ellipsis) is never reshaped.
        if (DOT_LEADER_RUN.containsMatchIn(text)) {
            _blocks += DocumentBlock.Paragraph(cleanTocEntry(text))
            return
        }

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
     * SF-4 — repairs inter-word spacing lost at a style/link boundary, conservatively.
     *
     * Tika's PDFParser drops the space around a run/style boundary when the generator draws the
     * two runs as separate text objects with no intervening space glyph. The common visible
     * symptom is a closed-class function word glued to the following capitalised word
     * (`"Accounts of theUnited States"`, `"by theFederal Reserve"`, `"andStatistical Measures"`).
     *
     * The repair fires ONLY when a [GLUE_FUNCTION_WORDS] token appears as a WHOLE word (preceded
     * by a space or string start) immediately followed by an uppercase letter that opens a
     * lowercase continuation (`[A-Z][a-z]`). This triple guard is what keeps the heuristic from
     * corrupting good text:
     * - whole-word left side ⇒ "theory"/"often"/"android" (function-word *prefixes* of a single
     *   word) are NOT split;
     * - uppercase-then-lowercase right side ⇒ camelCase identifiers ("ourRepo") and ALL-CAPS
     *   runs are NOT split;
     * - function-word allow-list ⇒ no arbitrary `[a-z][A-Z]` boundary (which would shred real
     *   glued tokens / proper-noun compounds) is touched.
     */
    private fun repairGluedFunctionWords(text: String): String {
        if ('A' !in text && text.none { it.isUpperCase() }) return text
        return GLUE_BOUNDARY.replace(text) { m ->
            // m groups: (1) leading boundary char or empty, (2) function word, (3) the capital
            val word = m.groupValues[2]
            if (word.lowercase() in GLUE_FUNCTION_WORDS) {
                "${m.groupValues[1]}$word ${m.groupValues[3]}"
            } else {
                m.value
            }
        }
    }

    /**
     * SF-10 — rejoins a word split across an INTERIOR soft line-wrap hyphen inside one block:
     * `"assis-\ntance"` → `"assistance"`. Only a lowercase-letter, then `-`, then optional
     * trailing spaces, a newline, optional leading spaces, then a lowercase letter is treated as
     * a soft wrap. A hyphen before a DIGIT or an UPPERCASE letter is a real compound / range
     * (`"30-\n40"`, `"Loan-\nTo-Value"`): the newline collapses to a space but the hyphen stays.
     */
    private fun rejoinLineWrapHyphens(text: String): String {
        if (!text.contains('\n') || '-' !in text) return text
        // Soft wrap: [a-z]- <newline> [a-z]  → join with NO hyphen and NO space.
        val joined = SOFT_WRAP_HYPHEN.replace(text) { m -> m.groupValues[1] + m.groupValues[2] }
        // Any remaining hyphen-at-end-of-line (before digit/uppercase/etc.) keeps its hyphen but
        // the newline becomes a space so the two tokens don't fuse.
        return joined.replace(HARD_HYPHEN_WRAP) { m -> "${m.groupValues[1]}- ${m.groupValues[2]}" }
    }

    /**
     * SF-3 — cleans a detected table-of-contents entry: collapses the dot-leader run to a single
     * space, and splits a page number that has fused to the start of the NEXT entry onto its own
     * line (`"… Flexibilities ..... 52.3 A Few Limitations"` → `"… Flexibilities 5\n2.3 A Few
     * Limitations"`). Called only when [DOT_LEADER_RUN] matched, so ordinary prose is unaffected.
     */
    private fun cleanTocEntry(text: String): String {
        // Collapse every dot-leader run (with its surrounding spaces) to a single space.
        var cleaned = DOT_LEADER_RUN.replace(text, " ")
        // Split a fused "<pageDigits><nextSectionNumber>" where the next section starts a new
        // entry, e.g. "5" + "2.3 A Few Limitations". The next entry begins with a digit-dot-digit
        // section number followed by a capitalised word.
        cleaned = FUSED_PAGE_THEN_ENTRY.replace(cleaned) { m ->
            "${m.groupValues[1]}\n${m.groupValues[2]}"
        }
        return cleaned.trim()
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
        // SF-9: a generator/tool artifact title ("Enscript Output") that Tika leaks as the first
        // prose line must never become the document's H1 — it would demote the real title.
        if (line.lowercase() in GENERATOR_TITLE_ARTIFACTS) return false
        // A heading does not end in sentence punctuation. NAV-3: a TRAILING colon is also a
        // reject — "Control Enhancements:", "Cookies:" are list/value lead-ins, not sections.
        // An INTERIOR colon is fine ("Appendix A: Framework Core") and is handled below.
        if (line.last() in HEADING_TERMINAL_REJECT || line.last() == ':') return false

        // Flavour 1: standalone numbered heading ("3 Definitions", "1.1 Overview", "1.1.  Notation").
        // NAV-3: only when the numbered remainder is a real Title-Case/ALL-CAPS section title,
        // not an enumerated list item ("2. Group and role membership; and") or a chart-axis
        // fragment ("100 Percent"). isNumberedHeadingTitle vets the remainder words.
        STANDALONE_NUMBERED_HEADING.matchEntire(line)?.let { m ->
            val sectionNumber = m.groupValues[1]
            val remainder = line.removePrefix(sectionNumber).trim()
            if (isNumberedHeadingRemainderClean(sectionNumber, remainder)) {
                val level = sectionNumber.trimEnd('.').count { it == '.' } + 1
                _blocks += DocumentBlock.Heading(level.coerceIn(1, 6), line)
                return true
            }
            // Falls through: a numbered line whose remainder is sentence-shaped / list-shaped
            // is prose, not a heading.
            return false
        }

        // Reject list-item shapes ("1) …", "a. …", "i. …") so they stay prose.
        if (LIST_ITEM_PREFIX.containsMatchIn(line)) return false

        // NAV-3: equation-glyph / symbol-heavy fragments ("QKT", "<EOS> <EOS>", "QK V") are not
        // headings. Reject lines that contain markup-angle brackets or whose letters are
        // dominated by isolated single-capital tokens (matrix/vector glyphs) rather than words.
        if (isSymbolOrGlyphFragment(line)) return false

        // Flavour 2: standalone unnumbered heading — must be terse and Title-Case or ALL-CAPS.
        val words = line.split(WHITESPACE_SPLIT).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > MAX_HEADING_WORDS) return false
        // Reject lines containing alphanumeric data tokens (codes, versions, dates) — these are
        // table rows / title-page metadata that Tika leaks as prose, not section headings:
        // "BUG-001 MEDIUM RESOLVED", "Specificationv1.0 — 2026-04-30", "v1.0". A heading is made
        // of clean words (letters, optional trailing colon, optional single appendix letter).
        if (words.any { !isCleanHeadingWord(it) }) return false
        if (!isTitleCaseOrAllCaps(words)) return false
        // NAV-3: glossary rows — a leading ALL-CAPS acronym followed by a Title-Case expansion
        // ("ANSI American National Standards Institute"). These are key/value rows in an acronym
        // appendix, not section headings.
        if (isAcronymExpansionRow(words)) return false

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
     * Vets the title remainder of a standalone numbered line so only real section headings
     * (`"4.0 Self-Assessing Cybersecurity Risk"`) are promoted, while enumerated list items
     * (`"2. Group and role membership; and"`) and chart-axis fragments (`"100 Percent"`) are
     * rejected. NAV-3.
     *
     * Rejections:
     * - empty remainder;
     * - a single-DIGIT enumerator (`"2."`/`"2"`) whose remainder reads as a sentence/clause —
     *   detected by an interior `;`, a trailing connective ("and"/"or"), or a lowercase
     *   significant word. Real single-segment SECTION numbers ("4.0", "1") are distinguished
     *   because their remainder is a clean Title-Case/ALL-CAPS phrase.
     * - a chart-axis fragment: a bare integer (no dot) followed by exactly one word
     *   (`"100 Percent"`).
     */
    private fun isNumberedHeadingRemainderClean(sectionNumber: String, remainder: String): Boolean {
        if (remainder.isEmpty()) return false
        if (remainder.last() in HEADING_TERMINAL_REJECT || remainder.last() == ':') return false

        val words = remainder.split(WHITESPACE_SPLIT).filter { it.isNotEmpty() }

        // Chart-axis fragment: a bare integer with NO trailing dot followed by exactly one word
        // ("100 Percent"). A genuine single-word section number carries a trailing dot
        // ("1.  Introduction") or at least one dot-segment, so it is NOT caught here.
        val hasNoDot = '.' !in sectionNumber
        if (hasNoDot && words.size == 1) return false

        // Enumerated-list shape: ";" anywhere, or a trailing bare connective.
        if (';' in remainder) return false
        if (words.lastOrNull()?.lowercase()?.trimEnd(',', '.') in LIST_TAIL_CONNECTIVES) return false

        // The remainder must read as a heading: clean words, Title-Case or ALL-CAPS.
        if (words.any { !isCleanHeadingWord(it) }) return false
        return isTitleCaseOrAllCaps(words)
    }

    /**
     * True for equation-glyph / symbol-heavy fragments that Tika leaks from formulae and figure
     * axes — `"QKT"`, `"<EOS> <EOS>"`, `"QK V"`. NAV-3.
     *
     * Signals:
     * - contains an angle-bracket markup token (`<EOS>`) — never a section heading;
     * - the line is dominated by isolated single-letter capital tokens (matrix/vector symbols
     *   like `Q`, `K`, `V`) or short ALL-CAPS glyph runs with no real multi-letter words.
     */
    private fun isSymbolOrGlyphFragment(line: String): Boolean {
        if ('<' in line && '>' in line) return true
        val words = line.split(WHITESPACE_SPLIT).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        // A "real word" has ≥3 letters and is not entirely a glyph run. If NONE of the words is a
        // real word, the line is a symbol/glyph fragment ("QKT", "QK V").
        val hasRealWord = words.any { w ->
            val letters = w.filter { it.isLetter() }
            letters.length >= 3 && !(letters.all { it.isUpperCase() } && letters.length <= 4)
        }
        return !hasRealWord
    }

    /**
     * True for acronym-glossary rows: a leading ALL-CAPS acronym (≥2 letters) followed by a
     * mixed-case Title-Case expansion of ≥2 words — e.g. `"ANSI American National Standards
     * Institute"`, `"NIST National Institute of Standards and Technology"`. NAV-3.
     *
     * Distinguished from a genuine ALL-CAPS heading ("CHAPTER THREE", "INTRODUCTION") because
     * those are wholly ALL-CAPS, whereas a glossary row mixes an ALL-CAPS head with lowercase
     * expansion words.
     */
    private fun isAcronymExpansionRow(words: List<String>): Boolean {
        if (words.size < 3) return false
        val head = words.first().trimEnd(':', '.', ')')
        val headLetters = head.filter { it.isLetter() }
        val headIsAcronym = headLetters.length in 2..6 && headLetters.all { it.isUpperCase() }
        if (!headIsAcronym) return false
        // The expansion must contain at least one lowercase letter (mixed-case), i.e. it is NOT a
        // fully ALL-CAPS heading.
        val expansion = words.drop(1).joinToString(" ")
        return expansion.any { it.isLowerCase() }
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

        // IMG-1: carry the <img alt> as the figure's alt-text so the marker leads with the
        // description. IMG-4: an image-mime EmbeddedFileRef (even with path=null) serialises
        // with the `[image:` token, so the corpus probe's imageMarkers count is consistent
        // with the body for HTML too (previously rendered `[embedded:]`, contradicting the metric).
        _blocks += DocumentBlock.EmbeddedFileRef(
            name = displayName,
            mimeType = mime,
            path = null,
            altText = alt.takeIf { it.isNotEmpty() },
        )
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
         * Generator/tool-artifact document titles that PDF producers stamp into `/Title` and that
         * Tika then leaks as the first prose line. These are NOT real section headings — promoting
         * one would demote the document's real title (SF-9). Lower-cased for comparison.
         */
        val GENERATOR_TITLE_ARTIFACTS = setOf("enscript output", "untitled", "untitled document")

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

        /**
         * Bare connectives that, as the LAST word of a numbered line, mark an enumerated list
         * item rather than a section heading ("2. Group and role membership; and").
         */
        val LIST_TAIL_CONNECTIVES = setOf("and", "or", "nor")

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
         *
         * SF-4: `�` (the Unicode REPLACEMENT CHARACTER) is included because some PDF
         * generators draw the list bullet with a glyph that has no Unicode mapping; Tika then
         * decodes it to `�`. We treat `�` as a bullet glyph ONLY at line start (where
         * a real bullet sits); a mid-line `�` is a genuine decode artifact and is left alone
         * (see [BULLET_PREFIX] anchoring + [normaliseBulletMarkers] per-line trimStart gate).
         */
        val BULLET_GLYPH_QUICK_CHECK = setOf('•', '●', '◦', '�')

        /** Bullet glyph + at-least-one-whitespace at start-of-line. */
        val BULLET_PREFIX = Regex("^[\\u2022\\u25CF\\u25E6\\uFFFD]\\s+")

        /**
         * SF-4 — closed-class function words that, when glued as a whole token to a following
         * Capitalised word, signal a dropped inter-word space. Deliberately small: every entry
         * is a word that, in real prose, is almost always followed by a separate word — so a
         * glued `Capitalised` continuation is overwhelmingly a lost space, not a real token.
         */
        val GLUE_FUNCTION_WORDS = setOf(
            "the", "of", "and", "in", "to", "for", "on", "at", "by", "with",
            "from", "as", "or", "an", "a", "into", "per", "via",
        )

        /**
         * SF-4 — a function-word token glued to a Capital+lowercase continuation.
         * Group 1: the boundary on the left (a single space or empty at string start).
         * Group 2: the candidate function word (vetted against [GLUE_FUNCTION_WORDS]).
         * Group 3: the capital letter that opens the next word.
         * The `(?=[a-z])` lookahead requires a lowercase continuation so ALL-CAPS runs and
         * single-capital glyphs are never split.
         */
        val GLUE_BOUNDARY = Regex("(^|\\s)([A-Za-z]{1,4})([A-Z])(?=[a-z])")

        /** SF-10 — soft line-wrap hyphen: lowercase, hyphen, newline, lowercase. */
        val SOFT_WRAP_HYPHEN = Regex("([a-z])-[ \\t]*\\n[ \\t]*([a-z])")

        /** SF-10 — a hyphen at a line wrap before a non-lowercase continuation (keep the hyphen). */
        val HARD_HYPHEN_WRAP = Regex("([A-Za-z0-9])-[ \\t]*\\n[ \\t]*(\\S)")

        /**
         * SF-3 — a dot-leader run: at least four dots, optionally space-separated, surrounded by
         * optional whitespace. Four is well above a 3-dot ellipsis so prose is never matched.
         */
        val DOT_LEADER_RUN = Regex("\\s*\\.(?:\\s*\\.){3,}\\s*")

        /**
         * SF-3 — a page number fused to the next TOC entry's section number: trailing digits of
         * the page number, then a `digit(.digit)+` section number opening a Capitalised word.
         * Group 1 = page-number digits, Group 2 = the next section number + title.
         */
        val FUSED_PAGE_THEN_ENTRY = Regex("(\\d)(\\d+(?:\\.\\d+)+\\s+[A-Z].*)$")
    }
}
