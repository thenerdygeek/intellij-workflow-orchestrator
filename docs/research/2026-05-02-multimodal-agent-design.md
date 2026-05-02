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

See §"Type model evolution" for the routing rule against the actual type shapes (`ChatMessage.parts: List<ContentPart>?`). High-level summary repeated here for convenience:

- `messages` has no image parts → `openAiCompatBrain.chat(messages, tools)` (existing path; no behavior change)
- `messages` has image parts AND `tools.isEmpty()` → `codyStreamBrain.chat(messages)` (new client; SSE)
- `messages` has image parts AND `tools.isNotEmpty()` → `twoStepWorkaround(messages, tools)` (described below)

### Two-step workaround (with explicit failure handling)

```kotlin
suspend fun twoStepWorkaround(messages: List<ChatMessage>, tools: List<Tool>): AssistantMessage {
    // Step 1: vision-summarize on /stream
    val descriptionResult = runCatching {
        codyStreamBrain.chat(
            messages.lastImageBearingTurnReplacedWith(
                "Describe this image in detail for a follow-up tool call. " +
                "Be precise; the description will be the only signal a downstream agent has."
            )
        )
    }
    when {
        descriptionResult.isFailure -> {
            // timeout, HTTP error, malformed SSE — surface to user, no step 2
            return AssistantMessage.error(
                "Image analysis failed: ${descriptionResult.exceptionOrNull()?.message}. " +
                "Try again, or remove the image and describe it in text."
            )
        }
        descriptionResult.getOrNull()?.text?.matchesAbstention() == true -> {
            // model said "I cannot see this image clearly" or similar — operationally a failure
            return AssistantMessage.error(
                "The model couldn't analyze the attached image. " +
                "Try a clearer image, or describe it in text."
            )
        }
    }
    val description = descriptionResult.getOrThrow().text

    // Step 2: tools call on /chat/completions with image replaced by text description
    val rebuiltMessages = messages.replacingImagesWithText("[image description: $description]")
    val finalAnswer = openAiCompatBrain.chat(rebuiltMessages, tools)

    // Badge fires only when step 1 actually executed and produced a non-abstaining description
    return finalAnswer.copy(badges = finalAnswer.badges + AnalyzedImageBadge)
}

private fun String.matchesAbstention(): Boolean = listOf(
    "i cannot see", "i can't see", "i don't see", "no image", "unable to view",
    "cannot view", "can't view", "i'm unable to process"
).any { contains(it, ignoreCase = true) }
```

The badge fires only when step 1 actually executed and the model actually described the image. Abstention or wire-level failure produces a user-visible error toast; no badge, no garbled tool call.

The badge propagates to the JCEF chat which renders the `📷 image analyzed` strip above the assistant content.

**Model-downgrade race (Decision 3 corner case):** if `ModelFallbackManager` swaps the active model mid-iteration (between attach and Send, or between Step 1 and Step 2), the fallback chain MUST be filtered to vision-capable models when the in-flight payload contains image parts. If no vision-capable fallback exists, the chain fails fast and the user sees the same vision-disabled toast from Decision 3. The chip stays in the input area; the user can switch model and re-Send. No automatic resend.

## Type model evolution (CRITICAL — added during spec review)

Image content crosses three distinct types in the codebase, with existing structural mismatches that this work must reconcile. Spec review caught that the original spec only modeled the on-disk shape and missed the in-process types entirely.

**The three types involved:**

| Type | File | Current shape | Currently supports image? |
|---|---|---|---|
| `ChatMessage` (in-process LLM-facing) | `core/src/main/kotlin/com/workflow/orchestrator/core/ai/dto/ChatCompletionModels.kt` | `data class ChatMessage(val role: Role, val content: String?, ...)` — flat `String?` | **No** — flat `content: String?` |
| `ApiMessage` + `ContentBlock` (on-disk) | `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ApiMessage.kt` | `data class ApiMessage(..., val content: List<ContentBlock>)` with `sealed interface ContentBlock` having `Text(text)`, `ToolUse(...)`, `ToolResult(...)`, **and `Image(mediaType, data)`** | **Partial** — `ContentBlock.Image` already exists but is **unused dead code** (only one reference: `else -> false` in `MessageStateHandler.isEmptyAssistant`) |
| Wire body to `OpenAiCompatBrain.chat()` | same file as `ChatMessage` | derives directly from `ChatMessage.content: String?` | **No** |

