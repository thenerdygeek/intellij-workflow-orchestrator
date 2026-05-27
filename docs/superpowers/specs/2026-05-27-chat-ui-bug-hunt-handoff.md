# Chat UI Bug-Hunt — Context Handoff (2026-05-27)

**Branch:** `feature/cross-ide-delegation` (worktree `.worktrees/cross-ide`). All work below is **committed but UNPUSHED**. Pre-existing unrelated uncommitted changes exist in the tree (agent/CLAUDE.md, SpawnAgentTool.kt, agent.md, a Kotlin test) — **do not touch/stage them**.

**Running record:** memory `project_chat_file_document_attachments.md` has the full per-commit detail.

---

## What this work is

Started as the **file/document attachment** feature for the agent chat (JCEF picker + drag-drop fix + non-image routing to read_file/read_document). It then expanded into a multi-round adversarial **chat-UI bug hunt** (devil's-advocate agents → reproduce → root-cause fix → regression test).

## State of tests

- **vitest: 584 passing** (73+ files). **e2e (Playwright): 21 passing** across 6 specs.
- Run: `cd agent/webview && npx vitest run` and `npm run test:e2e` (needs `npx playwright install chromium` once).
- `npx tsc -b` is clean. The **strict tsc build catches things vitest's esbuild misses** — always run it before committing.

## Test infra built this session (reusable)

- `agent/webview/playwright.config.ts` — auto-starts the vite dev server, headless chromium, CI-ready.
- `e2e/` specs: `chat-attachments`, `chat-input`, `chat-copy`, `chat-ticket-paste`, `chat-mentions`, `chat-edge-cases`.
- `src/harness/HarnessApp.tsx` now mounts the **full `<InputBar/>`** (§0) + §6 CommandPreview + §7 copy fixtures.
- `src/harness/mock-bridge.ts` stubs: `_pickAttachment`, `_setDropActive`, `_addAttachmentChip` (via InputBar), `_searchTickets`, `_validateTicket`, `_searchMentions` (push via `chatStore.receiveMentionResults`), and `__harness.setNextStepHint`.
- **Other chat surfaces (message list, plan card, question wizard, approval gate, history view) are NOT mounted in the harness** — fixing their bugs e2e needs mounting them + seeding store state. Most are also unit-testable directly against `chatStore` actions (no mount).

## Bugs FIXED this session (each has a regression test)

Input/attachment/mention: streaming thinking/tool order; ticket double-`#`; typed-ticket-not-recorded; duplicate mention; stuck dropdown; same-named-files merged (key by path); attachment-only unsendable (`canSend`); hint-accept Send disabled (`setText` fires change); multi-line chip prefix/× leak (`extractText` recurses); slash-after-chip (strip ZWSP); Escape ghost-ticket; copy unhandled-rejection; mention out-of-order race (`pendingMentionQuery`); phantom undo snapshot (`removeChipByLabel` guard); **toasts were dead UI → added `<ToastStack>`**; stale approval/question survive session reset/resume; thinking dropped on completeSession/endStream; sub-agent tool finalized wrong row (id match); PlanSummaryCard Rules-of-Hooks; approval double-click latch.

Key commits: `7d8757bab..b2932ec97` (attach feature) + `8f12901d1`, `98919d9b8`, `4683cc30d`, `746796a72`, `28bb30835`, `754a74b1a`, `2a4603ca1`, `35e4c5c6d`, `e7886e9c5`.

---

## REMAINING OPEN BUGS — all CONFIRMED real, MUST be fixed (no deferral)

User directive: **reproduce each with a test first; if genuine, fix at the ROOT (architectural, not a cheap patch); validate. For all bug types.**

### Question wizard (`src/components/agent/QuestionView.tsx`, `src/components/ui/tool-ui/question-flow.tsx`, `chatStore.ts`)
- **#17 "Chat about this" always targets `options[0]`** — `QuestionView.tsx:426` does `setChatAbout({ qid, label: question.options[0]?.label ?? '' })`. The "Chat about this" button is per-QUESTION (one button, line 128), but `ChatAboutInput` shows "Ask about: <optionLabel>" and sends `_chatAboutOption(qid, optionLabel, msg)` with options[0]. **Root question/design decision needed:** is chat-about about the whole QUESTION (then don't label it with an arbitrary option — send the question text/id) or per-OPTION (then the button must be per-option)? Pick one and make the data flow consistent. CONFIRMED at line 426.
- **#18 multi-select / "Other" answer label mapping inconsistency** — `finalizeQuestionsAsMessage` (`chatStore.ts:1380-`, answer mapping ~1397) joins multi-select labels and falls back to raw id for free-text, but `ChatView`'s QUESTION_WIZARD completed render builds options/answers differently → mismatch on multi-select + "Other". Root: two serialization paths for the same answered-question receipt. Unify into one helper.
- **#13 multi-select with zero selections is a dead-end** — `question-flow.tsx` `handleNext` early-returns when `selectedIds.size === 0` and Next is `disabled={!canProceed}`; a legitimately-empty multi-select answer is unreachable (only "Skip", different semantics). Root: conflating "no selection" with "can't proceed".
- **#5 question summary 300ms timer has no cleanup** — `QuestionView.tsx` `handleSelect`/`handleCustomSubmit`/`handleTextAnswer` schedule `setTimeout(() => setShowSummary(true), 300)` with no clearTimeout on Back/Skip/clearQuestions → summary can flash against stale/cleared questions. Root: uncancelled timer; use a ref + cleanup or a derived state.

### History view (`src/components/history/HistoryView.tsx`)
- **#14 bulk-delete has no confirmation + selectedIds diverges from the filtered list** — `handleBulkDelete` fires `bulkDeleteSessions` immediately (irreversible, N sessions); `handleSelectAll` overwrites `selectedIds` with the filtered set so hidden selections silently change what's deleted vs shown. Root: destructive action with no confirm + selection set not reconciled against the visible/filtered list.

### Plan card (`src/components/agent/PlanSummaryCard.tsx`, `chatStore.ts`)
- **#11 button stuck "Approving…" on a same-title plan revision** — the button-reset effect keys on `planIdentity = `${plan.title}:${plan.approved}`` (PlanSummaryCard) while the typewriter keys on `title::text`. A revised plan with the same title + still-unapproved doesn't change `planIdentity` → effect doesn't fire → button frozen. **Root (architectural): a plan has no stable identity.** Give the plan a stable id (or derive identity from `title:summary:approved:revisionCount`) and use ONE identity formula everywhere. `setPlan`/`approvePlan` in chatStore should set/bump it.

### Attachments (`src/components/input/InputBar.tsx`, `AttachmentManager.ts`)
- **#7 compress-confirm modal single-slot orphans the first promise** — `compressPrompt` state is one slot; a second oversize paste overwrites it, leaving the first `confirmCompress` promise unresolved forever (and per-turn cap counting off). Root: `confirmCompress` needs to serialize (queue) prompts or reject/await while one is open.

### Chip identity (RECURRING ROOT — the big architectural one)
- **#10 cross-TYPE same-label chip operations** — `updateChipStatus`/`removeChipByLabel` query `[data-mention-label="${label}"]` and the MutationObserver prunes `mentionsRef` by `existingLabels.has(m.label)` — all **label-keyed**. A file chip and a ticket chip sharing a label corrupt each other; `removeChipByLabel` nukes the wrong-type chip. **Root architectural fix: give every chip a unique id (`data-chip-id`) at insert time and key ALL chip operations (insert, status update, remove, MutationObserver prune, getMentions dedup) by that id, not the label.** This is the same root that forced the path-keyed `getMentions` patch (#28bb30835) and the same-name-files fix — doing the id refactor would let those be simplified and closes #10 properly. Biggest single architectural item; touches `RichInput.tsx` insertChip/updateChipStatus/removeChipByLabel/getMentions + the MutationObserver.

### Resume / streaming (`chatStore.ts hydrateFromUiMessages`)
- **#15 open code-fence content dropped on resume** — `hydrateFromUiMessages` filters out `!m.partial` (~line 2233), so an interrupted streaming message (persisted `partial:true`, e.g. an open ```fence) is dropped entirely on reload instead of rendered best-effort. Root: partial-but-has-content messages should be finalized (rendered) on hydrate, not filtered. Coordinate with the Kotlin persist side (interrupted turns may already write a finalized marker).

Full original analysis: devil's-advocate agent reports `a142d4425164335c1` (non-input surfaces) and the earlier input-surface one. Re-run a devil's advocate scoped to any surface for fresh combinations.

---

## Gotchas / conventions
- **No Co-Authored-By trailer** in commits. Stage only the files for your change.
- **ZWSP (U+200B) in source is fragile** — the editor round-trips a literal ZWSP; to put/remove one use `perl -CSD -i -pe 's/\x{200b}//g if /pattern/'`, or strip ZWSP at runtime (`.replace(/​/g,'')`) instead of embedding it in a regex literal.
- **zsh eats `--include=*.tsx`** in Bash tool greps — quote it (`--include="*.tsx"`) or use `grep -rn ... src`.
- **`handleSend` is async** — in e2e, assert sends with `expect.poll`, not a one-shot `getCalls()` read (races under parallel load).
- **`getMentions` is DOM-derived** and self-heals, which MASKS some races (the undo-vs-MutationObserver one was proven safe, not a bug). Always reproduce before fixing.
- **macOS DropTarget risk** (JVM-side, untested): JCEF OSR may not deliver OS file drops to `b.component`; if smoke fails, install the DropTarget on the parent panel. Needs `runIde`.

## Resume recipe
1. Pick a bug above. Write a failing test (vitest store/component, or e2e — mount the surface in HarnessApp + seed store if needed).
2. Confirm it reproduces (red).
3. Fix at the root (architectural). For chip bugs, strongly consider the `data-chip-id` refactor (#10) as it subsumes several.
4. `npx vitest run` (full) + `npx playwright test` (full) + `npx tsc -b` — all green, no regressions.
5. Commit (no co-author trailer; only your files). Update the memory file.

## Pending non-code items
- Manual smoke of the attachment feature needs `runIde` (JCEF FileChooser + DropTarget + read_document routing — not reachable by tests). macOS drag-drop is the main risk.
- Branch is unpushed; no PR opened.
