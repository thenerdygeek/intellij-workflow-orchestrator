package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.ai.dto.CompletionStreamRequest
import com.workflow.orchestrator.core.ai.dto.CompletionStreamResult
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.ChatHttpEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * Thrown when the Sourcegraph stream endpoint returns a non-2xx response.
 *
 * The status code is exposed so callers can branch on auth (401), context-length
 * (413), rate limit (429), etc. without re-parsing the message string.
 */
class HttpException(val statusCode: Int, message: String) : RuntimeException(message)

/**
 * HTTP client for Sourcegraph's `/.api/completions/stream` (Cody-shape) endpoint.
 *
 * Multimodal-agent Phase 3 — pure wire layer. NO routing logic, NO UI wiring,
 * NO message sanitization. Phase 6 (BrainRouter) decides when to use this client
 * vs. the existing [SourcegraphChatClient].
 *
 * **Why a separate client:** the existing `/.api/llm/chat/completions` endpoint
 * (Format A) does NOT accept `image_url` content parts. The legacy
 * `/.api/completions/stream` endpoint (Format B) does, but uses a Cody-specific
 * body shape (`speaker` instead of `role`, `maxTokensToSample` instead of
 * `max_tokens`) and SSE response format (`event: completion / data: {deltaText}`).
 * See spec §Wire formats.
 *
 * **Construction:** mirrors the [SourcegraphChatClient] + [ModelCatalogService]
 * pattern — direct `OkHttpClient.Builder()` construction with `AuthInterceptor`
 * (TOKEN scheme) + `ChatHttpEventListener`. Does NOT go through
 * `HttpClientFactory.clientFor(SOURCEGRAPH)` per the Sourcegraph isolation
 * policy (`project_sourcegraph_isolation.md`): shared interceptors and the
 * shared connection pool are not safe on Sourcegraph endpoints. Tests inject
 * `httpClientOverride` to use `MockWebServer` without auth.
 *
 * **Stream API version:** read from [ModelCatalogService.getLatestStreamApiVersion]
 * at request time (defaults to 8 when client-config not loaded). Appended as
 * `?api-version=N` to the URL.
 *
 * **End-of-stream termination:** delegated to [CodyStreamSseParser]. Three signals
 * (whichever first): `event: done`, `data: [DONE]`, or EOF.
 *
 * Spec: `docs/research/2026-05-02-multimodal-agent-design.md` §Wire formats > Format B
 */
class SourcegraphCompletionsStreamClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val modelCatalogService: ModelCatalogService,
    httpClientOverride: OkHttpClient? = null,
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 120,
) {
    private val log = Logger.getInstance(SourcegraphCompletionsStreamClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val parser = CodyStreamSseParser()

    /**
     * Plain `OkHttpClient` per Sourcegraph isolation policy. Force HTTP/1.1 to
     * avoid the HTTP/2 RST_STREAM (CANCEL) errors observed on the chat client
     * (corporate proxies + Sourcegraph gateway timeouts). Auth via
     * [AuthInterceptor] with [AuthScheme.TOKEN] — emits `Authorization: token <sgp_...>`
     * automatically; do NOT manually set the header in request builders.
     */
    private val httpClient: OkHttpClient by lazy {
        httpClientOverride ?: OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .eventListener(ChatHttpEventListener())
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.TOKEN))
            .build()
    }

    /**
     * Send the stream request and return the assembled assistant text once the
     * stream completes.
     *
     * [onDelta] fires once per incremental text fragment, in order. Both frame
     * shapes funnel through it:
     * - For [CodyStreamSseParser.ParseResult.TextDelta] frames: the delta text
     *   is appended to internal accumulator and forwarded as-is to [onDelta].
     * - For [CodyStreamSseParser.ParseResult.TextReplacement] frames (api-version
     *   1 cumulative): the new tail is computed by stripping the previously-known
     *   accumulator prefix; only that tail is forwarded so callers see incremental
     *   updates regardless of which wire shape the server uses.
     *
     * Token sanity: `tokenProvider()` must return non-null. The provider is
     * called once per `chat()` and the result is buried inside
     * [AuthInterceptor]'s closure — but we still pre-check here so callers get a
     * clear `IllegalStateException` instead of an opaque 401 from the gateway.
     *
     * @throws HttpException on non-2xx responses
     * @throws IllegalStateException if no Sourcegraph token is configured
     */
    suspend fun chat(
        request: CompletionStreamRequest,
        onDelta: suspend (String) -> Unit = {},
    ): CompletionStreamResult = withContext(Dispatchers.IO) {
        if (tokenProvider() == null) {
            throw IllegalStateException("no Sourcegraph token configured")
        }
        val apiVersion = modelCatalogService.getLatestStreamApiVersion()
        val url = "${baseUrl.trimEnd('/')}$STREAM_PATH?api-version=$apiVersion"
        val body = json.encodeToString(CompletionStreamRequest.serializer(), request)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val httpRequest = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(body)
            .build()

        log.debug("[Agent:API] POST $STREAM_PATH?api-version=$apiVersion (${request.messages.size} messages)")

        val started = System.currentTimeMillis()
        httpClient.newCall(httpRequest).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string()?.take(500) ?: ""
                log.warn("[Agent:API] stream endpoint returned ${resp.code}: ${errBody.take(200)}")
                throw HttpException(resp.code, "stream endpoint returned ${resp.code}: $errBody")
            }
            val accumulated = StringBuilder()
            var stopReason: String? = null
            val reader: BufferedReader = resp.body!!.charStream().buffered()
            parser.parse(reader) { result ->
                when (result) {
                    is CodyStreamSseParser.ParseResult.TextDelta -> {
                        accumulated.append(result.text)
                        onDelta(result.text)
                    }
                    is CodyStreamSseParser.ParseResult.TextReplacement -> {
                        // api-version 1 cumulative: compute the diff between previous
                        // accumulated text and this new full text; emit only the new tail
                        // via onDelta so callers see incremental updates either way.
                        val prev = accumulated.toString()
                        val newTail = if (result.text.startsWith(prev)) {
                            result.text.substring(prev.length)
                        } else {
                            // Defensive: gateway sent non-cumulative replacement —
                            // emit full so we never lose content.
                            result.text
                        }
                        accumulated.clear()
                        accumulated.append(result.text)
                        if (newTail.isNotEmpty()) onDelta(newTail)
                    }
                    is CodyStreamSseParser.ParseResult.StreamDone -> {
                        // No-op; parser has already returned and the loop will exit.
                    }
                    is CodyStreamSseParser.ParseResult.Error -> {
                        log.warn("[Agent:API] stream parse error frame: ${result.message}")
                    }
                }
            }
            CompletionStreamResult(
                text = accumulated.toString(),
                stopReason = stopReason,
                durationMs = System.currentTimeMillis() - started
            )
        }
    }

    companion object {
        /** Sourcegraph (Cody) legacy completions stream path. */
        const val STREAM_PATH = "/.api/completions/stream"
    }
}
