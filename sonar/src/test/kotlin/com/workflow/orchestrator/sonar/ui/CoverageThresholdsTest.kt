package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.core.ui.StatusColors
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Pins the gate-driven two-level coloring on `CoverageThresholds`. The user
 * setting that previously drove a 80/50 three-band split is gone — Sonar's
 * gate threshold is now the source of truth.
 */
class CoverageThresholdsTest {

    @Test
    fun `coverage at-or-above threshold is SUCCESS (LT comparator)`() {
        // Sonar gate: coverage LT 95.5 → fail when actual < 95.5
        assertEquals(StatusColors.SUCCESS, CoverageThresholds.colorForGateMetric(96.0, 95.5))
        assertEquals(StatusColors.SUCCESS, CoverageThresholds.colorForGateMetric(95.5, 95.5),
            "boundary value passes the gate")
    }

    @Test
    fun `coverage below threshold is ERROR (LT comparator)`() {
        assertEquals(StatusColors.ERROR, CoverageThresholds.colorForGateMetric(94.7, 95.5))
        assertEquals(StatusColors.ERROR, CoverageThresholds.colorForGateMetric(0.0, 95.5))
    }

    @Test
    fun `null threshold returns null so caller renders neutral`() {
        assertNull(CoverageThresholds.colorForGateMetric(85.0, null),
            "no gate condition for the metric → no opinion")
    }

    @Test
    fun `GT comparator inverts pass-fail direction`() {
        // GT is used for ratings/violation counters: e.g. bugs GT 5 → fail when bugs > 5
        assertEquals(StatusColors.SUCCESS, CoverageThresholds.colorForGateMetric(3.0, 5.0, "GT"))
        assertEquals(StatusColors.SUCCESS, CoverageThresholds.colorForGateMetric(5.0, 5.0, "GT"),
            "boundary at threshold passes")
        assertEquals(StatusColors.ERROR, CoverageThresholds.colorForGateMetric(7.0, 5.0, "GT"))
    }

    @Test
    fun `unknown comparator returns null`() {
        assertNull(CoverageThresholds.colorForGateMetric(85.0, 80.0, "EQ"))
    }

    @Test
    fun `comparator is case-insensitive`() {
        assertEquals(StatusColors.SUCCESS, CoverageThresholds.colorForGateMetric(96.0, 95.5, "lt"))
        assertEquals(StatusColors.SUCCESS, CoverageThresholds.colorForGateMetric(3.0, 5.0, "gt"))
    }
}
