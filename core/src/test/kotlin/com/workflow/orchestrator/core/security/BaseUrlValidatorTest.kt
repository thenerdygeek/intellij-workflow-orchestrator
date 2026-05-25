package com.workflow.orchestrator.core.security

import com.workflow.orchestrator.core.security.BaseUrlValidator.ValidationResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BaseUrlValidator].
 *
 * The DNS-resolution path is exercised by passing literal IP addresses — no real DNS lookup
 * is made for the REJECT cases (literal match fires first) and the ACCEPT cases use hostnames
 * that the system will attempt to resolve; if offline they fall back to [ValidationResult.SoftWarning].
 *
 * Closes audit finding core:F-12, jira:F-4, bamboo:F-4, sonar:F-3.
 */
class BaseUrlValidatorTest {

    // ── Scheme rejections ──────────────────────────────────────────────────────

    @Test fun `file scheme is rejected`() {
        assertInvalid(BaseUrlValidator.validate("file:///etc/passwd"))
    }

    @Test fun `gopher scheme is rejected`() {
        assertInvalid(BaseUrlValidator.validate("gopher://x"))
    }

    @Test fun `ftp scheme is rejected`() {
        assertInvalid(BaseUrlValidator.validate("ftp://x"))
    }

    @Test fun `blank url is rejected`() {
        assertInvalid(BaseUrlValidator.validate(""))
    }

    @Test fun `whitespace-only url is rejected`() {
        assertInvalid(BaseUrlValidator.validate("   "))
    }

    @Test fun `http with no host is rejected`() {
        // "http://" parses to a URI with blank host
        assertInvalid(BaseUrlValidator.validate("http://"))
    }

    @Test fun `https with no host is rejected`() {
        assertInvalid(BaseUrlValidator.validate("https://"))
    }

    // ── Loopback rejections ────────────────────────────────────────────────────

    @Test fun `127_0_0_1 is rejected`() {
        assertInvalid(BaseUrlValidator.validate("http://127.0.0.1:8080"))
    }

    @Test fun `127_0_0_1 loopback with port is rejected`() {
        assertInvalid(BaseUrlValidator.validate("http://127.0.0.1:8080/jira"))
    }

    @Test fun `localhost is rejected`() {
        assertInvalid(BaseUrlValidator.validate("http://localhost:8080"))
    }

    @Test fun `localhost https is also rejected`() {
        assertInvalid(BaseUrlValidator.validate("https://localhost/api"))
    }

    // ── Link-local / AWS IMDS rejections ──────────────────────────────────────

    @Test fun `169_254_169_254 AWS IMDS is rejected`() {
        assertInvalid(BaseUrlValidator.validate("http://169.254.169.254/"))
    }

    @Test fun `169_254 range is rejected`() {
        assertInvalid(BaseUrlValidator.validate("http://169.254.1.1/"))
    }

    // ── RFC 1918 private LAN rejections ───────────────────────────────────────

    @Test fun `10_x private LAN is rejected`() {
        assertInvalid(BaseUrlValidator.validate("http://10.1.2.3/"))
    }

    @Test fun `192_168 private LAN is rejected`() {
        assertInvalid(BaseUrlValidator.validate("http://192.168.1.1/"))
    }

    @Test fun `172_16 private LAN is rejected`() {
        assertInvalid(BaseUrlValidator.validate("http://172.16.0.1/"))
    }

    @Test fun `172_31 private LAN is rejected`() {
        assertInvalid(BaseUrlValidator.validate("http://172.31.255.254/"))
    }

    // ── IPv6 rejections ────────────────────────────────────────────────────────

    @Test fun `IPv6 loopback bracket form is rejected`() {
        assertInvalid(BaseUrlValidator.validate("http://[::1]/"))
    }

    // core:F-3 — IPv6 link-local with a percent-encoded scope id (%25 = "%").
    // Must be rejected whether the URI parser chokes on the scope id (malformed)
    // or parses it through to the fe80::/10 link-local check.
    @Test fun `IPv6 link-local with scope id is rejected`() {
        assertInvalid(BaseUrlValidator.validate("http://[fe80::1%25eth0]/"))
    }

    // ── Malformed URLs ─────────────────────────────────────────────────────────

    @Test fun `completely invalid URL is rejected`() {
        assertInvalid(BaseUrlValidator.validate("not a url"))
    }

    // ── Valid corporate URLs — accepted ────────────────────────────────────────
    // These use well-known public domains. On CI without DNS they may produce
    // SoftWarning; both Valid and SoftWarning mean "do NOT reject".

    @Test fun `https jira company com is accepted`() {
        assertNotInvalid(BaseUrlValidator.validate("https://jira.company.com/"))
    }

    @Test fun `http jira company com with port is accepted`() {
        assertNotInvalid(BaseUrlValidator.validate("http://jira.company.com:8080/"))
    }

    @Test fun `https bamboo corp example is accepted`() {
        assertNotInvalid(BaseUrlValidator.validate("https://bamboo.corp.example/bamboo/"))
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun assertInvalid(result: ValidationResult) {
        assertTrue(result is ValidationResult.Invalid, "Expected Invalid but got $result")
    }

    private fun assertNotInvalid(result: ValidationResult) {
        assertFalse(result is ValidationResult.Invalid, "Expected Valid or SoftWarning but got $result")
    }
}
