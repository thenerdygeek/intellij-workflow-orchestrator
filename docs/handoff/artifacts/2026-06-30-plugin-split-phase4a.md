# Plugin Split — Phase 4a (Native Anthropic-Direct LLM Provider) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let the agent run against `api.anthropic.com` with a user-supplied API key (no Sourcegraph required), via a native Anthropic provider.

**Architecture (Option A — "native wire, XML-internal"):** A new `AnthropicDirectBrain : LlmBrain` calls Anthropic's Messages API natively (top-level `system` field, structured `tools:[]`, `tool_use`/`tool_result`, adaptive thinking + effort, `x-api-key`, proxy-aware OkHttp) and **deterministically serializes the model's structured `tool_use` into the canonical XML the agent already uses** before it reaches the loop — so `AssistantMessageParser`, persistence (`ApiMessage` XML-in-`Text`), and the dialect-drift machinery are unchanged. Provider choice is exclusive at `BrainFactory.create()`; the native path skips `BrainRouter`. The structured-persistence path + seam consolidations are Phase 4b.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform Plugin v2, OkHttp + kotlinx.serialization, JUnit 5 + MockK. Modules: `:core` (DTOs, protocol, catalog, http, settings, credentials, ModelCatalogService adapter) and `:agent` (brain, factory + protocol wiring).

**Spec:** `docs/superpowers/specs/2026-06-30-plugin-split-phase4a-design.md` (v2, review-corrected). Signature inventory: `.superpowers/phase4/plan-signatures.md`. Plan reviews folded: `.superpowers/phase4/plan-review-skeptic.md` (3 Crit/6 Imp) + `.superpowers/phase4/plan-review-completeness.md` (1 Crit/9 Imp).

## Global Constraints

