package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Fix A — SSE idle timeout.
 *
 * Probe `sse_keepalive` (streaming_lab_results.json) verdict: NO_KEEPALIVE. The
 * Sourcegraph gateway never emits `: keep-alive` comment bytes during a slow
 * generation. Combined with the LiteLLM #20347-class "200 OK + silent stream
 * close" pattern, this means a stream can stay quiet for many seconds without
 * any signal that the upstream is dead.
 *
 * Fix: the client should treat an SSE stream that goes idle for more than
 * `sseIdleTimeoutMs` as `finishReason=upstream_timeout`, surfacing through the
 * same recovery branch as the explicit gateway error frame (AgentLoop Stage 3.6).
 *
 * Tests below inject a short idle timeout (200ms) so they run fast.
 */
class SourcegraphChatClientSseIdleTimeoutTest {

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

    private fun buildClient(idleTimeoutMs: Long): SourcegraphChatClient =
        SourcegraphChatClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "test-token" },
            model = "test-model",
            httpClientOverride = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS) // longer than idle to prove idle fires first
                .build(),
            sseIdleTimeoutMs = idleTimeoutMs,
        )

    @Test
    fun `idle SSE stream with no events surfaces as upstream_timeout finish reason`() = runTest {
        // Server sends 200 + SSE Content-Type, but holds the body open by delaying
        // delivery. With body delay >>> idle timeout, the reader blocks waiting for
        // bytes and the watchdog must fire before any chunk lands. Even-the-headers
        // shape: setBody must be non-empty so the body stream stays open while delayed
        // (empty body short-circuits to EOF immediately).
        val padding = "data: {\"id\":\"never-arrives\",\"choices\":[]}\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(padding)
                .setBodyDelay(5, TimeUnit.SECONDS)
                .setSocketPolicy(SocketPolicy.KEEP_OPEN)
        )

        val client = buildClient(idleTimeoutMs = 200L)
        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            tools = null,
            onChunk = {}
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val response = (result as ApiResult.Success).data
        assertEquals(
            "upstream_timeout",
            response.choices.first().finishReason,
            "Idle stream must map to upstream_timeout so AgentLoop Stage 3.6 fires instead of empty Case C"
        )
    }

    @Test
    fun `idle SSE after partial content surfaces as upstream_timeout`() = runTest {
        // Server emits one delta then stalls. Body is delayed so the chunk arrives
        // after the idle timeout, but headers arrive immediately. The watchdog should
        // cancel before the chunk lands, classifying the stream as upstream_timeout.
        val chunk = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"partial..."},"finish_reason":null}]}"""
        val body = "data: $chunk\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
                .setBodyDelay(2, TimeUnit.SECONDS) // body waits longer than the idle timeout
                .setSocketPolicy(SocketPolicy.KEEP_OPEN)
        )

        val client = buildClient(idleTimeoutMs = 200L)
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
            "Idle stream that stays quiet past the timeout must classify as upstream_timeout"
        )
    }

    @Test
    fun `normal stream completes within idle timeout window without false trigger`() = runTest {
        // Sanity check: a normal fast stream finishes before the idle timeout
        // would fire, so the watchdog must not break legitimate completions.
        val chunk = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        val usage = """{"id":"c1","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}"""
        val body = "data: $chunk\n\ndata: $usage\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )

        val client = buildClient(idleTimeoutMs = 500L)
        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            tools = null,
            onChunk = {}
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val response = (result as ApiResult.Success).data
        assertEquals("stop", response.choices.first().finishReason)
        assertTrue(
            response.choices.first().message.content?.contains("ok") == true,
            "Fast stream must complete cleanly, watchdog must not false-trigger"
        )
    }
}
