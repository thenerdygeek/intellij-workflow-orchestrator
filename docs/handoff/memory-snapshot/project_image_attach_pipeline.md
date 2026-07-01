---
name: Image-attach pipeline (multimodal-agent) status
description: End-to-end image-attach flow on feature/context-compaction; five root causes fixed in 0.83.47–0.83.52; next session: verify Windows test of 0.83.52
type: project
originSessionId: 98c84ba7-478c-4f6e-b0fb-24a7f59b66ed
---
End-to-end image-attach pipeline went from "click Open does nothing" to working
across five releases on `feature/context-compaction`.

**Why:** User on Windows could not attach images. Each release fixed a distinct
root cause that was masking the next one. The bug was reproducible on Windows
but not Mac because secure-context behavior + CEF threading model differ.

**How to apply:** When the user reports an image-attach regression on this
branch, check the handoff doc at `docs/handoff/2026-05-04-image-attachment-handoff.md`
for the five root causes already fixed. The bug is almost certainly a
sixth one. Verify each link of the pipeline (audit list is in the handoff).

**Five root causes fixed:**
1. 0.83.48 — `terserOptions.drop_console: true` stripped all `[multimodal:attach]` logs (now `false` in `vite.config.ts`)
2. 0.83.49 — `crypto.subtle === undefined` because `http://workflow-agent` is not a secure context (pure-JS sha256 fallback in `AttachmentManager.sha256Hex`)
3. 0.83.50 — `runBlockingCancellable` IllegalStateException on CEF network thread (added `AttachmentStore.storeBlocking`/`readBlocking` synchronous variants)
4. 0.83.51 — Upload arrives ~12ms before `executeTask` creates the session (lazy-allocate `currentSessionId` in `AgentController.setCurrentSessionDirProvider`)
5. 0.83.52 — Sha256 list never reached Kotlin (full pipeline plumbing: JS sendMessage → kotlinBridge.sendMessageWithMentions(text, mentionsJson, attachmentsJson) → AgentCefPanel parses → onSendMessageWithMentions(text, mentionsJson, attachmentsJson?) → executeTaskWithMentions → executeTask(attachments=) → AgentService builds ApiMessage with Text + ImageRef blocks). Also added bubble rendering: `<img src="http://workflow-agent/attachments/{sha}">` served by new `AttachmentReadHandler`.

**Known limitations:**
- Attachments on pending-question reply or plan-mode reply render in bubble
  but don't reach LLM (those channels are text-only). Logs WARN.
- Fallback non-JCEF panel doesn't render attachments.

**Bonus latent fix in 0.83.52:** The `ask_followup_question` no-options watchdog
bug — streaming-token path bypassed `showQuestions` so `_reportInteractiveRender`
was never called. Now sets `AskQuestionsTool.uiRenderConfirmed = true` inline
in the no-options + catch-fallback branches in `AgentController.kt`.

**2026-05-05 update — format-truth + BrainRouter simplification PR:**
9-commit series on `feature/context-compaction` after format_lab probe at
api-version=9 found:
1. Sourcegraph rejects HEIC/HEIF (0/6 vision models) despite Cody's UI
   advertising support → dropped from `imageMimeWhitelist` default.
2. Sourcegraph forwards `tools` field on `/.api/completions/stream` at
   api-version=9 → BrainRouter two-step image+tools workaround DELETED.
   ~520 lines removed. Image+tools now single round-trip through /stream
   with `delta_tool_calls` SSE frames (verified by Haiku probe, all 6 models).
3. `event: error` SSE frames now surface as user-visible assistant
   message ("Sourcegraph rejected this attachment: …") instead of empty
   bubbles. Was 58 of 96 cells silently failing per format_lab.

Net diff: -417 / +149 in BrainRouter.kt; whitelist changes touched 4 files
across `:core` settings + JS webview defaults. New baseline at
`tools/sourcegraph-probe/baselines/capabilities_lab_2026-05-05_*.json`
supersedes 2026-04-22 baseline.
