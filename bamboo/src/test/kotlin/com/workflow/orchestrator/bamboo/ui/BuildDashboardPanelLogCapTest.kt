package com.workflow.orchestrator.bamboo.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the build-log soft-cap helper in [BuildDashboardPanel] (§8.7).
 *
 * [BuildDashboardPanel.capLogForDisplay] is a pure function that truncates oversized
 * log strings to a tail of [BuildDashboardPanel.LOG_RENDER_CAP_BYTES] bytes, preventing
 * EDT jank when rendering large CI logs (repo CI logs hit 405 KB on the production server).
 *
 * These tests run without any IntelliJ platform infra.
 */
class BuildDashboardPanelLogCapTest {

    @Test
    fun `capLogForDisplay returns unchanged text when within cap`() {
        val text = "a".repeat(100)
        val result = BuildDashboardPanel.capLogForDisplay(text, maxBytes = 200)
        assertEquals(text, result)
    }

    @Test
    fun `capLogForDisplay returns unchanged text when exactly at cap`() {
        val text = "b".repeat(200)
        val result = BuildDashboardPanel.capLogForDisplay(text, maxBytes = 200)
        assertEquals(text, result)
    }

    @Test
    fun `capLogForDisplay trims leading content when over cap`() {
        // 300 chars, cap at 200 → last 200 chars should appear
        val head = "H".repeat(100)
        val tail = "T".repeat(200)
        val text = head + tail
        val result = BuildDashboardPanel.capLogForDisplay(text, maxBytes = 200)
        assertTrue(result.endsWith(tail), "Result should end with the tail content")
        assertFalse(result.startsWith(head), "Result should not start with the head content")
    }

    @Test
    fun `capLogForDisplay includes omission warning when truncated`() {
        val text = "X".repeat(500)
        val result = BuildDashboardPanel.capLogForDisplay(text, maxBytes = 200)
        assertTrue(result.contains("omitted"), "Result should contain omission notice")
        assertTrue(result.contains("KB"), "Result should mention KB in omission notice")
    }

    @Test
    fun `capLogForDisplay preserves exact tail length after truncation`() {
        val text = "A".repeat(1000)
        val cap = 400
        val result = BuildDashboardPanel.capLogForDisplay(text, maxBytes = cap)
        // The result is a prefix banner + "\n" + exactly cap chars of tail
        assertTrue(result.endsWith("A".repeat(cap)),
            "Last $cap chars of result should be the tail")
    }

    @Test
    fun `capLogForDisplay with default constant works on 405 KB log`() {
        // Simulate a 405 KB log (from bundle-repo CI build on the production server)
        val bigLog = "line content with spaces and chars\n".repeat(12000)  // ~420 KB
        val result = BuildDashboardPanel.capLogForDisplay(bigLog)
        assertTrue(result.length <= BuildDashboardPanel.LOG_RENDER_CAP_BYTES + 200,
            "Result should not significantly exceed the cap (banner is small overhead)")
        assertTrue(result.contains("omitted"), "Should contain omission notice")
    }

    @Test
    fun `capLogForDisplay empty string stays empty`() {
        val result = BuildDashboardPanel.capLogForDisplay("", maxBytes = 200)
        assertEquals("", result)
    }
}
