package com.workflow.orchestrator.agent.brain

import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.core.model.ApiResult

/**
 * Abstraction over LLM providers. Allows swapping between
 * Sourcegraph OpenAI-compatible API, future Cody CLI tool support,
 * or other providers without changing agent logic.
 */
interface LlmBrain {
    /** Send a multi-turn conversation with optional tool definitions. */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        maxTokens: Int? = null
    ): ApiResult<ChatCompletionResponse>

    /** Estimate token count for a string (for budget tracking). */
    fun estimateTokens(text: String): Int

    /** The model identifier being used. */
    val modelId: String
}
