# Multimodal Agent — Session Handoff

**Date written:** 2026-05-02 (end of session 1 of multi-session implementation)
**Branch:** `feature/context-compaction`
**Branch HEAD:** `ee035f7e` (synced with `origin/feature/context-compaction`)
**Pipeline:** 4 of 7 implementation phases complete; Phases 5–7 + E2E test + final report deferred to next session

This document is the single source of truth for resuming the multimodal-agent work in a fresh session. Read this first; the plan/spec/baselines are linked below for deep dives.

---

## TL;DR — what to say to start the next session

> Continue multimodal-agent implementation. Branch `feature/context-compaction` HEAD is `ee035f7e`. Plan + spec + baselines committed. **Phases 1–4 done with full Opus reviews and followups.** Next: Phase 5 (JCEF chat image-attachment surface) per `docs/research/2026-05-02-multimodal-agent-plan.md` Phase 5 section. **CSP fix (Task 5.0) MUST be the first commit** — without it, the entire fetch-based upload path silently fails at smoke test. After Phase 5, Phases 6 and 7, then E2E test pass, then final report. All implementation is subagent-driven with Opus + per-phase Opus code review.

---

## Current state

### Completed this session (8 commits on `feature/context-compaction`)

| Commit | Phase | Summary |
|---|---|---|
| `1cc7dfca` | 1 (impl) | `UnsupportedContentBlock` polymorphic fallback so v1 readers don't crash on unknown `ContentBlock` discriminators |
| `7ba77755` | 1 (followup) | `UnsupportedContentBlock` — corrected lossy-serialize KDoc per code review |
| `de831b35` | 2 (impl) | `ModelCatalogService` + per-model context budget; `ContextManager.maxInputTokensFor(modelRef)` |
| `83f7344e` | 2 (followup) | `ModelCatalogService` — lock in `AuthScheme.TOKEN` contract test |
| `98b469c2` | 3 (impl) | `SourcegraphCompletionsStreamClient` + `CodyStreamSseParser` (3-signal termination, cumulative/delta disambiguation) |
| `0a09316f` | 4 (impl) | Persistence schema: `AttachmentStore`, `ContentBlock.ImageRef`, `ChatMessage.parts`, `ApiHistoryFile` v1↔v2 migration |
| `ee035f7e` | 4 (followup) | `AttachmentStore` — explicit per-session isolation assertion |

Plus 4 design/plan commits earlier in the session: `7d3b2267` (spec), `f323d54c` (spec v2), `e59cd7e6` (plan v1), `058f9140` (plan v2 after Opus REJECT).

### Test totals at handoff

`./gradlew :core:test`  →  **780 tests, 0 failures, 0 errors**
`./gradlew :agent:test` → **3110 tests, 0 failures, 0 errors**

(Run `:core:test` and `:agent:test` SEPARATELY — combined `./gradlew :core:test :agent:test` hits a `prepareTestSandbox` race in Gradle 9.)

### `verifyPlugin` status

**Pre-existing FAIL** unrelated to this work — `ideaIU-2025.1.7.1-aarch64.dmg` checksum mismatch in `gradle/verification-metadata.xml`. The JetBrains IDE installer DMG checksum needs a metadata refresh; outside the multimodal-agent scope. Every phase noted this; the implementer doesn't need to fix it.

### Branch state

`feature/context-compaction` is up to date with `origin`. **Nothing has been released.** Per `feedback_release_timing.md`, do NOT bump `pluginVersion` or run `gh release create` unless the user explicitly asks.

---

## Authoritative documents (in priority order for the next session)

1. **`docs/research/2026-05-02-multimodal-agent-plan.md`** (commit `058f9140`) — the 3,903-line implementation plan. **Phase 5 section is what the next session executes first.** Plan Revision Log at the top documents every change made after the Opus plan-reviewer's REJECT verdict.
2. **`docs/research/2026-05-02-multimodal-agent-design.md`** (commit `f323d54c`) — the 549-line design spec. 8 user-confirmed design decisions + spec-review iteration log.
3. **`docs/research/2026-05-02-multimodal-agent-handoff.md`** — THIS document.
4. **`tools/sourcegraph-probe/baselines/{vision_lab,capabilities_lab,client_config,model_catalog}_2026-04-22_sourcegraph-6.12.json`** — probe baselines that drove every design decision. Use as regression evidence if Sourcegraph behavior ever changes.
5. **Memory files (loaded automatically):** `reference_sourcegraph_image_transport.md`, `reference_sourcegraph_internal_api_full_inventory.md`, `project_sourcegraph_isolation.md` — load-bearing context the implementers and reviewers rely on.

