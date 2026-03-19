# Tool Response Format Research

**Date:** 2026-03-19
**Purpose:** Determine the optimal format for AI agent tool responses that serves both LLM consumption and UI rendering.

---

## 1. Anthropic/Claude Tool Result Format

### The API Format

Claude's Messages API accepts tool results in a `tool_result` content block with three possible content shapes:

**Simple string:**
```json
{
  "type": "tool_result",
  "tool_use_id": "toolu_01A09q90qw90lq917835lq9",
  "content": "15 degrees"
}
```

**Array of typed content blocks (text, image, document):**
```json
{
  "type": "tool_result",
  "tool_use_id": "toolu_01A09q90qw90lq917835lq9",
  "content": [
    { "type": "text", "text": "Current weather: 72F, partly cloudy" },
    { "type": "image", "source": { "type": "base64", "media_type": "image/png", "data": "..." } }
  ]
}
```

**Error result:**
```json
{
  "type": "tool_result",
  "tool_use_id": "toolu_01A09q90qw90lq917835lq9",
  "content": "File not found: /src/main.kt",
  "is_error": true
}
```

### How Claude Code's Built-in Tools Return Data

Claude Code's six core tools (Read, Write, Edit, Bash, Glob, Grep) return **plain text**, not JSON:

| Tool | Return Format | Example |
|------|---------------|---------|
| Read | Line-numbered text (`cat -n` style) | `  1\tfun main() {` |
| Bash | Raw stdout/stderr (truncated at 30K chars) | Command output as-is |
| Glob | Newline-separated file paths, sorted by mtime | `/src/Main.kt\n/src/App.kt` |
| Grep (content) | Ripgrep output with optional line numbers | `src/Main.kt:15:val x = 1` |
| Grep (files) | Newline-separated file paths | `/src/Main.kt` |
| Edit | Success/failure status | Implicit from lack of error |
| Write | Success/failure status | Implicit from lack of error |

**Key insight:** Claude Code deliberately uses plain text for tool results, not JSON. The LLM parses line-numbered text, ripgrep output, and raw command output natively. These formats were chosen because they match patterns Claude saw extensively in training data (terminal output, grep results, cat output).

### Anthropic's Official Best Practices (from engineering blog)

From [Writing Tools for Agents](https://www.anthropic.com/engineering/writing-tools-for-agents):

1. **Return only high-signal information.** Prioritize contextual relevance over flexibility. Avoid low-level identifiers (UUIDs, MIME types) unless the agent needs them for downstream tool calls.

2. **Tool response structure (XML, JSON, Markdown) impacts performance.** LLMs perform better with formats matching their training data. The optimal format varies by task. **Test with evaluations.**

3. **Expose a `response_format` parameter.** Let the agent choose `"concise"` vs `"detailed"`:
   - Detailed Slack response: 206 tokens (includes thread_ts, IDs, metadata)
   - Concise Slack response: 72 tokens (content only)
   - **Token reduction: ~66%** by letting the agent pick the format

4. **Implement pagination, filtering, truncation.** Claude Code caps at 25K tokens per response. When truncating, include instructions like "Use filters for targeted searches" to steer agent behavior.

5. **Aggregate before returning.** One example reduced 200KB of raw expense data to 1KB of aggregated results: `[{"name": "Alice", "spent": 12500, "limit": 10000}]`

6. **Document return formats explicitly.** When Claude writes code to parse outputs, it needs to know: field names, types, enum values, nesting structure.

**Source:** https://www.anthropic.com/engineering/writing-tools-for-agents, https://www.anthropic.com/engineering/advanced-tool-use

---

## 2. MCP (Model Context Protocol) Tool Response Format

### The Specification (November 2025)

MCP's `CallToolResult` supports two response modes:

**Unstructured content** (the `content` array):
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      { "type": "text", "text": "Current weather in New York:\nTemperature: 72F\nConditions: Partly cloudy" }
    ],
    "isError": false
  }
}
```

**Structured content** (the `structuredContent` field, new in Nov 2025 spec):
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": {
    "content": [
      { "type": "text", "text": "{\"temperature\": 22.5, \"conditions\": \"Partly cloudy\", \"humidity\": 65}" }
    ],
    "structuredContent": {
      "temperature": 22.5,
      "conditions": "Partly cloudy",
      "humidity": 65
    }
  }
}
```

