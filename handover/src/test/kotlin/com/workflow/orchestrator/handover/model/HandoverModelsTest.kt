package com.workflow.orchestrator.handover.model

import com.workflow.orchestrator.core.events.WorkflowEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class HandoverModelsTest {

    @Test
    fun `HandoverState defaults to empty state`() {
        val state = HandoverState()
        assertEquals("", state.ticketId)
        assertEquals("", state.ticketSummary)
        assertNull(state.prUrl)
        assertFalse(state.prCreated)
        assertNull(state.buildStatus)
        assertNull(state.qualityGatePassed)
        assertTrue(state.suiteResults.isEmpty())
        assertFalse(state.copyrightFixed)
        assertFalse(state.jiraCommentPosted)
        assertFalse(state.jiraTransitioned)
        assertFalse(state.todayWorkLogged)
        assertEquals(0L, state.startWorkTimestamp)
    }

    @Test
    fun `SuiteResult tracks running state with null passed`() {
        val result = SuiteResult(
            suitePlanKey = "PROJ-REGR",
            buildResultKey = "PROJ-REGR-42",
            dockerTagsJson = """{"my-service":"1.2.3"}""",
            passed = null,
            durationMs = null,
            triggeredAt = Instant.now(),
            bambooLink = "https://bamboo.example.com/browse/PROJ-REGR-42"
        )
        assertNull(result.passed)
        assertNull(result.durationMs)
    }

    @Test
    fun `SuiteResult tracks completed state`() {
        val result = SuiteResult(
            suitePlanKey = "PROJ-SMOKE",
            buildResultKey = "PROJ-SMOKE-18",
            dockerTagsJson = """{"my-service":"1.2.3"}""",
            passed = true,
            durationMs = 120_000L,
            triggeredAt = Instant.now(),
            bambooLink = "https://bamboo.example.com/browse/PROJ-SMOKE-18"
        )
        assertTrue(result.passed!!)
        assertEquals(120_000L, result.durationMs)
    }

    @Test
    fun `BuildSummary captures build info`() {
        val summary = BuildSummary(
            buildNumber = 42,
            status = WorkflowEvent.BuildEventStatus.SUCCESS,
            planKey = "PROJ-BUILD"
        )
        assertEquals(42, summary.buildNumber)
        assertEquals(WorkflowEvent.BuildEventStatus.SUCCESS, summary.status)
    }

    @Test
    fun `CopyrightFileEntry tracks file copyright status`() {
        val entry = CopyrightFileEntry(
            filePath = "src/main/java/Foo.java",
            status = CopyrightStatus.YEAR_OUTDATED,
            oldYear = "2025",
            newYear = "2025-2026"
        )
        assertEquals(CopyrightStatus.YEAR_OUTDATED, entry.status)
        assertEquals("2025", entry.oldYear)
        assertEquals("2025-2026", entry.newYear)
    }

    @Test
    fun `ReviewFinding sorts by severity`() {
        val findings = listOf(
            ReviewFinding(FindingSeverity.LOW, "a.kt", 10, "minor", "unused-import"),
            ReviewFinding(FindingSeverity.HIGH, "b.kt", 20, "critical", "missing-transactional"),
            ReviewFinding(FindingSeverity.MEDIUM, "c.kt", 30, "moderate", "unclosed-resource")
        )
        val sorted = findings.sortedBy { it.severity.ordinal }
        assertEquals(FindingSeverity.HIGH, sorted[0].severity)
        assertEquals(FindingSeverity.MEDIUM, sorted[1].severity)
        assertEquals(FindingSeverity.LOW, sorted[2].severity)
    }

    @Test
    fun `MacroStep tracks execution state`() {
        val step = MacroStep(
            id = "jira-comment",
            label = "Post Jira Comment",
            enabled = true,
            status = MacroStepStatus.PENDING
        )
        assertEquals(MacroStepStatus.PENDING, step.status)
        assertTrue(step.enabled)
    }
}
