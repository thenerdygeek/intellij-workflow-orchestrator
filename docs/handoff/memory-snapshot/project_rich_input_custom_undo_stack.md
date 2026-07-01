---
name: project_rich_input_custom_undo_stack
description: Chat input (RichInput.tsx) owns a custom undo/redo stack — do NOT replace with native contentEditable undo.
metadata: 
  node_type: memory
  type: project
  originSessionId: 3b4ada6d-a983-4d0e-8d14-6bdd7dedc81f
---

**ADDED 2026-05-27 (bugfix worktree cross-ide):** The agent chat input `RichInput.tsx` is a `contentEditable` div with inline mention chips, NOT a `<textarea>`. Browser-native Ctrl+Z for contentEditable only tracks user keystrokes + `document.execCommand`; RichInput mutates the DOM directly all over (insertChip, paste-chip path, setText, chip-remove click, removeChipByLabel) which **desyncs the native undo stack** → Ctrl+Z did nothing / behaved erratically.

Fix: RichInput now owns an explicit undo/redo history (same approach as Lexical/ProseMirror/Slate). Snapshots = `{ html, mentions }`. `recordHistory(coalesce)` — typing coalesces within `UNDO_COALESCE_MS=400ms` into one step; chip/paste/setText are discrete steps. `Ctrl+Z` / `Ctrl+Shift+Z` / `Ctrl+Y` wired in `handleKeyDown` (after the dropdown first-refusal check), always `preventDefault` so the key never escapes to JCEF/IDE. Caret restored to END on undo (deliberate simplification — avoids stale-offset math after innerHTML swap). `clear()` (post-submit) resets history so a sent message can't be resurrected. Baseline snapshot seeded in a mount `useEffect`.

⚠ **Do NOT "simplify" this back to native undo** — it cannot work while chips mutate the DOM directly. Pinned by `rich-input-undo.test.tsx` (6 cases).

⚠ TDZ gotcha: `insertChip`'s `useCallback` dep array must NOT list `fireChange` (declared later in the file → eager dep-array eval hits TDZ at render). `fireChange` is referenced as a stable closure; only `recordHistory` (declared earlier) is in its deps. Same reason the original used `[]`.

Same session as [[project_edit_file_document_write_disk_undo_fix]] (both surfaced from the user's "Ctrl+Z doesn't work" report — but different root causes: file-edit undo was a missing `WriteCommandAction`; chat-input undo is this native-contentEditable limitation).