### Content Types Supported

| Type | Purpose |
|------|---------|
| `text` | Plain text or serialized JSON |
| `image` | Base64-encoded image with mimeType |
| `audio` | Base64-encoded audio with mimeType |
| `resource_link` | URI reference to a fetchable resource |
| `resource` | Embedded resource with content inline |

### The Dual-Content Pattern

MCP's `structuredContent` + `content` pattern is the most significant finding:

- **`structuredContent`** — Machine-parseable JSON object, validated against an `outputSchema`
- **`content`** — Human/LLM-readable text representation (required for backward compatibility)

When a tool defines an `outputSchema`, the server MUST return conforming `structuredContent`. The `content` array SHOULD also contain a serialized JSON text block for clients that don't support structured content.

**This is the canonical dual-format pattern for our architecture.**

### Annotations for Audience Routing

Each content block supports annotations:
```json
{
  "type": "text",
  "text": "Build passed in 3m 22s",
  "annotations": {
    "audience": ["user"],
    "priority": 0.9
  }
}
```

`audience` can be `["user"]`, `["assistant"]`, or `["user", "assistant"]` — enabling content to be routed to the UI only, the LLM only, or both.

**Source:** https://modelcontextprotocol.io/specification/2025-11-25/server/tools

---

## 3. OpenAI Function Calling Tool Response Format

### The Format

OpenAI tool results are plain strings:

**Chat Completions API:**
```json
{
  "role": "tool",
  "tool_call_id": "call_12345xyz",
  "content": "result_string_here"
}
```

**Responses API:**
```json
{
  "type": "function_call_output",
  "call_id": "call_12345xyz",
  "output": "result_string_here"
}
```

### Key Design Decision

OpenAI's documentation states: "the result you pass should typically be a string, where the format is up to you (JSON, error codes, plain text, etc.)." The model interprets the string accordingly.

There is no structured/typed content system. Everything is a string. If you want to return JSON, you serialize it into the `content`/`output` string field.

**Implications:**
- Simpler API surface than Anthropic/MCP
- No type system for responses (no image/audio blocks in results)
- JSON-in-a-string is the de facto pattern for structured data
- No built-in audience routing or annotations

**Source:** https://developers.openai.com/api/docs/guides/function-calling

---

## 4. IDE Agent Tool Response Formats

### Cursor

Cursor uses **XML-style tags** for tool communication, with a mix of markdown and XML:

```
<tool>... full contents of index.py ...</tool>
<assistant>Based on the file contents...</assistant>
```

For file edits, Cursor uses a "semantic diff" format — only changed lines plus language-specific comments indicating where unchanged code exists. This optimizes token usage (sending diffs not full files).

Tools available: `codebase_search`, `read_file`, `run_terminal_cmd`, `list_dir`, `grep_search`, `edit_file`, `file_search`, `delete_file`, `reapply`, `fetch_rules`, `diff_history`.

**Source:** https://blog.sshh.io/p/how-cursor-ai-ide-works, https://roman.pt/posts/cursor-under-the-hood/

### Cline

Cline uses XML for tool invocation and results:
```xml
<read_file>
  <path>src/main.js</path>
</read_file>
```

Results are returned as plain text within the conversation context. Cline's system prompt (~11K chars) uses a two-mode system: Plan Mode (strategizing) and Act Mode (execution).

**Source:** https://github.com/cline/cline

### GitHub Copilot

Copilot reduced from 40 tools to 13 core tools using embedding-guided routing. Tool results are used for iterative refinement — if a code edit produces lint errors, results feed back for self-correction. Specific response formatting details are not publicly documented.

**Source:** https://github.blog/ai-and-ml/github-copilot/how-were-making-github-copilot-smarter-with-fewer-tools/

### Vercel AI SDK — The `toModelOutput` Pattern

The most architecturally relevant pattern from IDE tooling. Vercel AI SDK separates:

- **`execute()` result** — The full, rich tool output (used by application code / UI)
- **`toModelOutput()` result** — A transformed, model-optimized representation

