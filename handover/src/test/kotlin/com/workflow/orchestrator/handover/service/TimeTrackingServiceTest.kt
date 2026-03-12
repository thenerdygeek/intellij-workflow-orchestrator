package com.workflow.orchestrator.handover.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TimeTrackingServiceTest {

    private val service = TimeTrackingService()

    @Test
    fun `validateHours accepts valid hours`() {
        assertTrue(service.validateHours(1.0))
        assertTrue(service.validateHours(4.0))
        assertTrue(service.validateHours(7.0))
        assertTrue(service.validateHours(0.5))
    }

    @Test
    fun `validateHours rejects zero`() {
        assertFalse(service.validateHours(0.0))
    }

    @Test
    fun `validateHours rejects negative`() {
        assertFalse(service.validateHours(-1.0))
    }

    @Test
    fun `validateHours clamps to max 7h`() {
        assertFalse(service.validateHours(8.0))
        assertFalse(service.validateHours(7.5))
    }

    @Test
    fun `hoursToSeconds converts correctly`() {
        assertEquals(3600, service.hoursToSeconds(1.0))
        assertEquals(7200, service.hoursToSeconds(2.0))
        assertEquals(1800, service.hoursToSeconds(0.5))
        assertEquals(25200, service.hoursToSeconds(7.0))
    }

    @Test
    fun `formatStartedDate produces ISO 8601`() {
        val result = service.formatStartedDate(2026, 3, 12, 9, 0)
        assertTrue(result.contains("2026-03-12"))
        assertTrue(result.contains("09:00:00"))
    }

    @Test
    fun `clampHours reduces values above 7`() {
        assertEquals(7.0, service.clampHours(8.0))
        assertEquals(7.0, service.clampHours(10.0))
    }

    @Test
    fun `clampHours passes through valid values`() {
        assertEquals(4.0, service.clampHours(4.0))
        assertEquals(0.5, service.clampHours(0.5))
    }

    @Test
    fun `isFutureDate rejects tomorrow`() {
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        assertTrue(service.isFutureDate(tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth))
    }

    @Test
    fun `isFutureDate accepts today`() {
        val today = java.time.LocalDate.now()
        assertFalse(service.isFutureDate(today.year, today.monthValue, today.dayOfMonth))
    }

    @Test
    fun `isFutureDate accepts yesterday`() {
        val yesterday = java.time.LocalDate.now().minusDays(1)
        assertFalse(service.isFutureDate(yesterday.year, yesterday.monthValue, yesterday.dayOfMonth))
    }

    @Test
    fun `computeElapsedHours returns correct duration`() {
        val now = System.currentTimeMillis()
        val twoHoursAgo = now - (2 * 3600 * 1000)
        val elapsed = service.computeElapsedHours(twoHoursAgo, now)
        assertEquals(2.0, elapsed, 0.1)
    }

    @Test
    fun `computeElapsedHours returns 0 for zero timestamp`() {
        val elapsed = service.computeElapsedHours(0L, System.currentTimeMillis())
        assertEquals(0.0, elapsed)
    }
}
