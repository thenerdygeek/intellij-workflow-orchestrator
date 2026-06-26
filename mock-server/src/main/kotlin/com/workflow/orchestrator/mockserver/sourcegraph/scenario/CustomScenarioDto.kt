package com.workflow.orchestrator.mockserver.sourcegraph.scenario

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire form for an author-supplied (cowork) scenario, POSTed to
 * `POST /admin/sourcegraph/scenario/custom`. The mock parses, registers (overwriting any same-name
 * scenario), and activates it (becomes the default + turn indices reset).
 *
 * Schema (this is the contract cowork authors against):
 * ```json
 * {
 *   "name": "my-flow",
 *   "turns": [
 *     {
 *       "thinking": "optional reasoning, wrapped in <thinking> on the wire",
 *       "text": "optional assistant prose (single chunk)",
 *       "textChunks": ["optional", "multi-chunk prose (overrides `text` when present)"],
 *       "toolCalls": [
 *         { "name": "read_file", "arguments": { "path": "src/Main.kt" } },
 *         { "name": "any_tool",  "argumentsJson": "{\"k\":\"v\"}" }
 *       ],
 *       "finishReason": "tool_calls" | "stop" | "length",
 *       "usage": { "promptTokens": 1200, "completionTokens": 80 },
 *       "error": "optional — emits a Cody event:error frame for this turn"
 *     }
 *   ]
 * }
 * ```
 *
 * Tool args may be given as EITHER a JSON object (`arguments`) OR a raw JSON string
 * (`argumentsJson`); both normalize to the XML-in-content form the serializers already emit. ANY tool
 * name is accepted (no allow-list) so the plugin's unknown-tool handling can be exercised.
 *
 * Conveniences (so authors can be terse):
 *  - `finishReason` is optional — defaults to `tool_calls` when the turn has tool calls, else `stop`.
 *  - `usage` is optional — defaults to 0/0.
 *  - `text` is sugar for a single-element `textChunks`.
 */
@Serializable
data class CustomScenarioRequest(
    val name: String,
    val turns: List<CustomTurn> = emptyList(),
)

@Serializable
data class CustomTurn(
    val thinking: String? = null,
    val text: String? = null,
    val textChunks: List<String>? = null,
    val toolCalls: List<CustomToolCall> = emptyList(),
    val finishReason: String? = null,
    val usage: CustomUsage? = null,
    val error: String? = null,
)

@Serializable
data class CustomToolCall(
    val name: String,
    /** Tool args as a JSON object. Mutually-exclusive sugar for [argumentsJson]; this wins if both set. */
    val arguments: JsonObject? = null,
    /** Tool args as a raw JSON-object string. Used when [arguments] is absent. */
    val argumentsJson: String? = null,
)

@Serializable
data class CustomUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

/** Normalize the two arg forms to a single JSON-object string (the form [ScenarioToolCall] holds). */
fun CustomToolCall.normalizedArgumentsJson(): String =
    arguments?.toString() ?: argumentsJson ?: "{}"

/** Map a [CustomTurn] to the domain [Turn], inferring finishReason and unwrapping `text`. */
fun CustomTurn.toTurn(): Turn {
    val tcs = toolCalls.map { ScenarioToolCall(it.name, it.normalizedArgumentsJson()) }
    val chunks = textChunks ?: text?.let { listOf(it) } ?: emptyList()
    val finish = finishReason ?: if (tcs.isNotEmpty()) Turn.FINISH_TOOL_CALLS else Turn.FINISH_STOP
    return Turn(
        thinking = thinking,
        textChunks = chunks,
        toolCalls = tcs,
        finishReason = finish,
        usage = TurnUsage(usage?.promptTokens ?: 0, usage?.completionTokens ?: 0),
        error = error,
    )
}

/** Map a whole request to a domain [Scenario]. */
fun CustomScenarioRequest.toScenario(): Scenario =
    Scenario(name = name, turns = turns.map { it.toTurn() })
