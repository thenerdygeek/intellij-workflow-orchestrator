package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.CompletionStreamFrame
import com.workflow.orchestrator.core.ai.dto.DeltaToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedReader

/**
 * SSE parser for Sourcegraph's `/.api/completions/stream` (Cody-shape) response.
 *
 * Multimodal-agent Phase 3 — pure parser; no I/O beyond reading the supplied
 * [BufferedReader] line-by-line. Emits [ParseResult]s via the suspending callback
 * so the caller can stream deltas to the UI without buffering the full response.
 *
 * **Frame shape detection** (mutually exclusive per frame):
 * - `{"deltaText": "..."}` → emit [ParseResult.TextDelta] (caller appends)
 * - `{"completion": "..."}` → emit [ParseResult.TextReplacement] (caller REPLACES)
 *
 * The two shapes coexist because Cody's stream API has two versions:
 * - `api-version >= 2`: incremental `deltaText` (the modern shape)
 * - `api-version == 1`: cumulative `completion` (the legacy shape)
 *
 * Mixed frames are not expected on a real gateway but the parser handles each
 * frame on its own merits — there's no global mode-switch.
 *
 * **End-of-stream signals (whichever comes first):**
 * 1. `event: done` (Cody emits as courtesy)
 * 2. `data: [DONE]` (OpenAI sentinel — defensive)
 * 3. EOF on the [BufferedReader] (the original spec relied on this alone, but
 *    HTTP/1.1 keepalive + buffering proxies can delay close detection by minutes;
 *    see spec §Wire formats > Format B)
 *
 * Spec: `docs/research/2026-05-02-multimodal-agent-design.md` §Wire formats > Format B
 */
class CodyStreamSseParser {

    sealed interface ParseResult {
        /** Incremental delta — caller appends to accumulated text. */
        data class TextDelta(val text: String) : ParseResult

        /** Cumulative full text (api-version 1) — caller REPLACES accumulated text. */
        data class TextReplacement(val text: String) : ParseResult

        /**
         * Emitted when a frame carries a non-null `stopReason` field (e.g.
         * `"end_turn"`, `"stop_sequence"`, `"length"`). Phase 6 BrainRouter
         * branches on `"length"` for compaction-and-retry; other reasons are
         * surfaced via [CompletionStreamResult.stopReason] so callers can
         * distinguish e.g. truncated-completion from natural end-of-turn.
         */
        data class StopReason(val reason: String) : ParseResult

        /** Emitted exactly once at end-of-stream regardless of which signal triggered termination. */
        data object StreamDone : ParseResult

        /**
         * A gateway-emitted `event: error` frame. Sourcegraph returns HTTP 200
         * even for unsupported request shapes — the failure is signalled inside
         * the SSE stream as `event: error` followed by a `data:` payload that
         * may be a JSON `{"error": "..."}` object or a free-form string.
         *
         * format_lab probe (2026-05-05) found 58/96 cells return this pattern
         * for unsupported MIMEs (HEIC/HEIF/BMP/TIFF/AVIF/SVG) and unsupported
         * document shapes. Without this signal, the agent's chat panel renders
         * an empty assistant bubble and the user has no idea Sourcegraph
         * rejected the attachment. Callers (BrainRouter / agent loop) should
         * surface [message] to the user as an assistant message.
         */
        data class Error(val message: String) : ParseResult

