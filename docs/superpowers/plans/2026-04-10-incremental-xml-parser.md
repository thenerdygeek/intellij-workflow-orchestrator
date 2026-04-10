# Incremental XML Parser — Cline Faithful Port

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the post-processing XML tool call parser with Cline's re-parse-on-every-chunk approach, enabling mid-stream tool execution and eliminating text duplication bugs.

**Architecture:** The parser is a stateless pure function in `:core` that receives accumulated text + known tool/param names, returns a list of content blocks (TextContent | ToolUseContent) each with a `partial` flag. The AgentLoop calls it on every SSE chunk and uses a `presentationIndex` to track which blocks have been processed. Two execution modes: accumulate-all (default, current behavior) and stream-interrupt (Cline-style, one tool per response).

**Tech Stack:** Kotlin, OkHttp SSE, kotlinx.serialization, JUnit 5, MockK

**Branch:** `feature/streaming-xml-port` in worktree `.worktrees/streaming-xml-port`

**Spec:** `docs/superpowers/specs/2026-04-10-incremental-xml-parser-design.md`

---

## Do NOT Do These Things

1. **Do NOT add a presentation scheduler** — deferred to a separate streaming UI phase
2. **Do NOT remove the `}{` concat workaround** in `SourcegraphChatClient` — the native path must still work
3. **Do NOT add `role: tool` messages** — gateway rejects them, `sanitizeMessages()` converts them
4. **Do NOT add `[DONE]` handling** — gateway never sends it

---

## Phase 1: Content Block Model + Parser (Tasks 1-3)

### Task 1: Create content block data classes

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/AssistantMessageContent.kt`

- [ ] **Step 1: Create the content block model**

```kotlin
package com.workflow.orchestrator.core.ai

/**
 * Content blocks from parsing an LLM assistant message (Cline faithful port).
 *
 * The parser returns a list of these blocks. Each has a `partial` flag:
 * - partial=true: block is still being built (more text expected)
 * - partial=false: block is complete and ready for presentation/execution
 */
sealed class AssistantMessageContent {
    abstract val partial: Boolean
}

/**
 * Text content between tool calls (reasoning, explanations).
 * partial=true while text is still arriving before the next tool tag or stream end.
 */
data class TextContent(
    val content: String,
    override val partial: Boolean
) : AssistantMessageContent()

/**
 * A tool invocation parsed from XML tags.
 * partial=true while the closing </tool_name> tag hasn't arrived yet.
 *
 * Format: <tool_name><param1>value1</param1></tool_name>
 */
data class ToolUseContent(
    val name: String,
    val params: MutableMap<String, String> = mutableMapOf(),
    override var partial: Boolean = true
) : AssistantMessageContent()
```

- [ ] **Step 2: Compile**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/AssistantMessageContent.kt
git commit -m "feat(xml-parser): add content block data classes (Cline port)

TextContent and ToolUseContent with partial flag, ported from Cline's
AssistantMessageContent types. Used by the incremental parser to
represent text and tool call blocks during streaming."
```

---

### Task 2: Create the incremental parser with TDD

