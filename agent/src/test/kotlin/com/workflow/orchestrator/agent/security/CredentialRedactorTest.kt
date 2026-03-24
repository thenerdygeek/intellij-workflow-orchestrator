package com.workflow.orchestrator.agent.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CredentialRedactorTest {

    @Test
    fun `redacts PEM private key blocks`() {
        val input = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----"
        val result = CredentialRedactor.redact(input)
        assertFalse(result.contains("MIIEpAIBAAKCAQEA"))
        assertTrue(result.contains("[REDACTED: private key]"))
    }

    @Test
    fun `redacts EC private key header`() {
        val input = "Found: -----BEGIN EC PRIVATE KEY-----"
        val result = CredentialRedactor.redact(input)
        assertTrue(result.contains("[REDACTED_PRIVATE_KEY]"))
    }

    @Test
    fun `redacts DSA private key header`() {
        val input = "-----BEGIN DSA PRIVATE KEY-----"
        val result = CredentialRedactor.redact(input)
        assertTrue(result.contains("[REDACTED_PRIVATE_KEY]"))
    }

    @Test
    fun `redacts OPENSSH private key header`() {
        val input = "-----BEGIN OPENSSH PRIVATE KEY-----"
        val result = CredentialRedactor.redact(input)
        assertTrue(result.contains("[REDACTED_PRIVATE_KEY]"))
    }

    @Test
    fun `redacts AWS access key`() {
        val input = "Key: AKIAIOSFODNN7EXAMPLE"
        val result = CredentialRedactor.redact(input)
        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"))
        assertTrue(result.contains("[REDACTED: AWS key]"))
    }

    @Test
    fun `redacts GitHub token`() {
        val input = "Token: ghp_ABCDEFghijklmnopqrstuvwxyz0123456789"
        val result = CredentialRedactor.redact(input)
        assertFalse(result.contains("ghp_"))
        assertTrue(result.contains("[REDACTED: GitHub token]"))
    }

    @Test
    fun `redacts Sourcegraph token`() {
        val input = "sgp_abcdef1234567890abcdef1234567890abcdef1234"
        val result = CredentialRedactor.redact(input)
        assertFalse(result.contains("sgp_"))
        assertTrue(result.contains("[REDACTED: Sourcegraph token]"))
    }

    @Test
    fun `redacts API secret key`() {
        val input = "sk-abcdefghijklmnopqrstuvwxyz0123456789"
        val result = CredentialRedactor.redact(input)
        assertFalse(result.contains("sk-abcdefghijklmnopqrstuvwxyz"))
        assertTrue(result.contains("[REDACTED: API key]"))
    }

    // --- New patterns ---

    @Test
    fun `redacts JWT tokens`() {
        val input = "Auth: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val result = CredentialRedactor.redact(input)
        assertFalse(result.contains("eyJhbGciOiJ"))
        assertTrue(result.contains("[REDACTED_JWT]"))
    }

    @Test
    fun `redacts Bearer tokens`() {
        val input = "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6.long_token_value_here"
        val result = CredentialRedactor.redact(input)
        assertTrue(result.contains("Bearer [REDACTED]"))
    }

    @Test
    fun `does not redact short Bearer values`() {
        // Short values (under 20 chars) should not be redacted
        val input = "Bearer shortval"
        val result = CredentialRedactor.redact(input)
        assertEquals(input, result)
    }

    @Test
    fun `redacts Azure keys`() {
        val input = "azure_key=abcdefghijklmnopqrstuvwxyz1234567890"
        val result = CredentialRedactor.redact(input)
        assertTrue(result.contains("[REDACTED_AZURE]"))
        assertFalse(result.contains("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `redacts Azure secrets with various formats`() {
        val input = "AZ_SECRET: \"some-very-long-secret-value-1234567890\""
        val result = CredentialRedactor.redact(input)
        assertTrue(result.contains("[REDACTED_AZURE]"))
    }

    @Test
    fun `redacts Azure token with equals sign`() {
        val input = "AZURE_TOKEN=myverylongazuretokenvalue12345"
        val result = CredentialRedactor.redact(input)
        assertTrue(result.contains("[REDACTED_AZURE]"))
    }

    @Test
    fun `redacts GitLab personal access tokens`() {
        val input = "Token: glpat-abcdefghijklmnopqrstuvwxyz"
        val result = CredentialRedactor.redact(input)
        assertFalse(result.contains("glpat-"))
        assertTrue(result.contains("[REDACTED_GITLAB]"))
    }

    @Test
    fun `redacts Slack bot tokens`() {
        val input = "SLACK_TOKEN=xoxb-123456789012-abcdefghij"
        val result = CredentialRedactor.redact(input)
        assertFalse(result.contains("xoxb-"))
        assertTrue(result.contains("[REDACTED_SLACK]"))
    }

    @Test
    fun `redacts Slack user tokens`() {
        val input = "xoxp-1234567890-abcdefghij"
        val result = CredentialRedactor.redact(input)
        assertTrue(result.contains("[REDACTED_SLACK]"))
    }

    @Test
    fun `redacts Slack app tokens`() {
        val input = "xoxa-1234567890-abcdefghij"
        val result = CredentialRedactor.redact(input)
        assertTrue(result.contains("[REDACTED_SLACK]"))
    }

    // --- Clean output ---

    @Test
    fun `passes clean text unchanged`() {
        val input = "fun hello() = println(\"Hello world\")"
        val result = CredentialRedactor.redact(input)
        assertEquals(input, result)
    }

    @Test
    fun `passes normal code with password variable name`() {
        val input = "val password = getPassword()"
        val result = CredentialRedactor.redact(input)
        assertEquals(input, result)
    }

    @Test
    fun `redacts multiple credentials in same text`() {
        val input = """
            AWS key: AKIAIOSFODNN7EXAMPLE
            GitHub: ghp_ABCDEFghijklmnopqrstuvwxyz0123456789
            GitLab: glpat-abcdefghijklmnopqrstuvwxyz
            Slack: xoxb-123456789012-abcdefghij
        """.trimIndent()
        val result = CredentialRedactor.redact(input)
        assertTrue(result.contains("[REDACTED: AWS key]"))
        assertTrue(result.contains("[REDACTED: GitHub token]"))
        assertTrue(result.contains("[REDACTED_GITLAB]"))
        assertTrue(result.contains("[REDACTED_SLACK]"))
    }
}
