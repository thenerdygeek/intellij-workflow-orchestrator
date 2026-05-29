package com.workflow.orchestrator.document.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/**
 * SF-1 — detects whether a single PDF page is laid out in **two prose columns**, and if so returns
 * the x-coordinate of the vertical white gutter that separates them.
 *
 * ## Why a whitespace-valley histogram (and not glyph-start clustering)
 *
 * A measured spike (see `docs/superpowers/specs/2026-05-29-sf1-reading-order-design.md` §2) showed
 * that clustering line-start x-positions false-positives on indented equations / section numbers
 * (arXiv body flagged 2-col wrongly). The robust signal is a **glyph-coverage valley**: bin every
 * glyph's horizontal span into [BIN_COUNT] bins across the page width, then look for a deep,
 * near-empty band in the page center — a vertical white gutter that a true two-column layout always
 * carries and a single-column / full-width page never does.
 *
 * ## The metric: local minimum vs flanking bands (NOT vs page mean)
 *
 * A *fixed ratio against the page mean* false-NEGATIVES on pages where a figure or table straddles
 * the gutter and lifts center coverage (the design's bjs p4 case: page-mean ratio 0.64). The robust
 * metric compares the **deepest center-band bin** to the **median coverage of the two flanking
 * column bands** (the glyph-dense body left and right of center). A gutter is low *relative to its
 * immediate neighbours* even when the page mean is dragged up by a straddling figure. We require:
 *
 * 1. the center valley's coverage is a small fraction ([VALLEY_RATIO_MAX]) of the flanking-band
 *    coverage (a genuine near-empty gutter), AND
 * 2. BOTH flanking bands carry real glyph coverage ([MIN_FLANK_COVERAGE]) — so a page that is
 *    simply empty on one side (a single narrow column, a title page) is not mistaken for two
 *    columns.
 *
 * ## Conservative bias
 *
 * Per the design's guardrail, we **false-negative a borderline page rather than corrupt** a
 * single-column or table page. The thresholds are tuned to the order-of-magnitude gap measured in
 * the corpus (true 2-col valley ratio ≈0.03 vs single-col ≈1.0+), leaving a wide safety margin.
 *
 * Detection is purely positional and read-only; it never mutates the document. The Tabula-table
 * gate (a 2-col page that Tabula claimed as a table must NOT be column-split) lives in the
 * pipeline, not here — this class only answers "is this page geometrically two-column?".
 */
class PdfColumnDetector {

    /**
     * @param page The page to classify.
     * @return The gutter x-coordinate (in PDF user-space points, left-origin) when [page] is a
     *         genuine two-column prose page; `null` for single-column, empty, or ambiguous pages.
     */
    fun detectGutter(page: PDPage): Double? {
        val mediaBox = page.mediaBox
        val pageWidth = (mediaBox.upperRightX - mediaBox.lowerLeftX).toDouble()
        if (pageWidth <= 0.0) return null
        val originX = mediaBox.lowerLeftX.toDouble()

        // Histogram of glyph horizontal coverage. Each glyph contributes its width to every bin its
        // x-span overlaps (clamped), so wide and narrow glyphs are weighted by actual ink extent.
        val bins = DoubleArray(BIN_COUNT)
        val binWidth = pageWidth / BIN_COUNT
        var glyphCount = 0

        val collector = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                val gx = (text.xDirAdj.toDouble() - originX)
                val gw = text.widthDirAdj.toDouble()
                if (gw <= 0.0) return
                glyphCount++
                val startBin = ((gx) / binWidth).toInt().coerceIn(0, BIN_COUNT - 1)
                val endBin = ((gx + gw) / binWidth).toInt().coerceIn(0, BIN_COUNT - 1)
                for (b in startBin..endBin) bins[b] += binWidth.coerceAtMost(gw)
            }
        }
        collector.sortByPosition = true
        collector.startPage = 1
        collector.endPage = 1
        // Strip a one-page doc view of just this page. A throwaway single-page document avoids
        // mutating the caller's document and bounds the strip to the page we care about.
        try {
            PDDocument().use { single ->
                single.importPage(page)
                collector.getText(single)
            }
        } catch (_: Exception) {
            return null
        }

        if (glyphCount < MIN_GLYPHS) return null

        // Center band: bins in [CENTER_LOW, CENTER_HIGH) of page width — where a two-column gutter
        // sits. Flanking bands: the glyph-dense body to the left and right of center.
        val centerStart = (BIN_COUNT * CENTER_LOW).toInt()
        val centerEnd = (BIN_COUNT * CENTER_HIGH).toInt()
        val leftEnd = centerStart
        val rightStart = centerEnd

        // Deepest (minimum-coverage) bin in the center band, and its index.
        var valley = Double.MAX_VALUE
        var valleyBin = -1
        for (b in centerStart until centerEnd) {
            if (bins[b] < valley) {
                valley = bins[b]
                valleyBin = b
            }
        }
        if (valleyBin < 0) return null

        val leftFlank = medianOf(bins, 0, leftEnd)
        val rightFlank = medianOf(bins, rightStart, BIN_COUNT)

        // Both columns must carry real text. A page empty on one side of center is single-column
        // (or a title page), never two-column prose.
        if (leftFlank < MIN_FLANK_COVERAGE || rightFlank < MIN_FLANK_COVERAGE) return null

        // The gutter must be a deep valley relative to the WEAKER flank (so a lopsided figure can't
        // mask a genuine gutter, and a shallow dip between two dense bands isn't over-claimed).
        val flankRef = minOf(leftFlank, rightFlank)
        if (flankRef <= 0.0) return null
        if (valley / flankRef > VALLEY_RATIO_MAX) return null

        // Gutter x = center of the valley bin, in PDF user space.
        return originX + (valleyBin + 0.5) * binWidth
    }

    /** Median of `bins[from, to)`. Returns 0.0 for an empty range. */
    private fun medianOf(bins: DoubleArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        val slice = bins.copyOfRange(from, to).sorted()
        val n = slice.size
        return if (n % 2 == 1) slice[n / 2] else (slice[n / 2 - 1] + slice[n / 2]) / 2.0
    }

    private companion object {
        /** Histogram resolution across page width. 60 bins ≈ 10pt/bin on LETTER — fine enough to
         *  resolve a ~30pt gutter, coarse enough to be robust to per-glyph jitter. */
        const val BIN_COUNT = 60

        /** Center band lower bound (fraction of page width) where a 2-col gutter may sit. */
        const val CENTER_LOW = 0.35

        /** Center band upper bound (fraction of page width). */
        const val CENTER_HIGH = 0.65

        /** Minimum glyphs on a page before detection runs (a near-blank page can't be 2-col). */
        const val MIN_GLYPHS = 40

        /** A center valley deeper than this fraction of the flanking-band coverage is a gutter. */
        const val VALLEY_RATIO_MAX = 0.15

        /** Each flanking column band must carry at least this much coverage to count as a column. */
        const val MIN_FLANK_COVERAGE = 1.0
    }
}