### Project conventions (loaded automatically via CLAUDE.md)

- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/CLAUDE.md` — root-level project conventions
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/CLAUDE.md` — `:agent` module specifics

---

## Phase 5 next — what the next session must do FIRST

**Goal per plan:** "User-visible image attachment via paperclip + paste + drag-drop. Thumbnail chip with × removal. Vision-disabled error toast at Send. Settings UI for MIME whitelist + size cap. JCEF bridge methods for attach/detach using the chunked-by-sha256 IPC pattern."

### Critical prerequisite: Task 5.0 (CSP fix)

**This MUST be the first commit of Phase 5.** Without it, the entire `fetch()` upload path Phase 5 builds on top of silently fails at smoke test (the chip appears in the UI, the user hits Send, the `fetch('http://workflow-agent/upload/<sha256>')` is blocked by `connect-src 'none'` CSP, no bytes ever reach disk, and Phase 6 routing reads a missing-attachment error).

**The fix:** modify `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandler.kt` (currently around line 139). Change `connect-src 'none'` to `connect-src 'self' http://workflow-agent`. Verify in DevTools console:
```javascript
fetch('http://workflow-agent/upload/test', {method: 'POST', body: 'x'})
  .then(r => console.log(r.status))
```
Expected: not blocked by CSP (returns some HTTP status, possibly 404 since AttachmentUploadHandler isn't wired yet — that's Task 5.4).

### Phase 5 task order per plan

Tasks 5.0 → 5.8 in `docs/research/2026-05-02-multimodal-agent-plan.md`:

1. **5.0** CSP relax (above) — separate commit, prerequisite
2. **5.1** `PluginSettings.State` image fields — use `by property(...)` delegate syntax (NOT `by 5_242_880L` which is invalid Kotlin); `imageMimeWhitelist` uses `by stringList()` per existing convention
3. **5.2** `MultimodalSettingsConfigurable` + `plugin.xml` registration — `parentId="workflow.orchestrator"` (NOT `com.workflow.orchestrator.settings.workflow` which is phantom)
4. **5.3** `AttachmentUploadHandler` (CEF resource handler serving `http://workflow-agent/upload/<sha256>` POST)
5. **5.4** Wire CEF resource handler into `AgentCefPanel` + add `attachmentExistsQuery` bridge
6. **5.5** TypeScript `AttachmentManager` (sha256 + size validation + uploadAll via `fetch`)
7. **5.6** `<ChipPreview>` component + `InputBar.tsx` paperclip (extend the existing `Plus` DropdownMenu — add an "Image" item — NOT a sibling button) + `RichInput.tsx` paste handler (must integrate INSIDE existing `handlePaste`, not a sibling listener)
8. **5.7** Vision-disabled toast on Send (Decision 3)
9. **5.7a** HEIC MIME probe sub-task (verify JBCef Chromium 122-ish surfaces `image/heic` correctly; if not, drop HEIC from default whitelist or document client-side re-encode-to-JPEG fallback)
10. **5.8** Single commit with TDD evidence in body

### Phase 5 patterns established by Phases 1–4

The next implementer must reuse these (Phases 1–4 implementers caught and locked them in):

1. **Direct `OkHttpClient.Builder()` for any HTTP work** (NOT `HttpClientFactory.clientFor()`) — Sourcegraph isolation policy per `project_sourcegraph_isolation.md` memory. n/a for Phase 5 (no HTTP) but worth knowing.
2. **TDD red→green discipline visible in commit body.** Phases 2, 3, 4 all documented "X test was written first; observed failing with Y; implementation made it pass" in the commit message. Phase 5 must do the same.
3. **Build-cache trap.** Any `suspend` signature change requires `--no-build-cache --rerun-tasks`. Phase 5 adds suspend bridge methods (likely) — apply the flag.
4. **Add tests beyond plan minimum.** Phase 1 added 1, Phase 2 added 7, Phase 3 added 6, Phase 4 added 12. Each implementer treated the plan as a floor; locked in additional contracts (auth-header lockdown pattern is the canonical example).
5. **Per-session isolation is the cornerstone.** Phase 4's `AttachmentStore(sessionDir)` takes the session dir as constructor param; never share state across sessions. Phase 5's UI must construct the store from the active session per `currentSessionDir()`.
6. **Single commit per phase is acceptable.** Per CLAUDE.md "fix root functions; consolidate over parallel paths" + `feedback_skip_subagent_reviews` on iteration speed.

