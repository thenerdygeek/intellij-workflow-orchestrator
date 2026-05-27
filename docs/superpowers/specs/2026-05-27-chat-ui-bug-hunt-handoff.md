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

### Question wizard — ✅ ALL FIXED (commit `3739cf62b`)
- **#17 "Chat about this" always targeted `options[0]` — FIXED.** Design decision taken: chat-about is **per-QUESTION** (the button is a single per-question button). `setChatAbout` now carries `{ qid, questionText }`; `ChatAboutInput` shows "Ask about this question" + the question text and sends `_chatAboutOption(qid, questionText, msg)`. Placeholder/labels updated. Pinned by `question-view-cluster.test.tsx`.
- **#18 answer label serialization duplicated — FIXED (unified).** Verify-don't-trust: the two mappers (`finalizeQuestionsAsMessage` + QuestionView summary) were byte-identical, so NOT a live visible mismatch — but duplicated and free to drift. Extracted `answerToLabels`/`answerToDisplay` in `src/util/question-answer.ts` as the single source of truth; both call sites use it. (`AnsweredQuestionsCard` stays a dumb display of finalize's pre-joined output.) Pinned by `question-answer-format.test.ts` (6 cases incl. multi-select + free-text "Other").
- **#13 zero-selection multi-select dead-end — FIXED.** `question-flow.tsx`: `canProceed = selectionMode === 'multi' || size > 0`; both `handleNext` (Progressive + Upfront) allow an empty answer for multi-select. Single-select still requires a choice. Pinned by `question-flow-zero-select.test.tsx`.
- **#5 summary 300ms timer no cleanup — FIXED.** `QuestionView` now owns a single `summaryTimerRef` via `scheduleSummary()`/`cancelSummaryTimer()`; cancelled from Back/Skip/Cancel and an unmount `useEffect`. Pinned by `question-view-cluster.test.tsx` (Back-within-window no longer flashes the summary).

### History view (`src/components/history/HistoryView.tsx`)
- **#14 bulk-delete has no confirmation + selectedIds diverges from the filtered list** — `handleBulkDelete` fires `bulkDeleteSessions` immediately (irreversible, N sessions); `handleSelectAll` overwrites `selectedIds` with the filtered set so hidden selections silently change what's deleted vs shown. Root: destructive action with no confirm + selection set not reconciled against the visible/filtered list.

### Plan card — ✅ FIXED (commit `e6ae97160`)
- **#11 button stuck "Approving…" on a same-title plan revision — FIXED.** Root taken: plans now have a stable identity. `chatStore.setPlan` assigns a monotonic `revision` (from session-scoped `planRevisionSeq`), bumped on content change, preserved on identical re-push, unique across `clearPlan` within a session. `PlanSummaryCard` derives ONE identity formula `planIdentity(plan)` (`rev:N`, with a content-hash fallback for store-less/legacy renders) used by BOTH the button-reset effect and the summary typewriter seen-set — eliminating the two divergent formulas (`title:approved` vs `title::text`) that caused the drift. Pinned by `plan-card-identity.test.tsx` (component reset on revision + 3 store cases).

### Attachments (`src/components/input/InputBar.tsx`, `AttachmentManager.ts`)
- **#7 compress-confirm modal single-slot orphans the first promise** — `compressPrompt` state is one slot; a second oversize paste overwrites it, leaving the first `confirmCompress` promise unresolved forever (and per-turn cap counting off). Root: `confirmCompress` needs to serialize (queue) prompts or reject/await while one is open.

### Chip identity (RECURRING ROOT — the big architectural one)
- **#10 cross-TYPE same-label chip operations — ✅ FIXED (commit `cb0a76006`).** Root fix landed: every chip now gets a unique `data-chip-id` at insert time. `insertChip` returns the id; `updateChipStatus(chipId, …)` and the renamed `removeChipById(chipId)` target that id; `mentionsRef` tracks `{id, mention}`; the MutationObserver prunes by id; `getMentions` resolves each chip→mention by id while still deduping the OUTPUT payload by entity (`path||label`, so same-name-files survive and the same ticket inserted twice collapses). `onPastedTickets` now carries `{key, chipId}` so paste validation targets the right chip. `validateTicket(ticketKey, chipId)` in `InputBar`. Pinned by `rich-input-chip-identity.test.tsx` (5 cases). vitest 589 / e2e 21 / tsc clean.
  - Note: the path-keyed `getMentions` dedup (`28bb30835`) and same-name-files behaviour are **preserved** as the payload-dedup rule — chip-id is the DOM↔mention identity; entity key is still how the send list dedups. They were not removed, just no longer load-bearing for targeting.

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
