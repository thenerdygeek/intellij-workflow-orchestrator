package com.workflow.orchestrator.agent.ai

import com.workflow.orchestrator.agent.tool.SessionAttachmentAccess
import com.workflow.orchestrator.core.ai.AnthropicModelCatalog
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.ai.anthropic.AnthropicHttpTransport
import com.workflow.orchestrator.core.ai.anthropic.AnthropicRequestMapper
import com.workflow.orchestrator.core.ai.anthropic.AnthropicSseParser
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.ai.dto.StreamChoice
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.StreamDelta
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue

/**
 * [LlmBrain] implementation that uses the native Anthropic Messages API stack:
 * [AnthropicRequestMapper] → [AnthropicHttpTransport] → [AnthropicSseParser].
 *
 * Lives in `:agent` (not `:core`) because image hydration requires
 * [SessionAttachmentAccess], an `:agent`-scoped type.
 *
 * ## Design constraints
 *
 * **temperature no-op.** The Anthropic Messages API returns HTTP 400 when sampling
 * params (temperature / top_p / top_k) appear alongside extended thinking.
 * [AnthropicRequestMapper] already emits no sampling fields; the no-op setter
 * enforces that nothing can accidentally inject them from [LlmBrain] callers.
 *
 * **chat() delegates to chatStream().** The transport is streaming-only; we don't
 * add a second non-streaming HTTP path.
 *
 * **Progressive streaming via a producer/consumer bridge.** Two impedance mismatches
 * are bridged so text reaches [onChunk] *as it streams*, not in one batch at the end:
 * - [AnthropicHttpTransport.postStream] *pushes* raw SSE lines via a `(String) -> Unit`
 *   callback, while [AnthropicSseParser.parse] *pulls* a `Sequence<String>`. A
 *   [java.util.concurrent.LinkedBlockingQueue] bridges them: the transport `put`s each
 *   line as it arrives and the parser's sequence `take`s the next line, blocking until
 *   one is available — so `emitText` fires per line, concurrently with the transport,
 *   instead of after the whole response is buffered.
 * - `emitText` inside the parser is non-suspend, but [onChunk] is suspend. An UNLIMITED
 *   [kotlinx.coroutines.channels.Channel] bridges them: `emitText` does a non-blocking
 *   `trySend` (never fails on an unlimited buffer) and the suspend consumer drains the
 *   channel, calling [onChunk] for each piece progressively.
 *
 * The transport and the parser run as two concurrent `Dispatchers.IO` coroutines (the
 * parser blocks its carrier thread on `take()`, so the transport needs its own thread to
 * feed the queue). The transport ALWAYS enqueues an end-of-stream sentinel in a `finally`
 * — even when `postStream` returns an error or throws `CancellationException` on abort —
 * so the parser's `take()` can never block forever and the consumer loop always ends.
 *
 * **Cancellation.** The producer coroutine is held in [activeJob]; both [interruptStream]
 * and [cancelActiveRequest] cancel it. Cancellation cascades to the child transport
 * coroutine, aborting the in-flight OkHttp call via `invokeOnCompletion`; the transport's
 * `finally` then enqueues end-of-stream, the parser returns, the text channel is closed,
 * and the consumer loop ends — no leak, no deadlock.
 *
 * Phase 4a Task 9.
 */