**Required type-model changes:**

1. **`ChatMessage`** gains an optional sibling field `parts: List<ContentPart>?` (sealed interface mirroring the wire-format shape). When `parts` is non-null, it takes precedence over `content` for body construction. This preserves backward compat for every existing call site.
2. **`ContentBlock.Image`** in `ApiMessage.kt` is **repurposed** (not replaced). The existing `Image(mediaType, data)` shape — where `data` is base64 bytes inline — is deprecated in favor of the new schema: `Image(sha256: String, mime: String, size: Long, originalFilename: String?)`. A migration path in `ApiMessage` deserializer detects the old inline-base64 shape, writes it to `attachments/<sha256>.<ext>`, and rewrites the in-memory representation to the new ref shape. Since the existing `Image` variant has zero non-test references, no production code breaks.
3. **`ApiMessage.toChatMessage()`** (currently flattens `List<ContentBlock>` to text and silently drops `Image`) is updated: when an `Image` block is present, it builds a `ChatMessage` with both the text-flattened `content` (for code paths that don't yet read `parts`) and a populated `parts: List<ContentPart>?` for the new path.
4. **`OpenAiCompatBrain.chat()`** keeps its existing signature; the routing rule reads `message.parts` (not `turn.content` — there is no `turn` type in this codebase). When `parts` is null or contains only text, traffic flows through the existing path unchanged. When `parts` contains any `ImagePart`, the routing rule from §Architecture engages.

**Routing rule (corrected to match real types):**

```kotlin
val needsTools = tools.isNotEmpty()
val hasImage   = messages.any { it.parts?.any { p -> p is ContentPart.Image } == true }

when {
    !hasImage              -> openAiCompatBrain.chat(messages, tools)        // existing
    hasImage && !needsTools -> codyStreamBrain.chat(messages)                 // new
    hasImage && needsTools -> twoStepWorkaround(messages, tools)              // new
}
```

**Sealed-interface forward-compat (CRITICAL — Phase 1's actual mechanism):**

`@Serializable sealed interface ContentBlock` discriminates on `type` field. The existing `Json { ignoreUnknownKeys = true }` setting in `MessageStateHandler` does NOT cover unknown polymorphic discriminators — it only covers unknown *fields within* known classes. An unknown `type` value throws `SerializationException: Serializer for subclass 'X' is not found in the polymorphic scope of 'ContentBlock'`.

Phase 1 must explicitly install one of:
- A `JsonContentPolymorphicSerializer<ContentBlock>` that returns `ContentBlock.Text("[unsupported attachment]")` when the discriminator is unknown, OR
- `serializersModule { polymorphic(ContentBlock::class) { defaultDeserializer { UnknownContentBlockSerializer } } }`

Either way, the Phase 1 commit must ship a regression test: hand-roll a JSON snippet with `{"type":"some_future_type",...}` inside a content array, deserialize with the existing reader, assert no exception + assert it round-trips through `ApiMessage.toChatMessage()` to placeholder text.

**Implication for Phase 1+4 ordering:**

The original spec defended Phase-1-then-Phase-4 ordering as "bakes for one release cycle." For a **single-user plugin** (you are the only installer), there is no soak window: you control both the v1 and v2 install on the same machine. Phase 1's protection is still correct in principle (defensive parsing is good engineering) but the rationale for separating it from Phase 4 is weak. Two valid options:

- **Keep Phase 1 separate** as documented audit trail of when defensive parsing landed; ship same-week as Phase 4.
- **Merge Phases 1+4** into one defensive-parser-plus-write commit. Acceptable for single-user.

User to choose during plan-writing review.

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
Response: SSE; `event: completion\ndata: {"deltaText":"..."}` per chunk.

**End-of-stream signal: whichever comes first of three signals.** The original spec said "rely on connection close" — spec review caught that this is unsafe under HTTP/1.1 keepalive (connection stays open after response) and behind buffering proxies (close detection delayed by minutes). Correct termination logic:
1. `event: done` (Cody emits as courtesy; not in spec but observed)
2. `data: [DONE]` (OpenAI sentinel; not observed on this endpoint but defensive)
3. Connection close

Whichever is first wins. Phase 3's E2E scenario must include a fake-server test that asserts the parser terminates within bounded time when `event: done` is sent before the connection closes.

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

### GC of orphaned attachments — REVISED for safe v1

The original spec proposed cross-session content-addressed dedup with no auto-GC. Spec review caught the footgun: `MessageStateHandler.deleteSession()` recursively deletes the session dir, so if `attachments/<sha256>.png` is *shared* across 3 sessions and any one is deleted, the others break. Two safe options:

**Option A (chosen for v1 — simpler):** **per-session attachments, no cross-session dedup.**
- Files live at `sessions/{sessionId}/attachments/<sha256>.<ext>`, NOT a global pool.
- Same image attached in 5 different sessions = 5 copies on disk. Acceptable cost: at 5MB max per image, even 50 sessions × 10 images = 2.5 GB worst case (most users will be far below).
- `deleteSession()` semantics unchanged — recursive delete just works; no orphan risk.
- Future: option B can be added later as an optimization without breaking v1 sessions.

**Option B (deferred — would need refcount):** global pool at `~/.workflow-orchestrator/{dirName}-{hash}/agent/attachments-pool/<sha256>.<ext>` with a refcount index. NOT in v1 scope; documented here only so we don't accidentally land it.

**Within a single session, dedup IS preserved:** if the user pastes the same screenshot twice in one session, sha256 collides, file is written once, both content blocks reference the same path. Disk savings within sessions; not across. Good enough for v1.

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

- Existing compaction trigger thresholds: entry floor at **70%** util (Stage 0 returns early below this), Stage 2 at **85%**, Stage 3 at **95%** (per `ContextManager.kt`). Auto-compaction default `compactionThreshold = 0.85`. Unchanged by this work.
- When compaction strips a turn containing image parts: image-content blocks are removed from the in-context payload and a sibling `ContentBlock.Text` is inserted in their place with text like `[image attached earlier; bytes preserved on disk]`.
- The placeholder text is deliberately written to NOT match `extractFilePathFromToolResult`'s regex (`^\[(\S+) for '([^']+)'] Result:` or `<file_content path="...">`), so it doesn't get caught by Stage 1 file-read dedup or Stage 2 truncation heuristics.
- On-disk JSON keeps the original `ContentBlock.Image` references (with `sha256`/`mime`/`size`/`originalFilename` fields); only the in-context payload sent to the model is stripped. Historical chat UI still renders the chip from the on-disk reference.

## UI surface

### JCEF chat input — image attachment

Three entry points, all surface a `<ChipPreview>` component above the input area.

- **Paperclip button:** in input toolbar — repurpose the existing `Plus` icon in `agent/webview/src/components/input/InputBar.tsx`, OR add a sibling button. Click → native file picker filtered to MIME whitelist.
- **Paste handler:** must integrate with `agent/webview/src/components/input/RichInput.tsx::handlePaste` (line ~431) which already calls `e.preventDefault()` and reads `text/plain` only. Image-paste handling is added INSIDE that same handler (NOT a sibling listener) — otherwise the existing `preventDefault` swallows the event before our handler runs. Detection: `e.clipboardData.items[i].kind === 'file' && imageMimeWhitelist.includes(items[i].type)`.
- **Drag-drop handler:** `drop` event on the chat panel; detection: `event.dataTransfer.items[i].kind === 'file'` AND `event.dataTransfer.files` fallback. Supports multi-file drop up to 2-image cap.

**MIME quirks to verify in Phase 5:**
- macOS HEIC paste arrives as `public.heic` not `image/heic` — JBCef Chromium build (CEF 122-ish) MIME mapping is unverified for HEIC/WebP. Phase 5 includes a probe sub-task to confirm. If JBCef doesn't surface HEIC, decide: drop HEIC from whitelist OR client-side re-encode to JPEG (which makes `originalFilename` and `sha256` diverge from user's source file — must be documented in tooltip).
- Drag-drop from Finder may arrive as `text/uri-list` first; the handler must check both `dataTransfer.items` (file kind) and `dataTransfer.files`.

### IPC payload across the JCEF bridge

`JBCefJSQuery` is **string-only** (Java string ↔ JS string). For a 5MB image, base64-encoded that's ~6.6 MB of JSON-safe characters per attach event. The existing `AgentCefPanel.kt` carries small text payloads in its ~30 query handlers; multi-MB IPC has not been proven on this codebase.

**Decision: chunked-by-sha256 path.**
1. JS computes file size client-side. If `> imageMaxBytes`, reject locally with toast — no bridge call.
2. JS computes sha256 client-side (subtle.crypto). Sends ONLY `{sha256, mime, size, originalFilename}` to Kotlin via bridge.
3. Kotlin checks `attachments/<sha256>.<ext>` on disk. If exists → respond with `{exists: true}`; chip created from existing file. If absent → respond with `{exists: false, uploadUrl: "/upload/<sha256>"}`.
4. JS uploads bytes via `CefResourceSchemeHandler` (HTTP-style endpoint served by the plugin) at `http://workflow-agent/upload/<sha256>` — bypasses bridge IPC entirely.
5. Kotlin handler validates MIME at upload time, writes to disk, responds 200.

This keeps bridge IPC tiny (just the metadata) and offloads the multi-MB transfer to a path that doesn't pin EDT.

**Threading:** All disk writes happen on `Dispatchers.IO`. The bridge handler responds immediately after dispatching; no EDT pinning during sha256 lookup or upload.

### `<ChipPreview>` component

- 64×64 px thumbnail (rendered from in-memory bytes; no disk round-trip needed for unsent attachments).
- × icon top-right; click removes from queue (bytes are abandoned in JS memory; nothing to clean up since uploaded files are content-addressed and only get GC'd at session delete).
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

**Scope:** project-level `PluginSettings.State` (not application-level). Mirrors existing `documentMaxChars`/`documentTimeoutMs`/etc. patterns in the same file.

| Field | Default | Description |
|---|---|---|
| `imageMimeWhitelist` | `["image/png","image/jpeg","image/webp","image/heic","image/heif"]` | Mirrors Cody's whitelist. Editable list. |
| `imageMaxBytes` | `5242880` (5 MB) | Per-attachment cap. Editable. |
| `imagesPerTurnCap` | `2` | Mirrors Cody's per-turn cap. Editable. |
| `enableImageInput` | `true` | Kill switch. Disabling hides paperclip + rejects paste/drag-drop. |
| `imageTokenEstimateDefault` | `1500` | For pre-send budget warnings. Authoritative cost is `usage.prompt_tokens` from response. |

**Test convention:** add `core/src/test/kotlin/.../PluginSettingsImageFieldsTest.kt` mirroring the existing `PluginSettingsDocumentFieldsTest.kt` pattern (round-trip serialization + default values + edit-and-persist).

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

- **Phase 1 (defensive parser):**
  - "v1 session JSON loaded by v2 reader without throwing; unknown content-block discriminator deserializes to `ContentBlock.Text('[unsupported attachment]')`."
  - "Hand-rolled `{\"type\":\"some_future_type\",...}` snippet inside a content array round-trips through `ApiMessage.toChatMessage()` to placeholder text without exception."
- **Phase 2 (ModelCatalogService):**
  - "On startup, ModelCatalogService caches the catalog; `getContextWindow('claude-sonnet-4-5-latest', 'enterprise')` returns 132000 (NOT 45000)."
  - "Plugin starts, gateway is unreachable, model picker shows empty + retry affordance (no hard-coded fallback)."
  - "ModelCatalogService refreshes after TTL expiry; deprecated status surfaces in next picker open."
- **Phase 3 (SSE client):**
  - "POST to `/.api/completions/stream?api-version=8` with canonical Cody body returns SSE; parser accumulates `deltaText` from N completion frames."
  - "End-of-stream: parser terminates within bounded time when `event: done` is sent BEFORE connection close (fake-server test)."
  - "End-of-stream: parser terminates on `data: [DONE]` if Cody ever sends it."
  - "End-of-stream: parser terminates on connection close as final fallback."
- **Phase 4 (persistence + per-session attachments):**
  - "User attaches image in session A; file lands at `sessions/A/attachments/<sha256>.png`."
  - "Same image re-attached in same session A: sha256 collides; only one file on disk; both content blocks reference same path."
  - "Same image attached in session B: separate file at `sessions/B/attachments/<sha256>.png` (per-session, no cross-session dedup in v1)."
  - "`MessageStateHandler.deleteSession(A)` removes `sessions/A/` recursively including attachments; session B intact."
  - "Old `ContentBlock.Image(mediaType, data)` inline-base64 shape on disk migrates to ref shape on first read; no data loss."
- **Phase 5 (UI):**
  - "User pastes image into RichInput; chip appears in input area."
  - "User pastes image > imageMaxBytes; client-side rejection toast fires immediately, no bridge round-trip."
  - "User attaches image to non-vision model, Send fails with toast, user removes chip and re-Sends successfully (chip persists across rejection)."
  - "User attaches image to vision model, model is downgraded mid-iteration, Send fails fast with vision-disabled toast (no garbled request)."
  - "User attaches HEIC on macOS; verify JBCef Chromium presents correct MIME (probe sub-task within Phase 5 — may force re-encode to JPEG)."
- **Phase 6 (routing + workaround):**
  - "User sends text-only turn: routes to `/.api/llm/chat/completions` (existing path; no behavior change)."
  - "User sends image-only turn: routes to `/.api/completions/stream`; assistant reply renders normally."
  - "User sends image + tools turn: two-step workaround engages; step 1 succeeds; step 2 emits tool_calls; assistant message renders with 📷 badge."
  - "User sends image + tools turn; step 1 times out: error toast, no step 2, no badge, no garbled tool call."
  - "User sends image + tools turn; step 1 succeeds but model abstains ('I cannot see this image'): error toast, no step 2."
  - "Session has 50 turns, 25 with images; ContextManager triggers compaction at 85%; in-context payload sent to model has 0 image bytes (all replaced with placeholder text); on-disk JSON unchanged."
- **Phase 7 (UI polish):**
  - "Model picker shows '132K context · 18K per-message' for Sonnet, '93K' for Sonnet-Thinking."
  - "Chat input indicator pulls value from `ContextManager.utilizationPercent()` (single source of truth — not a parallel calculation)."
  - "Indicator color shifts: gray <50%, amber 50-80%, red >80%."
  - "Model with `status: deprecated` shows ⚠ badge in picker."

### Code review per phase

Dispatch Opus code-reviewer subagent on each phase's commits. Block next phase until review passes.

### Build-cache trap (per CLAUDE.md)

Phases 2 (ModelCatalogService — coroutine-based HTTP fetcher), 3 (SSE parser), and 6 (BrainRouter) all touch coroutine code with `suspend`-typed lambdas. Run tests with `--no-build-cache --rerun-tasks` to avoid stale `Function0` bytecode against new test compile (per the documented Phase 4 D8b incident in CLAUDE.md).

## What's NOT in scope

- **RepoPart** — user explicitly declined (capabilities_lab P5 also failed; not viable on this instance without indexed repos).
- **FilePart with URI-only auto-fetch** — capabilities_lab P4 disproved; Sourcegraph does not fetch from URIs server-side.
- **`/.api/completions/code`** — single-shot, strictly less useful than `/stream`.
- **Cody Agent JSON-RPC integration** — adds operational complexity (embedded Node process) for capability we already reach via plain HTTP.
- **Custom retry/backoff for `tools` on `/stream`** — fix is routing, not retry.
- **`api-version=9` upgrade** — defer until a v9-only feature is needed.
- **Tools support on `/.api/completions/stream`** — silently dropped per probe (P6 SAW_NO); cannot be made to work client-side.

## Spec review iteration log

This spec was reviewed by an Opus sub-agent against the actual codebase (not just the spec text). The review caught several issues that have been folded into the spec above. Recording for posterity:

**Critical issues addressed (would have broken implementation):**
1. **Type model split** — original spec only modeled the on-disk `ContentBlock` shape and missed the in-process `ChatMessage.content: String?` flat type. Fix: added §"Type model evolution" section spelling out the three types (`ChatMessage`, `ApiMessage`/`ContentBlock`, wire body) and how `ChatMessage.parts: List<ContentPart>?` lets routing read image presence without breaking call sites. Also caught that `ContentBlock.Image(mediaType, data)` already exists as dead code — Phase 4 repurposes (not replaces) it.
2. **Sealed-interface forward-compat** — original spec assumed `Json { ignoreUnknownKeys = true }` would let v1 readers handle v2 polymorphic discriminators. It does NOT — that setting only covers unknown *fields within* known classes, not unknown subclass discriminators. Fix: Phase 1 explicitly ships `JsonContentPolymorphicSerializer` returning `Text("[unsupported attachment]")` for unknown discriminators.
3. **Compaction thresholds** — original spec said 50/75/90; actual code is 70/85/95. Fixed.

**Important issues addressed:**
- Model-downgrade race (Decision 3 corner case): added explicit handling in §Two-step workaround.
- Two-step workaround failure modes: added explicit `runCatching` + abstention detection + error toasts.
- Phase 1+4 ordering: reframed for single-user threat model; user picks during plan-writing whether to merge.
- GC + dedup: dropped cross-session content-addressing for v1; per-session attachments only. No orphan risk.
- JCEF bridge IPC: chunked-by-sha256 with `CefResourceSchemeHandler`-served upload endpoint; bridge stays text-only.
- RichInput paste: must integrate INSIDE existing `handlePaste` (not sibling listener), since existing handler calls `preventDefault`.
- Validation timing: client-side size check before bridge call (no IPC for oversize).
- PluginSettings: scope clarified to `PluginSettings.State`; test convention specified.
- SSE termination: three signals (event:done OR [DONE] OR connection close), whichever first.

**Minor issues deferred to plan-writing:**
- Wire chat-input indicator to `ContextManager.utilizationPercent()` (single source of truth), not parallel calculation.
- Refactor immutable `data class ChatMessage` with `.copy()` in two-step workaround pseudocode (not invented `Turn` API).
- Phase 4 acceptance test must be runnable without UI (inject bytes via test harness).
- `EnvironmentDetailsBuilder` and tool-result context: image presence is NOT surfaced to other tools — explicit no.
- Cody parity: input doesn't need to be focused for paste (paste anywhere in chat panel).
- Wire `?api-version=N` from `ModelCatalogService.getLatestStreamApiVersion()` from day 1 instead of hard-coding `=8`.

## Open questions for user review

None unresolved. All 8 design decisions + bonus + 11 spec-review fixes incorporated. Awaiting user review of this doc before invoking writing-plans skill.
