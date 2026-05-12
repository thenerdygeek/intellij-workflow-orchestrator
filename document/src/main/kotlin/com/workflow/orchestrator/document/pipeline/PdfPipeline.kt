package com.workflow.orchestrator.document.pipeline

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.pdf.PdfMetadataExtractor
import com.workflow.orchestrator.document.pdf.PdfProseExtractor
import com.workflow.orchestrator.document.pdf.PdfTableExtractor
import com.workflow.orchestrator.document.pdf.PositionedBlock
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
 *    translated to reading-order Y (smaller = closer to top of page). Tika's
 *    `isExtractAnnotationText` flag remains `true` in Phase 1; the old untyped Paragraph
 *    leakage and the new typed Comment blocks coexist until Phase 4b dedupes them.
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
 * @param tableExtractor     Source of lattice/stream Tabula tables. Default: lattice-only.
 * @param proseExtractor     Source of Tika XHTML prose blocks. Default: [PdfProseExtractor].
 * @param metadataExtractor  Source of PDFBox markup annotations. Default: [PdfMetadataExtractor].
 */
class PdfPipeline(
    private val tableExtractor: PdfTableExtractor = PdfTableExtractor(),
    private val proseExtractor: PdfProseExtractor = PdfProseExtractor(),
    private val metadataExtractor: PdfMetadataExtractor = PdfMetadataExtractor(),
) {

    /**
     * Extracts all content from [file] as an ordered [List<DocumentBlock>] in reading order.
     *
     * @param file Absolute path to the PDF file.
     * @return Merged, overlap-suppressed list of document blocks in reading order.
     *         Never empty for a well-formed PDF with extractable text.
     * @throws org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException for encrypted PDFs.
     */
    fun extract(file: Path): List<DocumentBlock> {
        val tables: List<PositionedBlock<DocumentBlock.Table>> = tableExtractor.extract(file)
        val prose: List<PositionedBlock<DocumentBlock>> = proseExtractor.extract(file)
        val metadata: List<PositionedBlock<DocumentBlock>> = metadataExtractor.extract(file)

        // Dedup pass: when Tabula extracted a Table, the same cell content also appears in
        // Tika's prose stream as flat whitespace-separated lines. Drop the prose paragraphs
        // whose tokens are entirely contained in the table's cell+header set.
        // This avoids the LLM seeing every cell value twice (once as prose, once as table).
        val dedupedProse = removeProseDuplicatedByTables(prose, tables)

        @Suppress("UNCHECKED_CAST")
        val merged: List<PositionedBlock<DocumentBlock>> =
            (tables.map { it as PositionedBlock<DocumentBlock> } + dedupedProse + metadata)
                .sortedWith(compareBy({ it.page }, { it.top }))

        val deChromed = stripRepeatedPageChrome(merged)
        return suppressOverlaps(deChromed).map { it.block }
    }

    /**
     * Drops prose [DocumentBlock.Paragraph] blocks whose entire content is also present
     * as cells in any extracted [DocumentBlock.Table].
     *
     * ## Why
     * Tika's PDFParser emits PDF text content (including ruled-table cells) as flat
     * paragraphs. Tabula independently extracts the same cells as a `Table`. Without
     * deduplication, the user sees every value twice — once as a flat row of words,
     * once as a Markdown table.
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
     * A prose paragraph is considered duplicate if **all** of these hold:
     * 1. Its length is short (< 200 chars) — long prose mentioning a cell value is preserved.
     * 2. Every whitespace-split token in the paragraph is found in the global cell+header set.
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
            val block = pb.block
            if (block !is DocumentBlock.Paragraph) return@filter true
            if (block.text.length >= MAX_PROSE_LEN_FOR_DEDUP) return@filter true

            val proseTokens = block.text.split(WORD_SPLIT)
                .map { it.normalizeForDedup() }
                .filter { it.isNotEmpty() }
            if (proseTokens.isEmpty()) return@filter true

            !proseTokens.all { it in allTableTokens }
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
     * 2. **Trailing-page-number normalisation** — same rule, but with arabic digits or short
     *    roman-numeral suffixes (e.g. " 16", " vii") stripped before counting. Catches
     *    chrome where each page emits the same prefix with its own page number appended,
     *    which the exact-match pass would treat as N distinct strings.
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
            normalisedCounts.merge(stripTrailingPageNumber(text), 1, Int::plus)
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
            stripTrailingPageNumber(text) !in normalisedNoise
        }
    }

    private fun stripTrailingPageNumber(text: String): String =
        text.trimEnd().replace(TRAILING_PAGE_NUMBER, "").trimEnd()

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
    }
}
