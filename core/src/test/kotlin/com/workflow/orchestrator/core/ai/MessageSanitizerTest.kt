package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MessageSanitizer.sanitizeForAnthropic].
 *
 * Coverage:
 * - Phase 1: system + tool role conversion
 * - Phase 2: consecutive same-role merging
 * - Phase 3 Case 1: drop empty-content messages with no tool calls
 * - Phase 3 Case 2: substitute U+200B placeholder for assistant messages with
 *   tool calls but null/empty content — the root-cause of the /stream rejection
 * - Phase 4: ensure conversation starts with "user"
 * - Phase 5: final same-role merge after removals
 */
class MessageSanitizerTest {

    private fun sanitize(vararg messages: ChatMessage) =
        MessageSanitizer.sanitizeForAnthropic(messages.toList())

    // ---- Phase 1: role conversion ----

    @Test
    fun `system message is merged into next user message as system_instructions block`() {
        val result = sanitize(
            ChatMessage(role = "system", content = "Be helpful."),
            ChatMessage(role = "user", content = "Hello"),
        )
        assertEquals(1, result.size)
        assertEquals("user", result[0].role)
        assertTrue(result[0].content!!.contains("<system_instructions>"))
        assertTrue(result[0].content!!.contains("Be helpful."))
        assertTrue(result[0].content!!.contains("Hello"))
    }

    @Test
    fun `tool message is coerced to user with TOOL RESULT prefix`() {
        val result = sanitize(
            ChatMessage(role = "user", content = "Do something"),
            ChatMessage(role = "assistant", content = "Ok"),
            ChatMessage(role = "tool", content = "result data", toolCallId = "tc_1"),
        )
        val userMessages = result.filter { it.role == "user" }
        assertTrue(userMessages.any { it.content?.contains("TOOL RESULT:") == true })
        assertTrue(userMessages.any { it.content?.contains("result data") == true })
        assertFalse(result.any { it.role == "tool" })
    }

    @Test
    fun `tool message does not use XML tags - prevents LLM echo hallucination`() {
        val result = sanitize(
            ChatMessage(role = "user", content = "Read a file"),
            ChatMessage(role = "assistant", content = "Reading."),
            ChatMessage(role = "tool", content = "file contents here", toolCallId = "tc_1"),
        )
        val toolResultMsg = result.first { it.content?.contains("TOOL RESULT:") == true }
        assertFalse(toolResultMsg.content!!.contains("<tool_result>"),
            "Tool result must use plain text prefix, not XML tags")
    }

    // ---- Phase 2: consecutive same-role merging ----

    @Test
    fun `consecutive user messages are merged`() {
        val result = sanitize(
            ChatMessage(role = "user", content = "First"),
            ChatMessage(role = "user", content = "Second"),
        )
        assertEquals(1, result.size)
        assertTrue(result[0].content!!.contains("First"))
        assertTrue(result[0].content!!.contains("Second"))
    }

    @Test
    fun `consecutive assistant messages without tool calls are merged`() {
        val result = sanitize(
            ChatMessage(role = "user", content = "Hi"),
            ChatMessage(role = "assistant", content = "Part A"),
            ChatMessage(role = "assistant", content = "Part B"),
        )
        val assistantMessages = result.filter { it.role == "assistant" }
        assertEquals(1, assistantMessages.size)
        assertTrue(assistantMessages[0].content!!.contains("Part A"))
        assertTrue(assistantMessages[0].content!!.contains("Part B"))
    }

    // ---- Phase 3 Case 1: drop empty messages with no tool calls ----

    @Test
    fun `user message with blank content and no tool calls is dropped`() {
        val result = sanitize(
            ChatMessage(role = "user", content = "Hello"),
            ChatMessage(role = "assistant", content = ""),
        )
        assertFalse(result.any { it.role == "assistant" && it.toolCalls.isNullOrEmpty() },
            "Assistant message with empty content and no tool calls must be dropped")
    }

    @Test
    fun `U+200B-only echo from LLM is dropped - prevents stuck loop`() {
        // The LLM sometimes mirrors the U+200B placeholder back as its reply.
        // isEffectivelyBlank catches this so the echo doesn't accumulate in history.
        val result = sanitize(
            ChatMessage(role = "user", content = "Do something"),
            ChatMessage(role = "assistant", content = "\u200B"),  // echo of the placeholder
        )
        // The U+200B echo with no tool calls must be removed
        assertFalse(result.any { it.role == "assistant" && it.toolCalls.isNullOrEmpty() },
            "U+200B-only assistant echo must be dropped by isEffectivelyBlank")
    }

