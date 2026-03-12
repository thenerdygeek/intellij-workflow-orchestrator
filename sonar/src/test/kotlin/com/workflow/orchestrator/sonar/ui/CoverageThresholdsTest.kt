package com.workflow.orchestrator.sonar.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CoverageThresholdsTest {
    @Test
    fun `high coverage returns GREEN`() {
        assertEquals(CoverageThresholds.GREEN, CoverageThresholds.colorForCoverage(90.0, 80.0, 50.0))
    }
    @Test
    fun `medium coverage returns YELLOW`() {
        assertEquals(CoverageThresholds.YELLOW, CoverageThresholds.colorForCoverage(65.0, 80.0, 50.0))
    }
    @Test
    fun `low coverage returns RED`() {
        assertEquals(CoverageThresholds.RED, CoverageThresholds.colorForCoverage(30.0, 80.0, 50.0))
    }
    @Test
    fun `exact threshold boundary is GREEN`() {
        assertEquals(CoverageThresholds.GREEN, CoverageThresholds.colorForCoverage(80.0, 80.0, 50.0))
    }
    @Test
    fun `custom thresholds respected`() {
        assertEquals(CoverageThresholds.YELLOW, CoverageThresholds.colorForCoverage(95.0, 99.0, 90.0))
    }
}
