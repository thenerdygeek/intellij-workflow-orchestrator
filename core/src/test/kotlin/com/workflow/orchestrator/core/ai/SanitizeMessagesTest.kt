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
 * Tests for SourcegraphChatClient.sanitizeMessages() — verifies that messages
 * are converted to a format the Sourcegraph gateway actually accepts.
 *
 * Constraints proven by streaming_lab.py probe:
 * - role="tool" is REJECTED (400: "invalid value for MessageRole: tool")
 * - role="system" is REJECTED (400: "system role is not supported")
 * - Only role="user" and role="assistant" are accepted
 * - tool_calls on assistant messages may be rejected when tools=null
 * - Unknown fields (e.g., "reasoning") may be rejected
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

    // ── Tool role is always converted to user ──────────────────────────

    @Test
    fun `tool message WITH toolCallId is converted to user role`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "read the file"),
            ChatMessage(
                role = "assistant",
                content = "I'll read that",
                toolCalls = listOf(
                    ToolCall(id = "call_1", function = FunctionCall(name = "read_file", arguments = """{"path":"Foo.kt"}"""))
                )
            ),
            ChatMessage(role = "tool", content = "file contents here", toolCallId = "call_1"),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        // No role="tool" messages should exist — Sourcegraph rejects them
        val toolMsgs = sent.filter { it.role == "tool" }
        assertTrue(toolMsgs.isEmpty(), "role='tool' must NEVER be sent — Sourcegraph rejects it with 400")

        // Should appear as user message with TOOL RESULT prefix
        val toolResultMsg = sent.find { it.content?.contains("TOOL RESULT:") == true }
        assertNotNull(toolResultMsg, "Tool result should be converted to user message with TOOL RESULT prefix")
        assertEquals("user", toolResultMsg!!.role)
        assertTrue(toolResultMsg.content!!.contains("file contents here"))
    }

    @Test
    fun `tool message WITHOUT toolCallId is also converted to user role`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "do something"),
            ChatMessage(role = "assistant", content = "sure"),
            ChatMessage(role = "tool", content = "xml tool output", toolCallId = null),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        val toolMsgs = sent.filter { it.role == "tool" }
        assertTrue(toolMsgs.isEmpty(), "role='tool' must NEVER be sent")

        val toolResultMsg = sent.find { it.content?.contains("TOOL RESULT:") == true }
        assertNotNull(toolResultMsg, "XML tool result should be converted to user message")
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

    // ── Multiple tool results are merged as consecutive user messages ──

    @Test
    fun `multiple tool results become user messages and merge correctly`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "read two files"),
            ChatMessage(
                role = "assistant",
                content = "Reading both",
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

        // No tool messages
        val toolMsgs = sent.filter { it.role == "tool" }
        assertTrue(toolMsgs.isEmpty(), "No role='tool' messages should remain")

        // Both tool results should appear as user content (merged by consecutive-role merge)
        val userMsgs = sent.filter { it.role == "user" }
        val combined = userMsgs.joinToString(" ") { it.content ?: "" }
        assertTrue(combined.contains("contents of A"), "First tool result should be in user content")
        assertTrue(combined.contains("contents of B"), "Second tool result should be in user content")
    }

    // ── Assistant toolCalls are stripped and converted to inline text ──

    @Test
    fun `assistant toolCalls are stripped and converted to inline text`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "read src/main.kt"),
            ChatMessage(
                role = "assistant",
                content = "Let me read that",
                toolCalls = listOf(
                    ToolCall(id = "call_1", function = FunctionCall(name = "read_file", arguments = """{"path":"src/main.kt"}"""))
                )
            ),
            ChatMessage(role = "tool", content = "file contents", toolCallId = "call_1"),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        // Assistant message should have no tool_calls field
        val assistantMsg = sent.find { it.role == "assistant" }
        assertNotNull(assistantMsg)
        assertNull(assistantMsg!!.toolCalls, "toolCalls must be stripped — Sourcegraph may reject them with tools=null")

        // But the tool call info should be inlined as text
        assertTrue(assistantMsg.content!!.contains("read_file"), "Tool call name should appear in assistant content")
        assertTrue(assistantMsg.content!!.contains("src/main.kt"), "Tool call args should appear in assistant content")
    }

    @Test
    fun `assistant message with toolCalls but blank content gets placeholder`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "read it"),
            ChatMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(
                    ToolCall(id = "call_1", function = FunctionCall(name = "read_file", arguments = """{"path":"A.kt"}"""))
                )
            ),
            ChatMessage(role = "tool", content = "result", toolCallId = "call_1"),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        val assistantMsg = sent.find { it.role == "assistant" }
        assertNotNull(assistantMsg)
        assertNull(assistantMsg!!.toolCalls)
        // Content should have tool call info (not be blank)
        assertTrue(assistantMsg.content!!.contains("read_file"))
    }

    // ── Reasoning field is stripped ───────────────────────────────────

    @Test
    fun `reasoning field is stripped from assistant messages`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "think about this"),
            ChatMessage(role = "assistant", content = "Here's my answer", reasoning = "Let me think step by step..."),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        val assistantMsg = sent.find { it.role == "assistant" }
        assertNotNull(assistantMsg)
        assertNull(assistantMsg!!.reasoning, "reasoning field must be stripped — not a standard OpenAI field")
        assertEquals("Here's my answer", assistantMsg.content)
    }

    // ── System role is still converted to user ──────────────────────

    @Test
    fun `pending system content is flushed before tool result`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "user", content = "start"),
            ChatMessage(
                role = "assistant",
                content = "I'll read it",
                toolCalls = listOf(
                    ToolCall(id = "call_1", function = FunctionCall(name = "read_file", arguments = """{"path":"A.kt"}"""))
                )
            ),
            ChatMessage(role = "system", content = "system instruction"),
            ChatMessage(role = "tool", content = "tool output", toolCallId = "call_1"),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        // No system or tool role messages
        assertTrue(sent.none { it.role == "system" }, "No system messages should remain")
        assertTrue(sent.none { it.role == "tool" }, "No tool messages should remain")

        // System content should be present as user message with system_instructions tag
        val systemUserMsg = sent.find { it.content?.contains("system_instructions") == true }
        assertNotNull(systemUserMsg, "System content should be flushed as user message")
    }

    // ── Only user and assistant roles in output ─────────────────────

    @Test
    fun `output contains only user and assistant roles`() = runTest {
        server.enqueue(minimalStreamResponse())

        val messages = listOf(
            ChatMessage(role = "system", content = "You are helpful"),
            ChatMessage(role = "user", content = "read it"),
            ChatMessage(
                role = "assistant",
                content = "Sure",
                toolCalls = listOf(
                    ToolCall(id = "c1", function = FunctionCall(name = "read_file", arguments = """{"path":"x.kt"}"""))
                ),
                reasoning = "Thinking..."
            ),
            ChatMessage(role = "tool", content = "file data", toolCallId = "c1"),
            ChatMessage(role = "user", content = "thanks"),
            ChatMessage(role = "assistant", content = "Done"),
        )

        client.sendMessageStream(messages = messages, tools = null, onChunk = {})
        val sent = captureRequestMessages()

        val roles = sent.map { it.role }.toSet()
        assertEquals(setOf("user", "assistant"), roles, "Only user and assistant roles should remain")

        // No toolCalls or reasoning on any message
        assertTrue(sent.all { it.toolCalls == null }, "No toolCalls should remain on any message")
        assertTrue(sent.all { it.reasoning == null }, "No reasoning should remain on any message")
    }
}
