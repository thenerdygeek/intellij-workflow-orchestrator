# Tool Call & Tool Result Message Formats — Cross-Agent Comparison

**Date:** 2026-04-04
**Scope:** 8 open-source agents + 2 API specs analyzed (source-code level)

## Executive Summary

There are **three fundamentally different approaches** to tool calling in agentic AI systems:

1. **Native API tool calling** — Uses the provider's built-in `tool_use`/`tool_calls` format
2. **Text-based tool calling** — Tools embedded as XML/markdown in assistant text, parsed client-side
3. **Hybrid** — Supports both, choosing at runtime based on model capabilities

**The industry consensus is clear: use native API tool calling when available, with text-based fallback for models that don't support it.**

Using `<tools></tools>` XML tags is a **legacy/fallback approach**, not the recommended primary method.

---

## API Wire Formats (The Foundation)

### Anthropic Messages API

```
┌─────────────────────────────────────────────────────────────┐
│ ONLY TWO ROLES: "user" and "assistant"                      │
│ Tool results are "user" messages with type: "tool_result"   │
│ Tool calls are content blocks in "assistant" messages        │
└─────────────────────────────────────────────────────────────┘
```

**Assistant calls a tool:**
```json
{
  "role": "assistant",
  "content": [
    { "type": "text", "text": "I'll check the weather." },
    { "type": "tool_use", "id": "toolu_abc123", "name": "get_weather", "input": { "location": "SF" } }
  ]
}
```

**Tool result sent back:**
```json
{
  "role": "user",
  "content": [
    { "type": "tool_result", "tool_use_id": "toolu_abc123", "content": "73°F and sunny" }
  ]
}
```

**Error result:**
```json
{
  "role": "user",
  "content": [
    { "type": "tool_result", "tool_use_id": "toolu_abc123", "content": "Connection timeout", "is_error": true }
  ]
}
```

### OpenAI Chat Completions API

```
┌─────────────────────────────────────────────────────────────┐
│ THREE ROLES: "user", "assistant", "tool"                    │
│ Tool results have dedicated "tool" role                     │
│ Tool calls are in a separate "tool_calls" array             │
└─────────────────────────────────────────────────────────────┘
```

**Assistant calls a tool:**
```json
{
  "role": "assistant",
  "content": null,
  "tool_calls": [
    { "id": "call_abc123", "type": "function", "function": { "name": "get_weather", "arguments": "{\"location\":\"SF\"}" } }
  ]
}
```

**Tool result sent back:**
```json
{
  "role": "tool",
  "content": "73°F and sunny",
  "tool_call_id": "call_abc123"
}
```

### OpenAI Responses API (newer)

```
┌─────────────────────────────────────────────────────────────┐
│ NO ROLES for tool items — flat array of typed items          │
│ function_call and function_call_output are standalone items  │
└─────────────────────────────────────────────────────────────┘
```

**Tool call (standalone item):**
```json
{ "type": "function_call", "call_id": "call-001", "name": "shell", "arguments": "{\"command\":[\"ls\"]}" }
```

**Tool result (standalone item):**
```json
{ "type": "function_call_output", "call_id": "call-001", "output": "file1.txt\nfile2.txt" }
```

---

## Per-Agent Analysis

### 1. Cline (TypeScript, VS Code Extension)

**Approach:** HYBRID — XML-based (legacy) + Native API (modern)

**Internal canonical format:** Anthropic's message format (even for OpenAI models)

**XML mode (legacy):**
- Assistant returns XML tags in text content: `<read_file><path>main.ts</path></read_file>`
- Tool result sent as `role: "user"` with `type: "text"` content
- Tools defined in system prompt as XML usage examples

**Native mode:**
- Assistant returns `type: "tool_use"` content blocks
- Tool result sent as `role: "user"` with `type: "tool_result"` content blocks
- Converted to OpenAI format (`role: "tool"`, `tool_calls`) when using OpenAI providers

**Key detail:** Only uses `"user"` and `"assistant"` roles internally. Provider-specific converters handle the translation.

