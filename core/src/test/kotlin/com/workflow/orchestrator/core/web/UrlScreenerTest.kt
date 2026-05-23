package com.workflow.orchestrator.core.web

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Assertions.*

class UrlScreenerTest {
    data class Case(
        val name: String,
        val url: String,
        val expectFlag: UrlScreener.Flag? = null,
        val expectReject: WebError? = null,
        val httpsRequired: Boolean = true,
        val allowIpLiteral: Boolean = false,
    )

    private val cases = listOf(
        // ── malformed ────────────────────────────────────────────────────
        Case("empty", "", expectReject = WebError.MalformedUrl("")),
        Case("no scheme", "example.com", expectReject = WebError.MalformedUrl("example.com")),
        Case("garbage", "http://", expectReject = WebError.MalformedUrl("http://")),
        // ── scheme ───────────────────────────────────────────────────────
        Case("https ok", "https://example.com"),
        Case("http rejected when https required", "http://example.com",
             expectReject = WebError.HttpDisallowed("http://example.com")),
        Case("http allowed when https not required", "http://example.com",
             httpsRequired = false),
        Case("ftp rejected", "ftp://example.com",
             expectReject = WebError.HttpDisallowed("ftp://example.com")),
        // ── credentials in URL ───────────────────────────────────────────
        Case("userinfo rejected", "https://user:pass@example.com",
             expectReject = WebError.CredentialsInUrl("https://user:pass@example.com")),
        Case("user-only userinfo rejected", "https://user@example.com",
             expectReject = WebError.CredentialsInUrl("https://user@example.com")),
        // ── IP literals ──────────────────────────────────────────────────
        Case("IPv4 literal rejected by default", "https://203.0.113.1/",
             expectReject = WebError.RawIpLiteral("https://203.0.113.1/")),
        Case("IPv4 literal allowed when toggled", "https://203.0.113.1/",
             allowIpLiteral = true),
        Case("IPv6 literal rejected by default", "https://[2001:db8::1]/",
             expectReject = WebError.RawIpLiteral("https://[2001:db8::1]/")),
        // ── IDN / Punycode ───────────────────────────────────────────────
        Case("ASCII passes without flag", "https://example.com"),
        Case("Cyrillic homograph flagged",
             "https://gооgle.com",   // contains Cyrillic о
             expectFlag = UrlScreener.Flag.IDN_HOMOGRAPH),
        Case("Pure Unicode (not homograph) gets IDN flag only",
             "https://日本.example",
             expectFlag = UrlScreener.Flag.IDN_NON_ASCII),
        // ── shortener detection ──────────────────────────────────────────
        Case("bit.ly flagged as shortener", "https://bit.ly/abc",
             expectFlag = UrlScreener.Flag.SHORTENER),
        Case("t.co flagged as shortener", "https://t.co/abc",
             expectFlag = UrlScreener.Flag.SHORTENER),
        Case("tinyurl flagged as shortener", "https://tinyurl.com/abc",
             expectFlag = UrlScreener.Flag.SHORTENER),
        Case("non-shortener host not flagged", "https://docs.python.org/3"),
        // ── TLD classification ───────────────────────────────────────────
        Case("normal TLD not flagged", "https://example.com"),
        Case("suspicious TLD .tk flagged", "https://example.tk",
             expectFlag = UrlScreener.Flag.SUSPICIOUS_TLD),
        Case("suspicious TLD .zip flagged", "https://example.zip",
             expectFlag = UrlScreener.Flag.SUSPICIOUS_TLD),
        Case("suspicious TLD .click flagged", "https://example.click",
             expectFlag = UrlScreener.Flag.SUSPICIOUS_TLD),
    )

    @TestFactory
    fun screen() = cases.map { c ->
        DynamicTest.dynamicTest(c.name) {
            val result = UrlScreener.screen(
                c.url,
                httpsRequired = c.httpsRequired,
                allowIpLiteral = c.allowIpLiteral,
            )
            if (c.expectReject != null) {
                assertTrue(result is UrlScreener.Result.Reject,
                           "Expected reject for ${c.url}, got $result")
                assertEquals(c.expectReject.code,
                             (result as UrlScreener.Result.Reject).error.code)
            } else {
                assertTrue(result is UrlScreener.Result.Pass,
                           "Expected pass for ${c.url}, got $result")
                val pass = result as UrlScreener.Result.Pass
                if (c.expectFlag != null) {
                    assertTrue(pass.flags.contains(c.expectFlag),
                               "Expected flag ${c.expectFlag} for ${c.url}, got ${pass.flags}")
                }
            }
        }
    }
}
