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

// ---- Two-step workaround prompts and framing -------------------------------
// Pulled out of `twoStepWorkaround` so each hardening pass lands in one place
// and tests can reference the constants directly instead of grepping for
// fragile substrings.

/**
 * Step 1 prompt — replaces the image-bearing turn for the vision call.
 *
 * Phrased POSITIVELY (no "Do NOT refuse" double-negatives). Empirically
 * (Windows logs 2026-05-05) a heavier directive prompt with "Do NOT refuse,
 * hedge, or say you cannot see the image" produced 0-char responses from the
 * Cody stream endpoint — likely a safety/content-filter path that silently
 * suppressed output instead of returning text we could classify as abstention.
 * Positive framing avoids the trigger and gets useful descriptions back.
 */
internal const val VISION_PROMPT: String =
    "Describe what you see in this image clearly and concretely. " +
        "Include any visible text (transcribe verbatim), UI elements, " +
        "errors, diagrams, charts, or data. Be specific and complete — " +
        "your description is the only signal a downstream agent will receive."

/**
 * Step 2 framing wraps the vision description so the OpenAI-compat model treats
 * it as the actual content of the image rather than metadata about an unseen
 * image. The original `[image description: …]` wrapper was weak enough that
 * the trained "I can't analyze image files" tool-use refusal still fired
 * (Windows logs 2026-05-04). The header below pushes back on that pattern.
 */
internal const val FRAMING_HEADER: String =
    "[VISION ANALYSIS — this is the actual content of the attached image, " +
        "produced by a vision model. Treat this as your perception of the image " +
        "and respond as if you can see it directly. Do NOT say you cannot view " +
        "or analyze images.]"

internal const val FRAMING_FOOTER: String = "[END VISION ANALYSIS]"

/** Suffix shared by step-1 HTTP-failure messages. */
internal const val STEP1_RETRY_HINT: String =
    "Try again, or remove the image and describe it in text."

/** User-visible message when step 1 abstained — step 2 was aborted to avoid a garbled tool call. */
internal const val STEP1_ABSTAIN_USER_MESSAGE: String =
    "The model couldn't analyze the attached image. Try a clearer image, or describe it in text."

/**
 * User-visible message when step 1 returned empty text. Distinct from abstention
 * because the model didn't produce any output at all (likely safety-filtered);
 * letting the empty string flow into step 2's framing would produce
 * "[VISION ANALYSIS]\n\n[END]" — and the step-2 model honestly reports it
 * came back empty (Windows logs 2026-05-05).
 */
