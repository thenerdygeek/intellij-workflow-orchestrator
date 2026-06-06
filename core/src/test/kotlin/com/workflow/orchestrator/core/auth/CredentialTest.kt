package com.workflow.orchestrator.core.auth

import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CredentialTest {
    private fun authHeader(c: Credential): String? {
        val b = Request.Builder().url("https://example.com")
        c.applyTo(b)
        return b.build().header("Authorization")
    }

    @Test
    fun `bearer emits Bearer scheme`() {
        assertEquals("Bearer abc", authHeader(Credential.Bearer("abc")))
    }

    @Test
    fun `token emits token scheme`() {
        assertEquals("token abc", authHeader(Credential.Token("abc")))
    }

    @Test
    fun `basic emits base64 Basic`() {
        // "user:pass" -> base64 -> dXNlcjpwYXNz
        assertEquals("Basic dXNlcjpwYXNz", authHeader(Credential.Basic("user", "pass")))
    }

    @Test
    fun `custom sets the named header verbatim`() {
        val b = Request.Builder().url("https://example.com")
        Credential.Custom("X-API-Key", "k123").applyTo(b)
        assertEquals("k123", b.build().header("X-API-Key"))
    }
}
