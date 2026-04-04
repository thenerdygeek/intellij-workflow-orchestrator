package com.workflow.orchestrator.agent.util

/**
 * Unified diff generation for file changes.
 *
 * Ported from Cline's DiffViewProvider pattern: after every file edit,
 * Cline computes the diff between original and modified content and
 * uses it for display and user review.
 *
 * Uses a simple line-by-line diff algorithm (LCS-based).
 * Produces standard unified diff output with @@ hunk headers.
 */
object DiffUtil {

    /**
     * Generate a unified diff between old and new content (string API).
     *
     * @param oldContent the file content before the edit
     * @param newContent the file content after the edit
     * @param filePath path shown in the diff header
     * @param contextLines number of context lines around changes (default 3)
     * @return unified diff string, or empty string if contents are identical
     */
    fun unifiedDiff(
        oldContent: String,
        newContent: String,
        filePath: String,
        contextLines: Int = 3
    ): String {
        if (oldContent == newContent) return ""
        return unifiedDiffFromLines(
            oldLines = oldContent.lines(),
            newLines = newContent.lines(),
            filePath = filePath,
            contextLines = contextLines
        )
    }

    /**
     * Generate a unified diff between old and new line lists.
     *
     * @param oldLines lines of the file before the edit
     * @param newLines lines of the file after the edit
     * @param filePath path shown in the diff header
     * @param contextLines number of context lines around changes (default 3)
     * @return unified diff string, or empty string if contents are identical
     */
    fun unifiedDiffFromLines(
        oldLines: List<String>,
        newLines: List<String>,
        filePath: String,
        contextLines: Int = 3
    ): String {
        if (oldLines == newLines) return ""

        val edits = computeEdits(oldLines, newLines)
        if (edits.all { it.type == EditType.KEEP }) return ""

        return buildDiff(edits, oldLines, newLines, filePath, contextLines)
    }

    // ---- Internal diff algorithm ----

    internal enum class EditType { KEEP, DELETE, INSERT }
    internal data class Edit(val type: EditType, val oldIndex: Int, val newIndex: Int)

    /**
     * Compute edit operations using a simple LCS-based diff.
     * Uses the standard dynamic programming approach (O(n*m) space).
     * For agent-sized diffs (typically < 1000 lines), this is efficient.
     */
    internal fun computeEdits(oldLines: List<String>, newLines: List<String>): List<Edit> {
        val m = oldLines.size
        val n = newLines.size

        // LCS table
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // Backtrack to build edit list
        val edits = mutableListOf<Edit>()
        var i = m
        var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1] -> {
                    edits.add(Edit(EditType.KEEP, i - 1, j - 1))
                    i--; j--
                }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                    edits.add(Edit(EditType.INSERT, i, j - 1))
                    j--
                }
                else -> {
                    edits.add(Edit(EditType.DELETE, i - 1, j))
                    i--
                }
            }
        }
        edits.reverse()
        return edits
    }

    /**
     * Build unified diff output from edits with access to actual line content.
     */
    private fun buildDiff(
        edits: List<Edit>,
        oldLines: List<String>,
        newLines: List<String>,
        filePath: String,
        contextLines: Int
    ): String {
        // Find indices of change edits
        val changeIndices = edits.indices.filter { edits[it].type != EditType.KEEP }
        if (changeIndices.isEmpty()) return ""

        // Group nearby changes into hunks
        data class HunkRange(val startIdx: Int, val endIdx: Int)
        val groups = mutableListOf<HunkRange>()
        var groupStart = changeIndices[0]
        var groupEnd = changeIndices[0]

        for (i in 1 until changeIndices.size) {
            val idx = changeIndices[i]
            if (idx - groupEnd <= 2 * contextLines + 1) {
                groupEnd = idx
            } else {
                groups.add(HunkRange(groupStart, groupEnd))
                groupStart = idx
                groupEnd = idx
            }
        }
        groups.add(HunkRange(groupStart, groupEnd))

        val sb = StringBuilder()
        sb.appendLine("--- a/$filePath")
        sb.appendLine("+++ b/$filePath")

        for (group in groups) {
            val editStart = maxOf(0, group.startIdx - contextLines)
            val editEnd = minOf(edits.size - 1, group.endIdx + contextLines)

            val hunkLines = mutableListOf<String>()
            var oldCount = 0
            var newCount = 0
            var oldStart = -1
            var newStart = -1

            for (idx in editStart..editEnd) {
                val edit = edits[idx]
                when (edit.type) {
                    EditType.KEEP -> {
                        if (oldStart == -1) {
                            oldStart = edit.oldIndex
                            newStart = edit.newIndex
                        }
                        hunkLines.add(" ${oldLines[edit.oldIndex]}")
                        oldCount++
                        newCount++
                    }
                    EditType.DELETE -> {
                        if (oldStart == -1) {
                            oldStart = edit.oldIndex
                            newStart = edit.newIndex
                        }
                        hunkLines.add("-${oldLines[edit.oldIndex]}")
                        oldCount++
                    }
                    EditType.INSERT -> {
                        if (oldStart == -1) {
                            oldStart = edit.oldIndex
                            newStart = edit.newIndex
                        }
                        hunkLines.add("+${newLines[edit.newIndex]}")
                        newCount++
                    }
                }
            }

            if (hunkLines.isNotEmpty()) {
                val hunkOldStart = (oldStart.coerceAtLeast(0)) + 1
                val hunkNewStart = (newStart.coerceAtLeast(0)) + 1
                sb.appendLine("@@ -$hunkOldStart,$oldCount +$hunkNewStart,$newCount @@")
                for (line in hunkLines) {
                    sb.appendLine(line)
                }
            }
        }

        return sb.toString().trimEnd()
    }
}
