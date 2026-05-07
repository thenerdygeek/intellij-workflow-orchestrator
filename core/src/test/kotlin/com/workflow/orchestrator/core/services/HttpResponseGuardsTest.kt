package com.workflow.orchestrator.core.services

import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpResponseGuardsTest {

    @Test
    fun `isSuccess returns true for 2xx`() {
        for (code in 200..299) {
            assertTrue(isSuccess(code), "Expected $code to be success")
        }
    }

    @Test
    fun `isSuccess returns false for 1xx`() {
        assertFalse(isSuccess(100))
        assertFalse(isSuccess(199))
    }

    @Test
    fun `isSuccess returns false for 3xx redirects`() {
        // The whole point: 200..399 used to mask auth-redirect failures.
        for (code in 300..399) {
            assertFalse(isSuccess(code), "Expected $code to NOT be success")
        }
    }

    @Test
    fun `isSuccess returns false for 4xx and 5xx`() {
        assertFalse(isSuccess(400))
        assertFalse(isSuccess(401))
        assertFalse(isSuccess(404))
        assertFalse(isSuccess(409))
        assertFalse(isSuccess(500))
        assertFalse(isSuccess(503))
    }

    @Test
    fun `looksLikeAuthRedirect detects text-html`() {
        val headers = Headers.headersOf("Content-Type", "text/html")
        assertTrue(looksLikeAuthRedirect(headers))
    }

    @Test
    fun `looksLikeAuthRedirect detects text-html with charset suffix`() {
        val headers = Headers.headersOf("Content-Type", "text/html;charset=UTF-8")
        assertTrue(looksLikeAuthRedirect(headers))
    }

    @Test
    fun `looksLikeAuthRedirect is case-insensitive`() {
        val headers = Headers.headersOf("Content-Type", "TEXT/HTML")
        assertTrue(looksLikeAuthRedirect(headers))
    }

    @Test
    fun `looksLikeAuthRedirect is false for application-json`() {
        val headers = Headers.headersOf("Content-Type", "application/json")
        assertFalse(looksLikeAuthRedirect(headers))
    }

    @Test
    fun `looksLikeAuthRedirect is false for empty content-type`() {
        // 204 No Content responses arrive with no Content-Type at all — must not flag as auth redirect.
        val headers = Headers.headersOf()
        assertFalse(looksLikeAuthRedirect(headers))
    }

    @Test
    fun `looksLikeAuthRedirect is false for blank content-type`() {
        val headers = Headers.headersOf("Content-Type", "")
        assertFalse(looksLikeAuthRedirect(headers))
    }
}
