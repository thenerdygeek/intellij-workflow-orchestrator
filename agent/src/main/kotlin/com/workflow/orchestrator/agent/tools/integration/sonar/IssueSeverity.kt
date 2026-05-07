package com.workflow.orchestrator.agent.tools.integration.sonar

/**
 * SonarQube issue severity ordering + filter helpers. Used to enforce a
 * `min_severity` parameter on local_analysis. Severities follow Sonar's
 * canonical ordering: BLOCKER (highest) > CRITICAL > MAJOR > MINOR > INFO.
 */
object IssueSeverity {

    private val ORDER = listOf("INFO", "MINOR", "MAJOR", "CRITICAL", "BLOCKER")

    /**
     * Severity levels in display order — highest severity first. Public so
     * UI / output rendering code can iterate severities consistently with
     * the threshold ordering enforced by [meetsMinSeverity], without
     * duplicating the literal list.
     */
    val DISPLAY_ORDER: List<String> = ORDER.asReversed()

    /** Returns true when [severity] matches any of Sonar's known levels. */
    fun isValid(severity: String): Boolean = ORDER.any { it.equals(severity, ignoreCase = true) }

    /**
     * Returns true when [severity] is at or above [minSeverity]. A null /
     * blank [minSeverity] is treated as "no filter" — every severity passes.
     * Unknown severities are conservatively treated as INFO so a stricter
     * filter still excludes them.
     *
     * **Note on asymmetric defaults:** an unknown [severity] falls back to
     * INFO (most strict — gets filtered out by stricter thresholds), but
     * an unknown [minSeverity] returns `true` (no filter). The two
     * fallbacks are intentionally asymmetric: callers are expected to
     * validate `minSeverity` via [isValid] before invoking this function
     * (the `local_analysis` action handler does so), so an unknown
     * threshold here means a future caller skipped that validation. Returning
     * `true` (no filter) is the least surprising behavior in that case.
     */
    fun meetsMinSeverity(severity: String, minSeverity: String?): Boolean {
        if (minSeverity.isNullOrBlank()) return true
        val sevIdx = ORDER.indexOfFirst { it.equals(severity, ignoreCase = true) }
        val minIdx = ORDER.indexOfFirst { it.equals(minSeverity, ignoreCase = true) }
        if (minIdx < 0) return true  // unknown threshold → no filter
        val effectiveSevIdx = if (sevIdx < 0) 0 else sevIdx  // unknown sev → INFO
        return effectiveSevIdx >= minIdx
    }
}
