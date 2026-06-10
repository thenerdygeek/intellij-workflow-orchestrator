package com.workflow.orchestrator.agent.checkpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiffCalculatorTest {

    @Test
    fun `identical text returns 0 added 0 removed`() {
        val (a, r) = DiffCalculator.countDiff("a\nb\nc", "a\nb\nc")
        assertEquals(0, a); assertEquals(0, r)
    }

    @Test
    fun `pure addition counts as added only`() {
        val (a, r) = DiffCalculator.countDiff("a\nb", "a\nb\nc\nd")
        assertEquals(2, a); assertEquals(0, r)
    }

    @Test
    fun `pure removal counts as removed only`() {
        val (a, r) = DiffCalculator.countDiff("a\nb\nc\nd", "a\nb")
        assertEquals(0, a); assertEquals(2, r)
    }

    @Test
    fun `in-place replacement counts as 1 added 1 removed per line`() {
        val (a, r) = DiffCalculator.countDiff("foo\nbar", "foo\nBAZ")
        assertEquals(1, a); assertEquals(1, r)
    }

    @Test
    fun `empty baseline vs content counts every line as added`() {
        val (a, r) = DiffCalculator.countDiff("", "x\ny\nz")
        assertEquals(3, a); assertEquals(0, r)
    }

    @Test
    fun `mixed line endings normalize to the same diff`() {
        val (a1, r1) = DiffCalculator.countDiff("foo\r\nbar\r\nbaz", "foo\nbar\nbaz")
        assertEquals(0, a1); assertEquals(0, r1)

        val (a2, r2) = DiffCalculator.countDiff("foo\rbar\rbaz", "foo\nbar\nbaz")
        assertEquals(0, a2); assertEquals(0, r2)
    }

    @Test
    fun `over cap returns line-count fallback without OOM`() {
        // Post affix-trim these inputs reduce to small lists and take the EXACT LCS path,
        // but the counts are identical to the old cap-fallback answers, so the pins hold.
        val big = "x\n".repeat(10_000)             // 10,000 lines
        val same = "x\n".repeat(10_000)
        val (a, r) = DiffCalculator.countDiff(big, same)
        assertEquals(0, a); assertEquals(0, r)

        val bigger = "x\n".repeat(10_500)
        val (a2, r2) = DiffCalculator.countDiff(big, bigger)
        assertEquals(500, a2); assertEquals(0, r2)
    }

    @Test
    fun `one-line change in a 6000-line file trims under the cap and reports exact counts`() {
        // Old behavior: 6000 lines > MAX_LINES_FOR_LCS hit the net-delta fallback → (0, 0).
        // Affix trim reduces the edited region to ~1 line per side → exact (1, 1).
        val baseLines = (1..6_000).map { "line $it" }
        val baseline = baseLines.joinToString("\n")
        val current = baseLines.toMutableList().also { it[2_999] = "CHANGED" }.joinToString("\n")
        val (a, r) = DiffCalculator.countDiff(baseline, current)
        assertEquals(1, a); assertEquals(1, r)
    }

    @Test
    fun `identical 10k-line strings return zero zero`() {
        val text = (1..10_000).joinToString("\n") { "unique line $it" }
        val (a, r) = DiffCalculator.countDiff(text, text)
        assertEquals(0, a); assertEquals(0, r)
    }

    @Test
    fun `over cap with no common affix still uses net-delta fallback`() {
        // First and last lines differ on both sides, so the trim removes nothing and the
        // 5001-line side exceeds MAX_LINES_FOR_LCS → monotonic net-delta upper bound.
        val baseline = (1..5_001).joinToString("\n") { "L$it" }
        val currentLines = (1..5_001).map { "L$it" }.toMutableList().also {
            it[0] = "X"
            it[it.size - 1] = "Y"
            it.removeAt(2_500)
        }
        val (a, r) = DiffCalculator.countDiff(baseline, currentLines.joinToString("\n"))
        assertEquals(0, a); assertEquals(1, r)
    }

    // ── Hand-traced pins for the two-row LCS (final row must land in `prev` after the swap) ──

    @Test
    fun `single middle-line change counts 1 added 1 removed`() {
        val (a, r) = DiffCalculator.countDiff("a\nb\nc", "a\nX\nc")
        assertEquals(1, a); assertEquals(1, r)
    }

    @Test
    fun `empty baseline vs one line counts 1 added`() {
        val (a, r) = DiffCalculator.countDiff("", "a")
        assertEquals(1, a); assertEquals(0, r)
    }

    @Test
    fun `one line vs empty counts 1 removed`() {
        val (a, r) = DiffCalculator.countDiff("a", "")
        assertEquals(0, a); assertEquals(1, r)
    }

    @Test
    fun `two-line reorder counts 1 added 1 removed`() {
        // LCS([a,b],[b,a]) = 1 — exercises the DP row swap with no trimmable affix.
        val (a, r) = DiffCalculator.countDiff("a\nb", "b\na")
        assertEquals(1, a); assertEquals(1, r)
    }

    @Test
    fun `mid-list match with no common affix hand-traces to 2 added 2 removed`() {
        // prefix=0 (a≠b), suffix=0 (d≠e); LCS([a,b,c,d],[b,a,c,e]) = 2 ([a,c] or [b,c]).
        val (a, r) = DiffCalculator.countDiff("a\nb\nc\nd", "b\na\nc\ne")
        assertEquals(2, a); assertEquals(2, r)
    }

    @Test
    fun `asymmetric sizes exercise the shorter-longer swap`() {
        // LCS([a,b,c],[c,b]) = 1 → added = 2-1, removed = 3-1.
        val (a, r) = DiffCalculator.countDiff("a\nb\nc", "c\nb")
        assertEquals(1, a); assertEquals(2, r)
    }
}
