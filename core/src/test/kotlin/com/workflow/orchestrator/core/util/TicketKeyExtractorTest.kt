package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TicketKeyExtractorTest {

    // ── extractFromBranch ───────────────────────────────────────────────────

    @Test
    fun `extracts standard key from branch name`() {
        assertEquals("WO-912", TicketKeyExtractor.extractFromBranch("feature/WO-912-add-button"))
    }

    @Test
    fun `extracts key with digit in project prefix`() {
        assertEquals("AFTER8TE-912", TicketKeyExtractor.extractFromBranch("feature/AFTER8TE-912-add-button"))
    }

    @Test
    fun `returns null when branch has no ticket pattern`() {
        assertNull(TicketKeyExtractor.extractFromBranch("feature/no-ticket-here"))
    }

    @Test
    fun `returns null for single-letter project key`() {
        // Requires >=2 chars before the dash — single letter 'X' should not match.
        assertNull(TicketKeyExtractor.extractFromBranch("bugfix/X-123"))
    }

    @Test
    fun `extracts exact key when branch IS the key`() {
        assertEquals("WO-912", TicketKeyExtractor.extractFromBranch("WO-912"))
    }

    // ── isValidKey ─────────────────────────────────────────────────────────

    @Test
    fun `isValidKey returns true for well-formed key`() {
        assertTrue(TicketKeyExtractor.isValidKey("WO-912"))
    }

    @Test
    fun `isValidKey returns false when key has trailing suffix`() {
        // "WO-912-extra" is not a valid standalone key.
        assertFalse(TicketKeyExtractor.isValidKey("WO-912-extra"))
    }

    @Test
    fun `isValidKey returns false for single-letter project prefix`() {
        assertFalse(TicketKeyExtractor.isValidKey("X-123"))
    }

    @Test
    fun `isValidKey returns true for key with digit in project prefix`() {
        assertTrue(TicketKeyExtractor.isValidKey("AFTER8TE-912"))
    }
}
