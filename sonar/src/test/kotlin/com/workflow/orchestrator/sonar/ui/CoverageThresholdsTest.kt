package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.core.ui.StatusColors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CoverageThresholdsTest {
    @Test
    fun `high coverage returns SUCCESS`() {
        assertEquals(StatusColors.SUCCESS, CoverageThresholds.colorForCoverage(90.0, 80.0, 50.0))
    }
    @Test
    fun `medium coverage returns WARNING`() {
        assertEquals(StatusColors.WARNING, CoverageThresholds.colorForCoverage(65.0, 80.0, 50.0))
    }
    @Test
    fun `low coverage returns ERROR`() {
        assertEquals(StatusColors.ERROR, CoverageThresholds.colorForCoverage(30.0, 80.0, 50.0))
    }
    @Test
    fun `exact threshold boundary is SUCCESS`() {
        assertEquals(StatusColors.SUCCESS, CoverageThresholds.colorForCoverage(80.0, 80.0, 50.0))
    }
    @Test
    fun `custom thresholds respected`() {
        assertEquals(StatusColors.WARNING, CoverageThresholds.colorForCoverage(95.0, 99.0, 90.0))
    }
}
