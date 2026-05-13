package com.workflow.orchestrator.core.ai.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for Sourcegraph's `/.api/completions/stream` endpoint (Cody-shape body).
 *
 * Multimodal-agent Phase 3 — wire layer for image-bearing turns. The existing
 * `/.api/llm/chat/completions` (Format A — see SourcegraphChatClient) does NOT
 * accept `image_url` content parts. The legacy `/.api/completions/stream` endpoint
 * (Format B) does, but uses a different body shape:
 * - `speaker` (NOT `role`): "human" | "assistant" | "system"
 * - `maxTokensToSample` (NOT `max_tokens`)
 * - `content` is an array of `{type, text|image_url}` parts (multimodal)
 *
 * Spec: `docs/research/2026-05-02-multimodal-agent-design.md` §Wire formats > Format B
 */

/**
 * Top-level request body for `POST /.api/completions/stream?api-version=N`.
 *
 * `topK` and `topP` default to `-1` (Cody convention meaning "use the model's
 * default"). `stream` is always true for this endpoint — the server returns SSE.
 */
@Serializable
data class CompletionStreamRequest(
    val model: String,
    val messages: List<StreamMessage>,
    val maxTokensToSample: Int,
    val temperature: Double = 0.0,
    val stream: Boolean = true,
    val topK: Int = -1,
    val topP: Int = -1,
    /**
     * Tool definitions forwarded to the upstream provider. format_lab probe
     * (2026-05-05, api-version=9) verified that Sourcegraph's stream
     * endpoint now forwards tool calls back as `delta_tool_calls` SSE
     * frames — flipped from the prior api-version=8 silent-drop behavior.
     * Null when the caller has no tools (text-only / image-only turns).
     *
     * Schema is OpenAI-compatible (same shape as ToolDefinition used by
     * the public chat/completions endpoint) — Sourcegraph forwards the
     * field verbatim to the upstream Anthropic/OpenAI provider.
     */
    val tools: List<ToolDefinition>? = null
)

/**
 * One message in the conversation. `speaker` replaces OpenAI's `role` and only
 * accepts `"human"`, `"assistant"`, or `"system"` (per Cody's spec).
 */
@Serializable
data class StreamMessage(
    val speaker: String,
    val content: List<StreamContentPart>
)

/**
 * One content part in a message — either text or an image URL. The
 * `type` discriminator is serialized via `@SerialName` so the wire shape
 * matches the Cody spec exactly.
 *
 * Wire shape examples:
 * ```json
 * {"type": "text", "text": "What color?"}
 * {"type": "image_url", "image_url": {"url": "data:image/png;base64,xxx"}}
 * ```
 */
@Serializable
sealed interface StreamContentPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : StreamContentPart

    @Serializable
    @SerialName("image_url")
    data class Image(@SerialName("image_url") val imageUrl: ImageUrl) : StreamContentPart
}

/**
 * Image URL payload. `url` is a `data:` URI with base64 image bytes.
 * `detail` is optional (`"low"`, `"high"`, `"auto"`) — Anthropic models
 * ignore it; OpenAI models honor it.
 */
@Serializable
data class ImageUrl(
    val url: String,
    val detail: String? = null
)

/**
 * One SSE frame parsed off the wire. Mutually exclusive: only `deltaText`
 * OR `completion` is populated per frame.
 *
 * - `deltaText`: incremental delta (api-version >= 2). Caller appends.
 * - `completion`: cumulative full text (api-version 1). Caller REPLACES.
 * - `stopReason`: end-of-stream cause when present (e.g. `"end_turn"`,
 *   `"stop_sequence"`).
 */
@Serializable
data class CompletionStreamFrame(
    val deltaText: String? = null,
    val completion: String? = null,
    val stopReason: String? = null,
    /**
     * Incremental tool-call deltas. Observed shape from format_lab 2026-05-05
     * Haiku 4.5 probe:
     * ```
     * {"delta_tool_calls":[{"id":"toolu_vrtx_...","type":"function",
     *                       "function":{"name":"foo","arguments":""}}]}
     * {"delta_tool_calls":[{"id":"","type":"function",
     *                       "function":{"name":"","arguments":"{\""}}]}
     * ...
     * ```
     * First frame carries `id` + `name`; subsequent frames append to
     * `arguments`. Caller (CodyStreamSseParser → SourcegraphCompletionsStream
     * Client) accumulates by id.
     */
    @SerialName("delta_tool_calls")
    val deltaToolCalls: List<DeltaToolCall>? = null
)

/**
 * One incremental tool-call delta. All fields nullable: the first frame for a
 * given tool carries `id` + `function.name`; later continuation frames carry
 * empty strings for those and append to `function.arguments`.
 */
@Serializable
data class DeltaToolCall(
    val id: String? = null,
    val type: String? = null,
    val function: DeltaToolCallFunction? = null
)

@Serializable
data class DeltaToolCallFunction(
    val name: String? = null,
    val arguments: String? = null
)

/**
 * Final result returned by `SourcegraphCompletionsStreamClient.chat()`.
 *
 * `text` is the assembled completion (deltaText concatenation, OR last
 * cumulative completion, depending on api-version). `durationMs` measures
 * wall-clock from request send to last-byte-received.
 *
 * `rejectionReason` is populated when the gateway emitted an SSE
 * `event: error` frame — Sourcegraph signals "this attachment shape is
 * unsupported" in-band on HTTP 200, not via HTTP 4xx. When non-null,
 * `text` is typically empty and the caller (BrainRouter) should surface
 * this string to the user instead of rendering an empty assistant bubble.
 */
@Serializable
data class CompletionStreamResult(
    val text: String,
    val stopReason: String?,
    val durationMs: Long,
    val rejectionReason: String? = null,
    /**
     * @deprecated always empty since XML-in-content migration 2026-05-13.
     *
     * Previously assembled from `delta_tool_calls` SSE frames. The accumulator
     * was removed when the `tools:[...]` request parameter was dropped — tool
     * definitions now live in the system prompt only, and tool calls are
     * extracted from raw text by [AssistantMessageParser]. Retained for
     * binary compatibility with any existing callers.
     */
    val toolCalls: List<ToolCall> = emptyList()
)