### Patterns Phase 6 (after Phase 5) will need

The Phase 4 implementer surfaced these explicitly for Phase 6:

1. **`ApiMessage.toChatMessage()` text-flattens `ImageRef` to `[image: <mime>, <size> bytes]`.** Phase 6 must extend the converter (or add a parallel converter) to populate `ChatMessage.parts` when `ApiMessage.content` carries `ImageRef` blocks — otherwise the routing predicate (`hasImageParts()`) will always see `parts == null` and never engage the vision brain.
2. **Routing predicate is `ChatMessage.hasImageParts()`** (extension function in `core/ai/dto/ContentPart.kt`).
3. **`HttpException(statusCode, message)` is now a typed throw on `SourcegraphCompletionsStreamClient`.** BrainRouter should branch on `statusCode == 401/413/429` (e.g. 413 = context-length-exceeded → trigger compaction-and-retry).
4. **On-disk vs on-wire discriminator distinction:**
   - On-disk: `@SerialName("image_url_ref")` (Phase 4)
   - On-wire (Cody stream): `{"type":"image_url",...}` (Phase 3)
   - These look similar but live in different sealed hierarchies. Phase 6's converter must NOT cast — it must translate.
5. **`ModelCatalogService` and `getLatestStreamApiVersion()` are now `open`** (Phase 3 deviation) so test fakes can extend them. Phase 6's BrainRouter tests can use the same pattern.

---

## Followups tracked from per-phase reviews (not blocking; address when relevant)

| # | Item | Phase to address | Cost | Notes |
|---|---|---|---|---|
| F1 | `stopReason` dead-wired in `CodyStreamSseParser` → `CompletionStreamResult.stopReason` is always `null` | Phase 6 | ~30 lines | First consumer is BrainRouter retry/length-truncation logic; better to land where the consumer lives. Add `ParseResult.StopReason(reason: String)` variant + plumbing through to `CompletionStreamResult`. |
| F2 | `ModelCatalogService` mutex contention (catalog + config share one mutex; 30s timeout on either blocks the other) | Phase 6/7 | ~30 lines | Cold path today. Phase 6 image-token estimation per call + Phase 7 chat-input usage indicator polled on keystroke will surface it. Fix: split into `catalogMutex` + `configMutex`. |
| F3 | `AttachmentStore.read()` glob-by-prefix could match leaked `.tmp` files | Phase 5 cleanup | ~5 lines | Functionally benign (identical bytes); fix when wiring into UI. Add `.tmp.` filter in `read()` OR sweep on init. |
| F4 | `:agent` CLAUDE.md docstring drift (mentions hard-coded 150K) | Phase 7 docs sweep | 1 line | Pure docs. Update to mention per-model `maxInputTokensFor()` + 90K fallback. |
| F5 | `HttpException` placement (could move to its own file) | Phase 6 | hoist | Defer until BrainRouter has cross-class consumer in `:agent`. |
| F6 | MIME inconsistency on dedup (different MIME, same content → on-disk extension is from first store) | v2 deferred | medium | Cosmetic; doesn't affect correctness. Document in `AttachmentStore` KDoc. |
| F7 | `lastEvent` dead variable in `CodyStreamSseParser` | Phase 6 cleanup | 1 line | Pure cleanup. |
| F8 | `isEmptyAssistant` `else -> false` could be explicit `is UnsupportedContentBlock -> false` | Phase 5 cleanup | 1 line | Pure documentation-of-intent. |

---

## Pipeline tasks remaining

These were created via TaskCreate during this session and are persisted in the task tool. Next session should resume them.

| Task # | Status | Subject |
|---|---|---|
| 19 | pending | Phase 5 implementation: JCEF chat image-attachment surface |
| 20 | pending | Code review Phase 5 (Opus) |
| 21 | pending | Phase 6 implementation: routing + image+tools two-step workaround |
| 22 | pending | Code review Phase 6 (Opus) |
| 23 | pending | Phase 7 implementation: model picker capacity + chat input usage indicator + deprecated badge |
| 24 | pending | Code review Phase 7 (Opus) |
| 25 | pending | End-to-end test pass (all phases) |
| 26 | pending | Final report back |

The Tasks 1–18 are all completed.

---

## Key file paths the next session will modify

### Phase 5 — UI

