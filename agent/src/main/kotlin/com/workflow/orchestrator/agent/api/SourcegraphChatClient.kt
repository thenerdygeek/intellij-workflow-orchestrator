package com.workflow.orchestrator.agent.api

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.api.dto.*
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
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class SourcegraphChatClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val model: String,
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 120,
    httpClientOverride: OkHttpClient? = null
) {
    private val log = Logger.getInstance(SourcegraphChatClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // Uses longer read timeout (120s) than default (30s) because LLM calls are slow.
    // RetryInterceptor handles 429/5xx with exponential backoff (1s, 2s, 4s).
    private val httpClient: OkHttpClient by lazy {
        httpClientOverride ?: OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.TOKEN))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    /**
     * Send a streaming chat completion request. Each SSE chunk is emitted via [onChunk]
     * for real-time UI updates. The accumulated response is returned as a single
     * [ChatCompletionResponse] when the stream completes.
     */
    suspend fun sendMessageStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int? = null,
        temperature: Double = 0.0,
        onChunk: suspend (StreamChunk) -> Unit
    ): ApiResult<ChatCompletionResponse> = withContext(Dispatchers.IO) {
        try {
            val request = ChatCompletionRequest(
                model = model,
                messages = messages,
                tools = tools?.takeIf { it.isNotEmpty() },
                toolChoice = if (tools?.isNotEmpty() == true) JsonPrimitive("auto") else null,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = true
            )

            val jsonBody = json.encodeToString(request)
            log.debug("[Agent:API] POST /chat/completions (stream, ${jsonBody.length} chars)")

            val httpRequest = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
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
                    toolCallBuilders.entries.sortedBy { it.key }.map { it.value.toToolCall() }
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

    suspend fun sendMessage(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int? = null,
        temperature: Double = 0.0,
        toolChoice: JsonElement? = null
    ): ApiResult<ChatCompletionResponse> = withContext(Dispatchers.IO) {
        try {
            val resolvedToolChoice = toolChoice
                ?: if (tools?.isNotEmpty() == true) JsonPrimitive("auto") else null

            val request = ChatCompletionRequest(
                model = model,
                messages = messages,
                tools = tools?.takeIf { it.isNotEmpty() },
                toolChoice = resolvedToolChoice,
                temperature = temperature,
                maxTokens = maxTokens
            )

            val jsonBody = json.encodeToString(request)
            log.debug("[Agent:API] POST /chat/completions (${jsonBody.length} chars)")

            val httpRequest = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
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
