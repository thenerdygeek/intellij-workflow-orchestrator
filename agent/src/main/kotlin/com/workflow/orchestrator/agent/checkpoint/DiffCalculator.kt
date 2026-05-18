package com.workflow.orchestrator.agent.checkpoint

object DiffCalculator {

    /** LCS DP becomes O(N*M) memory; cap each side at 5000 lines to prevent ~400MB allocations. */
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
     * When either side exceeds [MAX_LINES_FOR_LCS] the LCS step is skipped and a net-change
     * monotonic upper bound is returned instead (avoids ~400MB DP table allocations).
     */
    fun countDiff(baseline: String, current: String): Pair<Int, Int> {
        val normBaseline = normalize(baseline)
        val normCurrent = normalize(current)
        val a = if (normBaseline.isEmpty()) emptyList() else normBaseline.split("\n")
        val b = if (normCurrent.isEmpty()) emptyList() else normCurrent.split("\n")

        // Fallback for large files: skip LCS, report net change as a monotonic upper bound.
        // We cannot tell which lines matched without LCS, so we assume worst-case: the
        // smaller list is fully contained in the larger one's deletions + additions.
        if (a.size > MAX_LINES_FOR_LCS || b.size > MAX_LINES_FOR_LCS) {
            val delta = a.size - b.size
            return if (delta >= 0) (0 to delta) else (-delta to 0)
        }

        val lcsLength = lcs(a, b)
        val removed = a.size - lcsLength
        val added = b.size - lcsLength
        return added to removed
    }

    /** Normalize CRLF/CR to LF so files persisted on Windows and edited on Unix don't produce phantom diffs. */
    private fun normalize(s: String): String =
        s.replace("\r\n", "\n").replace("\r", "\n")

    /** Returns length of longest common subsequence of [a] and [b]. */
    private fun lcs(a: List<String>, b: List<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1
                           else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        return dp[a.size][b.size]
    }
}
