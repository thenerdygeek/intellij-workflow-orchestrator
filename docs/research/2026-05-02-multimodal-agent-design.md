# Multimodal Agent Design — Image Input + Capability Discovery

**Status:** Brainstorming complete; awaiting user review before plan-writing.
**Date:** 2026-05-02
**Branch:** `feature/context-compaction`
**Probe baselines:** `tools/sourcegraph-probe/baselines/{vision_lab,capabilities_lab,client_config,model_catalog}_2026-04-22_sourcegraph-6.12.json`

## Background

Two probe rounds against the user's Sourcegraph 6.12.5040 Enterprise instance established the wire-level feasibility of image input through `/.api/completions/stream` (the internal Cody endpoint), and identified critical capability gaps that drive routing decisions:

- **vision_lab:** 24/24 PASS across 6 Claude 4.5 vision models × 4 request shapes. `/.api/completions/stream?api-version=8` accepts `image_url` content parts with `data:image/<mime>;base64,...` URLs.
- **capabilities_lab:** confirmed `tools` field is **silently dropped** on `/.api/completions/stream` (P6 SAW_NO + P7 image+tools SAW_NO); `tools` still works correctly on the public `/.api/llm/chat/completions` (P8 PASS); FilePart with URI alone does NOT auto-fetch (P4 SAW_NO).

Spec-vs-reality deltas captured in `~/.claude/.../memory/reference_sourcegraph_internal_api_full_inventory.md` and `reference_sourcegraph_image_transport.md`.

This document captures every design decision needed to implement multimodal capability in the `:agent` module before any Kotlin code is written.

## Design decisions (8 + 1 bonus)

Each decision uses the format: **question → options considered → recommendation → user-confirmed answer**.

### Bonus: First-run model selection

- **Question:** When the plugin starts and the user has no previously-selected model, which model is pre-selected?
- **Options:** (a) Hard-coded constant in plugin source; (b) Dynamic from `defaultModels.chat` in `/.api/modelconfig/supported-models.json`; (c) Dynamic with hard-coded fallback if catalog unreachable.
- **Recommendation:** (b) Dynamic, no fallback constant. If gateway is unreachable, model picker shows empty + retry affordance.
- **User-confirmed:** **(b) — no hard-coded model defaults anywhere in plugin source.**

### Decision 1 — Image attachment entry points

- **Question:** How does the user get an image into the chat input?
- **Options:** (A) Paperclip only; (B) Paste only; (C) Drag-drop only; (D) All three; (E) Custom subset.
- **Recommendation:** (D). Paste-from-clipboard is the killer flow in IDE workflows ("paste error screenshot from Snipping Tool"). Cody's own JetBrains/VSCode plugins ship paperclip + paste; drag-drop is cheap to add. ~1.5 days total.
- **User-confirmed:** **(D) — all three.**

### Decision 2 — Preview before send

- **Question:** What does the user see between attaching and sending?
- **Options:** (A) Thumbnail chip with × to remove; (B) Full-size inline; (C) Chip + hover-for-larger; (D) Filename + size text only; (E) No preview.
- **Recommendation:** (A). Matches Cody, GitHub PR drafts, Slack — universally recognized. × removes cheaply. Multiple chips stack horizontally.
- **User-confirmed:** **(A) — thumbnail chip with × to remove. Cap at 2 images per turn (mirrors Cody).**

### Decision 3 — Vision-disabled UX

- **Question:** What happens when the user attaches an image and the selected model lacks the `vision` capability tag?
- **Options:** (A) Gray paperclip + tooltip; (B) Hide paperclip entirely; (C) Always allow attach, error toast at Send; (D) Auto-switch model with notice; (E) Disable input entirely.
- **Recommendation:** (A). Industry-standard "action exists, currently unavailable" affordance.
- **User-confirmed:** **(C) — always show paperclip enabled, error toast at Send. Trusts user; fails at moment of intent rather than pre-empting.**
- **Confirmed consequence:** user can attach + see chip + Send and get rejection. ~1 extra click vs option (A). User accepted this trade.

### Decision 4 — Image+tools workaround visibility

- **Question:** When the routing rule triggers the two-step workaround (image to `/stream` for description → description as text to `/chat/completions` for tools), how visible is it?
- **Options:** (A) Two separate assistant messages; (B) One merged message + `📷 image analyzed` badge; (C) Invisible; (D) Loading-state visible.
- **Recommendation:** (B). Badge surfaces token-cost truth + debuggability without visually fragmenting the chat.
- **User-confirmed:** **(B) — one merged message with badge.**

