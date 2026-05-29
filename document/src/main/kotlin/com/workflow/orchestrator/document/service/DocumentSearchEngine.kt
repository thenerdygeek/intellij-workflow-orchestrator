package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentSearchMatch
import com.workflow.orchestrator.core.model.DocumentSearchResult

/**
 * Pure, dependency-free full-text search over already-extracted document content (G-10).
 *
 * Stateless object — no IntelliJ services, no I/O. The store passes in the content string and the
 * two index lookups (`pageAt`, `sectionAt`) as function references so this engine has no knowledge
 * of persistence or the `DocumentIndex` type beyond those projections, keeping it trivially unit-testable.
 *
 * Algorithm (see [run]):
 *  1. Split [query] into whitespace-delimited terms; lowercase everything (case-insensitive).
 *  2. Find every occurrence of EACH term in the content.
 *  3. A "hit" is anchored at an occurrence of the FIRST term; a hit is kept only when every other
 *     term also occurs within ±[contextChars] of that anchor (the all-terms gate). A single-term
 *     query therefore degenerates to a plain substring search.
 *  4. Score each hit: a phrase match (all terms adjacent, in query order, at the anchor) scores
 *     highest; otherwise the score rises as the span enclosing all matched terms tightens
 *     (density). Ties break by document order (earlier offset first).
 *  5. Collapse near-duplicate hits whose anchors fall inside the same window so the same passage
 *     isn't reported repeatedly.
 *  6. Sort by score desc, then offset asc; report the true pre-cap total and the capped slice.
 */
internal object DocumentSearchEngine {

    fun run(
        content: String,
        query: String,
        contextChars: Int,
        resultCap: Int,
        pageAt: (Int) -> Int?,
        sectionAt: (Int) -> String?,
        availableSections: List<String>,
    ): DocumentSearchResult {
        val trimmedQuery = query.trim()
        val terms = trimmedQuery.split(WHITESPACE).filter { it.isNotEmpty() }.map { it.lowercase() }
        if (terms.isEmpty()) {
            return DocumentSearchResult(trimmedQuery, emptyList(), 0, resultCap, availableSections)
        }

        val haystack = content.lowercase()

        // All occurrences of the anchor term (the first query term).
        val anchorTerm = terms.first()
        val anchorPositions = allOccurrences(haystack, anchorTerm)
        if (anchorPositions.isEmpty()) {
            return DocumentSearchResult(trimmedQuery, emptyList(), 0, resultCap, availableSections)
        }

        val phraseLower = terms.joinToString(" ")
        val rawHits = ArrayList<RawHit>()

        for (anchor in anchorPositions) {
            val windowStart = (anchor - contextChars).coerceAtLeast(0)
            val windowEnd = (anchor + anchorTerm.length + contextChars).coerceAtMost(haystack.length)

            // All-terms gate: every other term must appear somewhere in the window.
            var allPresent = true
            var spanMin = anchor
            var spanMax = anchor + anchorTerm.length
            for (term in terms) {
                val at = if (term == anchorTerm) anchor else haystack.indexOf(term, windowStart)
                if (at < 0 || at >= windowEnd) { allPresent = false; break }
                spanMin = minOf(spanMin, at)
                spanMax = maxOf(spanMax, at + term.length)
            }
            if (!allPresent) continue

            // Phrase bonus: the whole query (terms joined by single space) occurs at the anchor.
            val isPhrase = terms.size > 1 && haystack.startsWith(phraseLower, anchor)
            val span = (spanMax - spanMin).coerceAtLeast(1)
            // Higher score = better. Phrase wins outright; otherwise tighter span ⇒ higher score.
            val score = if (isPhrase) PHRASE_SCORE else (SPAN_SCALE.toDouble() / span)

            rawHits.add(RawHit(offset = anchor, score = score, span = span))
        }

        if (rawHits.isEmpty()) {
            return DocumentSearchResult(trimmedQuery, emptyList(), 0, resultCap, availableSections)
        }

        // Collapse hits whose anchors fall within the same context window (same passage reported once).
        // Keep, per cluster, the highest-scoring (then earliest) hit.
        val deduped = dedupeByWindow(rawHits, contextChars)

        val total = deduped.size
        val ranked = deduped.sortedWith(compareByDescending<RawHit> { it.score }.thenBy { it.offset })
        val capped = ranked.take(resultCap)

        val matches = capped.map { hit ->
            DocumentSearchMatch(
                offset = hit.offset,
                page = pageAt(hit.offset),
                section = sectionAt(hit.offset),
                snippet = buildSnippet(content, hit.offset, anchorTerm.length, contextChars),
            )
        }
        return DocumentSearchResult(trimmedQuery, matches, total, resultCap, availableSections)
    }