        /**
         * Incremental tool-call delta from a `delta_tool_calls` SSE frame.
         * format_lab probe (2026-05-05) verified Sourcegraph forwards tool
         * calls on `/.api/completions/stream` at api-version=9 — the prior
         * silent-drop behavior at api-version=8 is gone.
         *
         * Wire shape (Haiku 4.5 sample):
         * ```
         * delta_tool_calls":[{"id":"toolu_vrtx_...","type":"function",
         *                     "function":{"name":"foo","arguments":""}}]
         * ```
         * Subsequent continuation frames carry empty `id`/`name` and append
         * to `arguments`. Callers must accumulate by id (or by index — the
         * stream client uses [accumulateToolCalls]).
         *
         * The list contains one entry per tool call advanced in this frame.
         * Multiple parallel tool calls from the same model produce multiple
         * deltas in a single frame.
         */
        data class ToolCallDelta(val deltas: List<DeltaToolCall>) : ParseResult
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Read SSE chunks from [reader] and emit [ParseResult]s via [onResult] until
     * any termination signal fires. Runs on [Dispatchers.IO] so it's safe to call
     * from any context — the caller decides how to dispatch [onResult] (typically
     * the UI thread for streaming chat updates).
     *
     * Malformed JSON frames are silently dropped: gateway corruption shouldn't
     * crash the agent loop. Unknown event types (e.g. `event: ping`) are ignored.
     */
    suspend fun parse(
        reader: BufferedReader,
        onResult: suspend (ParseResult) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // Sticky one-shot state: when the previous event line was `event: error`,
        // the next data: payload is the error body (NOT a CompletionStreamFrame).
        // Cleared after consuming the next data: line so subsequent frames parse
        // normally (gateway sometimes streams an error then continues with done).
        var nextDataIsError = false

        var line = reader.readLine()
        while (line != null) {
            when {
                line.startsWith("event:") -> {
                    val event = line.removePrefix("event:").trim()
                    if (event == "done") {
                        onResult(ParseResult.StreamDone)
                        return@withContext
                    }
                    if (event == "error") {
                        nextDataIsError = true
                    }
                }
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        onResult(ParseResult.StreamDone)
                        return@withContext
                    }
                    if (nextDataIsError) {
                        nextDataIsError = false
                        onResult(ParseResult.Error(extractErrorMessage(payload)))
                        // Don't return — gateway typically follows the error frame
                        // with `event: done`, which we still want to surface.
                    } else if (payload.isNotEmpty()) {
                        runCatching {
                            json.decodeFromString(CompletionStreamFrame.serializer(), payload)
                        }.onSuccess { frame ->
                            // A single frame may carry both a text payload AND a stopReason
                            // (Anthropic's stream emits stopReason inline on the final
                            // completion frame). Emit text first so the accumulator is
                            // up-to-date before the StopReason result fires.
                            when {
                                frame.deltaText != null -> onResult(ParseResult.TextDelta(frame.deltaText))
                                frame.completion != null -> onResult(ParseResult.TextReplacement(frame.completion))
                                // Frame with only stopReason / unknown shape: no text payload — fall through
                            }
                            // delta_tool_calls frames are no longer requested (tools=null
                            // on the wire, XML-in-content migration 2026-05-13). Drop any
                            // such frames if upstream emits them defensively.
                            if (frame.stopReason != null) {
                                onResult(ParseResult.StopReason(frame.stopReason))
                            }
                        }
                        // Malformed JSON: ignore (defensive — gateway corruption shouldn't crash agent)
                    }
                }
                // Blank line = SSE message separator; ignore.
                // Other lines (id:, retry:, comments starting with ':'): ignore.
            }
            line = reader.readLine()
        }
        // Reader exhausted (EOF): implicit done — no extra StreamDone emit (caller's
        // accumulated text is already complete from the last delta/replacement frame).
    }

    /**
     * Best-effort extraction of a human-readable message from an error-frame
     * data: payload. Sourcegraph emits two shapes observed in practice:
     *   - `{"error": "media type not supported"}` (typed envelope)
     *   - `{"message": "..."}` (alternate envelope)
     *   - free-form text (older gateway versions)
     * Falls back to the raw payload when neither key is present so we never
     * lose the gateway's diagnostic.
     */
    private fun extractErrorMessage(payload: String): String {
        if (payload.isEmpty()) return "Sourcegraph emitted an empty error frame"
        return runCatching {
            json.parseToJsonElement(payload).let { el ->
                val obj = el as? JsonObject ?: return@let null
                val msg = (obj["error"] ?: obj["message"]) as? JsonPrimitive
                msg?.contentOrNull
            }
        }.getOrNull() ?: payload
    }
}