### Decision 5 — Image attachment persistence

- **Question:** Where do image bytes live across plugin restarts?
- **Options:** (A) Inline base64 in `api_conversation_history.json`; (B) External `sessions/{id}/attachments/<sha256>.<ext>` + JSON refs by hash; (C) No persistence.
- **Recommendation:** (B). Content-addressed; cross-session dedup; checkpoints stay tiny; per-session JSON stays ~10 KB.
- **User-confirmed:** **(B) — external content-addressed files, JSON refs by hash.**

### Decision 6 — History compaction with images

- **Question:** When `ContextManager` compacts old turns, what happens to images in those turns?
- **Options:** (A) Strip during compaction, replace with text placeholder; (B) Preserve verbatim; (C) LRU per-image; (D) Vision-summarize at compact time + drop bytes.
- **Recommendation:** (A). Compaction exists *because* we're over budget — preserving images defeats the savings goal. Bytes stay on disk; only the in-context payload is stripped.
- **User-confirmed:** **(A) — strip during compaction, placeholder text preserves structural fact.**

### Decision 7 — Per-model context window visibility

- **Question:** How does the user see per-model context limits (Sonnet=132K, Sonnet-thinking=93K, etc.)?
- **Options:** (A) Silent switch; (B) Show in model picker only; (C) Show as live indicator in chat input only; (D) Both B and C; (E) Threshold warnings only.
- **Recommendation:** (D). Picker badge informs choice; input indicator informs ongoing use; together prevent "why did my agent suddenly compact?" confusion.
- **User-confirmed:** **(D) — both: capacity in picker + usage indicator in chat input.**

### Decision 8 — Hooks + personas behavior on image-bearing turns

- **Question:** Do hooks fire normally on image-bearing turns? Do personas need a `supports_vision` declaration?
- **Options:** (A) Fully transparent; (B) Hook-aware (`has_image: true` flag); (C) Persona-gated; (D) Fully gated.
- **Recommendation:** (A). Zero new infrastructure. Hooks remain content-agnostic notification points (existing metadata gains attachment refs naturally). Personas as YAML opt-in is over-engineering for a hypothetical problem.
- **User-confirmed:** **(A) — fully transparent. Hooks get attachment refs in event metadata; persona schema unchanged. Hooks never receive image bytes (refs only).**

## Architecture

### Component map

**New components in `:core`:**
- **`SourcegraphCompletionsStreamClient.kt`** — thin HTTP client targeting `/.api/completions/stream?api-version=N`. Cody-shape body (`speaker:human`, `maxTokensToSample`, content array with `text` + `image_url` parts). SSE response.
- **`CodyStreamSseParser.kt`** — accumulates `deltaText` from `event: completion` frames. Falls back to cumulative `completion` field for `api-version <= 1`. Authoritative end-of-stream signal: connection close (NOT `event: done` which Cody emits as courtesy).
- **`ModelCatalogService.kt`** — fetches `/.api/modelconfig/supported-models.json` once at plugin startup; caches with 1-hour TTL + on-demand refresh. Also fetches `/.api/client-config` at the same time. Exposes:
  - `getDefaultChatModel(): String` (from catalog `defaultModels.chat`)
  - `getContextWindow(modelRef: String, tier: String): ContextWindow` (reads `modelConfigAllTiers.<tier>.contextWindow`, NOT misleading top-level)
  - `supportsVision(modelRef: String): Boolean` (`"vision" in capabilities`)
  - `getStatus(modelRef: String): ModelStatus`
  - `getLatestStreamApiVersion(): Int` (from client-config)

**Existing components touched:**
- **`OpenAiCompatBrain.kt`** (or its caller) — adds the routing decision branch.
- **`ContextManager.kt`** — context budget pulled from `ModelCatalogService` instead of hard-coded 150K. Adds image-token estimation (~1500/image default; `usage.prompt_tokens` from response is authoritative). Compaction strips image parts, replaces with placeholder text.
- **`AgentChatPanel`** + JCEF webview — new image-attachment surface (Section: UI).
- **`PluginSettings.kt`** + Settings UI — new fields.
- **Persistence layer** — new `sessions/{id}/attachments/` directory; `schemaVersion` field on `api_conversation_history.json`.

