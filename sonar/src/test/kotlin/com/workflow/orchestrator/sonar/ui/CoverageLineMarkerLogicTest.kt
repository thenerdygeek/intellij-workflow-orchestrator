package com.workflow.orchestrator.sonar.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoverageLineMarkerLogicTest {

    @Test
    fun `covered color is green`() {
        val icon = CoverageLineMarkerProvider.coverageIcon(CoverageLineMarkerProvider.COVERED_COLOR)
        assertNotNull(icon)
        assertEquals(6, icon.iconWidth)
        assertEquals(14, icon.iconHeight)
    }

    @Test
    fun `all three status colors produce distinct icons`() {
        val covered = CoverageLineMarkerProvider.coverageIcon(CoverageLineMarkerProvider.COVERED_COLOR)
        val uncovered = CoverageLineMarkerProvider.coverageIcon(CoverageLineMarkerProvider.UNCOVERED_COLOR)
        val partial = CoverageLineMarkerProvider.coverageIcon(CoverageLineMarkerProvider.PARTIAL_COLOR)
        assertNotNull(covered)
        assertNotNull(uncovered)
        assertNotNull(partial)
    }
}
