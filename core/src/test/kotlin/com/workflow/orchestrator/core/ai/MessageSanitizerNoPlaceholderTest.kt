package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageSanitizerNoPlaceholderTest {

    @Test
    fun `assistant with no content and no tool calls is dropped not placeholder-substituted`() {
        // After migration, assistant turns always have content (XML inline
        // when tools were used). Empty-content assistant turns are real
        // empties, not tool-call placeholders — drop them per Case 1.
        val input = listOf(
            ChatMessage(role = "user", content = "hello"),
            ChatMessage(role = "assistant", content = null, toolCalls = null),
            ChatMessage(role = "user", content = "still there?")
        )
        val out = MessageSanitizer.sanitizeForAnthropic(input)
        // Empty assistant dropped → consecutive users merged
        assertEquals(1, out.size)
        assertEquals("user", out[0].role)
        assertEquals("hello\n\nstill there?", out[0].content)
    }
}
