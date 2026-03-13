package com.workflow.orchestrator.jira.vcs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TimeTrackingLogicTest {

    @Test
    fun `formats minutes into display format`() {
        assertEquals("2h 30m", TimeTrackingLogic.formatJiraTime(150))
        assertEquals("1h 0m", TimeTrackingLogic.formatJiraTime(60))
        assertEquals("0h 30m", TimeTrackingLogic.formatJiraTime(30))
        assertEquals("0h 0m", TimeTrackingLogic.formatJiraTime(0))
    }

    @Test
    fun `calculates elapsed minutes from timestamp`() {
        val now = System.currentTimeMillis()
        val twoHoursAgo = now - (2 * 60 * 60 * 1000)
        val elapsed = TimeTrackingLogic.elapsedMinutes(twoHoursAgo, now)
        assertEquals(120, elapsed)
    }

    @Test
    fun `clamps elapsed time to max hours`() {
        val now = System.currentTimeMillis()
        val tenHoursAgo = now - (10 * 60 * 60 * 1000)
        val clamped = TimeTrackingLogic.clampMinutes(
            TimeTrackingLogic.elapsedMinutes(tenHoursAgo, now),
            maxHours = 7.0f
        )
        assertEquals(420, clamped)
    }

    @Test
    fun `returns 0 when no start timestamp`() {
        assertEquals(0, TimeTrackingLogic.elapsedMinutes(0, System.currentTimeMillis()))
    }

    @Test
    fun `builds Jira worklog time spent string`() {
        assertEquals("2h 30m", TimeTrackingLogic.toJiraTimeSpent(150))
        assertEquals("1h", TimeTrackingLogic.toJiraTimeSpent(60))
        assertEquals("30m", TimeTrackingLogic.toJiraTimeSpent(30))
    }

    @Test
    fun `handles zero minutes`() {
        assertEquals("0m", TimeTrackingLogic.toJiraTimeSpent(0))
    }
}