```typescript
const screenshotTool = {
  execute: async ({ action, coordinate, text }) => {
    // Full result for application/UI consumption
    return { type: 'image', data: base64String, metadata: { width: 1920, height: 1080 } };
  },
  toModelOutput: ({ output }) => {
    // Stripped-down result for model context
    return {
      type: 'content',
      value: [{ type: 'media', data: output.data, mediaType: 'image/png' }]
    };
  }
};
```

**Why this matters:** When tools return large text (file contents, search results) or binary data (screenshots), you waste tokens sending the full result to the model. `toModelOutput` lets you strip metadata, truncate, or transform for the LLM while keeping the rich result for the UI.

**Source:** https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling

---

## 5. Token Efficiency Research

### JSON vs Plain Text vs TOON

**TOON (Token-Oriented Object Notation)** is a 2025 format combining YAML (for nested objects) and CSV (for tabular data) to reduce LLM token consumption.

| Format | Tokens (500-row dataset) | Accuracy (data retrieval) |
|--------|--------------------------|---------------------------|
| JSON (pretty-printed) | 11,842 | 69.7% |
| JSON (minified) | 4,617 | ~70% |
| TOON | ~4,700 | 73.9% |
| YAML (deeply nested) | Varies | Similar to TOON |

**Key benchmarks (from academic paper arXiv:2603.03306):**
- TOON: 73.9% accuracy vs JSON: 69.7% across 209 retrieval questions, 4 models
- RAG benchmarks: TOON 70.1% vs JSON 65.4%
- Token efficiency: TOON 27.7 accuracy-per-1K-tokens vs JSON compact 23.7 vs JSON standard 16.4
- GPT 5 Nano: TOON achieves 99.4% accuracy with 46% fewer tokens

**Caveats:**
- TOON benchmarks are self-reported, not independently validated
- TOON excels at uniform/tabular data; degrades with deeply nested or non-uniform structures
- For agent tool results (which are typically small, heterogeneous objects), the savings are minimal
- The "prompt tax" of instructional overhead reduces savings in shorter contexts

### Practical Implications for Tool Results

Tool results are typically small (10-500 tokens), not tabular datasets. For these:
- **Pretty-printed JSON** wastes ~2.5x tokens vs minified
- **Minified JSON** and **plain text** are roughly equivalent in token count
- **Key-value plain text** (`Temperature: 72F\nConditions: Partly cloudy`) is marginally more efficient than `{"temperature": "72F", "conditions": "Partly cloudy"}` due to no braces/quotes/commas
- **The format matching training data matters more than raw token count** for comprehension accuracy

**Sources:**
- https://arxiv.org/abs/2603.03306
- https://dev.to/akari_iku/is-json-outdated-the-reasons-why-the-new-llm-era-format-toon-saves-tokens-31e5
- https://www.tensorlake.ai/blog-posts/toon-vs-json

---

## 6. The "Backend API" Pattern for AI Tools

### WunderGraph Supergraph Pattern

One unified API (GraphQL supergraph) serves both traditional UI clients and LLM agents via MCP:

```
[Web App]  ─┐
[Mobile]   ─┤── Supergraph ──> [Microservices]
[LLM Agent]─┘     (one schema)
```

Both UIs and agents query the same schema-validated responses. The LLM writes GraphQL queries against the schema; the UI uses typed SDK clients.

**Source:** https://wundergraph.com/blog/backend-framework-for-llms

### Backend-for-Frontend Extended to AI

The traditional BFF pattern creates per-client adapters. The AI extension adds an "LLM-facing BFF" that:
1. Exposes only safe, relevant functions to the LLM
2. Shapes responses for model consumption (concise, filtered)
3. Uses the same underlying service layer as the UI-facing BFF

**Source:** https://johnnyreilly.com/large-language-models-view-models-backend-for-frontend

### Dynamic UI Generation Pattern

The LLM generates a JSON specification describing desired UI components, which the client renders:
```json
{
  "component": "WeatherCard",
  "props": { "temperature": 72, "conditions": "Partly cloudy", "location": "New York" }
}
```

This inverts the typical pattern: instead of the tool returning data that both LLM and UI consume, the LLM generates the UI spec as structured output.

**Source:** https://blog.fka.dev/blog/2025-05-16-beyond-text-only-ai-on-demand-ui-generation-for-better-conversational-experiences/

---

## 7. Synthesis: Patterns Across All Systems

### What Every System Has in Common

