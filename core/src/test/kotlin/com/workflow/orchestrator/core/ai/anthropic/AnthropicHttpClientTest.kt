package com.workflow.orchestrator.core.ai.anthropic

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory
import kotlin.test.assertIs

/**
 * Behavioural tests for [AnthropicHttpClient] — Phase 4a Task 8.
 *
 * Uses MockWebServer (available in core test classpath) for round-trip assertions.
 * MockWebServer is headless-safe; the `:web` triad used in [AnthropicHttpClient]
 * is also headless-safe (IdeProxy falls back to ProxySelector.getDefault(),
 * IdeTrust.applyTo is a no-op when CertificateManager is unavailable).
 *
 * Tests:
 *  1. Client constructs headless and implements [AnthropicHttpTransport].
 *  2. x-api-key and anthropic-version headers are present on the recorded request.
 *  3. Serialised request body contains no null fields and no sampling parameters.
 *  4. SSE response lines reach the onLine callback.
 *  5. A 529 response maps to ApiResult.Error with type SERVER_ERROR.
 *  6. A 429 response maps to ApiResult.Error with type RATE_LIMITED and parses Retry-After.
 *  7. A 401 response maps to AUTH_FAILED.
 *  8. A 413 response maps to CONTEXT_LENGTH_EXCEEDED.
 *  9. Debug dump file is written when debugDir is set (key must be redacted).
 */
class AnthropicHttpClientTest {

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private fun minimalRequest() = AnthropicRequest(
        model = "claude-opus-4-5",
        messages = listOf(
            AnthropicMessage(role = "user", content = listOf(ContentBlock(type = "text", text = "hi")))
        ),
        maxTokens = 100,
    )

    private fun successResponse(body: String = "data: {\"type\":\"message_stop\"}\n\n") =
        MockResponse().setResponseCode(200).setBody(body)

    // ── 1. Construction / interface ────────────────────────────────────────────

    @Test
    fun `constructs headless and is an AnthropicHttpTransport`() {
        val client = AnthropicHttpClient("https://api.anthropic.com", "test-key")
        assertIs<AnthropicHttpTransport>(client)
    }

    // ── 2. Request headers ─────────────────────────────────────────────────────

    @Test
    fun `records x-api-key and anthropic-version headers on outgoing request`() = runTest {
        val server = MockWebServer()
        server.enqueue(successResponse())
        server.start()
        try {
            val client = AnthropicHttpClient(server.url("").toString().trimEnd('/'), "my-secret-key")
            client.postStream(minimalRequest()) {}

            val recorded = server.takeRequest()
            assertEquals(
                "my-secret-key",
                recorded.getHeader("x-api-key"),
                "x-api-key header must match the apiKey constructor arg",
            )
            assertEquals(
                "2023-06-01",
                recorded.getHeader("anthropic-version"),
                "anthropic-version must be 2023-06-01",
            )
            assertEquals(
                "application/json",
                recorded.getHeader("content-type")?.substringBefore(";")?.trim(),
                "content-type must be application/json",
            )
        } finally {
            server.shutdown()
        }
    }

    // ── 3. No null fields / no sampling params in body ─────────────────────────

    @Test
    fun `request body has no null fields and no sampling parameters`() = runTest {
        val server = MockWebServer()
        server.enqueue(successResponse())
        server.start()
        try {
            val client = AnthropicHttpClient(server.url("").toString().trimEnd('/'), "k")
            client.postStream(minimalRequest()) {}

            val body = server.takeRequest().body.readUtf8()
            // explicitNulls=false must be honoured
            assertFalse(body.contains(":null"), "Serialised body must not contain :null — got: $body")
            // No sampling params may appear in the wire payload (not declared in the DTO)
            assertFalse(body.contains("\"temperature\""), "temperature must not appear in body")
            assertFalse(body.contains("\"top_p\""), "top_p must not appear in body")
            assertFalse(body.contains("\"top_k\""), "top_k must not appear in body")
        } finally {
            server.shutdown()
        }
    }

    // ── 4. SSE lines reach onLine ──────────────────────────────────────────────