internal const val STEP1_EMPTY_USER_MESSAGE: String =
    "The vision model returned no description for the attached image. " +
        "This usually means the image was filtered or unreadable. Try a different " +
        "image, or describe what's in it as text."

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
        val route = when { !hasImage -> "text-only"; !needsTools -> "image-only"; else -> "two-step" }
        log.info("[multimodal] BrainRouter.chat decision: hasImage=$hasImage hasTools=$needsTools → route=$route")
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
        val route = when { !hasImage -> "text-only"; !needsTools -> "image-only-stream"; else -> "two-step" }
        log.info("[multimodal] BrainRouter.chatStream decision: hasImage=$hasImage hasTools=$needsTools → route=$route")
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
            buildRouterResponse(r)
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
            buildRouterResponse(r)
        }.getOrElse { e ->
            log.warn("[BrainRouter] image-only chat failed: ${e.message}")
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
        return successResponse(text = finalText, stopReason = r.stopReason)
    }

    // ---- Two-step workaround (image + tools) ----
    //
    // The Sourcegraph gateway has two endpoints with mutually-incompatible
    // capabilities: /.api/llm/chat/completions accepts tools but rejects images,
    // and /.api/completions/stream accepts images but silently drops the tools
    // field. When a turn carries both, we chain them: step 1 turns the image
    // into a text description on the stream endpoint, step 2 sends that
    // description plus the tools array to the OpenAI-compat endpoint.
    //
    // The orchestration below is intentionally a flat `when` over a sealed
    // VisionResult. The pure helpers (runVisionStep, buildStep2Messages,
    // VISION_PROMPT, FRAMING_HEADER, etc.) live in the companion so each
    // hardening pass lands in the right place rather than growing the orchestrator.

    /** Outcome of step 1 — the vision-summarize call against /completions/stream. */
    private sealed interface VisionResult {
        /** Step 1 succeeded with a usable image description. */
        data class Description(val text: String) : VisionResult

        /**
         * Step 1 returned text but the model abstained ("I cannot see this image…").
         * Step 2 must NOT proceed — the description is useless and would only
         * produce a garbled tool call.
         */
        data class Abstained(val description: String) : VisionResult

        /**
         * Step 1's HTTP call succeeded (200) but the parsed description was
         * empty/blank. Distinct from [Failed] (HTTP error) and [Abstained]
         * (model produced text saying it couldn't see the image). Empty output
         * usually means the model's response was safety-filtered server-side
         * — no text events were emitted at all. Pinned by Windows logs
         * 2026-05-05 where heavy directive prompting on /completions/stream
         * silently produced 0-char responses. Step 2 MUST NOT proceed —
         * letting empty text flow into the framing wraps nothing and the
         * step-2 model honestly reports it came back empty.
         */
        object Empty : VisionResult

        /** Step 1's HTTP call failed. Step 2 must NOT proceed. */
        data class Failed(val reason: String) : VisionResult
    }

    private suspend fun twoStepWorkaround(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        toolChoice: JsonElement?,
        onChunk: (suspend (StreamChunk) -> Unit)?,
    ): ApiResult<ChatCompletionResponse> {
        return when (val vision = runVisionStep(messages, maxTokens)) {
            is VisionResult.Failed ->
                synthesizeAbortResponse(
                    id = "router-step1-fail",
                    content = "Image analysis failed: ${vision.reason}. " + STEP1_RETRY_HINT,
                )
            is VisionResult.Abstained ->
                synthesizeAbortResponse(
                    id = "router-step1-abstain",
                    content = STEP1_ABSTAIN_USER_MESSAGE,
                )
            is VisionResult.Empty ->
                synthesizeAbortResponse(
                    id = "router-step1-empty",
                    content = STEP1_EMPTY_USER_MESSAGE,
                )
            is VisionResult.Description -> {
                val rebuilt = buildStep2Messages(messages, vision.text)
                val resp = if (onChunk != null) {
                    openAiCompatBrain.chatStream(rebuilt, tools, maxTokens, onChunk)
                } else {
                    openAiCompatBrain.chat(rebuilt, tools, maxTokens, toolChoice)
                }
                if (resp is ApiResult.Success) onAnalyzedImageBadge?.invoke()
                resp
            }
        }
    }

    /**
     * Step 1 — replace the last image-bearing turn with [VISION_PROMPT], call
     * the stream endpoint, classify the response into a [VisionResult].
     *
     * Encapsulates: prompt construction, HTTP fault tolerance, abstention
     * detection, debug logging. Future hardening of step 1 lands here.
     */
    private suspend fun runVisionStep(
        messages: List<ChatMessage>,
        maxTokens: Int?,
    ): VisionResult {
        val visionMessages = messages.buildStep1VisionPayload(VISION_PROMPT)
        val result = runCatching {
            streamClient.chat(buildStreamRequest(visionMessages, maxTokens))
        }
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: "unknown error"
            log.warn("[BrainRouter] two-step step 1 failed: $msg")
            return VisionResult.Failed(msg)
        }
        val description = result.getOrThrow().text
        log.info(
            "[multimodal] BrainRouter two-step step 1 description: " +
                "${description.length} chars, preview=${description.take(200).replace("\n", " ")}",
        )
        if (description.isBlank()) {
            log.warn("[multimodal] BrainRouter two-step step 1 returned empty/blank text — likely safety-filtered, aborting step 2")
            return VisionResult.Empty
        }
        if (ABSTENTION_PHRASES.any { description.contains(it, ignoreCase = true) }) {
            log.info("[multimodal] BrainRouter two-step step 1 abstention detected, aborting step 2")
            return VisionResult.Abstained(description)
        }
        return VisionResult.Description(description)
    }

    /**
     * Step 2 — replace every image part with an authoritative framing of [description].
     *
     * Encapsulates: framing wording. Future hardening of step 2 lands here.
     */
    private fun buildStep2Messages(
        messages: List<ChatMessage>,
        description: String,
    ): List<ChatMessage> {
        val framed = "$FRAMING_HEADER\n$description\n$FRAMING_FOOTER"
        return messages.replacingImagePartsWithText(framed)
    }

    /**
     * Build a synthetic single-choice [ChatCompletionResponse] used to abort
     * the two-step before step 2 fires (HTTP failure or abstention). Keeps
     * the abort paths from duplicating ~12 lines of choice/usage boilerplate.
     */
    private fun synthesizeAbortResponse(
        id: String,
        content: String,
    ): ApiResult.Success<ChatCompletionResponse> = ApiResult.Success(
        ChatCompletionResponse(
            id = id,
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = content),
                    finishReason = "stop",
                ),
            ),
            usage = null,
        ),
    )

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

// ---- Extension helpers ----

/**
 * Builds a clean single-message payload for the step-1 vision call.
 *
 * Why a single message: empirically (Windows logs 2026-05-05) the vision
 * endpoint silently bails out and returns 0 chars when handed a multi-turn
 * agent conversation with the image buried in turn 8 of 8. The exact same
 * image + prompt + endpoint produces a 1558-char description in 15.5s when
 * sent as a clean single-turn payload (path A — user-attached image), but
 * fails with 0 chars in 0.2–1.4s when sent with full conversation context
 * (path B — image arrived via tool_result on turn 8). Size is comparable
 * (188KB vs 200KB) — the difference is structural: multiple consecutive
 * `human`-coerced tool turns and JSON-shaped surrounding content cause the
 * vision model to short-circuit instead of engaging with the image.
 *
 * The vision step has exactly one job: describe the image. It does not need
 * the system prompt, prior tool calls, prior tool results, or the agent's
 * IDE context. We extract the image parts from the last image-bearing turn
 * and wrap them with [text] in a single user message — the same shape that
 * works for direct attachment.
 *
 * Returns an empty list if no image-bearing turn is found (defensive — the
 * caller should have routed by [hasImageParts] check before reaching here).
 */
internal fun List<ChatMessage>.buildStep1VisionPayload(text: String): List<ChatMessage> {
    val imageBearing = lastOrNull { it.hasImageParts() } ?: return emptyList()
    val imagesOnly = imageBearing.parts.orEmpty().filterIsInstance<ContentPart.Image>()
    return listOf(
        ChatMessage(
            role = "user",
            content = null,
            parts = imagesOnly + ContentPart.Text(text),
        ),
    )
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