    /** Non-overlapping left-to-right occurrences of [needle] in [haystack] (both already lowercased). */
    private fun allOccurrences(haystack: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            out.add(i)
            i = haystack.indexOf(needle, i + needle.length)
        }
        return out
    }

    /**
     * Collapses hits clustered within [contextChars] of one another (their snippet windows would
     * overlap) into a single representative — the highest-scoring, earliest hit of the cluster —
     * so the same passage isn't reported multiple times. Operates on document order.
     */
    private fun dedupeByWindow(hits: List<RawHit>, contextChars: Int): List<RawHit> {
        val byOffset = hits.sortedBy { it.offset }
        val out = ArrayList<RawHit>()
        for (hit in byOffset) {
            val last = out.lastOrNull()
            if (last != null && hit.offset - last.offset <= contextChars) {
                // Same passage cluster: keep the better-scoring representative.
                if (hit.score > last.score) out[out.size - 1] = hit
            } else {
                out.add(hit)
            }
        }
        return out
    }

    /**
     * Builds the snippet: a window of [contextChars] chars on each side of the match, snapped OUT to
     * the nearest word boundary so no partial words leak, with the matched region (the anchor term)
     * delimited by `«…»` and any elided edge marked `…`. Uses the ORIGINAL-case [content].
     */
    private fun buildSnippet(content: String, matchOffset: Int, matchLen: Int, contextChars: Int): String {
        val matchEnd = (matchOffset + matchLen).coerceAtMost(content.length)
        var start = (matchOffset - contextChars).coerceAtLeast(0)
        var end = (matchEnd + contextChars).coerceAtMost(content.length)

        // Snap to word boundaries: move start forward to just after a whitespace; end back to just
        // before a whitespace — but never into the match region.
        if (start > 0) {
            val ws = content.lastIndexOf(' ', start)
            // Prefer trimming inward to a clean word start within the window.
            val nextWs = content.indexOf(' ', start)
            if (nextWs in (start + 1)..(matchOffset - 1)) start = nextWs + 1
            else if (ws in 0 until start) start = (ws + 1)
        }
        if (end < content.length) {
            val prevWs = content.lastIndexOf(' ', end)
            if (prevWs in (matchEnd + 1) until end) end = prevWs
        }
        start = start.coerceAtMost(matchOffset)
        end = end.coerceAtLeast(matchEnd)

        val before = content.substring(start, matchOffset)
        val matched = content.substring(matchOffset, matchEnd)
        val after = content.substring(matchEnd, end)

        val leadEllipsis = if (start > 0) "…" else ""
        val tailEllipsis = if (end < content.length) "…" else ""
        return (leadEllipsis + before.trimStart() + "«" + matched + "»" + after.trimEnd() + tailEllipsis)
            .replace(NEWLINES, " ")
            .trim()
    }

    private data class RawHit(val offset: Int, val score: Double, val span: Int)

    private val WHITESPACE = Regex("\\s+")
    private val NEWLINES = Regex("[\\r\\n]+")

    /** Sentinel score guaranteeing phrase hits sort above any span-density score. */
    private const val PHRASE_SCORE = 1_000_000.0

    /** Numerator for the span-density score: score = SPAN_SCALE / span (tighter ⇒ higher). */
    private const val SPAN_SCALE = 10_000
}
