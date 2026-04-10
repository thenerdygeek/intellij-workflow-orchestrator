# Incremental XML Parser — Cline Faithful Port

> Faithful port of Cline's `parseAssistantMessageV2` streaming XML parser to Kotlin. Replaces the post-processing `XmlToolCallParser` with a re-parse-on-every-chunk approach that enables mid-stream tool execution.

## Problem

The current XML tool call parser (`XmlToolCallParser`) runs **after** streaming completes — it post-processes the full accumulated text. This causes:

1. **Delayed tool execution** — all tools wait for the full response before any execute
2. **Duplicate text** — tool callbacks (e.g., `ask_followup_question`) re-emit text already shown by streaming
3. **No partial tool UI** — users see raw text until the stream ends, then tools appear all at once
4. **Format divergence** — our `<tool><name>...</name><args>...</args></tool>` format differs from Cline's simpler `<tool_name><param>value</param></tool_name>`

## Solution

Port Cline's `parseAssistantMessageV2()` — a state machine that scans the full accumulated text on every SSE chunk, producing a list of content blocks (`TextContent | ToolUse`) each with a `partial: boolean` flag. Complete tool blocks execute immediately mid-stream.

## Target Class

The implementation targets `AgentLoop.kt` (`agent/.../loop/AgentLoop.kt`), which contains the ReAct loop with `run()`. The agent module's `CLAUDE.md` references `SingleAgentSession` but that is a planned refactor on a separate branch — this work targets the existing `AgentLoop` class in this worktree.

## XML Format (Cline Standard)

**Tool-name-as-tag** — each tool name IS the XML tag. No wrapper `<tool>` element.

```xml
<read_file>
<path>src/main/kotlin/Foo.kt</path>
</read_file>

<edit_file>
<path>src/Foo.kt</path>
<old_string>fun bar() = 41</old_string>
<new_string>fun bar() = 42</new_string>
</edit_file>

<run_command>
<command>./gradlew test</command>
</run_command>
```

Tool definitions in the system prompt use the same format:

```
## read_file
Description: Read a file's contents
Parameters:
- path: (required) The file path to read
Usage:
<read_file>
<path>File path here</path>
</read_file>
```

## Content Block Model

Faithful port of Cline's `AssistantMessageContent`:

```kotlin
sealed class AssistantMessageContent {
    abstract val partial: Boolean
}

data class TextContent(
    val content: String,
    override val partial: Boolean
) : AssistantMessageContent()

data class ToolUseContent(
    val name: String,
    val params: MutableMap<String, String>,
    override var partial: Boolean
) : AssistantMessageContent()
```

- `TextContent` — text between tool calls (reasoning, explanations). `partial=true` while text is still arriving before the next tool tag or stream end.
- `ToolUseContent` — a tool invocation. `partial=true` while the closing `</tool_name>` hasn't arrived yet. `params` is a mutable map built incrementally as child tags are parsed.

## Parser Design

### Module Placement

The parser lives in `:core` (`core/.../ai/AssistantMessageParser.kt`) as a pure function. It receives tool names as a `Set<String>` parameter — no dependency on the `:agent` module's tool registry. The caller (`AgentLoop` in `:agent`) passes `registry.getActiveTools().keys`.

### Algorithm: Full Re-parse on Every Chunk

Faithful port of Cline's approach — **not** incremental across chunks. On every SSE text delta:

```kotlin
accumulatedText += chunk.text
contentBlocks = AssistantMessageParser.parse(accumulatedText, knownToolNames, knownParamNames)
```

The parser is stateless (pure function). All state lives in the output list. This eliminates chunk-boundary bugs at the cost of O(n²) over the message length. For typical assistant messages (<100KB) with ~10-byte SSE chunks, total string scanning is ~1GB — benchmarked at tens of milliseconds on JVM `String.indexOf`. Cline uses this exact approach in production.

### State Machine (3 States)