- Branch `feature/plugin-split`. **NO `Co-Authored-By` / "Generated with" trailer** on any commit.
- **Canonical provider literal is `"anthropic"`** — NOT the spec's earlier `"anthropic-direct"`. Every `llmProvider == "anthropic"` guard and field uses this exact value; one mismatched literal silently routes through the Sourcegraph path with no compile error. (Spec §5.8 corrected to match.)
- **Never emit sampling params** (`temperature`/`top_p`/`top_k`/`budget_tokens`) in the Anthropic request — `budget_tokens` 400s confirmed; the rest omitted regardless. The native brain's `temperature` setter is a **no-op**.
- **Anthropic request invariants:** `max_tokens` required (fall back to the model's max-output when null); thinking = `{"type":"adaptive","display":"summarized"}` + `output_config:{effort}` when enabled; system prompt → top-level `system` field; tools → `tools:[{name,description,input_schema}]`; one `cache_control:{type:"ephemeral"}` on the system block.
- **Bare Anthropic model IDs** (`claude-opus-4-8`, `claude-sonnet-4-6`, `claude-haiku-4-5`(+`-20251001`), `claude-fable-5`); never a Sourcegraph `provider::apiVersion::id` ref. **Sonnet 4.6 max output = 128K.**
- **Provider exclusivity:** when `llmProvider == "anthropic"`, EVERY provider-dependent site uses the Anthropic provider — brain, **the `ToolProtocol` instance threaded to all prompt-build + dialect-guard sites** (orchestrator, loop, resume, `SubagentRunner`, `MessageStateHandler`), the `ModelCatalogService`, sub-agent default model, OFFLINE probe URL, L2 fallback chain, `availableModels`, the in-chat picker, and `wrapBrainWithRouter` skip. No Sourcegraph URL / `ModelCache` ref / `provider::…::…` string may leak onto the native path.
- `:core` tests are pure JUnit5 (no `BasePlatformTestCase`). Gate: `:core:test` + `:agent:test`, `verifyPlugin` (`-x buildSearchableOptions` on macOS), `koverVerify -Pcoverage -x uiTest`. `--no-build-cache` for any `suspend`-signature change; `gradlew --stop` for macOS config-cache.

---

### Task 1: Settings + `ServiceType.ANTHROPIC` + `DefaultWorkflowConfig` branch + credential

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/model/ServiceType.kt:3-10`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/config/DefaultWorkflowConfig.kt:20-30` (the exhaustive `when`)
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/ConnectionSettings.kt:23-49`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt` (State)
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/settings/AnthropicSettingsTest.kt` (new)

**Interfaces produced:** `ServiceType.ANTHROPIC`; `ConnectionSettings.State.anthropicApiUrl: String`; `DefaultWorkflowConfig.baseUrl(ServiceType.ANTHROPIC)` returns it; `AgentSettings.State.{llmProvider, anthropicModel, anthropicEffort, anthropicThinkingEnabled}`.

- [ ] **Step 1: Write the failing test** — the `DefaultWorkflowConfig` case is Crit-1: a new enum entry without a `when` arm is a COMPILE error, so this test guards the whole gate.
```kotlin
class AnthropicSettingsTest {
    @Test fun `ANTHROPIC service type exists with display name`() {
        assertEquals("Anthropic", ServiceType.ANTHROPIC.displayName)
    }
    @Test fun `ConnectionSettings has anthropic api url default`() {
        assertEquals("https://api.anthropic.com", ConnectionSettings.State().anthropicApiUrl)
    }
    @Test fun `DefaultWorkflowConfig returns anthropicApiUrl for ANTHROPIC`() {
        val cfg = DefaultWorkflowConfig { ConnectionSettings.State(anthropicApiUrl = "https://api.anthropic.com") }
        assertEquals("https://api.anthropic.com", cfg.baseUrl(ServiceType.ANTHROPIC))
    }
}
```
- [ ] **Step 2: Run → fail.** `./gradlew :core:test --tests "*AnthropicSettingsTest*"` → FAIL (unresolved `ANTHROPIC` / `anthropicApiUrl`).
- [ ] **Step 3: Implement.** Add `ANTHROPIC("Anthropic")` to `ServiceType`. Add `var anthropicApiUrl: String = "https://api.anthropic.com"` to `ConnectionSettings.State`. Add `ServiceType.ANTHROPIC -> state.anthropicApiUrl` to the `when` in `DefaultWorkflowConfig.baseUrl()`. Add to `AgentSettings.State`:
```kotlin
var llmProvider by string("sourcegraph")          // "sourcegraph" | "anthropic"  (canonical literal: "anthropic")
var anthropicModel by string("claude-opus-4-8")
var anthropicEffort by string("high")             // low|medium|high|xhigh|max
var anthropicThinkingEnabled by property(true)
```
- [ ] **Step 4: Run → pass.** Same command → PASS. Confirm `:core` compiles (the `when` exhaustiveness is the real check).
- [ ] **Step 5: Detekt + commit.** `./gradlew :core:detekt --rerun-tasks`; commit `core/.../ServiceType.kt core/.../DefaultWorkflowConfig.kt core/.../ConnectionSettings.kt agent/.../AgentSettings.kt core/src/test/.../AnthropicSettingsTest.kt` — `feat(core): anthropic provider settings + ServiceType.ANTHROPIC + WorkflowConfig arm`.

---

### Task 2: `AnthropicModelCatalog` (static)

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/AnthropicModelCatalog.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/AnthropicModelCatalogTest.kt`

**Interfaces produced:**
```kotlin
object AnthropicModelCatalog {
    data class Entry(val id: String, val contextWindow: Int, val maxOutput: Int, val supportsVision: Boolean)
    val MODELS: List<Entry>
    fun entry(modelId: String): Entry?
    fun contextWindow(modelId: String): Int      // default 200_000 on miss
    fun maxOutput(modelId: String): Int          // default 64_000 on miss
    fun defaultModel(): String                   // "claude-opus-4-8"
    fun defaultSubagentModel(): String           // "claude-sonnet-4-6"  (spec §5.7 calls it getDefaultSubagentModel; idiomatic name used here)
    fun fallbackChain(): List<String>            // ["claude-opus-4-8","claude-sonnet-4-6"]
}
```

- [ ] **Step 1: Write the failing test**
```kotlin
class AnthropicModelCatalogTest {
    @Test fun `sonnet 4_6 max output is 128k`() =
        assertEquals(128_000, AnthropicModelCatalog.maxOutput("claude-sonnet-4-6"))
    @Test fun `opus 4_8 context window is 1M`() =
        assertEquals(1_000_000, AnthropicModelCatalog.contextWindow("claude-opus-4-8"))
    @Test fun `defaults are bare ids`() {
        assertEquals("claude-opus-4-8", AnthropicModelCatalog.defaultModel())
        assertEquals("claude-sonnet-4-6", AnthropicModelCatalog.defaultSubagentModel())
        assertFalse(AnthropicModelCatalog.defaultSubagentModel().contains("::"))
    }
    @Test fun `unknown model falls back`() {
        assertEquals(200_000, AnthropicModelCatalog.contextWindow("nope"))
        assertEquals(64_000, AnthropicModelCatalog.maxOutput("nope"))
    }
    @Test fun `fallback chain is opus then sonnet`() =
        assertEquals(listOf("claude-opus-4-8","claude-sonnet-4-6"), AnthropicModelCatalog.fallbackChain())
}
```
- [ ] **Step 2: Run → fail.** `./gradlew :core:test --tests "*AnthropicModelCatalogTest*"`
- [ ] **Step 3: Implement** the static map: `claude-opus-4-8`(1_000_000/128_000/vision), `claude-sonnet-4-6`(1_000_000/**128_000**/vision), `claude-haiku-4-5`(200_000/64_000/vision), `claude-fable-5`(1_000_000/128_000/vision); funcs as above.
- [ ] **Step 4: Run → pass.**
- [ ] **Step 5: Detekt + commit** — `feat(core): static AnthropicModelCatalog (context windows, defaults, fallback chain)`.

---

### Task 3: `AnthropicModelCatalog` → `ModelCatalogService` adapter + `ContextManager` wiring (C2)

**Why:** Without this, on native `getSharedModelCatalog()` returns null (keyed on a blank Sourcegraph URL) → `ContextManager.effectiveMaxInputTokens()` falls back to `FALLBACK_MAX_INPUT_TOKENS = 90_000`, so `claude-opus-4-8` (1M) compacts at ~79K — an ~11× premature-compaction regression that also corrupts the `UsageIndicator`/model-picker capacity strip.

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/AnthropicModelCatalogService.kt` (adapter implementing `core.ai.ModelCatalogService`)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` — `newContextManager()` (`:896-901`), `effectiveContextWindow.windowLookup` (`:906`), executeTask catalog selection (`:2054`,`:2574`)
- Test: `core/.../AnthropicModelCatalogServiceTest.kt` + `agent/.../NativeCatalogSelectionContractTest.kt`

**Interfaces consumed:** `core.ai.ModelCatalogService` (read its interface — at least `getContextWindow(modelRef: String): Int?`), `AnthropicModelCatalog`.
**Interfaces produced:** `class AnthropicModelCatalogService : ModelCatalogService` returning `AnthropicModelCatalog.contextWindow(id)` keyed on the **bare** id.

- [ ] **Step 1: Write the failing tests.** First read `core/.../ai/ModelCatalogService.kt` to match its exact method set. Then:
```kotlin
class AnthropicModelCatalogServiceTest {
    private val svc = AnthropicModelCatalogService()
    @Test fun `getContextWindow uses bare id`() =
        assertEquals(1_000_000, svc.getContextWindow("claude-opus-4-8"))
    @Test fun `unknown ref falls back to catalog default`() =
        assertEquals(200_000, svc.getContextWindow("totally-unknown"))
}
class NativeCatalogSelectionContractTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()
    @Test fun `agent selects anthropic catalog when provider is anthropic`() {
        assertTrue(src.contains("AnthropicModelCatalogService"))
        assertTrue(Regex("llmProvider\\s*==\\s*\"anthropic\"").containsMatchIn(src))
    }
}
```
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement** the adapter (every `ModelCatalogService` method delegates to `AnthropicModelCatalog`; non-applicable methods return a sensible static value). In `AgentService`, where `modelCatalogService` / `effectiveContextWindow.windowLookup` are assigned from `getSharedModelCatalog()`/`sharedCatalogHolder.peek()`, branch: `if (llmProvider == "anthropic") AnthropicModelCatalogService() else <existing>`. Use the bare model id (`anthropicModel`) as the lookup ref on native.
- [ ] **Step 4: Run → pass.** `./gradlew :core:test :agent:test --tests "*Catalog*"`.
- [ ] **Step 5: `:agent:detekt --rerun-tasks` + commit** — `feat: wire AnthropicModelCatalog into ContextManager via ModelCatalogService (native context window)`.

---

### Task 4: Anthropic request DTOs + `AnthropicRequestMapper`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/anthropic/AnthropicDtos.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/anthropic/AnthropicRequestMapper.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/anthropic/AnthropicRequestMapperTest.kt`

**Interfaces consumed:** `ChatMessage`(role,content,parts,toolCalls,toolCallId), `ContentPart.{Text,Image}`, `ToolDefinition`/`FunctionParameters`/`ParameterProperty`, `ToolCall`/`FunctionCall` (inventory §4,§7).
**Interfaces produced:** request DTOs + `AnthropicRequestMapper.build(messages, tools, model, maxTokens, thinkingEnabled, effort, imageBytes): AnthropicRequest` where `imageBytes: (String) -> Pair<String, String>?` maps `sha256 → Pair(mediaType, base64)` (null if unavailable).

- [ ] **Step 1: Write the failing tests**
```kotlin
class AnthropicRequestMapperTest {
    private fun toolDef() = ToolDefinition(function = FunctionDefinition(
        "read_file","Read a file",
        FunctionParameters(properties = mapOf("path" to ParameterProperty("string","the path")), required = listOf("path"))))
    private val NOIMG: (String) -> Pair<String, String>? = { null }

    @Test fun `system role maps to top-level system field not a message`() {
        val req = AnthropicRequestMapper.build(
            listOf(ChatMessage("system","SYS"), ChatMessage("user","hi")),
            tools = null, model = "claude-opus-4-8", maxTokens = 4096,
            thinkingEnabled = false, effort = "high", imageBytes = NOIMG)
        assertEquals("SYS", req.system.first().text)
        assertTrue(req.messages.none { it.role == "system" })
        assertEquals("user", req.messages.single().role)
    }
    @Test fun `never emits sampling params`() {
        val json = Json.encodeToString(AnthropicRequestMapper.build(
            listOf(ChatMessage("user","hi")), null, "claude-opus-4-8", 4096, true, "high", NOIMG))
        listOf("temperature","top_p","top_k","budget_tokens").forEach { assertFalse(json.contains(it)) }
    }
    @Test fun `system block carries one ephemeral cache_control`() {
        val req = AnthropicRequestMapper.build(listOf(ChatMessage("system","S"), ChatMessage("user","x")),
            null, "claude-opus-4-8", 4096, false, "high", NOIMG)
        assertEquals("ephemeral", req.system.single().cacheControl?.type)
    }
    @Test fun `tool def unwraps openai compat to input_schema`() {
        val req = AnthropicRequestMapper.build(listOf(ChatMessage("user","x")), listOf(toolDef()),
            "claude-opus-4-8", 4096, false, "high", NOIMG)
        val t = req.tools!!.single()
        assertEquals("read_file", t.name); assertEquals("object", t.inputSchema.type)
        assertEquals(listOf("path"), t.inputSchema.required)
    }
    @Test fun `tool result message maps to tool_result block`() {
        val req = AnthropicRequestMapper.build(
            listOf(ChatMessage("tool", content="OUT", toolCallId="tu_1")), null,
            "claude-opus-4-8", 4096, false, "high", NOIMG)
        val block = req.messages.single().content.first()
        assertEquals("tool_result", block.type); assertEquals("tu_1", block.toolUseId)
    }
    @Test fun `image part hydrates to base64 image block`() {
        val msg = ChatMessage("user", parts = listOf(ContentPart.Image("sha","image/png")))
        val req = AnthropicRequestMapper.build(listOf(msg), null, "claude-opus-4-8", 4096, false, "high") {
            "image/png" to "BASE64" }
        val block = req.messages.single().content.first()
        assertEquals("image", block.type); assertEquals("BASE64", block.source!!.data)
    }
    @Test fun `thinking block present only when enabled, display summarized`() {
        val on = AnthropicRequestMapper.build(listOf(ChatMessage("user","x")), null, "claude-opus-4-8", 4096, true, "high", NOIMG)
        assertEquals("adaptive", on.thinking!!.type); assertEquals("summarized", on.thinking!!.display)
        assertEquals("high", on.outputConfig!!.effort)
        val off = AnthropicRequestMapper.build(listOf(ChatMessage("user","x")), null, "claude-opus-4-8", 4096, false, "high", NOIMG)
        assertNull(off.thinking)
    }
    @Test fun `array param maps to items schema`() {
        val arr = ToolDefinition(function = FunctionDefinition("grep","g",
            FunctionParameters(properties = mapOf("globs" to
                ParameterProperty("array","globs", items = ParameterProperty("string","one"))))))
        val schema = AnthropicRequestMapper.build(listOf(ChatMessage("user","x")), listOf(arr),
            "claude-opus-4-8", 4096, false, "high", NOIMG).tools!!.single().inputSchema
        val globs = schema.properties["globs"]!!
        assertEquals("array", globs.type); assertEquals("string", globs.items!!.type)
    }
}
```
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement** `AnthropicDtos.kt` (`@Serializable` DTOs — `AnthropicRequest{model, system:List<TextBlock>, messages, tools?, max_tokens, thinking?, output_config?}` with `TextBlock{type="text", text, cacheControl:CacheControl?}`, `CacheControl{type}`, content-block type `{type, text?, source?, tool_use_id?, content?, name?, input?}`, `ImageSource{type="base64", mediaType, data}`, `AnthropicTool{name, description, input_schema}`, `InputSchema{type,properties,required}`, `Thinking{type,display}`, `OutputConfig{effort}` — NO sampling fields anywhere) and `AnthropicRequestMapper`. Extract `system`-role messages → `system` field (one `cache_control` ephemeral); map user/assistant/tool turns into content blocks; `ContentPart.Image` → image block via `imageBytes`; `ToolCall` → `tool_use` block; `tool`-role → `tool_result` block; convert `ToolDefinition.function.parameters` → `input_schema` (carry `properties`/`required`; recurse `ParameterProperty.items` for arrays); set `thinking`/`outputConfig` only when `thinkingEnabled`.
- [ ] **Step 4: Run → pass.**
- [ ] **Step 5: Detekt + commit** — `feat(core): Anthropic request DTOs + mapper (system-field, tools, images, cache_control, no sampling params)`.

---

### Task 5: structured `tool_use` → canonical XML serializer + fidelity pin + characterization

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/anthropic/ToolUseXmlSerializer.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/anthropic/ToolUseXmlRoundTripTest.kt`

**Interfaces produced:** `ToolUseXmlSerializer.toXml(name: String, inputJson: JsonObject): String` → `<name><param>value</param>…</name>` (the exact shape `AssistantMessageParser` parses, inventory §8).

**Characterization note (I3 — the round-trip is NOT pure identity).** `AssistantMessageParser.parse` (read it: `core/.../AssistantMessageParser.kt`) `.trim()`s every value (`:130`), never unescapes, and only `lastIndexOf`-scopes the close tag for `CODE_CARRYING_PARAMS = {content,new_string,old_string,diff,code}` (`:112-127`); params not in `paramNameSet` are dropped. So a native value with leading/trailing whitespace, or a NON-code param whose value contains its own close tag (e.g. a `command`/`pattern` value containing `</command>`), does not round-trip. The native model authors only the JSON value with no awareness of the XML wrapping, so collisions are more likely than on the XML-taught path. **The tests characterize this; the serializer must `error()` on a detected close-tag collision in a non-code param rather than emit a silently-truncating payload.**

- [ ] **Step 1: Write the failing tests** (fidelity pin + characterization)
```kotlin
class ToolUseXmlRoundTripTest {
    private fun roundTrip(name: String, input: JsonObject, params: Set<String>): ToolUseContent {
        val xml = ToolUseXmlSerializer.toXml(name, input)
        return AssistantMessageParser.parse(xml, setOf(name), params).filterIsInstance<ToolUseContent>().single()
    }
    @Test fun `single param round-trips canonically`() {
        val call = roundTrip("read_file", buildJsonObject { put("path","src/Foo.kt") }, setOf("path"))
        assertEquals("read_file", call.name); assertEquals("src/Foo.kt", call.params["path"])
    }
    @Test fun `code-carrying param with embedded angle brackets round-trips`() {
        val call = roundTrip("edit_file",
            buildJsonObject { put("path","F.kt"); put("old_string","a<b"); put("new_string","c") },
            setOf("path","old_string","new_string"))
        assertEquals("a<b", call.params["old_string"])
    }
    @Test fun `array value serializes as compact JSON`() {
        val call = roundTrip("grep", buildJsonObject { putJsonArray("globs"){ add("a"); add("b") } }, setOf("globs"))
        assertEquals("""["a","b"]""", call.params["globs"])   // documents the array representation
    }
    @Test fun `non-code param containing its own close tag is rejected at serialize time`() {
        val ex = assertThrows<IllegalStateException> {
            ToolUseXmlSerializer.toXml("run_command", buildJsonObject { put("command","echo </command>") })
        }
        assertTrue(ex.message!!.contains("collision"))
    }
}
```
(Verify `ToolUseContent.{name,params}` against `AssistantMessageParser.kt:56` at implementation — inventory §8 confirms the type name.)
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement** `toXml`: emit `<name>`, then per input key `<key>stringValue</key>`, then `</name>`. Stringify values (primitive → raw string; object/array → compact JSON via `Json.encodeToString`). Before emitting a non-`CODE_CARRYING_PARAMS` value, if it contains `</key>` (or a tag-like `<`), `error("close-tag collision in param '$key'")`.
- [ ] **Step 4: Run → pass.**
- [ ] **Step 5: Detekt + commit** — `feat(core): tool_use→canonical-XML serializer + round-trip pin + collision guard`.

---

### Task 6: `AnthropicSseParser`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/anthropic/AnthropicSseParser.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/anthropic/AnthropicSseParserTest.kt`

**Interfaces produced:** `AnthropicSseParser.parse(lines: Sequence<String>, emitText: (String) -> Unit): Result` with `data class Result(val finishReason: String?, val usageOutputTokens: Int, val errorClass: String?)`. Emits: `text_delta` → `emitText(text)`; `thinking_delta` → `emitText("<thinking>"+text+"</thinking>")` (open once at the thinking block's first delta, close at its `content_block_stop`); accumulates `tool_use` (`input_json_delta.partial_json` per index) and at `message_stop` calls `emitText(ToolUseXmlSerializer.toXml(name, parsedInput))` for each; `event: error` → `errorClass = AnthropicNativeProtocol().classifyStreamLine(dataLine)` (Task 7).

- [ ] **Step 1: Write the failing tests** — full fixtures, NO comment-only stubs (Imp-2)
```kotlin
class AnthropicSseParserTest {
    private fun parse(sse: String): Pair<String, AnthropicSseParser.Result> {
        val out = StringBuilder()
        val r = AnthropicSseParser.parse(sse.trimIndent().lineSequence()) { out.append(it) }
        return out.toString() to r
    }
    @Test fun `text + tool_use stream emits prose then canonical XML at stop`() {
        val (out, r) = parse("""
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Reading."}}
            event: content_block_stop
            data: {"type":"content_block_stop","index":0}
            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"read_file"}}
            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"F.kt\"}"}}
            event: content_block_stop
            data: {"type":"content_block_stop","index":1}
            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}
            event: message_stop
            data: {"type":"message_stop"}""")
        assertEquals("tool_use", r.finishReason); assertEquals(12, r.usageOutputTokens)
        assertTrue(out.contains("Reading."))
        assertTrue(out.contains("<read_file><path>F.kt</path></read_file>"))
    }
    @Test fun `thinking_delta is wrapped in thinking tags`() {
        val (out, _) = parse("""
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}
            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me reason"}}
            event: content_block_stop
            data: {"type":"content_block_stop","index":0}
            event: message_stop
            data: {"type":"message_stop"}""")
        assertTrue(out.contains("<thinking>Let me reason</thinking>"))
    }
    @Test fun `event error sets errorClass`() {
        val (_, r) = parse("""
            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}""")
        assertNotNull(r.errorClass)
    }
}
```
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement** the line-by-line SSE state machine (concatenate `input_json_delta.partial_json` per index; parse accumulated JSON at the tool_use block's stop; serialize via `ToolUseXmlSerializer` at `message_stop`). Pure (no OkHttp) so it's unit-testable.
- [ ] **Step 4: Run → pass.**
- [ ] **Step 5: Detekt + commit** — `feat(core): Anthropic SSE parser (text/thinking/tool_use→canonical XML)`.

---

### Task 7: `AnthropicNativeProtocol`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/protocol/AnthropicNativeProtocol.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/AnthropicNativeProtocolTest.kt`

**Interfaces produced:** `class AnthropicNativeProtocol : NativeProtocol` — `presentTools` returns null + `requiresDialectGuard=false`; `parseToolCalls(text,toolNames,paramNames)` **delegates to `AssistantMessageParser.parse`** (the brain rendered canonical XML into text — Option A; relaxes the `NativeProtocol` "structured deltas" doc invariant, note it in KDoc); `classifyStreamLine(line): String?` maps `event: error` payloads → `overloaded_error`/`rate_limit_error`/`invalid_request_error`; `classifyHttpError(status, body): String?` maps 429→rate_limit, 529→overloaded, 413→context_length, 400→validation, 401/403→auth.

- [ ] **Step 1: Write the failing tests** (read `NativeProtocol.kt` first to match signatures + which members need `override`)
```kotlin
class AnthropicNativeProtocolTest {
    private val p = AnthropicNativeProtocol()
    @Test fun `presentTools is null and dialect guard off`() {
        assertNull(p.presentTools(emptyList()))
        assertFalse(p.requiresDialectGuard)
    }
    @Test fun `parseToolCalls delegates to XML parse`() {
        val calls = p.parseToolCalls("<read_file><path>x</path></read_file>", setOf("read_file"), setOf("path"))
            .filterIsInstance<ToolUseContent>()
        assertEquals("read_file", calls.single().name)
    }
    @Test fun `classifyHttpError maps overloaded and rate limit`() {
        assertNotNull(p.classifyHttpError(529, "overloaded"))
        assertNotNull(p.classifyHttpError(429, "slow down"))
    }
    @Test fun `classifyStreamLine recognizes overloaded error frame`() {
        assertNotNull(p.classifyStreamLine("""{"type":"error","error":{"type":"overloaded_error"}}"""))
    }
}
```
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run → pass.**
- [ ] **Step 5: Detekt + commit** — `feat(core): AnthropicNativeProtocol (presentTools=null, XML-delegate parse, error classification)`.

---

### Task 8: `AnthropicHttpTransport` interface + proxy-aware client

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/anthropic/AnthropicHttpTransport.kt` (interface — mockability, Imp-9)
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/anthropic/AnthropicHttpClient.kt` (impl)
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/anthropic/AnthropicHttpClientTest.kt`

**Interfaces produced:**
```kotlin
interface AnthropicHttpTransport {
    suspend fun postStream(request: AnthropicRequest, onLine: (String) -> Unit): ApiResult<Unit>
}
class AnthropicHttpClient(
    private val baseUrl: String,
    private val apiKey: String,
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 180,
    private val debugDir: java.io.File? = null,
) : AnthropicHttpTransport
```
Builds OkHttp via the `:web` triad (`IdeProxy.selector()`+`proxyAuthenticator()`+`IdeTrust.applyTo`), headers `x-api-key`/`anthropic-version: 2023-06-01`/`content-type: application/json`, POSTs `{baseUrl}/v1/messages`, streams SSE lines, maps HTTP errors via `AnthropicNativeProtocol().classifyHttpError` → `ApiResult.Error(ErrorType, body, retryAfterMs)`, writes a request/response dump to `debugDir` when non-null.

- [ ] **Step 1: Write the failing tests.** The `:web` triad is headless-safe (`IdeTrust.applyTo` no-ops without `CertificateManager`, `IdeProxy.selector()` falls back to `ProxySelector.getDefault()`), so the client constructs headless. Prefer behavioral over brittle source-text (m1):
```kotlin
class AnthropicHttpClientTest {
    @Test fun `constructs headless and is an AnthropicHttpTransport`() {
        val c = AnthropicHttpClient("https://api.anthropic.com", "k")
        assertIs<AnthropicHttpTransport>(c)
    }
    @Test fun `wires proxy-trust triad and anthropic headers`() {
        val src = File("src/main/kotlin/com/workflow/orchestrator/core/ai/anthropic/AnthropicHttpClient.kt").readText()
        listOf("IdeProxy.selector()","IdeProxy.proxyAuthenticator()","IdeTrust.applyTo",
               "x-api-key","anthropic-version").forEach { assertTrue(src.contains(it), "missing $it") }
        listOf("\"temperature\"","\"top_p\"","\"top_k\"").forEach { assertFalse(src.contains(it)) }
    }
}
```
(If `:core` already has `okhttp3.mockwebserver` — check `core/build.gradle.kts` — replace the source-text guard with a `MockWebServer` round-trip asserting recorded request headers + that a 529 maps to an overloaded `ApiResult.Error`.)
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement** per the `:web` pattern (inventory §9). Honor `retry-after` → `retryAfterMs`. Keep isolated from `HttpClientFactory`.
- [ ] **Step 4: Run → pass.**
- [ ] **Step 5: Detekt + commit** — `feat(core): proxy-aware Anthropic HTTP transport (IdeProxy+IdeTrust, x-api-key, SSE, debug dumps)`.

---

### Task 9: `AnthropicDirectBrain`

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ai/AnthropicDirectBrain.kt` (**`:agent`** — image hydration needs `:agent` types; Imp-8)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ai/AnthropicDirectBrainTest.kt`

**Interfaces consumed:** `LlmBrain` (inventory §1), `AnthropicHttpTransport`, `AnthropicRequestMapper`, `AnthropicSseParser`, `AnthropicModelCatalog`, `SessionAttachmentAccess` (sha256 → bytes+mime → base64).
**Interfaces produced (full ctor — Imp-4):**
```kotlin
class AnthropicDirectBrain(
    override val modelId: String,
    private val http: AnthropicHttpTransport,
    private val attachmentAccess: SessionAttachmentAccess,
    private val thinkingEnabled: () -> Boolean,
    private val effort: () -> String,
    override var toolNameSet: Set<String> = emptySet(),
    override var paramNameSet: Set<String> = emptySet(),
) : LlmBrain {
    override var temperature: Double = 0.0
        set(_) {}   // no-op: Anthropic 400s on sampling params (Global Constraints)
}
```

- [ ] **Step 1: Write the failing tests** — all real, MockK-backed (Imp-3), captured request
```kotlin
class AnthropicDirectBrainTest {
    private val http = mockk<AnthropicHttpTransport>()
    private val reqSlot = slot<AnthropicRequest>()
    private fun brain(model: String = "claude-opus-4-8", thinking: Boolean = true) =
        AnthropicDirectBrain(model, http, mockk(relaxed = true), { thinking }, { "high" })
    @BeforeEach fun stub() { coEvery { http.postStream(capture(reqSlot), any()) } returns ApiResult.Success(Unit) }

    @Test fun `temperature setter is a no-op`() {
        val b = brain(); b.temperature = 1.0; assertEquals(0.0, b.temperature)
    }
    @Test fun `null maxTokens falls back to model max output`() = runBlocking {
        brain("claude-sonnet-4-6").chatStream(listOf(ChatMessage("user","x")), null, null) {}
        assertEquals(128_000, reqSlot.captured.maxTokens)
    }
    @Test fun `request carries no sampling params and a thinking block when enabled`() = runBlocking {
        brain(thinking = true).chatStream(listOf(ChatMessage("user","x")), null, 4096) {}
        val json = Json.encodeToString(reqSlot.captured)
        listOf("temperature","top_p","top_k","budget_tokens").forEach { assertFalse(json.contains(it)) }
        assertEquals("summarized", reqSlot.captured.thinking!!.display)
    }
    @Test fun `tool_use streams back as canonical XML through onChunk`() = runBlocking {
        coEvery { http.postStream(any(), any()) } answers {
            val onLine = secondArg<(String) -> Unit>()
            sseToolUseFixture().lines().forEach(onLine)   // reuse Task 6 fixture
            ApiResult.Success(Unit)
        }
        val sb = StringBuilder()
        brain().chatStream(listOf(ChatMessage("user","x")), null, 4096) { c ->
            c.choices.firstOrNull()?.delta?.content?.let(sb::append) }
        assertTrue(sb.contains("<read_file><path>F.kt</path></read_file>"))
    }
}
```
- [ ] **Step 2: Run → fail.** `./gradlew :agent:test --tests "*AnthropicDirectBrainTest*"`
- [ ] **Step 3: Implement** `chatStream`: build the request via `AnthropicRequestMapper` (`maxTokens ?: AnthropicModelCatalog.maxOutput(modelId)`, thinking/effort from the lambdas, images hydrated via `attachmentAccess`), call `http.postStream`, feed lines to `AnthropicSseParser` whose `emitText` wraps each piece into `StreamChunk(choices=[StreamChoice(delta=StreamDelta(content=text))])` and invokes `onChunk`; accumulate a `ChatCompletionResponse`; map a transport `ApiResult.Error` straight through.
- [ ] **Step 4: Run → pass.**
- [ ] **Step 5: Detekt + commit** — `feat(agent): AnthropicDirectBrain (native chatStream, no-op temperature, max_tokens fallback, image hydration)`.

---

### Task 10: `BrainFactory` provider branch (branch-first, `modelOverride`, debug, skip router)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/brain/BrainFactory.kt:25-133` (branch FIRST in `create()`, before the blank-SG guard at `:43-45`)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` — `createBrain` (~795); `wrapBrainWithRouter` call sites (`:1881`,`:2039`); Sourcegraph shared-catalog warm-up (`:1855`)
- Test: `agent/.../BrainFactoryProviderBranchTest.kt` + `agent/.../NativeRouterSkipContractTest.kt`

**Interfaces consumed:** `AgentSettings.State.llmProvider`, `AnthropicDirectBrain`, `AnthropicHttpClient`, `AnthropicModelCatalog`, `CredentialStore.getToken(ServiceType.ANTHROPIC)`.

- [ ] **Step 1: Write the failing tests** (real code — Imp-5)
```kotlin
class BrainFactoryProviderBranchTest {
    @Test fun `returns AnthropicDirectBrain when llmProvider is anthropic`() = runBlocking {
        val brain = brainFactory(project).create(modelOverride = null)   // settings: llmProvider="anthropic", key present
        assertIs<AnthropicDirectBrain>(brain)
    }
    @Test fun `native branch honors modelOverride`() = runBlocking {
        assertEquals("claude-sonnet-4-6", brainFactory(project).create("claude-sonnet-4-6").modelId)
    }
    @Test fun `native branch does not require a Sourcegraph URL`() = runBlocking {
        // ConnectionSettings.sourcegraphUrl = ""  → must NOT throw
        assertIs<AnthropicDirectBrain>(brainFactory(project).create(null))
    }
}
class NativeRouterSkipContractTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()
    @Test fun `wrapBrainWithRouter is guarded by provider`() {
        assertTrue(Regex("llmProvider\\s*==\\s*\"anthropic\"").containsMatchIn(src))
        assertTrue(src.contains("wrapBrainWithRouter") || src.contains("wrapBrainOrSkip"))
    }
}
```
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement.** Make the `llmProvider == "anthropic"` branch the **first statement** in `BrainFactory.create(modelOverride)` (before `:43-45`'s `error(...)`): resolve `modelOverride ?: anthropicModel ?: AnthropicModelCatalog.defaultModel()`; read key via `CredentialStore.getToken(ServiceType.ANTHROPIC)`; `debugDir = sessionDebugDir?.takeIf { agentSettings.state.writeApiDebugDumps }`; construct `AnthropicHttpClient(baseUrl=anthropicApiUrl, apiKey, debugDir=debugDir)` + `AnthropicDirectBrain(...)` (Imp-7). In `AgentService`, extract `wrapBrainOrSkip(rawBrain,…)` returning `rawBrain` unchanged when `llmProvider == "anthropic"`; guard the SG shared-catalog warm-up at `:1855` on `llmProvider != "anthropic"`.
- [ ] **Step 4: Run → pass.**
- [ ] **Step 5: `:agent:detekt --rerun-tasks` + commit** — `feat(agent): BrainFactory native branch (branch-first, modelOverride, debug, skip BrainRouter)`.

---

### Task 11: provider-selected `ToolProtocol` threaded to all prompt-build + dialect-guard sites (C1/I2)

**Why:** `presentTools → null` must hold everywhere a system prompt is built or the dialect guard runs, or native sessions double-present tools (XML system prompt + native `tools:[]`) → the historical mixed-signal drift, compounded by the guard rejecting drifting turns. `AgentService.kt:864` is ONE construction-time `val toolProtocol = XmlToolProtocol()` feeding the orchestrator prompt (`:2221`), the loop facade (`:2459/:2468`), resume redaction (`:2909`); `SubagentRunner.kt:162-163,628` and `MessageStateHandler.kt:47` hardcode their own.

**Files:**
- Modify: `agent/.../AgentService.kt` — resolve the protocol **per task** from `llmProvider` (NOT a stale `@Service` val) and thread the SAME instance to `:2221`, `:2459/:2468`, `:2909`
- Modify: `agent/.../loop/SubagentRunner.kt:162-163,628` — accept a `toolProtocol` ctor param (default `XmlToolProtocol()`); `SpawnAgentTool`/`AgentService` pass the provider-selected one
- Modify: `agent/.../loop/MessageStateHandler.kt:47` — accept the provider-selected protocol (so `requiresDialectGuard=false` on native → dialect chokepoint short-circuits, matching `SystemPrompt.kt:184-185,640-641` WA-1 comments; m2)
- Test: `agent/.../NativeProtocolThreadingContractTest.kt` + `agent/.../SubagentNativePresentToolsTest.kt`

- [ ] **Step 1: Write the failing tests**
```kotlin
class NativeProtocolThreadingContractTest {
    private val agent = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()
    private val sub = File("src/main/kotlin/com/workflow/orchestrator/agent/loop/SubagentRunner.kt").readText()
    @Test fun `agent resolves toolProtocol per provider, not a single hardcoded val`() {
        assertTrue(Regex("AnthropicNativeProtocol\\(\\)").containsMatchIn(agent))
        assertTrue(Regex("llmProvider\\s*==\\s*\"anthropic\"").containsMatchIn(agent))
    }
    @Test fun `SubagentRunner accepts an injected toolProtocol`() {
        assertTrue(sub.contains("toolProtocol") && sub.contains("ToolProtocol"))
    }
}
class SubagentNativePresentToolsTest {
    @Test fun `native protocol presentTools returns null so sub-agent prompt omits XML tool docs`() {
        assertNull(AnthropicNativeProtocol().presentTools(emptyList()))
    }
}
```
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement.** Compute `val activeToolProtocol = if (llmProvider == "anthropic") AnthropicNativeProtocol() else XmlToolProtocol()` per task (next to the per-task brain) and pass that instance to `:2221` (`activeToolProtocol.presentTools(defs)`), the loop/`XmlLlmProvider` facade (`:2459/:2468`), resume redaction (`:2909`), and into `SubagentRunner` + `MessageStateHandler` via new ctor params (default `XmlToolProtocol()` to preserve existing callers). `SpawnAgentTool` threads the provider-selected protocol when it constructs `SubagentRunner`.
- [ ] **Step 4: Run → pass.** `./gradlew :agent:test --tests "*Protocol*" --tests "*Subagent*"`.
- [ ] **Step 5: `:agent:detekt --rerun-tasks` + commit** — `feat(agent): thread provider-selected ToolProtocol to orchestrator+loop+resume+subagent+statehandler (native presentTools=null)`.

---

### Task 12: provider-branch wiring (sub-agent model, OFFLINE probe, L2 fallback, model advertisement)

**Files:**
- Modify: `agent/.../tools/builtin/SpawnAgentTool.kt:694` (sub-agent default model) + param doc `:215-218`
- Modify: `agent/.../AgentService.kt:2605-2606` (llmProbeUrl), `:1941-1943` (fallback chain), `:2163`/`:3143` (`formatModelsForPrompt`/`availableModels` — I4)
- Test: `agent/.../ProviderBranchWiringTest.kt`

- [ ] **Step 1: Write the failing tests** (real code — Imp-6)
```kotlin
class ProviderBranchWiringTest {
    private val spawn = File("src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt").readText()
    private val agent = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()
    @Test fun `subagent default model uses anthropic catalog on native`() {
        assertTrue(spawn.contains("AnthropicModelCatalog.defaultSubagentModel()"))
        assertTrue(Regex("llmProvider\\s*==\\s*\"anthropic\"").containsMatchIn(spawn))
    }
    @Test fun `llmProbeUrl branches to anthropicApiUrl on native`() {
        assertTrue(agent.contains("anthropicApiUrl")); assertTrue(agent.contains("llmProbeUrl"))
    }
    @Test fun `L2 fallback chain uses anthropic catalog on native`() {
        assertTrue(agent.contains("AnthropicModelCatalog.fallbackChain()"))
    }
    @Test fun `availableModels does not feed Sourcegraph cache on native`() {
        assertTrue(Regex("llmProvider\\s*==\\s*\"anthropic\"[\\s\\S]{0,400}(AnthropicModelCatalog|MODELS)").containsMatchIn(agent))
    }
}
```
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement** the four provider branches (C2 sub-agent model / I1 probe / I2 chain / I4 availableModels). Update the `SpawnAgentTool` `model`-param description to show the bare Anthropic ID form alongside the Sourcegraph form.
- [ ] **Step 4: Run → pass.**
- [ ] **Step 5: `:agent:detekt --rerun-tasks` + commit** — `feat(agent): provider-branch wiring (subagent model, OFFLINE probe, L2 chain, availableModels)`.

---

### Task 13: settings UI + in-chat model picker reconciliation

**Files:**
- Modify: the AI-Agent configurable (`agent/.../settings/…Configurable.kt`) — "LLM Provider" dropdown (`sourcegraph`|`anthropic`), Anthropic API-key field (`CredentialStore.storeToken(ServiceType.ANTHROPIC, …)`), model dropdown (`AnthropicModelCatalog.MODELS`), effort dropdown, thinking toggle, base-URL field
- Modify: the in-chat top-bar picker path (`AgentController.loadModelList`) — on native, source from `AnthropicModelCatalog.MODELS` and drive `anthropicModel` (I4)
- Test: `agent/.../AnthropicConfigurableApplyTest.kt` (TDD required per Minor-5)

- [ ] **Step 1: Write the failing test** — `apply()` persists the new fields
```kotlin
class AnthropicConfigurableApplyTest {
    @Test fun `apply persists provider model and effort`() {
        val c = anthropicConfigurable(project)
        c.setProvider("anthropic"); c.setModel("claude-sonnet-4-6"); c.setEffort("xhigh"); c.apply()
        val s = AgentSettings.getInstance(project).state
        assertEquals("anthropic", s.llmProvider)
        assertEquals("claude-sonnet-4-6", s.anthropicModel)
        assertEquals("xhigh", s.anthropicEffort)
    }
}
```
- [ ] **Step 2: Run → fail.**
- [ ] **Step 3: Implement** the controls (Configurable+DialogPanel pattern: hold the `DialogPanel` ref; delegate `isModified`/`apply`/`reset`), and reconcile the in-chat picker to read/write `anthropicModel` on native.
- [ ] **Step 4: Run → pass; manual UI smoke deferred to Task 15.**
- [ ] **Step 5: Commit** — `feat(agent): settings UI + in-chat picker for Anthropic provider`.

---

### Task 14: docs

**Files:**
- Modify: `agent/CLAUDE.md` ("## LLM API" — dual provider: Sourcegraph XML-in-content vs Anthropic-direct native; the Option-A note; the per-task provider-selected `ToolProtocol`)
- Modify: `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (append `## 25. Phase 4a resolved` — provider exclusivity, §5.7 branch points, the protocol-threading site list)

- [ ] **Step 1–2:** Document. Note the spec was review-corrected (literal `"anthropic"`, `AnthropicNativeProtocol` not `XmlToolProtocol`, `anthropicApiUrl`). **Step 3: Commit** — `docs(plugin-split): record Phase 4a (§25) + agent/CLAUDE.md dual-provider`.

---

### Task 15: gate + live smoke

- [ ] `./gradlew :core:test :agent:test` green.
- [ ] `./gradlew verifyPlugin -x buildSearchableOptions` green; `koverVerify -Pcoverage -x uiTest` (retry #51 flakes).
- [ ] **Live smoke (manual, gated; no key in CI):** real Anthropic key in Settings + `llmProvider=anthropic` + blank Sourcegraph URL — run the agent: confirm a text turn, a tool call that executes, **a spawned sub-agent (verify its prompt has NO XML tool docs — C1)**, thinking renders live, a pasted image is described, and the context-window/`UsageIndicator` shows ~1M not 90K (C2). **Probe §3/A3:** send one request with `temperature` set and record whether Opus 4.8 400s (informs whether the no-op setter is strictly required).
- [ ] Whole-phase opus review (cross-task): provider exclusivity — no Sourcegraph URL/`ModelCache`/`::`-ref leaks on native; Option-A round-trip + collision guard hold; protocol threaded to ALL five sites; no sampling params anywhere; `:core` JUnit5 purity. Target 0 Critical / 0 Important. Triage the carry-forward Minors below.

---

## Self-Review (against spec v2, review-corrected)

- **Spec coverage:** §5.1 protocol→T7 + threading T11; §5.2 brain→T9 (+SSE T6, mapper T4, serializer T5); §5.3 thinking/caching→T4; §5.4 tool-def conversion→T4 (no widening — `tools` param already exists, inventory §1/§14); §5.5 images/skip-router→T9+T10; §5.6 proxy client→T8, catalog→T2, **catalog wiring→T3**; §5.7 C2/I1/I2/I4→T12, M1/temperature→T9, M2 debug→T8+T10, **C1 double-presentation→T11**, **C2 context-window→T3**, **C3 modelOverride/branch-first→T10**; §5.8 settings/factory→T1+T10+T13; docs→T14; gate/smoke→T15.
- **Placeholder scan:** Tasks 5/6/9/10/11/12/13 carry real test code (Imp-2/3/5/6/Minor-5 fixed). T9 ctor fully specified (Imp-4). Transport extracted as an interface for mockability (Imp-9). Brain committed to `:agent` (Imp-8). Remaining "verify at implementation" notes (T3 `ModelCatalogService` method set; T5 `ToolUseContent` field name vs inventory §8; T7 `NativeProtocol` signatures; T8 MockWebServer-if-available) are inventory-pinned reads, not silent TODOs.
- **Type consistency:** `AnthropicModelCatalog.{maxOutput,contextWindow,defaultModel,defaultSubagentModel,fallbackChain}`, `AnthropicModelCatalogService.getContextWindow`, `AnthropicRequestMapper.build(...)` (imageBytes `(String)->Pair<String,String>?`), `ToolUseXmlSerializer.toXml`, `AnthropicSseParser.parse`, `AnthropicNativeProtocol`, `AnthropicHttpTransport.postStream`, `AnthropicDirectBrain` ctor — consistent across tasks. `LlmBrain.chatStream`/`ChatMessage`/`ToolDefinition` match inventory exactly.
- **Literal:** `"anthropic"` everywhere (Imp-1/I6 resolved; spec corrected).

## Carry-forward Minors (for the Task 15 whole-phase review)
- m1: source-text contract tests are brittle — prefer behavioral assertions where a `MockWebServer`/headless construct exists (applied to T8; extend to T10/T11/T12 if a behavioral hook is cheap).
- m3: native sub-agent API-debug dumps — `SubagentRunner.kt:264` gates on `brain is OpenAiCompatBrain`; pass `debugDir` to the native brain there too, or document the sub-agent parity gap.
- Minor-2: spec says `getDefaultSubagentModel()`; plan uses idiomatic `defaultSubagentModel()` — intentional.

## Review provenance
Built from `.superpowers/phase4/plan-signatures.md` (14 verified signature groups). Folded `.superpowers/phase4/plan-review-skeptic.md` (C1/C2/C3 + I1–I6 + m1–m5) and `.superpowers/phase4/plan-review-completeness.md` (Crit-1 + Imp-1–9). Spec corrected in the same pass (§5.1, §5.6, §5.7, §5.8, line-26 note). Goes to SDD with a whole-phase opus review at the end.
