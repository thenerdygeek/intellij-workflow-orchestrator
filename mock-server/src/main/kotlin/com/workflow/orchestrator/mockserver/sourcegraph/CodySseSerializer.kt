package com.workflow.orchestrator.mockserver.sourcegraph

import com.workflow.orchestrator.mockserver.sourcegraph.scenario.Turn
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.contentFragments
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Renders one [Turn] to the Cody-shape SSE consumed by `SourcegraphCompletionsStreamClient` /
 * `CodyStreamSseParser` (`POST /.api/completions/stream`).
 *
 * Emits `data: {"deltaText":"…"}` frames (the plugin accumulates these and extracts tool calls from
 * the text via `AssistantMessageParser`), a `stopReason` frame, and a courtesy `event: done`. Tool
 * calls are also emitted as native `delta_tool_calls` frames to match a real gateway (the current
 * plugin drops them). For an error [Turn] it emits an in-band `event: error` frame on HTTP 200,
 * exactly as Sourcegraph signals unsupported requests.
 */
object CodySseSerializer {

    /** Each element is a complete SSE event (ends with a blank line); the route flushes per event. */
    fun frames(turn: Turn): List<String> {
        val frames = mutableListOf<String>()

        if (turn.error != null) {
            val errObj = buildJsonObject { put("error", turn.error) }
            frames += "event: error\ndata: $errObj\n\n"
            frames += "event: done\n\n"
            return frames
        }

        // Text / thinking / tool-call XML — one deltaText frame per fragment.
        turn.contentFragments().forEach { fragment ->
            val obj = buildJsonObject { put("deltaText", fragment) }
            frames += "event: completion\ndata: $obj\n\n"
        }

        // Native delta_tool_calls frames (belt-and-suspenders; current plugin parses the XML above).
        turn.toolCalls.forEachIndexed { i, tc ->
            val obj = buildJsonObject {
                putJsonArray("delta_tool_calls") {
                    addJsonObject {
                        put("id", "toolu_mock_$i")
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tc.name)
                            put("arguments", tc.argumentsJson)
                        }
                    }
                }
            }
            frames += "data: $obj\n\n"
        }

        // Terminal stop reason, then the courtesy done event.
        val stopObj = buildJsonObject { put("stopReason", codyStopReason(turn.finishReason)) }
        frames += "data: $stopObj\n\n"
        frames += "event: done\n\n"
        return frames
    }

    /** Convenience: the full SSE body as one string (used by tests). */
    fun serialize(turn: Turn): String = frames(turn).joinToString("")

    private fun codyStopReason(finishReason: String): String = when (finishReason) {
        Turn.FINISH_TOOL_CALLS -> "tool_use"
        Turn.FINISH_LENGTH -> "length"
        else -> "end_turn"
    }
}
