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
}
