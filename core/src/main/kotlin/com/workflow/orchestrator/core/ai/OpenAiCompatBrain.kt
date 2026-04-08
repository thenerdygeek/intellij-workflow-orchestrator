package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.*
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
 * - max_tokens: mandatory for thinking models (HTTP 500 if omitted), no fixed cap
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
        // TEMPORARY: Use non-streaming to debug tool call truncation and token tracking.
        // Streaming drops tool_call deltas and doesn't report usage, causing:
        //   1. Truncated/missing tool calls
        //   2. Token tracking never updating (contextManager.updateTokens never called)
        // Non-streaming gives complete tool calls and accurate usage in every response.
        val result = client.sendMessage(
            messages = messages,
            tools = tools,
            maxTokens = maxTokens
        )
        // Emit the full response as a single chunk so the UI still receives content
        if (result is ApiResult.Success) {
            val content = result.data.choices.firstOrNull()?.message?.content
            if (content != null) {
                onChunk(StreamChunk(
                    choices = listOf(StreamChoice(delta = StreamDelta(content = content)))
                ))
            }
        }
        return result
    }

    override fun estimateTokens(text: String): Int = TokenEstimator.estimate(text)

    override fun cancelActiveRequest() = client.cancelActiveRequest()

    /**
     * Set the session-scoped directory for API debug dumps.
     * Pass null to disable dumping (e.g., when no session is active).
     */
    fun setApiDebugDir(dir: java.io.File?) {
        client.apiDebugSessionDir = dir
    }

    /**
     * Reset the API call counter. Call at the start of each new session
     * so dump file numbering restarts from 001.
     */
    fun resetApiCallCounter() {
        client.resetApiCallCounter()
    }

    /**
     * Inject a session-scoped API call counter so multiple brains within one
     * task share monotonic call numbering. Used by AgentService to keep
     * `api-debug/call-NNN-*.txt` filenames non-overlapping across:
     *   - multi-message chats (each user message creates a new brain)
     *   - brain recycling on stream errors (new brain mid-task)
     *
     * The injected counter's lifecycle is owned by the caller (typically the
     * session). When null, the inner client falls back to its local counter.
     */
    fun setSharedApiCallCounter(counter: java.util.concurrent.atomic.AtomicInteger) {
        client.setSharedApiCallCounter(counter)
    }
}
