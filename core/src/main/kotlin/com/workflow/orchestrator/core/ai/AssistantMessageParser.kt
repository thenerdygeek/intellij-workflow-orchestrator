package com.workflow.orchestrator.core.ai

/**
 * Parses LLM assistant messages into content blocks (Cline faithful port).
 *
 * Ported from Cline's `parseAssistantMessageV2` in `parse-assistant-message.ts`.
 * Re-parses the ENTIRE accumulated text on every call (stateless pure function).
 * Returns content blocks with `partial` flags for streaming presentation.
 *
 * Format: tool-name-as-tag (e.g., <read_file><path>...</path></read_file>)
 */
object AssistantMessageParser {

    /**
     * Tags whose values can contain arbitrary code with XML-like strings.
     * Uses lastIndexOf for the closing tag (Cline pattern for HTML-in-code safety).
     */
    private val CODE_CARRYING_PARAMS = setOf("content", "new_string", "old_string", "diff", "code")

    /**
     * Parse assistant message text into content blocks.
     *
     * @param text The full accumulated assistant message text
     * @param toolNames Known tool names (used to distinguish tool tags from regular XML)
     * @param paramNames Known parameter names across all tools
     * @return List of content blocks, each with a `partial` flag
     */
    fun parse(
        text: String,
        toolNames: Set<String>,
        paramNames: Set<String>
    ): List<AssistantMessageContent> {
        val blocks = mutableListOf<AssistantMessageContent>()

        // Pre-compute tag lookup maps for O(1) matching
        val toolOpenTags = toolNames.associateBy { "<$it>" }   // "<read_file>" → "read_file"
        val toolCloseTags = toolNames.associateBy { "</$it>" }  // "</read_file>" → "read_file"
        val paramOpenTags = paramNames.associateBy { "<$it>" }  // "<path>" → "path"

        var i = 0
        var textStart = 0
        var currentToolUse: ToolUseContent? = null
        var currentParamName: String? = null
        var currentParamValueStart = 0

        while (i < text.length) {
            if (currentToolUse == null) {
                // State 1: Scanning text — looking for tool open tag
                val matchedTool = matchTagEndingAt(text, i, toolOpenTags)
                if (matchedTool != null) {
                    // Capture text before the tool tag
                    val tagLen = "<${matchedTool}>".length
                    val textEnd = i - tagLen + 1
                    if (textEnd > textStart) {
                        val textContent = text.substring(textStart, textEnd)
                        if (textContent.isNotBlank()) {
                            blocks.add(TextContent(content = textContent.trim(), partial = false))
                        }
                    }
                    currentToolUse = ToolUseContent(name = matchedTool)
                    i++
                    continue
                }
            } else if (currentParamName == null) {
                // State 2: Inside tool, not in param — looking for param open or tool close
                val matchedParam = matchTagEndingAt(text, i, paramOpenTags)
                if (matchedParam != null) {
                    currentParamName = matchedParam
                    currentParamValueStart = i + 1
                    i++
                    continue
                }

                val matchedClose = matchTagEndingAt(text, i, toolCloseTags)
                if (matchedClose != null && matchedClose == currentToolUse!!.name) {
                    // Tool complete
                    currentToolUse!!.partial = false
                    blocks.add(currentToolUse!!)
                    currentToolUse = null
                    textStart = i + 1
                    i++
                    continue
                }
            } else {
                // State 3: Inside param value — looking for param close tag only
                // Sequential guarantee: only scan for THIS param's closing tag
                val closeTag = "</$currentParamName>"
                val useLastIndexOf = currentParamName in CODE_CARRYING_PARAMS

                val closeIdx = if (useLastIndexOf) {
                    // For code-carrying params, use lastIndexOf to handle XML in code
                    text.lastIndexOf(closeTag)
                } else {
                    text.indexOf(closeTag, currentParamValueStart)
                }

                if (closeIdx != -1 && closeIdx >= currentParamValueStart) {
                    val value = text.substring(currentParamValueStart, closeIdx).trim()
                    currentToolUse!!.params[currentParamName!!] = value
                    currentParamName = null
                    i = closeIdx + closeTag.length
                    continue
                } else {
                    // Closing tag not found yet — jump to end (value is partial)
                    val partialValue = text.substring(currentParamValueStart).trim()
                    if (partialValue.isNotEmpty()) {
                        currentToolUse!!.params[currentParamName!!] = partialValue
                    }
                    i = text.length
                    continue
                }
            }
            i++
        }

        // Finalize: any remaining tool use is partial
        if (currentToolUse != null) {
            blocks.add(currentToolUse!!)
        }

        // Remaining text after last tool (or all text if no tools)
        if (currentToolUse == null && textStart < text.length) {
            val remaining = text.substring(textStart)
            if (remaining.isNotBlank()) {
                // Last text block is always partial (stream may not be done)
                blocks.add(TextContent(content = remaining.trim(), partial = true))
            }
        }

        return blocks
    }

    /**
     * Check if a known tag ends at position `endIndex` in the text.
     * Uses backward startsWith matching (ported from Cline).
     */
    private fun matchTagEndingAt(
        text: String,
        endIndex: Int,
        tagMap: Map<String, String>
    ): String? {
        for ((tag, name) in tagMap) {
            if (endIndex >= tag.length - 1) {
                val startPos = endIndex - tag.length + 1
                if (text.startsWith(tag, startPos)) {
                    return name
                }
            }
        }
        return null
    }

    /**
     * Strip incomplete XML tags at the end of text for clean UI display.
     * Ported from Cline — prevents flickering partial tags like "<read_".
     *
     * Known limitation: "Use the < operator" could be false-positive stripped
     * at a chunk boundary. Accepted trade-off (same as Cline).
     */
    fun stripPartialTag(text: String): String {
        val lastOpen = text.lastIndexOf('<')
        if (lastOpen == -1) return text
        val afterOpen = text.substring(lastOpen)
        if ('>' !in afterOpen) {
            val tagBody = afterOpen.removePrefix("</").removePrefix("<").trim()
            if (tagBody.isEmpty() || tagBody.matches(Regex("^[a-zA-Z_]+$"))) {
                return text.substring(0, lastOpen).trimEnd()
            }
        }
        return text
    }
}
