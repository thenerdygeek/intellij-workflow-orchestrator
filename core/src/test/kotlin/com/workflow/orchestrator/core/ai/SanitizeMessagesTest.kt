package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for SourcegraphChatClient.sanitizeMessages() — verifies that
 * native tool results (with toolCallId) are preserved as role=tool,
 * XML-based tool results (without toolCallId) are converted to user role,
 * and tool messages are never merged together.
 *
 * Since sanitizeMessages() is private, we test indirectly by sending
 * messages through sendMessageStream() and inspecting the serialized
 * request body captured by MockWebServer.
 */
class SanitizeMessagesTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SourcegraphChatClient
    private val json = Json { ignoreUnknownKeys = true }

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

    private fun minimalStreamResponse(): MockResponse {
        val chunk = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        val usage = """{"id":"c1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":1,"total_tokens":11}}"""
        return sseResponse(chunk, usage)
    }

    /**
     * Extract the messages array from the request body sent to the mock server.
     */
    private fun captureRequestMessages(): List<ChatMessage> {
        val request = server.takeRequest()
        val bodyStr = request.body.readUtf8()
        val parsed = json.decodeFromString<ChatCompletionRequest>(bodyStr)
        return parsed.messages
    }

    @Test
    fun `tool message WITH toolCallId is preserved as role=tool`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "read the file"),
            ChatMessage(
                role = "assistant",
                content = "\u200B",
                toolCalls = listOf(
                    ToolCall(id = "call_1", function = FunctionCall(name = "read_file", arguments = """{"path":"Foo.kt"}"""))
                )
            ),
            ChatMessage(role = "tool", content = "file contents here", toolCallId = "call_1"),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        // Find the tool message in the sent request
        val toolMsg = sent.find { it.role == "tool" }
        assertNotNull(toolMsg, "Native tool message with toolCallId should be preserved as role=tool")
        assertEquals("call_1", toolMsg!!.toolCallId)
        assertEquals("file contents here", toolMsg.content)
    }

    @Test
    fun `tool message WITHOUT toolCallId is converted to user role`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "do something"),
            ChatMessage(role = "assistant", content = "sure"),
            ChatMessage(role = "tool", content = "xml tool output", toolCallId = null),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        // No tool-role messages should remain
        val toolMsgs = sent.filter { it.role == "tool" }
        assertTrue(toolMsgs.isEmpty(), "XML-based tool result (no toolCallId) should be converted to user role")

        // Should appear as user message with TOOL RESULT prefix
        val userMsgs = sent.filter { it.role == "user" }
        val toolResultMsg = userMsgs.find { it.content?.contains("TOOL RESULT:") == true }
        assertNotNull(toolResultMsg, "XML tool result should be converted to user message with TOOL RESULT prefix")
        assertTrue(toolResultMsg!!.content!!.contains("xml tool output"))
    }

    @Test
    fun `tool message with blank toolCallId is converted to user role`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "do something"),
            ChatMessage(role = "assistant", content = "sure"),
            ChatMessage(role = "tool", content = "blank id result", toolCallId = ""),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        val toolMsgs = sent.filter { it.role == "tool" }
        assertTrue(toolMsgs.isEmpty(), "Tool message with blank toolCallId should be converted to user role")
    }

    @Test
    fun `multiple tool messages are NOT merged together`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "read two files"),
            ChatMessage(
                role = "assistant",
                content = "\u200B",
                toolCalls = listOf(
                    ToolCall(id = "call_1", function = FunctionCall(name = "read_file", arguments = """{"path":"A.kt"}""")),
                    ToolCall(id = "call_2", function = FunctionCall(name = "read_file", arguments = """{"path":"B.kt"}"""))
                )
            ),
            ChatMessage(role = "tool", content = "contents of A", toolCallId = "call_1"),
            ChatMessage(role = "tool", content = "contents of B", toolCallId = "call_2"),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        // Both tool messages should be preserved separately
        val toolMsgs = sent.filter { it.role == "tool" }
        assertEquals(2, toolMsgs.size, "Multiple tool messages must NOT be merged — each has its own toolCallId")
        assertEquals("call_1", toolMsgs[0].toolCallId)
        assertEquals("call_2", toolMsgs[1].toolCallId)
        assertEquals("contents of A", toolMsgs[0].content)
        assertEquals("contents of B", toolMsgs[1].content)
    }

    @Test
    fun `mixed native and XML tool results are handled correctly`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "do things"),
            ChatMessage(
                role = "assistant",
                content = "\u200B",
                toolCalls = listOf(
                    ToolCall(id = "call_1", function = FunctionCall(name = "read_file", arguments = """{"path":"A.kt"}"""))
                )
            ),
            ChatMessage(role = "tool", content = "native result", toolCallId = "call_1"),
            ChatMessage(role = "assistant", content = "and now XML"),
            ChatMessage(role = "tool", content = "xml result", toolCallId = null),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        // Native tool result should be preserved
        val toolMsgs = sent.filter { it.role == "tool" }
        assertEquals(1, toolMsgs.size, "Only the native tool result should remain as role=tool")
        assertEquals("call_1", toolMsgs[0].toolCallId)

        // XML tool result should be converted to user
        val userMsgs = sent.filter { it.content?.contains("TOOL RESULT:") == true }
        assertEquals(1, userMsgs.size, "XML tool result should become a user message")
        assertTrue(userMsgs[0].content!!.contains("xml result"))
    }

    @Test
    fun `pending system content is flushed before native tool message`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "start"),
            ChatMessage(
                role = "assistant",
                content = "\u200B",
                toolCalls = listOf(
                    ToolCall(id = "call_1", function = FunctionCall(name = "read_file", arguments = """{"path":"A.kt"}"""))
                )
            ),
            ChatMessage(role = "system", content = "system instruction"),
            ChatMessage(role = "tool", content = "tool output", toolCallId = "call_1"),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        // System content should be flushed as a user message before the tool message
        val toolIdx = sent.indexOfFirst { it.role == "tool" }
        assertTrue(toolIdx > 0, "Tool message should exist in output")

        // There should be a user message with system_instructions before the tool message
        val systemUserMsg = sent.find { it.content?.contains("system_instructions") == true && it.content?.contains("system instruction") == true }
        assertNotNull(systemUserMsg, "System content should be flushed before native tool message")
        val systemIdx = sent.indexOf(systemUserMsg)
        assertTrue(systemIdx < toolIdx, "System message should appear before the tool message")
    }
}