1. **Tool results are ultimately strings or text for the LLM.** Even MCP's `structuredContent` includes a text serialization. OpenAI is string-only. Claude accepts strings or text blocks. The LLM always processes text.

2. **Structured data travels as JSON.** When tools need to return structured data, every system serializes to JSON. No system uses XML, YAML, or custom formats for structured tool output.

3. **Error handling is a first-class concern.** Anthropic: `is_error: true`. MCP: `isError: true`. OpenAI: error in content string. All systems distinguish tool execution errors from protocol errors.

4. **Token efficiency comes from filtering, not format.** Returning 1KB of aggregated results instead of 200KB of raw data saves 99.5% of tokens. Switching JSON to TOON saves 40%. The filtering strategy dominates.

### Where Systems Diverge

| Concern | Anthropic/Claude | MCP | OpenAI | IDE Agents |
|---------|-----------------|-----|--------|------------|
| Content typing | text, image, document blocks | text, image, audio, resource, resource_link | Untyped string | Plain text |
| Structured output | No (string or typed blocks) | `structuredContent` + `outputSchema` | No | No |
| Audience routing | No | `annotations.audience` | No | No |
| Dual format | No | Yes (`content` + `structuredContent`) | No | Vercel's `toModelOutput` |
| Error field | `is_error` | `isError` | In content string | In content string |

---

## 8. Recommendation for Our Architecture

### The Hybrid Pattern: Structured JSON + LLM-Optimized Text

Based on all research, the optimal pattern for our agentic AI tools is:

```kotlin
data class ToolResult<T>(
    // Machine-parseable structured data — consumed by UI and programmatic code
    val data: T,

    // LLM-optimized text summary — consumed by the agent
    val summary: String,

    // Whether this result is an error
    val isError: Boolean = false,

    // Optional: hint to the agent about what to do next
    val hint: String? = null
)
```

### Why This Pattern

1. **`data: T`** — Typed, schema-validated JSON. The UI deserializes this directly into view models. Programmatic tool chaining uses this for downstream calls. This is the MCP `structuredContent` equivalent.

2. **`summary: String`** — Plain text optimized for LLM comprehension. This is what goes into the agent's context window. It can be concise ("Build PROJ-123 passed, 3m22s, 100% coverage") or detailed, controlled by the agent. This is the MCP `content` equivalent.

3. **`isError`** — First-class error signaling (matching Anthropic and MCP patterns).

4. **`hint`** — Steers agent behavior when results are truncated or need follow-up ("Use grep_search to find specific usages" or "Run automation suite next").

### Concrete Example

```kotlin
// Tool: get_build_status
ToolResult(
    data = BuildStatus(
        planKey = "PROJ-MAIN",
        buildNumber = 456,
        state = BuildState.SUCCESSFUL,
        duration = Duration.ofSeconds(202),
        stages = listOf(
            Stage("Compile", StageState.SUCCESSFUL, Duration.ofSeconds(45)),
            Stage("Test", StageState.SUCCESSFUL, Duration.ofSeconds(120)),
            Stage("Package", StageState.SUCCESSFUL, Duration.ofSeconds(37))
        ),
        testResults = TestResults(passed = 342, failed = 0, skipped = 2),
        coveragePercent = 98.5
    ),
    summary = """Build PROJ-MAIN-456: SUCCESSFUL (3m22s)
Stages: Compile (45s) -> Test (2m00s) -> Package (37s)
Tests: 342 passed, 0 failed, 2 skipped
Coverage: 98.5%""",
    hint = "Coverage is above 95% threshold. Ready for automation suite."
)
```

### Token Budget

The `summary` for this example is ~45 tokens. The full JSON `data` would be ~120 tokens. By sending only the summary to the LLM context, we save ~63% of tokens per tool call while keeping the full data available for UI rendering and programmatic access.

### Serialization Strategy

```kotlin
// For LLM context: send summary only
fun <T> ToolResult<T>.toLlmContent(): String {
    val parts = mutableListOf(summary)
    if (isError) parts.add(0, "[ERROR]")
    if (hint != null) parts.add("Hint: $hint")
    return parts.joinToString("\n")
}

// For UI rendering: send data as typed JSON
fun <T> ToolResult<T>.toUiJson(): String {
    return Json.encodeToString(serializer, data)
}

// For MCP compatibility: send both
fun <T> ToolResult<T>.toMcpResult(): McpCallToolResult {
    return McpCallToolResult(
        content = listOf(TextContent(text = toLlmContent())),
        structuredContent = Json.encodeToJsonElement(serializer, data),
        isError = isError
    )
}
```