### 2. Aider (Python, Terminal)

**Approach:** TEXT-ONLY — No native tool calling used in production

- LLM returns SEARCH/REPLACE blocks as plain text in `content`
- No `tool` role messages ever sent
- Strict `user`/`assistant` alternation
- Errors fed back as next `user` message (reflection loop, max 3 retries)
- Deprecated function-calling coders exist but raise `RuntimeError("Deprecated")`

**Key insight:** Aider avoids native tool calling entirely for maximum model compatibility.

### 3. OpenHands (Python, Full Agent Platform)

**Approach:** HYBRID — Native function calling + XML-like fallback

**Native mode (default):**
- Assistant: `role: "assistant"`, `content` (thought), `tool_calls` array
- Tool result: `role: "tool"`, `content`, `tool_call_id`, `name`

**Non-native mode (`mock_function_calling`):**
- Assistant: `role: "assistant"`, text contains `<function=tool_name><parameter=arg>value</parameter></function>`
- Tool result: `role: "user"`, text prefixed with `EXECUTION RESULT of [tool_name]:`
- Transparent conversion in LLM wrapper — agent code is format-agnostic

### 4. SWE-agent (Python, Research Agent)

**Approach:** HYBRID — Multiple parsers selectable via config

**Native function calling mode:**
- Assistant: standard `tool_calls` array
- Tool result: `role: "tool"` with `tool_call_id`

**Text-based modes (3+ variants):**
- `ThoughtActionParser`: markdown code blocks
- `XMLThoughtActionParser`: XML tags
- `XMLFunctionCallingParser`: `<function=name><parameter=arg>value</parameter></function>`
- Tool results always: `role: "user"`, content: `"Observation: {result}"`

**Key insight:** Enforces exactly ONE tool call per turn (rejects multiple parallel calls).

### 5. Goose (Rust, Block's Agent)

**Approach:** NATIVE API — Provider-specific serialization from unified internal model

**Internal model:**
- `ToolRequest` in `Assistant` message (contains `CallToolRequestParams`)
- `ToolResponse` in `User` message (contains `CallToolResult`)

**Anthropic serialization:**
- Tool call → `type: "tool_use"` content block
- Tool result → `type: "tool_result"` in user message

**OpenAI serialization:**
- Tool call → `tool_calls` array on assistant message
- Tool result → `role: "tool"` message with `tool_call_id`

**Key detail:** Each tool call is split into its own assistant+user message pair to ensure clean alternation.

### 6. Codex CLI (Rust, OpenAI)

**Approach:** NATIVE API — OpenAI Responses API only

- Tool call: `type: "function_call"` standalone item in flat `input` array
- Tool result: `type: "function_call_output"` standalone item linked by `call_id`
- No role-based messages for tool items — everything is a typed item in a flat list
- Supports multimodal tool results (text + images)

### 7. Continue.dev (TypeScript, IDE Extension)

**Approach:** HYBRID — Native API + markdown code block fallback

**Native mode (for supported models):**
- Internal: `role: "assistant"` with `toolCalls` array; `role: "tool"` with `toolCallId`
- Converted to Anthropic format (`tool_use`/`tool_result`) or OpenAI format as needed

**System message tools mode (for unsupported models):**
- Tools defined in system message as markdown
- Assistant outputs: ````tool\nTOOL_NAME: read_file\nBEGIN_ARG: filepath\n/src/main.ts\nEND_ARG\n````
- Tool result: `role: "user"`, text: `"Tool output for read_file tool call:\n\n{result}"`
- Intercepted from stream and converted to proper tool call objects internally

---

## Comparison Matrix