class AnthropicDirectBrain(
    override val modelId: String,
    private val http: AnthropicHttpTransport,
    private val attachmentAccess: SessionAttachmentAccess,
    private val thinkingEnabled: () -> Boolean,
    private val effort: () -> String,
    override var toolNameSet: Set<String> = emptySet(),
    override var paramNameSet: Set<String> = emptySet(),
) : LlmBrain {

    /**
     * Always 0.0. The setter is deliberately a no-op: the Anthropic Messages API
     * returns HTTP 400 when temperature is included with extended-thinking requests,
     * and [AnthropicRequestMapper] never emits sampling fields regardless.
     */
    override var temperature: Double = 0.0
        set(_) { /* no-op: Anthropic 400s on sampling params */ }

    @Volatile
    private var activeJob: Job? = null

    // ── LlmBrain: chatStream ───────────────────────────────────────────────────

    /**
     * Streams the conversation to the Anthropic Messages API.
     *
     * Builds the request via [AnthropicRequestMapper], then runs the transport and the SSE
     * parser as two concurrent `Dispatchers.IO` coroutines bridged by a blocking line queue
     * while a suspend consumer drains the parser's emitted text and invokes [onChunk]
     * progressively (see the class-level KDoc for the full bridge design). The producer is
     * stored in [activeJob] so [interruptStream] / [cancelActiveRequest] can abort it.
     * Error precedence: transport error → parse (tool-use collision) error → success with
     * the full accumulated assistant text.
     */
    override suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        onChunk: suspend (StreamChunk) -> Unit,
    ): ApiResult<ChatCompletionResponse> = coroutineScope {
        val resolvedMaxTokens = maxTokens ?: AnthropicModelCatalog.maxOutput(modelId)
        val request = AnthropicRequestMapper.build(
            messages = messages,
            tools = tools,
            model = modelId,
            maxTokens = resolvedMaxTokens,
            thinkingEnabled = thinkingEnabled(),
            effort = effort(),
            imageBytes = buildImageBytesLambda(),
        )

        // Bridge 1: transport's push onLine (IO thread) → blocking queue the parser pulls.
        val lineQueue = LinkedBlockingQueue<LineMsg>()
        // Bridge 2: parser's non-suspend emitText → UNLIMITED channel the suspend consumer drains.
        val textChannel = Channel<String>(Channel.UNLIMITED)

        var transportResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var finishReason: String? = null
        var parseError: IllegalStateException? = null

        // Producer: the transport feeds lineQueue while the parser drains it (emitText →
        // textChannel). Both run on Dispatchers.IO because the parser blocks its carrier
        // thread on take(), so the transport needs its own thread to keep feeding the queue.
        val producer = launch(Dispatchers.IO) {
            val transportJob = launch(Dispatchers.IO) {
                try {
                    transportResult = http.postStream(request) { line ->
                        lineQueue.put(LineMsg.Line(line))
                    }
                } finally {
                    // ALWAYS signal end-of-stream so the parser's take() unblocks even when
                    // postStream returns an error or throws CancellationException on abort.
                    lineQueue.put(LineMsg.EndOfStream)
                }
            }
            val lineSeq = generateSequence {
                when (val msg = lineQueue.take()) {
                    is LineMsg.Line -> msg.value
                    LineMsg.EndOfStream -> null
                }
            }
            try {
                finishReason = AnthropicSseParser.parse(lineSeq) { text ->
                    textChannel.trySend(text)
                }.finishReason
            } catch (e: IllegalStateException) {
                // Task 6 cross-task: tool_use collision guard in ToolUseXmlSerializer.
                parseError = e
            } finally {
                textChannel.close()
                transportJob.join()
            }
        }
        activeJob = producer

        // Consumer: drain text progressively → onChunk for each piece as it streams in.
        val accumulated = StringBuilder()
        for (text in textChannel) {
            accumulated.append(text)
            onChunk(
                StreamChunk(
                    choices = listOf(
                        StreamChoice(delta = StreamDelta(content = text))
                    )
                )
            )
        }
        producer.join()

        when {
            transportResult is ApiResult.Error -> {
                @Suppress("UNCHECKED_CAST")
                transportResult as ApiResult<ChatCompletionResponse>
            }
            parseError != null -> ApiResult.Error(
                type = ErrorType.PARSE_ERROR,
                message = "Tool-use collision during SSE parse: ${parseError?.message}",
                cause = parseError,
            )
            else -> ApiResult.Success(
                ChatCompletionResponse(
                    id = "anthropic-direct",
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = accumulated.toString()),
                            finishReason = finishReason,
                        )
                    ),
                )
            )
        }
    }

    // ── LlmBrain: chat (delegates to chatStream) ───────────────────────────────

    /**
     * Non-streaming fallback: delegates to [chatStream] with a no-op [onChunk].
     * The transport is streaming-only; we don't add a second non-streaming HTTP path.
     */
    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        toolChoice: JsonElement?,
    ): ApiResult<ChatCompletionResponse> = chatStream(messages, tools, maxTokens) { /* no-op */ }

    // ── LlmBrain: estimateTokens ───────────────────────────────────────────────

    /** Mirrors [com.workflow.orchestrator.core.ai.OpenAiCompatBrain.estimateTokens]. */
    override fun estimateTokens(text: String): Int = TokenEstimator.estimate(text)

    // ── LlmBrain: cancellation ─────────────────────────────────────────────────

    /**
     * Cancels the active streaming job cooperatively. The transport's
     * `invokeOnCompletion` hook then cancels the in-flight OkHttp call at the TCP
     * level (Task 8 cross-task wiring in [AnthropicHttpTransport] impl).
     */
    override fun interruptStream() {
        activeJob?.cancel()
    }

    /** Hard-abort alias — same as [interruptStream] for this transport. */
    override fun cancelActiveRequest() {
        activeJob?.cancel()
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Builds the `imageBytes` lambda expected by [AnthropicRequestMapper.build].
     *
     * Resolves sha256 → `(mediaType, base64Data)?` by:
     * 1. Finding raw bytes via [com.workflow.orchestrator.agent.session.AttachmentStore.readBlocking]
     * 2. Resolving the on-disk extension via
     *    [com.workflow.orchestrator.agent.session.AttachmentStore.findExtensionForBlocking]
     * 3. Mapping extension → IANA media type via [extToMime]
     * 4. Base64-encoding the bytes with the standard (non-URL-safe) encoder
     *
     * Returns null when no attachment with that sha256 exists in the session store
     * (e.g. the image was attached to a prior session that was not the current one).
     */
    private fun buildImageBytesLambda(): (String) -> Pair<String, String>? {
        val store = attachmentAccess.store
        // Use an anonymous function so `return null` exits the lambda, not chatStream.
        return fun(sha256: String): Pair<String, String>? {
            val bytes = store.readBlocking(sha256) ?: return null
            val ext = store.findExtensionForBlocking(sha256) ?: return null
            val mime = extToMime(ext)
            val base64 = Base64.getEncoder().encodeToString(bytes)
            return Pair(mime, base64)
        }
    }

    companion object {
        /**
         * Reverse-maps a file extension (as stored by
         * [com.workflow.orchestrator.agent.session.AttachmentStore]) to an IANA media type.
         * Mirrors the inverse of `AttachmentStore.mimeToExtension`.
         */
        internal fun extToMime(ext: String): String = when (ext.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
    }
}

/**
 * Producer→parser bridge message carried on the [LinkedBlockingQueue]: either a raw SSE
 * [Line], or the [EndOfStream] sentinel that tells the parser's `generateSequence` to stop
 * (`take()` returns it, the sequence yields `null`). The transport enqueues exactly one
 * [EndOfStream] in a `finally`, so the parser always terminates — including on abort.
 */
private sealed interface LineMsg {
    data class Line(val value: String) : LineMsg
    object EndOfStream : LineMsg
}
