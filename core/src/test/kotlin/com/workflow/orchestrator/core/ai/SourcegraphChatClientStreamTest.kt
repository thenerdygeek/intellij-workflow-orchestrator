package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for SourcegraphChatClient.sendMessageStream() — SSE parsing,
 * tool call assembly, usage capture, and edge cases confirmed by
 * streaming lab results (docs/Result_1/).
 */
class SourcegraphChatClientStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SourcegraphChatClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client = SourcegraphChatClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "test-token" },
            model = "test-model",
            httpClientOverride = OkHttpClient.Builder().build()
        )
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun sseResponse(vararg events: String): MockResponse {
        val body = events.joinToString("\n\n") { "data: $it" } + "\n\n"
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    @Test
    fun `streams text content and captures usage without DONE sentinel`() = runTest {
        // Lab confirmed: gateway NEVER sends [DONE], usage always emitted
        val chunk1 = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello "},"finish_reason":null}]}"""
        val chunk2 = """{"id":"c1","choices":[{"index":0,"delta":{"content":"world"},"finish_reason":"stop"}]}"""
        val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""

        server.enqueue(sseResponse(chunk1, chunk2, usageChunk))

        val chunks = mutableListOf<StreamChunk>()
        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            tools = null,
            onChunk = { chunks.add(it) }
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val response = (result as ApiResult.Success).data
        assertEquals("Hello world", response.choices.first().message.content)
        assertEquals("stop", response.choices.first().finishReason)
        assertNotNull(response.usage)
        assertEquals(10, response.usage!!.promptTokens)
        assertEquals(5, response.usage!!.completionTokens)
        assertTrue(chunks.size >= 2, "Should have received streaming chunks")
    }

    @Test
    fun `parses XML tool calls from text content when no native tools in request`() = runTest {
        // XML-in-content migration 2026-05-13: tool calls are parsed from the
        // accumulated assistant text. The full raw text (prose + XML tags) is
        // preserved in content for in-context learning; only toolCalls is
        // extracted for dispatch. The prior behavior of stripping XML from content
        // was removed — the content now includes the XML.
        val xmlContent = "Let me read that.\n\n<read_file>\n<path>Foo.kt</path>\n</read_file>"
        val escapedContent = xmlContent.replace("\"", "\\\"").replace("\n", "\\n")
        val chunk1 = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"$escapedContent"},"finish_reason":"stop"}]}"""
        val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":200,"completion_tokens":30,"total_tokens":230}}"""

        server.enqueue(sseResponse(chunk1, usageChunk))

        val xmlClient = SourcegraphChatClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "test-token" },
            model = "test-model",
            httpClientOverride = OkHttpClient.Builder().build()
        )

        val result = xmlClient.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "read Foo.kt")),
            tools = null,
            onChunk = {},
            knownToolNames = setOf("read_file"),
            knownParamNames = setOf("path")
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val response = (result as ApiResult.Success).data
        assertEquals("tool_calls", response.choices.first().finishReason)
        val toolCalls = response.choices.first().message.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls!!.size)
        assertEquals("read_file", toolCalls[0].function.name)
        assertTrue(toolCalls[0].function.arguments.contains("Foo.kt"))
        // Full raw content (XML inline + prose) is preserved — not stripped.
        val rawContent = response.choices.first().message.content
        assertNotNull(rawContent)
        assertTrue(rawContent!!.contains("Let me read that."),
            "Prose portion must survive in content")
        assertTrue(rawContent.contains("<read_file>"),
            "XML tool call must remain in content for in-context learning")
    }

    @Test
    fun `parses multiple parallel XML tool calls`() = runTest {
        val xmlContent = "Reading both.\n\n<read_file>\n<path>A.kt</path>\n</read_file>\n\n<read_file>\n<path>B.kt</path>\n</read_file>"
        val escapedContent = xmlContent.replace("\"", "\\\"").replace("\n", "\\n")
        val chunk = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"$escapedContent"},"finish_reason":"stop"}]}"""
        val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}"""

        server.enqueue(sseResponse(chunk, usageChunk))

        val xmlClient = SourcegraphChatClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "test-token" },
            model = "test-model",
            httpClientOverride = OkHttpClient.Builder().build()
        )

        val result = xmlClient.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "read both")),
            tools = null,
            onChunk = {},
            knownToolNames = setOf("read_file"),
            knownParamNames = setOf("path")
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val toolCalls = (result as ApiResult.Success).data.choices.first().message.toolCalls
        assertNotNull(toolCalls)
        assertEquals(2, toolCalls!!.size)
        assertTrue(toolCalls[0].function.arguments.contains("A.kt"))
        assertTrue(toolCalls[1].function.arguments.contains("B.kt"))
    }

    @Test
    fun `gateway context-deadline error frame yields finishReason=upstream_timeout`() = runTest {
        // Realistic SSE prefix: a couple of valid chunks, then the gateway error frame
        // (verbatim shape captured from production api-debug response.json), then EOF
        // without a [DONE] marker. Mirrors the actual failure mode for long
        // plan_mode_respond responses.
        val sseBody = buildString {
            append("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"<plan_mode_respond>\\n<response>Step 1...\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\" Step 2...\"}}]}\n\n")
            append("{\"message\":\"context deadline exceeded\",\"type\":\"completion.process_completion\"}\n\n")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "write a long plan")),
            tools = null,
            onChunk = {}
        )

        require(result is ApiResult.Success) { "Expected Success, got $result" }
        val choice = result.data.choices.single()
        assertEquals("upstream_timeout", choice.finishReason)
        // Partial assistant text is preserved as-is (no [TOOL_CALL_TRUNCATED] sentinel).
        assertNotNull(choice.message.content)
        assertFalse(choice.message.content!!.contains("[TOOL_CALL_TRUNCATED]"))
    }
}
