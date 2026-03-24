package com.workflow.orchestrator.agent.runtime

/**
 * Represents a single diff hunk to apply to a file.
 *
 * @param startLine 1-based line number where the old content starts
 * @param oldLines The existing lines to replace (must match the file content)
 * @param newLines The replacement lines
 */
data class DiffHunk(
    val startLine: Int,
    val oldLines: List<String>,
    val newLines: List<String>
)

/**
 * Result of applying a diff hunk to file content.
 *
 * @param content The resulting file content after application (or original on failure)
 * @param applied Whether the hunk was successfully applied
 * @param conflict Whether the hunk could not be matched (mismatch)
 * @param message Human-readable description of what happened
 */
data class ApplyResult(
    val content: String,
    val applied: Boolean,
    val conflict: Boolean = false,
    val message: String = ""
)

/**
 * Parses and applies diff hunks to file content with fuzzy matching.
 *
 * Supports two matching strategies:
 * 1. **Exact match** — checks the specified line number first
 * 2. **Fuzzy match** — searches +/-10 lines from the target for shifted content
 *
 * Returns [ApplyResult] indicating success, offset-adjusted success, or conflict.
 */
object DiffHunkApplier {

    /** Maximum line offset to search when exact match fails. */
    private const val MAX_FUZZY_OFFSET = 10

    /**
     * Apply a single diff hunk to file content.
     *
     * @param fileContent The current file content as a single string
     * @param hunk The diff hunk to apply
     * @return [ApplyResult] with the modified content or conflict info
     */
    fun apply(fileContent: String, hunk: DiffHunk): ApplyResult {
        val lines = fileContent.lines().toMutableList()
        val zeroIdx = hunk.startLine - 1

        // Pure insertion: empty oldLines means insert-only, no matching needed
        if (hunk.oldLines.isEmpty()) {
            val insertIdx = (hunk.startLine - 1).coerceIn(0, lines.size)
            lines.addAll(insertIdx, hunk.newLines)
            return ApplyResult(
                content = lines.joinToString("\n"),
                applied = true,
                message = "Inserted ${hunk.newLines.size} lines at line ${insertIdx + 1}"
            )
        }

        // Exact match first: check if old lines match at the specified position
        if (zeroIdx >= 0 && zeroIdx + hunk.oldLines.size <= lines.size) {
            val slice = lines.subList(zeroIdx, zeroIdx + hunk.oldLines.size)
            if (slice == hunk.oldLines) {
                return applyAtPosition(lines, zeroIdx, hunk)
            }
        }

        // Fuzzy match: search nearby lines (+/-MAX_FUZZY_OFFSET)
        for (offset in 1..MAX_FUZZY_OFFSET) {
            for (candidate in listOf(zeroIdx + offset, zeroIdx - offset)) {
                if (candidate >= 0 && candidate + hunk.oldLines.size <= lines.size) {
                    val slice = lines.subList(candidate, candidate + hunk.oldLines.size)
                    if (slice == hunk.oldLines) {
                        val result = applyAtPosition(lines, candidate, hunk)
                        return result.copy(
                            message = "Applied with offset ${candidate - zeroIdx}"
                        )
                    }
                }
            }
        }

        // No match found — conflict
        return ApplyResult(
            content = fileContent,
            applied = false,
            conflict = true,
            message = "Could not find matching lines at or near line ${hunk.startLine}"
        )
    }

    /**
     * Apply multiple hunks to file content in order.
     * Hunks are applied sequentially; line numbers in later hunks are NOT adjusted
     * for changes made by earlier hunks (caller is responsible for ordering).
     *
     * @param fileContent The current file content
     * @param hunks The list of hunks to apply
     * @return List of [ApplyResult] for each hunk
     */
    fun applyAll(fileContent: String, hunks: List<DiffHunk>): List<ApplyResult> {
        val sortedHunks = hunks.sortedByDescending { it.startLine }
        var currentContent = fileContent
        val results = mutableListOf<ApplyResult>()
        for (hunk in sortedHunks) {
            val result = apply(currentContent, hunk)
            results.add(result)
            if (result.applied) {
                currentContent = result.content
            }
        }
        return results
    }

    /**
     * Apply a hunk at a specific zero-indexed position.
     * Removes old lines and inserts new lines.
     */
    private fun applyAtPosition(lines: MutableList<String>, position: Int, hunk: DiffHunk): ApplyResult {
        // Remove old lines in reverse order to preserve indices
        for (i in hunk.oldLines.indices.reversed()) {
            lines.removeAt(position + i)
        }
        // Insert new lines
        lines.addAll(position, hunk.newLines)
        return ApplyResult(
            content = lines.joinToString("\n"),
            applied = true
        )
    }
}