**New:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AttachmentUploadHandler.kt` (CEF resource handler)
- `agent/webview/src/components/input/ChipPreview.tsx`
- `agent/webview/src/components/input/AttachmentManager.ts`
- `core/src/main/kotlin/com/workflow/orchestrator/core/settings/MultimodalSettingsConfigurable.kt`
- `core/src/test/kotlin/com/workflow/orchestrator/core/settings/PluginSettingsImageFieldsTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/AttachmentUploadHandlerTest.kt`

**Modified:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandler.kt` (CSP fix — Task 5.0)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt` (resource handler registration + bridge queries)
- `agent/webview/src/components/input/InputBar.tsx` (paperclip + drag-drop)
- `agent/webview/src/components/input/RichInput.tsx` (paste integration INSIDE existing `handlePaste`)
- `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt` (image fields)
- `src/main/resources/META-INF/plugin.xml` (`projectConfigurable` registration with `parentId="workflow.orchestrator"`)

### Phase 6 — Routing + workaround

**New:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/BrainRouter.kt` (note: lives in `:agent`, NOT `:core` — DAG violation otherwise)
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/BrainRouterTest.kt`

**Modified:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt` (replace direct `brain.chatStream()` call at line 643 with `brainRouter.chatStream(...)`)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt` (image-token estimation; compaction strips image parts)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackManager.kt` (vision-capability filter when payload has images — Task 6.3a)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/ApiMessage.kt` (extend `toChatMessage()` to populate `ChatMessage.parts` from `ImageRef`)
- `agent/webview/src/components/chat/AgentMessage.tsx` (📷 image-analyzed badge — note real path is `chat/AgentMessage.tsx`, NOT `messages/AssistantMessage.tsx` which the original plan had wrong)

### Phase 7 — Polish

**Modified:**
- Model picker dialog component (find via `grep -rn "modelRef\|model-picker\|ModelPicker"`)
- `agent/webview/src/components/input/InputBar.tsx` (chat input usage indicator)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt` (`getContextUsageQuery` bridge)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt` (expose `currentInputTokens()` + `currentModelRef()` if not added in Phase 6)

---

## Review pipeline conventions established this session

- **Implementer subagent:** general-purpose, **Opus**, foreground (no `run_in_background`).
- **Code reviewer subagent:** general-purpose, **Opus**, foreground, with explicit "no background tasks, run Bash synchronously, time out at 5 min per command" instruction (a prior Phase 2 reviewer stalled in a background-wait loop until SendMessage rescue).
- **Per-phase verdict format:** Critical issues / Important issues / Minor issues / Deviations assessment / Strengths / Verification I performed.
- **Followup commits:** small, focused, with commit message referencing the review.
- **TDD evidence:** in commit body. Phase 1 reviewer noted single-commit hides red→green; Phases 2–4 each documented "test was written first; observed failing with X" in body.

---

## Strategic context (read once, then refer to memory)

- **Routing rule:** text-only or text+tools → `/.api/llm/chat/completions` (existing path, unchanged); image-only turns → `/.api/completions/stream` (Phase 3's `SourcegraphCompletionsStreamClient`); image+tools combined → two-step workaround (vision-summarize on /stream, then tools call on /chat/completions, badge appears).
- **Why HYBRID and not full migration:** capabilities_lab P6 + P7 confirmed the gateway **silently drops** the `tools` field on `/.api/completions/stream`. Switching the agent ReAct loop to /stream would silently break tool calling. Spec/plan are explicit about this — DO NOT revisit.
- **Single-user threat model:** the user is the only installer. Phase 1+4 ordering (forward-compat read shipped before v2 writes) is preserved as audit-trail discipline, NOT for soak time.
- **Sourcegraph isolation policy** (per memory): Sourcegraph endpoints (`/.api/*`) bypass `HttpClientFactory.clientFor()` because shared `CachingInterceptor` + connection pool are incompatible. Use direct `OkHttpClient.Builder()` + `AuthInterceptor(tokenProvider, AuthScheme.TOKEN)`. Phases 2 + 3 both follow this pattern.
- **`AuthScheme.TOKEN` not BEARER:** Sourcegraph emits `Authorization: token <sgp_...>`. The Phase 2 followup `83f7344e` added an explicit test that pins this contract — Phase 3's mirror test `production httpClient default emits Authorization token header per Sourcegraph contract` follows the same pattern. Phase 5+6 should preserve it for any new HTTP-bearing class.

---

## Risk register (still active)

| Risk | Mitigation already in place | Phase to monitor |
|---|---|---|
| HTTP/1.1 keepalive after final `event: completion` (no termination signal) | `CodyStreamSseParser` terminates on whichever first: `event: done` / `data: [DONE]` / EOF (3 dedicated tests) | Phase 6 |
| Build-cache trap on suspend signature changes | All test commands use `--no-build-cache --rerun-tasks` | Phase 5+6 (suspend bridge methods + BrainRouter signature) |
| CSP `connect-src 'none'` blocks fetch upload | Task 5.0 (NEW) explicitly relaxes for `workflow-agent` scheme | Phase 5 |
| Cross-file write atomicity (attachment file + JSON ref) | `AttachmentStore.store()` atomic-move first; cross-file order documented in KDoc | Phase 5 (UI must follow contract) |
| Atomic move + sha256 race when two paste events fire concurrently | Atomic-move + sha256 = same content → same path; concurrent identical writes converge | Phase 5 |
| Polymorphic forward-compat broken on new ContentBlock additions | Phase 1's `UnsupportedContentBlock` fallback inherits to v2 reader (verified by SchemaMigrationTest) | All future phases |
| `JBCefJSQuery` IPC hangs on multi-MB string payloads | Chunked-by-sha256 path through `AttachmentUploadHandler` (HTTP-style upload), bridge stays text-only | Phase 5 |
| HEIC MIME mapping in JBCef Chromium 122 unverified | Phase 5 Task 5.7a probe sub-task | Phase 5 |
| Two-step workaround step 1 abstention ("I cannot see this image") + step 2 garbage tool call | `BrainRouter.twoStepWorkaround` runs explicit abstention check before step 2 | Phase 6 |
| `ModelFallbackManager` swaps to non-vision model mid-iteration with image in payload | Phase 6 Task 6.3a adds `fallbackChainForVision(catalog)` filter | Phase 6 |
| Mutex contention on `ModelCatalogService` (one mutex for catalog + config) | F2 followup; cold path today | Phase 6/7 |

---

## Session 1 retrospective (for the next session, brief)

What went well:
- Spec + plan + dual sub-agent reviews caught 11+ critical issues before any code landed
- Each implementer self-corrected real plan defects (Phase 1: 4-Json-instances fix; Phase 2: Sourcegraph isolation policy; Phase 3: cumulative-completion handling; Phase 4: build-cache trap actually triggered)
- Followups stayed small and contract-locking
- 0 regressions across all 4 phases (3890 tests still pass)

What could improve:
- Phase 4 implementer hit an API stream-idle-timeout mid-implementation; SendMessage resume worked but burned ~5 min of dispatch time — consider sending the implementer a `--prioritize-writing-code-over-investigating` hint up front for high-bandwidth phases
- Two reviewers per phase produces overlapping but non-identical findings (Phase 2 had this happen accidentally via stalled-then-retry); for high-stakes phases (Phase 5 because of UI surprises, Phase 6 because of integration), consider deliberately dispatching two reviewers in parallel and unioning findings
- Plan v1 had 7 critical phantom-API issues that were caught and fixed in plan v2; Phase 4 implementer still reported plan-vs-real-API divergences — even after one round of plan revision, some drift remains. Phase 5's UI work has the most surface area (settings, JCEF bridge, React, plugin.xml) and the highest divergence risk; the next session should expect to spend extra orchestration time validating plan signatures against the actual codebase before each task.

---

## Final notes

- The wire/persistence layer (Phases 1-4) is **COMPLETE** and tested. Image bytes can be stored on disk, the JSON schema can carry `ImageRef` references, the gateway client can send images to `/.api/completions/stream`, and `ContextManager` can read per-model budgets. The ENTIRE backend foundation for image input is in place.
- Phases 5–7 just expose this foundation through the UI. Even if Phase 5 takes a full session, the architectural risk is now very low.
- Per `feedback_release_timing.md`: do NOT release any phase without explicit user request. Each phase commits to the branch; release happens later.
- Per `feedback_no_coauthor.md`: no Claude trailer in commits.
- Per `feedback_work_on_current_branch.md`: stay on `feature/context-compaction`. Do NOT create worktrees or branch off main.
- Per `feedback_always_subagent.md` + user's session 1 instruction: subagent-driven dev with Opus + per-phase Opus code review. Skip the spec/quality reviewers per `feedback_skip_subagent_reviews.md` — the per-phase code reviewer is enough.

End of handoff.
