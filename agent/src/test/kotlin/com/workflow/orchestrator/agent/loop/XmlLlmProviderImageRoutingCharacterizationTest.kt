package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmlLlmProviderImageRoutingCharacterizationTest {
    // A fake brain standing in for BrainRouter — records that chatStream was delegated unchanged.
    // Only the 3 non-defaulted LlmBrain members need overriding (cancelActiveRequest/interruptStream/
    // temperature/toolNameSet/paramNameSet all have interface defaults — verified LlmBrain.kt:39-65).
    private class RecordingBrain : LlmBrain {
        var streamCalls = 0
        override val modelId = "anthropic::v1::claude"
        override suspend fun chat(m: List<ChatMessage>, t: List<ToolDefinition>?, mx: Int?, tc: JsonElement?): ApiResult<ChatCompletionResponse> =
            ApiResult.Error(ErrorType.SERVER_ERROR, "unused")
        override suspend fun chatStream(m: List<ChatMessage>, t: List<ToolDefinition>?, mx: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
            streamCalls++; return ApiResult.Error(ErrorType.SERVER_ERROR, "unused")
        }
        override fun estimateTokens(text: String) = text.length / 4
    }

    @Test fun `provider delegates chatStream to the wrapped brain (router routing preserved)`() = runBlocking {
        val brain = RecordingBrain()
        // catalogService can be null for this delegation check; provider must tolerate it like ContextManager does.
        val provider = XmlLlmProvider(delegate = brain, catalogService = null, toolProtocol = XmlToolProtocol())
        provider.chatStream(emptyList(), null, null) {}
        assertEquals(1, brain.streamCalls)
        assertTrue(provider is com.workflow.orchestrator.core.ai.LlmProvider)
    }
}
