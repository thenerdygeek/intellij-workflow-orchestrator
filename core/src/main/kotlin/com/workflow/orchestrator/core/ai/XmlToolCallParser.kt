package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parses XML tool call tags from LLM text content (Cline-style Mode B).
 *
 * Format:
 * ```
 * <tool>
 *   <name>tool_name</name>
 *   <args>
 *     <param1>value1</param1>
 *     <param2>multi-line value</param2>
 *   </args>
 * </tool>
 * ```
 *
 * Uses `lastIndexOf` ONLY for `</content>` closing tag (ported from Cline)
 * because code content may contain XML-like strings such as `</div>` or
 * `</span>`. All other arg tags use `indexOf` from current position to
 * avoid mis-matching when a short tag name (e.g. `<path>`) appears inside
 * a later `<content>` block.
 */
object XmlToolCallParser {

    data class ParseResult(
        val toolCalls: List<ToolCall>,
        val textContent: String,
        val hasPartial: Boolean
    )

    private val idCounter = AtomicInteger(0)

    fun parse(text: String): ParseResult {
        val toolCalls = mutableListOf<ToolCall>()
        var hasPartial = false
        val textParts = mutableListOf<String>()
        var searchFrom = 0

        while (searchFrom < text.length) {
            val openIdx = text.indexOf("<tool>", searchFrom)
            if (openIdx == -1) {
                // No more tool tags — rest is text
                textParts.add(text.substring(searchFrom))
                break
            }

            // Text before the tool tag
            if (openIdx > searchFrom) {
                textParts.add(text.substring(searchFrom, openIdx))
            }

            // Find nearest closing </tool> tag after the opening tag.
            // Uses indexOf (not lastIndexOf) for sequential forward parsing.
            val closeIdx = text.indexOf("</tool>", openIdx)
            if (closeIdx == -1) {
                // Unclosed tool tag — partial/truncated
                hasPartial = true
                break
            }

            val toolBlock = text.substring(openIdx + "<tool>".length, closeIdx)
            val toolCall = parseToolBlock(toolBlock)
            if (toolCall != null) {
                toolCalls.add(toolCall)
            }

            searchFrom = closeIdx + "</tool>".length
        }

        return ParseResult(
            toolCalls = toolCalls,
            textContent = textParts.joinToString("").trim(),
            hasPartial = hasPartial
        )
    }

    private fun parseToolBlock(block: String): ToolCall? {
        val name = extractTag(block, "name") ?: return null
        val argsBlock = extractTagContent(block, "args") ?: return null
        val argsJson = parseArgsToJson(argsBlock)

        val id = "xmltool_${idCounter.incrementAndGet()}"
        return ToolCall(
            id = id,
            function = FunctionCall(
                name = name.trim(),
                arguments = argsJson
            )
        )
    }

    /**
     * Extract simple tag content: `<tag>value</tag>` → `value`
     */
    private fun extractTag(block: String, tag: String): String? {
        val open = block.indexOf("<$tag>")
        if (open == -1) return null
        val close = block.indexOf("</$tag>", open)
        if (close == -1) return null
        return block.substring(open + "<$tag>".length, close)
    }

    /**
     * Extract content of a tag that may contain nested XML-like content.
     * Uses lastIndexOf for the closing tag (Cline pattern).
     */
    private fun extractTagContent(block: String, tag: String): String? {
        val open = block.indexOf("<$tag>")
        if (open == -1) return null
        val contentStart = open + "<$tag>".length
        val close = block.lastIndexOf("</$tag>")
        if (close == -1 || close < contentStart) return null
        return block.substring(contentStart, close)
    }

    /**
     * Tags whose values can contain arbitrary code with XML-like strings.
     * Only these use `lastIndexOf` for the closing tag (Cline pattern).
     * All other tags use `indexOf` from current position to avoid
     * mis-matching when a short tag (e.g. `<path>`) appears inside code.
     */
    private val CODE_CARRYING_TAGS = setOf("content", "new_string", "old_string", "diff", "code")

    /**
     * Parse `<args>` child elements into a JSON string.
     *
     * Input: `<path>Foo.kt</path><content>code here</content>`
     * Output: `{"path":"Foo.kt","content":"code here"}`
     *
     * Uses `lastIndexOf` ONLY for code-carrying tags (content, new_string,
     * diff, code) whose values may contain XML-like strings. All other tags
     * use `indexOf` from the current position — this prevents `<path>` from
     * swallowing content when code contains `</path>`.
     */
    private fun parseArgsToJson(argsBlock: String): String {
        val args = mutableMapOf<String, String>()
        var pos = 0

        while (pos < argsBlock.length) {
            // Find next opening tag
            val tagStart = argsBlock.indexOf('<', pos)
            if (tagStart == -1) break

            val tagEnd = argsBlock.indexOf('>', tagStart)
            if (tagEnd == -1) break

            val tagName = argsBlock.substring(tagStart + 1, tagEnd).trim()
            if (tagName.startsWith("/") || tagName.isEmpty()) {
                pos = tagEnd + 1
                continue
            }

            val contentStart = tagEnd + 1
            val closeTag = "</$tagName>"

            // CRITICAL: Only code-carrying tags use lastIndexOf.
            // For short-value tags (path, pattern, line, etc.), indexOf
            // prevents mis-matching when the same tag name appears inside
            // a later <content> block.
            val closeIdx = if (tagName in CODE_CARRYING_TAGS) {
                argsBlock.lastIndexOf(closeTag)
            } else {
                argsBlock.indexOf(closeTag, contentStart)
            }

            if (closeIdx == -1 || closeIdx < contentStart) {
                pos = tagEnd + 1
                continue
            }

            val value = argsBlock.substring(contentStart, closeIdx)
            args[tagName] = value
            pos = closeIdx + closeTag.length
        }

        val jsonObj = buildJsonObject {
            args.forEach { (k, v) -> put(k, v) }
        }
        return jsonObj.toString()
    }
}
