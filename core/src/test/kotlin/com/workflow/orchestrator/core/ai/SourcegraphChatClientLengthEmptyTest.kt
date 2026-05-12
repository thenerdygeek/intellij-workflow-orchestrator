package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Fix B — `finish_reason="length"` with empty content should classify as
 * `upstream_timeout`, not as an empty Case C response.
 *
 * Source: research report `docs/research/2026-05-12-empty-response-deep-research.md`
 * §2.3 (Thinking budget exhaustion). Probe `truncated_tool_call` confirms
 * Sourcegraph returns `finish_reason="length"` (not `"max_tokens"`) on
 * truncation. When the model consumes all tokens inside extended thinking and
 * emits no visible content, we currently mis-classify it as a normal empty —
 * which drops into Case C retry loop and never recovers. Routing through
 * `upstream_timeout` reuses the existing Stage 3.6 recovery path (chunk-smaller
 * nudge) which is the recommendation in Anthropic's own stop-reason docs.
 *
 * When `length` arrives with usable content, the existing length-truncation
 * recovery in AgentLoop Stage 3.5 is still the right path; only the empty-
 * content shape gets re-routed.
 */
class SourcegraphChatClientLengthEmptyTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun buildClient(): SourcegraphChatClient =
        SourcegraphChatClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "test-token" },
            model = "test-model",
            httpClientOverride = OkHttpClient.Builder().build(),
        )

    private fun sseResponse(vararg events: String): MockResponse {
        val body = events.joinToString("\n\n") { "data: $it" } + "\n\n"
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    @Test
    fun `streaming length finish with empty content remaps to upstream_timeout`() = runTest {
        // Sourcegraph sends finish_reason=length but no content delta at all
        // (thinking-budget exhaustion pattern from claude-code #51568).
        val onlyFinish = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":"length"}]}"""
        val usage = """{"id":"c1","choices":[],"usage":{"prompt_tokens":50000,"completion_tokens":0,"total_tokens":50000}}"""
        server.enqueue(sseResponse(onlyFinish, usage))

        val client = buildClient()
        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "do work")),
            tools = null,
            onChunk = {}
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val response = (result as ApiResult.Success).data
        assertEquals(
            "upstream_timeout",
            response.choices.first().finishReason,
            "length + empty content + no tool calls must remap to upstream_timeout so Stage 3.6 chunks smaller instead of polluting context"
        )
    }

    @Test
    fun `streaming length finish with content keeps length finishReason`() = runTest {
        // When the model actually emitted text before hitting max_tokens, keep
        // the `length` classification so AgentLoop Stage 3.5 (continue-from-cutoff
        // nudge) fires, not Stage 3.6.
        val chunkWithContent = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"Here is the start of a long answer"},"finish_reason":"length"}]}"""
        val usage = """{"id":"c1","choices":[],"usage":{"prompt_tokens":50,"completion_tokens":100,"total_tokens":150}}"""
        server.enqueue(sseResponse(chunkWithContent, usage))

        val client = buildClient()
        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "long response please")),
            tools = null,
            onChunk = {}
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val response = (result as ApiResult.Success).data
        assertEquals(
            "length",
            response.choices.first().finishReason,
            "length + content stays as length so the continue-from-cutoff nudge fires"
        )
    }

    @Test
    fun `non-streaming length finish with empty content remaps to upstream_timeout`() = runTest {
        // Same shape but via non-streaming path. Mirrors the streaming behavior so
        // both endpoints route identically through Stage 3.6 recovery.
        val responseBody = """{"id":"r1","choices":[{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"length"}],"usage":{"prompt_tokens":50000,"completion_tokens":0,"total_tokens":50000}}"""
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody)
        )

        val client = buildClient()
        val result = client.sendMessage(
            messages = listOf(ChatMessage(role = "user", content = "do work")),
            tools = null,
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val response = (result as ApiResult.Success).data
        assertEquals(
            "upstream_timeout",
            response.choices.first().finishReason,
            "Non-streaming length + empty must also remap so recovery paths stay symmetric"
        )
    }
}
