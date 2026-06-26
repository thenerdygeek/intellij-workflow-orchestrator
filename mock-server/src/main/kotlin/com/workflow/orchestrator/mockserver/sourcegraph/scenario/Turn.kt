package com.workflow.orchestrator.mockserver.sourcegraph.scenario

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * One scripted tool call inside a [Turn].
 *
 * [argumentsJson] is a JSON **object** string (e.g. `{"path":"src/Main.kt"}`). It is rendered to the
 * wire in two complementary ways by the serializers:
 *  - as XML inside the assistant text content (`<read_file><path>src/Main.kt</path></read_file>`) —
 *    this is the load-bearing channel: the plugin's `AssistantMessageParser` extracts tool calls from
 *    the streamed text (the 2026-05-13 XML-in-content migration), NOT from native tool_calls frames.
 *  - as native `tool_calls` / `delta_tool_calls` SSE frames — belt-and-suspenders so the mock matches
 *    a real Sourcegraph gateway's documented OpenAI/Cody wire shape (the current plugin ignores them).
 *
 * Tool [name]s MUST be real agent tool names so the loop actually executes them and returns.
 */
data class ScenarioToolCall(
    val name: String,
    val argumentsJson: String,
)

/** Token usage for a single [Turn]; [totalTokens] is derived. */
data class TurnUsage(
    val promptTokens: Int,
    val completionTokens: Int,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/**
 * One LLM reply in a scenario — an ordered list of emit steps.
 *
 * Render order (see [contentFragments]): optional [thinking] → [textChunks] (in order) →
 * [toolCalls] (as XML) → finish marker ([finishReason]) → [usage].
 *
 * [error], when non-null, makes the Cody serializer emit an in-band `event: error` frame
 * (Sourcegraph signals failures on HTTP 200). The OpenAI serializer represents the same turn via
 * its [finishReason] (typically `"length"`). Used by the `error-retry` scenario.
 */
data class Turn(
    val thinking: String? = null,
    val textChunks: List<String> = emptyList(),
    val toolCalls: List<ScenarioToolCall> = emptyList(),
    val finishReason: String = FINISH_STOP,
    val usage: TurnUsage = TurnUsage(0, 0),
    val error: String? = null,
) {
    companion object {
        const val FINISH_STOP = "stop"
        const val FINISH_TOOL_CALLS = "tool_calls"
        const val FINISH_LENGTH = "length"
    }
}

/** A named, ordered sequence of [Turn]s. */
data class Scenario(
    val name: String,
    val turns: List<Turn>,
)

private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Render this tool call as the XML-in-content form the agent parses:
 * ```
 * <read_file>
 * <path>src/Main.kt</path>
 * </read_file>
 * ```
 * Object/array param values are emitted as their compact JSON (the plugin re-parses JSON-shaped
 * tag bodies); scalar values are emitted verbatim.
 */
fun ScenarioToolCall.toXml(): String {
    val args: JsonObject? = runCatching { LENIENT_JSON.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
    return buildString {
        append("<").append(name).append(">\n")
        args?.forEach { (key, value) ->
            val body = if (value is JsonPrimitive) value.content else value.toString()
            append("<").append(key).append(">").append(body).append("</").append(key).append(">\n")
        }
        append("</").append(name).append(">")
    }
}

/**
 * The ordered text fragments for this turn (dialect-agnostic). Each serializer wraps these in its own
 * frame shape: OpenAI puts each fragment in a `delta.content` chunk; Cody puts each in a `deltaText`
 * frame. Thinking is wrapped in `<thinking>…</thinking>` so the plugin's ThinkingTagSplitter pulls it
 * out of the prose.
 */
fun Turn.contentFragments(): List<String> {
    val fragments = mutableListOf<String>()
    thinking?.takeIf { it.isNotBlank() }?.let { fragments += "<thinking>\n$it\n</thinking>\n\n" }
    fragments += textChunks
    toolCalls.forEach { fragments += "\n" + it.toXml() + "\n" }
    return fragments
}
