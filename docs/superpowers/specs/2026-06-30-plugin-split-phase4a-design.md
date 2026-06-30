# Plugin Split — Phase 4a: Native Anthropic-Direct LLM Provider

**Date:** 2026-06-30
**Branch:** `feature/plugin-split`
**Spec version:** v2 — revised after three independent reviews (skeptic/opus, completeness/sonnet, accuracy/sonnet). The §5.2 fork is resolved (**Option A**), two factual errors fixed, and a provider-branch checklist (§5.7) + three undisclosed regressions added. Review provenance in §12.
**Extends:** `docs/superpowers/specs/2026-06-22-plugin-split-design.md` §6 (LLM pluggability) + §11 row 4. Decomposes the design's "Phase 4" into **4a (ship the native provider — this spec)** + **4b (seam consolidation — deferred)**.
**Predecessor:** Phase 0b-1 built the seam SHAPE (`core/ai/protocol/{ToolProtocol,XmlToolProtocol,NativeProtocol}.kt`, `core/ai/LlmProvider.kt`, `agent/loop/XmlLlmProvider.kt`, `requiresDialectGuard` gating, `ApiMessage.protocol: String? = null`).
**Exploration maps:** `.superpowers/phase4/explore-{seam,transport,persistence}.md`.

---

## 1. Goal

Add a **native Anthropic-direct LLM provider** so the agent runs against `api.anthropic.com` with a user-supplied API key, instead of *requiring* Sourcegraph Enterprise. This is the design's critical path — what makes an open-source plugin usable without corporate Sourcegraph — and it delivers, as side effects, reliable tool calls (native function-calling has no dialect-drift surface) and native thinking/effort.

Ships **A + B privately** (internal-first). Bedrock/Vertex and the OSS publish are later efforts.

---

## 2. The Phase-4a approach (Option A — resolved by review)

**4a uses the "native wire, XML-internal" design.** `AnthropicDirectBrain` calls Anthropic natively (top-level `system` field, `tools:[]`, structured `tool_use`/`tool_result`, adaptive thinking + effort, `x-api-key`, proxy-aware OkHttp), and **deterministically serializes the model's structured `tool_use` into the canonical XML-in-text representation at stream end**, so `AssistantMessageParser`, persistence (`ApiMessage` XML-in-`Text`), `ContextManager`, and the dialect-drift machinery all work **unchanged**.

