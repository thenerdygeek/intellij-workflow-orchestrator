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
                    // (e.g., <content><html></html></content> — indexOf would stop at </html>
                    //  which isn't a real closing tag for the param).
                    // Scope to the current tool's closing tag boundary to prevent merging
                    // multiple tool calls that use the same code-carrying param
                    // (e.g., two edit_file calls each with <new_string>).
                    val toolCloseTag = "</${currentToolUse!!.name}>"
                    val toolEndIdx = text.indexOf(toolCloseTag, currentParamValueStart)
                    val searchEnd = if (toolEndIdx >= 0) toolEndIdx else text.lastIndex
                    text.lastIndexOf(closeTag, searchEnd)
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

    /**
     * Returns true if [text] currently ends inside an unclosed tag — i.e. there
     * is a `<` after the last `>` (or a `<` and no `>` at all) and the body
     * since that `<` looks like a tag-name fragment (alphanum/underscore/slash).
     *
     * Used by the streaming presentation layer to suppress the skip-parse fast
     * path when the tail of the accumulated text could still be the inside of a
     * not-yet-closed tag. Without this, a chunk that arrives without `<` or `>`
     * (e.g. "_file" between chunks "<read" and ">") bypasses re-parse and is
     * appended to the visible stream, leaking the underscore-suffix of the
     * tool name into the assistant bubble.
     */
    fun endsWithIncompleteTag(text: String): Boolean {
        val lastOpen = text.lastIndexOf('<')
        if (lastOpen == -1) return false
        val afterOpen = text.substring(lastOpen)
        if ('>' in afterOpen) return false
        val body = afterOpen.removePrefix("</").removePrefix("<")
        return body.isEmpty() || body.matches(Regex("^[a-zA-Z_0-9]*$"))
    }

    /**
     * Tag names the LLM occasionally emits in visible text but that are NOT registered
     * tools. These are pretraining-echo artefacts from other agent protocols — Anthropic's
     * `<function_calls><invoke>`, deprecated `<tool_use>`/`<tool_calls>` wrappers, and
     * the literal `<tool_name>`/`<parameter_name>` placeholder shape seen in many tool-use
     * teaching examples online. None match a registered tool in [toolNames], so [parse]
     * passes them straight through as TextContent and they render as raw XML.
     */
    private val LEAKED_TAG_NAMES = listOf(
        "tool",
        "tool_use",
        "tool_calls",
        "tool_name",
        "parameter_name",
        "function_calls",
        "invoke"
    )

    /**
     * Strip pretraining-echo tool-use XML from visible assistant text. Two passes:
     *
     * 1. Remove balanced pairs `<name>...</name>` (including attribute-bearing open
     *    tags like `<invoke name="foo">`) for every name in [LEAKED_TAG_NAMES].
     * 2. Truncate at the earliest unclosed open tag of the same names — this is the
     *    streaming hold-back: prevents `<tool>` flashing in then disappearing once
     *    `</tool>` arrives a few chunks later.
     *
     * Real tool tags (those in `currentToolNames`) are not in [LEAKED_TAG_NAMES] and
     * are unaffected — they remain the responsibility of [parse]. Call site is the
     * streaming presentation layer in `AgentLoop`, right after [stripPartialTag].
     */
    fun stripLeakedToolXml(text: String): String {
        if (text.isEmpty()) return text
        var result = text

        for (name in LEAKED_TAG_NAMES) {
            val pattern = Regex(
                "<$name(?:\\s[^>]*)?>[\\s\\S]*?</$name>",
                RegexOption.IGNORE_CASE
            )
            result = pattern.replace(result, "")
        }

        var earliest = -1
        for (name in LEAKED_TAG_NAMES) {
            val openPattern = Regex("<$name(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
            val match = openPattern.find(result) ?: continue
            if (earliest < 0 || match.range.first < earliest) {
                earliest = match.range.first
            }
        }
        if (earliest >= 0) {
            result = result.substring(0, earliest).trimEnd()
        }

        return result
    }

    /**
     * Returns true if [text] contains an open tag from [LEAKED_TAG_NAMES] that has
     * not yet been closed. Used by the streaming presentation layer's skip-parse fast
     * path to suppress text appends while a leaked-tag body is mid-flight, mirroring
     * the existing [endsWithIncompleteTag] / `hasPendingTool` guard.
     *
     * Without this, when chunk A delivers `<tool>partial`, chunk B delivers a `<`/`>`-free
     * payload like ` more text`, and chunk C closes with `</tool>`, the chunk-B payload
     * would briefly leak into the visible bubble before chunk C's re-parse cleans it up.
     */
    fun hasUnclosedLeakedTag(text: String): Boolean {
        if (text.isEmpty()) return false
        for (name in LEAKED_TAG_NAMES) {
            val opens = Regex("<$name(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).findAll(text).count()
            if (opens == 0) continue
            val closes = Regex("</$name>", RegexOption.IGNORE_CASE).findAll(text).count()
            if (opens > closes) return true
        }
        return false
    }
}
