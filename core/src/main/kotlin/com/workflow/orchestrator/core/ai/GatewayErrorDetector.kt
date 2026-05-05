package com.workflow.orchestrator.core.ai

/**
 * Detects Sourcegraph Cody Gateway error frames injected into an active SSE stream.
 *
 * When the gateway's per-request deadline fires while it is still waiting on the
 * upstream LLM (Anthropic / OpenAI) to finish streaming, it emits a structured
 * error event in place of (or alongside) the normal `data: {chunk}` frames before
 * closing the socket. The HTTP call itself succeeds, so OkHttp does not raise —
 * the error rides in on the data channel.
 *
 * Pattern ported from Continue.dev's "Premature Close" detector
 * (`continue/gui/src/redux/thunks/streamNormalInput.ts:257-291`). Continue treats
 * the same class of mid-stream truncation as a tool failure that the model can
 * self-correct by retrying with smaller chunks.
 */
object GatewayErrorDetector {

    private const val TYPE_MARKER = "\"type\""
    private const val TYPE_VALUE = "completion.process_completion"
    private const val MESSAGE_MARKER = "context deadline exceeded"

    /**
     * Returns true if [line] looks like the gateway's "context deadline exceeded"
     * error frame for the completion-processing phase.
     *
     * Tolerant of: leading `data: ` prefix, surrounding whitespace, key/value
     * ordering, and key/value spacing inside the JSON object. Conservative on
     * payload — only matches when BOTH the type marker and the message marker
     * are present, to avoid false positives on unrelated provider errors.
     */
    fun isUpstreamTimeoutFrame(line: String): Boolean {
        val trimmed = line.trim().removePrefix("data:").trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]") return false
        if (!trimmed.startsWith("{")) return false
        if (!trimmed.contains(TYPE_MARKER)) return false
        if (!trimmed.contains(TYPE_VALUE)) return false
        if (!trimmed.contains(MESSAGE_MARKER, ignoreCase = true)) return false
        return true
    }
}