Why Option A over the structured `NativeProtocol` path for 4a (skeptic review, verified against source):
- It delivers 100% of §1's user value. The XML never round-trips *through* the model — the brain decodes structured `tool_use` JSON and serializes canonical XML once — so there is **no drift surface** despite using the XML internal form.
- It makes the persistence back-compat risk (the design's sharpest) **literally zero**: nothing on disk changes (`ApiMessage.protocol` stays `null`, `toChatMessage` keeps its existing XML render at `ApiMessage.kt:114`, `toolCalls` stays `null`), so 4a drops the discriminator branch, the `toApiMessage` change, and the mixed-session round-trip tests — ~a third of the surface, the riskiest third. The drift machinery stays armed-but-harmless (canonical XML never trips it). **Correction (skeptic review):** 4a does NOT escape protocol threading — `presentTools → null` must be threaded to the prompt-build + dialect-guard sites (incl. `SubagentRunner`/`MessageStateHandler`) so the wire `tools:[]` field isn't doubled by XML tool docs in the prompt (§5.1). What 4a drops is the *persistence-format* threading, not the *prompt-presentation* threading.
- Deferring 4b is structurally safe (verified): `AgentLoop.brain` is a reassignable `private var brain: LlmBrain` (`AgentLoop.kt:125`); `toolProtocol` is a separate never-reassigned `val` (`:130`); provider selection is **exclusive** at `BrainFactory.create()`, so a native brain never coexists with `BrainRouter` (subject to §5.5).

**4b (deferred)** promotes 4a to the structured `NativeProtocol` path (persist native `tool_use` with `protocol="native"`, branch `toChatMessage`) and does the seam consolidations (dissolve `BrainRouter`, widen `brain → LlmProvider`, relocate `toolNameSet`/`paramNameSet`). None adds native capability.

Option A's one real cost — serialization fidelity from structured `tool_use.input` (arbitrary JSON) to the flat-string XML param shape `AssistantMessageParser` expects — is pinned by a golden test (§8). The agent's tools were designed for that flat dialect, so it round-trips; the test guards it.

### Out of scope — deferred to Phase 4b
Structured `NativeProtocol`/discriminator/migration; `BrainRouter` dissolution; `brain → LlmProvider`; `toolNameSet`/`paramNameSet` relocation; multi-breakpoint conversation-prefix prompt caching.

### Out of scope — entirely (this program)
Bedrock/Vertex transports (SigV4/OAuth2 + their own HTTP clients that bypass `IdeProxy`/`IdeTrust`).

### Accepted regressions/limitations on the native path (USER-confirmed 2026-06-29 for the first; the rest disclosed by review)
- **Live streaming edit previews** — native has no XML tags mid-stream, so the `edit_file` diff appears at tool-call completion (via the approval card), not as a live-growing diff. (XML/Sourcegraph path unchanged.)
- **Thinking display** — handled, not regressed: the brain sets `display:"summarized"` (§5.3) so the live `ThinkingView` doesn't go dark.

---

## 3. The Anthropic Messages API surface (verified against live docs 2026-06-30)

- **Endpoint:** `POST https://api.anthropic.com/v1/messages` (base URL configurable).
- **Auth/headers:** `x-api-key: <key>`, `anthropic-version: 2023-06-01`, `content-type: application/json`. (Fits the static-header `Credential` model.)
- **System prompt:** top-level `system` field (string | `[{type:"text",text,cache_control?}]`), NOT a `system`-role message — replaces the Sourcegraph `sanitizeMessages()` system→user coercion on the native path.
- **Messages:** strict `user`/`assistant` alternation; assistant `tool_use` blocks `{type,id,name,input}`; tool results as a `user` message of `{type:"tool_result",tool_use_id,content,is_error?}` (1:1 with the existing structured `ContentBlock.ToolResult`).
- **Tools:** `tools:[{name,description,input_schema}]` (JSON Schema); `stop_reason:"tool_use"`. (Tools go in this field — `presentTools` returns null — so the brain must receive the structured defs; §5.4.)
- **Images:** inline `image` content blocks (base64 source) in the **same** `/v1/messages` endpoint — no separate vision endpoint (simpler than Sourcegraph's dual-endpoint `BrainRouter` routing).
- **Streaming SSE:** `message_start` → `content_block_start` (`content_block.type` ∈ `text`|`thinking`|`tool_use{id,name}`) → `content_block_delta` (`delta.type` ∈ `text_delta`|`thinking_delta`|`input_json_delta`|`signature_delta`) → `content_block_stop` → `message_delta` (`stop_reason` + cumulative `usage.output_tokens`) → `message_stop`. Tool inputs arrive as `input_json_delta` fragments concatenating into `tool_use.input`. In-stream errors arrive as an `event: error` frame.
- **Thinking/effort (Opus 4.8/4.7):** `thinking:{type:"adaptive", display?}` + `output_config:{effort:"low".."max"|"xhigh"}` (GA, no beta header; default effort `high`). `budget_tokens` 400s. **`display` default is model-specific: `"omitted"` on Opus 4.8/4.7/Fable 5 (empty thinking text), `"summarized"` on Opus 4.6/Sonnet 4.6** — so the brain must send `display:"summarized"` explicitly (§5.3).
- **Models / context / output (verified 2026-06-30):** `claude-opus-4-8` (1M ctx, **128K** out, $5/$25, default), `claude-sonnet-4-6` (1M, **128K** out), `claude-haiku-4-5`/`-20251001` (200K, 64K), `claude-fable-5` (1M, 128K; always-on thinking — out of scope as a 4a default). Exact ID strings, no date suffix (except haiku's). Optional live capability via `GET /v1/models`.
- **Errors:** `error.type` — `429 rate_limit_error` (+`retry-after`), `529 overloaded_error`, `413 request_too_large`, `400 invalid_request_error`, `401`/`403`.
- **Sampling params:** `budget_tokens` is a confirmed 400 on Opus 4.8/4.7. Whether `temperature`/`top_p`/`top_k` 400 is **not confirmed in live docs** (the `claude-api` reference asserts it; the model page doesn't) — **verify at implementation with a probe**. Regardless, the native request omits all sampling params (§5.3) and the native brain's `temperature` setter is a no-op (§5.7-T), so this is safe either way.
- **Prompt caching:** `cache_control:{type:"ephemeral"}` — a single breakpoint on the system block is in 4a (§5.3); the prefix-match minimum (4096 tokens on Opus 4.8) is exceeded by the tool-def block alone.

---

## 4. Current-state findings the design honors (from the three exploration maps + review)

- **Tool results already persist as structured `ContentBlock.ToolResult`** under `ApiRole.USER`; the `"TOOL RESULT:\n"` prefix is wire-time only (`SourcegraphChatClient.sanitizeMessages`). Under Option A nothing changes here.
- **`ContentBlock.ToolUse` is NOT `@Deprecated`** (corrected in v2). The `@Deprecated` is on `ContentBlock.Image` (`ApiMessage.kt:34`); `ToolUse` (`:19`) is a live, writable, already-read type — no annotation bookkeeping needed. (v1's "re-scope the annotation" was a phantom task; deleted.)
- **All current LLM clients bypass `IdeProxy`/`IdeTrust`** (raw OkHttp) → they silently fail TLS on SSL-inspection networks / are unreachable on proxy-only networks. `:web` (`WebFetchServiceImpl:64-68`, `WebSearchServiceImpl:67-71`) wires the triad `IdeProxy.selector()` + `IdeProxy.proxyAuthenticator()` + `IdeTrust.applyTo()` — copy it. A correct native client is *more* correct than the Sourcegraph path.
- **`classifyStreamLine` is a dead letter** — `SourcegraphChatClient` calls `GatewayErrorDetector` directly; the native SSE reader (new code) must call `classifyStreamLine`/`classifyHttpError` explicitly.
- **`ModelCache.getModels()` hits a Sourcegraph endpoint** (`/.api/llm/models`); `SharedCatalogHolder` is keyed on `sgUrl` (returns null for Anthropic) — bypass for anthropic-direct; use a static catalog.
- **A new `AnthropicDirectBrain implements LlmBrain` slots into the 9 `toolNameSet`/`paramNameSet` call-sites with zero churn** (per-instance fields) — which is why 4b's reroute can be deferred.

---

## 5. Design (Phase 4a)

### 5.1 `AnthropicNativeProtocol : NativeProtocol`
Under Option A the brain renders to XML internally, so 4a does **not** need a *structured* parse path. But the native brain **cannot** pair with `XmlToolProtocol` (corrected by skeptic review): `XmlToolProtocol.presentTools` returns the full XML tool-doc block, which would inject tool schemas into the system prompt **at the same time** the native brain sends them in the wire `tools:[]` field — re-creating the mixed-signal dialect drift this design exists to kill. So 4a ships **`AnthropicNativeProtocol`** with `presentTools → null` (tools go only on the wire), `requiresDialectGuard = false`, and a `parseToolCalls` that **delegates to `AssistantMessageParser.parse`** (the brain has already rendered canonical XML into the text — so it is XML parsing, which relaxes the `NativeProtocol` "consumes structured deltas" doc invariant for the Option-A mode). `classifyStreamLine`/`classifyHttpError` are implemented and called by the brain's SSE/HTTP layer (§5.6). The *structured* `parseToolCalls` (consuming `content_block_delta` directly) is the only piece deferred to 4b.

**Protocol threading is required — not skippable.** `presentTools → null` must hold at **every** site that builds a system prompt or runs the dialect guard on the native path: the orchestrator field `AgentService.toolProtocol` (`:864`, feeding prompt injection `:2221`, the loop/`XmlLlmProvider` facade `:2459/:2468`, and resume redaction `:2909`), **and `SubagentRunner` (`:162-163`,`:628`) + `MessageStateHandler` (`:47`)** — because sub-agents run the native brain too, and leaving them on `XmlToolProtocol` double-presents tools to every sub-agent. A single provider-selected `ToolProtocol` instance is resolved **per task** (alongside the per-task brain, since the project `@Service` field would otherwise go stale when the user switches provider) and threaded to all of them.

### 5.2 `AnthropicDirectBrain : LlmBrain`
The wire client.
1. **History → Anthropic request:** system prompt → top-level `system` field; user/assistant turns with strict alternation; `ContentBlock.ToolResult` → `tool_result` blocks; prior assistant XML-tool-call turns sent as text context.
2. **Tools → `tools:[{name,description,input_schema}]`** — convert `ToolDefinition` (§5.4).
3. **Request params:** `model`, `max_tokens` (§5.7-M), `thinking:{type:"adaptive",display:"summarized"}` + `output_config:{effort}` when thinking enabled. NEVER `temperature`/`top_p`/`top_k`/`budget_tokens`.
4. **SSE parse:** emit `text_delta` through the existing `StreamChunk` text path; **wrap `thinking_delta` frames in `<thinking>…</thinking>` synthetic text** so the existing `ThinkingTagSplitter`/`ThinkingView` pipeline renders them (§5.3); accumulate `tool_use` from `content_block_start`+`input_json_delta` and **serialize the completed calls into canonical XML appended to the assistant text at `message_stop`** (Option A — this is the accepted streaming-edit-preview regression: the XML materializes at completion, not live).
5. **Images:** hydrate `AttachmentStore` bytes (via `SessionAttachmentAccess`) into Anthropic `image` content blocks in-request (§5.5).
6. **Proxy-aware OkHttp** (§5.6); per-instance `toolNameSet`/`paramNameSet`.

### 5.3 Thinking + caching
- Send `thinking:{type:"adaptive", display:"summarized"}` (not the `"omitted"` default) when thinking is enabled, so the live `ThinkingView` shows reasoning instead of a silent pause; the brain wraps native `thinking_delta` text in `<thinking>` tags for `ThinkingTagSplitter`.
- Put one `cache_control:{type:"ephemeral"}` breakpoint on the system block (the frozen 11-section prompt + ~16.4K tool-def tokens) — ~8× cheaper on the prefix over a long session; the env-details/time are appended in *user* turns so the prefix is cache-stable. Multi-breakpoint conversation caching → 4b.

### 5.4 Tool-definition conversion
`presentTools` returns null for native, so thread the per-iteration `List<ToolDefinition>` to the brain (a brain-call param or per-iteration provider — the minimal 4a interface widening; 4b's `brain → LlmProvider` subsumes it). Conversion: unwrap the OpenAI-compat wrapper (`ToolDefinition.function.{name,description,parameters}` → Anthropic `{name,description,input_schema}`); emit `{"type":"array","items":{…}}` from `ParameterProperty.items` for array params. Current tools are flat (string/int/array-of-string) so no nested-object support is needed (`ParameterProperty` has no nested `properties` field) — note it as a known limitation.

### 5.5 Images / `wrapBrainWithRouter`
`AgentService.wrapBrainWithRouter` (`AgentService.kt:923`) is called unconditionally (`:1881,:2039`) and `BrainRouter` routes image turns to the Sourcegraph completions endpoint — so a native brain wrapped by the router would silently route images to a blank Sourcegraph URL and fail. **For anthropic-direct, skip `wrapBrainWithRouter`** and have `AnthropicDirectBrain` handle images directly (§5.2.5). The Sourcegraph step-1 image-analysis badge (`onAnalyzedImageBadge`) is a Sourcegraph artifact and N/A natively (images go inline). Pinned by a source-text contract test.

### 5.6 Proxy-aware HTTP + catalog
- Native OkHttp built through `IdeProxy.selector()` + `IdeProxy.proxyAuthenticator()` + `IdeTrust.applyTo()` (the `:web` triad) — required and a correctness win. Isolated from `HttpClientFactory` (agent LLM path stays separate) but with the proxy/trust triad explicit.
- `AnthropicModelCatalog` — a **dead-simple static map** of the 4–5 known models → context window / capabilities / status, keyed on the bare model id (no holder refactor). Bypass `ModelCache.getModels()` when provider == anthropic.
  - **Wiring is required (C2, skeptic review):** the catalog must be exposed as a `core.ai.ModelCatalogService` (an adapter implementing `getContextWindow(id)` over the static map) and **selected by `AgentService` when `llmProvider=="anthropic"`** for both `modelCatalogService` (`newContextManager` `:896-901`, executeTask `:2054`/`:2574`) and `effectiveContextWindow.windowLookup` (`:906`). Otherwise `getSharedModelCatalog()` returns **null** on a blank Sourcegraph URL → `ContextManager.effectiveMaxInputTokens()` falls back to `FALLBACK_MAX_INPUT_TOKENS = 90_000` and `claude-opus-4-8` (1M) compacts at ~79K — an ~11× premature-compaction regression that also corrupts the `UsageIndicator`/model-picker capacity strip.

### 5.7 Provider-branch checklist (the wiring the seam doesn't yet cover — completeness review)
Each is a "switching provider silently breaks adjacent machinery" bug; all survive Option A and must be in 4a:
- **(Sub-agent model) C2:** `SpawnAgentTool.kt:694` calls `ModelCache.pickSonnetNonThinking(...)?.id` → a Sourcegraph-formatted ref (`anthropic::…::…`) that 400s on `api.anthropic.com`. When anthropic-direct, use a static Anthropic default (`claude-sonnet-4-6`, via `AnthropicModelCatalog.getDefaultSubagentModel()`); update the `SpawnAgentTool` `model`-param description to show the bare-ID form.
- **(OFFLINE probe) I1:** `llmProbeUrl` is hardcoded to `sourcegraphUrl` (`AgentService.kt:2605`). Branch on provider → `ConnectionSettings.anthropicApiUrl` so the OFFLINE fail-fast probes the right host (else it's disabled/wrong on native).
- **(L2 fallback chain) I2:** `NetworkRecoveryPolicy.resolveFallbackChain` builds from `ModelCache.buildFallbackChain` (Sourcegraph) at `AgentService.kt:1976-1984`. When anthropic-direct, build a static Anthropic chain (`["claude-opus-4-8","claude-sonnet-4-6"]`) from the catalog and have the recycle `brainFactory` lambda branch on provider — or explicitly disable L2 (single-model chain) for 4a, documented. L1-recycle (same-model fresh socket) works unchanged.
- **(max_tokens) M1:** Anthropic requires `max_tokens`. `AnthropicDirectBrain` uses `maxTokens ?: modelDefaultOutputTokens` (from the catalog) so a null from a sub-agent/caller doesn't 400.
- **(temperature setter) T:** `AgentLoop.kt:1767` writes `brain.temperature = 1.0` on the empty-response retry → would 400 if forwarded. The native brain's `temperature` setter is a **no-op** (the empty-retry then just re-sends, which is fine).
- **(API-debug parity) M2:** wire `writeApiDebugDumps` tracing into the native client (the Sourcegraph path uses `RawApiTraceInterceptor`) so enabling dumps captures native calls. The native brain takes a `debugDir: File?` derived from `agentSettings.state.writeApiDebugDumps` at construction (the Sourcegraph-only `setApiDebugDir` is not on `LlmBrain`); the **sub-agent** debug path (`SubagentRunner.kt:264` gated on `brain is OpenAiCompatBrain`) must also pass `debugDir` to the native brain or the parity gap extends to sub-agents.
- **(model advertisement) I4:** two more Sourcegraph leaks beyond C2/I1/I2 — `formatModelsForPrompt(ModelCache.getCached())` (`AgentService.kt:2163,:3143`) feeds `SystemPrompt.availableModels` (stale/empty on native), and the in-chat top-bar picker (`AgentController.loadModelList` → `getSharedModelCatalog()`, null on blank `sgUrl`) writes `sourcegraphChatModel` while the native branch reads `anthropicModel`. Branch both on provider; the picker must drive `anthropicModel` on native.

### 5.8 Settings + factory
- **Canonical provider literal — `"anthropic"`** (NOT `"anthropic-direct"`; corrected by review for the plan/inventory). Every provider branch (`llmProvider == "anthropic"`) and field name below uses this exact value; one mismatched literal silently routes through the Sourcegraph path.
- `ConnectionSettings` — `anthropicApiUrl` (default `https://api.anthropic.com`); plus a `DefaultWorkflowConfig.baseUrl(ServiceType.ANTHROPIC) -> state.anthropicApiUrl` arm (the `when` is exhaustive — a missing arm is a compile error, and `CredentialStore.getToken(ANTHROPIC)` resolves its cache key through it).
- `AgentSettings` — `llmProvider: String = "sourcegraph"` (`"sourcegraph"`|`"anthropic"`), `anthropicModel: String? = "claude-opus-4-8"`, `anthropicEffort: String? = "high"`, `anthropicThinkingEnabled: Boolean`.
- `CredentialStore` — new `ServiceType.ANTHROPIC` (`x-api-key`); never in XML.
- `BrainFactory.create(modelOverride)` — **the provider branch is the FIRST statement in `create()`**, before the `if (sgUrl.isBlank()) error(...)` guard (`:43-45`) that would otherwise crash the headline "no Sourcegraph" DoD. anthropic branch: resolve `modelOverride ?: anthropicModel ?: AnthropicModelCatalog.defaultModel()` (dropping `modelOverride` would kill sub-agent-tier + recycle/escalation selection), construct `AnthropicDirectBrain`; the loop pairs with `AnthropicNativeProtocol` (§5.1, threaded per §5.1's site list) + the `AnthropicModelCatalog`-backed `ModelCatalogService` (§5.6); the caller skips `wrapBrainWithRouter` (§5.5) and skips the Sourcegraph shared-catalog warm-up. Provider/model/effort/thinking + the key live in Settings → Tools → Workflow Orchestrator → AI Agent (the **in-chat model picker** must also reconcile with `anthropicModel`, not only `sourcegraphChatModel` — I4).

---

## 6. Files touched (anticipated)

| Area | Files |
|---|---|
| Brain + DTOs + SSE | `agent/.../ai/AnthropicDirectBrain.kt` (new) + Anthropic request/response DTOs + SSE parser |
| Protocol | `core/ai/protocol/AnthropicNativeProtocol.kt` (new — `classifyStreamLine`/`classifyHttpError`; structured parse → 4b) |
| HTTP | proxy-aware OkHttp builder (reuse `:web` triad) |
| Catalog | `core/ai/AnthropicModelCatalog.kt` (new, static) + bypass `ModelCache.getModels()` for native |
| Factory/wiring | `brain/BrainFactory.kt` (provider branch); skip `wrapBrainWithRouter` for native; tool-def threading; `SpawnAgentTool` default-model branch (C2) + param-doc; `AgentService` `llmProbeUrl` (I1) + fallback-chain (I2) branches |
| Settings | `ConnectionSettings`, `AgentSettings`, `CredentialStore`/`ServiceType`, settings UI |
| Docs | `agent/CLAUDE.md` (dual-provider LLM API), main design-doc §25 note |

No persistence-format change (Option A). No `:plugin-b` change.

---

## 7. Testing strategy
- **Pure unit (JUnit5+MockK):** history→Anthropic request mapping (system-field, alternation, tool_result, **no-sampling-params**, image blocks); SSE parser (golden Anthropic SSE → text/thinking/tool_use accumulation; thinking-delta→`<thinking>` wrap); **the Option-A round-trip — structured `tool_use` → canonical XML → `AssistantMessageParser` → identical `ToolUseContent`** (the fidelity pin); `AnthropicNativeProtocol` classify; `AnthropicModelCatalog` (incl. 128K Sonnet); `BrainFactory` provider branch + default-subagent-model.
- **Source-text/contract:** native client wires `IdeProxy`+`IdeTrust`; native path skips `wrapBrainWithRouter`; request builder never emits sampling params; native `temperature` setter is a no-op; `llmProbeUrl`/fallback-chain branch on provider.
- **Live smoke (manual/gated):** one real `api.anthropic.com` call (text, a tool call, thinking, an image) behind a runIde/manual gate (no key in CI); **probe whether `temperature` 400s on Opus 4.8** (A3).
- **Gate:** `:agent:test` + `:core:test` (pure JUnit5), `verifyPlugin` (`-x buildSearchableOptions` on macOS), `koverVerify -Pcoverage -x uiTest`.

---

## 8. Risks & mitigations
- **Option-A serialization fidelity** — structured `tool_use.input` → flat XML param shape; pinned by the §7 round-trip golden test.
- **Sourcegraph path unchanged** — Option A touches no persistence/parse path used by the Sourcegraph brain; existing snapshots/tests stay green (regression guard).
- **Sampling-param 400** — no-op `temperature` setter + no-sampling request builder (§5.7-T); A3 probed at impl.
- **Provider-branch misses** (the C2/I1/I2 class) — each pinned by a §7 contract test; the §5.7 checklist is the gate's reference list.
- **`classifyStreamLine` not auto-invoked** — the native SSE reader calls it explicitly; pinned by a parse test.
- **API drift** — model IDs/thinking/effort/streaming verified against live docs at impl, not memory; Sonnet 4.6 = 128K out (v1 said 64K).
- **Build-cache trap** — `suspend`-signature changes run `--no-build-cache`; macOS config-cache → `gradlew --stop`.

---

## 9. Definition of Done
- A user selects **anthropic-direct** + key + model/effort in Settings and runs the agent end-to-end against `api.anthropic.com` — text, tool calls, thinking, **and a pasted image** — with NO Sourcegraph configured, NO 400s.
- The native client traverses `IdeProxy`/`IdeTrust`; requests never carry sampling params; sub-agents/recovery/offline-probe all use the Anthropic provider (no Sourcegraph model refs leak); thinking renders live (`display:"summarized"`); one system-prompt cache breakpoint is set.
- Persistence format is **unchanged**; Sourcegraph path byte-for-byte unchanged (snapshots/tests green).
- Full gate GREEN; whole-phase review 0 Critical / 0 Important.
- `agent/CLAUDE.md` (dual-provider) + main design-doc §25 updated in the implementing commits.

---

## 10. Out-of-4a → 4b (recorded for the next phase)
Structured `NativeProtocol` path (persist native `tool_use` `protocol="native"`; `toChatMessage` branch; thread native `toolProtocol` to `MessageStateHandler`/`SubagentRunner`; bulk-rewrite paths must copy per-message `protocol` verbatim — the skeptic's Critical that Option A defers); `BrainRouter` dissolution into a `SourcegraphProvider`; `brain → LlmProvider` widening; `toolNameSet`/`paramNameSet` relocation; multi-breakpoint conversation-prefix caching.

---

## 11. Process (standing rule)
This spec → user review → `writing-plans` → opus plan reviews → SDD → whole-phase review → gate. Commit on `feature/plugin-split`; **no `Co-Authored-By` / "Generated with" trailer.**

---

## 12. Review provenance (v2)
- **Skeptic (opus)** — SOUND-WITH-CAVEATS. Resolved the §5.2 fork to **Option A** (verified persistence-risk → zero, 4b-deferral structurally safe). Caught: the `temperature`-400 setter hazard (`AgentLoop.kt:1767`); `ContentBlock.ToolUse` is NOT `@Deprecated` (it's `Image`); thinking `display:"omitted"` silent pause + `thinking_delta`≠`<thinking>` mapping; silent vision drop; quantified prompt-caching cost (~8×).
- **Completeness (sonnet)** — MATERIAL-GAPS. The provider-branch checklist (§5.7): `wrapBrainWithRouter`/image routing (C1), sub-agent default model (C2), OFFLINE probe URL (I1), L2 fallback chain (I2), `max_tokens` null (M1), API-debug parity (M2), tool-conversion array/items + OpenAI-compat unwrap (M3).
- **Accuracy (sonnet)** — MINOR-CORRECTIONS. Anthropic API + seam claims confirmed except: **Sonnet 4.6 max output = 128K, not 64K** (A1, fixed §3); `display` defaults are model-specific (A2, §3/§5.3); `temperature`-400 unconfirmed in live docs (A3, §3/§7); `ToolUse` not `@Deprecated` (B1, fixed §4).
