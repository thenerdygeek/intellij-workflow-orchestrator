package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.sonar.model.GateCondition
import com.workflow.orchestrator.sonar.model.IssueType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GateStatusBannerTest {
    @Test
    fun `mapConditionToFilter maps new_bugs to Bug type`() {
        val cond = GateCondition("new_bugs", "GT", "0", "3", passed = false)
        val filter = GateStatusBanner.mapConditionToFilter(cond)
        assertEquals(IssueType.BUG, filter?.issueType)
        assertTrue(filter?.newCodeMode == true)
    }

    @Test
    fun `mapConditionToFilter maps new_vulnerabilities to Vulnerability type`() {
        val cond = GateCondition("new_vulnerabilities", "GT", "0", "2", passed = false)
        val filter = GateStatusBanner.mapConditionToFilter(cond)
        assertEquals(IssueType.VULNERABILITY, filter?.issueType)
    }

    @Test
    fun `mapConditionToFilter maps new_security_hotspots_reviewed to Hotspot type`() {
        val cond = GateCondition("new_security_hotspots_reviewed", "LT", "100", "75.0", passed = false)
        val filter = GateStatusBanner.mapConditionToFilter(cond)
        assertEquals(IssueType.SECURITY_HOTSPOT, filter?.issueType)
    }

    @Test
    fun `mapConditionToFilter returns coverage filter for coverage conditions`() {
        val cond = GateCondition("new_coverage", "LT", "80", "62.3", passed = false)
        val filter = GateStatusBanner.mapConditionToFilter(cond)
        assertNull(filter?.issueType)
        assertTrue(filter?.isCoverageCondition == true)
    }

    @Test
    fun `mapConditionToFilter returns null for passing conditions`() {
        val cond = GateCondition("new_bugs", "GT", "0", "0", passed = true)
        assertNull(GateStatusBanner.mapConditionToFilter(cond))
    }

    @Test
    fun `formatFailingConditions joins conditions with separator`() {
        val conditions = listOf(
            GateCondition("new_coverage", "LT", "80", "62.3", passed = false),
            GateCondition("new_bugs", "GT", "0", "3", passed = false),
            GateCondition("new_duplicated_lines_density", "GT", "3", "1.2", passed = true)
        )
        val text = GateStatusBanner.formatFailingConditions(conditions)
        assertTrue(text.contains("62.3"))
        assertTrue(text.contains("3"))
        assertFalse(text.contains("1.2")) // passing condition excluded
    }

    @Test
    fun `mapConditionToFilter returns coverage filter for duplication conditions`() {
        val cond = GateCondition("new_duplicated_lines_density", "GT", "3", "5.0", passed = false)
        val filter = GateStatusBanner.mapConditionToFilter(cond)
        assertNull(filter?.issueType)
        assertTrue(filter?.isCoverageCondition == true)
        assertTrue(filter?.newCodeMode == true)
    }

    @Test
    fun `mapConditionToFilter detects non-new-code metrics`() {
        val cond = GateCondition("bugs", "GT", "0", "3", passed = false)
        val filter = GateStatusBanner.mapConditionToFilter(cond)
        assertEquals(IssueType.BUG, filter?.issueType)
        assertFalse(filter?.newCodeMode == true)
    }

    @Test
    fun `formatFailingConditions returns empty string when all pass`() {
        val conditions = listOf(
            GateCondition("new_bugs", "GT", "0", "0", passed = true),
            GateCondition("new_coverage", "LT", "80", "85.0", passed = true)
        )
        val text = GateStatusBanner.formatFailingConditions(conditions)
        assertTrue(text.isEmpty())
    }
}
