---
name: BrainRouter two-step workaround SHIPPED removal
description: 2026-05-05 PR series on feature/context-compaction — format_lab probe at api-version=9 enabled deletion of the two-step image+tools workaround. 9 commits, ~520 LOC removed, image+tools now single round-trip.
type: project
originSessionId: 3fc7bf3b-1d53-46e9-a51e-ab5533cded79
---
**SHIPPED 2026-05-05** on `feature/context-compaction` as a 9-commit PR series.

**Motivation:** format_lab probe (2026-05-05) at api-version=9 verified that:
1. Sourcegraph forwards `tools` field on `/.api/completions/stream` and emits
   tool calls back as `delta_tool_calls` SSE frames (12/12 vision-model cells PASS).
2. Sourcegraph rejects HEIC/HEIF with `event: error` frames despite Cody UI
   advertising support (0/6 cells PASS).
3. 58/96 cells return HTTP 200 + `event: error` for unsupported formats —
   previously silent empty bubbles to users.

**Why:** the BrainRouter two-step image+tools workaround was built against the
2026-04-22 capabilities_lab finding that `/stream` silently dropped `tools` at
api-version=8. That assumption no longer holds; the workaround was ~520 lines
of dead code paired with 5 hardening commits (14361e88, 66f757f0, fa005e43,
b4d0fb36, 4e3f607d) all fixing the workaround itself.

**How to apply:** when extending BrainRouter or adding new wire-format probes:
- Re-probe format_lab against any new Sourcegraph version before assuming
  baselines from a prior version still hold.
- The "router-step1-" synthetic-response prefix in AgentLoop's exemption
  list is dead code post-this-PR — safe to remove in a future cleanup.
- `onAnalyzedImageBadge` callback on BrainRouter constructor is kept for
  ABI compat but never invoked — can be removed in a breaking-change cleanup.
- HEIC/HEIF storage layer mappings in `AttachmentStore.mimeToExtension` and
  `AttachmentReadHandler.mimeFromExtension` are intentionally kept (admins
  can extend the whitelist via Settings if Sourcegraph adds support).

**Commit sequence:**
1. `8162360b` — refresh capabilities_lab baseline against api-version=9
2. `65a03c23` — CodyStreamSseParser surfaces gateway error frames
3. `647f3f38` — CompletionStreamResult.rejectionReason
4. `f6f509f4` — BrainRouter renders Sourcegraph rejection as assistant message
5. `629ba532` — drop HEIC/HEIF from imageMimeWhitelist default
6. `65e5fd0a` — webview alignment (InputBar accept= + JS defaults)
7. `0597d63c` — CompletionStreamRequest carries tools, parser emits delta_tool_calls
8. `b207efce` — DELETE BrainRouter.twoStepWorkaround (HEADLINE)
9. (this commit) — docs + memory alignment

**Net diff:** -417 / +149 lines in BrainRouter.kt; whitelist changes touched
PluginSettings.kt + MultimodalSettingsConfigurable.kt + InputBar.tsx +
mock-bridge.ts + AgentCefPanel.kt; new baseline file.

**Latency win:** image+tools turns went from ~2.5s (step 1 vision-summarize +
step 2 chat completion) to ~1.5s (single round-trip).

**Tests:** 3221/3223 :agent:test pass (2 unrelated pre-existing
ToolSearchRelatedTest failures present before this PR). Webview build clean.
