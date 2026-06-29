# Plugin Split — Phase 4a: Native Anthropic-Direct LLM Provider

**Date:** 2026-06-30
**Branch:** `feature/plugin-split`
**Extends:** `docs/superpowers/specs/2026-06-22-plugin-split-design.md` §6 (LLM pluggability) + §11 roadmap row 4. Decomposes the design's "Phase 4" into **4a (ship the native provider — this spec)** and **4b (seam consolidation — deferred)**.
**Predecessor:** Phase 0b-1 (`docs/superpowers/plans/2026-06-23-plugin-split-phase0b-1-llm-seam.md`) built the seam SHAPE — `core/ai/protocol/{ToolProtocol,XmlToolProtocol,NativeProtocol}.kt`, `core/ai/LlmProvider.kt`, `agent/loop/XmlLlmProvider.kt`, `requiresDialectGuard` gating, and `ApiMessage.protocol: String? = null`. Phase 4a fills in the native impl on that seam.
**Exploration maps:** `.superpowers/phase4/explore-{seam,transport,persistence}.md`.

---

## 1. Goal

Add a **native Anthropic-direct LLM provider** so the agent can run against `api.anthropic.com` with a user-supplied API key, instead of *requiring* Sourcegraph Enterprise (`/.api/llm/chat/completions`). This is the design's critical path — it's what makes an open-source plugin actually usable without corporate Sourcegraph access — and it delivers, as side effects, reliable tool calls (native function-calling has no dialect-drift surface) and native thinking/effort control.

This phase ships **A + B privately** (internal-first); Bedrock/Vertex and the OSS publish remain later efforts.

---

## 2. Scope

### In scope — Phase 4a (USER-confirmed 2026-06-29)

