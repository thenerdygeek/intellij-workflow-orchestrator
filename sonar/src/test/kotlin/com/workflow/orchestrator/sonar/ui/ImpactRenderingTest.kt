package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.sonar.model.Impact
import com.workflow.orchestrator.sonar.model.ImpactSeverity
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.MappedIssue
import com.workflow.orchestrator.sonar.model.SoftwareQuality
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImpactRenderingTest {

    private fun issue(impacts: List<Impact>) = MappedIssue(
        key = "k", type = IssueType.BUG, severity = IssueSeverity.MAJOR,
        message = "m", rule = "r", filePath = "F.kt",
        startLine = 1, endLine = 1, startOffset = 0, endOffset = 0,
        effort = null, projectKey = "p:k", impacts = impacts
    )

    @Test
    fun `htmlBadge is empty when issue has no impacts`() {
        // Older Sonar (< 9.6) — graceful degradation, no badge in the row.
        assertEquals("", ImpactRendering.htmlBadge(issue(emptyList())))
    }

    @Test
    fun `htmlBadge renders highest-severity impact with 3-letter quality abbreviation`() {
        val html = ImpactRendering.htmlBadge(
            issue(listOf(
                Impact(SoftwareQuality.MAINTAINABILITY, ImpactSeverity.LOW),
                Impact(SoftwareQuality.RELIABILITY, ImpactSeverity.HIGH),
                Impact(SoftwareQuality.SECURITY, ImpactSeverity.MEDIUM),
            ))
        )
        // Highest severity is RELIABILITY/HIGH → REL/HIGH badge
        assertTrue(html.contains("[REL/HIGH]"), "badge should render highest impact, got: $html")
        assertFalse(html.contains("[MNT"), "lower-severity impacts should not render: $html")
    }

    @Test
    fun `htmlBadge collapses BLOCKER above HIGH`() {
        val html = ImpactRendering.htmlBadge(
            issue(listOf(
                Impact(SoftwareQuality.RELIABILITY, ImpactSeverity.HIGH),
                Impact(SoftwareQuality.SECURITY, ImpactSeverity.BLOCKER),
            ))
        )
        assertTrue(html.contains("[SEC/BLOCKER]"), "BLOCKER should outrank HIGH, got: $html")
    }

    @Test
    fun `shortName returns 3-letter abbreviation for known qualities`() {
        assertEquals("REL", ImpactRendering.shortName(SoftwareQuality.RELIABILITY))
        assertEquals("SEC", ImpactRendering.shortName(SoftwareQuality.SECURITY))
        assertEquals("MNT", ImpactRendering.shortName(SoftwareQuality.MAINTAINABILITY))
        assertEquals("?", ImpactRendering.shortName(SoftwareQuality.UNKNOWN))
    }
}
