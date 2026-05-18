package com.workflow.orchestrator.agent.checkpoint

object DiffCalculator {

    /**
     * Count added/removed lines between [baseline] and [current] using LCS.
     *
     * Returns Pair(added, removed):
     *  - added: lines in `current` not matched in the LCS to a line in `baseline`
     *  - removed: lines in `baseline` not matched in the LCS to a line in `current`
     *
     * In-place edit ("foo\nbar" -> "foo\nBAZ") counts as +1/-1 (bar removed, BAZ added).
     */
    fun countDiff(baseline: String, current: String): Pair<Int, Int> {
        val a = if (baseline.isEmpty()) emptyList() else baseline.split("\n")
        val b = if (current.isEmpty()) emptyList() else current.split("\n")
        val lcsLength = lcs(a, b)
        val removed = a.size - lcsLength
        val added = b.size - lcsLength
        return added to removed
    }

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