    @Test
    fun `SSE response lines are delivered to onLine callback`() = runTest {
        val delta = "{\"type\":\"content_block_delta\",\"index\":0," +
            "\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}"
        val sseBody = buildString {
            appendLine("event: message_start")
            appendLine("data: {\"type\":\"message_start\"}")
            appendLine()
            appendLine("event: content_block_start")
            appendLine("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}")
            appendLine()
            appendLine("event: content_block_delta")
            appendLine("data: $delta")
            appendLine()
            appendLine("event: message_stop")
            appendLine("data: {\"type\":\"message_stop\"}")
        }

        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(sseBody))
        server.start()
        try {
            val client = AnthropicHttpClient(server.url("").toString().trimEnd('/'), "k")
            val lines = mutableListOf<String>()
            val result = client.postStream(minimalRequest()) { lines.add(it) }

            assertIs<ApiResult.Success<Unit>>(result)
            assertTrue(lines.any { it.startsWith("event:") }, "event lines must reach onLine")
            assertTrue(lines.any { it.startsWith("data:") }, "data lines must reach onLine")
        } finally {
            server.shutdown()
        }
    }

    // ── 5. 529 → SERVER_ERROR ──────────────────────────────────────────────────

    @Test
    fun `529 response maps to ApiResult Error with SERVER_ERROR type`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(529)
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}")
        )
        server.start()
        try {
            val client = AnthropicHttpClient(server.url("").toString().trimEnd('/'), "k")
            val result = client.postStream(minimalRequest()) {}

            assertIs<ApiResult.Error>(result)
            assertEquals(
                ErrorType.SERVER_ERROR,
                (result as ApiResult.Error).type,
                "529 must map to SERVER_ERROR",
            )
        } finally {
            server.shutdown()
        }
    }

    // ── 6. 429 → RATE_LIMITED with Retry-After ────────────────────────────────

    @Test
    fun `429 response maps to RATE_LIMITED and parses Retry-After header`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "30")
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}")
        )
        server.start()
        try {
            val client = AnthropicHttpClient(server.url("").toString().trimEnd('/'), "k")
            val result = client.postStream(minimalRequest()) {}

            assertIs<ApiResult.Error>(result)
            val err = result as ApiResult.Error
            assertEquals(ErrorType.RATE_LIMITED, err.type)
            assertEquals(30_000L, err.retryAfterMs, "Retry-After 30 seconds → 30000 ms")
        } finally {
            server.shutdown()
        }
    }

    // ── 7. 401 → AUTH_FAILED ──────────────────────────────────────────────────

    @Test
    fun `401 response maps to AUTH_FAILED`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\"}}")
        )
        server.start()
        try {
            val client = AnthropicHttpClient(server.url("").toString().trimEnd('/'), "k")
            val result = client.postStream(minimalRequest()) {}

            assertIs<ApiResult.Error>(result)
            assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
        } finally {
            server.shutdown()
        }
    }

    // ── 8. 413 → CONTEXT_LENGTH_EXCEEDED ──────────────────────────────────────

    @Test
    fun `413 response maps to CONTEXT_LENGTH_EXCEEDED`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(413)
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"context_length_exceeded\"}}")
        )
        server.start()
        try {
            val client = AnthropicHttpClient(server.url("").toString().trimEnd('/'), "k")
            val result = client.postStream(minimalRequest()) {}

            assertIs<ApiResult.Error>(result)
            assertEquals(ErrorType.CONTEXT_LENGTH_EXCEEDED, (result as ApiResult.Error).type)
        } finally {
            server.shutdown()
        }
    }

    // ── 9. Debug dump redacts x-api-key ───────────────────────────────────────

    @Test
    fun `debug dump writes request file with api key redacted`() = runTest {
        val server = MockWebServer()
        server.enqueue(successResponse())
        server.start()

        val debugDir = createTempDirectory("anthropic-debug-test").toFile()
        try {
            val client = AnthropicHttpClient(
                baseUrl = server.url("").toString().trimEnd('/'),
                apiKey = "super-secret-api-key",
                debugDir = debugDir,
            )
            client.postStream(minimalRequest()) {}

            val files = debugDir.listFiles() ?: emptyArray()
            assertTrue(files.isNotEmpty(), "debugDir must contain at least one dump file")
            val content = files.first().readText()
            assertFalse(
                content.contains("super-secret-api-key"),
                "x-api-key value must not appear in debug dump",
            )
        } finally {
            server.shutdown()
            debugDir.deleteRecursively()
        }
    }
}
