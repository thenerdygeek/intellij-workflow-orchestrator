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

    suspend fun sendMessage(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int? = null,
        temperature: Double = 0.0
    ): ApiResult<ChatCompletionResponse> = withContext(Dispatchers.IO) {
        try {
            val request = ChatCompletionRequest(
                model = model,
                messages = messages,
                tools = tools?.takeIf { it.isNotEmpty() },
                toolChoice = if (tools?.isNotEmpty() == true) "auto" else null,
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
                    response.code == 401 || response.code == 403 -> {
                        ApiResult.Error(ErrorType.AUTH_FAILED, "Authentication failed (${response.code})")
                    }
                    response.code == 429 -> {
                        ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limited. Retry after delay.")
                    }
                    response.code in 500..599 -> {
                        ApiResult.Error(ErrorType.SERVER_ERROR, "Server error (${response.code}): $body")
                    }
                    else -> {
                        ApiResult.Error(ErrorType.VALIDATION_ERROR, "Unexpected response (${response.code}): $body")
                    }
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
}
