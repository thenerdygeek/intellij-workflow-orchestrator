package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.ai.dto.ListModelsResponse
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.ChatHttpEventListener
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
 * - max_tokens: MANDATORY for thinking models (omitting causes HTTP 500). Limit varies
 *   per model — no fixed API cap. Probe scripts confirm 8K/16K/100K all accepted.
 *   The agent currently sends up to 64K successfully.
 * - tools supported (AssistantToolsFunction[])
 * - tool_choice NOT supported by the API (omitted from requests)
 * - stream: true uses SSE on the same endpoint
 *
 * Key differences from standard OpenAI API:
 * - No `tool_choice` parameter
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

    /** Active HTTP call — cancel this to abort the in-flight LLM request instantly. */
    private val activeCall = AtomicReference<Call?>(null)

    /**
     * Cancel the active HTTP request immediately. Aborts at the TCP level —
     * the server stops sending and the client unblocks in milliseconds.
     * Safe to call from any thread. No-op if no call is active.
     */
    fun cancelActiveRequest() {
        activeCall.getAndSet(null)?.cancel()
    }

    /** Set by AgentLoop to cooperatively interrupt the SSE stream mid-response. */
    @Volatile var shouldInterruptStream = false

    companion object {
        /** Sourcegraph API path for chat completions (from OpenAPI spec). */
        const val CHAT_COMPLETIONS_PATH = "/.api/llm/chat/completions"

        /** Sourcegraph API path for listing available models. */
        const val MODELS_PATH = "/.api/llm/models"

        /**
         * Monotonic counter for XML-synthesized tool call IDs. Must be
         * process-wide (not per-response, not per-instance): the webview's
         * tool call map is keyed by these IDs and survives both cross-turn
         * LLM calls and client recreation on model fallback.
         */
        private val xmlToolIdCounter = AtomicInteger(0)
    }

    // Uses longer read timeout (120s) than default (30s) because LLM calls are slow.
    // RetryInterceptor handles 429/5xx with exponential backoff (1s, 2s, 4s).
    // Auth uses TOKEN scheme: "Authorization: token TOKEN_VALUE" per Sourcegraph spec.
    // Force HTTP/1.1 to avoid HTTP/2 RST_STREAM (CANCEL) errors from corporate proxies
    // and Sourcegraph gateway timeouts. HTTP/2 stream multiplexing isn't needed here
    // since we make one LLM call at a time per session.
    private val httpClient: OkHttpClient by lazy {
        httpClientOverride ?: OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .eventListener(ChatHttpEventListener())
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
                    else -> mapErrorResponse(response.code, body, response)
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
                    // Convert tool result to user message with plain text prefix.
                    // Do NOT use XML tags like <tool_result> — they prime the LLM to
                    // generate <tool_calls> as text instead of using the structured API.
                    // Plain text labels (matching OpenHands/SWE-agent pattern) avoid this.
                    val toolContent = "TOOL RESULT:\n${msg.content ?: ""}"
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
        // IMPORTANT: When the assistant makes tool calls, the API often returns content=null.
        // But Anthropic/Sourcegraph rejects empty content in conversation history. We need a
        // placeholder. Requirements:
        // 1. Not natural language — LLM will echo it back as a response (known stuck loop)
        // 2. Not XML that looks like tool syntax — LLM reproduces <tool_calls/> verbatim
        // 3. Short and obviously structural — won't be mistaken for actual output
        // Using a unicode marker that no LLM would generate as a natural response:
        for (i in merged.indices) {
            val msg = merged[i]
            if (msg.role == "assistant" && msg.content.isNullOrBlank() && !msg.toolCalls.isNullOrEmpty()) {
                merged[i] = ChatMessage(
                    role = "assistant",
                    content = "\u200B", // zero-width space — invisible, non-empty, impossible to echo
                    toolCalls = msg.toolCalls
                )
            }
        }

        // Phase 4: ensure conversation starts with "user"
        if (merged.isNotEmpty() && merged.first().role != "user") {
            merged.add(0, ChatMessage(role = "user", content = "[Context follows]"))
        }

        // Phase 5: ensure no two consecutive same-role messages after removals
        val result = mutableListOf<ChatMessage>()
        for (msg in merged) {
            val last = result.lastOrNull()
            if (last != null && last.role == msg.role && last.toolCalls == null && msg.toolCalls == null) {
                result[result.size - 1] = ChatMessage(
                    role = msg.role,
                    content = "${last.content ?: ""}\n\n${msg.content ?: ""}"
                )
            } else {
                result.add(msg)
            }
        }

        return result
    }

    /**
     * Convert parsed XML tool-use blocks into [ToolCall] DTOs, assigning each
     * one a fresh id from the process-wide [xmlToolIdCounter]. Used by both
     * the streaming and non-streaming paths when the model replies in Cline's
     * XML tool format instead of native function calls.
     */
    private fun xmlBlocksToToolCalls(blocks: List<ToolUseContent>): List<ToolCall> =
        blocks.map { block ->
            val argsJson = kotlinx.serialization.json.buildJsonObject {
                block.params.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
            }.toString()
            ToolCall(
                id = "xmltool_${xmlToolIdCounter.incrementAndGet()}",
                function = FunctionCall(name = block.name, arguments = argsJson)
            )
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
        onChunk: suspend (StreamChunk) -> Unit,
        knownToolNames: Set<String> = emptySet(),
        knownParamNames: Set<String> = emptySet()
    ): ApiResult<ChatCompletionResponse> = withContext(Dispatchers.IO) {
        try {
            val sanitized = sanitizeMessages(messages)
            val request = ChatCompletionRequest(
                model = model,
                messages = sanitized,
                tools = tools?.takeIf { it.isNotEmpty() },
                toolChoice = null,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = true,
                streamOptions = StreamOptions(includeUsage = true)
            )

            val jsonBody = json.encodeToString(request)
            log.debug("[Agent:API] POST $CHAT_COMPLETIONS_PATH (stream, ${jsonBody.length} chars)")

            // Dump request for streaming calls too
            dumpApiRequest(sanitized, tools, jsonBody.length)

            val httpRequest = Request.Builder()
                .url(chatCompletionsUrl())
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val call = httpClient.newCall(httpRequest)
            activeCall.set(call)
            try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    activeCall.set(null)
                    val body = response.body?.string() ?: ""
                    dumpApiError(response.code, body)
                    return@withContext mapErrorResponse(response.code, body, response)
                }

                val reader = response.body?.byteStream()?.bufferedReader()
                    ?: run {
                        activeCall.set(null)
                        return@withContext ApiResult.Error(
                            ErrorType.PARSE_ERROR, "Empty response body for stream"
                        )
                    }

                val contentBuilder = StringBuilder()
                val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()
                var role = "assistant"
                var finishReason = "stop"
                var streamUsage: UsageInfo? = null

                // Read SSE lines with cancellation check on every line.
                // When cancelActiveRequest() is called, call.cancel() closes the socket,
                // causing readLine() to throw IOException — breaking out instantly.
                // The ensureActive() is a secondary guard for coroutine-level cancellation.
                var line = reader.readLine()
                while (line != null) {
                    coroutineContext.ensureActive()
                    if (shouldInterruptStream) {
                        log.info("[Agent:API] Stream interrupted by caller (mid-stream tool execution)")
                        break
                    }

                    if (line.startsWith("data: ") && line != "data: [DONE]") {
                        val chunkJson = line.removePrefix("data: ")
                        try {
                            val chunk = json.decodeFromString<StreamChunk>(chunkJson)
                            onChunk(chunk)

                            chunk.choices.firstOrNull()?.let { choice ->
                                // Capture finish_reason from the final chunk
                                val fr = choice.finishReason
                                if (!fr.isNullOrBlank() && fr != "") {
                                    finishReason = fr
                                }
                                choice.delta.let { delta ->
                                    delta.role?.let { role = it }
                                    delta.content?.let { contentBuilder.append(it) }
                                    delta.toolCalls?.forEach { tc ->
                                        val builder = toolCallBuilders.getOrPut(tc.index) { ToolCallBuilder() }
                                        tc.id?.let { builder.id.append(it) }
                                        tc.function?.name?.let { builder.name.append(it) }
                                        tc.function?.arguments?.let { builder.arguments.append(it) }
                                        log.debug("[Agent:API] SSE tool delta: idx=${tc.index} id=${tc.id} name=${tc.function?.name} args=${tc.function?.arguments?.take(50)}")
                                    }
                                }
                            }
                            // Capture usage from the final chunk (Sourcegraph sends it with finish_reason)
                            chunk.usage?.let { streamUsage = it }
                        } catch (e: Exception) {
                            log.debug("[Agent:API] Skipping malformed SSE chunk: ${e.message}")
                        }
                    }
                    line = reader.readLine()
                }
                shouldInterruptStream = false  // Reset for next call

                // Detect streaming drop: Sourcegraph occasionally sends finish_reason=tool_calls
                // but omits the tool_call deltas entirely, leaving us with content-only "Using tools."
                // and an empty toolCallBuilders map. Fall back to non-streaming to recover the tool calls.
                if (finishReason == "tool_calls" && toolCallBuilders.isEmpty()) {
                    log.warn("[Agent:API] Stream finished with finish_reason=tool_calls but no tool_call deltas received — falling back to non-streaming to recover tool calls")
                    return@withContext sendMessage(
                        messages = messages,
                        tools = tools,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        knownToolNames = knownToolNames,
                        knownParamNames = knownParamNames
                    )
                }

                val toolCalls = if (toolCallBuilders.isNotEmpty()) {
                    toolCallBuilders.entries
                        .sortedBy { it.key }
                        .flatMap { entry ->
                            val tc = entry.value.toToolCall()
                            // Detect concatenated JSON objects: {"a":"b"}{"c":"d"}
                            // This happens when the Sourcegraph API merges parallel tool calls.
                            val args = tc.function.arguments.trim()
                            if (args.contains("}{")) {
                                log.warn("[Agent:API] Detected concatenated JSON in tool call '${tc.function.name}', attempting to split")
                                // Try to split on }{ boundary and keep only the first valid JSON object.
                                // The rest are lost, but at least one tool call succeeds instead of zero.
                                val firstJson = args.substringBefore("}{") + "}"
                                try {
                                    // Validate the extracted JSON is parseable
                                    json.decodeFromString<kotlinx.serialization.json.JsonObject>(firstJson)
                                    log.info("[Agent:API] Successfully extracted first tool call from concatenated JSON")
                                    listOf(ToolCall(
                                        id = tc.id,
                                        function = FunctionCall(name = tc.function.name, arguments = firstJson)
                                    ))
                                } catch (_: Exception) {
                                    log.warn("[Agent:API] Failed to extract valid JSON from concatenated tool call")
                                    emptyList()
                                }
                            } else {
                                listOf(tc)
                            }
                        }
                        .filter { it.function.name.isNotBlank() } // Drop tool calls with empty names
                        .ifEmpty { null }
                } else null

                // Log assembled tool calls for debugging
                if (toolCallBuilders.isNotEmpty()) {
                    toolCallBuilders.entries.sortedBy { it.key }.forEach { (idx, b) ->
                        log.info("[Agent:API] Assembled tool call #$idx: name='${b.name}' id='${b.id}' args(${b.arguments.length} chars)='${b.arguments.toString().take(100)}'")
                    }
                    log.info("[Agent:API] Final valid tool calls: ${toolCalls?.size ?: 0}, finishReason=$finishReason")
                }

                // --- Parse content blocks via AssistantMessageParser ---
                // Re-parse the full accumulated text to extract tool calls.
                // In accumulate mode, this happens once at stream end.
                val rawText = contentBuilder.toString()
                val parsedBlocks = if (toolCalls == null || toolCalls.isEmpty()) {
                    AssistantMessageParser.parse(rawText, knownToolNames, knownParamNames)
                        .takeIf { blocks -> blocks.any { it is ToolUseContent } }
                } else null

                val finalToolCalls = toolCalls ?: parsedBlocks
                    ?.filterIsInstance<ToolUseContent>()
                    ?.filter { !it.partial }
                    ?.let(::xmlBlocksToToolCalls)
                    ?.ifEmpty { null }

                val textOnlyContent = if (parsedBlocks != null) {
                    parsedBlocks.filterIsInstance<TextContent>()
                        .joinToString("\n\n") { it.content }
                        .ifBlank { null }
                } else {
                    rawText.ifBlank { null }
                }

                val finalFinishReason = if (finalToolCalls != null && finishReason == "stop") {
                    "tool_calls"
                } else {
                    finishReason
                }

                // Signal truncation for partial tool calls
                var finalContent = textOnlyContent
                if (parsedBlocks?.any { it is ToolUseContent && it.partial } == true && finalToolCalls.isNullOrEmpty()) {
                    log.warn("[Agent:API] XML tool call truncated — signaling for retry")
                    finalContent = (finalContent ?: "") + "\n\n[TOOL_CALL_TRUNCATED]"
                }

                val finalMessage = ChatMessage(
                    role = role,
                    content = finalContent,
                    toolCalls = finalToolCalls
                )

                activeCall.set(null)

                val streamResponse = ChatCompletionResponse(
                    id = "stream-${System.nanoTime()}",
                    choices = listOf(Choice(index = 0, message = finalMessage, finishReason = finalFinishReason)),
                    usage = streamUsage
                )
                dumpApiResponse(streamResponse)
                ApiResult.Success(streamResponse)
            }
            } finally {
                activeCall.set(null)
            }
        } catch (e: CancellationException) {
            log.info("[Agent:API] Stream cancelled by user")
            activeCall.getAndSet(null)?.cancel()
            throw e // re-throw so coroutine machinery handles it
        } catch (e: IOException) {
            activeCall.set(null)
            // Distinguish cancel-induced IOException from real network errors
            if (e.message?.contains("Canceled") == true || e.message?.contains("closed") == true) {
                log.info("[Agent:API] Stream aborted (cancel)")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Request cancelled", e)
            } else {
                log.warn("[Agent:API] Stream network error: ${e.message}", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Stream error: ${e.message}", e)
            }
        } catch (e: Exception) {
            activeCall.set(null)
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
        toolChoice: JsonElement? = null, // Accepted but not sent — not in Sourcegraph API spec
        knownToolNames: Set<String> = emptySet(),
        knownParamNames: Set<String> = emptySet()
    ): ApiResult<ChatCompletionResponse> = withContext(Dispatchers.IO) {
        try {
            val sanitized = sanitizeMessages(messages)
            val request = ChatCompletionRequest(
                model = model,
                messages = sanitized,
                tools = tools?.takeIf { it.isNotEmpty() },
                toolChoice = null,
                temperature = temperature,
                maxTokens = maxTokens
            )

            val jsonBody = json.encodeToString(request)
            log.debug("[Agent:API] POST $CHAT_COMPLETIONS_PATH (${jsonBody.length} chars)")

            // Dump full request/response to file for debugging multi-turn issues
            dumpApiRequest(sanitized, tools, jsonBody.length)

            val httpRequest = Request.Builder()
                .url(chatCompletionsUrl())
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val call = httpClient.newCall(httpRequest)
            activeCall.set(call)
            try {
                call.execute().use { response ->
                    activeCall.set(null)
                    val body = response.body?.string() ?: ""

                    when {
                        response.isSuccessful -> {
                            val parsed = json.decodeFromString<ChatCompletionResponse>(body)
                            log.debug("[Agent:API] Response: ${parsed.usage?.totalTokens} tokens")

                            // --- XML tool call fallback for non-streaming path ---
                            val choice = parsed.choices.firstOrNull()
                            val requestHadNoTools = tools.isNullOrEmpty()
                            if (requestHadNoTools && choice != null && choice.message.toolCalls.isNullOrEmpty()) {
                                val content = choice.message.content
                                if (content != null) {
                                    val parsedBlocks = AssistantMessageParser.parse(content, knownToolNames, knownParamNames)
                                    val xmlToolCalls = parsedBlocks.filterIsInstance<ToolUseContent>()
                                        .filter { !it.partial }
                                    if (xmlToolCalls.isNotEmpty()) {
                                        log.info("[Agent:API] Extracted ${xmlToolCalls.size} XML tool call(s) from non-streaming response")
                                        val textOnly = parsedBlocks.filterIsInstance<TextContent>()
                                            .joinToString("\n\n") { it.content }.ifBlank { null }
                                        val toolCallDtos = xmlBlocksToToolCalls(xmlToolCalls)
                                        val updatedMsg = choice.message.copy(content = textOnly, toolCalls = toolCallDtos)
                                        val updatedFr = if (choice.finishReason == "stop") "tool_calls" else choice.finishReason
                                        val updatedResp = parsed.copy(choices = listOf(choice.copy(message = updatedMsg, finishReason = updatedFr)))
                                        dumpApiResponse(updatedResp)
                                        return@withContext ApiResult.Success(updatedResp)
                                    }
                                }
                            }

                            dumpApiResponse(parsed)
                            ApiResult.Success(parsed)
                        }
                        else -> {
                            dumpApiError(response.code, body)
                            mapErrorResponse(response.code, body, response)
                        }
                    }
                }
            } finally {
                activeCall.set(null)
            }
        } catch (e: CancellationException) {
            throw e // Never swallow CancellationException — propagate for structured concurrency
        } catch (e: IOException) {
            log.warn("[Agent:API] Network error: ${e.message}", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Network error: ${e.message}", e)
        } catch (e: Exception) {
            log.error("[Agent:API] Unexpected error: ${e.message}", e)
            ApiResult.Error(ErrorType.PARSE_ERROR, "Unexpected error: ${e.message}", e)
        }
    }

    /**
     * Map HTTP error codes to typed ApiResult.Error.
     *
     * For 429 (rate limited), parses retry-after headers from the response
     * to provide a delay hint. Ported from Cline's retry.ts header parsing:
     * checks retry-after, x-ratelimit-reset, ratelimit-reset in priority order.
     *
     * @param code HTTP status code
     * @param body response body string
     * @param response optional OkHttp response for extracting rate-limit headers
     */
    private fun <T> mapErrorResponse(code: Int, body: String, response: Response? = null): ApiResult<T> = when {
        code == 401 || code == 403 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Authentication failed ($code)")
        code == 429 -> {
            val retryAfterMs = response?.let { parseRetryAfterHeaders(it) }
            ApiResult.Error(
                ErrorType.RATE_LIMITED,
                "Rate limited. Retry after delay.",
                retryAfterMs = retryAfterMs
            )
        }
        body.contains("context_length", ignoreCase = true) -> ApiResult.Error(
            ErrorType.CONTEXT_LENGTH_EXCEEDED,
            "Context length exceeded ($code): $body"
        )
        code in 500..599 -> ApiResult.Error(ErrorType.SERVER_ERROR, "Server error ($code): $body")
        else -> ApiResult.Error(ErrorType.VALIDATION_ERROR, "Unexpected response ($code): $body")
    }

    /**
     * Parse rate-limit headers from an HTTP response.
     *
     * Faithful port of Cline's retry.ts header parsing:
     * ```js
     * const retryAfter = error.headers?.["retry-after"]
     *     || error.headers?.["x-ratelimit-reset"]
     *     || error.headers?.["ratelimit-reset"]
     * ```
     *
     * Handles both delta-seconds and Unix timestamp formats.
     *
     * @return delay in milliseconds, or null if no retry-after info found
     */
    private fun parseRetryAfterHeaders(response: Response): Long? {
        val headerValue = response.header("retry-after")
            ?: response.header("x-ratelimit-reset")
            ?: response.header("ratelimit-reset")
            ?: return null

        val retryValue = headerValue.trim().toLongOrNull() ?: return null

        val nowSeconds = System.currentTimeMillis() / 1000
        return if (retryValue > nowSeconds) {
            // Unix timestamp
            (retryValue * 1000 - System.currentTimeMillis()).coerceAtLeast(0)
        } else {
            // Delta seconds
            retryValue * 1000
        }
    }

    private class ToolCallBuilder {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()

        fun toToolCall() = ToolCall(
            id = id.toString(),
            function = FunctionCall(name = name.toString(), arguments = arguments.toString())
        )
    }

    // ═══ API Debug Logging ═══
    // Dumps full request/response to the active session's api-debug/ subdirectory.
    // No-ops when no session is active (e.g., commit message generation).

    /** Session-scoped directory for API debug dumps. Set by caller when a session is active. */
    @Volatile var apiDebugSessionDir: java.io.File? = null

    private val apiDebugDir: java.io.File? get() {
        val sessionDir = apiDebugSessionDir
        return if (sessionDir != null) {
            java.io.File(sessionDir, "api-debug").also { it.mkdirs() }
        } else null
    }

    /**
     * Session-scoped API call counter, injected by the caller (e.g. AgentService) so
     * multiple SourcegraphChatClient instances within one logical task share monotonic
     * call numbering. When set, [nextCallNumber] increments this atomic; when null,
     * [localApiCallCounter] is used as a per-client fallback.
     *
     * The session owns the counter's lifecycle — clients only read/increment it.
     * This makes brain recycling and multi-message chats produce non-overlapping
     * `call-NNN-{request,response,error}.txt` filenames in `api-debug/`.
     *
     * Write access is restricted to [setSharedApiCallCounter] so callers can't
     * silently swap the counter mid-call and desynchronise [lastDumpedCallNum].
     */
    @Volatile var sharedApiCallCounter: AtomicInteger? = null
        private set

    /** Inject the session-scoped API call counter. See [sharedApiCallCounter]. */
    fun setSharedApiCallCounter(counter: AtomicInteger) {
        sharedApiCallCounter = counter
    }

    /** Local fallback counter for callers that don't inject a shared one (tests, commit msg). */
    @Volatile private var localApiCallCounter = 0

    /**
     * The call number this client most recently assigned via [nextCallNumber].
     * Used by [dumpApiResponse] and [dumpApiError] to write to the same call number
     * as the matching request — even when other clients (e.g. a recycled brain) have
     * since incremented the shared counter.
     */
    @Volatile private var lastDumpedCallNum = 0

    /** Increment the active counter (shared if injected, otherwise local) and remember it. */
    private fun nextCallNumber(): Int {
        val n = sharedApiCallCounter?.incrementAndGet() ?: ++localApiCallCounter
        lastDumpedCallNum = n
        return n
    }

    /** Reset the API call counter — call when starting a new session that doesn't inject a shared counter. */
    fun resetApiCallCounter() {
        localApiCallCounter = 0
        lastDumpedCallNum = 0
        // Note: sharedApiCallCounter (if set) is owned by the session and not reset here.
    }

    /**
     * Detach any previously-injected [sharedApiCallCounter] and resume using the
     * local counter. Used by sub-agent runners so their api-debug dumps number
     * independently of the parent session — otherwise a sub-agent spawned mid-task
     * would write `call-042-*.txt` into its own debug dir while the parent was
     * writing `call-043-*.txt` into the session dir, making dumps hard to correlate.
     */
    fun detachSharedApiCallCounter() {
        sharedApiCallCounter = null
        localApiCallCounter = 0
        lastDumpedCallNum = 0
    }

    private fun dumpApiRequest(messages: List<ChatMessage>, tools: List<ToolDefinition>?, bodyLength: Int) {
        val dir = apiDebugDir ?: return
        try {
            val idx = nextCallNumber()
            val file = java.io.File(dir, "call-${String.format("%03d", idx)}-request.txt")
            file.writeText(buildString {
                appendLine("=== API Request #$idx === ${java.time.Instant.now()} ===")
                appendLine("Model: $model")
                appendLine("Body length: $bodyLength chars")
                appendLine("Messages: ${messages.size}")
                appendLine("Tools: ${tools?.size ?: 0}")
                appendLine()

                messages.forEachIndexed { i, msg ->
                    appendLine("--- Message $i [role=${msg.role}] ---")
                    val content = msg.content
                    if (msg.role == "tool" && content != null) {
                        appendLine(sanitizeForDebug(content))
                    } else {
                        appendLine(content ?: "(null)")
                    }
                    msg.toolCalls?.forEach { tc ->
                        appendLine("  [tool_call] ${tc.function.name}(${sanitizeForDebug(tc.function.arguments)})")
                    }
                    appendLine()
                }

                if (tools != null && tools.isNotEmpty()) {
                    appendLine("=== Tool Schemas (${tools.size}) ===")
                    tools.forEach { tool ->
                        val fn = tool.function
                        val params = fn.parameters.properties.entries.joinToString(", ") { (k, v) ->
                            "$k: ${v.type}${if (v.enumValues != null) " [${v.enumValues.joinToString("|")}]" else ""}"
                        }
                        val req = fn.parameters.required.joinToString(", ")
                        appendLine("  ${fn.name}($params) required=[$req]")
                        appendLine("    ${fn.description.take(120)}")
                    }
                    appendLine()
                }
            })
            log.info("[Agent:API] Request dumped to ${file.name} (${messages.size} messages, ${tools?.size ?: 0} tools, $bodyLength chars)")
        } catch (e: Exception) {
            log.debug("[Agent:API] Failed to dump request: ${e.message}")
        }
    }

    private fun dumpApiResponse(response: ChatCompletionResponse) {
        val dir = apiDebugDir ?: return
        try {
            val idx = lastDumpedCallNum
            val file = java.io.File(dir, "call-${String.format("%03d", idx)}-response.txt")
            val choice = response.choices.firstOrNull()
            file.writeText(buildString {
                appendLine("=== API Response #$idx === ${java.time.Instant.now()} ===")
                appendLine("Usage: prompt=${response.usage?.promptTokens} completion=${response.usage?.completionTokens} total=${response.usage?.totalTokens}")
                appendLine("FinishReason: ${choice?.finishReason}")
                appendLine("Tool calls: ${choice?.message?.toolCalls?.size ?: 0}")
                appendLine()
                appendLine("--- Content ---")
                appendLine(choice?.message?.content ?: "(null)")
                choice?.message?.toolCalls?.forEach { tc ->
                    appendLine()
                    appendLine("--- Tool Call: ${tc.function.name} ---")
                    appendLine(sanitizeForDebug(tc.function.arguments))
                }
            })
            log.info("[Agent:API] Response dumped to ${file.name} (finish=${choice?.finishReason}, tools=${choice?.message?.toolCalls?.size ?: 0})")
        } catch (e: Exception) {
            log.debug("[Agent:API] Failed to dump response: ${e.message}")
        }
    }

    /**
     * Scrub likely credentials from text before writing to debug files.
     * Matches common patterns: password, token, secret, api_key, auth, bearer, credential.
     */
    private fun sanitizeForDebug(text: String): String {
        return text.replace(
            Regex("""("(?:password|token|secret|api_key|apiKey|api-key|auth|bearer|credential|private_key|privateKey)["\s]*[:=]\s*")([^"]{4,})""", RegexOption.IGNORE_CASE)
        ) { match ->
            "${match.groupValues[1]}***REDACTED***"
        }
    }

    private fun dumpApiError(code: Int, body: String) {
        val dir = apiDebugDir ?: return
        try {
            val idx = lastDumpedCallNum
            val file = java.io.File(dir, "call-${String.format("%03d", idx)}-error.txt")
            file.writeText(buildString {
                appendLine("=== API Error #$idx === ${java.time.Instant.now()} ===")
                appendLine("HTTP $code")
                appendLine(body)
            })
        } catch (e: Exception) {
            // Match dumpApiRequest/dumpApiResponse: log at debug so dump failures
            // are observable in idea.log without polluting warn-level output.
            log.debug("[Agent:API] Failed to dump error: ${e.message}")
        }
    }
}
