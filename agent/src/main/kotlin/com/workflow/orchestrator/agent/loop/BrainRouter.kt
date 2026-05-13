package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.core.ai.HttpException
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.MessageSanitizer
import com.workflow.orchestrator.core.ai.SourcegraphCompletionsStreamClient
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.ai.dto.CompletionStreamRequest
import com.workflow.orchestrator.core.ai.dto.ContentPart
import com.workflow.orchestrator.core.ai.dto.ImageUrl
import com.workflow.orchestrator.core.ai.dto.StreamChoice
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.StreamContentPart
import com.workflow.orchestrator.core.ai.dto.StreamDelta
import com.workflow.orchestrator.core.ai.dto.StreamMessage
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.dto.hasImageParts
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.JsonElement
import java.util.Base64

/**
 * Routes LLM calls across two Sourcegraph backends based on whether the turn
 * carries image parts.
 *
 * **Routing rule** (post-2026-05-05 simplification):
 *
 * | Condition          | Backend                                      |
 * |--------------------|----------------------------------------------|
 * | No image parts     | [openAiCompatBrain] (`/.api/llm/chat/completions`) |
 * | Image parts        | [streamClient] (`/.api/completions/stream`)        |
 *
 * **History — why this used to be more complicated:** the prior version of
 * this class implemented a "two-step workaround" for image+tools turns. The
 * 2026-04-22 capabilities_lab probe established that Sourcegraph silently
 * dropped the `tools` field on `/.api/completions/stream` at api-version=8,
 * so image-bearing turns that also needed tools had to be split: step 1
 * sent the image alone to `/stream` to get a textual description, step 2
 * replaced the image with that description and sent it + tools to
 * `/chat/completions`.
 *
 * The 2026-05-05 format_lab probe at api-version=9 found this is no longer
 * true: the gateway forwards `tools` on `/stream` and emits tool calls back
 * as `delta_tool_calls` SSE frames — verified across all 6 vision-capable
 * Claude 4.5 models. The two-step workaround (and the ~250 lines of step-1
 * abstention/empty-handling/framing-prompt code that supported it) has been
 * removed. Image+tools turns now make a single round-trip through `/stream`.
 *
 * This also retires the `📷 image analyzed` UI strip — there is no longer a
 * "vision result merged into a text turn" event to badge. The
 * [onAnalyzedImageBadge] callback is kept on the constructor for ABI
 * compatibility but is now never invoked.
 *
 * **Per-session isolation:** [attachmentStore] is constructed per active
 * session in [com.workflow.orchestrator.agent.AgentService]. Reusing a stale
 * store from a prior session would leak attachments into the new session;
 * this class never caches the store reference beyond construction.
 *
 * Spec: `docs/research/2026-05-02-multimodal-agent-design.md` (historical);
 * baselines `tools/sourcegraph-probe/baselines/capabilities_lab_2026-05-05_*`
 * + `format_lab_results.json` document the regime change.
 */
