package com.workflow.orchestrator.agent.context

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolResultCompressorTest {

    @Test
    fun `short content passes through unchanged`() {
        val content = "File read: 5 lines"
        val result = ToolResultCompressor.compress(content, "Read 5 lines", maxTokens = 4000)
        assertEquals(content, result)
    }

    @Test
    fun `long content is truncated with summary`() {
        val content = "a".repeat(10000) // way over 500 tokens
        val result = ToolResultCompressor.compress(content, "Large file read", maxTokens = 100)

        assertTrue(result.startsWith("Summary: Large file read"))
        assertTrue(result.contains("[truncated"))
        assertTrue(TokenEstimator.estimate(result) < 200) // some overhead from markers
    }

    @Test
    fun `summary is preserved in compressed output`() {
        val content = "line1\n".repeat(1000)
        val result = ToolResultCompressor.compress(content, "1000 lines from Main.kt", maxTokens = 50)

        assertTrue(result.contains("1000 lines from Main.kt"))
    }

    @Test
    fun `very low maxTokens still produces output with summary`() {
        val content = "x".repeat(5000) // clearly over any token limit
        val result = ToolResultCompressor.compress(content, "summary", maxTokens = 10)

        assertTrue(result.isNotBlank())
        assertTrue(result.contains("Summary: summary"))
    }
}
