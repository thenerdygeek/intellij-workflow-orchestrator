package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.AssistantMessageContent
import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.api.InternalApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Anthropic Messages API native-protocol adapter — Phase 4a.
 *
 * Seam contract (verified against [NativeProtocol] and [ToolProtocol]):
 *  - [presentTools] — returns `null` (inherited default from [NativeProtocol]); tools go in the
 *    `tools:[]` API field, not the system prompt.
 *  - [requiresDialectGuard] — `false` (inherited default from [NativeProtocol]); structured output
 *    cannot drift, dialect guard machinery bypassed.
 *  - [parseToolCalls] — **Option A (XML-delegate):** the brain (Phase 4a) renders canonical XML
 *    into the assistant text via [AnthropicSseParser] + [ToolUseXmlSerializer], so the end-of-stream
 *    extraction path is identical to today's XML parser.
 *    ⚠ NOTE: this relaxes the [NativeProtocol] "consumes structured `content_block_delta` frames"
 *    doc invariant: in Option-A mode the SSE layer has already collapsed structured deltas into XML
 *    text before this method is called. The invariant holds at the SSE layer; here we re-use the
 *    proven XML parser on the rendered text.
 *  - [classifyStreamLine] — parses Anthropic `event: error` data payloads and returns the inner
 *    `error.type` string (e.g. `"overloaded_error"`, `"rate_limit_error"`); null for non-error
 *    or unparseable lines.
 *  - [classifyHttpError] — maps raw HTTP status codes to normalized error-class strings for the
 *    `AgentLoop` retry/overflow logic. Not on [NativeProtocol] (lives on
 *    [com.workflow.orchestrator.core.ai.LlmProvider]); exposed here as a pure helper so
 *    [AnthropicDirectProvider] (Task 11) can delegate to a single source of truth.
 */
@InternalApi
class AnthropicNativeProtocol : NativeProtocol {

    // presentTools = null (inherited from NativeProtocol)
    // requiresDialectGuard = false (inherited from NativeProtocol)
    // toolResultWirePrefix = "" (inherited from NativeProtocol)
    // stripPartialTag = pass-through (inherited from NativeProtocol)
    // endsWithIncompleteTag = false (inherited from NativeProtocol)

    /**
     * Parses tool calls from the canonical XML text that [AnthropicSseParser] rendered from the
     * Anthropic structured streaming response (Option A). Delegates to [AssistantMessageParser.parse]
     * byte-for-byte — the proven XML path, reused because the SSE layer has already collapsed
     * `input_json_delta` chunks into `<tool_name><param>…</param></tool_name>` XML in the text.
     */
    override fun parseToolCalls(
        text: CharSequence,
        toolNames: Set<String>,
        paramNames: Set<String>,
    ): List<AssistantMessageContent> = AssistantMessageParser.parse(text, toolNames, paramNames)

    /**
     * Classifies an Anthropic SSE `event: error` data payload.
     *
     * Expects the raw `data:` line content (JSON string). Returns the inner `error.type` value
     * (e.g. `"overloaded_error"`, `"rate_limit_error"`, `"invalid_request_error"`) when the
     * line is a well-formed Anthropic error frame; null otherwise.
     *
     * Consumed by [AnthropicSseParser] and forwarded as [AnthropicSseParser.Result.errorClass].
     */
    override fun classifyStreamLine(line: String): String? = try {
        val obj = LENIENT_JSON.parseToJsonElement(line).jsonObject
        if (obj["type"]?.jsonPrimitive?.content == "error") {
            obj["error"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Maps a raw Anthropic HTTP error status to a normalized error-class string consumed by
     * the agent loop's retry / overflow logic
     * (`AgentLoop.isContextOverflowError`, `AgentLoop.kt:1854`).
     *
     * Returns null for status codes that don't require special handling (the loop's existing
     * retry machinery handles generic 5xx / network errors).
     *
     * Note: this method is NOT on [NativeProtocol]; it lives here as a pure helper so the
     * forthcoming [AnthropicDirectProvider] (Task 11) can delegate its
     * [com.workflow.orchestrator.core.ai.LlmProvider.classifyHttpError] override here rather
     * than duplicating the mapping.
     */
    fun classifyHttpError(statusCode: Int, @Suppress("UNUSED_PARAMETER") body: String): String? =
        when (statusCode) {
            HTTP_TOO_MANY_REQUESTS -> "rate_limit_error"
            HTTP_OVERLOADED -> "overloaded_error"
            HTTP_PAYLOAD_TOO_LARGE -> "context_length_exceeded"
            HTTP_BAD_REQUEST -> "invalid_request_error"
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> "authentication_error"
            else -> null
        }

    private companion object {
        /** Lenient JSON decoder — tolerates unknown keys in Anthropic payloads. */
        val LENIENT_JSON = Json { ignoreUnknownKeys = true }

        // HTTP status codes used by Anthropic that map to named error classes.
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_OVERLOADED = 529
        const val HTTP_PAYLOAD_TOO_LARGE = 413
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
