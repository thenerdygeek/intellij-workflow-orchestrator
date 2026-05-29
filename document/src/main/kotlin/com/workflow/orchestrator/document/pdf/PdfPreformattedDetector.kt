package com.workflow.orchestrator.document.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/**
 * SF-2 — detects **preformatted / monospace regions** on a PDF page (ABNF grammar productions,
 * source/pseudo-code, ASCII diagrams) so the pipeline can emit them as fenced code blocks with their
 * original line breaks preserved, instead of letting them collapse into reflowed run-on prose.
 *
 * ## Why position + font data (and why the prose path can't do this)
 *
 * By the time text reaches the Tika XHTML → `DocumentBlockHandler` prose path it carries NO font or
 * position data and the original newlines are already collapsed. So — exactly like SF-1's
 * [PdfColumnDetector] — this is a fresh PDFBox pass over the page's text layer, reading per-line
 * font and geometry from [TextPosition].
 *
 * ## The signal: consecutive monospace, column-non-filling lines
 *
 * The strong signal for code/ABNF in a spec is a **monospace font**. But that alone is not enough:
 * some specs (RFC 7230) render their ENTIRE body — prose included — in Courier, so a pure font test
 * would fence the whole document. The discriminating second signal is **column fill**:
 *
 * - Justified/body **prose** fills the text column: nearly every line reaches the page's body right
 *   margin (only the final line of a paragraph is short).
 * - A **code/ABNF/diagram** region is a run of **short lines that stop well short of the right
 *   margin** (a grammar production, a pseudo-code statement, a diagram row).
 *
 * So a preformatted region is a run of [MIN_REGION_LINES]+ **consecutive monospace lines** that are
 * **predominantly column-non-filling** (a strict majority do not reach the body right margin). The
 * body right margin is measured per page from the monospace lines' own right edges (a high
 * percentile), so it adapts to the document's column width without hard-coded coordinates.
 *
 * ## Conservative bias (false-negative over false-positive)
 *
 * Per the SF-1 guardrail we **false-negative a borderline block rather than fence genuine prose**:
 * - A page with NO monospace lines yields no regions (proportional-font specs — arXiv, NIST,
 *   fed-scf — are untouched, byte-identical).
 * - A single isolated monospace line in prose is never a block ([MIN_REGION_LINES] = 2).
 * - A run whose lines mostly fill the column is treated as monospace PROSE, not code, and left to
 *   the normal prose path.
 *
 * Detection is purely read-only; it never mutates the caller's document (it strips a throwaway
 * single-page import, mirroring [PdfColumnDetector] / [PdfColumnProseExtractor]).
 */
class PdfPreformattedDetector {

    /**
     * One detected preformatted region on a page: a contiguous run of lines, in reading order,
     * whose original line breaks must be preserved (emitted as a fenced code block).
     *
     * @param firstLineIndex Index (0-based, in page reading order over the page's non-blank text
     *                        lines) of the region's first line — used by the pipeline to splice the
     *                        region back into the prose stream at the right position.
     * @param lines          The verbatim region lines, trailing whitespace trimmed, in reading order.
     */
    data class Region(
        val firstLineIndex: Int,
        val lines: List<String>,
    )

    /**
     * Captures one page's text as a sequence of lines, each tagged with monospace-ness, right edge,
     * and baseline Y (top-down device space) so the detector can break runs on paragraph gaps.
     */
    private data class Line(
        val text: String,
        val rightX: Float,
        val baselineY: Float,
        val monospaceFraction: Double,
    )

    /**
     * Detects preformatted regions on [page]. Returns an empty list for pages with no qualifying
     * monospace code/ABNF run (the overwhelming-majority case — those pages are left to the normal
     * prose path byte-for-byte).
     */
    fun detectRegions(page: PDPage): List<Region> {
        val lines = collectLines(page)
        if (lines.isEmpty()) return emptyList()
        return detectRegionsFrom(lines)
    }

