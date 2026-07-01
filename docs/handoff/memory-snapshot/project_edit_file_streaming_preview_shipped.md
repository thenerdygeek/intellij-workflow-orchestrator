---
name: edit-file-streaming-preview-shipped
description: "SHIPPED 2026-05-21 on bugfix — 2-commit feature (3239333dd + f317f806b) that moves edit_file validation upstream of the approval gate AND streams the diff into JCEF chat as the LLM emits new_string. Cline DiffViewProvider port, JCEF-surface-only for v1."
metadata: 
  node_type: memory
  type: project
  originSessionId: 247bae41-48b3-4558-b20f-4db2cbaf460c
---

**SHIPPED 2026-05-21 on `bugfix` branch — `edit_file` streaming preview + upstream validation**

Two-commit feature, both on `bugfix`, ready for PR. Designed for independent revertability:

- **Commit 1 — `3239333dd`** `fix(agent): validate edit_file upstream of approval gate + real line numbers + clickable filename`
  - `EditFileTool.preview()` companion — pre-validation + real file-anchored unified diff. Returns `EditPreview.ValidationFailed` (skip approval card; `execute()` will surface the precise error) or `EditPreview.Ready(realDiff, matchStartLine)`.
  - `AgentController.approvalGate` hoists `parsedArgs` above the `pendingApproval` reentry guard so the early-return on `ValidationFailed` doesn't leak a half-installed deferred.
  - `DiffHtml.tsx` parses `startLine` from the first `@@ -L,N` header; both filename render sites become clickable hyperlinks via the existing `window._navigateToFile` bridge from commit `3ddf6f0dd`.

- **Commit 2 — `f317f806b`** `feat(agent): stream edit_file diff into chat preview as LLM generates it`
  - `StreamingEditTracker` — per-AgentLoop-call state machine. Heuristic for "old_string is complete": the param map contains a `new_string` key (parser opens that slot only after `</old_string>` closes). Reuses `EditFileTool.preview()` from Commit 1 for validation. 100ms throttle, dedup via `lastEmittedNewString`. `cancelAll()` on stream-interrupt or AgentLoop cancel.
  - `AgentLoop` — new optional ctor param `streamingEditCallback: StreamingEditCallback?` + per-loop tracker instance. `onChunk` reads `PluginSettings.State.enableStreamingEditPreview` once per iteration, iterates parsed blocks, fires `tracker.observe(callId, params, partial)`. Purely additive — `git revert HEAD` cleanly removes.
  - `PluginSettings.enableStreamingEditPreview = true` — kill switch. Settings checkbox under `AgentAdvancedConfigurable` ("AI Agent > Advanced > Tool Calling").
  - JCEF bridge: `_streamingEditOpen / _streamingEditUpdate / _streamingEditFinalize / _streamingEditCancel`. `chatStore.streamingEdits` slice + `<StreamingEditPreviewView />` mounted in `ChatFooter`. `endStream()` clears the slice so the approval card never coexists with a live preview.

**Test coverage:**
- `EditFileToolPreviewTest` (10) + `AgentControllerEditPreviewTest` (4 source-text pins, see below) + `DiffHtml.test.tsx` (4) — Commit 1.
- `StreamingEditTrackerTest` (10) + `StreamingEditPreviewView.test.tsx` (7) + `AgentLoopStreamingEditTest` (3 source-text pins) — Commit 2.
- Full `:agent:test` = 3796/3796 after `:agent:clean :agent:test --no-build-cache` (suspend-ctor-param + Function0 bytecode trap documented at CLAUDE.md "Build-cache trap").
- `verifyPlugin` + `npm run build` pass.

**Why:** User complained that the approval gate showed a phantom diff first, then validation ran after approve (so a bogus `old_string` produced "approve a card → see error" UX). Also the diff numbered from line 1 regardless of where in the file the change landed, and the filename header wasn't clickable. Bigger ask: live streaming preview à la Cline.

**How to apply:** Anything in the `edit_file` flow now has a defense-in-depth pattern: `preview()` for UX-side validation, `execute()` re-validates (file could change between preview and execute). The streaming preview is JCEF-surface-only — never touches the real file during stream, real write is still `EditFileTool.execute()` post-stream. If future work wants Cline-style editor-pane streaming (write into a side IntelliJ diff editor's right-hand Document via DiffManager + DocumentContent), that's a follow-up; the JCEF-only v1 was the safer first step.

**Source-text pinning pattern used twice (Commit 1 + Commit 2):** when full behavioural tests would require mocking Project + dashboard + EDT + suspending deferreds, source-text invariant tests (grep the source for the critical literal, assert presence) are an acceptable trade-off — they fail loudly on accidental removal during refactor. The user has explicitly approved this pattern. Examples: `AgentControllerEditPreviewTest.kt`, `AgentLoopStreamingEditTest.kt`.

**Deferred / known limits:**
- No editor-pane streaming (Cline's actual UX). v1 streams into JCEF chat only.
- Smoke test pending — user develops on Mac but tests on a Windows laptop, so end-to-end "watch the diff stream live" verification happens on the Windows install. If a regression surfaces there, `git revert HEAD` removes Commit 2 only (or flip `enableStreamingEditPreview = false` in settings as a no-restart kill switch).
- `callId` is derived as `"edit-{iteration}-{idx}"`. Stable for the lifetime of a single AgentLoop. If a future change starts compaction mid-stream (today compaction is at iteration boundaries only), the prefix could collide — re-derive then.

**Files (Commit 1, 6 files, +550/-11):**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- `agent/webview/src/components/rich/DiffHtml.tsx`
- Tests: `EditFileToolPreviewTest.kt`, `AgentControllerEditPreviewTest.kt`, `DiffHtml.test.tsx`.

**Files (Commit 2, 15 files, +1236/-2):**
- New: `agent/src/main/kotlin/com/workflow/orchestrator/agent/preview/StreamingEditTracker.kt`, `agent/webview/src/components/agent/StreamingEditPreviewView.tsx` + tests.
- Modified: `AgentLoop.kt` (+100, ctor param + onChunk hook + cancelAll), `AgentService.kt` (+16, thread the callback), `AgentController.kt` (+66, push helpers + wire into AgentLoop), `chatStore.ts` (+105 slice + actions), `jcef-bridge.ts` (+41), `globals.d.ts` (+10), `ChatFooter.tsx` (+7 mount), `AgentAdvancedConfigurable.kt` (+15 checkbox), `PluginSettings.kt` (+13 flag), `agent/CLAUDE.md` (+32 docs).

Related: [[brainrouter-stream-downgrade-dialect-trap]] — same area (streaming + tool calls) where past simplifications caused regressions; this work avoids that by not touching the parser or BrainRouter.