1. **Scanning text** — looking for any `<tool_name>` opening tag. Text before the tag becomes a `TextContent` block.
2. **Inside tool (not in param)** — looking for `<param_name>` or `</tool_name>`. Whitespace between params is ignored. Only known param names are matched — unknown tags are ignored (not treated as params).
3. **Inside param value** — accumulating value until `</param_name>`. **Sequential guarantee:** once inside a param value (state 3), ONLY the current param's closing tag is scanned for. Other param or tool tags are treated as literal text. This prevents `<path>` inside `<new_string>` from being confused with the `<path>` parameter. For code-carrying params (`content`, `new_string`, `old_string`, `diff`, `code`), uses `lastIndexOf` for the closing tag to handle code containing XML-like strings (e.g., `</div>`).

### Tag Detection

Pre-compute lookup maps for O(1) matching (ported from Cline):

```kotlin
val toolOpenTags: Map<String, String>  // "<read_file>" → "read_file"
val paramOpenTags: Map<String, String> // "<path>" → "path"
```

Detection uses backward `startsWith` from the current scan position — checking if the substring ending at the current index matches a known tag.

### Known Tool Names

The parser needs the full list of tool names upfront (both static and dynamic):

- **Static tools**: All registered `AgentTool.name` values from the tool registry
- **Dynamic tools**: MCP tools, deferred tools activated via `request_tools`, custom subagent tools

Cline handles this via `setDynamicToolUseNames()` called before parsing. Our equivalent: `registry.getActiveTools().keys` passed as `Set<String>` to `parse()` on each call.

### Known Parameter Names — Dynamic Generation

Unlike Cline's fixed 59-entry list, our parameter names are **generated dynamically** from tool definitions. This prevents silent failures when new tools are added:

```kotlin
fun collectParamNames(tools: Collection<AgentTool>): Set<String> {
    return tools.flatMap { it.parameters.properties.keys }.toSet()
}
```

This is called once at session start and updated when deferred tools are activated. The set is passed to `parse()` alongside tool names.

### Partial Tag Handling

Since the full string is re-parsed each time, partial tags at the end are handled naturally:

- String ends mid-tag (e.g., `<read_fi`) → parser doesn't match any known tag → text content continues
- String ends with open tool, no closing tag → `ToolUseContent(partial=true)` emitted
- String ends with open param, no closing tag → param value accumulated so far stored, tool remains `partial=true`

After the stream ends, all remaining `partial` blocks are marked `partial=false` for final processing.

### Partial Tag Stripping for UI

Before sending text to the UI, strip incomplete XML tags at the end (ported from Cline):

```kotlin
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
```

This prevents the user from seeing flickering partial tags like `<read_` during streaming. **Known limitation (ported from Cline):** legitimate text like "Use the < operator" could be false-positive stripped if a chunk boundary lands after `<`. This is an accepted trade-off.

### Tool Call ID Generation

IDs are assigned **outside the parser** when a tool block transitions from `partial=true` to `partial=false` (or at stream finalization). The stateless parser does not generate IDs — it returns blocks without IDs. The caller (`AgentLoop`) assigns IDs once per completed block using `AtomicInteger`:

```kotlin
// In AgentLoop, after parse() returns:
val newlyComplete = blocks.filterIsInstance<ToolUseContent>()
    .filter { !it.partial && it !in previouslyCompleted }
for (block in newlyComplete) {
    block.assignedId = "xmltool_${idCounter.incrementAndGet()}"
    previouslyCompleted.add(block)
}
```

## Tool Execution Model

### Two Modes (Configurable)

**Mode A: Stream-interrupt (Cline default)**
- Execute each tool as soon as its closing tag arrives (mid-stream)
- After execution, inject tool result into context and interrupt the stream
- LLM gets the result back immediately and decides the next action
- One tool per LLM response (tighter feedback loops)
- Matches Cline's `!isParallelToolCallingEnabled()` behavior

**Mode B: Accumulate-all (our current behavior)**
- Parse all content blocks but don't execute until stream ends
- Execute all complete tool calls together (parallel for read-only, sequential for writes)
- Multiple tools per LLM response (faster for multi-file reads)

