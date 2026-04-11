# Streaming Re-enablement & XML Tool Call Port

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-enable real SSE streaming to fix the timeout bug on long conversations, then port Cline's XML tool call format to fix broken parallel tool calls.

**Architecture:** Two independent phases. Phase 1 changes one method in `OpenAiCompatBrain` to delegate to the existing `sendMessageStream()` and raises the read timeout. Phase 2 adds an XML tool call parser that post-processes text content (both streamed and non-streamed) to extract `<tool>` tags, converts them to `ToolCall` objects, and returns them through the existing `ChatCompletionResponse` — so `AgentLoop` and `SingleAgentSession` don't change at all. XML parsing is wired into both `sendMessageStream()` and `sendMessage()` to cover the zero-delta fallback path. The XML tool definitions are injected into the system prompt by a new builder (`SystemPrompt.build()` Section 6), and `tools: [...]` is dropped from the request body. A `useXmlToolMode` setting in PluginSettings provides a rollback path.

**Tech Stack:** Kotlin, OkHttp SSE, kotlinx.serialization, JUnit 5, MockK

**Branch:** `feature/streaming-xml-port` in worktree `.worktrees/streaming-xml-port`

---

## Background: Why This Work Is Needed

The AI agent has two critical bugs caused by the Sourcegraph LLM gateway's behavior:

### Bug 1: Read timeouts on long conversations

`OpenAiCompatBrain.chatStream()` at `core/.../OpenAiCompatBrain.kt:56-82` does NOT actually stream. It calls the non-streaming `sendMessage()` and fakes a single SSE chunk for the UI. As conversation context grows over multiple turns, the LLM takes longer to generate a complete response. Eventually the 120s OkHttp read timeout fires. Users work around this by opening a new conversation (resetting context), but this loses all session state.

The real streaming path (`SourcegraphChatClient.sendMessageStream()` at lines 273-461) is fully implemented but never called. It was disabled because of two bugs that have since been mitigated by code written after the disable.

### Bug 2: Parallel tool calls silently lost

The Sourcegraph gateway has a **concatenated JSON bug** in native function calling. When the model requests multiple parallel tool calls, the gateway merges all tool call JSON objects into a single SSE delta at index 0: `{"path":"A.kt"}{"path":"B.kt"}`. The existing workaround at `SourcegraphChatClient.kt:390` splits on `}{` but only recovers the **first** tool call — all others are silently dropped.

This means if the agent requests 3 parallel `read_file` calls, only 1 executes. Research scope (up to 5 concurrent sub-agent tools), multi-file exploration, and any parallel operation are silently degraded.

---

## Lab Findings (Key Numbers)

A diagnostic Python script (`tools/sourcegraph-probe/streaming_lab.py`) probed the gateway with 25 scenarios x 3 modes + 20 probes. Results were analyzed on 2026-04-10. The raw results JSON is on the `main` branch at `docs/Result_1/streaming_lab_results.json` (not in this worktree).

### Streaming viability

| Metric | Value | Implication |
|---|---|---|
| TTFB ratio (streaming vs non-streaming) | **0.23** | Streaming gets first byte at 23% of non-streaming total time — prevents 120s timeout |
| Pre-generation silence (8K prompt) | **1764ms** | Well under timeout, but production prompts are 50-150K tokens — silence at that scale is unknown |
| SSE keepalive | **NONE** | Gateway sends no keepalive bytes during model thinking |
| `[DONE]` sentinel | **NEVER SENT** | Must terminate on socket close, not `[DONE]` |
| Usage chunk | **ALWAYS EMITTED** (5/5 runs) | Token tracking works in streaming mode |
| Max inter-chunk gap (500-line output) | **1213ms** | Well under 120s — no mid-stream timeout risk |
| Streaming reliability | **5/5 functionally complete** | Labeled "FLAKY" only because `[DONE]` absent — content and usage always arrived |

### Tool call modes

| Mode | Description | Pass Rate | Parallel Works? |
|---|---|---|---|
| A (Native function calling) | `tools: [...]` + `delta.tool_calls` | **17/25** | **NO** — concat JSON bug at all counts (2,3,4,5) |
| B (XML tags in text) | Tool defs in system prompt, `<tool>` in response | **22/25** | **YES** — all counts work |
| C (JSON in code block) | Fenced JSON | **15/25** | Yes but worse overall |

### Gateway constraints (confirmed)

| Feature | Status | Impact on Implementation |
|---|---|---|
| `role: tool` | **HTTP 400** | Already handled — `sanitizeMessages()` converts to user msg with `"TOOL RESULT:\n"` prefix |
| `role: system` in messages | **HTTP 400** | Already handled — converted to `<system_instructions>` in user msg |
| Anthropic `tool_result` content blocks | **HTTP 500** | Do NOT use Cline's exact Anthropic format for tool results |
| `tool_choice` | **Silently ignored** | Don't rely on it |
| `[DONE]` SSE sentinel | **Never sent** | Terminate on socket close (existing code already does this) |
| `stream_options.include_usage` | **Works** (usage emitted even without) | Request it explicitly as defensive measure |
| Native parallel tool calls | **BROKEN** (concat JSON bug) | Can NOT be fixed client-side — this is why we need XML mode |
| `cache_control: ephemeral` | **Silently ignored** (cached_tokens always 0) | Don't add prompt caching logic |
| Thinking/reasoning params | **All 8 strategies silently dropped** | Don't add thinking model support |
| Vision/image input | **Silently stripped** | Don't build image features |
| `max_tokens` up to 64K+ | **Works** | No hard cap despite spec claiming 8K |

---

## Do NOT Do These Things

These look reasonable but are confirmed broken by the lab:

1. **Do NOT use Anthropic `tool_result` content blocks** (`{"type": "tool_result", "tool_use_id": "..."}` in user messages) — returns HTTP 500. Keep the existing `"TOOL RESULT:\n..."` plain text format.
2. **Do NOT try to fix native parallel tool calls** — the concatenated JSON bug is in the Sourcegraph gateway, not our client code. The `}{` splitting workaround is the best we can do with native mode. XML mode sidesteps the problem entirely.
3. **Do NOT remove the `ToolCallBuilder` / `}{` workaround** — the `useXmlToolMode` settings toggle means the native path must still work as a fallback.
4. **Do NOT add `[DONE]` handling logic** — the gateway never sends it. The existing code correctly falls through to `readLine() == null` (socket close).
5. **Do NOT add `role: tool` messages** — the gateway rejects them. `sanitizeMessages()` already converts them to user messages, and this conversion must stay.

