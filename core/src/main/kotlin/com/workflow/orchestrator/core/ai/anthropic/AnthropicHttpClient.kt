package com.workflow.orchestrator.core.ai.anthropic

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.ai.protocol.AnthropicNativeProtocol
import com.workflow.orchestrator.core.http.IdeProxy
import com.workflow.orchestrator.core.http.IdeTrust
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Proxy-aware OkHttp transport for the Anthropic Messages API streaming endpoint.
 *
 * Built on the `:web` triad ([IdeProxy]/[IdeTrust]) so the client honours corporate
 * SSL-inspection CAs and IDE-configured proxies without any user-facing `keytool` steps.
 * Intentionally isolated from [com.workflow.orchestrator.core.http.HttpClientFactory]
 * (direct Anthropic traffic must not share the shared connection pool / interceptor chain).
 *
 * Wire-format contract:
 * - Serialises [AnthropicRequest] with `Json { explicitNulls = false }` so nullable
 *   fields (ContentBlock.input/source/toolUseId/content) are omitted rather than
 *   emitted as `"field":null`. No sampling parameters (temperature/top_p/top_k) are
 *   declared in the DTO and therefore can never appear in output.
 * - Headers: `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`.
 * - Endpoint: `{baseUrl}/v1/messages` (POST).
 *
 * Error mapping (non-2xx):
 * - 429  → [ErrorType.RATE_LIMITED] (Retry-After header parsed → retryAfterMs)
 * - 529  → [ErrorType.SERVER_ERROR]
 * - 413  → [ErrorType.CONTEXT_LENGTH_EXCEEDED]
 * - 400  → [ErrorType.VALIDATION_ERROR]
 * - 401  → [ErrorType.AUTH_FAILED]
 * - 403  → [ErrorType.FORBIDDEN]
 * - other→ [ErrorType.SERVER_ERROR]
 * - [SocketTimeoutException] → [ErrorType.TIMEOUT]
 * - [IOException] → [ErrorType.NETWORK_ERROR]
 *
 * Debug dumps: when [debugDir] is non-null a timestamped `.request.json` file is written
 * for each call. The `x-api-key` value is **never** written to the dump file.
 *
 * Phase 4a Task 8.
 */
