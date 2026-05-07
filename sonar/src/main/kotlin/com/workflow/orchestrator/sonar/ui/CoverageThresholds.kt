package com.workflow.orchestrator.sonar.ui

import com.intellij.ui.JBColor
import com.workflow.orchestrator.core.ui.StatusColors

/**
 * Two-level pass/fail coloring driven by SonarQube quality gate conditions.
 *
 * Sonar's gate is binary — for any metric, the gate either passes or fails
 * relative to its `errorThreshold`. We render the same: green when the value
 * passes the gate, red when it would fail. Returns `null` when no threshold
 * is available (no gate condition for the metric, or unparseable value); the
 * caller is expected to render that as neutral text rather than guess.
 *
 * The previous implementation was a static three-band split (green ≥ 80,
 * yellow ≥ 50, red below) sourced from a user setting. That setting was
 * universal and never matched Sonar's gate (which is per-metric, per-project,
 * and tightens over time as the team adopts Clean as You Code), so the
 * coloring was misleading. Source of truth is now the gate response.
 */
object CoverageThresholds {

    /**
     * @param actualPct the metric's current value (e.g. line coverage %)
     * @param threshold the gate's `errorThreshold` for this metric, or null when no condition exists
     * @param comparator gate comparator from the condition; "LT" for coverage (fail when actual < threshold), "GT" for ratings/violations (fail when actual > threshold). Defaults to "LT" since coverage is the dominant caller.
     * @return SUCCESS / ERROR, or null when no opinion (caller renders neutral)
     */
    fun colorForGateMetric(actualPct: Double, threshold: Double?, comparator: String = "LT"): JBColor? {
        if (threshold == null) return null
        return when (comparator.uppercase()) {
            "LT" -> if (actualPct >= threshold) StatusColors.SUCCESS else StatusColors.ERROR
            "GT" -> if (actualPct <= threshold) StatusColors.SUCCESS else StatusColors.ERROR
            else -> null
        }
    }
}