1. **`AnthropicDirectBrain`** (`LlmBrain` impl) + **`AnthropicNativeProtocol`** (`NativeProtocol` impl) — structured `tool_use`/`tool_result`, `content_block_delta`/`input_json_delta` streaming, native error classification.
2. **Proxy-aware native HTTP client** — `IdeProxy` + `IdeTrust`-routed OkHttp (today's LLM clients bypass both — see §6).
3. **Persisted-format discriminator handling** — `toChatMessage()` branches on `ApiMessage.protocol` so a session mixing XML and native turns replays correctly (the field is already reserved; the migration logic lands here).
4. **Static `AnthropicModelCatalog`** — same DTO shape as `ModelCatalogService` (context window / capabilities / status), keyed without the Sourcegraph `provider::apiVersion::` prefix.
5. **Settings** — provider selection, Anthropic API key (CredentialStore), model id, effort/thinking; `BrainFactory.create()` branches on the provider.

### Out of scope — deferred to Phase 4b (the seam consolidations; add no native capability)

- Dissolving `BrainRouter` into a `SourcegraphProvider`; widening `AgentLoop.brain` from `LlmBrain` to `LlmProvider`; relocating `toolNameSet`/`paramNameSet` off `LlmBrain` onto `ToolProtocol` (the atomic 9-site reroute, race-prone under parallel sub-agent fan-out).

### Out of scope — entirely (this program)

- **Bedrock / Vertex transports** (SigV4 / OAuth2 + their SDKs' own HTTP clients that bypass `IdeProxy`/`IdeTrust`) — Tier-2, after the program.
- **Live native streaming edit previews** — native has no XML tags to split mid-stream; the `edit_file` diff appears at tool-call completion via the approval card, not as a live-growing diff. The XML/Sourcegraph path keeps its live preview. (USER-confirmed 2026-06-29.)

---

## 3. The Anthropic Messages API surface (grounded in the `claude-api` reference, 2026-06-30)

Authoritative mapping for the native impl. Verify against the live reference at implementation time (API drift).

- **Endpoint:** `POST https://api.anthropic.com/v1/messages` (configurable base URL).
- **Auth + headers:** `x-api-key: <key>`, `anthropic-version: 2023-06-01`, `content-type: application/json`. (Fits the existing static-header `Credential` model — contrast Bedrock SigV4 / Vertex OAuth2.)
- **System prompt:** a **top-level `system` field** (string or `[{type:"text", text, cache_control?}]`) — NOT a `system`-role message. This *replaces* the Sourcegraph `sanitizeMessages()` "system→user with `<system_instructions>`" coercion for the native path.
- **Messages:** strict `user`/`assistant` alternation; assistant `tool_use` blocks `{type, id, name, input}`; tool results as a `user` message of `{type:"tool_result", tool_use_id, content, is_error?}` blocks (maps 1:1 to the existing structured `ContentBlock.ToolResult`).
- **Tools:** `tools: [{name, description, input_schema}]` (JSON Schema). `stop_reason: "tool_use"` when the model calls tools. (So `presentTools` returns null — tools do NOT go in the system prompt — and the brain must receive the structured tool defs; see §5.3.)
- **Streaming SSE events:** `message_start` → `content_block_start` (`content_block.type` ∈ `text` | `thinking` | `tool_use{id,name}`) → `content_block_delta` (`delta.type` ∈ `text_delta` | `thinking_delta` | `input_json_delta` | `signature_delta`) → `content_block_stop` → `message_delta` (carries `stop_reason` + cumulative `usage.output_tokens`) → `message_stop`. Tool-call inputs arrive as `input_json_delta` fragments that concatenate into the `tool_use.input` JSON.
- **Thinking / effort (Opus 4.8/4.7):** `thinking: {type:"adaptive", display?:"summarized"|"omitted"}` (default `omitted` = empty thinking text) + `output_config: {effort: "low"|"medium"|"high"|"xhigh"|"max"}` (GA, no beta header; default `high`). **`budget_tokens` 400s; `temperature`/`top_p`/`top_k` 400.** The provider must NOT forward sampling params.
- **Models + context windows (cached 2026-06-04 — verify live):** `claude-opus-4-8` (default, 1M ctx, 128K out, $5/$25), `claude-sonnet-4-6` (1M, 64K), `claude-haiku-4-5` (200K, 64K), `claude-fable-5` (most capable, 1M, 128K — note its always-on-thinking + refusal-fallback nuances, out of scope for 4a default). Exact ID strings, no date suffix (except haiku's `-20251001`). Optional live capability via `GET /v1/models`.
- **Errors:** `error.type` for classification — `429 rate_limit_error` (+ `retry-after`), `529 overloaded_error`, `500 api_error`, `413 request_too_large`, `400 invalid_request_error`, `401`/`403`. Streaming errors arrive as an `event: error` frame. (Wires to `classifyHttpError`/`classifyStreamLine` — §5.4.)
- **Prompt caching (`cache_control: {type:"ephemeral"}`):** a worthwhile follow-up for the agent's large system prompt, but **not in 4a's critical path** — note for 4b/optimization.

---

## 4. Current-state findings the design must honor (from the three exploration maps)

- **The migration is assistant-turn-only.** Tool results are *already* persisted as structured `ContentBlock.ToolResult(toolUseId, content, isError)` under `ApiRole.USER`; the `"TOOL RESULT:\n"` prefix is wire-time only (`SourcegraphChatClient.sanitizeMessages`). Only the *assistant* turn shape (XML-in-`Text` vs native structured `tool_use`) needs the discriminator branch.
- **All 6 drift-guard sites bypass cleanly** via the `consumeDialectDriftFlag` chokepoint + explicit `requiresDialectGuard` guards — *provided the native `toolProtocol` instance actually reaches them*. Trap: `MessageStateHandler` (3 of 4 ctor sites, e.g. `AgentService.kt:2315`) and `SubagentRunner.kt:162` hardcode `XmlToolProtocol()`. Phase 4a must thread the provider's `toolProtocol` to those sites, or a native session silently re-activates the dialect machinery.
- **`classifyStreamLine` is a dead letter today** — `SourcegraphChatClient` calls `GatewayErrorDetector` directly, bypassing the protocol seam. Phase 4a's native transport must call `classifyStreamLine`/`classifyHttpError` explicitly.
- **All current LLM clients bypass `IdeProxy`/`IdeTrust`** (raw `OkHttpClient.Builder()`), so they silently fail TLS on SSL-inspection networks and are unreachable on proxy-only networks. The `:web` module (`WebFetchServiceImpl`/`WebSearchServiceImpl`) already wires the full triad — copy it. A native client done right is *more* correct than the Sourcegraph path.
- **Three concrete code risks:** (1) `ContentBlock.ToolUse` is `@Deprecated` but native turns must *write* it — re-scope the annotation (or use a distinct native block type); (2) the reverse `ApiMessage.toApiMessage()` path drops `toolCalls` today — fix for native writes; (3) `ModelCache.getModels()` hits a Sourcegraph endpoint — bypass for anthropic-direct (use the static catalog).
- **A new `AnthropicDirectBrain implements LlmBrain` slots into the 9 existing `toolNameSet`/`paramNameSet` call-sites with zero churn** (the sets are per-instance fields) — which is *why* the consolidation reroute (4b) can be deferred.

---

## 5. Design (Phase 4a)

### 5.1 `AnthropicNativeProtocol : NativeProtocol` (`core/ai/protocol/`)

Implements the two abstract members `NativeProtocol` leaves open:
- **`parseToolCalls(text, toolNames, paramNames): List<AssistantMessageContent>`** — for native, the authoritative tool-call decode does NOT come from re-parsing text. The brain accumulates structured `tool_use` blocks during streaming and hands the protocol the assembled calls; `parseToolCalls` maps the brain's accumulated structured tool_use → `ToolUseContent`. (The defaulted `presentTools=null`, `toolResultWirePrefix=""`, `requiresDialectGuard=false`, no-op `stripPartialTag`/`endsWithIncompleteTag` from the `NativeProtocol` interface are correct as-is.)
- **`classifyStreamLine` / `classifyHttpError`** — map Anthropic SSE `event: error` frames and HTTP 429/529/413/400 to the loop's normalized recovery strings.

### 5.2 `AnthropicDirectBrain : LlmBrain` (`agent/.../ai/` or `core/ai/`)

The wire client. Responsibilities:
1. **History → Anthropic request mapping** — system prompt → top-level `system` field; user/assistant turns mapped with strict alternation; structured `ContentBlock.ToolResult` → `tool_result` blocks; assistant turns rendered by `protocol` (native → structured `tool_use`; legacy `xml` turns sent as plain text context).
2. **Tools → `tools:[{name,description,input_schema}]`** — convert the agent's `ToolDefinition` list to Anthropic JSON-schema tools (the structured equivalent of today's XML `ToolPromptBuilder` output).
3. **Request params** — `model`, `max_tokens` (from settings), `thinking:{type:"adaptive"}` + `output_config:{effort}` when enabled; NEVER `temperature`/`top_p`/`top_k`/`budget_tokens`.
4. **SSE parse** — consume the event stream; emit text + thinking deltas through the existing `StreamChunk` text path (so the live-text UI is unchanged); accumulate `tool_use` blocks internally from `content_block_start`+`input_json_delta`; surface the assembled tool calls at `message_stop` (this is the accepted streaming-edit-preview regression — the diff materializes at completion, not live).
5. **Proxy-aware OkHttp** — §6.
6. **Per-instance `toolNameSet`/`paramNameSet`** (LlmBrain contract) — so it slots into the 9 existing call-sites; the per-spawn isolation invariant holds without the 4b reroute.

> **Alternatives considered (flag for the plan-review): "native wire, XML-internal" (Option A).** A lower-risk variant renders the model's structured `tool_use` into canonical XML-in-text at stream end, so `AssistantMessageParser` + persistence work UNCHANGED and the discriminator/migration defer to 4b entirely. It delivers the same user value (direct Anthropic, reliable native tool calls, native thinking/effort, proxy-aware client) with a smaller blast radius, at the cost of not exercising the structured `NativeProtocol` path the seam was built for. The spec's primary design is the structured path (matches the seam + the approved 4a scope); the opus reviews should adjudicate whether 4a starts with Option A and promotes to structured in 4b.

### 5.3 Tool-definition threading

`presentTools` returns null for native, so tools must reach `AnthropicDirectBrain` as structured data (not baked into the system prompt). Design point for the plan: thread the per-iteration `List<ToolDefinition>` to the brain (a brain-call parameter or a per-iteration tool-def provider on the provider) — the minimal interface widening 4a needs. (4b's `brain → LlmProvider` consolidation subsumes this cleanly later.)

### 5.4 Persistence + discriminator

- On a native assistant turn, persist structured `tool_use` with `ApiMessage.protocol = "native"`; fix `toApiMessage()` to carry `toolCalls`; re-scope/replace the `@Deprecated ContentBlock.ToolUse` so native writes are first-class.
- `ApiMessage.toChatMessage()` branches on `protocol`: `"native"` → structured render; default/`"xml"` → existing `ToolUseXmlRenderer` path. A mixed XML+native session replays correctly.
- Thread the provider's `toolProtocol` to `MessageStateHandler` + `SubagentRunner` (the hardcoded-`XmlToolProtocol()` sites) so `requiresDialectGuard=false` is honored and the drift machinery stays inert for native sessions.

### 5.5 Catalog + model selection

- `AnthropicModelCatalog` — static map (or `GET /v1/models`-backed) of Anthropic models → context window / capabilities / status, in the `ModelCatalogService` DTO shape, keyed on the bare model id. `SharedCatalogHolder` (keyed on `sgUrl`) returns null for Anthropic → a catalog-service seam or a parallel holder selected by provider.
- Bypass `ModelCache.getModels()` (Sourcegraph endpoint) when provider == anthropic-direct.

### 5.6 Settings

- **`ConnectionSettings`** — optional `anthropicBaseUrl` (default `https://api.anthropic.com`).
- **`AgentSettings`** — `llmProvider: String = "sourcegraph"` (`"sourcegraph"` | `"anthropic-direct"`), `anthropicModel: String?` (default `claude-opus-4-8`), `anthropicEffort: String?` (default `high`), `anthropicThinkingEnabled: Boolean` (adaptive on/off).
- **CredentialStore** — new `ServiceType.ANTHROPIC` (`x-api-key`); never in XML.
- **`BrainFactory.create()`** — branch on `llmProvider`: sourcegraph → existing path; anthropic-direct → `AnthropicDirectBrain` + `AnthropicNativeProtocol` + `AnthropicModelCatalog`. Provider/model/effort/thinking surfaced in Settings → Tools → Workflow Orchestrator → AI Agent.

---

## 6. Proxy-aware HTTP client

Build the native OkHttp through `IdeProxy.selector()` + `IdeProxy.proxyAuthenticator()` + `IdeTrust.applyTo()` (the triad `:web`'s `WebFetchServiceImpl`/`WebSearchServiceImpl` already use) — NOT a raw `OkHttpClient.Builder()`. This is both required (native client must traverse corporate proxy + custom truststore) and a correctness win over the current Sourcegraph clients. Keep this client isolated from `HttpClientFactory` (the agent LLM path is intentionally separate), but wire the proxy/trust triad explicitly.

---

## 7. Files touched (anticipated)

| Area | Files |
|---|---|
| Protocol | `core/ai/protocol/AnthropicNativeProtocol.kt` (new) |
| Brain | `agent/.../ai/AnthropicDirectBrain.kt` (new) + Anthropic request/response DTOs + SSE parser |
| HTTP | proxy-aware OkHttp builder (reuse `:web` triad pattern) |
| Catalog | `core/ai/AnthropicModelCatalog.kt` (new) + `SharedCatalogHolder`/`ModelCatalogService` provider seam |
| Persistence | `ApiMessage.kt` (`toChatMessage` protocol branch, `toApiMessage` toolCalls fix, `ContentBlock.ToolUse` de-deprecation); thread `toolProtocol` to `MessageStateHandler` + `SubagentRunner` |
| Factory/wiring | `brain/BrainFactory.kt` (provider branch); tool-def threading to the brain |
| Settings | `ConnectionSettings`, `AgentSettings`, `CredentialStore`/`ServiceType`, settings UI |
| Docs | `agent/CLAUDE.md` (LLM API section — dual provider), main design-doc §25 note |

No `:plugin-b` change.

---

## 8. Testing strategy

- **Pure unit (JUnit 5 + MockK):** `AnthropicNativeProtocol` parse/classify; the history→Anthropic request mapping (system-field, alternation, tool_result mapping, no-sampling-params invariant); the SSE parser (golden Anthropic SSE fixtures → text/thinking/tool_use accumulation); `toChatMessage` protocol branch (xml / native / mixed-session); `AnthropicModelCatalog`; `BrainFactory.create()` provider branch.
- **Source-text/contract:** the native client wires `IdeProxy` + `IdeTrust` (pin against silent regression to raw OkHttp); `MessageStateHandler`/`SubagentRunner` receive the provider's `toolProtocol` (pin the drift-bypass wiring); the request builder never emits `temperature`/`top_p`/`top_k`/`budget_tokens`.
- **Round-trip:** persist a native turn → reload → `toChatMessage` → re-serialize, byte-stable; a mixed xml+native session resumes without loss.
- **Live smoke (manual / gated):** one real `api.anthropic.com` call with a test key — text stream, a tool call, thinking — behind a runIde/manual gate (no key in CI).
- **Gate:** `:agent:test` + `:core:test` (pure JUnit5), `verifyPlugin` (`-x buildSearchableOptions` on macOS), `koverVerify -Pcoverage -x uiTest`.

---

## 9. Risks & mitigations

- **Persisted-format back-compat** (the sharpest): a discriminator bug could corrupt resume of existing (xml) sessions. Mitigation: discriminator defaults to `"xml"`; `toChatMessage` xml-branch is byte-identical to today; explicit mixed-session round-trip tests; the structured-vs-XML-internal fork (§5.2) adjudicated in plan review before implementation.
- **`@Deprecated ContentBlock.ToolUse` re-scope** ripples into any reader asserting it's read-only — grep all readers before changing.
- **Drift machinery silently re-armed** if the native `toolProtocol` doesn't reach the hardcoded sites — pinned by the §8 wiring contract test.
- **`classifyStreamLine` dead-letter** — the native transport must call it explicitly (it isn't auto-invoked); pinned by a parse test.
- **Sampling-param leak** (instant 400 from Anthropic) — pinned by the no-sampling-params request test.
- **API drift** — model IDs/thinking/effort/streaming shapes verified against the live `claude-api` reference at implementation, not from memory.
- **Build-cache trap** — any `suspend`-signature change runs with `--no-build-cache` (the documented `NoSuchMethodError` trap); on macOS use `gradlew --stop` for config-cache, never `--no-build-cache` casually elsewhere.

---

## 10. Definition of Done

- A user can select **anthropic-direct** + enter an API key + pick a model/effort in Settings, and run the agent end-to-end against `api.anthropic.com` — text, tool calls, thinking — with NO Sourcegraph configured.
- The native client traverses `IdeProxy`/`IdeTrust`; requests never carry sampling params; native turns persist + resume (incl. mixed xml+native sessions); the drift machinery stays inert for native sessions.
- Sourcegraph path is byte-for-byte unchanged (existing snapshots/tests green).
- Full gate GREEN; whole-phase opus review 0 Critical / 0 Important.
- `agent/CLAUDE.md` (dual-provider) + main design-doc §25 updated in the implementing commits.

---

## 11. Process (standing rule)

This spec → user review → 2–3 independent opus spec reviews (incl. the §5.2 Option-A-vs-B adjudication and a persistence-back-compat skeptic) → `writing-plans` → opus plan reviews → SDD → whole-phase review → gate. Commit on `feature/plugin-split`; **no `Co-Authored-By` / "Generated with" trailer.**
