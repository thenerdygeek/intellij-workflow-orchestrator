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
    fun `assembles single tool call from deltas`() = runTest {
        val chunk1 = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]},"finish_reason":null}]}"""
        val chunk2 = """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]},"finish_reason":null}]}"""
        val chunk3 = """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"src/Foo.kt\"}"}}]},"finish_reason":"tool_calls"}]}"""
        val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}"""

        server.enqueue(sseResponse(chunk1, chunk2, chunk3, usageChunk))

        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "read the file")),
            tools = null,
            onChunk = {}
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val response = (result as ApiResult.Success).data
        assertEquals("tool_calls", response.choices.first().finishReason)
        val toolCalls = response.choices.first().message.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls!!.size)
        assertEquals("read_file", toolCalls[0].function.name)
        assertEquals("call_1", toolCalls[0].id)
        assertTrue(toolCalls[0].function.arguments.contains("src/Foo.kt"))
    }

    @Test
    fun `falls back to non-streaming on tool_calls finish with zero deltas`() = runTest {
        // Lab confirmed: gateway sometimes sends finish_reason=tool_calls with no deltas
        // Code falls back to sendMessage() (non-streaming)
        val streamChunk = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"Using tools."},"finish_reason":"tool_calls"}]}"""

        // First request: streaming — gets tool_calls finish but no deltas
        server.enqueue(sseResponse(streamChunk))
        // Second request: non-streaming fallback
        val nonStreamResponse = """{"id":"c2","choices":[{"index":0,"message":{"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"Foo.kt\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":50,"completion_tokens":10,"total_tokens":60}}"""
        server.enqueue(MockResponse().setBody(nonStreamResponse).setHeader("Content-Type", "application/json"))

        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "read")),
            tools = listOf(ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "read_file",
                    description = "Read a file",
                    parameters = FunctionParameters(properties = emptyMap())
                )
            )),
            onChunk = {}
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val toolCalls = (result as ApiResult.Success).data.choices.first().message.toolCalls
        assertNotNull(toolCalls)
        assertEquals("read_file", toolCalls!![0].function.name)
    }

    @Test
    fun `handles concat JSON bug in parallel tool calls — keeps first`() = runTest {
        // Lab confirmed: gateway concatenates parallel tool calls into index 0
        val chunk1 = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]},"finish_reason":null}]}"""
        val chunk2 = """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"A.kt\"}{\"path\":\"B.kt\"}"}}]},"finish_reason":"tool_calls"}]}"""
        val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":50,"completion_tokens":20,"total_tokens":70}}"""

        server.enqueue(sseResponse(chunk1, chunk2, usageChunk))

        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "read both")),
            tools = null,
            onChunk = {}
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val toolCalls = (result as ApiResult.Success).data.choices.first().message.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls!!.size) // Only first recovered from concat bug
        assertTrue(toolCalls[0].function.arguments.contains("A.kt"))
    }

    @Test
    fun `parses XML tool calls from text content when no native tools in request`() = runTest {
        val xmlContent = "Let me read that.\n\n<read_file>\n<path>Foo.kt</path>\n</read_file>"
        val escapedContent = xmlContent.replace("\"", "\\\"").replace("\n", "\\n")
        val chunk1 = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"$escapedContent"},"finish_reason":"stop"}]}"""
        val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":200,"completion_tokens":30,"total_tokens":230}}"""

        server.enqueue(sseResponse(chunk1, usageChunk))

        // Create client with known tool names for the new parser
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
        assertEquals("Let me read that.", response.choices.first().message.content?.trim())
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
}
