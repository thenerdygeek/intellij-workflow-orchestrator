package com.workflow.orchestrator.handover.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for [HandoverStateService.buildSafeBambooLink] — the Bamboo key
 * validator that guards bambooLink URL assembly.
 * (Audit finding automation:F-9)
 */
class BambooLinkValidationTest {

    private val base = "https://bamboo.example.com"

    // ── Valid keys ────────────────────────────────────────────────────────────

    @Test
    fun `standard two-segment key builds correct link`() {
        val link = HandoverStateService.buildSafeBambooLink(base, "PROJ-PLAN-42")
        assertNotNull(link)
        assertEquals("$base/browse/PROJ-PLAN-42", link)
    }

    @Test
    fun `three-segment key is accepted`() {
        val link = HandoverStateService.buildSafeBambooLink(base, "MYTEAM-BUILD-MAIN-100")
        assertNotNull(link)
        assertEquals("$base/browse/MYTEAM-BUILD-MAIN-100", link)
    }

    @Test
    fun `single-segment prefix with build number is accepted`() {
        val link = HandoverStateService.buildSafeBambooLink(base, "APISMOKE-1")
        assertNotNull(link)
    }

    @Test
    fun `alphanumeric segment with digits is accepted`() {
        val link = HandoverStateService.buildSafeBambooLink(base, "ABC1-BUILD2-99")
        assertNotNull(link)
    }

    // ── Invalid keys — injection patterns ────────────────────────────────────

    @Test
    fun `key with script tag injection is rejected`() {
        assertNull(HandoverStateService.buildSafeBambooLink(base, "PLAN-1\"><script>alert(1)</script>"))
    }

    @Test
    fun `key with spaces is rejected`() {
        assertNull(HandoverStateService.buildSafeBambooLink(base, "PLAN 1 EVIL 42"))
    }

    @Test
    fun `key with dot-dot path traversal is rejected`() {
        assertNull(HandoverStateService.buildSafeBambooLink(base, "PLAN-../../etc-1"))
    }

    @Test
    fun `key with slash is rejected`() {
        assertNull(HandoverStateService.buildSafeBambooLink(base, "PLAN/EVIL-42"))
    }

    @Test
    fun `empty key is rejected`() {
        assertNull(HandoverStateService.buildSafeBambooLink(base, ""))
    }

    @Test
    fun `key with lowercase letters is rejected`() {
        // Only uppercase alnum segments are valid
        assertNull(HandoverStateService.buildSafeBambooLink(base, "plan-build-42"))
    }

    @Test
    fun `key with non-numeric trailing segment is rejected`() {
        // Final segment must be digits only (the build number)
        assertNull(HandoverStateService.buildSafeBambooLink(base, "PLAN-BUILD-BRANCH"))
    }

    @Test
    fun `key with special chars including URL injection chars is rejected`() {
        assertNull(HandoverStateService.buildSafeBambooLink(base, "PLAN-1&evil=1"))
        assertNull(HandoverStateService.buildSafeBambooLink(base, "PLAN-1#fragment"))
        assertNull(HandoverStateService.buildSafeBambooLink(base, "PLAN-1%00"))
        assertNull(HandoverStateService.buildSafeBambooLink(base, "PLAN-1]"))
    }

    @Test
    fun `key with pipe or semicolon is rejected`() {
        assertNull(HandoverStateService.buildSafeBambooLink(base, "PLAN-1|EVIL-2"))
        assertNull(HandoverStateService.buildSafeBambooLink(base, "PLAN-1;EVIL-2"))
    }

    // ── Correct link output ───────────────────────────────────────────────────

    @Test
    fun `bamboo base URL trailing slash is already trimmed by caller`() {
        // Callers trim trailing slash before calling; we test that the output
        // does not double-slash.
        val link = HandoverStateService.buildSafeBambooLink("https://bamboo.example.com", "PROJ-PLAN-1")
        assertNotNull(link)
        assertEquals("https://bamboo.example.com/browse/PROJ-PLAN-1", link)
    }
}
