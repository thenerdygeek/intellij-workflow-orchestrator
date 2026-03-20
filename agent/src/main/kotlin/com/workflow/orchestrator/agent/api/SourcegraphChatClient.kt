package com.workflow.orchestrator.agent.api

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.api.dto.ListModelsResponse
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for Sourcegraph's LLM chat completions API.
 *
 * Implements the Sourcegraph OpenAPI spec (/.api/llm/chat/completions):
 * - Auth: `Authorization: token TOKEN_VALUE` (Sourcegraph token scheme)
 * - Endpoint: `{baseUrl}/.api/llm/chat/completions`
 * - Model format: `provider::apiVersion::modelId` (e.g., `anthropic::2023-06-01::claude-3.5-sonnet`)
 * - max_tokens capped at 4000 by the API
 * - tools supported (AssistantToolsFunction[])
 * - tool_choice NOT supported by the API (omitted from requests)
 * - stream: true uses SSE on the same endpoint
 *
 * Key differences from standard OpenAI API:
 * - No `tool_choice` parameter
 * - `max_tokens` maximum is 4000 (not 4096 or higher)
 * - Response `object` field is `"object"` not `"chat.completion"`
 * - Auth uses `token` prefix, not `Bearer`
 */
class SourcegraphChatClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val model: String,
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 120,
    httpClientOverride: OkHttpClient? = null
) {
    private val log = Logger.getInstance(SourcegraphChatClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    companion object {
        /** Sourcegraph API path for chat completions (from OpenAPI spec). */
        const val CHAT_COMPLETIONS_PATH = "/.api/llm/chat/completions"

        /** Sourcegraph API path for listing available models. */
        const val MODELS_PATH = "/.api/llm/models"

        /**
         * Maximum output tokens — no longer hardcoded.
         * The actual limit varies per model and Sourcegraph instance configuration.
         * We pass through whatever maxOutputTokens the user configures in settings.
         * If omitted, the API uses its own default.
         */
        @Deprecated("Output limit varies per model. Use AgentSettings.maxOutputTokens instead.")
        const val MAX_OUTPUT_TOKENS = 4000
    }

    // Uses longer read timeout (120s) than default (30s) because LLM calls are slow.
    // RetryInterceptor handles 429/5xx with exponential backoff (1s, 2s, 4s).
    // Auth uses TOKEN scheme: "Authorization: token TOKEN_VALUE" per Sourcegraph spec.
    private val httpClient: OkHttpClient by lazy {
        httpClientOverride ?: OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.TOKEN))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    /**
     * Fetch available models from the Sourcegraph instance.
     * Uses GET /.api/llm/models endpoint.
     *
     * @return List of available models, or empty list on error
     */
    suspend fun listModels(): ApiResult<ListModelsResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}$MODELS_PATH"
            log.debug("[Agent:API] GET $MODELS_PATH")

            val httpRequest = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string() ?: ""
                when {
                    response.isSuccessful -> {
                        val parsed = json.decodeFromString<ListModelsResponse>(body)
                        log.debug("[Agent:API] Models: ${parsed.data.size} available")
                        ApiResult.Success(parsed)
                    }
                    else -> mapErrorResponse(response.code, body)
                }
            }
        } catch (e: IOException) {
            log.warn("[Agent:API] Models fetch network error: ${e.message}", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Failed to fetch models: ${e.message}", e)
        } catch (e: Exception) {
            log.warn("[Agent:API] Models fetch error: ${e.message}", e)
            ApiResult.Error(ErrorType.PARSE_ERROR, "Failed to parse models: ${e.message}", e)
        }
    }

    /**
     * Build the full chat completions URL from the base URL.
     * Base URL should be the Sourcegraph instance root (e.g., "https://sourcegraph.company.com").
     */
    private fun chatCompletionsUrl(): String {
        return "${baseUrl.trimEnd('/')}$CHAT_COMPLETIONS_PATH"
    }

    /**
     * Pass through max_tokens from settings. No clamping — the actual limit
     * varies per model and Sourcegraph instance. Let the API reject if too high.
     */
    private fun clampMaxTokens(maxTokens: Int?): Int? {
        return maxTokens
    }

    /**
     * Convert messages to Sourcegraph/Anthropic-compatible format.
     *
     * Constraints:
     * 1. Sourcegraph rejects "system" role (400: "system role is not supported")
     * 2. Anthropic requires strict user/assistant alternation (no consecutive same-role messages)
     * 3. "tool" role may not pass through the proxy
     *
     * Strategy:
     * - System messages: merge into the next user message as a prefix
     * - Tool messages: merge into a synthetic user message
     * - Consecutive same-role messages: merge into one message
     * - Ensure conversation starts with "user" and alternates
     */
    private fun sanitizeMessages(messages: List<ChatMessage>): List<ChatMessage> {
        // Phase 1: convert system and tool roles to user content
        val converted = mutableListOf<ChatMessage>()
        var pendingSystemContent: String? = null

        for (msg in messages) {
            when (msg.role) {
                "system" -> {
                    // Buffer system content to merge into next user message
                    val content = msg.content ?: ""
                    pendingSystemContent = if (pendingSystemContent != null) {
                        "$pendingSystemContent\n$content"
                    } else content
                }
                "tool" -> {
                    // Convert tool result to user message
                    val toolContent = "<tool_result${msg.toolCallId?.let { " tool_use_id=\"$it\"" } ?: ""}>\n${msg.content ?: ""}\n</tool_result>"
                    converted.add(ChatMessage(role = "user", content = toolContent))
                }
                "user" -> {
                    // Merge any pending system content into this user message
                    val content = if (pendingSystemContent != null) {
                        val merged = "<system_instructions>\n$pendingSystemContent\n</system_instructions>\n\n<user_message>\n${msg.content ?: ""}\n</user_message>"
                        pendingSystemContent = null
                        merged
                    } else {
                        msg.content ?: ""
                    }
                    converted.add(ChatMessage(role = "user", content = content))
                }
                "assistant" -> {
                    // If there's buffered system content with no user message yet, emit as user first
                    if (pendingSystemContent != null) {
                        converted.add(ChatMessage(role = "user", content = "<system_instructions>\n$pendingSystemContent\n</system_instructions>"))
                        pendingSystemContent = null
                    }
                    converted.add(msg)
                }
                else -> converted.add(msg)
            }
        }

        // Flush any remaining system content
        if (pendingSystemContent != null) {
            converted.add(ChatMessage(role = "user", content = "<system_instructions>\n$pendingSystemContent\n</system_instructions>"))
        }

        // Phase 2: merge consecutive same-role messages (Anthropic requirement)
        val merged = mutableListOf<ChatMessage>()
        for (msg in converted) {
            val last = merged.lastOrNull()
            if (last != null && last.role == msg.role && last.toolCalls == null && msg.toolCalls == null) {
                // Merge into previous message
                merged[merged.size - 1] = ChatMessage(
                    role = msg.role,
                    content = "${last.content ?: ""}\n\n${msg.content ?: ""}"
                )
            } else {
                merged.add(msg)
            }
        }

        // Phase 3: handle empty/null content (Anthropic rejects "message content cannot be empty")
        // Case 1: Messages with no content AND no tool calls → drop entirely
        merged.removeAll { msg ->
            msg.content.isNullOrBlank() && msg.toolCalls.isNullOrEmpty()
        }
        // Case 2: Assistant messages with tool calls but null/empty content → set placeholder
        // (LLM often returns content=null when making tool calls — this is normal)
        for (i in merged.indices) {
            val msg = merged[i]
            if (msg.role == "assistant" && msg.content.isNullOrBlank() && !msg.toolCalls.isNullOrEmpty()) {
                merged[i] = ChatMessage(
                    role = "assistant",
                    content = "Using tools.",
                    toolCalls = msg.toolCalls
                )
            }
        }

        // Phase 4: ensure conversation starts with "user"
        if (merged.isNotEmpty() && merged.first().role != "user") {
            merged.add(0, ChatMessage(role = "user", content = "[Context follows]"))
        }

        // Phase 5: ensure no two consecutive same-role messages after removals
        val final = mutableListOf<ChatMessage>()
        for (msg in merged) {
            val last = final.lastOrNull()
            if (last != null && last.role == msg.role && last.toolCalls == null && msg.toolCalls == null) {
                final[final.size - 1] = ChatMessage(
                    role = msg.role,
                    content = "${last.content ?: ""}\n\n${msg.content ?: ""}"
                )
            } else {
                final.add(msg)
            }
        }

        return final
    }

    /**
     * Send a streaming chat completion request. Each SSE chunk is emitted via [onChunk]
     * for real-time UI updates. The accumulated response is returned as a single
     * [ChatCompletionResponse] when the stream completes.
     *
     * Note: Sourcegraph's spec labels `stream` as "Unsupported" but documents the
     * streaming response format. Streaming may work but fall back to non-streaming
     * if the proxy doesn't support it.
     */
    suspend fun sendMessageStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int? = null,
        temperature: Double = 0.0,
        onChunk: suspend (StreamChunk) -> Unit
    ): ApiResult<ChatCompletionResponse> = withContext(Dispatchers.IO) {
        try {
            val sanitized = sanitizeMessages(messages)
            val request = ChatCompletionRequest(
                model = model,
                messages = sanitized,
                tools = tools?.takeIf { it.isNotEmpty() },
                toolChoice = null,
                temperature = temperature,
                maxTokens = clampMaxTokens(maxTokens),
                stream = true
            )

            val jsonBody = json.encodeToString(request)
            log.debug("[Agent:API] POST $CHAT_COMPLETIONS_PATH (stream, ${jsonBody.length} chars)")

            val httpRequest = Request.Builder()
                .url(chatCompletionsUrl())
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    return@withContext mapErrorResponse(response.code, body)
                }

                val reader = response.body?.byteStream()?.bufferedReader()
                    ?: return@withContext ApiResult.Error(
                        ErrorType.PARSE_ERROR, "Empty response body for stream"
                    )

                val contentBuilder = StringBuilder()
                val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()
                var role = "assistant"

                reader.forEachLine { line ->
                    if (line.startsWith("data: ") && line != "data: [DONE]") {
                        val chunkJson = line.removePrefix("data: ")
                        try {
                            val chunk = json.decodeFromString<StreamChunk>(chunkJson)
                            // Use runBlocking here because forEachLine is not a suspend context.
                            // This is safe because we are already on Dispatchers.IO.
                            kotlinx.coroutines.runBlocking { onChunk(chunk) }

                            chunk.choices.firstOrNull()?.delta?.let { delta ->
                                delta.role?.let { role = it }
                                delta.content?.let { contentBuilder.append(it) }
                                delta.toolCalls?.forEach { tc ->
                                    val builder = toolCallBuilders.getOrPut(tc.index) { ToolCallBuilder() }
                                    tc.id?.let { builder.id = it }
                                    tc.function?.name?.let { builder.name = it }
                                    tc.function?.arguments?.let { builder.arguments.append(it) }
                                }
                            }
                        } catch (e: Exception) {
                            log.debug("[Agent:API] Skipping malformed SSE chunk: ${e.message}")
                        }
                    }
                }

                val toolCalls = if (toolCallBuilders.isNotEmpty()) {
                    toolCallBuilders.entries
                        .sortedBy { it.key }
                        .map { it.value.toToolCall() }
                        .filter { it.function.name.isNotBlank() } // Drop tool calls with empty names
                        .ifEmpty { null }
                } else null

                val finalMessage = ChatMessage(
                    role = role,
                    content = contentBuilder.toString().ifBlank { null },
                    toolCalls = toolCalls
                )

                ApiResult.Success(ChatCompletionResponse(
                    id = "stream-${System.nanoTime()}",
                    choices = listOf(Choice(index = 0, message = finalMessage, finishReason = "stop")),
                    usage = null
                ))
            }
        } catch (e: IOException) {
            log.warn("[Agent:API] Stream network error: ${e.message}", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Stream error: ${e.message}", e)
        } catch (e: Exception) {
            log.error("[Agent:API] Stream unexpected error: ${e.message}", e)
            ApiResult.Error(ErrorType.PARSE_ERROR, "Stream error: ${e.message}", e)
        }
    }

    /**
     * Send a non-streaming chat completion request.
     *
     * Note: [toolChoice] parameter is accepted for interface compatibility but
     * is NOT sent to the Sourcegraph API (not in their OpenAPI spec). The model
     * will always use "auto" behavior when tools are provided.
     */
    suspend fun sendMessage(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int? = null,
        temperature: Double = 0.0,
        toolChoice: JsonElement? = null // Accepted but not sent — not in Sourcegraph API spec
    ): ApiResult<ChatCompletionResponse> = withContext(Dispatchers.IO) {
        try {
            val sanitized = sanitizeMessages(messages)
            val request = ChatCompletionRequest(
                model = model,
                messages = sanitized,
                tools = tools?.takeIf { it.isNotEmpty() },
                toolChoice = null,
                temperature = temperature,
                maxTokens = clampMaxTokens(maxTokens)
            )

            val jsonBody = json.encodeToString(request)
            log.debug("[Agent:API] POST $CHAT_COMPLETIONS_PATH (${jsonBody.length} chars)")

            val httpRequest = Request.Builder()
                .url(chatCompletionsUrl())
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string() ?: ""

                when {
                    response.isSuccessful -> {
                        val parsed = json.decodeFromString<ChatCompletionResponse>(body)
                        log.debug("[Agent:API] Response: ${parsed.usage?.totalTokens} tokens")
                        ApiResult.Success(parsed)
                    }
                    else -> mapErrorResponse(response.code, body)
                }
            }
        } catch (e: IOException) {
            log.warn("[Agent:API] Network error: ${e.message}", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Network error: ${e.message}", e)
        } catch (e: Exception) {
            log.error("[Agent:API] Unexpected error: ${e.message}", e)
            ApiResult.Error(ErrorType.PARSE_ERROR, "Unexpected error: ${e.message}", e)
        }
    }

    private fun <T> mapErrorResponse(code: Int, body: String): ApiResult<T> = when {
        code == 401 || code == 403 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Authentication failed ($code)")
        code == 429 -> ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limited. Retry after delay.")
        body.contains("context_length", ignoreCase = true) -> ApiResult.Error(
            ErrorType.CONTEXT_LENGTH_EXCEEDED,
            "Context length exceeded ($code): $body"
        )
        code in 500..599 -> ApiResult.Error(ErrorType.SERVER_ERROR, "Server error ($code): $body")
        else -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "Unexpected response ($code): $body")
    }

    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()

        fun toToolCall() = ToolCall(id = id, function = FunctionCall(name = name, arguments = arguments.toString()))
    }
}
