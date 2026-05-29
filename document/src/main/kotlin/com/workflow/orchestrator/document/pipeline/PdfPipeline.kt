package com.workflow.orchestrator.document.pipeline

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.pdf.PdfLink
import com.workflow.orchestrator.document.pdf.PdfMetadataExtractor
import com.workflow.orchestrator.document.pdf.PdfProseExtractor
import com.workflow.orchestrator.document.pdf.PdfTableExtractor
import com.workflow.orchestrator.document.pdf.PositionedBlock
import com.workflow.orchestrator.document.service.ImageExtractionService
import java.nio.file.Path

/**
 * Full PDF extraction pipeline: Tabula tables merged with Tika XHTML prose in reading order.
 *
 * ## Pipeline steps
 *
 * 1. **Table extraction** — [PdfTableExtractor] uses Tabula-java lattice mode (optionally
 *    stream mode) to extract tables from each page. Each table is wrapped in a
 *    [PositionedBlock] with its PDF user-space bbox (`page`, `top`, `bottom`).
 *    Multi-page table continuations are merged by [PdfTableExtractor.mergeContinuations].
 *
 * 2. **Prose extraction** — [PdfProseExtractor] drives [TikaXhtmlPipeline] to produce
 *    headings, paragraphs, and page markers. Each block gets a synthetic Y-coordinate
 *    that preserves intra-page document order (Tika XHTML does not expose PDF bboxes).
 *
 * 3. **Annotation extraction** — [PdfMetadataExtractor] walks each page's annotation
 *    array via PDFBox and emits [DocumentBlock.Comment] blocks with
 *    [DocumentBlock.Comment.Kind.PDF_ANNOTATION]. Each block receives real PDF coordinates
 *    translated to reading-order Y (smaller = closer to top of page). [PdfMetadataExtractor]
 *    is the **sole** annotation source: Tika's `isExtractAnnotationText` flag is `false`
 *    (flipped in Phase 4b), so no untyped Paragraph annotation leakage coexists with the typed
 *    Comment blocks. `isExtractMarkedContent` is also `false` (NAV-1 / G-1 + HX-1) — see
 *    [hardenedPdfConfig]. Note: [PdfMetadataExtractor] only walks `PDAnnotationMarkup` (sticky
 *    notes, highlights); harvesting `/Link` + `/URI` annotations into Markdown links is a
 *    deferred P1 feature.
 *
 * 4. **Merge** — Table, prose, and annotation blocks are combined and sorted by `(page, top)`.
 *    Because Tabula table bboxes are real PDF units and prose bboxes are synthetic
 *    counters starting at 1.0, a table at `top = 320.0` on page 2 will sort correctly
 *    after prose blocks at `top = 1, 2, 3` if those prose blocks are on pages ≤ 2 with
 *    smaller y-values. For prose blocks on the same page as a table, the sort will place
 *    them before the table when their synthetic `top` < table's real `top` — this is the
 *    expected behaviour for a spec-doc with a heading then a table.
 *
 *    **Limitation:** Tika prose bboxes are synthetic, so interleaving of prose and tables
 *    on the *same page* is approximate. For the dominant spec-doc layout (heading above
 *    table, prose after table) the ordering is correct because headings appear before
 *    tables in Tika's parse order and receive smaller `top` values. Phase 6 can improve
 *    accuracy by extracting real text-block bboxes from PDFBox's `PDFTextStripper`.
 *
 *    **G-9 DEFERRED (need position data — NOT done here):**
 *    - **SF-1** two-column reading-order interleave (left column's lines fused with the right
 *      column's on the same visual row) and **SF-8** masthead two-column fusion share this exact
 *      root: Tika emits prose in stream order with no x-coordinate, so columns cannot be
 *      separated. The fix is a PDFTextStripper position-based prose path replacing the Tika prose
 *      stage — a large architectural rewrite that deserves its own focused effort.
 *    - **SF-2** preformatted/monospace fidelity (ABNF, pseudo-code, display equations) is only
 *      partially position-independent: by the time a block reaches this pipeline the original
 *      newlines are already collapsed onto one line (e.g. rfc7230's `HTTP-version = …  HTTP-name
 *      = …`), so there is no reliable in-stream signal to fence a code block without the same
 *      position data SF-1 needs. Deferred with SF-1.
 *
 *    The CONTAINED G-9 cleanups that DID land (no position data required): SF-5 repeating
 *    header/footer band stripping ([stripRepeatedPageChrome] / [normalisePageNumber]), SF-10
 *    cross-paragraph soft-hyphen rejoin ([rejoinHyphenatedParagraphs]); plus SF-4 glued
 *    function-word repair + U+FFFD bullets and SF-3 TOC dot-leader cleanup in
 *    [com.workflow.orchestrator.document.sax.DocumentBlockHandler].
 *
 * 5. **Overlap suppression** — [suppressOverlaps] drops Tabula table blocks that spatially
 *    overlap already-emitted prose paragraphs by > 70% vertically. In lattice-only mode
 *    this guard almost never fires (ruled tables are correctly bounded); it is defence in
 *    depth against any Tabula misclassification, especially when stream mode is enabled.
 *
 * ## Triple file open
 *
 * [tableExtractor], [proseExtractor], and [metadataExtractor] each open the file independently
 * (different PDFBox `PDDocument` instances). This is safer than sharing a `PDDocument` because
 * Tabula mutates page state during extraction, which could corrupt the other parsers if they ran
 * on the same open document. The performance cost (three OS-level file opens) is acceptable for v1.
 *
 * @param tableExtractor  Source of lattice/stream Tabula tables. Default: lattice with a
 *                        per-page stream fallback enabled, so borderless (whitespace-aligned)
 *                        tables — the norm in spec PDFs — are captured. Stream candidates pass
 *                        a strict phantom guard so multi-column prose pages don't sprout
 *                        phantom tables (see [PdfTableExtractor]).
 * @param proseExtractor  Source of Tika XHTML prose blocks. Default: [PdfProseExtractor].
 */