Setting: `toolExecutionMode` in `AgentSettings` — `"stream_interrupt"` or `"accumulate"`. Default: `"accumulate"` (preserves current behavior, users can opt into Cline-style).

### Presentation Index

A `presentationIndex: Int` variable (initialized to 0, reset at the start of each API call) tracks which content block in the parsed list is currently being presented to the user. This is ported from Cline's `currentStreamingContentIndex`.

On each parse cycle:
- If `presentationIndex < blocks.size`, process the block at that index
- `TextContent`: send stripped text to UI
- `ToolUseContent(partial=true)`: no action (or show partial indicator in future UI phase)
- `ToolUseContent(partial=false)`: execute tool (stream-interrupt) or queue for later (accumulate)
- After processing a complete block, increment `presentationIndex`
- Blocks before `presentationIndex` are already handled — skip them

### Stream Interruption Mechanics (Mode A Only)

When a `ToolUseContent` block becomes complete mid-stream:

1. **Cancel the HTTP call**: `cancelActiveRequest()` closes the OkHttp socket. The SSE reader's `readLine()` throws `IOException("Canceled")`, which is already caught at `SourcegraphChatClient.kt:449` and returns `ApiResult.Error(NETWORK_ERROR, "Request cancelled")`.

2. **Capture partial response**: Before cancellation, the `AgentLoop` has already accumulated text and tool blocks from prior parse cycles. The complete tool block plus all preceding text blocks form the "partial response."

3. **Return contract change**: `brain.chatStream()` still returns `ApiResult<ChatCompletionResponse>`. In stream-interrupt mode, the returned `ChatCompletionResponse` contains:
   - `choices[0].message.content` = accumulated text-only content (no XML)
   - `choices[0].message.toolCalls` = `[the single completed tool call]`
   - `choices[0].finishReason` = `"tool_calls"` (synthetic — the actual finish_reason is lost due to cancellation)
   - `usage` = null (stream was interrupted, no usage chunk received)

4. **Token estimation**: Since `usage` is null (stream interrupted), token count is estimated from the accumulated text using `TokenEstimator.estimate()`. This is already handled by the existing "streaming token estimate when usage is null" logic in `AgentLoop`.

5. **The SSE reader loop needs a cancellation signal**: Add a `@Volatile var shouldInterruptStream = false` flag on `SourcegraphChatClient` (or the `AgentLoop`). Before cancelling the HTTP call, set this flag. The SSE reader checks it on each line: `if (shouldInterruptStream) break`. This provides a cooperative exit path that avoids the `IOException` and allows clean partial response capture.

### Context Management for Mid-Stream Execution

After stream interruption, the conversation history is structured as:

```
[existing messages...]
assistant: { content: "I'll read that file.", toolCalls: [{read_file, {path: "Foo.kt"}}] }
user: "TOOL RESULT:\n<file content>"
```

This matches the existing format — `contextManager.addAssistantMessage()` receives a `ChatMessage` with both `content` (text-only) and `toolCalls` (the single completed tool). `contextManager.addToolResult()` adds the result as a user message (per `sanitizeMessages()` conversion).

The key difference from accumulate mode: the assistant message contains only the text and tool call(s) that were seen before interruption. Any text the LLM would have generated after the tool call is lost (the stream was cancelled). The LLM compensates on the next call — it sees the tool result and continues from there.

Two text accumulators (ported from Cline):
- `accumulatedText` — full text including XML tool tags (for parsing)
- `textOnlyContent` — text blocks only, no XML (for assistant message `content` field and conversation history)

### Execution Flow (Stream-Interrupt Mode)