---

## Phase 1: Re-enable Streaming

### Task 1: Re-enable real streaming in `OpenAiCompatBrain.chatStream()`

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt:56-82`

- [ ] **Step 1: Replace fake streaming with real streaming delegation**

Replace the entire `chatStream()` body. The existing `sendMessageStream()` already handles SSE parsing, tool call delta accumulation, usage capture, `[DONE]` absence, and the `finish_reason=tool_calls` zero-delta fallback.

```kotlin
override suspend fun chatStream(
    messages: List<ChatMessage>,
    tools: List<ToolDefinition>?,
    maxTokens: Int?,
    onChunk: suspend (StreamChunk) -> Unit
): ApiResult<ChatCompletionResponse> {
    return client.sendMessageStream(
        messages = messages,
        tools = tools,
        maxTokens = maxTokens,
        onChunk = onChunk
    )
}
```

- [ ] **Step 2: Build and verify compilation**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt
git commit -m "feat(streaming): re-enable real SSE streaming in OpenAiCompatBrain

Streaming was disabled due to tool call delta drops and missing usage.
Lab results (docs/Result_1/) confirm both issues are already mitigated:
- Usage is always emitted (3/3 reliability runs)
- Zero-delta fallback already exists at SourcegraphChatClient:372
- TTFB ratio 0.23 prevents the 120s read timeout on long conversations
- [DONE] sentinel is never sent but code handles via socket close"
```

---

### Task 2: Increase read timeout for no-keepalive safety

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt:27`

The lab confirmed NO_KEEPALIVE from the gateway. While pre-generation silence was only 1.8s at 8K chars, production prompts are 50-150K tokens. Increasing the default from 120s to 180s adds a safety margin for the first byte.

- [ ] **Step 1: Change default readTimeoutSeconds from 120 to 180**

```kotlin
class OpenAiCompatBrain(
    sourcegraphUrl: String,
    tokenProvider: () -> String?,
    private val model: String,
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 180,  // was 120 — increased for NO_KEEPALIVE safety on large prompts
    httpClientOverride: OkHttpClient? = null
) : LlmBrain {
```

- [ ] **Step 2: Build and verify**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt
git commit -m "feat(streaming): increase default read timeout to 180s for no-keepalive safety

Lab confirmed the Sourcegraph gateway sends NO keepalive bytes during
model thinking. With 50-150K token prompts, pre-generation silence
could exceed 120s. The 180s default adds safety margin while streaming
keeps the timeout resetting on every subsequent chunk."
```

---

### Task 3: Add `stream_options.include_usage` to streaming request

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/ChatCompletionModels.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt:282-290`

Lab showed usage IS emitted even without `include_usage`, but explicitly requesting it is defensive and follows OpenAI spec.

- [ ] **Step 1: Add `StreamOptions` DTO and `stream_options` field to request**

Add to `ChatCompletionModels.kt` after the `ChatCompletionRequest` class:

```kotlin
@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true
)
```

Add `streamOptions` field to `ChatCompletionRequest`:

```kotlin
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val temperature: Double = 0.0,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null
)
```

- [ ] **Step 2: Set `streamOptions` when streaming**

In `SourcegraphChatClient.sendMessageStream()`, update the request construction at line ~282:

```kotlin
val request = ChatCompletionRequest(
    model = model,
    messages = sanitized,
    tools = tools?.takeIf { it.isNotEmpty() },
    toolChoice = null,
    temperature = temperature,
    maxTokens = maxTokens,
    stream = true,
    streamOptions = StreamOptions(includeUsage = true)
)
```

- [ ] **Step 3: Build and verify**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/ChatCompletionModels.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt
git commit -m "feat(streaming): request stream_options.include_usage explicitly

Lab confirmed usage is emitted even without this flag, but requesting
it explicitly follows the OpenAI spec and is defensive."
```

---

### Task 4: Write streaming tests

**Files:**
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClientStreamTest.kt`

- [ ] **Step 1: Write test for SSE parsing with tool call deltas**

```kotlin
package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for SourcegraphChatClient.sendMessageStream() — SSE parsing,
 * tool call assembly, usage capture, and edge cases confirmed by
 * streaming lab results (docs/Result_1/).
 */
class SourcegraphChatClientStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SourcegraphChatClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client = SourcegraphChatClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "test-token" },
            model = "test-model",
            httpClientOverride = OkHttpClient.Builder().build()
        )
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun sseResponse(vararg events: String): MockResponse {
        val body = events.joinToString("\n\n") { "data: $it" } + "\n\n"
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    @Test
    fun `streams text content and captures usage without DONE sentinel`() = runTest {
        // Lab confirmed: gateway NEVER sends [DONE], usage always emitted
        val chunk1 = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello "},"finish_reason":null}]}"""
        val chunk2 = """{"id":"c1","choices":[{"index":0,"delta":{"content":"world"},"finish_reason":"stop"}]}"""
        val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""

        server.enqueue(sseResponse(chunk1, chunk2, usageChunk))

        val chunks = mutableListOf<StreamChunk>()
        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            tools = null,
            onChunk = { chunks.add(it) }
        )

        assertIs<ApiResult.Success<ChatCompletionResponse>>(result)
        val response = result.data
        assertEquals("Hello world", response.choices.first().message.content)
        assertEquals("stop", response.choices.first().finishReason)
        assertNotNull(response.usage)
        assertEquals(10, response.usage!!.promptTokens)
        assertEquals(5, response.usage!!.completionTokens)
        assertTrue(chunks.size >= 2, "Should have received streaming chunks")
    }

    @Test
    fun `assembles single tool call from deltas`() = runTest {
        val chunk1 = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]},"finish_reason":null}]}"""
        val chunk2 = """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]},"finish_reason":null}]}"""
        val chunk3 = """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"src/Foo.kt\"}"}}]},"finish_reason":"tool_calls"}]}"""
        val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}"""

        server.enqueue(sseResponse(chunk1, chunk2, chunk3, usageChunk))

        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "read the file")),
            tools = null,
            onChunk = {}
        )

        assertIs<ApiResult.Success<ChatCompletionResponse>>(result)
        val response = result.data
        assertEquals("tool_calls", response.choices.first().finishReason)
        val toolCalls = response.choices.first().message.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls.size)
        assertEquals("read_file", toolCalls[0].function.name)
        assertEquals("call_1", toolCalls[0].id)
        assertTrue(toolCalls[0].function.arguments.contains("src/Foo.kt"))
    }

    @Test
    fun `falls back to non-streaming on tool_calls finish with zero deltas`() = runTest {
        // Lab confirmed: gateway sometimes sends finish_reason=tool_calls with no deltas
        // Code falls back to sendMessage() (non-streaming)
        val streamChunk = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"Using tools."},"finish_reason":"tool_calls"}]}"""

        // First request: streaming — gets tool_calls finish but no deltas
        server.enqueue(sseResponse(streamChunk))
        // Second request: non-streaming fallback
        val nonStreamResponse = """{"id":"c2","choices":[{"index":0,"message":{"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"Foo.kt\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":50,"completion_tokens":10,"total_tokens":60}}"""
        server.enqueue(MockResponse().setBody(nonStreamResponse).setHeader("Content-Type", "application/json"))

        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "read")),
            tools = listOf(ToolDefinition(
                type = "function",
                function = FunctionDefinition(name = "read_file", description = "Read a file", parameters = null)
            )),
            onChunk = {}
        )

        assertIs<ApiResult.Success<ChatCompletionResponse>>(result)
        val toolCalls = result.data.choices.first().message.toolCalls
        assertNotNull(toolCalls)
        assertEquals("read_file", toolCalls[0].function.name)
    }

    @Test
    fun `handles concat JSON bug in parallel tool calls — keeps first`() = runTest {
        // Lab confirmed: gateway concatenates parallel tool calls into index 0
        val chunk1 = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]},"finish_reason":null}]}"""
        val chunk2 = """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"A.kt\"}{\"path\":\"B.kt\"}"}}]},"finish_reason":"tool_calls"}]}"""
        val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":50,"completion_tokens":20,"total_tokens":70}}"""

        server.enqueue(sseResponse(chunk1, chunk2, usageChunk))

        val result = client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "read both")),
            tools = null,
            onChunk = {}
        )

        assertIs<ApiResult.Success<ChatCompletionResponse>>(result)
        val toolCalls = result.data.choices.first().message.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls.size) // Only first recovered from concat bug
        assertTrue(toolCalls[0].function.arguments.contains("A.kt"))
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:test --tests "*.SourcegraphChatClientStreamTest" -v`
Expected: 4 tests PASSED

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClientStreamTest.kt
git commit -m "test(streaming): add SSE parsing tests for streaming re-enablement

Tests cover: text streaming without [DONE], single tool call assembly,
zero-delta fallback to non-streaming, and concat JSON bug recovery.
All scenarios confirmed by lab results in docs/Result_1/."
```

---

## Phase 2: XML Tool Call Port

