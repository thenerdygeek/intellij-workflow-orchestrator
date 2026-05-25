package com.workflow.orchestrator.sonar.api

/**
 * Canonical SonarQube metric keys.
 *
 * These string constants are the exact metric identifiers SonarQube's Web API
 * recognises (e.g. on `/api/measures/component_tree`, `/api/measures/component`,
 * and the `metricKeys=` query parameter). They were previously duplicated as raw
 * literals across [SonarApiClient], `SonarDataService`, `CoverageMapper`, and
 * `SonarServiceImpl` (audit finding sonar:F-17). Centralising them removes the
 * drift risk; the values themselves are unchanged so the strings sent to the
 * server stay byte-identical.
 *
 * CSV metric-key sets are built by joining these constants — see
 * [SonarApiClient.DEFAULT_METRIC_KEYS] / [SonarApiClient.PROJECT_HEALTH_METRIC_KEYS].
 */
object SonarMetricKey {
    // ── Coverage (overall) ───────────────────────────────────────────────
    const val COVERAGE = "coverage"
    const val LINE_COVERAGE = "line_coverage"
    const val BRANCH_COVERAGE = "branch_coverage"
    const val UNCOVERED_LINES = "uncovered_lines"
    const val UNCOVERED_CONDITIONS = "uncovered_conditions"
    const val LINES_TO_COVER = "lines_to_cover"

    // ── Coverage (new code) ──────────────────────────────────────────────
    const val NEW_COVERAGE = "new_coverage"
    const val NEW_LINE_COVERAGE = "new_line_coverage"
    const val NEW_BRANCH_COVERAGE = "new_branch_coverage"
    const val NEW_UNCOVERED_LINES = "new_uncovered_lines"
    const val NEW_UNCOVERED_CONDITIONS = "new_uncovered_conditions"
    const val NEW_LINES_TO_COVER = "new_lines_to_cover"

    // ── Issue counts (overall) ───────────────────────────────────────────
    const val BUGS = "bugs"
    const val VULNERABILITIES = "vulnerabilities"
    const val CODE_SMELLS = "code_smells"

    // ── Issue counts (new code) ──────────────────────────────────────────
    const val NEW_BUGS = "new_bugs"
    const val NEW_VULNERABILITIES = "new_vulnerabilities"
    const val NEW_CODE_SMELLS = "new_code_smells"

    // ── Maintainability / ratings / complexity ───────────────────────────
    const val SQALE_INDEX = "sqale_index"
    const val SQALE_RATING = "sqale_rating"
    const val SQALE_DEBT_RATIO = "sqale_debt_ratio"
    const val RELIABILITY_RATING = "reliability_rating"
    const val SECURITY_RATING = "security_rating"
    const val COMPLEXITY = "complexity"
    const val COGNITIVE_COMPLEXITY = "cognitive_complexity"

    // ── Duplication ──────────────────────────────────────────────────────
    const val DUPLICATED_LINES_DENSITY = "duplicated_lines_density"
    const val NEW_DUPLICATED_LINES = "new_duplicated_lines"
    const val NEW_DUPLICATED_LINES_DENSITY = "new_duplicated_lines_density"

    // ── Size ─────────────────────────────────────────────────────────────
    const val NCLOC = "ncloc"

    /** Joins keys into the comma-separated form SonarQube's `metricKeys=` param expects. */
    fun csv(vararg keys: String): String = keys.joinToString(",")
}