class BrainRouter(
    private val openAiCompatBrain: LlmBrain,
    private val streamClient: SourcegraphCompletionsStreamClient,
    private val attachmentStore: AttachmentStore,
    private val modelRefProvider: () -> String,
    private val onAnalyzedImageBadge: (() -> Unit)? = null,
) : LlmBrain {

    private val log = Logger.getInstance(BrainRouter::class.java)

    /**
     * Exposes the wrapped OpenAI-compat brain for Phase-2 setApiDebugDir /
     * setSharedApiCallCounter / temperature-reset call sites that were
     * already wired against the underlying type. Test fakes return a generic
     * `LlmBrain`; production wires a real `OpenAiCompatBrain`.
     *
     * Prefer this accessor over `brainRef as? OpenAiCompatBrain` so the
     * OpenAiCompatBrain hooks fire whether or not the brain has been wrapped.
     */
    val underlyingOpenAiCompat: LlmBrain get() = openAiCompatBrain

    /**
     * Mirrors [openAiCompatBrain.modelId] so the agent loop's logging,
     * fallback chain, and pricing lookups still see the correct primary model
     * id even when the BrainRouter is the active `brain`.
     */
    override val modelId: String get() = openAiCompatBrain.modelId

    /**
     * Forwards temperature changes through to the wrapped OpenAI-compat brain.
     * The stream client's temperature is fixed at request-build time (Phase 3
     * default 0.0) and is unaffected by this setter.
     */
    override var temperature: Double
        get() = openAiCompatBrain.temperature
        set(value) {
            openAiCompatBrain.temperature = value
        }

    override var toolNameSet: Set<String>
        get() = openAiCompatBrain.toolNameSet
        set(value) {
            openAiCompatBrain.toolNameSet = value
        }

    override var paramNameSet: Set<String>
        get() = openAiCompatBrain.paramNameSet
        set(value) {
            openAiCompatBrain.paramNameSet = value
        }

    override fun estimateTokens(text: String): Int = openAiCompatBrain.estimateTokens(text)

    override fun interruptStream() = openAiCompatBrain.interruptStream()

    override fun cancelActiveRequest() = openAiCompatBrain.cancelActiveRequest()

    /**
     * Non-streaming convenience entry — used by [BrainRouterTest] and any
     * non-streaming caller that lands later. The streaming variant is the one
     * the agent loop actually calls.
     */
    suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>): ApiResult<ChatCompletionResponse> =
        chat(messages, tools.takeIf { it.isNotEmpty() }, maxTokens = null, toolChoice = null)

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        toolChoice: JsonElement?,
    ): ApiResult<ChatCompletionResponse> {
        val hasImage = messages.any { it.hasImageParts() }
        val route = if (hasImage) "image-or-mixed" else "text-only"
        log.info("[multimodal] BrainRouter.chat decision: hasImage=$hasImage → route=$route")
        return if (hasImage) {
            imageBearingNonStreaming(messages, tools, maxTokens)
        } else {
            openAiCompatBrain.chat(messages, tools, maxTokens, toolChoice)
        }
    }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        onChunk: suspend (StreamChunk) -> Unit,
    ): ApiResult<ChatCompletionResponse> {
        val hasImage = messages.any { it.hasImageParts() }
        val route = if (hasImage) "image-or-mixed-stream" else "text-only"
        log.info("[multimodal] BrainRouter.chatStream decision: hasImage=$hasImage → route=$route")
        return if (hasImage) {
            imageBearingStreaming(messages, tools, maxTokens, onChunk)
        } else {
            openAiCompatBrain.chatStream(messages, tools, maxTokens, onChunk)
        }
    }

    // ---- Image-bearing turns (image-only or image+tools) ----
    //
    // Both image-only and image+tools turns route through /.api/completions/
    // stream. format_lab 2026-05-05 verified the gateway forwards the `tools`
    // field on api-version=9 — what used to be a two-step workaround is now
    // a single round-trip.

    private suspend fun imageBearingStreaming(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        onChunk: suspend (StreamChunk) -> Unit,
    ): ApiResult<ChatCompletionResponse> {
        return runCatching {
            val req = buildStreamRequest(messages, tools, maxTokens)
            // Forward each delta as an OpenAI-compat StreamChunk so the agent
            // loop's existing onChunk handler (which knows how to assemble
            // text deltas) works without modification.
            val r = streamClient.chat(req) { delta ->
                onChunk(asStreamChunk(delta))
            }
            buildRouterResponse(r)
        }.getOrElse { e ->
            log.warn("[BrainRouter] image-bearing stream failed: ${e.message}")
            errorFromThrowable(e)
        }
    }

    private suspend fun imageBearingNonStreaming(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
    ): ApiResult<ChatCompletionResponse> {
        return runCatching {
            val r = streamClient.chat(buildStreamRequest(messages, tools, maxTokens))
            buildRouterResponse(r)
        }.getOrElse { e ->
            log.warn("[BrainRouter] image-bearing chat failed: ${e.message}")
            errorFromThrowable(e)
        }
    }

    /**
     * Build the assistant response. When the gateway emitted an SSE error
     * frame (Sourcegraph signals "this attachment is unsupported" in-band on
     * HTTP 200), surface the rejection as a user-visible assistant message
     * instead of letting an empty bubble render. format_lab probe (2026-05-05)
     * found 58 of 96 cells produce this pattern for HEIC/HEIF/BMP/TIFF/AVIF/
     * SVG and unsupported document shapes — all dead ends previously rendered
     * as empty replies.
     *
     * Tool calls assembled by the stream client (from `delta_tool_calls`
     * frames) are attached to the assistant message so the agent loop's tool
     * dispatcher sees them — same shape it gets from the OpenAI-compat path.
     */
    private fun buildRouterResponse(
        r: com.workflow.orchestrator.core.ai.dto.CompletionStreamResult,
    ): ApiResult<ChatCompletionResponse> {
        val rejection = r.rejectionReason
        val finalText = if (!rejection.isNullOrBlank() && r.text.isBlank()) {
            "Sourcegraph rejected this attachment: $rejection. " +
                "Supported image formats: PNG, JPEG, WebP."
        } else {
            r.text
        }
        return ApiResult.Success(
            ChatCompletionResponse(
                id = "router-stream",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(
                            role = "assistant",
                            content = finalText.ifEmpty { null },
                            toolCalls = r.toolCalls.takeIf { it.isNotEmpty() },
                        ),
                        finishReason = when (r.stopReason) {
                            null -> "stop"
                            "end_turn", "stop_sequence" -> "stop"
                            "length", "max_tokens" -> "length"
                            "tool_use", "tool_calls" -> "tool_calls"
                            else -> r.stopReason
                        },
                    ),
                ),
                usage = null,
            ),
        )
    }

    // ---- Wire-payload construction ----

    /**
     * Expose for testing — allows the test module to call [buildStreamRequest] without
     * routing through the full [chatStream] / [chat] dispatch path.
     */
    internal suspend fun buildStreamRequestForTest(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
    ): CompletionStreamRequest = buildStreamRequest(messages, tools, maxTokens)

    /**
     * Builds a [CompletionStreamRequest] (Cody Format B) from a list of
     * OpenAI-compat [ChatMessage]s. Image parts are resolved against
     * [attachmentStore] and emitted as base64 `data:` URIs per the Cody spec.
     *
     * `speaker` mapping: `user` → `human`, `assistant` → `assistant`,
     * `system` → `system`. `tool` role messages are rare on image-bearing
     * turns; they are mapped to `human` (the gateway accepts only the three
     * Cody speakers) with their tool name preserved in the text.
     */
    private suspend fun buildStreamRequest(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
    ): CompletionStreamRequest {
        // Sanitize before content-part transform so that assistant messages with
        // empty content + toolCalls receive the U+200B placeholder. Without this,
        // Anthropic rejects the request with "message content cannot be empty"
        // and the agent loop counts each call as a text-only mistake (3 → hard stop).
        val sanitized = MessageSanitizer.sanitizeForAnthropic(messages)
        val streamMessages = sanitized.map { msg ->
            val parts = (msg.parts ?: listOf(ContentPart.Text(msg.content ?: ""))).map { part ->
                when (part) {
                    is ContentPart.Text -> StreamContentPart.Text(part.text)
                    is ContentPart.Image -> {
                        val bytes = attachmentStore.read(part.sha256)
                        if (bytes == null) {
                            log.warn("[multimodal] BrainRouter.buildStreamRequest MISS: sha256=${part.sha256.take(12)}… not found in AttachmentStore")
                            throw AttachmentMissingException(part.sha256)
                        }
                        val b64 = Base64.getEncoder().encodeToString(bytes)
                        log.info("[multimodal] BrainRouter.buildStreamRequest hydrated sha256=${part.sha256.take(12)}… ${bytes.size}B → ${b64.length}B base64 mime=${part.mime}")
                        StreamContentPart.Image(ImageUrl("data:${part.mime};base64,$b64"))
                    }
                }
            }
            StreamMessage(speaker = mapRoleToSpeaker(msg.role), content = parts)
        }
        return CompletionStreamRequest(
            model = modelRefProvider(),
            messages = streamMessages,
            // Phase 5/6 default — the chat input usage indicator (Phase 7) will
            // surface a tighter per-model cap once `ModelCatalogService` is
            // wired live; until then 8000 mirrors the Cody-default.
            maxTokensToSample = maxTokens ?: 8000,
            tools = tools?.takeIf { it.isNotEmpty() },
        )
    }

    private fun mapRoleToSpeaker(role: String): String = when (role.lowercase()) {
        "user", "human" -> "human"
        "assistant" -> "assistant"
        "system" -> "system"
        // Phase 7 followup F-P6-5 (intentional behavior — do NOT "fix" without
        // understanding the constraint): tool-role messages get coerced to
        // "human" because the Sourcegraph completions stream schema only
        // accepts the three Cody speakers (`human`, `assistant`, `system`).
        // Tool-result turns are rare on image-bearing turns (vision-summarize
        // is step 1; tools call happens on step 2 against the OpenAI-compat
        // backend which retains the `tool` role natively). When a stray tool
        // turn does land here — typically a stale tool result preceding a
        // fresh image-bearing user turn — emitting it as `human` keeps the
        // request well-formed; the gateway sees what looks like the user
        // narrating a prior result, which is harmless. Caller is responsible
        // for content sanity (i.e., the text body still reads as a tool result
        // because BrainRouter doesn't rewrite content).
        else -> "human"
    }

    private fun errorFromThrowable(e: Throwable): ApiResult<ChatCompletionResponse> {
        val type = when (e) {
            // Phase 7 followup F-P6-4 — map missing-attachment to VALIDATION_ERROR
            // (the closest non-retryable terminal type in the existing taxonomy
            // — retrying would fail again because the bytes are not on disk).
            // The user message surfaces the actual cause.
            is AttachmentMissingException -> com.workflow.orchestrator.core.model.ErrorType.VALIDATION_ERROR
            is HttpException -> when (e.statusCode) {
                401, 403 -> com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED
                413 -> com.workflow.orchestrator.core.model.ErrorType.CONTEXT_LENGTH_EXCEEDED
                429 -> com.workflow.orchestrator.core.model.ErrorType.RATE_LIMITED
                in 500..599 -> com.workflow.orchestrator.core.model.ErrorType.SERVER_ERROR
                else -> com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR
            }
            else -> com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR
        }
        val msg = if (e is AttachmentMissingException) {
            "Image attachment ${e.sha256.take(8)}… is missing from this session's disk store. " +
                "Re-upload the image or remove it from the message and try again."
        } else {
            e.message ?: "unknown error"
        }
        return ApiResult.Error(type = type, message = msg, cause = e)
    }

    private fun asStreamChunk(deltaText: String): StreamChunk = StreamChunk(
        id = "router-delta",
        choices = listOf(
            StreamChoice(index = 0, delta = StreamDelta(content = deltaText)),
        ),
    )
}

/**
 * Phase 7 followup F-P6-4 — typed exception for the "attachment bytes not on
 * disk" case. Distinct from generic IO/network failure so the loop's error
 * handler can map it to a user-visible "session attachment missing" message
 * instead of the confusing `NETWORK_ERROR` it was getting under the prior
 * bare `error()` call.
 *
 * Surfaces in [BrainRouter.errorFromThrowable] as `INTERNAL_ERROR` so the
 * caller's retry policy treats it as non-retryable.
 */
class AttachmentMissingException(val sha256: String) :
    IllegalStateException("attachment $sha256 not found on disk for session")

