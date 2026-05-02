package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.core.ai.HttpException
import com.workflow.orchestrator.core.ai.LlmBrain
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
 * Phrases the vision-summarize step (step 1 of the two-step workaround) MUST
 * NOT pass through to step 2. If the model abstains in step 1, step 2 would
 * see a useless description ("[image description: I cannot see this image]")
 * and produce a garbage tool call.
 *
 * Pinned by `BrainRouterTest "every abstention phrase aborts step 2"`.
 */
internal val ABSTENTION_PHRASES = listOf(
    "i cannot see",
    "i can't see",
    "i don't see",
    "no image",
    "unable to view",
    "cannot view",
    "can't view",
    "i'm unable to process",
)

/**
 * Phase 6 of multimodal-agent plan — hybrid routing across two Sourcegraph
 * brain backends.
 *
 * **Routing rule** (per Decision 4 of the design spec):
 *
 * | Condition                                    | Backend                                      |
 * |----------------------------------------------|----------------------------------------------|
 * | No image parts                               | [openAiCompatBrain] (existing path)          |
 * | Image parts AND no tools                     | [streamClient] `/.api/completions/stream`    |
 * | Image parts AND tools                        | two-step workaround (vision-summarize → tools) |
 *
 * **Why hybrid:** the gateway silently drops the `tools` field on
 * `/.api/completions/stream` (capabilities_lab P6/P7), so we cannot just
 * migrate everything to the stream endpoint. Conversely, the OpenAI-compat
 * `/.api/llm/chat/completions` endpoint does NOT accept `image_url` content
 * parts. Hence the routing rule.
 *
 * **Two-step workaround** (image+tools):
 *
 * 1. Send the image alone to `/.api/completions/stream` to obtain a verbal
 *    description.
 * 2. Replace the image part with `[image description: …]` text and send the
 *    rebuilt messages + tools to `/.api/llm/chat/completions`.
 *
 * Step 1 abstention (`I cannot see this image…`) and HTTP errors abort before
 * step 2 — the user gets a single error message rather than a garbled tool
 * call. Successful step-2 completions invoke [onAnalyzedImageBadge] so the UI
 * can render the `📷 image analyzed` strip on the assistant message.
 *
 * **Implements [LlmBrain]:** so it can drop into [AgentLoop] as the `brain`
 * parameter without further wiring. The `tools` parameter is required by the
 * interface but the routing logic uses it to decide between paths.
 *
 * **Per-session isolation:** [attachmentStore] is constructed per active
 * session in [com.workflow.orchestrator.agent.AgentService]. Reusing a stale
 * store from a prior session would leak attachments into the new session;
 * this class never caches the store reference beyond construction.
 *
 * Spec: `docs/research/2026-05-02-multimodal-agent-design.md` §Architecture
 *       > Two-step workaround.
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
        val needsTools = !tools.isNullOrEmpty()
        val hasImage = messages.any { it.hasImageParts() }
        return when {
            !hasImage -> openAiCompatBrain.chat(messages, tools, maxTokens, toolChoice)
            !needsTools -> imageOnlyNonStreaming(messages)
            else -> twoStepWorkaround(messages, tools, maxTokens, toolChoice, onChunk = null)
        }
    }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        onChunk: suspend (StreamChunk) -> Unit,
    ): ApiResult<ChatCompletionResponse> {
        val needsTools = !tools.isNullOrEmpty()
        val hasImage = messages.any { it.hasImageParts() }
        return when {
            !hasImage -> openAiCompatBrain.chatStream(messages, tools, maxTokens, onChunk)
            !needsTools -> imageOnlyStreaming(messages, maxTokens, onChunk)
            else -> twoStepWorkaround(messages, tools, maxTokens, toolChoice = null, onChunk = onChunk)
        }
    }

    // ---- Image-only (no tools) ----

    private suspend fun imageOnlyStreaming(
        messages: List<ChatMessage>,
        maxTokens: Int?,
        onChunk: suspend (StreamChunk) -> Unit,
    ): ApiResult<ChatCompletionResponse> {
        return runCatching {
            val req = buildStreamRequest(messages, maxTokens)
            // Forward each delta as an OpenAI-compat StreamChunk so the agent
            // loop's existing onChunk handler (which knows how to assemble
            // text deltas) works without modification.
            val r = streamClient.chat(req) { delta ->
                onChunk(asStreamChunk(delta))
            }
            successResponse(text = r.text, stopReason = r.stopReason)
        }.getOrElse { e ->
            log.warn("[BrainRouter] image-only stream failed: ${e.message}")
            errorFromThrowable(e)
        }
    }

    private suspend fun imageOnlyNonStreaming(
        messages: List<ChatMessage>,
    ): ApiResult<ChatCompletionResponse> {
        return runCatching {
            val r = streamClient.chat(buildStreamRequest(messages, maxTokens = null))
            successResponse(text = r.text, stopReason = r.stopReason)
        }.getOrElse { e ->
            log.warn("[BrainRouter] image-only chat failed: ${e.message}")
            errorFromThrowable(e)
        }
    }

    // ---- Two-step workaround (image + tools) ----

    private suspend fun twoStepWorkaround(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        toolChoice: JsonElement?,
        onChunk: (suspend (StreamChunk) -> Unit)?,
    ): ApiResult<ChatCompletionResponse> {
        // Step 1: vision-summarize on /.api/completions/stream
        val visionMessages = messages.replacingLastImageBearingTurnWith(
            "Describe this image in detail for a follow-up tool call. " +
                "Be precise; the description will be the only signal a downstream agent has.",
        )
        val descriptionResult = runCatching {
            streamClient.chat(buildStreamRequest(visionMessages, maxTokens))
        }
        if (descriptionResult.isFailure) {
            val msg = descriptionResult.exceptionOrNull()?.message ?: "unknown error"
            log.warn("[BrainRouter] two-step step 1 failed: $msg")
            return ApiResult.Success(
                ChatCompletionResponse(
                    id = "router-step1-fail",
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = ChatMessage(
                                role = "assistant",
                                content = "Image analysis failed: $msg. " +
                                    "Try again, or remove the image and describe it in text.",
                            ),
                            finishReason = "stop",
                        ),
                    ),
                    usage = null,
                ),
            )
        }
        val description = descriptionResult.getOrThrow().text
        if (ABSTENTION_PHRASES.any { description.contains(it, ignoreCase = true) }) {
            log.info("[BrainRouter] two-step step 1 abstention detected, aborting step 2")
            return ApiResult.Success(
                ChatCompletionResponse(
                    id = "router-step1-abstain",
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = ChatMessage(
                                role = "assistant",
                                content = "The model couldn't analyze the attached image. " +
                                    "Try a clearer image, or describe it in text.",
                            ),
                            finishReason = "stop",
                        ),
                    ),
                    usage = null,
                ),
            )
        }

        // Step 2: tool call with image replaced by text description
        val rebuilt = messages.replacingImagePartsWithText("[image description: $description]")
        val resp = if (onChunk != null) {
            openAiCompatBrain.chatStream(rebuilt, tools, maxTokens, onChunk)
        } else {
            openAiCompatBrain.chat(rebuilt, tools, maxTokens, toolChoice)
        }
        if (resp is ApiResult.Success) {
            onAnalyzedImageBadge?.invoke()
        }
        return resp
    }

    // ---- Wire-payload construction ----

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
        maxTokens: Int?,
    ): CompletionStreamRequest {
        val streamMessages = messages.map { msg ->
            val parts = (msg.parts ?: listOf(ContentPart.Text(msg.content ?: ""))).map { part ->
                when (part) {
                    is ContentPart.Text -> StreamContentPart.Text(part.text)
                    is ContentPart.Image -> {
                        val bytes = attachmentStore.read(part.sha256)
                            ?: error("attachment ${part.sha256} not found on disk for session")
                        val b64 = Base64.getEncoder().encodeToString(bytes)
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
        )
    }

    private fun mapRoleToSpeaker(role: String): String = when (role.lowercase()) {
        "user", "human" -> "human"
        "assistant" -> "assistant"
        "system" -> "system"
        // Tool-result turns aren't expected on image-bearing turns, but stream
        // schema rejects unknown speakers — coerce to human so the request
        // doesn't bounce. Caller is responsible for content sanity.
        else -> "human"
    }

    private fun successResponse(text: String, stopReason: String?): ApiResult<ChatCompletionResponse> =
        ApiResult.Success(
            ChatCompletionResponse(
                id = "router-stream",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = text),
                        finishReason = when (stopReason) {
                            null -> "stop"
                            "end_turn", "stop_sequence" -> "stop"
                            "length", "max_tokens" -> "length"
                            else -> stopReason
                        },
                    ),
                ),
                usage = null,
            ),
        )

    private fun errorFromThrowable(e: Throwable): ApiResult<ChatCompletionResponse> {
        val type = when (e) {
            is HttpException -> when (e.statusCode) {
                401, 403 -> com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED
                413 -> com.workflow.orchestrator.core.model.ErrorType.CONTEXT_LENGTH_EXCEEDED
                429 -> com.workflow.orchestrator.core.model.ErrorType.RATE_LIMITED
                in 500..599 -> com.workflow.orchestrator.core.model.ErrorType.SERVER_ERROR
                else -> com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR
            }
            else -> com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR
        }
        return ApiResult.Error(type = type, message = e.message ?: "unknown error", cause = e)
    }

    private fun asStreamChunk(deltaText: String): StreamChunk = StreamChunk(
        id = "router-delta",
        choices = listOf(
            StreamChoice(index = 0, delta = StreamDelta(content = deltaText)),
        ),
    )
}

// ---- Extension helpers ----

/**
 * Returns a copy of the message list where the **last** image-bearing turn
 * has its parts replaced with `[image, prompt-text]` so the vision step has a
 * focused prompt rather than the full conversation's mixed text. Earlier
 * image-bearing turns (rare in practice — usually only the latest user turn
 * carries the image) keep their original parts.
 */
internal fun List<ChatMessage>.replacingLastImageBearingTurnWith(text: String): List<ChatMessage> {
    val idx = indexOfLast { it.hasImageParts() }
    if (idx < 0) return this
    return mapIndexed { i, m ->
        if (i == idx) {
            val imagesOnly = m.parts.orEmpty().filterIsInstance<ContentPart.Image>()
            m.copy(parts = imagesOnly + ContentPart.Text(text), content = null)
        } else {
            m
        }
    }
}

/**
 * Returns a copy where every image part across the conversation is replaced
 * with the supplied [text] (concatenated onto each affected message's
 * content). Used to rebuild the post-step-1 messages for the OpenAI-compat
 * tools call — the model never sees image bytes; only the description text.
 */
internal fun List<ChatMessage>.replacingImagePartsWithText(text: String): List<ChatMessage> = map { m ->
    if (m.hasImageParts()) {
        m.copy(
            parts = null,
            content = ((m.content ?: "") + " " + text).trim(),
        )
    } else {
        m
    }
}
