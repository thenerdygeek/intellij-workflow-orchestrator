package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.JsonElement

/**
 * Abstraction over LLM providers. Allows swapping between
 * Sourcegraph OpenAI-compatible API, direct Sourcegraph LLM API,
 * or other providers without changing agent logic.
 */
interface LlmBrain {
    /** Send a multi-turn conversation with optional tool definitions. */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        maxTokens: Int? = null,
        toolChoice: JsonElement? = null
    ): ApiResult<ChatCompletionResponse>

    /**
     * Send a streaming multi-turn conversation. Each SSE chunk is emitted
     * via [onChunk] for real-time UI updates. Returns the accumulated response.
     */
    suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        maxTokens: Int? = null,
        onChunk: suspend (StreamChunk) -> Unit
    ): ApiResult<ChatCompletionResponse>

    /** Estimate token count for a string (for budget tracking). */
    fun estimateTokens(text: String): Int

    /** Cancel the active HTTP request immediately. Aborts at the TCP level. */
    fun cancelActiveRequest() {}

    /** The model identifier being used. */
    val modelId: String
}
