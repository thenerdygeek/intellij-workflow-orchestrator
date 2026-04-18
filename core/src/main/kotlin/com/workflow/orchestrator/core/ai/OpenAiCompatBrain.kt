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
    readTimeoutSeconds: Long = 180,  // Increased for NO_KEEPALIVE safety on large prompts
    httpClientOverride: OkHttpClient? = null,
    override var toolNameSet: Set<String> = emptySet(),
    override var paramNameSet: Set<String> = emptySet()
) : LlmBrain {

    /**
     * Sampling temperature forwarded to every chat/chatStream call.
     * Normally 0.0 (deterministic). Set to 1.0 by AgentLoop before retrying
     * after an empty response — breaks degenerate zero-temperature sampling.
     * Reset to 0.0 by AgentLoop when the model returns a real tool call.
     */
    override var temperature: Double = 0.0

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
            tools = null,  // XML mode always on: tools defined in system prompt
            maxTokens = maxTokens,
            toolChoice = toolChoice, // SourcegraphChatClient will ignore this
            temperature = temperature,
            knownToolNames = toolNameSet,
            knownParamNames = paramNameSet
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
            tools = null,  // XML mode always on: tools defined in system prompt
            maxTokens = maxTokens,
            temperature = temperature,
            onChunk = onChunk,
            knownToolNames = toolNameSet,
            knownParamNames = paramNameSet
        )
    }

    override fun estimateTokens(text: String): Int = TokenEstimator.estimate(text)

    override fun interruptStream() {
        client.shouldInterruptStream = true
    }

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

    /**
     * Detach any shared API call counter previously injected via
     * [setSharedApiCallCounter] and resume using a local counter. Used by sub-agent
     * runners so their dumps are numbered independently of the parent session.
     */
    fun detachSharedApiCallCounter() {
        client.detachSharedApiCallCounter()
    }
}