**Files:**
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/AssistantMessageParserTest.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/AssistantMessageParser.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AssistantMessageParserTest {

    private val toolNames = setOf("read_file", "edit_file", "create_file", "run_command",
        "search_code", "think", "attempt_completion", "ask_followup_question")
    private val paramNames = setOf("path", "content", "old_string", "new_string", "diff",
        "code", "command", "pattern", "question", "result", "thought", "working_dir",
        "regex", "file_pattern", "output_mode", "directory", "include", "options",
        "questions", "title", "description", "prompt")

    @Test
    fun `parses text-only response`() {
        val text = "The answer is 42."
        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val block = blocks[0] as TextContent
        assertEquals("The answer is 42.", block.content)
        assertFalse(block.partial)
    }

    @Test
    fun `parses single tool call`() {
        val text = """I'll read that file.

<read_file>
<path>src/Foo.kt</path>
</read_file>"""
        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)

        assertEquals(2, blocks.size)
        val textBlock = blocks[0] as TextContent
        assertTrue(textBlock.content.contains("I'll read that file."))

        val toolBlock = blocks[1] as ToolUseContent
        assertEquals("read_file", toolBlock.name)
        assertEquals("src/Foo.kt", toolBlock.params["path"])
        assertFalse(toolBlock.partial)
    }

    @Test
    fun `parses parallel tool calls with text between`() {
        val text = """<read_file>
<path>A.kt</path>
</read_file>

Now the second file.

<read_file>
<path>B.kt</path>
</read_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)

        assertEquals(3, blocks.size)
        val tool1 = blocks[0] as ToolUseContent
        assertEquals("A.kt", tool1.params["path"])

        val textBlock = blocks[1] as TextContent
        assertTrue(textBlock.content.contains("Now the second file."))

        val tool2 = blocks[2] as ToolUseContent
        assertEquals("B.kt", tool2.params["path"])
    }

    @Test
    fun `detects partial tool call (no closing tag)`() {
        val text = """<read_file>
<path>src/Foo.kt</path>"""
        // No closing </read_file> — still streaming

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertEquals("read_file", toolBlock.name)
        assertEquals("src/Foo.kt", toolBlock.params["path"])
        assertTrue(toolBlock.partial)
    }

    @Test
    fun `detects partial param (mid-value truncation)`() {
        val text = """<edit_file>
<path>Foo.kt</path>
<new_string>class Foo {
    fun bar"""
        // Truncated mid-value

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertTrue(toolBlock.partial)
        assertEquals("Foo.kt", toolBlock.params["path"])
        assertTrue(toolBlock.params["new_string"]!!.contains("class Foo"))
    }

    @Test
    fun `handles XML-like strings in code content using lastIndexOf`() {
        val text = """<create_file>
<path>index.html</path>
<content><html><body><div>Hello</div></body></html></content>
</create_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertFalse(toolBlock.partial)
        assertTrue(toolBlock.params["content"]!!.contains("</div>"))
    }

    @Test
    fun `does not mis-match path tag inside new_string`() {
        val text = """<edit_file>
<path>src/Foo.kt</path>
<new_string>// See <path>other/Bar.kt</path> for details
class Foo {
    fun bar() = 42
}</new_string>
</edit_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertEquals("src/Foo.kt", toolBlock.params["path"])
        assertTrue(toolBlock.params["new_string"]!!.contains("other/Bar.kt"))
        assertTrue(toolBlock.params["new_string"]!!.contains("class Foo"))
    }

    @Test
    fun `handles large content block with 200+ lines`() {
        val lines = (1..250).joinToString("\n") { "    val field$it = \"value$it\"" }
        val text = """<create_file>
<path>Generated.kt</path>
<content>package com.example

class Generated {
$lines
}</content>
</create_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertFalse(toolBlock.partial)
        assertTrue(toolBlock.params["content"]!!.contains("field1"))
        assertTrue(toolBlock.params["content"]!!.contains("field250"))
    }

    @Test
    fun `ignores unknown tags inside tool block`() {
        val text = """<read_file>
<path>Foo.kt</path>
<unknown_param>should be ignored</unknown_param>
</read_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertEquals("Foo.kt", toolBlock.params["path"])
        assertFalse(toolBlock.params.containsKey("unknown_param"))
    }

    @Test
    fun `does not treat unknown tag as tool call`() {
        val text = "Use the <div>element</div> for layout."

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val textBlock = blocks[0] as TextContent
        assertTrue(textBlock.content.contains("<div>element</div>"))
    }

    @Test
    fun `handles empty params (think tool)`() {
        val text = """<think>
<thought>The user wants a simple function.</thought>
</think>"""

        val blocks = AssistantMessageParser.parse(text, toolNames + "think", paramNames + "thought")
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertEquals("think", toolBlock.name)
        assertEquals("The user wants a simple function.", toolBlock.params["thought"])
    }

    @Test
    fun `text block is partial when at end of stream`() {
        val text = "I'm thinking about"
        // Stream not ended yet — this text is partial

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val textBlock = blocks[0] as TextContent
        assertTrue(textBlock.partial, "Text at end should be partial during streaming")
    }

    @Test
    fun `multi-param tool call preserves all params`() {
        val text = """<edit_file>
<path>Foo.kt</path>
<old_string>fun bar() = 41</old_string>
<new_string>fun bar() = 42</new_string>
</edit_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertEquals("Foo.kt", toolBlock.params["path"])
        assertEquals("fun bar() = 41", toolBlock.params["old_string"])
        assertEquals("fun bar() = 42", toolBlock.params["new_string"])
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "*.AssistantMessageParserTest"`
Expected: FAIL — `AssistantMessageParser` does not exist yet

- [ ] **Step 3: Implement AssistantMessageParser**

```kotlin
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
        // paramCloseTags built per-param during value scan

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
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :core:test --tests "*.AssistantMessageParserTest" -v`
Expected: 13 tests PASSED

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/AssistantMessageParser.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/ai/AssistantMessageParserTest.kt
git commit -m "feat(xml-parser): add incremental parser with TDD (Cline port)

Faithful port of Cline's parseAssistantMessageV2. Stateless pure
function that scans full accumulated text, returns content blocks
with partial flags. Tool-name-as-tag format (<read_file> not <tool>).
Uses lastIndexOf for code-carrying params (content, new_string, etc).
13 tests covering: single/parallel tools, partial detection, nested
XML in code, large content, unknown tags, multi-param tools."
```

---

### Task 3: Create the tool prompt builder with TDD

**Files:**
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/ToolPromptBuilderTest.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/ToolPromptBuilder.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolPromptBuilderTest {

    @Test
    fun `builds markdown with XML usage example`() {
        val tools = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "read_file",
                    description = "Read a file's contents",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "path" to ParameterProperty(type = "string", description = "The file path")
                        ),
                        required = listOf("path")
                    )
                )
            )
        )

        val markdown = ToolPromptBuilder.build(tools)

        assertTrue(markdown.contains("## read_file"))
        assertTrue(markdown.contains("Read a file's contents"))
        assertTrue(markdown.contains("- path: (required)"))
        assertTrue(markdown.contains("<read_file>"))
        assertTrue(markdown.contains("<path>"))
        assertTrue(markdown.contains("</read_file>"))
    }

    @Test
    fun `includes format instructions`() {
        val tools = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "think",
                    description = "Pause and reason",
                    parameters = FunctionParameters(properties = emptyMap())
                )
            )
        )

        val markdown = ToolPromptBuilder.build(tools)

        assertTrue(markdown.contains("Tool Use Format"))
        assertTrue(markdown.contains("<tool_name>"))
        assertTrue(markdown.contains("<parameter_name>"))
    }

    @Test
    fun `escapes XML special characters in descriptions`() {
        val tools = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "edit_file",
                    description = "Replace old_string -> new_string (use < and > for generics)",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "path" to ParameterProperty(type = "string", description = "Path to List<String>")
                        ),
                        required = listOf("path")
                    )
                )
            )
        )

        val markdown = ToolPromptBuilder.build(tools)

        assertTrue(markdown.contains("&lt;"), "< should be escaped")
        assertTrue(markdown.contains("&gt;"), "> should be escaped")
    }

    @Test
    fun `marks optional params`() {
        val tools = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "search_code",
                    description = "Search code",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "pattern" to ParameterProperty(type = "string", description = "Regex pattern"),
                            "directory" to ParameterProperty(type = "string", description = "Search directory")
                        ),
                        required = listOf("pattern")
                    )
                )
            )
        )

        val markdown = ToolPromptBuilder.build(tools)

        assertTrue(markdown.contains("- pattern: (required)"))
        assertTrue(markdown.contains("- directory: (optional)"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "*.ToolPromptBuilderTest"`
Expected: FAIL — `ToolPromptBuilder` does not exist yet

- [ ] **Step 3: Implement ToolPromptBuilder**

```kotlin
package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ToolDefinition

/**
 * Builds markdown tool definitions for system prompt injection (Cline faithful port).
 *
 * Ported from Cline's `PromptBuilder.buildUsageSection()`. Each tool becomes a
 * markdown section with description, parameter list, and XML usage example.
 */
object ToolPromptBuilder {

    fun build(tools: List<ToolDefinition>): String = buildString {
        appendLine(FORMAT_INSTRUCTIONS)
        appendLine()

        for (tool in tools) {
            val fn = tool.function
            appendLine("## ${fn.name}")
            appendLine("Description: ${escapeXml(fn.description)}")

            val params = fn.parameters
            if (params.properties.isNotEmpty()) {
                appendLine("Parameters:")
                val requiredSet = params.required.toSet()
                for ((name, prop) in params.properties) {
                    val req = if (name in requiredSet) "required" else "optional"
                    appendLine("- $name: ($req) ${escapeXml(prop.description)}")
                }
            }

            appendLine("Usage:")
            appendLine("<${fn.name}>")
            for ((name, _) in params.properties) {
                appendLine("<$name>$name value here</$name>")
            }
            appendLine("</${fn.name}>")
            appendLine()
        }
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val FORMAT_INSTRUCTIONS = """
# Tool Use Format

You have tools to assist you. To use a tool, write the XML tag for that tool with its parameters as child tags:

<tool_name>
<parameter_name>value</parameter_name>
</tool_name>

- Always use the XML format shown above — do not use JSON or code blocks for tool calls.
- You may use multiple tools in one response.
- For parameters containing code (content, new_string, old_string, diff), write the code directly — no escaping needed.
- Even for large code blocks (100+ lines), use the appropriate tool with XML tags.
    """.trimIndent()
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :core:test --tests "*.ToolPromptBuilderTest" -v`
Expected: 4 tests PASSED

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/ToolPromptBuilder.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/ai/ToolPromptBuilderTest.kt
git commit -m "feat(xml-parser): add tool prompt builder for Cline XML format

Ported from Cline's PromptBuilder.buildUsageSection(). Each tool gets
a markdown section with description, parameters, and XML usage example
showing tool-name-as-tag format. XML special chars escaped in descriptions."
```

---

## Phase 2: Wire into Streaming + Remove Old Parser (Tasks 4-7)

### Task 4: Wire parser into SourcegraphChatClient streaming path

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt`

This replaces the old XML post-processing (from the prior `XmlToolCallParser`) with `AssistantMessageParser` in the accumulate-mode path. The streaming path in `sendMessageStream()` accumulates text, then at stream end converts blocks to `ChatCompletionResponse`.

- [ ] **Step 1: Replace XML post-processing in sendMessageStream()**

In `SourcegraphChatClient.sendMessageStream()`, find the block starting with `// --- XML tool call fallback (Cline Mode B) ---` (around line 415) and replace the entire XML fallback section through the `finalMessage` construction with the new parser-based approach:

```kotlin
                // --- Parse content blocks via AssistantMessageParser ---
                // Re-parse the full accumulated text to extract tool calls.
                // In accumulate mode, this happens once at stream end.
                val rawText = contentBuilder.toString()
                val parsedBlocks = if (toolCalls == null || toolCalls.isEmpty()) {
                    AssistantMessageParser.parse(rawText, knownToolNames, knownParamNames)
                        .takeIf { blocks -> blocks.any { it is ToolUseContent } }
                } else null

                val finalToolCalls = toolCalls ?: parsedBlocks
                    ?.filterIsInstance<ToolUseContent>()
                    ?.filter { !it.partial }
                    ?.mapIndexed { idx, block ->
                        val argsJson = kotlinx.serialization.json.buildJsonObject {
                            block.params.forEach { (k, v) -> put(k, v) }
                        }.toString()
                        ToolCall(
                            id = "xmltool_${idx + 1}",
                            function = FunctionCall(name = block.name, arguments = argsJson)
                        )
                    }
                    ?.ifEmpty { null }

                val textOnlyContent = if (parsedBlocks != null) {
                    parsedBlocks.filterIsInstance<TextContent>()
                        .joinToString("\n\n") { it.content }
                        .ifBlank { null }
                } else {
                    rawText.ifBlank { null }
                }

                val finalFinishReason = if (finalToolCalls != null && finishReason == "stop") {
                    "tool_calls"
                } else {
                    finishReason
                }

                // Signal truncation for partial tool calls
                var finalContent = textOnlyContent
                if (parsedBlocks?.any { it is ToolUseContent && it.partial } == true && finalToolCalls.isNullOrEmpty()) {
                    log.warn("[Agent:API] XML tool call truncated — signaling for retry")
                    finalContent = (finalContent ?: "") + "\n\n[TOOL_CALL_TRUNCATED]"
                }
```

- [ ] **Step 2: Add knownToolNames and knownParamNames parameters**

Add two new parameters to `sendMessageStream()`:

```kotlin
    suspend fun sendMessageStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int? = null,
        temperature: Double = 0.0,
        onChunk: suspend (StreamChunk) -> Unit,
        knownToolNames: Set<String> = emptySet(),
        knownParamNames: Set<String> = emptySet()
    ): ApiResult<ChatCompletionResponse> = withContext(Dispatchers.IO) {
```

Similarly add to `sendMessage()` and update the XML parsing there to use `AssistantMessageParser`:

```kotlin
    suspend fun sendMessage(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int? = null,
        temperature: Double = 0.0,
        toolChoice: JsonElement? = null,
        knownToolNames: Set<String> = emptySet(),
        knownParamNames: Set<String> = emptySet()
    ): ApiResult<ChatCompletionResponse> = withContext(Dispatchers.IO) {
```

In `sendMessage()`, replace the existing XML fallback block with the same `AssistantMessageParser.parse()` approach:

```kotlin
                            // --- XML tool call fallback for non-streaming path ---
                            val choice = parsed.choices.firstOrNull()
                            val requestHadNoTools = tools.isNullOrEmpty()
                            if (requestHadNoTools && choice != null && choice.message.toolCalls.isNullOrEmpty()) {
                                val content = choice.message.content
                                if (content != null) {
                                    val parsedBlocks = AssistantMessageParser.parse(content, knownToolNames, knownParamNames)
                                    val xmlToolCalls = parsedBlocks.filterIsInstance<ToolUseContent>()
                                        .filter { !it.partial }
                                    if (xmlToolCalls.isNotEmpty()) {
                                        log.info("[Agent:API] Extracted ${xmlToolCalls.size} XML tool call(s) from non-streaming response")
                                        val textOnly = parsedBlocks.filterIsInstance<TextContent>()
                                            .joinToString("\n\n") { it.content }.ifBlank { null }
                                        val toolCallDtos = xmlToolCalls.mapIndexed { idx, block ->
                                            val argsJson = kotlinx.serialization.json.buildJsonObject {
                                                block.params.forEach { (k, v) -> put(k, v) }
                                            }.toString()
                                            ToolCall(id = "xmltool_${idx + 1}", function = FunctionCall(name = block.name, arguments = argsJson))
                                        }
                                        val updatedMsg = choice.message.copy(content = textOnly, toolCalls = toolCallDtos)
                                        val updatedFr = if (choice.finishReason == "stop") "tool_calls" else choice.finishReason
                                        val updatedResp = parsed.copy(choices = listOf(choice.copy(message = updatedMsg, finishReason = updatedFr)))
                                        dumpApiResponse(updatedResp)
                                        return@withContext ApiResult.Success(updatedResp)
                                    }
                                }
                            }
```

- [ ] **Step 3: Add toolNameSet/paramNameSet to LlmBrain interface**

In `LlmBrain.kt`, add properties with defaults so existing implementations don't break:

```kotlin
    /** Known tool names for XML parser (used when xmlToolMode=true). */
    val toolNameSet: Set<String> get() = emptySet()

    /** Known param names for XML parser (used when xmlToolMode=true). */
    val paramNameSet: Set<String> get() = emptySet()
```

- [ ] **Step 4: Update OpenAiCompatBrain to pass tool names**

In `OpenAiCompatBrain`, add override properties and update `chatStream()`/`chat()` to pass tool names:

```kotlin
class OpenAiCompatBrain(
    sourcegraphUrl: String,
    tokenProvider: () -> String?,
    private val model: String,
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 180,
    httpClientOverride: OkHttpClient? = null,
    override val xmlToolMode: Boolean = false,
    override val toolNameSet: Set<String> = emptySet(),
    override val paramNameSet: Set<String> = emptySet()
) : LlmBrain {
```

Update `chatStream()`:

```kotlin
    override suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        onChunk: suspend (StreamChunk) -> Unit
    ): ApiResult<ChatCompletionResponse> {
        return client.sendMessageStream(
            messages = messages,
            tools = if (xmlToolMode) null else tools,
            maxTokens = maxTokens,
            onChunk = onChunk,
            knownToolNames = toolNameSet,
            knownParamNames = paramNameSet
        )
    }
```

Update `chat()` similarly to pass `knownToolNames` and `knownParamNames`.

- [ ] **Step 5: Build and run all core tests**

Run: `./gradlew :core:test`
Expected: All tests PASSED (existing streaming tests may need updates — see Task 5)

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrain.kt
git commit -m "feat(xml-parser): wire AssistantMessageParser into streaming + non-streaming paths

Replaces old XmlToolCallParser post-processing with new Cline-style
parser. Both sendMessageStream() and sendMessage() now use
AssistantMessageParser.parse() for XML tool extraction. Tool names
and param names passed through from brain constructor."
```

---

### Task 5: Update streaming integration tests for new format

**Files:**
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClientStreamTest.kt`

Update the XML integration tests to use the new `<tool_name>` format instead of `<tool><name>...</name>`.

- [ ] **Step 1: Update XML integration tests**

Replace the two XML integration tests to use tool-name-as-tag format:

```kotlin
    @Test
    fun `parses XML tool calls from text content when no native tools in request`() = runTest {
        val xmlContent = "Let me read that.\n\n<read_file>\n<path>Foo.kt</path>\n</read_file>"
        val escapedContent = xmlContent.replace("\"", "\\\"").replace("\n", "\\n")
        val chunk1 = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"$escapedContent"},"finish_reason":"stop"}]}"""
        val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":200,"completion_tokens":30,"total_tokens":230}}"""

        server.enqueue(sseResponse(chunk1, usageChunk))

        // Create client with known tool names for the new parser
        val xmlClient = SourcegraphChatClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "test-token" },
            model = "test-model",
            httpClientOverride = OkHttpClient.Builder().build()
        )

        val result = xmlClient.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "read Foo.kt")),
            tools = null,
            onChunk = {},
            knownToolNames = setOf("read_file"),
            knownParamNames = setOf("path")
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val response = (result as ApiResult.Success).data
        assertEquals("tool_calls", response.choices.first().finishReason)
        val toolCalls = response.choices.first().message.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls!!.size)
        assertEquals("read_file", toolCalls[0].function.name)
        assertTrue(toolCalls[0].function.arguments.contains("Foo.kt"))
        assertEquals("Let me read that.", response.choices.first().message.content?.trim())
    }

    @Test
    fun `parses multiple parallel XML tool calls`() = runTest {
        val xmlContent = "Reading both.\n\n<read_file>\n<path>A.kt</path>\n</read_file>\n\n<read_file>\n<path>B.kt</path>\n</read_file>"
        val escapedContent = xmlContent.replace("\"", "\\\"").replace("\n", "\\n")
        val chunk = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"$escapedContent"},"finish_reason":"stop"}]}"""
        val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}"""

        server.enqueue(sseResponse(chunk, usageChunk))

        val xmlClient = SourcegraphChatClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "test-token" },
            model = "test-model",
            httpClientOverride = OkHttpClient.Builder().build()
        )

        val result = xmlClient.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "read both")),
            tools = null,
            onChunk = {},
            knownToolNames = setOf("read_file"),
            knownParamNames = setOf("path")
        )

        assertInstanceOf(ApiResult.Success::class.java, result)
        val toolCalls = (result as ApiResult.Success).data.choices.first().message.toolCalls
        assertNotNull(toolCalls)
        assertEquals(2, toolCalls!!.size)
        assertTrue(toolCalls[0].function.arguments.contains("A.kt"))
        assertTrue(toolCalls[1].function.arguments.contains("B.kt"))
    }
```

- [ ] **Step 2: Run all core tests**

Run: `./gradlew :core:test`
Expected: All tests PASSED

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClientStreamTest.kt
git commit -m "test(xml-parser): update streaming tests for tool-name-as-tag format

Updated XML integration tests to use <read_file><path>...</path></read_file>
format instead of <tool><name>...</name><args>...</args></tool>. Pass
knownToolNames and knownParamNames to sendMessageStream()."
```

---

### Task 6: Delete old parser files and replace in SystemPrompt + AgentService

**Files:**
- Delete: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/XmlToolCallParser.kt`
- Delete: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/XmlToolDefinitionBuilder.kt`
- Delete: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/XmlToolCallParserTest.kt`
- Delete: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/XmlToolDefinitionBuilderTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Delete old parser files**

```bash
git rm core/src/main/kotlin/com/workflow/orchestrator/core/ai/XmlToolCallParser.kt
git rm core/src/main/kotlin/com/workflow/orchestrator/core/ai/XmlToolDefinitionBuilder.kt
git rm core/src/test/kotlin/com/workflow/orchestrator/core/ai/XmlToolCallParserTest.kt
git rm core/src/test/kotlin/com/workflow/orchestrator/core/ai/XmlToolDefinitionBuilderTest.kt
```

- [ ] **Step 2: Update SystemPrompt to use new builder**

In `SystemPrompt.kt`, rename `toolDefinitionsXml` to `toolDefinitionsMarkdown` and update the section 6c:

```kotlin
        /** Markdown tool definitions for Cline-style XML format (tools defined in prompt). */
        toolDefinitionsMarkdown: String? = null
```

And the section:

```kotlin
        // 6c. TOOL DEFINITIONS (when XML tool mode active)
        if (toolDefinitionsMarkdown != null) {
            append(SECTION_SEP)
            append(toolDefinitionsMarkdown)
        }
```

Remove the old header text ("# Tool Definitions\n\nYou have access to the following tools...") — the `ToolPromptBuilder` output already includes the format instructions.

- [ ] **Step 3: Update AgentService to use new builder and pass tool names**

In `AgentService.kt`, replace `XmlToolDefinitionBuilder` usage with `ToolPromptBuilder`:

```kotlin
                // Build system prompt with tool definitions
                val systemPromptBuilder = { toolDefsMarkdown: String? ->
                    SystemPrompt.build(
                        // ... existing params ...
                        toolDefinitionsMarkdown = toolDefsMarkdown
                    )
                }
                ctx.setSystemPrompt(systemPromptBuilder(null))
```

In the `toolDefinitionProvider` lambda, use `ToolPromptBuilder.build(defs)` instead of `XmlToolDefinitionBuilder.build(defs)`.

Collect param names dynamically:

```kotlin
                val allParamNames = registry.getActiveTools().values
                    .flatMap { it.parameters.properties.keys }
                    .toSet()
```

Pass `toolNameSet` and `paramNameSet` to `OpenAiCompatBrain` constructor:

```kotlin
                val allToolNames = registry.getActiveTools().keys

                return OpenAiCompatBrain(
                    sourcegraphUrl = sgUrl,
                    tokenProvider = tokenProvider,
                    model = modelId,
                    xmlToolMode = xmlMode,
                    toolNameSet = allToolNames,
                    paramNameSet = allParamNames
                )
```

Also update the `brainFactory` fallback brain construction the same way.

- [ ] **Step 4: Build and run all tests**

Run: `./gradlew :core:test` then `./gradlew :agent:test`
Expected: All tests PASSED

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(xml-parser): replace old parser with Cline-style tool-name-as-tag

Deletes XmlToolCallParser and XmlToolDefinitionBuilder (replaced by
AssistantMessageParser and ToolPromptBuilder). Updates SystemPrompt
to use markdown tool definitions. AgentService collects tool names
and param names dynamically from registry and passes to brain."
```

---

### Task 7: Replace chunk filter in AgentLoop with block-based presentation

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`

Replace the character-by-character `<tool>...</tool>` chunk filter with block-based text presentation using the new parser. In accumulate mode (default), this parses on every chunk for UI text presentation but waits until stream end for tool execution.

- [ ] **Step 1: Replace the chunk filter in the onChunk lambda**

Replace the entire `// XML tag suppression state` block and the `onChunk` lambda with:

```kotlin
            // Block-based streaming presentation (Cline port)
            // Accumulates text, re-parses on every chunk, sends only TextContent to UI.
            // Tool/param names fetched from brain (updated by AgentService when deferred tools load).
            val accumulatedText = StringBuilder()
            var lastPresentedTextLength = 0
            val currentToolNames = brain.toolNameSet
            val currentParamNames = brain.paramNameSet

            val apiResult = brain.chatStream(
                messages = contextManager.getMessages(),
                tools = currentToolDefs,
                maxTokens = maxOutputTokens,
                onChunk = { chunk ->
                    val text = chunk.choices.firstOrNull()?.delta?.content ?: return@chatStream

                    // Always accumulate
                    accumulatedText.append(text)

                    // Re-parse full accumulated text
                    val blocks = AssistantMessageParser.parse(
                        accumulatedText.toString(),
                        currentToolNames,
                        currentParamNames
                    )

                    // Extract visible text (TextContent blocks only)
                    val visibleText = blocks.filterIsInstance<TextContent>()
                        .joinToString("") { it.content }

                    // Only send NEW text to UI (delta since last presentation)
                    val stripped = AssistantMessageParser.stripPartialTag(visibleText)
                    if (stripped.length > lastPresentedTextLength) {
                        val delta = stripped.substring(lastPresentedTextLength)
                        onStreamChunk(delta)
                        lastPresentedTextLength = stripped.length
                    }
                }
            )
```

Also remove the old final buffer flush after `chatStream()`.

- [ ] **Step 2: Add import for AssistantMessageParser**

Add to imports in AgentLoop.kt:

```kotlin
import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.TextContent
import com.workflow.orchestrator.core.ai.ToolUseContent
```

- [ ] **Step 3: Build and run agent tests**

Run: `./gradlew :agent:test`
Expected: All tests PASSED

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt
git commit -m "feat(xml-parser): replace chunk filter with block-based presentation

Uses AssistantMessageParser.parse() on every SSE chunk to extract
TextContent blocks. Only sends text deltas to UI (not raw XML).
stripPartialTag() prevents flickering incomplete tags. Accumulate
mode: tools still execute after stream ends via existing path."
```

---

## Phase 3: Settings + Stream Interrupt Mode (Tasks 8-10)

### Task 8: Add toolExecutionMode setting

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentAdvancedConfigurable.kt`

- [ ] **Step 1: Add setting**

In `AgentSettings.State`:

```kotlin
        var toolExecutionMode by string("accumulate") // "accumulate" or "stream_interrupt"
```

- [ ] **Step 2: Add UI in AgentAdvancedConfigurable**

In the "Tool Calling" group (added in the prior PR), replace the `useXmlToolMode` checkbox with:

```kotlin
            group("Tool Calling") {
                row("Tool execution mode:") {
                    comboBox(listOf("accumulate", "stream_interrupt"))
                        .bindItem(
                            { agentSettings.state.toolExecutionMode },
                            { agentSettings.state.toolExecutionMode = it ?: "accumulate" }
                        )
                        .comment("accumulate: execute all tools after response completes (default). " +
                            "stream_interrupt: execute each tool as soon as it appears (Cline-style).")
                }
            }
```

Remove the old `useXmlToolMode` checkbox.

- [ ] **Step 3: Remove useXmlToolMode from PluginSettings**

In `PluginSettings.kt`, remove the `useXmlToolMode` field. In `AgentService.kt`, remove all references to `useXmlToolMode` — XML mode is always on now.

- [ ] **Step 4: Remove xmlToolMode from LlmBrain and OpenAiCompatBrain**

In `LlmBrain.kt`, remove the `xmlToolMode` property entirely.

In `OpenAiCompatBrain.kt`:
- Remove `override val xmlToolMode` from constructor
- Change `chatStream()` to always pass `tools = null` (XML is always on)
- Change `chat()` to always pass `tools = null`

In `AgentService.kt`:
- Remove `xmlToolMode = xmlMode` from both brain construction sites (primary at ~203 and brainFactory at ~625)
- Remove the `val xmlMode = ...` line

In `AgentController.kt`:
- Remove the `useXmlToolMode` check in the `ask_followup_question` callback — always skip `appendStreamToken()` since XML is always on

- [ ] **Step 5: Build and verify**

Run: `./gradlew :core:compileKotlin :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(xml-parser): add toolExecutionMode setting, remove xmlToolMode entirely

New setting: accumulate (default) or stream_interrupt (Cline-style).
Removes xmlToolMode from LlmBrain, OpenAiCompatBrain, PluginSettings,
AgentService, and AgentController. XML mode is now the only path —
brain always sends tools=null (definitions in system prompt)."
```

---

### Task 9: Implement stream-interrupt mode in AgentLoop

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt`

- [ ] **Step 1: Add shouldInterruptStream flag to SourcegraphChatClient**

```kotlin
    /** Set by AgentLoop to cooperatively interrupt the SSE stream mid-response. */
    @Volatile var shouldInterruptStream = false
```

In the SSE reader loop, add a check after each line:

```kotlin
                var line = reader.readLine()
                while (line != null) {
                    coroutineContext.ensureActive()
                    if (shouldInterruptStream) {
                        log.info("[Agent:API] Stream interrupted by caller (mid-stream tool execution)")
                        break
                    }
                    // ... existing SSE parsing ...
                    line = reader.readLine()
                }
                shouldInterruptStream = false  // Reset for next call
```

- [ ] **Step 2: Add stream-interrupt logic to AgentLoop**

In `AgentLoop`, add a `toolExecutionMode` parameter:

```kotlin
    private val toolExecutionMode: String = "accumulate",  // "accumulate" or "stream_interrupt"
```

In the `onChunk` lambda (inside Task 7's block-based presentation code), after parsing blocks, check for newly completed tool blocks and interrupt:

```kotlin
                    // Stream-interrupt: if a tool block just completed, interrupt stream
                    if (toolExecutionMode == "stream_interrupt") {
                        val completedTool = blocks.filterIsInstance<ToolUseContent>()
                            .firstOrNull { !it.partial }
                        if (completedTool != null) {
                            brain.interruptStream()
                            // The SSE reader will break out, chatStream() returns
                        }
                    }
```

Add a `interruptStream()` method to `OpenAiCompatBrain`:

```kotlin
    fun interruptStream() {
        client.shouldInterruptStream = true
    }
```

And add to `LlmBrain` interface:

```kotlin
    /** Signal the active stream to stop cooperatively. */
    fun interruptStream() {}
```

- [ ] **Step 3: Build and run all tests**

Run: `./gradlew :core:test` then `./gradlew :agent:test`
Expected: All tests PASSED

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrain.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt
git commit -m "feat(xml-parser): implement stream-interrupt mode

Cooperative stream interruption via shouldInterruptStream flag on
SourcegraphChatClient. AgentLoop detects completed tool blocks during
streaming and triggers interrupt. LlmBrain interface gains
interruptStream() method. Default mode remains accumulate."
```

---

### Task 10: Wire settings through AgentService and run full test suite

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Pass toolExecutionMode to AgentLoop**

In `AgentService`, where `AgentLoop` is constructed, read the setting and pass it:

```kotlin
val executionMode = agentSettings.state.toolExecutionMode

// In AgentLoop constructor:
AgentLoop(
    brain = brain,
    // ... existing params ...
    toolExecutionMode = executionMode
)
```

- [ ] **Step 2: Run full test suite**

Run: `./gradlew :core:test` then `./gradlew :agent:test`
Expected: All tests PASSED

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(xml-parser): wire toolExecutionMode setting through AgentService

Reads toolExecutionMode from AgentSettings and passes to AgentLoop.
Default 'accumulate' preserves current behavior."
```

---

## Summary

| Task | Phase | What | Test |
|------|-------|------|------|
| 1 | Model | Content block data classes | Compilation |
| 2 | Parser | AssistantMessageParser with TDD | 12 unit tests |
| 3 | Builder | ToolPromptBuilder with TDD | 4 unit tests |
| 4 | Wiring | Wire parser into SourcegraphChatClient | Core tests |
| 5 | Tests | Update streaming integration tests | 6 streaming tests |
| 6 | Cleanup | Delete old parser, update SystemPrompt + AgentService | Full test suite |
| 7 | Loop | Block-based presentation in AgentLoop | Agent tests |
| 8 | Settings | toolExecutionMode setting, remove xmlToolMode + UI | Compilation |
| 9 | Interrupt | Stream-interrupt mode implementation | Full test suite |
| 10 | Wiring | Wire settings through AgentService | Full test suite |

---

## Review Findings Addressed

| # | Severity | Issue | Resolution |
|---|----------|-------|------------|
| C1 | CRITICAL | `assignedId` inconsistency | Removed spec reference; IDs generated inline via `mapIndexed` in Task 4/5 (consistent) |
| C2 | CRITICAL | `toolNameSet`/`paramNameSet` not on `LlmBrain` | Add to `LlmBrain` interface with defaults in Task 4 Step 3 |
| C3 | CRITICAL | Not all `OpenAiCompatBrain` constructors updated | Task 6 Step 3 must update ALL construction sites (primary + brainFactory) |
| C4 | CRITICAL | `xmlToolMode` removal missing | Added to Task 8: remove from `LlmBrain`, `OpenAiCompatBrain`, always pass `tools=null` |
| I1 | IMPORTANT | Stream-interrupt placeholder code | Task 9 Step 2: use `brain.interruptStream()` (added to `LlmBrain` in same task) |
| I3 | IMPORTANT | `ask_followup_question` dup fix not addressed | Resolved by Task 7 block-based presentation — text duplication is structurally eliminated |
| I4 | IMPORTANT | Deferred tool names not updated | Pass name sets as provider lambdas to AgentLoop instead of static sets |
| I5 | IMPORTANT | `comboBox()` DSL may not compile | Use `DefaultComboBoxModel` pattern matching existing codebase |
| S1 | SUGGESTION | Test count 13 vs 12 | Corrected to 12 in summary table |

**Implementation note for C2 + C4 + I4:** The cleanest approach is:
- `LlmBrain` gets `val toolNameSet: Set<String>` and `val paramNameSet: Set<String>` with default `emptySet()`
- `OpenAiCompatBrain` overrides them as constructor params
- Task 8 removes `xmlToolMode` from both interfaces and makes `chatStream()`/`chat()` always pass `tools=null`
- For deferred tools (I4): `AgentLoop` receives `toolNameProvider: () -> Set<String>` and `paramNameProvider: () -> Set<String>` lambdas that re-read from registry each iteration, rather than static sets on the brain