### Decision Matrix: When to Use What

| Scenario | Format | Rationale |
|----------|--------|-----------|
| Agent reasoning about next step | `summary` (plain text) | Minimal tokens, matches training data |
| UI rendering a dashboard panel | `data` (typed JSON) | Type-safe deserialization, no parsing |
| Tool chaining (output of A -> input of B) | `data` (typed JSON) | Programmatic access to fields |
| Error recovery by agent | `summary` + `isError` + `hint` | Agent needs context + guidance |
| Logging/debugging | Full `ToolResult` serialized | Complete audit trail |
| MCP interop | `content` + `structuredContent` | Spec compliance |

### Format Comparison (Same Data)

**Plain text summary (45 tokens):**
```
Build PROJ-MAIN-456: SUCCESSFUL (3m22s)
Stages: Compile (45s) -> Test (2m00s) -> Package (37s)
Tests: 342 passed, 0 failed, 2 skipped
Coverage: 98.5%
```

**Minified JSON (120 tokens):**
```json
{"planKey":"PROJ-MAIN","buildNumber":456,"state":"SUCCESSFUL","duration":"PT3M22S","stages":[{"name":"Compile","state":"SUCCESSFUL","duration":"PT45S"},{"name":"Test","state":"SUCCESSFUL","duration":"PT2M"},{"name":"Package","state":"SUCCESSFUL","duration":"PT37S"}],"testResults":{"passed":342,"failed":0,"skipped":2},"coveragePercent":98.5}
```

**Key-value text (65 tokens):**
```
plan_key: PROJ-MAIN
build_number: 456
state: SUCCESSFUL
duration: 3m22s
stages: Compile(45s,OK) Test(2m00s,OK) Package(37s,OK)
tests: 342 passed, 0 failed, 2 skipped
coverage: 98.5%
```

The plain text summary is the most token-efficient AND the most comprehensible to the LLM because it matches natural language patterns from training data.

---

## Sources

### Anthropic/Claude
- [Tool use with Claude - API Docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview)
- [How to implement tool use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use)
- [Writing Tools for Agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
- [Advanced Tool Use](https://www.anthropic.com/engineering/advanced-tool-use)
- [Claude Code internal tools (reverse-engineered)](https://gist.github.com/bgauryy/0cdb9aa337d01ae5bd0c803943aa36bd)

### MCP
- [MCP Specification (Nov 2025) - Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [MCP Specification - Overview](https://modelcontextprotocol.io/specification/2025-11-25)

### OpenAI
- [Function calling guide](https://developers.openai.com/api/docs/guides/function-calling)
- [Structured outputs](https://platform.openai.com/docs/guides/structured-outputs)

### IDE Agents
- [How Cursor AI IDE Works](https://blog.sshh.io/p/how-cursor-ai-ide-works)
- [Cursor Under the Hood](https://roman.pt/posts/cursor-under-the-hood/)
- [Cline GitHub](https://github.com/cline/cline)
- [Copilot: Smarter with Fewer Tools](https://github.blog/ai-and-ml/github-copilot/how-were-making-github-copilot-smarter-with-fewer-tools/)
- [Vercel AI SDK - Tools and Tool Calling](https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling)

### Token Efficiency
- [TOON vs JSON: Academic Benchmark (arXiv:2603.03306)](https://arxiv.org/abs/2603.03306)
- [TOON vs JSON: Token-Optimized Data Format](https://www.tensorlake.ai/blog-posts/toon-vs-json)
- [TOON GitHub](https://github.com/toon-format/toon)

### Architecture Patterns
- [WunderGraph: Backend Framework for LLMs](https://wundergraph.com/blog/backend-framework-for-llms)
- [BFF Pattern for LLMs](https://johnnyreilly.com/large-language-models-view-models-backend-for-frontend)
- [Beyond Text-Only AI: On-Demand UI Generation](https://blog.fka.dev/blog/2025-05-16-beyond-text-only-ai-on-demand-ui-generation-for-better-conversational-experiences/)
