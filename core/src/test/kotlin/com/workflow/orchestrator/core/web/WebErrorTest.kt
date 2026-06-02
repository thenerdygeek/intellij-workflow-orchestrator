package com.workflow.orchestrator.core.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class WebErrorTest {
    @Test
    fun `MalformedUrl is non-recoverable with stable code`() {
        val e = WebError.MalformedUrl("http://bad")
        assertEquals("MALFORMED_URL", e.code)
        assertFalse(e.recoverable)
        assertTrue(e.message.contains("http://bad"))
    }

    @Test
    fun `HttpStatus 5xx is recoverable, 4xx is not`() {
        assertTrue(WebError.HttpStatus(503, "https://x").recoverable)
        assertFalse(WebError.HttpStatus(404, "https://x").recoverable)
    }

    @Test
    fun `UrlBlocked carries the SSRF reason in its code`() {
        val e = WebError.UrlBlocked(
            reason = com.workflow.orchestrator.core.security.UrlSafetyGuard.Reason.IPV4_LOOPBACK,
            host = "localhost"
        )
        assertEquals("URL_BLOCKED_IPV4_LOOPBACK", e.code)
    }

    @Test
    fun `connect error carries proxy guidance`() {
        val e = WebError.HttpConnectError("https://x.test")
        assertEquals("HTTP_CONNECT_FAILED", e.code)
        assertTrue(e.recoverable)
        assertTrue(e.message.contains("proxy", ignoreCase = true))
    }

    @Test
    fun `dns and tls and read errors exist with stable codes`() {
        assertEquals("HTTP_DNS_FAILED", WebError.HttpDnsError("https://x.test").code)
        assertEquals("HTTP_TLS_FAILED", WebError.HttpTlsError("https://x.test").code)
        assertEquals("HTTP_READ_TIMEOUT", WebError.HttpReadTimeout("https://x.test").code)
        assertEquals("HTTP_ERROR", WebError.HttpError("https://x.test", "java.io.IOException").code)
    }

    @Test
    fun `egress screener unavailable is recoverable`() {
        assertEquals("EGRESS_SCREENER_UNAVAILABLE", WebError.EgressScreenerUnavailable.code)
        assertTrue(WebError.EgressScreenerUnavailable.recoverable)
    }
}