| Agent | Primary Mode | Fallback Mode | Tool Call Role | Tool Result Role | XML Tags? | ID Linking? |
|-------|-------------|---------------|----------------|-----------------|-----------|-------------|
| **Cline** | Native API | XML in text | assistant | user | Yes (legacy) | Yes (native) |
| **Aider** | Text only | — | assistant | user | No | No |
| **OpenHands** | Native API | `<function=>` XML | assistant | tool / user | Yes (fallback) | Yes (native) |
| **SWE-agent** | Configurable | Multiple parsers | assistant | tool / user | Yes (option) | Yes (native) |
| **Goose** | Native API | — | assistant | user (Anthropic) / tool (OpenAI) | No | Yes |
| **Codex CLI** | Responses API | — | (flat item) | (flat item) | No | Yes (call_id) |
| **Continue** | Native API | Markdown codeblocks | assistant / tool | user / tool | No | Yes (native) |

---

## Key Architectural Patterns

### Pattern 1: Unified Internal Format + Provider Adapters (RECOMMENDED)
**Used by:** Cline, Goose, Continue.dev

Store messages in one canonical format internally (typically Anthropic's format since it's the most structured), then convert to provider-specific format at the API boundary.

```
Internal Model → Anthropic Adapter → Anthropic API
              → OpenAI Adapter   → OpenAI API
              → Ollama Adapter   → Ollama API
```

### Pattern 2: Transparent Mock Function Calling
**Used by:** OpenHands, SWE-agent

When the model doesn't support native tools, inject tool definitions into the system prompt as text, parse tool calls from the assistant's text output using regex/XML parsing, and convert back to the internal tool call format. The agent code never knows the difference.

### Pattern 3: Text-Only (No Native Tools)
**Used by:** Aider

Avoid the tool calling API entirely. Use prompt engineering to get structured output (SEARCH/REPLACE blocks) and parse it client-side. Maximum compatibility at the cost of reliability.

---

## Answer to the Original Question: Is `<tools></tools>` the correct approach?

### Short Answer: No — use native API tool calling as the primary method.

### Detailed Answer:

**Using XML tags like `<tools></tools>` is a text-based/legacy approach** that every modern agent treats as a fallback, not the primary method. Here's why:

1. **Reliability**: Native API tool calling has structured validation, guaranteed JSON parsing, and provider-side schema enforcement. XML in text is fragile — the LLM can produce malformed XML, miss closing tags, or embed tool calls in unexpected positions.

2. **Streaming**: Native tool calls stream as structured deltas (`tool_use` blocks with `input` accumulated). XML must be accumulated as text and post-processed.

3. **Parallel calls**: Native APIs support multiple `tool_use` blocks in one response with distinct IDs. XML parsing must handle multiple tags and match results correctly — error-prone.

4. **Provider compatibility**: Different providers serialize tools differently. Native APIs handle this. XML requires manual parsing for every format variation.

### Recommended Architecture for Your Plugin:

```kotlin
// 1. Internal canonical format (provider-agnostic)
sealed class AgentContent {
    data class Text(val text: String) : AgentContent()
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : AgentContent()
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : AgentContent()
}

data class AgentMessage(
    val role: Role,  // USER or ASSISTANT only
    val content: List<AgentContent>
)

// 2. Assistant tool call message
AgentMessage(
    role = ASSISTANT,
    content = listOf(
        AgentContent.Text("I'll check the build status."),
        AgentContent.ToolUse(id = "tool_1", name = "bamboo_builds.get_build", input = jsonOf("buildKey" to "PROJ-123"))
    )
)

// 3. Tool result message
AgentMessage(
    role = USER,  // Always "user" role for Anthropic
    content = listOf(
        AgentContent.ToolResult(toolUseId = "tool_1", content = """{"status": "SUCCESS", ...}""")
    )
)

// 4. Provider adapters serialize to wire format
interface ProviderAdapter {
    fun serializeMessages(messages: List<AgentMessage>): JsonArray
    fun parseResponse(response: JsonObject): AgentMessage
}
```

### When XML Text-Based IS Appropriate:

- **Fallback for models without native tool support** (local LLMs, older API versions)
- **Sourcegraph Cody** (your integration uses text-based chat, not structured tool calling)
- **Simple single-tool scenarios** where reliability isn't critical

Even in these cases, parse the XML into your internal structured format immediately — don't pass raw XML through the system.