```
presentationIndex = 0
accumulatedText = ""
textOnlyContent = ""

On each SSE chunk:
  1. accumulatedText += chunk.text
  2. blocks = parse(accumulatedText, toolNames, paramNames)
  3. While presentationIndex < blocks.size:
     block = blocks[presentationIndex]
     - If TextContent(partial=true): send stripPartialTag(block.content) to UI, break
     - If TextContent(partial=false): send block.content to UI, textOnlyContent += block.content, presentationIndex++
     - If ToolUseContent(partial=true): break (wait for more chunks)
     - If ToolUseContent(partial=false):
         a. Assign ID, convert to ToolCall
         b. Set shouldInterruptStream = true
         c. Build ChatCompletionResponse with textOnlyContent + [toolCall]
         d. cancelActiveRequest()
         e. Return response to AgentLoop for tool execution
         f. (AgentLoop handles: addAssistantMessage, executeToolCall, addToolResult, next LLM call)
```

### Execution Flow (Accumulate Mode)

```
On each SSE chunk:
  1. accumulatedText += chunk.text
  2. blocks = parse(accumulatedText, toolNames, paramNames)
  3. Send visible text (TextContent blocks, stripped of partial tags) to UI

After stream ends:
  4. Mark all partial blocks as complete
  5. Assign IDs to all ToolUseContent blocks
  6. Collect textOnlyContent from TextContent blocks
  7. Convert ToolUseContent blocks to ToolCall DTOs
  8. Build ChatCompletionResponse with textOnlyContent + toolCalls
  9. Return via existing AgentLoop flow (same as current)
```

### Conversion: ToolUseContent → ToolCall DTO

For compatibility with the existing `AgentLoop` tool execution pipeline:

```kotlin
fun ToolUseContent.toToolCall(id: String): ToolCall {
    val argsJson = buildJsonObject {
        params.forEach { (k, v) -> put(k, v) }
    }.toString()
    return ToolCall(
        id = id,
        function = FunctionCall(name = name, arguments = argsJson)
    )
}
```

## System Prompt Changes

### Tool Definition Format

Replace `XmlToolDefinitionBuilder` with Cline's `buildUsageSection` pattern:

```
## tool_name
Description: ...
Parameters:
- param1: (required) Description
- param2: (optional) Description
Usage:
<tool_name>
<param1>value here</param1>
<param2>value here</param2>
</tool_name>
```

No `<tool_definitions>` wrapper. No `<tool_usage_instructions>` block. Each tool is a markdown section with a usage example showing the exact XML format. Descriptions are escaped for XML safety (`&`, `<`, `>` → `&amp;`, `&lt;`, `&gt;`).

### Format Instructions

