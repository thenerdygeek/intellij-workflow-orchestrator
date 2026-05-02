package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.CompletionStreamFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

        /** Reserved for future use (e.g. surfacing gateway error frames). */
        data class Error(val message: String) : ParseResult
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
        var line = reader.readLine()
        while (line != null) {
            when {
                line.startsWith("event:") -> {
                    val event = line.removePrefix("event:").trim()
                    if (event == "done") {
                        onResult(ParseResult.StreamDone)
                        return@withContext
                    }
                }
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        onResult(ParseResult.StreamDone)
                        return@withContext
                    }
                    if (payload.isNotEmpty()) {
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
}