class AnthropicHttpClient(
    private val baseUrl: String,
    private val apiKey: String,
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 180,
    private val debugDir: File? = null,
) : AnthropicHttpTransport {

    private val log = Logger.getInstance(AnthropicHttpClient::class.java)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
        .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
        .proxySelector(IdeProxy.selector())
        .proxyAuthenticator(IdeProxy.proxyAuthenticator())
        .let { IdeTrust.applyTo(it) }
        .build()

    /** Serialiser that drops null fields to produce clean Anthropic wire payloads. */
    private val wireJson = Json { explicitNulls = false }

    private val protocol = AnthropicNativeProtocol()

    /** Counter for unique debug file names within a single client instance. */
    private val debugCounter = AtomicInteger(0)

    // ── Public API ─────────────────────────────────────────────────────────────

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun postStream(
        request: AnthropicRequest,
        onLine: (String) -> Unit,
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val bodyJson = wireJson.encodeToString(AnthropicRequest.serializer(), request)

        debugDir?.let { writeRequestDump(it, bodyJson) }

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = client.newCall(httpRequest)
        // Propagate coroutine cancellation to OkHttp so the brain's interruptStream()/
        // cancelActiveRequest() (which cancel the coroutine) abort the in-flight call
        // instead of blocking up to readTimeout.
        coroutineContext[Job]?.invokeOnCompletion(onCancelling = true) { cause -> if (cause != null) call.cancel() }

        try {
            call.execute().use { response ->
                val statusCode = response.code
                val responseBody = response.body

                if (!response.isSuccessful) {
                    val errorBody = responseBody?.string() ?: ""
                    log.debug("[AnthropicHttpClient] Non-2xx response: $statusCode")
                    return@withContext mapHttpError(statusCode, errorBody, response)
                }

                // Stream SSE lines line-by-line on the IO dispatcher.
                // ResponseBody.source() returns an okio.BufferedSource — already buffered,
                // readUtf8Line() handles LF and CRLF line endings.
                responseBody?.source()?.let { source ->
                    while (!source.exhausted()) {
                        coroutineContext.ensureActive() // stop promptly if cancelled
                        val line = source.readUtf8Line() ?: break
                        onLine(line)
                    }
                }

                ApiResult.Success(Unit)
            }
        } catch (e: SocketTimeoutException) {
            log.debug("[AnthropicHttpClient] Timeout: ${e.message}")
            ApiResult.Error(ErrorType.TIMEOUT, "Request timed out: ${e.message}", cause = e)
        } catch (e: IOException) {
            log.debug("[AnthropicHttpClient] IO error: ${e.message}")
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Network error: ${e.message}", cause = e)
        }
    }

    // ── Error mapping ──────────────────────────────────────────────────────────

    private fun mapHttpError(
        statusCode: Int,
        body: String,
        response: okhttp3.Response,
    ): ApiResult.Error {
        // Use AnthropicNativeProtocol to enrich the error message with the Anthropic error class.
        val errorClass = protocol.classifyHttpError(statusCode, body) ?: "unknown"
        val message = "HTTP $statusCode ($errorClass): ${body.take(MAX_ERROR_BODY_CHARS)}"

        return when (statusCode) {
            HTTP_TOO_MANY_REQUESTS -> {
                val retryAfterMs = parseRetryAfter(response)
                ApiResult.Error(ErrorType.RATE_LIMITED, message, retryAfterMs = retryAfterMs)
            }
            HTTP_OVERLOADED -> ApiResult.Error(ErrorType.SERVER_ERROR, message)
            HTTP_PAYLOAD_TOO_LARGE -> ApiResult.Error(ErrorType.CONTEXT_LENGTH_EXCEEDED, message)
            HTTP_BAD_REQUEST -> ApiResult.Error(ErrorType.VALIDATION_ERROR, message)
            HTTP_UNAUTHORIZED -> ApiResult.Error(ErrorType.AUTH_FAILED, message)
            HTTP_FORBIDDEN -> ApiResult.Error(ErrorType.FORBIDDEN, message)
            else -> ApiResult.Error(ErrorType.SERVER_ERROR, message)
        }
    }

    /** Parses the `Retry-After` header (seconds) into milliseconds, or null if absent/invalid. */
    private fun parseRetryAfter(response: okhttp3.Response): Long? =
        response.header("Retry-After")?.toLongOrNull()?.let { it * MILLIS_PER_SECOND }

    // ── Debug dumps ────────────────────────────────────────────────────────────

    /**
     * Writes a human-readable request dump to [dir].
     * The `x-api-key` value is never written — a redaction marker is written instead.
     */
    private fun writeRequestDump(dir: File, bodyJson: String) {
        dir.mkdirs()
        val counter = debugCounter.incrementAndGet()
        val file = File(dir, "${System.currentTimeMillis()}-$counter.request.json")
        val content = buildString {
            appendLine("// Anthropic HTTP request dump — x-api-key: ***REDACTED***")
            appendLine("// anthropic-version: 2023-06-01")
            appendLine("// POST $baseUrl/v1/messages")
            appendLine()
            append(bodyJson)
        }
        try {
            file.writeText(content)
        } catch (e: Exception) {
            log.warn("[AnthropicHttpClient] Failed to write debug dump: ${e.message}")
        }
    }

    // ── Constants ──────────────────────────────────────────────────────────────

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_OVERLOADED = 529
        const val HTTP_PAYLOAD_TOO_LARGE = 413
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403

        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_ERROR_BODY_CHARS = 500
    }
}
