package com.workflow.orchestrator.agent.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Renders a [ContentBlock.ToolUse] (id, name, JSON-encoded input) as the canonical
 * per-tool-name XML format the LLM is taught to emit.
 *
 * Used by [ApiMessage.toChatMessage] to inline-render legacy tool_use blocks that
 * predate the XML-in-content migration (commits before 2026-05-13). New writes
 * persist tool calls as raw text in [ContentBlock.Text], so this renderer is
 * read-path only — but it's load-bearing for session resume across the migration.
 *
 * Output shape:
 * ```
 * <tool_name>
 * <param1>value1</param1>
 * <param2>value2</param2>
 * </tool_name>
 * ```
 *
 * Nested JSON object/array values are emitted as their raw JSON string serialization
 * (the parser tolerates this for code-carrying params like `content`, `diff`,
 * `env`, etc., and non-code params still parse back as strings).
 *
 * Malformed input JSON falls through to an empty body — preferable to throwing
 * because the alternative is breaking session resume on corrupt history.
 */
object ToolUseXmlRenderer {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun render(toolUse: ContentBlock.ToolUse): String = buildString {
        append("<").append(toolUse.name).append(">")
        val params = parseParams(toolUse.input)
        for ((key, value) in params) {
            append("\n<").append(key).append(">")
            val text = if (value is JsonPrimitive) value.content else value.toString()
            append(text)
            append("</").append(key).append(">")
        }
        append("\n</").append(toolUse.name).append(">")
    }

    private fun parseParams(input: String): JsonObject =
        try {
            json.parseToJsonElement(input).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
}