Add a concise format instruction section (ported from Cline's `formatting.ts`):

```
# Tool Use Format

You have tools to assist you. To use a tool, write the XML tag for that tool with its parameters as child tags:

<tool_name>
<parameter_name>value</parameter_name>
</tool_name>

- Always use the XML format shown above — do not use JSON or code blocks for tool calls.
- You may use multiple tools in one response.
- For parameters containing code (content, new_string, old_string, diff), write the code directly — no escaping needed.
- Even for large code blocks (100+ lines), use the appropriate tool with XML tags.
```

## Files Changed

### Deleted
- `core/.../ai/XmlToolCallParser.kt` — replaced by `AssistantMessageParser`
- `core/.../ai/XmlToolDefinitionBuilder.kt` — replaced by `ToolPromptBuilder`
- `core/.../ai/XmlToolCallParserTest.kt` — replaced by `AssistantMessageParserTest`
- `core/.../ai/XmlToolDefinitionBuilderTest.kt` — replaced by `ToolPromptBuilderTest`

### New Files
- `core/.../ai/AssistantMessageParser.kt` — the parser (pure function, stateless, in `:core`)
- `core/.../ai/AssistantMessageContent.kt` — content block data classes
- `core/.../ai/ToolPromptBuilder.kt` — system prompt tool definition builder
- `core/.../ai/AssistantMessageParserTest.kt` — parser tests (rewritten for new format)
- `core/.../ai/ToolPromptBuilderTest.kt` — builder tests (rewritten for new format)

### Modified
- `core/.../ai/SourcegraphChatClient.kt` — add `shouldInterruptStream` flag, cooperative exit in SSE reader; replace XML post-processing with `AssistantMessageParser` in accumulate mode
- `core/.../ai/OpenAiCompatBrain.kt` — remove `xmlToolMode` flag (always XML now); always strip `tools` from API requests
- `core/.../ai/LlmBrain.kt` — remove `xmlToolMode` property
- `agent/.../prompt/SystemPrompt.kt` — replace `toolDefinitionsXml` param with `toolDefinitionsMarkdown` using new builder
- `agent/.../AgentService.kt` — collect tool names + param names, pass to parser; use new builder for system prompt; wire `shouldInterruptStream` for stream-interrupt mode
- `agent/.../loop/AgentLoop.kt` — replace chunk filter with block-based presentation; track `presentationIndex`; in stream-interrupt mode, cancel stream and return partial response
- `agent/.../settings/AgentSettings.kt` — add `toolExecutionMode` setting
- `agent/.../settings/AgentAdvancedConfigurable.kt` — add execution mode dropdown, remove old `useXmlToolMode` checkbox
- `core/.../settings/PluginSettings.kt` — remove `useXmlToolMode` (always XML now)

### Test Changes
- Rewrite all XML parser tests for `<tool_name>` format (same edge case scenarios: nested XML, truncation, large content, text between tools, empty args)
- Rewrite builder tests for markdown format
- Update streaming integration tests in `SourcegraphChatClientStreamTest`
- Add stream-interrupt integration test: mock SSE with 2 tool calls, verify first executes mid-stream

## Backward Compatibility

- The `useXmlToolMode` setting is removed — XML mode is always on. The native function calling path (`tools: [...]` in API request) remains in the code but is no longer the default. If the Sourcegraph gateway fixes the concatenated JSON bug, we can re-enable native mode as a separate effort.
- The `}{` split workaround in `SourcegraphChatClient` remains untouched (it's in the native path).
- The `xmlToolMode` flag on `LlmBrain`/`OpenAiCompatBrain` is removed — the brain always strips tools from the API request.
- **Session persistence**: Old sessions stored with `<tool><name>...</name>` format in JSONL will load but their tool call content appears as text (no XML re-parsing on replay). New sessions use the new format. This is acceptable — session replay is for context resumption, not re-execution.

## Non-Goals

- **Presentation scheduler** — deferred to the user's upcoming streaming UI phase (see memory: `project_streaming_ui_phase.md` for Cline's 40ms `TaskPresentationScheduler` design)
- **Native function calling re-enablement** — separate effort if/when gateway is fixed
- **Incremental tool-use UI indicators** — showing "Using read_file..." with live arg preview during streaming. The block model enables this but the UI work is deferred to streaming UI phase.

## Review Findings Addressed

| # | Severity | Issue | Resolution |
|---|----------|-------|------------|
| C1 | CRITICAL | Stream interruption mechanics unspecified | Added "Stream Interruption Mechanics" section: cooperative `shouldInterruptStream` flag, `cancelActiveRequest()`, partial response capture, return contract |
| C2 | CRITICAL | Context management for mid-stream unaddressed | Added "Context Management for Mid-Stream Execution" section: two text accumulators, assistant message structure, tool result injection |
| C3 | CRITICAL | Target class ambiguity | Added "Target Class" section: targets `AgentLoop.kt`, not `SingleAgentSession` |
| I1 | IMPORTANT | `presentationIndex` undefined | Added "Presentation Index" section with lifecycle and reset conditions |
| I2 | IMPORTANT | Tool names across module boundary | Added "Module Placement" section: parser in `:core` receives `Set<String>` param |
| I3 | IMPORTANT | Static param name list | Changed to dynamic generation from tool definitions via `collectParamNames()` |
| I4 | IMPORTANT | Parse order for nested code params | Added sequential guarantee explanation in state machine state 3 |
| I5 | IMPORTANT | Tool result injection unspecified | Described in context management section: standard assistant+toolResult message pair |
| I6 | IMPORTANT | Non-deterministic tool call IDs | ID generation moved outside parser, assigned once on block completion |
