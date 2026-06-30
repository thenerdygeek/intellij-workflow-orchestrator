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
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.Base64

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
 * **onLine → Sequence bridge.** [AnthropicHttpTransport.postStream] pushes raw SSE
 * lines via a `(String) -> Unit` callback (push model). [AnthropicSseParser.parse]
 * consumes a `Sequence<String>` (pull model). Bridge: accumulate lines into a list
 * during `postStream`, then parse the list in one pass after the transport returns.
 * This is correct because:
 * - `postStream` is already suspend — callers get real network streaming.
 * - Tool-use XML always emits at `message_stop`, never mid-stream, so there is no
 *   latency difference between progressive-parse and post-parse for the dominant
 *   tool-use case.
 * - `emitText` inside `AnthropicSseParser.parse` is non-suspend, so the suspend
 *   [onChunk] callback must be called after parse returns, not inside the parser.
 *
 * **Cancellation.** `postStream` runs inside a child [Job]; both [interruptStream]
 * and [cancelActiveRequest] cancel that job, which cancels the coroutine running the
 * transport (and thus aborts the in-flight OkHttp call via `invokeOnCompletion`).
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
     * Build [AnthropicRequest] via [AnthropicRequestMapper], run
     * [AnthropicHttpTransport.postStream] in a child [Job] (so cancellation works),
     * accumulate raw SSE lines, parse them via [AnthropicSseParser], and invoke
     * [onChunk] for each text/XML piece emitted by the parser.
     */
    override suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        onChunk: suspend (StreamChunk) -> Unit,
    ): ApiResult<ChatCompletionResponse> {
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

        // Accumulate SSE lines while the transport streams them. Run inside a child
        // Job so interruptStream/cancelActiveRequest can abort the in-flight call.
        val lines = mutableListOf<String>()
        var transportResult: ApiResult<Unit> = ApiResult.Success(Unit)

        coroutineScope {
            val job = launch {
                transportResult = http.postStream(request) { line -> lines.add(line) }
            }
            activeJob = job
            job.join()
        }

        if (transportResult is ApiResult.Error) {
            @Suppress("UNCHECKED_CAST")
            return transportResult as ApiResult<ChatCompletionResponse>
        }

        // emitText inside AnthropicSseParser.parse is non-suspend, so collect
        // pieces into a list, then call the suspend onChunk after parse returns.
        val textPieces = mutableListOf<String>()
        val parseResult = try {
            AnthropicSseParser.parse(lines.asSequence()) { text ->
                textPieces.add(text)
            }
        } catch (e: IllegalStateException) {
            // Task 6 cross-task: tool_use collision guard in ToolUseXmlSerializer
            return ApiResult.Error(
                type = ErrorType.PARSE_ERROR,
                message = "Tool-use collision during SSE parse: ${e.message}",
                cause = e,
            )
        }

        val accumulated = StringBuilder()
        for (piece in textPieces) {
            accumulated.append(piece)
            onChunk(
                StreamChunk(
                    choices = listOf(
                        StreamChoice(delta = StreamDelta(content = piece))
                    )
                )
            )
        }

        return ApiResult.Success(
            ChatCompletionResponse(
                id = "anthropic-direct",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = accumulated.toString()),
                        finishReason = parseResult.finishReason,
                    )
                ),
            )
        )
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