### Task 5: Create the XML tool call parser

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/XmlToolCallParser.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/XmlToolCallParserTest.kt`

This is the core of the Cline port. The parser extracts `<tool>` blocks from streamed text content. It's called after streaming completes (not incrementally — incremental UI preview is a follow-up).

Cline's approach:
- Accumulates full text, re-parses on each chunk
- Uses `lastIndexOf("</tag>")` to avoid false positives from code content like `</div>`
- Marks tool calls as `partial: true` when closing tag is missing (truncation)

Our simplified approach for the MVP:
- Parse accumulated text after streaming completes
- Extract all `<tool>...</tool>` blocks
- Parse `<name>` and `<args>` children
- For `<args>`, extract child elements as key-value pairs
- Return `List<ToolCall>` with synthetic IDs
- Return `partial = true` if unclosed `<tool>` tag found

- [ ] **Step 1: Write failing tests first**

```kotlin
package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ToolCall
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XmlToolCallParserTest {

    @Test
    fun `parses single tool call`() {
        val text = """I'll read that file for you.

<tool>
  <name>read_file</name>
  <args>
    <path>src/main/kotlin/Foo.kt</path>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        assertFalse(result.hasPartial)
        assertEquals("read_file", result.toolCalls[0].function.name)
        assertTrue(result.toolCalls[0].function.arguments.contains("src/main/kotlin/Foo.kt"))
    }

    @Test
    fun `parses parallel tool calls`() {
        val text = """Let me read both files.

<tool>
  <name>read_file</name>
  <args>
    <path>A.kt</path>
  </args>
</tool>

<tool>
  <name>read_file</name>
  <args>
    <path>B.kt</path>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(2, result.toolCalls.size)
        assertFalse(result.hasPartial)
        assertTrue(result.toolCalls[0].function.arguments.contains("A.kt"))
        assertTrue(result.toolCalls[1].function.arguments.contains("B.kt"))
    }

    @Test
    fun `detects partial tool call on truncation`() {
        val text = """<tool>
  <name>search_code</name>
  <args>
    <pattern>suspend fun"""
        // Truncated mid-arg — no closing tags

        val result = XmlToolCallParser.parse(text)

        assertTrue(result.hasPartial)
        assertEquals(0, result.toolCalls.size) // Partial not included in completed
    }

    @Test
    fun `extracts text content before tool calls`() {
        val text = """I found the issue. Let me fix it.

<tool>
  <name>edit_file</name>
  <args>
    <path>Foo.kt</path>
    <content>class Foo { fun bar() = 42 }</content>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals("I found the issue. Let me fix it.", result.textContent.trim())
        assertEquals(1, result.toolCalls.size)
    }

    @Test
    fun `handles content with XML-like strings using lastIndexOf`() {
        // Code containing </div> should not break the parser
        val text = """<tool>
  <name>create_file</name>
  <args>
    <path>index.html</path>
    <content><html><body><div>Hello</div></body></html></content>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        assertFalse(result.hasPartial)
        val args = result.toolCalls[0].function.arguments
        assertTrue(args.contains("</div>"), "Should preserve XML-like content in args")
    }

    @Test
    fun `returns empty for text-only response`() {
        val text = "The answer is 42. No tools needed."

        val result = XmlToolCallParser.parse(text)

        assertEquals(0, result.toolCalls.size)
        assertFalse(result.hasPartial)
        assertEquals(text, result.textContent)
    }

    @Test
    fun `handles multiline arg values`() {
        val text = """<tool>
  <name>edit_file</name>
  <args>
    <path>Foo.kt</path>
    <content>package com.example

class Foo {
    fun bar(): Int {
        return 42
    }
}</content>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        val args = result.toolCalls[0].function.arguments
        assertTrue(args.contains("class Foo"))
        assertTrue(args.contains("return 42"))
    }

    @Test
    fun `does not mis-match path tag inside content block`() {
        // CRITICAL: <path> inside <content> must not confuse the parser.
        // Before the fix, lastIndexOf("</path>") found the one inside content,
        // causing <path> to swallow everything up to and including the code reference.
        val text = """<tool>
  <name>edit_file</name>
  <args>
    <path>src/Foo.kt</path>
    <content>// See <path>other/Bar.kt</path> for details
class Foo {
    fun bar() = 42
}</content>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        val args = result.toolCalls[0].function.arguments
        // path should be ONLY "src/Foo.kt", not swallowing into content
        assertTrue(args.contains("\"path\":\"src/Foo.kt\""), "path should be exact, got: $args")
        // content should contain the full code including the <path> reference
        assertTrue(args.contains("other/Bar.kt"), "content should include the code reference")
        assertTrue(args.contains("class Foo"), "content should include the class")
    }

    @Test
    fun `handles large content block with 200+ lines`() {
        // Matches lab scenario xml_write_large_file — verify no corruption on large payloads
        val lines = (1..250).joinToString("\n") { "    val field$it: String = \"value$it\"" }
        val text = """<tool>
  <name>create_file</name>
  <args>
    <path>src/Generated.kt</path>
    <content>package com.example

class Generated {
$lines
}</content>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        assertFalse(result.hasPartial)
        val args = result.toolCalls[0].function.arguments
        assertTrue(args.contains("field1"))
        assertTrue(args.contains("field250"))
        assertTrue(args.contains("Generated"))
    }

    @Test
    fun `generates unique synthetic IDs`() {
        val text = """<tool>
  <name>read_file</name>
  <args><path>A.kt</path></args>
</tool>
<tool>
  <name>read_file</name>
  <args><path>B.kt</path></args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(2, result.toolCalls.size)
        assertTrue(result.toolCalls[0].id != result.toolCalls[1].id, "IDs should be unique")
        assertTrue(result.toolCalls[0].id.startsWith("xmltool_"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:test --tests "*.XmlToolCallParserTest" -v`
Expected: FAIL — `XmlToolCallParser` does not exist yet

- [ ] **Step 3: Implement XmlToolCallParser**

```kotlin
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
    private val CODE_CARRYING_TAGS = setOf("content", "new_string", "diff", "code")

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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:test --tests "*.XmlToolCallParserTest" -v`
Expected: 10 tests PASSED

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/XmlToolCallParser.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/ai/XmlToolCallParserTest.kt
git commit -m "feat(xml-tools): add XML tool call parser (Cline Mode B port)

Parses <tool><name>...</name><args>...</args></tool> blocks from LLM
text content. Uses lastIndexOf ONLY for code-carrying tags (content,
new_string, diff, code) — all other args use indexOf to prevent
mis-matching when short tag names appear inside code blocks.

Thread-safe via AtomicInteger ID counter for parallel sub-agents.
Detects partial/truncated tool calls when closing tag is missing.
Converts arg child elements to JSON for ToolCall DTO compatibility."
```

---

### Task 6: Create XML tool definition builder

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/XmlToolDefinitionBuilder.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/XmlToolDefinitionBuilderTest.kt`

Converts `List<ToolDefinition>` to an XML string for injection into the system prompt.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class XmlToolDefinitionBuilderTest {

    @Test
    fun `builds XML for simple tool with no params`() {
        val tools = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "think",
                    description = "Pause and reason about the problem",
                    parameters = null
                )
            )
        )

        val xml = XmlToolDefinitionBuilder.build(tools)

        assertContains(xml, "<tool_definitions>")
        assertContains(xml, "<tool_name>think</tool_name>")
        assertContains(xml, "<description>Pause and reason about the problem</description>")
        assertContains(xml, "</tool_definitions>")
    }

    @Test
    fun `builds XML with parameters`() {
        val params = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "The file path to read")
                }
            }
            put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("path")) })
        }

        val tools = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "read_file",
                    description = "Read a file's contents",
                    parameters = params
                )
            )
        )

        val xml = XmlToolDefinitionBuilder.build(tools)

        assertContains(xml, "<tool_name>read_file</tool_name>")
        assertContains(xml, "<parameter name=\"path\" type=\"string\" required=\"true\">")
        assertContains(xml, "The file path to read")
    }

    @Test
    fun `includes usage instruction block`() {
        val tools = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "read_file",
                    description = "Read",
                    parameters = null
                )
            )
        )

        val xml = XmlToolDefinitionBuilder.build(tools)

        assertContains(xml, "<tool>")
        assertContains(xml, "<name>")
        assertContains(xml, "<args>")
        // Should include usage example
        assertContains(xml, "tool_usage_instructions")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:test --tests "*.XmlToolDefinitionBuilderTest" -v`
Expected: FAIL — class doesn't exist

- [ ] **Step 3: Implement XmlToolDefinitionBuilder**

```kotlin
package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts ToolDefinition objects to XML schemas for system prompt injection.
 * Ported from Cline's approach where tools are defined in the system prompt
 * instead of the `tools: [...]` request parameter.
 *
 * Output format:
 * ```
 * <tool_definitions>
 *   <tool>
 *     <tool_name>read_file</tool_name>
 *     <description>Read a file's contents</description>
 *     <parameters>
 *       <parameter name="path" type="string" required="true">
 *         The file path to read
 *       </parameter>
 *     </parameters>
 *   </tool>
 *   ...
 * </tool_definitions>
 *
 * <tool_usage_instructions>
 * To call a tool, output XML in this exact format:
 * <tool>
 *   <name>tool_name</name>
 *   <args>
 *     <param_name>value</param_name>
 *   </args>
 * </tool>
 * After reasoning, ALWAYS emit your tool call as XML.
 * You may call multiple tools in one response.
 * </tool_usage_instructions>
 * ```
 */
object XmlToolDefinitionBuilder {

    fun build(tools: List<ToolDefinition>): String {
        val sb = StringBuilder()
        sb.appendLine("<tool_definitions>")
        for (tool in tools) {
            sb.appendLine("  <tool>")
            sb.appendLine("    <tool_name>${tool.function.name}</tool_name>")
            sb.appendLine("    <description>${tool.function.description ?: ""}</description>")

            val params = tool.function.parameters
            if (params != null && params is JsonObject) {
                sb.appendLine("    <parameters>")
                val properties = params["properties"]?.jsonObject ?: emptyMap()
                val required = (params["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?.toSet() ?: emptySet()

                for ((name, schema) in properties) {
                    val obj = schema.jsonObject
                    val type = obj["type"]?.jsonPrimitive?.content ?: "string"
                    val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                    val isRequired = name in required
                    sb.appendLine("      <parameter name=\"$name\" type=\"$type\" required=\"$isRequired\">")
                    sb.appendLine("        $desc")
                    sb.appendLine("      </parameter>")
                }
                sb.appendLine("    </parameters>")
            }

            sb.appendLine("  </tool>")
        }
        sb.appendLine("</tool_definitions>")
        sb.appendLine()
        sb.appendLine(USAGE_INSTRUCTIONS)
        return sb.toString()
    }

    private val USAGE_INSTRUCTIONS = """
<tool_usage_instructions>
To call a tool, output XML in this exact format:
<tool>
  <name>tool_name</name>
  <args>
    <param_name>value</param_name>
  </args>
</tool>

Rules:
- After reasoning, ALWAYS emit your tool call as XML. Do not describe tool calls in text.
- You may call multiple tools in one response by emitting multiple <tool> blocks.
- Each <tool> block must have exactly one <name> and one <args> child.
- Parameter values can span multiple lines (e.g., file content).
- Do NOT use native function calling — always use XML tool tags.
- IMPORTANT: Even for large code outputs (100+ lines), you MUST wrap them in a <tool> block.
  Never output code in a fenced code block when a tool call is expected. Always use the
  appropriate tool (create_file, edit_file) with the code in the <content> parameter.
</tool_usage_instructions>
    """.trimIndent()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:test --tests "*.XmlToolDefinitionBuilderTest" -v`
Expected: 3 tests PASSED

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/XmlToolDefinitionBuilder.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/ai/XmlToolDefinitionBuilderTest.kt
git commit -m "feat(xml-tools): add XML tool definition builder for system prompt injection

Converts ToolDefinition objects to XML schemas that get injected into
the system prompt (Cline pattern). Includes usage instructions that
tell the LLM to emit <tool> XML blocks instead of using native
function calling. Handles parameter types, descriptions, and required."
```

---

### Task 7: Wire XML parsing into `SourcegraphChatClient.sendMessageStream()`

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt:382-437`

After streaming completes, if no native tool calls were found but the text contains `<tool>` tags, parse them with `XmlToolCallParser` and inject into the response.

- [ ] **Step 1: Add XML tool call extraction after stream assembly**

In `SourcegraphChatClient.sendMessageStream()`, after the existing tool call assembly block (line ~413) and before the `finalMessage` construction (line ~423), add XML fallback parsing:

```kotlin
                // --- XML tool call fallback (Cline Mode B) ---
                // If no native tool calls were assembled but the text content contains
                // <tool> tags, parse them. This handles the case where tools were
                // defined in the system prompt as XML schemas (not via tools: []).
                val xmlParsed = if (toolCalls == null || toolCalls.isEmpty()) {
                    val text = contentBuilder.toString()
                    if (text.contains("<tool>")) {
                        val parsed = XmlToolCallParser.parse(text)
                        if (parsed.toolCalls.isNotEmpty()) {
                            log.info("[Agent:API] Extracted ${parsed.toolCalls.size} tool call(s) from XML in text content")
                            parsed
                        } else null
                    } else null
                } else null

                val finalToolCalls = toolCalls ?: xmlParsed?.toolCalls?.ifEmpty { null }
                var finalContent = if (xmlParsed != null) {
                    xmlParsed.textContent.ifBlank { null }
                } else {
                    contentBuilder.toString().ifBlank { null }
                }
                val finalFinishReason = if (finalToolCalls != null && finishReason == "stop") {
                    "tool_calls" // Upgrade finish reason when XML tools found
                } else {
                    finishReason
                }

                // Signal truncation when XML tool call was cut off by max_tokens.
                // AgentLoop can detect this marker and retry with higher maxTokens.
                if (xmlParsed?.hasPartial == true && finalToolCalls.isNullOrEmpty()) {
                    log.warn("[Agent:API] XML tool call truncated by max_tokens — signaling for retry")
                    finalContent = (finalContent ?: "") + "\n\n[TOOL_CALL_TRUNCATED: The tool call was cut off by the output limit. Please retry with the same tool call.]"
                }
```

Then update the `finalMessage` and `streamResponse` to use `finalToolCalls`, `finalContent`, and `finalFinishReason`.

- [ ] **Step 2: Update finalMessage construction**

Replace the existing `finalMessage` and `streamResponse` construction:

```kotlin
                val finalMessage = ChatMessage(
                    role = role,
                    content = finalContent,
                    toolCalls = finalToolCalls
                )

                activeCall.set(null)

                val streamResponse = ChatCompletionResponse(
                    id = "stream-${System.nanoTime()}",
                    choices = listOf(Choice(index = 0, message = finalMessage, finishReason = finalFinishReason)),
                    usage = streamUsage
                )
```

- [ ] **Step 3: Build and run all core tests**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:test -v`
Expected: All tests PASSED

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt
git commit -m "feat(xml-tools): wire XML tool call parsing into streaming response

After streaming completes, if no native tool calls were assembled but
the text contains <tool> tags, parses them via XmlToolCallParser and
injects into the ChatCompletionResponse. This is transparent to
AgentLoop — it receives tool calls the same way regardless of whether
they came from native function calling or XML text parsing.

Upgrades finish_reason from 'stop' to 'tool_calls' when XML tools found."
```

---

### Task 8: Add XML mode flag to `OpenAiCompatBrain` + XML parsing in `sendMessage()`

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrain.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt`

When XML mode is active, `chatStream()` passes `tools = null` to the API (tool definitions are in the system prompt instead). The caller is responsible for injecting XML tool definitions into the system prompt.

**CRITICAL:** XML parsing must also be wired into `sendMessage()` (non-streaming), not just `sendMessageStream()`. The zero-delta fallback at `SourcegraphChatClient.kt:372-379` calls `sendMessage()` — if `xmlToolMode=true`, the model responds with XML in text content, but without parsing, the response has `toolCalls=null` and the agent silently skips tool execution.

- [ ] **Step 1: Add `xmlToolMode` property to `LlmBrain` interface**

```kotlin
interface LlmBrain {
    // ... existing members ...

    /** When true, tools are defined as XML in the system prompt, not via tools: [] */
    val xmlToolMode: Boolean get() = false
}
```

- [ ] **Step 2: Add `xmlToolMode` to `OpenAiCompatBrain`**

```kotlin
class OpenAiCompatBrain(
    sourcegraphUrl: String,
    tokenProvider: () -> String?,
    private val model: String,
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 180,
    httpClientOverride: OkHttpClient? = null,
    override val xmlToolMode: Boolean = false
) : LlmBrain {
```

- [ ] **Step 3: Strip `tools` param when in XML mode**

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
        tools = if (xmlToolMode) null else tools,  // XML mode: tools in prompt, not request
        maxTokens = maxTokens,
        onChunk = onChunk
    )
}
```

Update `chat()` the same way:

```kotlin
override suspend fun chat(
    messages: List<ChatMessage>,
    tools: List<ToolDefinition>?,
    maxTokens: Int?,
    toolChoice: JsonElement?
): ApiResult<ChatCompletionResponse> {
    return client.sendMessage(
        messages = messages,
        tools = if (xmlToolMode) null else tools,
        maxTokens = maxTokens,
        toolChoice = toolChoice
    )
}
```

- [ ] **Step 4: Add XML parsing to `sendMessage()` response path**

In `SourcegraphChatClient.sendMessage()`, after the response is parsed from JSON but before returning, add XML fallback parsing. This mirrors the streaming path (Task 7) and covers the zero-delta fallback:

```kotlin
// In sendMessage(), after response deserialization:
val choice = response.choices.firstOrNull()
if (choice != null && choice.message.toolCalls.isNullOrEmpty()) {
    val content = choice.message.content
    if (content != null && content.contains("<tool>")) {
        val parsed = XmlToolCallParser.parse(content)
        if (parsed.toolCalls.isNotEmpty()) {
            log.info("[Agent:API] Extracted ${parsed.toolCalls.size} XML tool call(s) from non-streaming response")
            val updatedMessage = choice.message.copy(
                content = parsed.textContent.ifBlank { null },
                toolCalls = parsed.toolCalls
            )
            val updatedFinishReason = if (choice.finishReason == "stop") "tool_calls" else choice.finishReason
            return ApiResult.Success(
                response.copy(
                    choices = listOf(choice.copy(message = updatedMessage, finishReason = updatedFinishReason))
                )
            )
        }
    }
}
```

- [ ] **Step 5: Build and run all core tests**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:test -v`
Expected: All tests PASSED

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrain.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt
git commit -m "feat(xml-tools): add xmlToolMode flag + XML parsing in sendMessage()

When xmlToolMode=true, tools parameter is stripped from API requests
(tools are in the system prompt as XML instead). Defaults to false
for backward compatibility.

CRITICAL: Also wires XML parsing into sendMessage() (non-streaming)
to cover the zero-delta fallback path. Without this, tool calls in
the fallback response would be silently lost."
```

---

### Task 9: Add `useXmlToolMode` setting to PluginSettings

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/AiAdvancedSettingsComponent.kt` (or equivalent settings UI)

Provides a rollback path if XML mode causes issues in production. Per project convention: always add settings UI for new config fields.

- [ ] **Step 1: Add boolean field to PluginSettings State**

```kotlin
// In PluginSettings.State, alongside other boolean fields:
var useXmlToolMode by property(true)  // Default ON — XML fixes parallel tool calls
```

- [ ] **Step 2: Add checkbox to AI & Advanced settings page**

In the settings component (AI & Advanced page), add a checkbox:

```kotlin
row("XML tool calling:") {
    checkBox("Use XML tool format instead of native function calling")
        .bindSelected(state::useXmlToolMode)
        .comment("Fixes parallel tool calls. Disable to fall back to native function calling.")
}
```

- [ ] **Step 3: Build and verify**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/settings/AiAdvancedSettingsComponent.kt
git commit -m "feat(xml-tools): add useXmlToolMode setting with UI toggle

Default ON (XML mode fixes parallel tool calls). Users can disable
to fall back to native function calling if XML mode causes issues.
Provides a rollback path without requiring a plugin update."
```

---

### Task 10: Activate XML mode in `AgentService` brain construction

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:202`

- [ ] **Step 1: Read `useXmlToolMode` from settings and pass to brain**

At `AgentService.kt:202`, read the setting and pass it:

```kotlin
val xmlMode = PluginSettings.getInstance().state.useXmlToolMode

return OpenAiCompatBrain(
    sourcegraphUrl = sgUrl,
    tokenProvider = tokenProvider,
    model = modelId,
    xmlToolMode = xmlMode
)
```

Also update the fallback brain construction at line ~620 the same way.

- [ ] **Step 2: Build and verify**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(xml-tools): read useXmlToolMode from settings in AgentService

Brain construction reads the PluginSettings toggle. When true (default),
xmlToolMode is enabled and tools are in the system prompt as XML."
```

---

### Task 11: Inject XML tool definitions into `SystemPrompt.build()`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt:530`

This is the critical integration point. `SystemPrompt.build()` assembles 11 sections separated by `====`. The XML tool definitions become a new Section 6 (after Capabilities, before Rules). The tool definitions must be injected dynamically because they change based on plan mode (write tools filtered out) and deferred tool activation.

**Note on token budget:** Moving ~22 tool definitions into the system prompt adds ~8-15K tokens to the system message. The context manager must count this enlarged prompt correctly. Since `SystemPrompt.build()` output is already included in token counting (it's the first message), this should be automatic — but verify after wiring.

- [ ] **Step 1: Add `toolDefinitionsXml` parameter to `SystemPrompt.build()`**

```kotlin
// In SystemPrompt.build(), add parameter:
fun build(
    // ... existing params ...
    toolDefinitionsXml: String? = null  // XML tool definitions for Mode B
): String = buildString {
    // ... existing sections 1-5 ...

    // Section 6: XML Tool Definitions (only when xmlToolMode active)
    if (toolDefinitionsXml != null) {
        append(SECTION_SEP)
        append("# Tool Definitions\n\n")
        append("You have access to the following tools. To use a tool, output XML as shown below.\n\n")
        append(toolDefinitionsXml)
    }

    // ... existing sections 7-11 (Rules, System Info, etc.) ...
}
```

- [ ] **Step 2: Wire tool definitions from `AgentLoop` into `SystemPrompt`**

In `AgentLoop`, at line ~530 where the system prompt is built and tools are obtained for the API call, inject XML definitions when the brain has `xmlToolMode=true`:

```kotlin
val currentToolDefs = toolDefinitionProvider?.invoke() ?: toolDefinitions

// Build XML tool definitions for system prompt injection (when xmlToolMode active)
val toolDefsXml = if (brain.xmlToolMode) {
    XmlToolDefinitionBuilder.build(currentToolDefs)
} else null

val systemPromptText = systemPrompt.build(
    // ... existing params ...
    toolDefinitionsXml = toolDefsXml
)

val apiResult = brain.chatStream(
    messages = contextManager.getMessages(),
    tools = currentToolDefs,  // Brain strips these when xmlToolMode=true
    maxTokens = maxOutputTokens
)
```

This ensures tool definitions are always in sync — the same `currentToolDefs` list generates both the XML (for the system prompt) and the native list (which the brain discards when in XML mode). Plan mode filtering is already applied before this point, so write tools are correctly excluded from XML definitions in plan mode.

- [ ] **Step 3: Verify large-code reinforcement is in USAGE_INSTRUCTIONS**

Lab result: `streaming_code_500_lines` Mode B — model chose plain text instead of XML tool tags for large code generation. The `USAGE_INSTRUCTIONS` in `XmlToolDefinitionBuilder` (already written in Task 6) includes the explicit reinforcement:

> "IMPORTANT: Even for large code outputs (100+ lines), you MUST wrap them in a `<tool>` block."

Verify this text is present in the builder. If not, add it to `XmlToolDefinitionBuilder.USAGE_INSTRUCTIONS`.

- [ ] **Step 4: Build and run agent tests**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :agent:test -v`
Expected: All tests PASSED (or known pre-existing failures only)

- [ ] **Step 5: Verify token budget impact**

After wiring, check that the system prompt with XML tool definitions doesn't cause unexpected context budget issues:

```kotlin
// Quick verification: build system prompt with all tools and measure
val toolDefs = registry.getAllDefinitions()
val xml = XmlToolDefinitionBuilder.build(toolDefs)
val systemPrompt = SystemPrompt.build(toolDefinitionsXml = xml, /* other params */)
println("System prompt tokens (approx): ${systemPrompt.length / 4}")
// Expect: ~15-25K tokens for system prompt with 22 tools
// Context budget should still leave 125K+ tokens for conversation
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/ai/XmlToolDefinitionBuilder.kt
git commit -m "feat(xml-tools): inject XML tool definitions into SystemPrompt section 6

Wires XmlToolDefinitionBuilder output into SystemPrompt.build() as a
new section between Capabilities and Rules. Tool definitions are
generated dynamically from the same ToolDefinition list used for API
calls, so plan mode filtering is automatically applied.

Strengthens usage instructions for large code outputs — lab showed the
model may skip XML format for 500+ line code generation without
explicit reinforcement.

Token budget impact: adds ~8-15K tokens to system prompt. Verified
this leaves sufficient room in 150K context for conversation."
```

---

### Task 12: Full integration test

**Files:**
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClientStreamTest.kt`

- [ ] **Step 1: Add XML mode integration test**

```kotlin
@Test
fun `parses XML tool calls from text content when no native tools in request`() = runTest {
    // Simulate XML mode: model responds with XML tool tags in text
    val chunk1 = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"Let me read that.\n\n<tool>\n  <name>read_file</name>\n  <args>\n    <path>"},"finish_reason":null}]}"""
    val chunk2 = """{"id":"c1","choices":[{"index":0,"delta":{"content":"Foo.kt</path>\n  </args>\n</tool>"},"finish_reason":"stop"}]}"""
    val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":200,"completion_tokens":30,"total_tokens":230}}"""

    server.enqueue(sseResponse(chunk1, chunk2, usageChunk))

    val result = client.sendMessageStream(
        messages = listOf(ChatMessage(role = "user", content = "read Foo.kt")),
        tools = null,  // XML mode: no tools in request
        onChunk = {}
    )

    assertIs<ApiResult.Success<ChatCompletionResponse>>(result)
    val response = result.data
    // Should have extracted tool call from XML and upgraded finish reason
    assertEquals("tool_calls", response.choices.first().finishReason)
    val toolCalls = response.choices.first().message.toolCalls
    assertNotNull(toolCalls)
    assertEquals(1, toolCalls.size)
    assertEquals("read_file", toolCalls[0].function.name)
    assertTrue(toolCalls[0].function.arguments.contains("Foo.kt"))
    // Text content should be just the reasoning, not the XML
    assertEquals("Let me read that.", response.choices.first().message.content?.trim())
}

@Test
fun `parses multiple parallel XML tool calls`() = runTest {
    val content = "I'll read both files.\n\n<tool>\n  <name>read_file</name>\n  <args>\n    <path>A.kt</path>\n  </args>\n</tool>\n\n<tool>\n  <name>read_file</name>\n  <args>\n    <path>B.kt</path>\n  </args>\n</tool>"
    val chunk = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(content)}},"finish_reason":"stop"}]}"""
    val usageChunk = """{"id":"c1","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}"""

    server.enqueue(sseResponse(chunk, usageChunk))

    val result = client.sendMessageStream(
        messages = listOf(ChatMessage(role = "user", content = "read both")),
        tools = null,
        onChunk = {}
    )

    assertIs<ApiResult.Success<ChatCompletionResponse>>(result)
    val toolCalls = result.data.choices.first().message.toolCalls
    assertNotNull(toolCalls)
    assertEquals(2, toolCalls.size) // Both recovered — no concat bug!
    assertTrue(toolCalls[0].function.arguments.contains("A.kt"))
    assertTrue(toolCalls[1].function.arguments.contains("B.kt"))
}
```

- [ ] **Step 2: Run all core tests**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :core:test -v`
Expected: All tests PASSED

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClientStreamTest.kt
git commit -m "test(xml-tools): add integration tests for XML tool call extraction from streaming

Tests end-to-end flow: SSE streaming → text accumulation → XML parsing
→ tool call extraction → ChatCompletionResponse with proper tool_calls.
Covers single XML tool, parallel XML tools (the key fix — both recovered
unlike native which loses all but first)."
```

---

### Task 13: Streaming chunk filter for XML tags in chat UI

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt` (or wherever `onChunk` callback is wired to the UI)

**Known UX regression:** During streaming, the `onChunk` callback sends raw text to the chat UI. With XML mode, users see `<tool><name>read_file</name><args>...` scrolling past instead of clean output.

A minimal chunk filter suppresses text after detecting a `<tool>` opening tag. Full incremental XML parsing (showing "Using tool: read_file" in real time) is a follow-up.

- [ ] **Step 1: Add simple XML tag suppression in the onChunk wrapper**

In the `onChunk` callback that sends text to the UI, track whether we're inside a `<tool>` block:

```kotlin
// State for XML chunk filtering (reset per API call)
var insideXmlTool = false
val visibleTextBuffer = StringBuilder()

val filteredOnChunk: suspend (StreamChunk) -> Unit = { chunk ->
    val text = chunk.text ?: ""
    for (char in text) {
        visibleTextBuffer.append(char)
        val current = visibleTextBuffer.toString()
        if (!insideXmlTool && current.endsWith("<tool>")) {
            // Entering tool block — suppress from here
            // Remove the "<tool>" we just accumulated from visible output
            insideXmlTool = true
        }
        if (insideXmlTool && current.endsWith("</tool>")) {
            // Exiting tool block — resume visible output
            insideXmlTool = false
        }
    }

    if (!insideXmlTool && chunk.text != null) {
        // Only forward text chunks that aren't inside <tool> blocks
        val visibleText = chunk.text.takeUnless { insideXmlTool }
        if (visibleText != null) {
            originalOnChunk(chunk)
        }
    }
}
```

**Note:** This is a minimal filter. It may leave a partial `<tool` visible at the boundary. A proper incremental parser (re-parsing accumulated text on each chunk, like Cline does) is tracked as a follow-up task. The key behavior: once the full response is assembled, `XmlToolCallParser` post-processes correctly regardless of what was shown during streaming.

- [ ] **Step 2: Build and run agent tests**

Run: `cd .worktrees/streaming-xml-port && ./gradlew :agent:test -v`
Expected: All tests PASSED

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt
git commit -m "feat(xml-tools): suppress XML tool tags from streaming chat UI

Minimal chunk filter that detects <tool>...</tool> blocks during
streaming and suppresses them from the visible chat output. Users
see reasoning text but not raw XML. Full incremental parsing
(showing tool-use indicators) is a follow-up."
```

---

## Summary

| Task | Phase | What | Test |
|------|-------|------|------|
| 1 | Streaming | Re-enable real streaming in `chatStream()` | Compilation |
| 2 | Streaming | Increase read timeout to 180s | Compilation |
| 3 | Streaming | Add `stream_options.include_usage` | Compilation |
| 4 | Streaming | Write streaming SSE parsing tests | 4 unit tests |
| 5 | XML Port | Create `XmlToolCallParser` with TDD (thread-safe, correct `lastIndexOf`) | 10 unit tests |
| 6 | XML Port | Create `XmlToolDefinitionBuilder` with TDD | 3 unit tests |
| 7 | XML Port | Wire XML parsing into `sendMessageStream()` + truncation signal | Existing + new tests |
| 8 | XML Port | Add `xmlToolMode` flag + XML parsing in `sendMessage()` (zero-delta fix) | Compilation |
| 9 | XML Port | Add `useXmlToolMode` setting to PluginSettings with UI | Compilation |
| 10 | XML Port | Activate in `AgentService` — read setting, pass to brain | Compilation |
| 11 | XML Port | Inject XML tool defs into `SystemPrompt.build()` Section 6 + reinforced instructions | Agent tests |
| 12 | XML Port | Full integration tests | 2 integration tests |
| 13 | XML Port | Streaming chunk filter — suppress XML tags in chat UI | Agent tests |

---

## Review Findings Addressed

This plan was reviewed and updated to fix the following issues:

| # | Severity | Issue | Resolution |
|---|----------|-------|------------|
| 1 | **CRITICAL** | `parseArgsToJson()` `lastIndexOf` mis-parses multi-arg tools when `<path>` appears inside `<content>` | Fixed: `indexOf` for all tags except `CODE_CARRYING_TAGS` (content, new_string, diff, code). Added dedicated test. |
| 2 | **CRITICAL** | Zero-delta fallback broken in XML mode — `sendMessage()` doesn't parse XML from text | Fixed: Task 8 Step 4 adds XML parsing to `sendMessage()` response path |
| 3 | **CRITICAL** | `idCounter` race condition with concurrent sub-agents | Fixed: `AtomicInteger` instead of `var` |
| 4 | **HIGH** | Task 9 had no concrete code for SystemPrompt wiring | Fixed: Task 11 has full code for `SystemPrompt.build()` param, `AgentLoop` wiring, token budget verification |
| 5 | **HIGH** | Model skips XML format for large code outputs (500-line lab failure) | Fixed: Strengthened `USAGE_INSTRUCTIONS` with explicit large-code reinforcement in Task 11 Step 3 |
| 6 | **HIGH** | No rollback path / feature flag | Fixed: New Task 9 adds `useXmlToolMode` to PluginSettings with UI checkbox |
| 7 | **HIGH** | Raw XML tags visible in chat UI during streaming | Fixed: New Task 13 adds minimal chunk filter. Full incremental parsing tracked as follow-up |
| 8 | **MEDIUM** | `hasPartial` (truncation) detected but never used | Fixed: Task 7 now appends `[TOOL_CALL_TRUNCATED]` signal for agent retry |
| 9 | **MEDIUM** | System prompt token budget increase unaccounted | Fixed: Task 11 Step 5 adds explicit token budget verification |
| 10 | **MEDIUM** | No large-content test case (200+ lines) | Fixed: Added `handles large content block with 200+ lines` test in Task 5 |
| 11 | **MEDIUM** | Misleading `lastIndexOf` comment in `parse()` — code uses `indexOf` | Fixed: Comment now accurately describes forward `indexOf` parsing |
