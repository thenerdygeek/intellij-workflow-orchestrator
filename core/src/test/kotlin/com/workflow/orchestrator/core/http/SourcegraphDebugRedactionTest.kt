package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Regression tests for audit finding core:F-4:
 * sanitizeForDebug and PreSanitizeDumper must redact the Sourcegraph access
 * token before writing to api-debug/ or raw-trace/ files.
 *
 * Tests cover:
 * - [PromptBodyRedactor] redacts Authorization header values (regex pass 1)
 * - [PromptBodyRedactor] redacts `token` / `access_token` JSON fields (regex pass 1)
 * - [PreSanitizeDumper.dump] applies the supplied redactor before writing
 * - Literal-token redactor replaces the exact token string regardless of context
 */
class SourcegraphDebugRedactionTest {

    @TempDir
    lateinit var tempDir: File

    // ── PromptBodyRedactor — Authorization-header value redaction ────────────

    @Test
    fun `PromptBodyRedactor redacts Authorization token scheme in JSON`() {
        // Sourcegraph uses "Authorization: token <value>" — ensure it is caught.
        val input = """{"headers": {"Authorization": "token sgp_my_secret_token"}}"""
        val result = PromptBodyRedactor.redact(input)
        assertFalse(result.contains("sgp_my_secret_token"), "Sourcegraph token must be redacted from Authorization JSON field")
        assertTrue(result.contains("***REDACTED***"), "Redaction marker must appear")
    }

    @Test
    fun `PromptBodyRedactor redacts Bearer token in JSON field`() {
        val input = """{"Authorization": "Bearer sk-ant-api03-secret-1234"}"""
        val result = PromptBodyRedactor.redact(input)
        assertFalse(result.contains("sk-ant-api03-secret-1234"), "Bearer secret must be redacted")
        assertTrue(result.contains("***REDACTED***"), "Redaction marker must appear")
    }

    @Test
    fun `PromptBodyRedactor redacts token JSON field`() {
        val input = """{"token": "sgp_very_long_personal_access_token_value"}"""
        val result = PromptBodyRedactor.redact(input)
        assertFalse(result.contains("sgp_very_long_personal_access_token_value"), "token field value must be redacted")
        assertTrue(result.contains("***REDACTED***"), "Redaction marker must appear")
    }

    @Test
    fun `PromptBodyRedactor redacts access_token JSON field`() {
        val input = """{"access_token": "eyJhbGciOiJIUzI1NiJ9.secret"}"""
        val result = PromptBodyRedactor.redact(input)
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiJ9.secret"), "access_token value must be redacted")
        assertTrue(result.contains("***REDACTED***"), "Redaction marker must appear")
    }

    @Test
    fun `PromptBodyRedactor preserves non-credential fields`() {
        val input = """{"model": "anthropic::claude-3", "max_tokens": 4096, "prompt": "What is 2+2?"}"""
        val result = PromptBodyRedactor.redact(input)
        assertTrue(result.contains("anthropic::claude-3"), "model field must be preserved")
        assertTrue(result.contains("4096"), "max_tokens must be preserved")
        assertTrue(result.contains("What is 2+2?"), "prompt must be preserved")
    }

    // ── Literal-token redactor — exact-match pass ────────────────────────────

    @Test
    fun `literal-token redactor catches token in arbitrary context`() {
        val token = "sgp_abcdef1234567890"
        val input = """{"messages": [{"content": "My token is sgp_abcdef1234567890 do not log it"}]}"""

        // Simulate what buildPreSanitizeRedactor produces
        val redactor: (String) -> String = { text ->
            var r = PromptBodyRedactor.redact(text)
            r = r.replace(token, "***REDACTED***")
            r
        }

        val result = redactor(input)
        assertFalse(result.contains(token), "Literal token must be redacted from arbitrary context")
        assertTrue(result.contains("***REDACTED***"), "Redaction marker must appear")
    }

    @Test
    fun `literal-token redactor handles null token gracefully`() {
        val input = """{"model": "claude-3", "prompt": "hello"}"""

        val redactor: (String) -> String = { text ->
            var r = PromptBodyRedactor.redact(text)
            val tok: String? = null
            if (!tok.isNullOrBlank()) r = r.replace(tok, "***REDACTED***")
            r
        }

        val result = redactor(input)
        assertTrue(result.contains("claude-3"), "Non-credential content preserved when token is null")
        assertFalse(result.contains("***REDACTED***"), "No spurious redaction when token is null")
    }

    // ── PreSanitizeDumper — routes through textRedactor ──────────────────────

    @Test
    fun `PreSanitizeDumper applies textRedactor before writing`() {
        val token = "sgp_secret_pre_sanitize_token"
        val messages = listOf(
            ChatMessage(role = "user", content = "Authorization: token $token — please help"),
            ChatMessage(role = "system", content = """{"token": "$token"}""")
        )
        val reqId = "120000-001"
        val traceDir = File(tempDir, "trace")

        val redactor: (String) -> String = { text ->
            var r = PromptBodyRedactor.redact(text)
            r = r.replace(token, "***REDACTED***")
            r
        }

        PreSanitizeDumper.dump(messages, reqId, traceDir, redactor)

        val file = File(traceDir, "$reqId.pre-sanitize.json")
        assertTrue(file.exists(), "Pre-sanitize file must be created")
        val content = file.readText()
        assertFalse(content.contains(token), "Raw token must not appear in pre-sanitize dump")
        assertTrue(content.contains("***REDACTED***"), "Redaction marker must appear in pre-sanitize dump")
    }

    @Test
    fun `PreSanitizeDumper default redactor (PromptBodyRedactor) catches token JSON field`() {
        val messages = listOf(
            ChatMessage(role = "user", content = """{"token": "sgp_default_redactor_test"}""")
        )
        val reqId = "120001-002"
        val traceDir = File(tempDir, "trace2")

        // Use the default redactor (PromptBodyRedactor::redact)
        PreSanitizeDumper.dump(messages, reqId, traceDir)

        val file = File(traceDir, "$reqId.pre-sanitize.json")
        assertTrue(file.exists(), "Pre-sanitize file must be created")
        val content = file.readText()
        assertFalse(content.contains("sgp_default_redactor_test"), "Token value must be redacted by default redactor")
        assertTrue(content.contains("***REDACTED***"), "Redaction marker must appear")
    }
}
