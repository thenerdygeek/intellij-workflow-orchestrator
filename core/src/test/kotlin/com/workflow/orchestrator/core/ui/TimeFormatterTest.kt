package com.workflow.orchestrator.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimeFormatterTest {

    @Test
    fun `formatFileAge returns seconds for under 1 minute`() {
        val now = System.currentTimeMillis()
        assertEquals("0s ago", TimeFormatter.formatFileAge(now))
        assertEquals("5s ago", TimeFormatter.formatFileAge(now - 5_000))
        assertEquals("59s ago", TimeFormatter.formatFileAge(now - 59_000))
    }

    @Test
    fun `formatFileAge returns minutes for 1m to under 1h`() {
        val now = System.currentTimeMillis()
        assertEquals("1m ago", TimeFormatter.formatFileAge(now - 60_000))
        assertEquals("5m ago", TimeFormatter.formatFileAge(now - 5 * 60_000))
        assertEquals("59m ago", TimeFormatter.formatFileAge(now - 59 * 60_000))
    }

    @Test
    fun `formatFileAge returns hours for 1h to under 1d`() {
        val now = System.currentTimeMillis()
        assertEquals("1h ago", TimeFormatter.formatFileAge(now - 60 * 60_000))
        assertEquals("12h ago", TimeFormatter.formatFileAge(now - 12 * 60 * 60_000L))
        assertEquals("23h ago", TimeFormatter.formatFileAge(now - 23 * 60 * 60_000L))
    }

    @Test
    fun `formatFileAge returns days for 1d and up`() {
        val now = System.currentTimeMillis()
        assertEquals("1d ago", TimeFormatter.formatFileAge(now - 24 * 60 * 60_000L))
        assertEquals("7d ago", TimeFormatter.formatFileAge(now - 7 * 24 * 60 * 60_000L))
        assertEquals("365d ago", TimeFormatter.formatFileAge(now - 365 * 24 * 60 * 60_000L))
    }

    @Test
    fun `formatDurationSeconds composes hours minutes seconds`() {
        assertEquals("0s", TimeFormatter.formatDurationSeconds(0))
        assertEquals("0s", TimeFormatter.formatDurationSeconds(-1))
        assertEquals("12s", TimeFormatter.formatDurationSeconds(12))
        assertEquals("1m", TimeFormatter.formatDurationSeconds(60))
        assertEquals("1m 5s", TimeFormatter.formatDurationSeconds(65))
        assertEquals("1h", TimeFormatter.formatDurationSeconds(3600))
        assertEquals("1h 1m 5s", TimeFormatter.formatDurationSeconds(3665))
    }

    @Test
    fun `formatDurationSeconds respects custom zero`() {
        assertEquals("--", TimeFormatter.formatDurationSeconds(0, zero = "--"))
        assertEquals("", TimeFormatter.formatDurationSeconds(0, zero = ""))
    }

    @Test
    fun `formatDurationMillis no hour branch by design`() {
        assertEquals("--", TimeFormatter.formatDurationMillis(0))
        assertEquals("0s", TimeFormatter.formatDurationMillis(500))
        assertEquals("5s", TimeFormatter.formatDurationMillis(5_000))
        assertEquals("1m 5s", TimeFormatter.formatDurationMillis(65_000))
        assertEquals("90m 0s", TimeFormatter.formatDurationMillis(5_400_000))
    }

    @Test
    fun `formatEffortMinutes uses 8-hour work day`() {
        assertEquals("0min", TimeFormatter.formatEffortMinutes(0))
        assertEquals("0min", TimeFormatter.formatEffortMinutes(-5))
        assertEquals("45min", TimeFormatter.formatEffortMinutes(45))
        assertEquals("4h 30min", TimeFormatter.formatEffortMinutes(4 * 60 + 30))
        assertEquals("1d", TimeFormatter.formatEffortMinutes(8 * 60))
        assertEquals("2d 3h", TimeFormatter.formatEffortMinutes(2 * 8 * 60 + 3 * 60))
    }

    @Test
    fun `relative returns just now for under 1 minute`() {
        val now = System.currentTimeMillis()
        assertEquals("just now", TimeFormatter.relative(now - 30_000))
    }

    @Test
    fun `relative empty for non-positive input`() {
        assertEquals("", TimeFormatter.relative(0))
        assertEquals("", TimeFormatter.relative(-1))
    }

    @Test
    fun `relative produces minutes hours days`() {
        val now = System.currentTimeMillis()
        assertEquals("2m ago", TimeFormatter.relative(now - 2 * 60_000))
        assertEquals("3h ago", TimeFormatter.relative(now - 3 * 60 * 60_000L))
        assertEquals("2d ago", TimeFormatter.relative(now - 2 * 24 * 60 * 60_000L))
    }
}
