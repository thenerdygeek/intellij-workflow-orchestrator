---
name: project_edit_file_document_write_disk_undo_fix
description: "edit_file Document write path didn't save-to-disk or register undo; fixed. format_code/optimize_imports/refactor_rename still have the disk-flush gap (deferred)."
metadata: 
  node_type: memory
  type: project
  originSessionId: 3b4ada6d-a983-4d0e-8d14-6bdd7dedc81f
---

**FIXED 2026-05-27 (bugfix worktree cross-ide):** `EditFileTool.writeViaDocument` had two co-located bugs that surfaced as "git diff (via run_command) shows no changes until I press Ctrl+S" and "Ctrl+Z on the edited editor does nothing".

Root cause: it mutated the in-memory `Document` via `document.replaceString` inside a bare coroutine `writeAction { }`.
1. **No disk flush** — `replaceString` only touches the in-memory document; an external `git diff` reads disk, which stays stale until a save trigger (Ctrl+S / frame deactivation / build-through-IDE). Fixed by adding `FileDocumentManager.getInstance().saveDocument(document)` after the edit.
2. **No undo** — bare `writeAction { }` takes only the write LOCK; the IDE `UndoManager` records changes ONLY inside a `CommandProcessor` command. Fixed by wrapping the mutation in `WriteCommandAction.runWriteCommandAction(project, "Agent: Edit File", null, Runnable { ... })` on `withContext(Dispatchers.EDT)` — same pattern every other write tool uses.

The old KDoc falsely claimed `writeAction` == `WriteCommandAction.runWriteCommandAction`. It is NOT — that claim was the trap.

**Why headless tests missed it:** with no live `Application`, `findVirtualFile` returns null and `execute` falls through to `writeViaFileIo` (java.io, which correctly hits disk). The Document path is unreachable in MockK+@TempDir tests. Pinned instead by `EditFileDocumentWriteContractTest` (source-text structural test, scoped to the `writeViaDocument` body so KDoc mentions of WriteCommandAction can't false-green). Same testing posture as [[project_dialog_modality_showandget_trap]].

**Sibling tools fixed too (same session, user follow-up):** `format_code` + `optimize_imports` (single-file → `FileDocumentManager.getInstance().getDocument(vf)?.let { saveDocument(it) }` after the WriteCommandAction, inside the `withContext(Dispatchers.EDT)` block) and `refactor_rename` (multi-file rename via `RenameProcessor` → `FileDocumentManager.getInstance().saveAllDocuments()` after the refactoring). Those three were already undoable (they wrap in `WriteCommandAction`) — they only lacked the disk flush. Pinned by `DocumentWriteFlushContractTest` (agent/tools/ide). All four Document-writing tools now flush to disk.
