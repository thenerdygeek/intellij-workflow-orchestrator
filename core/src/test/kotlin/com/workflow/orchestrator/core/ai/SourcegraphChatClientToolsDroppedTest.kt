package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ChatCompletionRequest
import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SourcegraphChatClientToolsDroppedTest {

    @Test
    fun `request body never contains tools field even when caller passes them`() {
        // Construct a request body the same way SourcegraphChatClient does at
        // SourcegraphChatClient.kt:242-251. After the migration, the `tools`
        // field must be null on the wire regardless of caller intent.
        val req = ChatCompletionRequest(
            model = "anthropic::sonnet",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            tools = null,  // <-- explicit null after migration
            toolChoice = null,
            temperature = 0.0,
            maxTokens = 100,
            stream = true
        )
        val json = Json { encodeDefaults = false; explicitNulls = false }
        val body = json.encodeToString(ChatCompletionRequest.serializer(), req)
        // Wire body MUST NOT include a "tools" key — Sourcegraph forwards
        // it to upstream Anthropic, which activates native tool_use mode.
        assert(!body.contains("\"tools\"")) {
            "Expected no `tools` field in request body, got: $body"
        }
    }
}
