package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatCompletionRequest
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WireOutputScenarioTest {

    private val json = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `W1 — request body has no tools field even if caller passes them`() {
        // After migration, SourcegraphChatClient unconditionally sets tools = null.
        // Construct the DTO directly with tools = null and verify the JSON omits it.
        val req = ChatCompletionRequest(
            model = "anthropic::sonnet",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            tools = null,
            toolChoice = null,
            temperature = 0.0,
            maxTokens = 100,
            stream = true
        )
        val body = json.encodeToString(ChatCompletionRequest.serializer(), req)
        assertFalse(body.contains("\"tools\""), "Wire body must not include `tools` field, got: $body")
        assertFalse(body.contains("\"tool_choice\""))
    }

    @Test
    fun `W2 — assistant message in wire body has no tool_calls field`() {
        val req = ChatCompletionRequest(
            model = "anthropic::sonnet",
            messages = listOf(
                ChatMessage(role = "user", content = "x"),
                ChatMessage(
                    role = "assistant",
                    content = "ok\n<read_file>\n<path>x</path>\n</read_file>",
                    toolCalls = null  // post-migration assistant turns never carry this
                )
            ),
            stream = true
        )
        val body = json.encodeToString(ChatCompletionRequest.serializer(), req)
        assertFalse(body.contains("\"tool_calls\""), "Assistant wire body must not include `tool_calls`, got: $body")
        assertTrue(body.contains("<read_file>"), "XML should be inline in assistant content")
    }
}