**What's NOT changing:**
- Auth scheme (still `Authorization: token <sgp_...>`)
- Existing OpenAI-compat path (still handles all text + tools traffic)
- Hook system, persona YAML schema, sub-agent IPC, plan mode, EventBus events
- 9-module architecture (`:agent` still depends only on `:core`)

### Hybrid routing rule

```kotlin
val needsTools = turn.tools.isNotEmpty()
val hasImage   = turn.content.any { it is ImagePart }

when {
    !hasImage              -> openAiCompatBrain.send(turn)        // existing
    hasImage && !needsTools -> codyStreamBrain.send(turn)         // new
    hasImage && needsTools -> twoStepWorkaround(turn)             // new
}
```

### Two-step workaround

```kotlin
fun twoStepWorkaround(turn: Turn): AssistantMessage {
    val description = codyStreamBrain.send(
        turn.replacingContent(
            "Describe this image in detail for a follow-up tool call. " +
            "Be precise; the description will be the only signal a downstream agent has."
        )
    )
    val rebuiltTurn = turn.replacingImagesWithText(
        "[image description: $description]"
    )
    val finalAnswer = openAiCompatBrain.send(rebuiltTurn)
    return finalAnswer.copy(badges = finalAnswer.badges + AnalyzedImageBadge)
}
```

The badge propagates to the JCEF chat which renders the `📷 image analyzed` strip above the assistant content.

## Wire formats

### Format A: existing path (text + tools) — UNCHANGED

```
POST {sg}/.api/llm/chat/completions
Authorization: token <sgp_...>
Content-Type: application/json
```
```json
{
  "model": "anthropic::2024-10-22::claude-sonnet-4-5-latest",
  "messages": [{"role": "user", "content": "..."}],
  "max_tokens": 8000,
  "temperature": 0.7,
  "tools": [...]
}
```
Response: standard OpenAI JSON; `choices[0].message.content` + `choices[0].message.tool_calls[]`.

### Format B: new path (image-bearing) — `SourcegraphCompletionsStreamClient`

```
POST {sg}/.api/completions/stream?api-version=8
Authorization: token <sgp_...>
Content-Type: application/json; charset=utf-8
Accept: text/event-stream
```
```json
{
  "model": "anthropic::2024-10-22::claude-sonnet-4-5-latest",
  "messages": [{
    "speaker": "human",
    "content": [
      {"type": "image_url",
       "image_url": {"url": "data:image/png;base64,<b64>"}},
      {"type": "text", "text": "<user prompt>"}
    ]
  }],
  "maxTokensToSample": 8000,
  "temperature": 0.7,
  "stream": true,
  "topK": -1,
  "topP": -1
}
```
Response: SSE; `event: completion\ndata: {"deltaText":"..."}` per chunk. End-of-stream = connection close.

## Persistence schema

### Directory layout

```
~/.workflow-orchestrator/{dirName}-{hash}/agent/sessions/{sessionId}/
├── api_conversation_history.json     # JSON refs by hash, NEVER inline base64
├── ui_messages.json                  # existing
├── attachments/                      # NEW
│   ├── 3a4f8b9c....png               # content-addressed: <sha256>.<ext>
│   ├── 7e2d1f5a....jpg
│   └── ...
└── checkpoints/                      # existing; small now (no image bytes)
```

### Wire shape inside `api_conversation_history.json` for an image part

```json
{
  "type": "image_url",
  "image_url": {
    "sha256": "3a4f8b9c...",
    "mime": "image/png",
    "size": 148523,
    "originalFilename": "screenshot.png"
  }
}
```

### Schema versioning

- New top-level field `schemaVersion: 2` on `api_conversation_history.json` (current sessions are implicit `1`).
- **Forward-compatible read first** (Phase 1) — separate release before any image-related code teaches the existing reader to skip unknown content-part `type` values without crashing. Ships and bakes for one release cycle. Phase 4 then adds the actual image content.
- v2-aware reader on a v1 file: defaults `schemaVersion` to 1 and reads as before.
- v1-aware reader on a v2 file (after Phase 1 ships): renders unknown content parts as `[unsupported attachment]` placeholder; doesn't crash.

### Content-addressed write flow (when user attaches an image)

1. Read bytes from clipboard / file picker / drag-drop
2. Validate MIME against `PluginSettings.imageMimeWhitelist` (default: `image/png`, `image/jpeg`, `image/webp`, `image/heic`, `image/heif`)
3. Validate size <= `PluginSettings.imageMaxBytes` (default: 5 MB)
4. Compute sha256
5. If `attachments/<sha256>.<ext>` doesn't exist: write it (if it exists, dedup is automatic)
6. Append the JSON ref to `api_conversation_history.json`
7. JCEF chat updates the chip

