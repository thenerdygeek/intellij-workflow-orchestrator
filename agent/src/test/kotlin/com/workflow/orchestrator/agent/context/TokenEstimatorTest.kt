package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TokenEstimatorTest {
    @Test
    fun `estimate returns reasonable count for English text`() {
        // "Hello world" = 11 chars, ~3.14 tokens at 3.5 chars/token
        val count = TokenEstimator.estimate("Hello world")
        assertTrue(count in 3..5, "Expected 3-5 tokens, got $count")
    }

    @Test
    fun `estimate returns reasonable count for code`() {
        val code = "fun main() {\n    println(\"Hello\")\n}"
        val count = TokenEstimator.estimate(code)
        assertTrue(count in 8..14, "Expected 8-14 tokens for code snippet, got $count")
    }

    @Test
    fun `estimate handles empty string`() {
        assertEquals(1, TokenEstimator.estimate("")) // +1 minimum
    }

    @Test
    fun `estimate messages includes overhead`() {
        val messages = listOf(
            ChatMessage(role = "user", content = "Hello")
        )
        val count = TokenEstimator.estimate(messages)
        assertTrue(count > TokenEstimator.estimate("Hello"), "Message estimate should include overhead")
    }
}