class PdfPipeline(
    private val tableExtractor: PdfTableExtractor = PdfTableExtractor(enableStreamMode = true),
    private val proseExtractor: PdfProseExtractor = PdfProseExtractor(),
) {

    /**
     * Extracts all content from [file] as an ordered [List<DocumentBlock>] in reading order.
     *
     * A fresh [PdfMetadataExtractor] is constructed per call so that [imageService] and [docKey]
     * can be threaded in without requiring the pipeline instance to be stateful. This mirrors the
     * per-call extractor construction used by [OfficePipeline].
     *
     * @param file         Absolute path to the PDF file.
     * @param imageService When non-null, embedded file attachments and image XObjects are
     *                     extracted and saved via [ImageExtractionService]. When null, both
     *                     P4T2 extraction passes are skipped.
     * @param docKey       Stable identifier for the document passed to [ImageExtractionService.save].
     * @return Merged, overlap-suppressed list of document blocks in reading order.
     *         Never empty for a well-formed PDF with extractable text.
     * @throws org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException for encrypted PDFs.
     */
    fun extract(
        file: Path,
        imageService: ImageExtractionService? = null,
        docKey: String = "anonymous",
        onPage: ((done: Int, total: Int) -> Unit)? = null,
    ): List<DocumentBlock> {
        val tables: List<PositionedBlock<DocumentBlock.Table>> = tableExtractor.extract(file, onPage)
        val proseRaw: List<PositionedBlock<DocumentBlock>> = proseExtractor.extract(file)
        val metadataExtractor = PdfMetadataExtractor(imageService = imageService, docKey = docKey)
        val metadata: List<PositionedBlock<DocumentBlock>> = metadataExtractor.extract(file)

        // G-6 / HX-1 harvest: splice link annotations INLINE into prose so their target survives
        // as Markdown without duplicating or hoisting the visible display text. Done before any
        // dedup/merge so the link markup travels with the paragraph through the rest of the pipeline.
        val links: List<PdfLink> = try { metadataExtractor.extractLinks(file) } catch (_: Exception) { emptyList() }
        val prose = if (links.isEmpty()) proseRaw else spliceLinksIntoProse(proseRaw, links)

        // NAV-4/NAV-6: when PdfMetadataExtractor harvested the PDF outline into authoritative
        // Heading blocks, the prose heuristic's promoted Headings (which both invert the
        // hierarchy and over-promote body lines) must NOT compete. Drop heuristic-promoted
        // Headings from the prose stream — but DEMOTE them to paragraphs rather than deleting,
        // so the heading text still appears in the body (it is real on-page content). The outline
        // Headings remain the sole section-anchor source. When there is no outline this is a no-op
        // and the heuristic headings flow through unchanged (rfc7230 / nist-csf path).
        val outlineSeeded = metadata.any { it.block is DocumentBlock.Heading }
        val proseForMerge = if (outlineSeeded) demoteProseHeadings(prose) else prose

        // Dedup pass: when Tabula extracted a Table, the same cell content also appears in
        // Tika's prose stream as flat whitespace-separated lines. Drop the prose paragraphs
        // whose tokens are entirely contained in the table's cell+header set.
        // This avoids the LLM seeing every cell value twice (once as prose, once as table).
        val dedupedProse = removeProseDuplicatedByTables(proseForMerge, tables)

        @Suppress("UNCHECKED_CAST")
        val merged: List<PositionedBlock<DocumentBlock>> =
            (tables.map { it as PositionedBlock<DocumentBlock> } + dedupedProse + metadata)
                .sortedWith(compareBy({ it.page }, { it.top }))

        val deChromed = stripRepeatedPageChrome(merged)
        // SF-10: rejoin paragraphs split by a soft line-wrap hyphen across the visual-line breaks
        // that Tika emits as separate <p> blocks ("… Board of Gov-" + "ernors, …"). Done after
        // chrome stripping so a footer band can't be merged into body, and before overlap
        // suppression so the rejoined paragraph carries the first fragment's bbox.
        val rejoined = rejoinHyphenatedParagraphs(deChromed)
        return suppressOverlaps(rejoined).map { it.block }
    }

    /**
     * Splices harvested [PdfLink]s INLINE into the prose stream (G-6 / HX-1 harvest).
     *
     * ## Strategy — disjoint, single-pass replacement
     *
     * Per paragraph we locate each link's display text, expand the hit to whole-word boundaries
     * (so a rect that captured a fragment like "urRepo" links the full "OurRepo"), then keep only
     * a set of **non-overlapping** spans and rewrite the paragraph once, right-to-left, so the
     * visible text:
     * - appears exactly once (no duplication — the HX-1 hoist failure mode),
     * - stays in its original reading-order position (no floating link block, no hoist),
     * - never produces an empty `[]`/`()` (blank display text was dropped in
     *   [PdfMetadataExtractor.extractLinks]),
     * - never produces NESTED brackets — real PDFs carry several overlapping `/Link` rects over the
     *   same/adjacent glyphs (e.g. a citation `[13]` split into two annotations, or a URL drawn as
     *   two halves). We bind only the FIRST link per overlapping span and skip the rest, so we never
     *   splice inside an already-claimed range.
     *
     * ## Target URL
     *
     * - [PdfLink.PartialTarget.Uri] → the raw URI, verbatim.
     * - [PdfLink.PartialTarget.InternalPage] → `#page-N`. We deliberately do NOT map to a section
     *   slug: a page→nearest-preceding-heading guess is wrong far too often (arxiv citation links
     *   that all point into the bibliography would mis-resolve to whatever the last seen heading
     *   was). `#page-N` is exact and recoverable, which is what the spec requires at minimum.
     *
     * ## Reliability fallback (rect→text correlation)
     *
     * The area-stripper and Tika's stripper are the same PDFBox text layer, so the captured display
     * text is normally a verbatim substring. When it is NOT a clean match — the hit would split a
     * dotted/dashed number (`3` out of `3.2`), the display/anchor contains Markdown control chars,
     * or the text simply isn't found on the page — the link is left **un-linked**: the reading text
     * is never corrupted, the target is just not surfaced for that one annotation. This is the
     * documented graceful degradation for imprecise rects.
     */
    private fun spliceLinksIntoProse(
        prose: List<PositionedBlock<DocumentBlock>>,
        links: List<PdfLink>,
    ): List<PositionedBlock<DocumentBlock>> {
        // Group links by page; preserve emission order so earlier links bind first.
        val linksByPage: Map<Int, List<PdfLink>> = links
            .filter { it.displayText.none { c -> c in "[]()" } }
            .groupBy { it.page }
        if (linksByPage.isEmpty()) return prose

        // A link binds to at most one paragraph (the first same-page paragraph containing its
        // display text). Track consumed links across paragraphs on the same page.
        val pending: MutableMap<Int, MutableList<PdfLink>> =
            linksByPage.mapValues { (_, v) -> v.toMutableList() }.toMutableMap()

        return prose.map { pb ->
            val para = pb.block as? DocumentBlock.Paragraph ?: return@map pb
            val pageLinks = pending[pb.page]?.takeIf { it.isNotEmpty() } ?: return@map pb

            val text = para.text
            // Collect non-overlapping (start, end, url) spans. Greedy by link emission order.
            val claimed = mutableListOf<IntRange>()
            val spans = mutableListOf<Triple<Int, Int, String>>()
            val bound = mutableListOf<PdfLink>()

            for (link in pageLinks) {
                val idx = text.indexOf(link.displayText)
                if (idx < 0) continue

                // Expand the matched span to whole-word boundaries so a fragment-capture
                // ("urRepo" of "OurRepo") links the full word.
                var start = idx
                var end = idx + link.displayText.length
                while (start > 0 && text[start - 1].isLetterOrDigit() && text[start].isLetterOrDigit()) start--
                while (end < text.length && text[end].isLetterOrDigit() && text[end - 1].isLetterOrDigit()) end++

                // Reject mid-number boundary (rect under-covered a dotted/dashed token like "3.2").
                val splitsNumberRight = end < text.length - 1 &&
                    text[end] in ".-" && text[end + 1].isDigit() && text[end - 1].isDigit()
                val splitsNumberLeft = start > 1 &&
                    text[start - 1] in ".-" && text[start - 2].isDigit() && text[start].isDigit()
                if (splitsNumberRight || splitsNumberLeft) continue

                // Skip overlap with an already-claimed span (overlapping annotations).
                val span = start until end
                if (claimed.any { it.first < span.last + 1 && span.first < it.last + 1 }) continue

                claimed += span
                spans += Triple(start, end, finalUrl(link))
                bound += link
            }
            pageLinks.removeAll(bound)
            if (spans.isEmpty()) return@map pb

            // Apply right-to-left so earlier offsets stay valid.
            val sb = StringBuilder(text)
            for ((start, end, url) in spans.sortedByDescending { it.first }) {
                val anchor = sb.substring(start, end)
                sb.replace(start, end, "[$anchor]($url)")
            }
            PositionedBlock(pb.page, pb.top, pb.bottom, DocumentBlock.Paragraph(sb.toString()))
        }
    }

    /**
     * Builds the final Markdown URL for a [PdfLink]. URIs pass through verbatim; internal page
     * targets render as `#page-N` (exact + recoverable).
     */
    private fun finalUrl(link: PdfLink): String =
        when (val t = link.target) {
            is PdfLink.PartialTarget.Uri -> t.uri
            is PdfLink.PartialTarget.InternalPage -> "#page-${t.page}"
        }

    /**
     * Drops prose [DocumentBlock.Paragraph] AND [DocumentBlock.Heading] blocks whose entire
     * content is also present as cells in any extracted [DocumentBlock.Table].
     *
     * ## Why
     * Tika's PDFParser emits PDF text content (including ruled-table cells) as flat
     * paragraphs. Tabula independently extracts the same cells as a `Table`. Without
     * deduplication, the user sees every value twice — once as a flat row of words,
     * once as a Markdown table.
     *
     * ## Why Headings are also considered
     * [com.workflow.orchestrator.document.sax.DocumentBlockHandler] promotes clean Title-Case
     * lines to [DocumentBlock.Heading] for section-anchor coverage. A flat table-header row that
     * Tika leaks as prose (e.g. "Metric Bound Measured", "Test Expected Actual") is shaped exactly
     * like a short heading and gets promoted too — but it is really table content, not a section
     * heading. Folding Headings into the same token-containment check drops those false anchors so
     * they never pollute the section index, while real section headings (which carry words NOT in
     * the table) are preserved.
     *
     * ## Why the token set is global, not per-page
     * Multi-page tables are merged by [PdfTableExtractor.mergeContinuations] into a
     * single `Table` whose `page` is the *first* page where the table started. A
     * per-page token map then has no entry for the continuation pages, so the prose
     * leaked from those pages is not suppressed. Folding all tables into a single
     * global token set restores correctness — the "every token must match" guard
     * is enough safety on its own to prevent false-positive drops.
     *
     * ## Heuristic
     * A prose paragraph / heading is considered duplicate if **all** of these hold:
     * 1. Its length is short (< 200 chars) — long prose mentioning a cell value is preserved.
     * 2. Every whitespace-split token is found in the global cell+header set.
     *
     * Token comparison is case-insensitive and trims punctuation. Empty tokens are skipped.
     */
    private fun removeProseDuplicatedByTables(
        prose: List<PositionedBlock<DocumentBlock>>,
        tables: List<PositionedBlock<DocumentBlock.Table>>,
    ): List<PositionedBlock<DocumentBlock>> {
        if (tables.isEmpty()) return prose

        val allTableTokens: Set<String> = tables
            .flatMap { tablePb ->
                val t = tablePb.block
                (t.headers + t.rows.flatten())
                    .flatMap { it.split(WORD_SPLIT) }
                    .map { it.normalizeForDedup() }
                    .filter { it.isNotEmpty() }
            }
            .toSet()

        return prose.filter { pb ->
            val text = when (val block = pb.block) {
                is DocumentBlock.Paragraph -> block.text
                is DocumentBlock.Heading -> block.text
                else -> return@filter true
            }
            if (text.length >= MAX_PROSE_LEN_FOR_DEDUP) return@filter true

            val tokens = text.split(WORD_SPLIT)
                .map { it.normalizeForDedup() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@filter true

            !tokens.all { it in allTableTokens }
        }
    }

    /**
     * Demotes prose-stream [DocumentBlock.Heading] blocks (promoted by the
     * [com.workflow.orchestrator.document.sax.DocumentBlockHandler] heuristic) back to
     * [DocumentBlock.Paragraph]. NAV-4/NAV-6.
     *
     * Called only when [PdfMetadataExtractor] produced authoritative outline Headings. The
     * outline is the section-anchor source; the heuristic Headings would otherwise pollute the
     * index with inverted levels and over-promoted body lines. We DEMOTE (not delete) so the
     * line's text — which is genuine on-page content — survives in the body markdown; it simply
     * stops being a section anchor.
     */
    private fun demoteProseHeadings(
        prose: List<PositionedBlock<DocumentBlock>>,
    ): List<PositionedBlock<DocumentBlock>> =
        prose.map { pb ->
            when (val b = pb.block) {
                is DocumentBlock.Heading -> PositionedBlock(pb.page, pb.top, pb.bottom, DocumentBlock.Paragraph(b.text))
                else -> pb
            }
        }

    private fun String.normalizeForDedup(): String =
        lowercase().trim { it in PUNCT_TRIM }

    /**
     * Drops short paragraphs that look like per-page chrome (running headers / footers).
     *
     * ## Why
     * Tika's PDFParser does not separate page chrome from body text. On long PDFs with a
     * date / title / URL footer, the same line is emitted as a `Paragraph` on every page —
     * tens of repetitions that drown real content for the LLM.
     *
     * ## Heuristics
     * Two passes, each gated by [MIN_PAGES_FOR_CHROME_STRIP] (no point heuristic-stripping
     * a 3-page doc). Both require the same paragraph variant to repeat more than half the
     * total page count before they fire.
     *
     * 1. **Exact match** — short paragraph (≤ [MAX_CHROME_LEN]) appearing literally `> pages/2`
     *    times. Catches static chrome like running titles.
     * 2. **Page-number normalisation** — same rule, but with the page number neutralised before
     *    counting so the per-page variants of one chrome band collapse to a single key. Catches:
     *    - a trailing arabic/roman suffix (`" 16"`, `" vii"`) — the original case;
     *    - a bracketed `[Page N]` token anywhere in the line (RFC 7230 footers
     *      `"Fielding & Reschke … Standards Track [Page 7]"` — SF-5); and
     *    - a bare `Page N` band token (running headers that carry the page number between two
     *      static title halves rather than at the trailing edge — SF-5).
     *
     * False-positive risk is bounded by the page-count floor and the per-page threshold:
     * for the heuristic to fire on legitimate body prose, the same short paragraph would
     * have to appear on most pages of a long document — vanishingly rare in practice.
     */
    private fun stripRepeatedPageChrome(
        blocks: List<PositionedBlock<DocumentBlock>>,
    ): List<PositionedBlock<DocumentBlock>> {
        val pageCount = blocks.maxOfOrNull { it.page } ?: return blocks
        if (pageCount < MIN_PAGES_FOR_CHROME_STRIP) return blocks

        val threshold = pageCount / 2

        // Single pass: build both exact-text and normalised-text counts so we don't
        // iterate every paragraph twice. Exact-match candidates are a strict subset
        // of normalised candidates because MAX_CHROME_LEN < MAX_CHROME_LEN_WITH_PAGE_NUM.
        val exactCounts = HashMap<String, Int>()
        val normalisedCounts = HashMap<String, Int>()
        for (pb in blocks) {
            val text = (pb.block as? DocumentBlock.Paragraph)?.text ?: continue
            if (text.length > MAX_CHROME_LEN_WITH_PAGE_NUM) continue
            normalisedCounts.merge(normalisePageNumber(text), 1, Int::plus)
            if (text.length <= MAX_CHROME_LEN) {
                exactCounts.merge(text, 1, Int::plus)
            }
        }
        val exactNoise = exactCounts.filterValues { it > threshold }.keys
        val normalisedNoise = normalisedCounts.filterValues { it > threshold }.keys

        if (exactNoise.isEmpty() && normalisedNoise.isEmpty()) return blocks

        return blocks.filter { pb ->
            val text = (pb.block as? DocumentBlock.Paragraph)?.text ?: return@filter true
            if (text in exactNoise) return@filter false
            if (text.length > MAX_CHROME_LEN_WITH_PAGE_NUM) return@filter true
            normalisePageNumber(text) !in normalisedNoise
        }
    }

    /**
     * Neutralises the page-number component of a chrome line so its per-page variants collapse to
     * one key (SF-5). Order matters: bracketed `[Page N]` and bare `Page N` band tokens are
     * blanked first (they may sit anywhere in the line), then any remaining bare trailing
     * arabic/roman page number is stripped. Pure-prose lines without a page number are returned
     * unchanged apart from trailing-whitespace trimming.
     */
    private fun normalisePageNumber(text: String): String =
        text
            .replace(PAGE_BAND, "")
            .trimEnd()
            .replace(TRAILING_PAGE_NUMBER, "")
            .trimEnd()

    /**
     * SF-10 — merges a [DocumentBlock.Paragraph] that ends in a soft line-wrap hyphen with the
     * immediately-following paragraph, de-hyphenating the wrapped word.
     *
     * Tika emits each visual line of a PDF as its own `<p>`, so a word hyphenated at a line break
     * arrives as two paragraphs: `"… Board of Gov-"` then `"ernors, the Federal …"`. We join them
     * when:
     * - the first paragraph ends in `<lowercase>-` (a soft wrap, not a dangling em-dash); and
     * - the next block is also a Paragraph.
     *
     * The de-hyphenation rule mirrors the in-block one in
     * [com.workflow.orchestrator.document.sax.DocumentBlockHandler]:
     * - a lowercase continuation ⇒ drop the hyphen and concatenate (`"Gov-" + "ernors"` →
     *   `"Governors"`);
     * - a non-lowercase continuation (digit / uppercase — a numeric range or compound) ⇒ keep the
     *   hyphen and insert a space, so the two tokens don't fuse.
     *
     * Conservative by construction: a paragraph NOT ending in `<lowercase>-` is never merged, and
     * a trailing hyphen paragraph with no following paragraph is left untouched.
     */
    private fun rejoinHyphenatedParagraphs(
        blocks: List<PositionedBlock<DocumentBlock>>,
    ): List<PositionedBlock<DocumentBlock>> {
        if (blocks.size < 2) return blocks
        val result = mutableListOf<PositionedBlock<DocumentBlock>>()
        var i = 0
        while (i < blocks.size) {
            val pb = blocks[i]
            val para = pb.block as? DocumentBlock.Paragraph
            val next = blocks.getOrNull(i + 1)
            val nextPara = next?.block as? DocumentBlock.Paragraph
            if (para != null && nextPara != null && SOFT_HYPHEN_TAIL.containsMatchIn(para.text)) {
                val left = para.text.trimEnd()
                val right = nextPara.text.trimStart()
                val firstRight = right.firstOrNull()
                val mergedText = if (firstRight != null && firstRight.isLowerCase()) {
                    // Soft wrap: drop the trailing hyphen and concatenate directly.
                    left.dropLast(1) + right
                } else {
                    // Hard hyphen (range/compound): keep the hyphen, separate with a space.
                    "$left $right"
                }
                // Carry the FIRST fragment's position so reading order is preserved.
                result += PositionedBlock(pb.page, pb.top, next.bottom, DocumentBlock.Paragraph(mergedText))
                i += 2
            } else {
                result += pb
                i += 1
            }
        }
        return result
    }

    // ── Test hooks (mirror the guessImageMimeFromSrc internal-test precedent) ──

    /** Test-only access to [stripRepeatedPageChrome] (SF-5). */
    internal fun stripRepeatedPageChromeForTest(
        blocks: List<PositionedBlock<DocumentBlock>>,
    ): List<PositionedBlock<DocumentBlock>> = stripRepeatedPageChrome(blocks)

    /** Test-only access to [rejoinHyphenatedParagraphs] (SF-10). */
    internal fun rejoinHyphenatedParagraphsForTest(
        blocks: List<PositionedBlock<DocumentBlock>>,
    ): List<PositionedBlock<DocumentBlock>> = rejoinHyphenatedParagraphs(blocks)

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Drops Tabula [DocumentBlock.Table] blocks whose vertical bbox overlaps an
     * already-emitted [DocumentBlock.Paragraph] by more than 70% of the paragraph's height.
     *
     * ## Why prose bbox is synthetic
     *
     * [PdfProseExtractor] assigns placeholder bboxes (top = 1, 2, 3 … per page; height = 10).
     * Real Tabula table bboxes are typically in the hundreds of PDF units. Because of this
     * mismatch, the overlap check between a Paragraph at `(top=3, bottom=13)` and a Table at
     * `(top=250, bottom=400)` will *never* trigger (no vertical intersection). This is the
     * correct behaviour: prose placeholder bboxes are not intended to represent real screen
     * coordinates and should not suppress tables.
     *
     * The guard is meaningful when stream mode is enabled (`enableStreamMode=true`) and Tabula
     * produces a phantom table on a prose-heavy page. In that case the phantom table's bbox may
     * coincide with Tika's page coordinates — the 70% threshold catches it while allowing
     * legitimate tables that partially overlap a paragraph boundary.
     *
     * @param blocks Merge-sorted positioned blocks (already in reading order).
     * @return Filtered list with phantom tables removed.
     */
    private fun suppressOverlaps(
        blocks: List<PositionedBlock<DocumentBlock>>,
    ): List<PositionedBlock<DocumentBlock>> {
        // Track emitted paragraph bboxes per page for overlap checks.
        // Key: page number; Value: list of (top, bottom) pairs.
        val emittedParagraphBboxes = mutableMapOf<Int, MutableList<Pair<Double, Double>>>()

        val result = mutableListOf<PositionedBlock<DocumentBlock>>()

        for (pb in blocks) {
            when (val block = pb.block) {
                is DocumentBlock.Table -> {
                    // Check if this table overlaps any emitted paragraph on the same page.
                    val paragraphs = emittedParagraphBboxes[pb.page] ?: emptyList()
                    val suppressed = paragraphs.any { (pTop, pBottom) ->
                        verticalOverlapFraction(
                            aTop = pb.top, aBottom = pb.bottom,
                            bTop = pTop, bBottom = pBottom,
                        ) > OVERLAP_THRESHOLD
                    }
                    if (!suppressed) {
                        result += pb
                    }
                }
                is DocumentBlock.Paragraph -> {
                    emittedParagraphBboxes.getOrPut(pb.page) { mutableListOf() }
                        .add(pb.top to pb.bottom)
                    result += pb
                }
                else -> result += pb
            }
        }

        return result
    }

    /**
     * Computes the vertical overlap fraction between two bboxes, normalised by the height
     * of bbox B (the "reference" — i.e. the paragraph). Returns 0.0 when there is no overlap.
     */
    private fun verticalOverlapFraction(
        aTop: Double, aBottom: Double,
        bTop: Double, bBottom: Double,
    ): Double {
        val overlapTop = maxOf(aTop, bTop)
        val overlapBottom = minOf(aBottom, bBottom)
        val overlap = overlapBottom - overlapTop
        if (overlap <= 0.0) return 0.0
        val bHeight = bBottom - bTop
        if (bHeight <= 0.0) return 0.0
        return overlap / bHeight
    }

    private companion object {
        /** Tables overlapping prose by more than this fraction of prose height are suppressed. */
        const val OVERLAP_THRESHOLD = 0.70

        /** Prose paragraphs longer than this are never considered for table-dup removal. */
        const val MAX_PROSE_LEN_FOR_DEDUP = 200

        /** Page-count floor below which the chrome heuristic does not fire. */
        const val MIN_PAGES_FOR_CHROME_STRIP = 5

        /** Max length for the exact-match chrome heuristic. */
        const val MAX_CHROME_LEN = 100

        /**
         * Max length for the trailing-page-number-normalised heuristic. Higher than the
         * exact-match cap because real page chrome often combines two lines (date/title +
         * URL/page-number) into a single ~150-char paragraph block.
         */
        const val MAX_CHROME_LEN_WITH_PAGE_NUM = 200

        /** Whitespace and tabs split tokens for the dedup comparison. */
        val WORD_SPLIT = Regex("\\s+")

        /** Punctuation trimmed from each token before comparison so "FR-001," matches "FR-001". */
        val PUNCT_TRIM = setOf('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')', '[', ']')

        /**
         * Trailing whitespace + arabic digits (1–4) or short roman numerals (1–5 chars).
         * Anchored at end-of-string so only the page-number suffix is stripped.
         */
        val TRAILING_PAGE_NUMBER = Regex("\\s+([0-9]{1,4}|[ivxlcdmIVXLCDM]{1,5})$")

        /**
         * SF-5 — a page-number band token that may sit ANYWHERE in a chrome line: a bracketed
         * `[Page 7]` (RFC footers) or a bare `Page 7` run. Case-insensitive. Replaced with the
         * empty string so the static chrome on either side collapses to one normalised key.
         * Includes surrounding whitespace so removing an interior band doesn't leave a double
         * space that would defeat the equality grouping.
         */
        val PAGE_BAND = Regex("\\s*\\[?\\s*Page\\s+[0-9ivxlcdmIVXLCDM]{1,6}\\s*\\]?\\s*", RegexOption.IGNORE_CASE)

        /** SF-10 — a paragraph ending in a soft line-wrap hyphen: `<lowercase>-` at end of text. */
        val SOFT_HYPHEN_TAIL = Regex("[a-z]-$")
    }
}
