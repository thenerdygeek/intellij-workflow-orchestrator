package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.*
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

    @Test
    fun `estimateToolDefinitions returns nonzero for tools`() {
        val tools = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "read_file",
                    description = "Read a file from disk",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "path" to ParameterProperty(type = "string", description = "File path")
                        ),
                        required = listOf("path")
                    )
                )
            ),
            ToolDefinition(
                function = FunctionDefinition(
                    name = "edit_file",
                    description = "Edit a file with precise string replacement",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "path" to ParameterProperty(type = "string", description = "File path"),
                            "old_string" to ParameterProperty(type = "string", description = "Text to find"),
                            "new_string" to ParameterProperty(type = "string", description = "Replacement text")
                        ),
                        required = listOf("path", "old_string", "new_string")
                    )
                )
            )
        )

        val count = TokenEstimator.estimateToolDefinitions(tools)
        assertTrue(count > 20, "Expected >20 tokens for 2 tool definitions, got $count")
    }

    @Test
    fun `estimateToolDefinitions returns zero for empty list`() {
        assertEquals(0, TokenEstimator.estimateToolDefinitions(emptyList()))
    }
}
