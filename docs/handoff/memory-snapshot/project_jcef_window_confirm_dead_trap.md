---
name: project_jcef_window_confirm_dead_trap
description: "Native window.confirm/alert/prompt don't work in the JCEF webview — they silently no-op; use inline two-step confirm or a styled modal instead"
metadata: 
  node_type: memory
  type: project
  originSessionId: b9c4d15a-400b-4615-a1c9-9a30ac1569d9
---

**FIXED 2026-05-27 (uncommitted): "Checkpoint to here" button did nothing.**

Root cause: `UserMessageRevertButton.tsx` gated the revert on `if (window.confirm(...))`. In the JCEF (embedded Chromium) webview there is **no `CefJSDialogHandler` registered** anywhere in the Kotlin code, so native `window.confirm()` never shows a dialog and returns `false` → the `_revertToUserMessage` bridge call never fired. Same latent bug in `EditStatsBar.tsx` ("⟲ Revert all").

**Why it shipped green:** the unit test stubbed it — `vi.spyOn(window,'confirm').mockReturnValue(true)`. jsdom also logs "Not implemented: Window's confirm() method". Tests masked the exact production behavior that was broken — only runIde smoke would catch it.

**Why:** the entire rest of the production webview already avoids native dialogs — `AttachmentManager.ts` comments say `window.confirm` is a "test/harness" fallback and "Production wires a styled modal"; `SessionCard`/`HistoryView`/`use-action-buttons` use inline two-step confirm; `LinkConfirmModal` is the styled-modal option.

**How to apply:** NEVER use `window.confirm/alert/prompt` in webview production paths. Use the inline two-step confirm pattern (click → swaps to Confirm/Cancel, auto-dismiss ~4s) or `LinkConfirmModal`. The fix replaced both revert sites with the inline two-step (`lucide` Check/X), pinned by `user-message-revert.test.tsx` + `edit-stats-bar.test.tsx` (now assert `window.confirm` is NOT called). After any webview fix: `npm run build` to refresh `agent/src/main/resources/webview/dist/`. Related: [[project_chat_file_document_attachments]], [[reference_jcef_implementation]].