    private fun detectRegionsFrom(lines: List<Line>): List<Region> {
        val monoCount = lines.count { it.monospaceFraction >= MONO_LINE_FRACTION }
        if (monoCount < MIN_REGION_LINES) return emptyList()

        // Body right margin = the column edge where justified body text wraps. Measured from a high
        // percentile of ALL lines' right edges (NOT just the monospace lines): on a mixed page the
        // proportional prose defines the column width; on an all-monospace spec page (RFC 7230) the
        // Courier body prose runs to that same edge while ABNF/code lines stop well short. Using a
        // high percentile (not the max) keeps one stray over-long line from inflating it.
        val allRightEdges = lines.map { it.rightX }.sorted()
        val rightMargin = percentile(allRightEdges, RIGHT_MARGIN_PERCENTILE)
        // A line is "non-filling" when its right edge falls short of the body right margin by more
        // than this slack — i.e. it does NOT run to the column edge the way justified prose does.
        val fillThreshold = rightMargin - NON_FILL_SLACK

        // Normal single-line spacing for this page = the median gap between adjacent lines. A gap
        // meaningfully larger than this is a PARAGRAPH break (blank-line spacing) — it must break a
        // run so a short prose-tail line and the following short heading are NOT fused into a code
        // block. Within real ABNF/code the lines sit at exactly the single-line spacing.
        val gaps = lines.zipWithNext { a, b -> b.baselineY - a.baselineY }.filter { it > 0.5f }.sorted()
        val lineSpacing = if (gaps.isEmpty()) Float.MAX_VALUE else percentile(gaps, 0.5)
        val paragraphGap = lineSpacing * PARAGRAPH_GAP_FACTOR

        // A line is a "code candidate" when it is monospace AND column-non-filling.
        fun isCandidate(line: Line): Boolean =
            line.monospaceFraction >= MONO_LINE_FRACTION && line.rightX < fillThreshold

        val regions = mutableListOf<Region>()
        var i = 0
        while (i < lines.size) {
            if (!isCandidate(lines[i])) { i++; continue }
            // Extend a run of consecutive code-candidate lines, breaking on a paragraph-sized gap so
            // a prose-tail + heading pair (both short, but separated by paragraph spacing) is not a
            // region. Lines within a code block sit at single-line spacing, so they stay together.
            var j = i + 1
            while (j < lines.size &&
                isCandidate(lines[j]) &&
                (lines[j].baselineY - lines[j - 1].baselineY) <= paragraphGap
            ) j++
            val run = lines.subList(i, j)
            if (run.size >= MIN_REGION_LINES) {
                regions += Region(firstLineIndex = i, lines = run.map { it.text.trimEnd() })
            }
            i = j
        }
        return regions
    }

    /**
     * Strips [page] into per-line records carrying the line text, its right edge, and the fraction
     * of its non-space glyphs set in a monospace font. Blank lines are dropped (they are not part of
     * a logical text line and would split a code run spuriously).
     */
    private fun collectLines(page: PDPage): List<Line> {
        val out = mutableListOf<Line>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: List<TextPosition>) {
                val trimmed = text.trim()
                if (trimmed.isEmpty()) return
                var monoGlyphs = 0
                var totalGlyphs = 0
                var rightX = 0f
                val baselineY = textPositions.firstOrNull()?.yDirAdj ?: 0f
                for (tp in textPositions) {
                    val u = tp.unicode
                    rightX = maxOf(rightX, tp.xDirAdj + tp.widthDirAdj)
                    if (u.isNullOrBlank()) continue
                    totalGlyphs++
                    if (isMonospaceFont(tp)) monoGlyphs++
                }
                if (totalGlyphs == 0) return
                out += Line(
                    text = text.trimEnd(),
                    rightX = rightX,
                    baselineY = baselineY,
                    monospaceFraction = monoGlyphs.toDouble() / totalGlyphs,
                )
            }
        }
        stripper.sortByPosition = true
        return try {
            PDDocument().use { single ->
                single.importPage(page)
                stripper.startPage = 1
                stripper.endPage = 1
                stripper.getText(single)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * True when [tp]'s glyph is set in a monospace (fixed-pitch) font. Two signals, either suffices:
     *
     * 1. **Font name** — the dominant, reliable signal in specs: Courier, any name containing "Mono"
     *    or "Consolas" (case-insensitive). Catches subset-prefixed names (`ABCDEF+CourierNewPSMT`).
     * 2. **Fixed-pitch flag** — the embedded font descriptor's `isFixedPitch`, when the font exposes
     *    it, as a fallback for monospace fonts with non-obvious names.
     */
    private fun isMonospaceFont(tp: TextPosition): Boolean {
        val font = tp.font ?: return false
        val name = (font.name ?: "").substringAfter('+').lowercase()
        if (name.contains("courier") || name.contains("mono") || name.contains("consolas")) return true
        return try {
            font.fontDescriptor?.isFixedPitch == true
        } catch (_: Exception) {
            false
        }
    }

    /** Linear-interpolated [pct] (0.0..1.0) percentile of a pre-sorted ascending list. */
    private fun percentile(sorted: List<Float>, pct: Double): Float {
        if (sorted.isEmpty()) return 0f
        if (sorted.size == 1) return sorted[0]
        val idx = (pct * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
        val lo = idx.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = (idx - lo).toFloat()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    private companion object {
        /** A line counts as monospace when at least this fraction of its glyphs are fixed-pitch. */
        const val MONO_LINE_FRACTION = 0.8

        /** Minimum consecutive monospace, non-filling lines to call a region preformatted. */
        const val MIN_REGION_LINES = 2

        /**
         * A vertical gap larger than this multiple of the page's single-line spacing is a PARAGRAPH
         * break and ends a code run — so a short prose-tail line and a following short heading
         * (separated by paragraph spacing) are not fused into a spurious code block. Code/ABNF lines
         * sit at single-line spacing, comfortably under this factor.
         */
        const val PARAGRAPH_GAP_FACTOR = 1.5

        /** Percentile of monospace line right-edges used as the body right margin (column width). */
        const val RIGHT_MARGIN_PERCENTILE = 0.9

        /**
         * A line is column-non-filling when its right edge is more than this many points short of
         * the body right margin. ~36pt ≈ half an inch — enough that a justified prose line (which
         * fills to within a glyph of the margin) is never "non-filling", while a grammar/code line
         * that stops mid-column is.
         */
        const val NON_FILL_SLACK = 36f
    }
}
