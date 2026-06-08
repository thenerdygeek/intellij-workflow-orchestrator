package com.workflow.orchestrator.agent.ui

/**
 * Pure model-name version ordering for the model picker (Phase 3 cut — extracted from the local
 * functions inside `AgentController.loadModelList`). Dependency-free (operates on model-name
 * strings) so the picker's generation-grouping + newest-first ordering is unit-testable.
 */
object ModelVersionOrdering {

    private val THINKING = Regex("(?i)-thinking")
    private val LATEST = Regex("(?i)-latest")
    private val DATE_SUFFIX = Regex("-\\d{8}$")
    private val DIGITS = Regex("\\d+")

    /**
     * Strip `-thinking`, `-latest`, and an 8-digit date suffix so that
     * "claude-opus-4-5-thinking-latest" and "claude-opus-4-5" share the same version key for
     * generation-grouping purposes.
     */
    fun versionKey(modelName: String): String =
        modelName
            .replace(THINKING, "")
            .replace(LATEST, "")
            .replace(DATE_SUFFIX, "")
            .trimEnd('-')

    /**
     * Extract digit groups from the version key for numerical version comparison.
     * "claude-opus-4-6" → [4, 6]; "claude-3-opus" → [3]; "claude-opus-4-5" → [4, 5].
     */
    fun versionNums(modelName: String): List<Int> =
        DIGITS.findAll(versionKey(modelName)).map { it.value.toInt() }.toList()

    /**
     * Compare two model names by version number (ascending), NOT by `created` timestamp.
     * Using `created` is unreliable because Sourcegraph refreshes the timestamp on "-latest" alias
     * models after newer numbered releases, causing the alias to sort above the genuinely newer
     * model. A missing trailing version group is treated as 0.
     */
    fun compareByVersionAsc(a: String, b: String): Int {
        val va = versionNums(a)
        val vb = versionNums(b)
        for (i in 0 until maxOf(va.size, vb.size)) {
            val diff = va.getOrElse(i) { 0 } - vb.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}
