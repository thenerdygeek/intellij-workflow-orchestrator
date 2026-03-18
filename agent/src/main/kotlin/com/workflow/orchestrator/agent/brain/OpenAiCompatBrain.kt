package com.workflow.orchestrator.agent.brain

import com.workflow.orchestrator.agent.api.SourcegraphChatClient
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.core.model.ApiResult
import okhttp3.OkHttpClient

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
        maxTokens: Int?
    ): ApiResult<ChatCompletionResponse> {
        return client.sendMessage(
            messages = messages,
            tools = tools,
            maxTokens = maxTokens
        )
    }

    override fun estimateTokens(text: String): Int = TokenEstimator.estimate(text)
}
