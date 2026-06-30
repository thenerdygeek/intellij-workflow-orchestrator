package com.workflow.orchestrator.core.ai.anthropic

import com.workflow.orchestrator.core.ai.AssistantMessageParser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Serializes a native Anthropic `tool_use` block (structured JSON) into the
 * canonical XML format that [com.workflow.orchestrator.core.ai.AssistantMessageParser]
 * already parses — so nothing downstream changes when switching from the
 * Sourcegraph-dialect XML path to the native Anthropic API path.
 *
 * This is Task 5 of Phase 4a (native Anthropic-direct LLM provider).
 *
 * Format produced:
 * ```
 * <tool_name><param1>value1</param1><param2>value2</param2></tool_name>
 * ```
 *
 * Value stringification rules (must match parser expectations):
 * - JSON primitive (string/number/bool/null) → raw content string (no surrounding quotes)
 * - JSON object or array → compact JSON via [Json.encodeToString]
 *
 * Collision guard (correctness invariant):
 * For params NOT in [CODE_CARRYING_PARAMS], the parser uses `indexOf` to find the
 * close tag, so any `<` in the value would either truncate the value or confuse the
 * tag scanner.  Rather than emit a silently-broken payload, this function throws
 * [IllegalStateException] (via [error]) whose message contains "collision".
 * Code-carrying params are exempt because the parser uses `lastIndexOf` scoped to
 * the enclosing tool's close tag, which correctly handles embedded markup.
 */
object ToolUseXmlSerializer {

    /**
     * References [AssistantMessageParser.CODE_CARRYING_PARAMS] directly — the single
     * authoritative source.  The collision guard's exemption list must equal the set the
     * parser uses for its `lastIndexOf` scoping, so sharing the constant eliminates
     * any risk of silent drift.
     */
    private val CODE_CARRYING_PARAMS = AssistantMessageParser.CODE_CARRYING_PARAMS

    private val COMPACT_JSON = Json { prettyPrint = false }

    /**
     * Convert a structured [inputJson] tool-use block into canonical XML.
     *
     * @param name the tool name (e.g. "read_file")
     * @param inputJson the `input` field of the Anthropic `tool_use` content block
     * @return XML string ready for [com.workflow.orchestrator.core.ai.AssistantMessageParser.parse]
     * @throws IllegalStateException if a non-code-carrying param value contains `<`
     *         (which would silently truncate under the parser)
     */
    fun toXml(name: String, inputJson: JsonObject): String {
        val sb = StringBuilder()
        sb.append('<').append(name).append('>')
        for ((key, element) in inputJson) {
            val value = when (element) {
                is JsonPrimitive -> element.content
                else -> COMPACT_JSON.encodeToString(element)
            }
            if (key !in CODE_CARRYING_PARAMS && '<' in value) {
                error("close-tag collision in param '$key'")
            }
            sb.append('<').append(key).append('>').append(value).append("</").append(key).append('>')
        }
        sb.append("</").append(name).append('>')
        return sb.toString()
    }
}
