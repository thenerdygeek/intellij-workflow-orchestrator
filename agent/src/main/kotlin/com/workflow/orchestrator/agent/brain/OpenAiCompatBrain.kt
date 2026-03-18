package com.workflow.orchestrator.agent.brain

import com.workflow.orchestrator.agent.api.SourcegraphChatClient
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/**
 * LLM brain implementation that talks to Sourcegraph's OpenAI-compatible chat completions API.
 *
 * Sourcegraph API differences from standard OpenAI:
 * - Endpoint: `/.api/llm/chat/completions` (not `/v1/chat/completions`)
 * - Auth: `token TOKEN_VALUE` (not `Bearer`)
 * - Model format: `provider::apiVersion::modelId` (e.g., `anthropic::2024-10-22::claude-sonnet-4-20250514`)
 * - max_tokens capped at 4000
 * - tool_choice not supported (tools use auto behavior)
 *
 * @param sourcegraphUrl The Sourcegraph instance root URL (e.g., "https://sourcegraph.company.com")
 * @param tokenProvider Provides the Sourcegraph access token
 * @param model The model identifier in Sourcegraph format
 */
class OpenAiCompatBrain(
    sourcegraphUrl: String,
    tokenProvider: () -> String?,
    private val model: String,
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 120,
    httpClientOverride: OkHttpClient? = null
) : LlmBrain {

    private val client = SourcegraphChatClient(
        baseUrl = sourcegraphUrl,
        tokenProvider = tokenProvider,
        model = model,
        connectTimeoutSeconds = connectTimeoutSeconds,
        readTimeoutSeconds = readTimeoutSeconds,
        httpClientOverride = httpClientOverride
    )

    override val modelId: String = model

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        toolChoice: JsonElement? // Accepted for interface compat; not sent to Sourcegraph
    ): ApiResult<ChatCompletionResponse> {
        return client.sendMessage(
            messages = messages,
            tools = tools,
            maxTokens = maxTokens,
            toolChoice = toolChoice // SourcegraphChatClient will ignore this
        )
    }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        onChunk: suspend (StreamChunk) -> Unit
    ): ApiResult<ChatCompletionResponse> {
        return client.sendMessageStream(
            messages = messages,
            tools = tools,
            maxTokens = maxTokens,
            onChunk = onChunk
        )
    }

    override fun estimateTokens(text: String): Int = TokenEstimator.estimate(text)
}
