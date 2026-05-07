package com.workflow.orchestrator.agent.tools.integration.sonar

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IssueSeverityTest {

    @Test
    fun `BLOCKER meets BLOCKER threshold`() {
        assertTrue(IssueSeverity.meetsMinSeverity("BLOCKER", "BLOCKER"))
    }

    @Test
    fun `CRITICAL meets MAJOR threshold`() {
        assertTrue(IssueSeverity.meetsMinSeverity("CRITICAL", "MAJOR"))
    }

    @Test
    fun `INFO does not meet MAJOR threshold`() {
        assertFalse(IssueSeverity.meetsMinSeverity("INFO", "MAJOR"))
    }

    @Test
    fun `MINOR does not meet CRITICAL threshold`() {
        assertFalse(IssueSeverity.meetsMinSeverity("MINOR", "CRITICAL"))
    }

    @Test
    fun `MAJOR does not meet CRITICAL threshold`() {
        assertFalse(IssueSeverity.meetsMinSeverity("MAJOR", "CRITICAL"))
    }

    @Test
    fun `CRITICAL does not meet BLOCKER threshold`() {
        assertFalse(IssueSeverity.meetsMinSeverity("CRITICAL", "BLOCKER"))
    }

    @Test
    fun `MINOR does not meet MAJOR threshold`() {
        assertFalse(IssueSeverity.meetsMinSeverity("MINOR", "MAJOR"))
    }

    @Test
    fun `unknown severity is treated as INFO`() {
        // Defensive: future Sonar versions may add new levels. We don't want
        // an unknown severity to silently bypass a strict filter.
        assertFalse(IssueSeverity.meetsMinSeverity("WHATEVER", "MAJOR"))
        assertTrue(IssueSeverity.meetsMinSeverity("WHATEVER", "INFO"))
    }

    @Test
    fun `case-insensitive`() {
        assertTrue(IssueSeverity.meetsMinSeverity("blocker", "major"))
        assertTrue(IssueSeverity.meetsMinSeverity("Critical", "MAJOR"))
    }

    @Test
    fun `null minSeverity admits all`() {
        assertTrue(IssueSeverity.meetsMinSeverity("INFO", null))
    }

    @Test
    fun `blank minSeverity admits all`() {
        assertTrue(IssueSeverity.meetsMinSeverity("INFO", ""))
        assertTrue(IssueSeverity.meetsMinSeverity("INFO", "   "))
    }

    @Test
    fun `validate accepts known severities`() {
        listOf("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO").forEach {
            assertTrue(IssueSeverity.isValid(it))
            assertTrue(IssueSeverity.isValid(it.lowercase()))
        }
    }

    @Test
    fun `validate rejects unknown severities`() {
        assertFalse(IssueSeverity.isValid("HIGH"))
        assertFalse(IssueSeverity.isValid(""))
    }
}