### GC of orphaned attachments

- v1 (Phase 4): no auto-GC. User can manually delete `attachments/` if needed. Precedent: existing 7-day log retention requires no plumbing.
- v2 (deferred): refcount index in `attachments/refcount.json`; deleted sessions decrement; files at refcount=0 are removed.

## Context management + token accounting

### Per-model context budget

- `ContextManager` queries `ModelCatalogService.getContextWindow(modelRef, tier)` at the start of each turn.
- Reads `modelConfigAllTiers.<tier>.contextWindow.maxInputTokens` (real values: 132K non-thinking, 93K thinking on user's instance), NOT misleading top-level 45K.
- Falls back to existing 5-tier budget logic if `ModelCatalogService` returns null (catalog cache miss + gateway unreachable simultaneously).

### Per-message cap (`maxUserInputTokens` = 18K observed)

- Soft warning at 80% in chat input.
- Hard block at 100% with toast.
- Image token cost counts against this per-message cap.

### Image-token estimation

- Default: 1500 tokens per image (Anthropic vision pricing model approximation).
- More accurate: tile-based (85 base + 170 per 512×512 tile).
- Authoritative cost: `usage.prompt_tokens` from response after the call returns.

### Compaction trigger (existing logic; new behavior on images)

- Existing 3-stage compaction trigger (50% / 75% / 90% of context) unchanged.
- When compaction strips a turn containing image parts: refs are replaced with `[image: 145 KB PNG, attached 2 turns ago, see attachments/<sha256>]`.
- On-disk JSON keeps refs; only in-context payload is stripped. Historical chat UI still renders the chip.

## UI surface

### JCEF chat input — image attachment

Three entry points, all surface a `<ChipPreview>` component above the input area.

- **Paperclip button:** in input toolbar (left side, before model picker badge). Click → native file picker filtered to MIME whitelist.
- **Paste handler:** `paste` event on the input; if `event.clipboardData.items[].type` matches whitelist, attaches.
- **Drag-drop handler:** `drop` event on the chat panel; same MIME validation; supports multi-file drop up to 2-image cap.

### `<ChipPreview>` component

- 64×64 px thumbnail (rendered from in-memory bytes; no disk round-trip needed for unsent attachments).
- × icon top-right; click removes from queue (no disk write yet — bytes live in JS memory until Send).
- Hover shows filename + KB.
- Stacks horizontally for multiple images (cap at 2).

### Vision-disabled (Decision 3 → option C)

- Paperclip stays clickable. Paste/drag-drop stay enabled.
- On Send, if `selectedModel.capabilities` does NOT include `"vision"` AND turn has image parts → reject with toast: `"<model name> doesn't support image input. Switch to a vision-capable model."` Image bytes stay in chip; user can either remove or switch model.

### Image+tools workaround badge (Decision 4 → option B)

- After two-step workaround completes, the assistant message renders with a badge above the content: `📷 image analyzed`.
- Tooltip on hover: `"Image was analyzed in a separate request to enable tool use."`
- Single message; not two; no spinner-then-replace.

### Model picker (Decision 7 → option D, picker side)

- Existing model picker dialog gets two new column-style fields per model:
  - **Capacity:** `132K context · 18K per-message`
  - **Tags:** small icon strip — 👁 vision · 🔧 tools · 🧠 reasoning · ⚠ deprecated (when applicable)
- "deprecated" badge fires for any model with `status: "deprecated"` in catalog.

### Chat input usage indicator (Decision 7 → option D, input side)

- Small text strip immediately below the input area: `context: 23K / 132K used (17%)` — refreshes after each turn.
- Color shifts: gray <50%, amber 50-80%, red >80%.
- Click reveals breakdown popover: text tokens + image tokens + per-message cap usage.

### `PluginSettings` additions

| Field | Default | Description |
|---|---|---|
| `imageMimeWhitelist` | `["image/png","image/jpeg","image/webp","image/heic","image/heif"]` | Mirrors Cody's whitelist. Editable list. |
| `imageMaxBytes` | `5242880` (5 MB) | Per-attachment cap. Editable. |
| `imagesPerTurnCap` | `2` | Mirrors Cody's per-turn cap. Editable. |
| `enableImageInput` | `true` | Kill switch. Disabling hides paperclip + rejects paste/drag-drop. |
| `imageTokenEstimateDefault` | `1500` | For pre-send budget warnings. |

**Settings UI page:** new page under existing Tools > Workflow Orchestrator > Multimodal.

## Phased rollout

Each phase ships as a separate user-explicit release. Per `feedback_release_timing.md`: only release when explicitly asked; bump patch each time.

| # | Title | What ships | User-visible |
|---|---|---|---|
| 1 | Forward-compat read | Existing reader skips unknown content-part types without crashing | Nothing — defensive parsing only |
| 2 | ModelCatalogService + per-model budget | New service hits `/.api/client-config` + `/.api/modelconfig/supported-models.json` at startup; ContextManager reads per-model budget | Telemetry change only |
| 3 | SourcegraphCompletionsStreamClient + SSE parser + tests | New HTTP client + parser; **NO UI wiring** | Nothing — pure wire layer |
| 4 | Persistence schema (`attachments/` + sha256 dedup) + migration tests | `sessions/{id}/attachments/` directory; `schemaVersion: 2` bump; v1 sessions still load | Nothing — schema only |
| 5 | JCEF chat image-attachment surface | Paperclip + paste + drag-drop + chip + Settings UI | **First user-visible feature** — image input works |
| 6 | Routing + image+tools workaround | BrainRouter; two-step workaround with `📷 image analyzed` badge; image-token estimation | Image input safe to use mid-tool-call |
| 7 | Model picker capacity + chat input usage indicator + deprecated badge | UI polish on capability discovery from Phase 2 | Token meter, model badges visible |

**Why this order:** highest-risk pieces (forward-compat read, persistence migration) ship first with no behavior change so they bake. Wire layer ships next without UI so it's testable in isolation. UI ships last when both wire layer and persistence layer are stable. Image+tools workaround is its own phase to keep testing focused.

## Testing approach

Per `feedback_real_tdd.md`: tests derived from spec scenarios, not from code.

### E2E scenarios per phase

- **Phase 1:** "v1 session JSON is loaded by v2 reader without throwing; unknown content parts render as `[unsupported attachment]` placeholder."
- **Phase 2:** "On startup, ModelCatalogService caches the catalog; `getContextWindow('claude-sonnet-4-5-latest', 'enterprise')` returns 132000."
- **Phase 3:** "POST to `/.api/completions/stream?api-version=8` with the canonical Cody body returns SSE; parser accumulates `deltaText` from N completion frames; end-of-stream detected on connection close."
- **Phase 4:** "User attaches image; file lands at `attachments/<sha256>.png`; identical re-attach in different session reuses the same file."
- **Phase 5:** "User pastes image into chat input; chip appears; × removes; Send rejects with toast if vision-incapable model selected."
- **Phase 6:** "User sends image+tools turn; routing branch picks two-step; assistant message renders with 📷 badge; ContextManager budget reflects the image token cost."
- **Phase 7:** "Model picker shows 132K capacity for Sonnet, 93K for Sonnet-Thinking; chat input indicator updates after each turn; deprecated model shows warning badge."

### Code review per phase

Dispatch Opus code-reviewer subagent on each phase's commits. Block next phase until review passes.

### Build-cache trap (per CLAUDE.md)

Phases 3 (SSE parser) and 6 (BrainRouter) touch coroutine code with `suspend`-typed lambdas. Run tests with `--no-build-cache --rerun-tasks` to avoid stale `Function0` bytecode against new test compile (per the documented Phase 4 D8b incident in CLAUDE.md).

## What's NOT in scope

- **RepoPart** — user explicitly declined (capabilities_lab P5 also failed; not viable on this instance without indexed repos).
- **FilePart with URI-only auto-fetch** — capabilities_lab P4 disproved; Sourcegraph does not fetch from URIs server-side.
- **`/.api/completions/code`** — single-shot, strictly less useful than `/stream`.
- **Cody Agent JSON-RPC integration** — adds operational complexity (embedded Node process) for capability we already reach via plain HTTP.
- **Custom retry/backoff for `tools` on `/stream`** — fix is routing, not retry.
- **`api-version=9` upgrade** — defer until a v9-only feature is needed.
- **Tools support on `/.api/completions/stream`** — silently dropped per probe (P6 SAW_NO); cannot be made to work client-side.

## Open questions for user review

None at brainstorming completion. All 8 design decisions + bonus locked. Awaiting user review of this doc before invoking writing-plans skill.