    // ---- Phase 3 Case 2: empty-content assistant + toolCalls → placeholder ----

    @Test
    fun `assistant message with null content and toolCalls receives U+200B placeholder`() {
        val toolCall = ToolCall(
            id = "call_1",
            type = "function",
            function = FunctionCall(name = "view_image", arguments = "{}"),
        )
        val result = sanitize(
            ChatMessage(role = "user", content = "Look at this"),
            ChatMessage(role = "assistant", content = null, toolCalls = listOf(toolCall)),
        )
        val assistantMsg = result.firstOrNull { it.role == "assistant" }
        assertNotNull(assistantMsg, "Assistant message must be preserved")
        assertNotNull(assistantMsg!!.content, "Content must not be null after sanitization")
        assertTrue(assistantMsg.content!!.isNotEmpty(),
            "Content must be non-empty (placeholder substituted)")
        // Verify the tool calls are preserved alongside the placeholder
        assertEquals(listOf(toolCall), assistantMsg.toolCalls)
    }

    @Test
    fun `assistant message with empty-string content and toolCalls receives U+200B placeholder`() {
        // This is the primary bug trigger: LLM emits content="" (not null) when
        // only tool_use is present. The sanitizer must treat "" the same as null.
        val toolCall = ToolCall(
            id = "call_1",
            type = "function",
            function = FunctionCall(name = "view_image", arguments = "{}"),
        )
        val result = sanitize(
            ChatMessage(role = "user", content = "Look at this image"),
            ChatMessage(
                role = "assistant",
                content = "",  // EMPTY — this is the bug trigger
                toolCalls = listOf(toolCall),
            ),
            ChatMessage(role = "tool", content = "image attached", toolCallId = "call_1"),
        )
        val assistantMsg = result.firstOrNull { it.role == "assistant" }
        assertNotNull(assistantMsg, "Assistant message must survive sanitization")
        val content = assistantMsg!!.content
        assertNotNull(content, "Content must not be null after sanitization")
        assertTrue(content!!.isNotEmpty(),
            "Empty content must be replaced by placeholder, not left empty. " +
                "An empty assistant turn causes Anthropic to reject the request with " +
                "'message content cannot be empty'.")
        // The placeholder must be the U+200B zero-width space
        assertEquals("\u200B", content,
            "Placeholder must be exactly U+200B (zero-width space)")
        assertEquals(listOf(toolCall), assistantMsg.toolCalls,
            "Tool calls must be preserved alongside the placeholder")
    }

    @Test
    fun `assistant message with non-empty content and toolCalls is NOT modified`() {
        val toolCall = ToolCall(
            id = "call_1",
            type = "function",
            function = FunctionCall(name = "read_file", arguments = "{\"path\":\"/foo.kt\"}"),
        )
        val result = sanitize(
            ChatMessage(role = "user", content = "Read that file"),
            ChatMessage(
                role = "assistant",
                content = "Let me read the file for you.",
                toolCalls = listOf(toolCall),
            ),
        )
        val assistantMsg = result.firstOrNull { it.role == "assistant" }
        assertNotNull(assistantMsg)
        assertEquals("Let me read the file for you.", assistantMsg!!.content,
            "Non-empty content alongside tool calls must NOT be overwritten")
    }

    // ---- Phase 4: ensure conversation starts with user ----

    @Test
    fun `conversation starting with assistant gets a synthetic user prefix`() {
        val result = sanitize(
            ChatMessage(role = "assistant", content = "Hello"),
        )
        assertEquals("user", result.first().role,
            "Conversation must start with a user message")
    }

    // ---- Integration: multi-turn tool-call sequence ----

    @Test
    fun `multi-turn assistant-with-tool-calls sequence sanitizes all empty turns`() {
        val call1 = ToolCall("c1", "function", FunctionCall("tool_a", "{}"))
        val call2 = ToolCall("c2", "function", FunctionCall("tool_b", "{}"))
        val result = sanitize(
            ChatMessage(role = "user", content = "Do both tools"),
            ChatMessage(role = "assistant", content = null, toolCalls = listOf(call1)),
            ChatMessage(role = "tool", content = "result_a", toolCallId = "c1"),
            ChatMessage(role = "assistant", content = "", toolCalls = listOf(call2)),
            ChatMessage(role = "tool", content = "result_b", toolCallId = "c2"),
        )
        val assistantMsgs = result.filter { it.role == "assistant" }
        assertTrue(assistantMsgs.isNotEmpty(), "Both assistant messages must survive")
        for (msg in assistantMsgs) {
            assertTrue(msg.content?.isNotEmpty() == true,
                "Every assistant message must have non-empty content after sanitization")
        }
    }
}
