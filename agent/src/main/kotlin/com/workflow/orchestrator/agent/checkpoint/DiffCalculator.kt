package com.workflow.orchestrator.agent.checkpoint

object DiffCalculator {

    /**
     * LCS DP is O(N*M) TIME; cap each side (post affix-trim) at 5000 lines to bound CPU.
     * Memory is no longer the concern — the two-row DP allocates O(min(N,M)) ints instead
     * of the old full N×M matrix (~400MB for two 10K-line files).
     */
    private const val MAX_LINES_FOR_LCS = 5_000

    /**
     * Count added/removed lines between [baseline] and [current] using LCS.
     *
     * Returns Pair(added, removed):
     *  - added: lines in `current` not matched in the LCS to a line in `baseline`
     *  - removed: lines in `baseline` not matched in the LCS to a line in `current`
     *
     * In-place edit ("foo\nbar" -> "foo\nBAZ") counts as +1/-1 (bar removed, BAZ added).
     *
     * Line endings are normalized (CRLF/CR → LF) before diffing so Windows-persisted baselines
     * and IntelliJ-normalized current files don't produce phantom per-line diffs.
     *
     * Common prefix/suffix lines are trimmed before the LCS so typical edits cost
     * ~edited-region² instead of file². When either trimmed side still exceeds
     * [MAX_LINES_FOR_LCS] the LCS step is skipped and a net-change monotonic upper
     * bound is returned instead (bounds worst-case CPU on huge rewrites).
     */
    fun countDiff(baseline: String, current: String): Pair<Int, Int> {
        val normBaseline = normalize(baseline)
        val normCurrent = normalize(current)
        var a = if (normBaseline.isEmpty()) emptyList() else normBaseline.split("\n")
        var b = if (normCurrent.isEmpty()) emptyList() else normCurrent.split("\n")

        // Common prefix/suffix trim: trimmed lines are common to both sides, so they were
        // always part of the LCS — counts are unchanged, cost scales with the edited region.
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) suffix++
        a = a.subList(prefix, a.size - suffix)
        b = b.subList(prefix, b.size - suffix)

        // Fallback for huge edited regions: skip LCS, report net change as a monotonic upper
        // bound. We cannot tell which lines matched without LCS, so we assume worst-case: the
        // smaller list is fully contained in the larger one's deletions + additions.
        if (a.size > MAX_LINES_FOR_LCS || b.size > MAX_LINES_FOR_LCS) {
            val delta = a.size - b.size
            return if (delta >= 0) (0 to delta) else (-delta to 0)
        }

        val lcsLength = lcsLength(a, b)
        return (b.size - lcsLength) to (a.size - lcsLength)
    }

    /** Normalize CRLF/CR to LF so files persisted on Windows and edited on Unix don't produce phantom diffs. */
    private fun normalize(s: String): String =
        s.replace("\r\n", "\n").replace("\r", "\n")

    /** Two-row DP: same LCS length as the full matrix, O(min(n,m)) memory instead of O(n*m). */
    private fun lcsLength(a: List<String>, b: List<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val (shorter, longer) = if (a.size <= b.size) a to b else b to a
        var prev = IntArray(shorter.size + 1)
        var curr = IntArray(shorter.size + 1)
        for (i in 1..longer.size) {
            for (j in 1..shorter.size) {
                curr[j] = if (longer[i - 1] == shorter[j - 1]) {
                    prev[j - 1] + 1
                } else {
                    maxOf(prev[j], curr[j - 1])
                }
            }
            // After this swap the just-computed row lives in `prev` — so when the loop
            // exits, the FINAL row is read from prev[shorter.size] (hand-traced in tests).
            val t = prev
            prev = curr
            curr = t
        }
        return prev[shorter.size]
    }
}
