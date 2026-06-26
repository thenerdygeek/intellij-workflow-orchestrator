# Agent — History, Sessions, Resume & Sub-Agents

This section covers the Agent chat's **history list**, **session view / resume / continue**, **sub-agents**, and **cross-IDE delegation** UI. Every behavior below is traced to source so a tester running `./gradlew runIde` on licensed Windows IntelliJ Ultimate can reproduce and judge it from this document alone.

**Where this lives in the UI.** The Agent chat is a single JCEF (Chromium) webview hosted in the bottom-docked **"Workflow"** tool window. The webview has exactly two top-level modes driven by `chatStore.viewMode` (`'history'` | `'chat'`) — `App.tsx:111-131`. In `'history'` mode the whole panel is `<HistoryView>`; in `'chat'` mode it is `<TopBar> / <SkillBanner> / <DelegationBanner> / <ChatView> / <EditStatsBar> / <InputBar>`. There is no separate "History" tab button inside the webview — you reach history via the **New Chat / history affordance in the chat header** (Kotlin pushes `loadSessionHistory(json)` which flips `viewMode → 'history'`, `jcef-bridge.ts:674-689`).

**Marquee user priority:** *"existing chat can be resumed to continue chatting, or not."* The Resume/Continue scenarios (HIS-9 … HIS-16) are intentionally the most detailed.

**Bridge map (for the tester's mental model).** JS→Kotlin calls used here: `_showSession` (open read-only), `_deleteSession`, `_bulkDeleteSessions`, `_toggleFavorite`, `_startNewSession`, `_exportSession`, `_exportAllSessions`, `resumeViewedSession`, `startIncomingDelegation` (`jcef-bridge.ts:795-814`, `HistoryView.tsx:61-87`). Kotlin→JS pushes: `loadSessionHistory`, `_loadSessionState`/`pushStateToWebview`, `showResumeBar`/`hideResumeBar`, `_setActiveSessionDelegated`.

**Global setup for every scenario below**
- Settings → Tools → Workflow Orchestrator → AI Agent: agent **enabled**, a working Sourcegraph token configured (so a real session can run).
- Have at least 5–10 historical sessions on disk before testing the list (run several short tasks, interrupt some, complete some, favorite a couple). Storage root: `~/.workflow-orchestrator/{proj-slug}-{sha6}/agent/sessions.json` + `sessions/{id}/`.
- Test the full **light + dark** theme matrix on every scenario (Settings → Appearance → Theme; the webview re-syncs CSS vars live).

---

## A. History list (viewMode `'history'`)

### [HIS-1] History list renders session cards with correct metadata
- **Component(s):** `HistoryView.tsx`, `SessionCard.tsx` (`formatTimeAgo`/`formatTokens`/`formatCost`/`formatModelId`)
- **Preconditions:** ≥5 sessions on disk with varied ages, models, token totals, and costs.
- **Steps:** 1. Open the Agent tool window. 2. Navigate to History (header → New Chat menu / history affordance). 3. Read each card.
- **Expected — visual:** Header reads **"History"** with **Export All**, **Select**, and a primary **+ New Chat** button (`HistoryView.tsx:181-213`). A search box ("Search sessions…") sits below. Each card shows: task title (max 2 lines, `line-clamp-2`), a clock icon + relative age ("just now" / "Xm ago" / "Xh ago" / "Xd ago" / "Xw ago", `SessionCard.tsx:6-17`), an uppercase model chip with the `claude-` prefix stripped (`formatModelId`), a cost like `$0.04` (hidden when ≤ 0), a token label (`12.3K tok` when ≥1000 else `850 tok`, summing `tokensIn + tokensOut`), and a hollow favorite star.
- **Expected — behavioral:** Cards are virtualized (react-virtuoso) — scrolling a long list keeps rows mounting/unmounting smoothly. Relative ages reflect `Date.now() - item.ts` at render time.
- **✅ Checks (tick each):**
  - [ ] Title, time, model chip, cost, token count all present and plausible.
  - [ ] Cost is omitted on a $0 session; token label uses `K tok` past 1000.
  - [ ] Long list scrolls without jank; no blank rows on fast scroll.
  - [ ] Star renders hollow (unfavorited) vs filled-amber (favorited).
- **🐞 Bug signals:** token label shows only `tokensIn` (missing out); model chip still shows `claude-`; cost shows `$0.00` instead of hiding; cards overlap or clip; flicker/remount on scroll.
- **Theme/size matrix:** light + dark; narrow vs wide tool window.
- **⛔ Write note:** read-only.

### [HIS-2] No status badge on cards — confirm the gap, not a regression
- **Component(s):** `SessionCard.tsx`, `bridge/types.ts` (`HistoryItem`)
- **Preconditions:** A mix of completed, interrupted, and errored sessions on disk.
- **Steps:** 1. In History, inspect cards for a running/completed/interrupted/error indicator.
- **Expected — visual:** **No** running/completed/interrupted/error status pill appears on any card. `HistoryItem` (`types.ts:561-573`) carries no `status` field; the card only renders title/age/model/cost/tokens/star/delegation-badge. The completed-vs-interrupted distinction surfaces **only when the session is opened** (resume bar, HIS-9).
- **Expected — behavioral:** Cards look identical regardless of how the underlying run ended.
- **✅ Checks (tick each):**
  - [ ] Confirm there is genuinely no status indicator (this is expected, by design).
  - [ ] Note whether the absence is confusing from a UX standpoint (feedback only).
- **🐞 Bug signals:** any half-rendered/empty status element implying a status field was added but not populated.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HIS-3] Search filters the list by task text (case-insensitive)
- **Component(s):** `HistoryView.tsx` (`filteredItems`, `historySearch`)
- **Preconditions:** Sessions with distinct task titles, including mixed-case words.
- **Steps:** 1. Type a substring of one task title (try different casing). 2. Clear with the ✕ button.
- **Expected — visual:** List narrows to titles containing the query (`item.task.toLowerCase().includes(query)`, `HistoryView.tsx:43-49`). The ✕ clear button appears only while text is present (`HistoryView.tsx:257-264`).
- **Expected — behavioral:** Matching is **task-title only** — searching for a model name, a date, or message-body text does **not** match. Clearing restores the full list.
- **✅ Checks (tick each):**
  - [ ] Mixed-case query still matches (case-insensitive).
  - [ ] Non-title text (e.g. model id) does not match.
  - [ ] ✕ clears and restores the list.
- **🐞 Bug signals:** case-sensitive matching; search box persists stale results; ✕ doesn't clear.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HIS-4] Empty-state and no-match-state
- **Component(s):** `HistoryView.tsx:271-294`
- **Preconditions:** (a) A throwaway project with zero sessions; (b) any project with sessions.
- **Steps:** 1. (a) Open History with zero sessions. 2. (b) With sessions present, type a query that matches nothing.
- **Expected — visual:** (a) Centered `MessageSquareDashed` icon, **"No sessions yet"**, sub-text "Start a conversation with the AI agent to see your session history here.", and a **Start New Chat** button. (b) Centered `Search` icon, **"No matching sessions"**, "Try a different search term" — and **no** search box/Export/Select in the header path is still present (the empty-list header hides Export All / Select since `!isEmpty` is false only in case a).
- **Expected — behavioral:** (a) "Start New Chat" → `_startNewSession` → blank chat. (b) Clearing the query restores cards.
- **✅ Checks (tick each):**
  - [ ] Zero-session empty state matches exactly (icon + both lines + button).
  - [ ] No-match state matches exactly.
  - [ ] "Start New Chat" opens a fresh chat.
- **🐞 Bug signals:** wrong copy; empty state shown when sessions exist; search box visible in the zero-session state.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only (the Start New Chat just opens a blank chat).

### [HIS-5] Sort order of the list
- **Component(s):** `HistoryView.tsx` (`historyItems` order), `MessageStateHandler.loadGlobalIndex`
- **Preconditions:** Sessions created at clearly different times.
- **Steps:** 1. Observe the top-to-bottom order of cards. 2. Resume an old session, send a message, return to History.
- **Expected — visual:** There is **no sort control** in the UI. Order is whatever `sessions.json` / `loadGlobalIndex` returns (no client-side sort in `HistoryView`).
- **Expected — behavioral:** Verify empirically whether most-recent is first and whether a freshly-touched session jumps to the top after activity.
- **✅ Checks (tick each):**
  - [ ] Record the observed order (newest-first? insertion order?).
  - [ ] Record whether a touched session re-orders.
- **🐞 Bug signals:** random/unstable ordering between refreshes; a just-used session buried at the bottom.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HIS-6] Favorite star toggle from a card
- **Component(s):** `SessionCard.tsx:115-132`, `HistoryView.handleToggleFavorite`, `AgentController.handleToggleFavorite`
- **Preconditions:** A non-favorited session visible.
- **Steps:** 1. Click the star on a card (not in selection mode). 2. Click again to unfavorite.
- **Expected — visual:** Star fills amber (`fill-[var(--warning)]`) when favorited, hollow when not; tooltip toggles **"Favorite"/"Unfavorite"** (`title` attr).
- **Expected — behavioral:** Click stops propagation (does NOT open the card action bar). `toggleFavorite` writes to disk and the index reloads — the new state persists across a History refresh and an IDE restart.
- **✅ Checks (tick each):**
  - [ ] Star fill toggles; tooltip text matches state.
  - [ ] Toggling does not expand the card's Resume/Delete bar.
  - [ ] State survives leaving and re-entering History.
- **🐞 Bug signals:** star click also opens action bar; state reverts after refresh; tooltip stuck.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** writes `isFavorited` to `sessions.json` — allowed (local metadata only).

### [HIS-7] No "filter to favorites" control — confirm the gap
- **Component(s):** `HistoryView.tsx`
- **Preconditions:** Some sessions favorited.
- **Steps:** 1. Look for any "favorites only" toggle/filter in the History header or search area.
- **Expected — visual:** There is **no** favorites filter anywhere — the only filter is the free-text search. Favoriting affects only the star icon.
- **✅ Checks (tick each):**
  - [ ] Confirm no favorites-filter UI exists (expected gap, not a bug).
  - [ ] Note as a UX gap if the tester finds it surprising.
- **🐞 Bug signals:** a half-wired "favorites" toggle that does nothing.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HIS-8] Right-click context menu on a card
- **Component(s):** `SessionContextMenu.tsx`, `HistoryView.handleContextMenu`
- **Preconditions:** Any card visible.
- **Steps:** 1. Right-click a card. 2. Hover each item. 3. Click outside, then Escape, to dismiss.
- **Expected — visual:** A fixed-position menu at the cursor with exactly four items: **Resume** (message icon), **Favorite/Unfavorite** (star, label reflects state), **Export** (download icon), **Delete** (trash, red). **No Rename action exists.**
- **Expected — behavioral:** Resume → opens the session (same as card Resume). Favorite → toggles + closes. Export → copies that session's markdown to clipboard + toast. Delete → deletes immediately (this menu path has **no** inline confirm — contrast HIS-19/20). Menu closes on outside-click or Escape (`SessionContextMenu.tsx:21-36`).
- **✅ Checks (tick each):**
  - [ ] Exactly four items, correct icons/labels; Delete is red; no Rename.
  - [ ] Favorite label matches current state.
  - [ ] Outside-click and Escape both close the menu.
  - [ ] Menu repositions sanely near screen edges (note if it clips off-screen — see Bug signals).
- **🐞 Bug signals:** **Delete in the context menu fires with no confirmation** (verify this is intended — the card and bulk paths both confirm; this one does not); menu clipped off the bottom/right edge (it positions at raw `clientX/clientY` with no viewport clamping); Escape doesn't close.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** context-menu **Delete is an unconfirmed local delete** — verify whether a confirm is expected; Export only touches the clipboard.

---

## B. Opening / viewing a session (→ viewMode `'chat'`)

### [HIS-9] Open a session → full transcript loads read-only; resume bar gating
- **Component(s):** `SessionCard` Resume, `HistoryView.handleResume`, `AgentController.showSession` (`AgentController.kt:4341-4415`), `ResumeHelper.determineResumeAskType`, `ChatFooter` resume bar
- **Preconditions:** One **interrupted** session and one **completed** session on disk.
- **Steps:** 1. In History, click a card to reveal its action bar, click **Resume** (or context-menu Resume). 2. Observe the chat. 3. Repeat for the completed session.
- **Expected — visual:** View switches to chat (`showChatView`) and the **entire prior transcript** renders (user turns, assistant prose, tool-call chains, sub-agent cards, completion card, etc.). Input is unlocked, spinner off. For the **interrupted** session a **Resume bar** appears at the bottom: *"This session was interrupted. You can continue where it left off."* + a **Resume** button (`ChatFooter.tsx:236-265`). For the **completed** session **no** resume bar appears (it's display-only; `isCompleted` via `determineResumeAskType` → `RESUME_COMPLETED_TASK`, `showSession` skips `showResumeBar`).
- **Expected — behavioral:** Loading happens off-EDT (no UI freeze even on a multi-MB transcript). A stale-click guard drops a late load if you quickly click another session (`generation`/`viewedSessionId` checks, `AgentController.kt:4398`).
- **✅ Checks (tick each):**
  - [ ] Full transcript renders in order (prose + tool cards + sub-agent cards).
  - [ ] Interrupted session shows the Resume bar; completed session does not.
  - [ ] No EDT freeze when opening a large session.
  - [ ] Rapidly clicking two sessions ends on the *second* one's transcript (no stale stomp).
- **🐞 Bug signals:** transcript empty/partial; resume bar shown on a completed session (or missing on an interrupted one); UI freeze on open; the first-clicked session's content "wins" after clicking a second.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only view; resume is a separate explicit action (HIS-10).

### [HIS-10] Switch from an opened session back to History and to another session
- **Component(s):** `AgentController.showHistory`/`showSession`, stale-load generation guard
- **Preconditions:** ≥3 sessions on disk.
- **Steps:** 1. Open session A. 2. Go back to History. 3. Open session B. 4. Go back, open A again.
- **Expected — visual:** History list re-appears unchanged; opening B shows B's transcript; re-opening A shows A's transcript fresh from disk.
- **Expected — behavioral:** `showHistory()` bumps the showSession generation so a late A-load can't yank you back into chat (`AgentController.kt:4112-4124`). The chat view is `dashboard.reset()` then re-populated on each open — no leftover messages from the previously-viewed session.
- **✅ Checks (tick each):**
  - [ ] Back-to-History always lands on the list (never flashes the old chat).
  - [ ] B's transcript shows no residue of A.
  - [ ] Re-opening A restores A correctly.
- **🐞 Bug signals:** messages from A bleed into B; History view momentarily replaced by a late chat push; scroll position garbage.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HIS-11] Opening a session while another is actively running (transition guard)
- **Component(s):** `AgentController.showSession` → `killBackgroundsOnTransition`, `service.cancelCurrentTask`
- **Preconditions:** Start a task that spawns a long `run_command` or background process; while it runs, go to History.
- **Steps:** 1. With a session actively running (spinner on, maybe a background process alive), navigate to History and click another session's Resume.
- **Expected — visual:** If the running session has live background processes, a **modal confirmation** appears ("Switching sessions" — kill background processes?). Confirm → switch proceeds; Cancel → stays on the running session.
- **Expected — behavioral:** On confirm, the current task is cancelled (`cancelCurrentTask`) and monitors/background processes for that session are disposed before the new transcript loads.
- **✅ Checks (tick each):**
  - [ ] Confirmation appears only when there are live background processes to kill.
  - [ ] Cancel keeps you on the running session, untouched.
  - [ ] Confirm cleanly stops the old run and opens the new session.
- **🐞 Bug signals:** old run keeps streaming into the newly-opened transcript; no confirmation despite live processes; orphaned background process after switch.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** **Observe-only on the dialog content**; confirming kills local background processes (expected) — do this only on a throwaway run.

---

## C. Resume & continue (MARQUEE — be exhaustive)

### [HIS-12] Resume an interrupted run → loop continues from where it stopped
- **Component(s):** Resume bar → `kotlinBridge.resumeViewedSession()` → `AgentController.resumeViewedSession`/`resumeSession` (`AgentController.kt:4446-4519`), `AgentService.resumeSession`, `[TASK RESUMPTION]` preamble
- **Preconditions:** Start a multi-step task ("refactor X across these files, run the tests, summarize"). Let it run a few iterations (several tool calls). Click the per-tool/global **Stop** so the run ends mid-task → the session is now interrupted.
- **Steps:** 1. The interrupted session is on screen (or open it from History). 2. Confirm the Resume bar shows "This session was interrupted…". 3. Click **Resume** (no typed message). 4. Watch the loop.
- **Expected — visual:** Resume bar disappears (`hideResumeBar`); a "Resuming session…" working state appears (`prepareForReplay`); the agent resumes streaming and issuing tool calls, continuing the original objective. Prior transcript stays intact above the new activity.
- **Expected — behavioral:** `resumeSession` sets `currentSessionId`/`sessionActive`, rebuilds the `ContextManager` from persisted `api_conversation_history.json`, drops a trailing interrupted user/tool turn (`ResumeHelper.popTrailingUserMessage`), and re-enters the loop with a `[TASK RESUMPTION]` preamble carrying a time-ago. Approvals, plan mode, token/cost display, checkpoints, and steering are all wired (full callback set).
- **✅ Checks (tick each):**
  - [ ] Resume bar vanishes; spinner + "Resuming…" appears.
  - [ ] Agent continues the SAME task (context intact — references earlier files/decisions).
  - [ ] Token/cost counters resume from the prior totals, not from zero.
  - [ ] Approval prompts still gate write tools after resume.
- **🐞 Bug signals:** agent restarts from scratch / loses context; duplicate of the last tool call replays a stale result; counters reset to 0; resume silently no-ops (spinner forever, no streaming).
- **Theme/size matrix:** light + dark
- **⛔ Write note:** this **executes the live agent** (real tool calls). Run against throwaway scratch files only; approve writes deliberately.

### [HIS-13] After resuming, type a NEW message → keeps chatting in the SAME session
- **Component(s):** `AgentController.executeTask` viewed-session short-circuit (`AgentController.kt:2481-2486`), `resumeViewedSession(task)`
- **Preconditions:** Continue from HIS-12 (or open any interrupted session).
- **Steps:** 1. With the session resumed/running or just-resumed, type a follow-up like "now also update the README" and Send. 2. After it answers, send another message.
- **Expected — visual:** Your message appears as a user bubble appended to the existing transcript (history preserved above). The agent answers in the same thread; no new session card is created.
- **Expected — behavioral:** Because `viewedSessionId`/`currentSessionId` point at this session, the typed text routes through `resumeViewedSession(task)` (when still in viewed state) or normal steering/turn handling — **not** the new-session path that would wipe the chat. Multi-turn continues indefinitely in one session.
- **✅ Checks (tick each):**
  - [ ] New user message appends to the SAME transcript (old turns still visible).
  - [ ] Agent has full prior context (answers reference earlier work).
  - [ ] No duplicate/extra session card appears in History for the follow-ups.
  - [ ] A second and third follow-up keep working.
- **🐞 Bug signals:** typing creates a brand-new blank session / wipes history; agent answers with no memory of prior turns; follow-up spawns a duplicate history entry.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** executes the live agent — throwaway files only.

### [HIS-14] Type into a VIEWED interrupted session WITHOUT clicking Resume first
- **Component(s):** `executeTask` viewed-session branch → `resumeViewedSession(task)`
- **Preconditions:** Open an interrupted session from History so the Resume bar is showing; do NOT click Resume.
- **Steps:** 1. Just type a message in the input and Send.
- **Expected — visual:** The session resumes automatically carrying your message as the continuation — equivalent to clicking Resume then typing.
- **Expected — behavioral:** `viewedSessionId != null` → `resumeViewedSession(task)` resumes with `userText=task` (`AgentController.kt:2482-2486`). The Resume bar is no longer needed.
- **✅ Checks (tick each):**
  - [ ] Sending a message resumes the session (no need to click Resume).
  - [ ] Transcript preserved; message threaded in.
- **🐞 Bug signals:** the typed message is lost; a new session starts instead; double-resume.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** executes the live agent — throwaway files only.

### [HIS-15] Resume/continue a cleanly-COMPLETED session
- **Component(s):** `showSession` (completed → no resume bar), `executeTask` viewed-session branch, `ResumeHelper.buildTaskResumptionPreamble` (`[TASK CONTINUATION]`, `ResumeHelper.kt:113-115`)
- **Preconditions:** A session that ended with a completion card (`attempt_completion`).
- **Steps:** 1. Open it from History — confirm it is display-only (no Resume bar). 2. Type a new message ("actually, also handle the edge case where input is null") and Send.
- **Expected — visual:** No resume bar on open. After sending, the completed transcript is preserved and the agent picks up with your new message as a fresh turn on top of the old context.
- **Expected — behavioral:** Internally the preamble is `[TASK CONTINUATION] … the previous task was completed … treat it as the start of a new turn, not a resumption of unfinished work.` The session is reused (same id), not forked.
- **✅ Checks (tick each):**
  - [ ] Completed session opens with NO resume bar.
  - [ ] Sending a message continues the SAME completed session (history kept).
  - [ ] Agent treats it as a new turn (doesn't re-run the already-finished work).
- **🐞 Bug signals:** resume bar wrongly shown on a completed session; agent re-executes the old completed task; a new session is created instead of continuing.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** executes the live agent — throwaway files only.

### [HIS-16] Close & reopen the IDE mid-task → startup interrupted-session notification → resume
- **Component(s):** `AgentStartupActivity` (`AgentStartupActivity.kt:19-83`), `WorkflowNotificationService`, History resume
- **Preconditions:** Start a task; while it is actively running (so the session ts is "recent", < 10 min) close the IDE (or the project) so the session lock is released without a clean completion.
- **Steps:** 1. Reopen the project. 2. Watch for a notification balloon. 3. Open the Agent tool window → History → open the interrupted session → Resume.
- **Expected — visual:** A **notification balloon** (IDE event log, group "Workflow Orchestrator") titled **"Interrupted Agent Session"** with body *"Agent session \"{first 60 chars of task}\" was interrupted. Open the Agent tab to resume it."* — NOT an in-chat banner. (Fires only when a session has no `.lock` and `ts` is within 10 minutes, and the agent is enabled.)
- **Expected — behavioral:** There is **no auto-resume and no startup in-chat "Resume" banner** — the notification just points you to the Agent tab. From there, the normal History → open → Resume bar flow (HIS-9/HIS-12) applies.
- **✅ Checks (tick each):**
  - [ ] Balloon appears on reopen with the correct title/body and truncated task name.
  - [ ] No balloon if the prior session completed cleanly or is older than 10 min.
  - [ ] Opening + resuming the interrupted session from History continues it.
- **🐞 Bug signals:** no balloon despite a fresh interruption; balloon for an already-completed session; clicking the Agent tab auto-resumes without user intent; resume after restart loses context.
- **Theme/size matrix:** light + dark (balloon is native IDE chrome — verify both themes render it)
- **⛔ Write note:** resuming executes the live agent — throwaway files only.

### [HIS-17] Resume of an OFFLINE / errored session and the retry pill
- **Component(s):** `resumeSession` callbacks → `onRetry` status, retry/continue pill in `ChatFooter.tsx:202-234`
- **Preconditions:** A session that ended in a NETWORK/timeout error, or any errored session. **Concrete repro options:** (a) **toggle the Windows Wi-Fi / network off for a few seconds during the run** (then turn it back on), or (b) **temporarily point the Sourcegraph URL in Settings → Tools → Workflow Orchestrator → Connections to an invalid host** (e.g. `https://invalid.invalid`), start the run so it errors, then **restore the correct URL** before retrying. Either path drives the session into the OFFLINE/errored state needed below.
- **Steps:** 1. Open the errored session. 2. Click Resume (or use the retry/continue pill if shown). 3. Observe retry status lines.
- **Expected — visual:** On retry the footer shows a **Continue/Retry** pill with a caption (e.g. an offline-specific caption). During resume, retry status lines appear ("… — retrying (1/5) in Ns…", `AgentController.kt:4499-4507`).
- **Expected — behavioral:** Clicking the pill re-runs the last task (`retryLastTask`). Sets busy immediately so the spinner appears during the Kotlin cleanup gap.
- **✅ Checks (tick each):**
  - [ ] Pill label is "Continue" or "Retry" per `retryState.kind`; caption is sensible.
  - [ ] Retry backoff status lines render and count up attempts.
  - [ ] Once connectivity returns, resume proceeds normally.
- **🐞 Bug signals:** pill missing after an error; clicking does nothing / no spinner; infinite retry with no cap; offline caption shown when actually online.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** executes the live agent on retry — throwaway files only.

---

## D. New session, delete, export

### [HIS-18] New session (`_startNewSession`) → blank chat, fresh stats, prior session saved
- **Component(s):** `HistoryView` New Chat → `kotlinBridge.startNewSession` → `AgentController.handleStartNewSession`/`resetForNewChat` (`AgentController.kt:4055-4100`)
- **Preconditions:** A session currently open/active.
- **Steps:** 1. Click **+ New Chat** (History header or chat header). 2. If the current session has live background processes, respond to the confirm. 3. Inspect the fresh chat. 4. Go to History.
- **Expected — visual:** Chat clears completely: no messages, no tool chains, token budget bar at 0, no session title, edit-stats bar reset to `+0 −0`, no skill banner, no delegated banner, input focused and unlocked. The previous session now appears as a card in History.
- **Expected — behavioral:** `resetForNewChat` nulls `currentSessionId`/`viewedSessionId`, bumps the showSession generation, resets all dashboard widgets, clears `activeSessionDelegated`. The previous session was persisted incrementally so it survives as a history entry.
- **✅ Checks (tick each):**
  - [ ] Chat is fully blank; all counters/banners reset.
  - [ ] Input is focused and immediately typeable.
  - [ ] Previous session is present and intact in History.
  - [ ] Leaving a running session prompts to kill background processes first.
- **🐞 Bug signals:** stale messages/tasks/diff bar carry into the new chat; delegated banner persists; previous session lost from History; token bar still shows old fill.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only w.r.t. disk except it finalizes the prior session; killing background processes only happens on explicit confirm.

### [HIS-19] Delete a session from the card action bar (inline two-step confirm)
- **Component(s):** `SessionCard.tsx:150-190` inline confirm, `HistoryView.handleDelete` → `_deleteSession` → `AgentController.handleDeleteSession`
- **Preconditions:** ≥2 sessions; one is the currently-viewed one.
- **Steps:** 1. Click a card to expand its action bar. 2. Click **Delete** → inline "Delete this session?" appears. 3. Click **Confirm**. 4. Repeat but click **Cancel** at the confirm step.
- **Expected — visual:** Delete swaps the action bar to **"Delete this session?"** with **Confirm** (red check) and **Cancel** (`SessionCard.tsx:153-169`). Confirm removes the card from the list; Cancel returns to Resume/Delete.
- **Expected — behavioral:** Confirm → `MessageStateHandler.deleteSession` removes the session dir + index entry, then the list reloads. If the deleted session was the one open in chat, `viewedSessionId` clears, the resume bar hides, and the chat view resets (`AgentController.kt:4135-4153`).
- **✅ Checks (tick each):**
  - [ ] The inline confirm appears BEFORE deletion (no instant delete).
  - [ ] Confirm removes the card; Cancel aborts.
  - [ ] Deleting the currently-open session also clears the chat view.
- **🐞 Bug signals:** delete fires without the confirm step; deleted card lingers in the list; chat still shows the deleted session afterwards (and "Resume" then silently no-ops).
- **Theme/size matrix:** light + dark
- **⛔ Write note:** **Local delete is allowed** — but verify the confirm dialog appears first. Delete throwaway sessions only.

### [HIS-20] Bulk selection + bulk delete with modal confirm
- **Component(s):** `HistoryView.tsx` selection mode (`:154-239`, `:343-381`), `visibleSelectedIds`, `_bulkDeleteSessions`
- **Preconditions:** ≥4 sessions on disk.
- **Steps:** 1. Click **Select**. 2. Tick a few cards (checkbox). 3. Use **Select All** / **Deselect All**. 4. Apply a search filter that hides some selected cards. 5. Click **Delete Selected** → confirm modal. 6. Confirm, then repeat and Cancel / click the backdrop.
- **Expected — visual:** Header switches to "**N selected**" + **Delete Selected** (red, disabled when 0) + **Cancel**; a Select-All/Deselect-All toggle bar shows. Each card shows a checkbox. Delete Selected opens a centered modal **"Delete N session(s)?"** / "This permanently removes … can't be undone." + Cancel + **Delete N** (`HistoryView.tsx:356-378`).
- **Expected — behavioral:** The count and delete payload use `visibleSelectedIds` — sessions hidden by the current search filter are **never** deleted even if they were ticked before filtering (bug #14, `HistoryView.tsx:55-58`). Backdrop-click or Cancel dismisses the modal without deleting. Confirm deletes only the visible-selected set and exits selection mode.
- **✅ Checks (tick each):**
  - [ ] "N selected" count tracks ticks and respects the active filter.
  - [ ] Delete Selected disabled at 0 selected.
  - [ ] Filtering out a selected card excludes it from the delete (count drops; it survives).
  - [ ] Modal Cancel and backdrop-click both abort.
  - [ ] Confirm deletes exactly the visible-selected set and leaves selection mode.
- **🐞 Bug signals:** a filtered-out-but-previously-ticked session gets deleted; count includes hidden cards; modal doesn't appear; Cancel still deletes.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** **Bulk delete is allowed** — verify the modal appears first; delete throwaway sessions only.

### [HIS-21] Export single session and Export All to clipboard
- **Component(s):** `HistoryView.handleExport`/`handleExportAll`, `AgentController.handleExportSession`/`handleExportAllSessions`/`formatSessionAsMarkdown` (`AgentController.kt:4227-4320`)
- **Preconditions:** ≥2 sessions with real conversation content.
- **Steps:** 1. Context-menu a card → **Export**. 2. Paste into a scratch editor. 3. In History header click **Export All**. 4. Paste.
- **Expected — visual:** A success toast: "Session exported to clipboard" (single) or "{N} sessions exported to clipboard" (all).
- **Expected — behavioral:** Clipboard markdown starts with `# {task}` then `**User:** …` / `**Agent:** …` lines (first SAY.TEXT = the user task; later SAY.TEXT = agent; FOLLOWUP = user; COMPLETION_RESULT = "Agent (completion)"). Export All joins sessions with `\n\n---\n\n`. Tool calls/status are skipped.
- **✅ Checks (tick each):**
  - [ ] Toast appears with correct count.
  - [ ] Pasted markdown is well-formed (title + alternating User/Agent).
  - [ ] Export All concatenates all sessions with separators.
- **🐞 Bug signals:** empty clipboard; toast but no content; mis-attributed User/Agent lines; Export All only exports one.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** clipboard only — safe.

---

## E. Sub-agents (`SubAgentView`)

### [HIS-22] Single sub-agent card lifecycle (RUNNING → COMPLETED)
- **Component(s):** `SubAgentView.tsx`, `ChatView.renderItem` (`msg.subagentData`, `ChatView.tsx:152-156`), `subAgentStreams` side-channel
- **Preconditions:** A chat where the agent spawns one sub-agent (e.g. "use the code-reviewer agent to review this file").
- **Steps:** 1. Trigger a single `agent(...)` spawn. 2. Watch the sub-agent card from start to finish. 3. Expand/collapse it.
- **Expected — visual:** A bordered card (status-colored ring) with: Bot icon, **SUB-AGENT** badge, agent **name**, **type** chip, **model** chip (if known), live **timer**, status indicator (spinner while RUNNING), **iter N · M tkn** (iteration shown only while running), a **Kill** (□) button while running, and a chevron. An indeterminate progress bar animates while running. Body (default **open** while running) shows streaming thinking, streaming prose, and the active tool-call chain. On completion: ring turns green, a ✓ replaces the spinner, the Kill button disappears, the card **auto-collapses**, and an expandable **"Result"** summary block (with a Copy button) is available.
- **Expected — behavioral:** Live counters (iteration, tokens, elapsed) update each second from the side-channel while running; finalized card reads committed values and freezes the timer. Tokens display via `toLocaleString()`.
- **✅ Checks (tick each):**
  - [ ] Header shows name/type/model/timer/iter/tokens correctly.
  - [ ] Streaming thinking + prose render live inside the body.
  - [ ] Card auto-collapses on completion; ✓ + green ring; Result block present and Copy works.
  - [ ] Timer freezes at the final elapsed value.
- **🐞 Bug signals:** card never collapses; spinner persists after completion; iter/token counters frozen mid-run; streaming shimmer left on a completed card; Result missing.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** executes a live sub-agent — read-only sub-agent (reviewer/explorer) preferred; throwaway files for any coder agent.

### [HIS-23] Parallel sub-agents (up to 5 cards)
- **Component(s):** `SubAgentView` (one card per sub-agent), Kotlin parallel fan-out (read-only agents)
- **Preconditions:** A prompt that fans out (e.g. "explore these 3 areas in parallel"). Parallel fan-out is read-only-agents only.
- **Steps:** 1. Trigger a parallel `agent(prompt, prompt_2, prompt_3, …)` spawn. 2. Observe multiple cards.
- **Expected — visual:** Up to **5** independent sub-agent cards stack in the transcript, each with its own status ring, timer, iteration/token counters, and progress bar; each streams independently.
- **Expected — behavioral:** Cards update independently (P0-3: each subscribes to its own `subAgentStreams[agentId]` slice, so only the changed card re-renders). They may finish at different times and each collapses on its own completion.
- **✅ Checks (tick each):**
  - [ ] Each parallel branch gets its own card with distinct name/type.
  - [ ] Cards stream and complete independently (no cross-contamination of text).
  - [ ] At most 5 run concurrently.
- **🐞 Bug signals:** text from one sub-agent appears in another's card; all cards re-render on every delta (visible jank); more than 5 concurrent; a card stuck RUNNING after its branch finished.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** executes live sub-agents — read-only fan-out only.

### [HIS-24] Stop a single sub-agent (Kill)
- **Component(s):** `SubAgentView.handleKill` → `chatStore.killSubAgent` → `cancelAgent(agentId)`
- **Preconditions:** A running sub-agent (single or one of several parallel).
- **Steps:** 1. While a sub-agent is RUNNING, click its **□ Kill** button. 2. If parallel, kill only one.
- **Expected — visual:** The killed card transitions to **KILLED** (gray ring + ✗/XCircle, spinner gone, Kill button gone). Other sub-agents (and the orchestrator) keep running.
- **Expected — behavioral:** `handleKill` stops propagation (does not toggle collapse), only fires when `isRunning`. The orchestrator loop continues with a stopped-result for that branch.
- **✅ Checks (tick each):**
  - [ ] Killed card shows KILLED status; only that one is affected.
  - [ ] Kill does not collapse/expand via the click.
  - [ ] Orchestrator continues after a sub-agent kill.
- **🐞 Bug signals:** Kill stops ALL sub-agents or the orchestrator; killed card still shows a spinner; clicking Kill toggles the body instead.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** Kill is a local cancel — safe; affects only the in-flight sub-agent.

### [HIS-25] Sub-agent ERROR state and transient status note
- **Component(s):** `SubAgentView` ERROR ring/indicator, `liveStatusNote` strip (`SubAgentView.tsx:304-312`)
- **Preconditions:** A sub-agent that errors or hits a retry/compaction (network blip during a sub-agent run is the easiest reproduction).
- **Steps:** 1. Run a sub-agent under flaky connectivity. 2. Watch for the status-note strip and the final ERROR state.
- **Expected — visual:** While running, a small italic strip with a tiny spinner shows transient notes like "Compacting context…" or "timeout — retrying (2/3) in 3s…". On hard failure the ring turns red and an AlertCircle (ERROR) indicator shows.
- **Expected — behavioral:** Retries/compactions surface on the sub-agent card, **not** in the orchestrator's main chat.
- **✅ Checks (tick each):**
  - [ ] Transient status note appears on the card (not in main chat) during retry/compaction.
  - [ ] ERROR state renders red ring + alert icon.
- **🐞 Bug signals:** retry noise leaks into the orchestrator chat; ERROR card looks identical to COMPLETED; status note persists on a finished card.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only repro preferred.

### [HIS-26] Sub-agent cards survive a resume / reopen
- **Component(s):** `SubAgentView` default-collapsed on terminal status, persisted `subagentData`
- **Preconditions:** A session that ran sub-agents to completion; close it (or the IDE) and reopen from History.
- **Steps:** 1. Open a past session that used sub-agents. 2. Inspect the rendered sub-agent cards.
- **Expected — visual:** Completed/killed sub-agent cards render **collapsed by default** (so they don't each eat ~440px), with their final status ring, summary expandable on click.
- **Expected — behavioral:** Re-hydrated from persisted `subagentData`; no live timers tick (terminal), no streaming shimmer.
- **✅ Checks (tick each):**
  - [ ] Past sub-agent cards are collapsed and show terminal status.
  - [ ] Expanding shows the Result/summary and the recorded tool chain.
- **🐞 Bug signals:** all sub-agent cards expanded (wall of cards); a resumed card shows a spinner or live timer; missing summary.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

---

## F. Cross-IDE delegation (mostly OBSERVE-ONLY — needs two IDE instances)

> All of section F requires either a second IntelliJ instance/project to delegate to/from, or a previously-recorded delegated session on disk. **Any action that sends to another IDE is observe-only** — do not treat a failed cross-IDE send as a bug unless a second consenting instance is genuinely available. The delegation conversation **cards** can also be inspected by opening a recorded delegated session from History.

### [HIS-27] Delegation badge on a delegated session's history card
- **Component(s):** `DelegationBadge.tsx`, `SessionCard.tsx:109-113`
- **Preconditions:** A history session whose `delegated` metadata is set (a session this IDE received from another IDE).
- **Steps:** 1. In History, find a delegated session card.
- **Expected — visual:** Below the title, a small pill **"📨 {delegatorRepo}"** (repo name, never an "ide-$pid" id), tooltip "This session was delegated from {repo}".
- **✅ Checks (tick each):**
  - [ ] Badge shows the delegator REPO name (a real repo, not a pid/hash).
  - [ ] Tooltip text matches.
- **🐞 Bug signals:** badge shows "ide-1234" instead of a repo; badge on a non-delegated session; missing on a delegated one.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HIS-28] Delegated-session banner at the top of the chat (IDE-B)
- **Component(s):** `DelegationBanner.tsx`, `App.tsx:117`, `_setActiveSessionDelegated` → `chatStore.activeSessionDelegated`
- **Preconditions:** Open a delegated session (live, or reopened from History), so `activeSessionDelegated` is set.
- **Steps:** 1. Open the delegated session. 2. Read the banner above the transcript.
- **Expected — visual:** A thin top banner: **"Delegated from {repo} · Started {ago} · Active"** (or "· Closed {ago} ({reason})" once closed), with a 📨 glyph and the repo highlighted in the delegation accent color.
- **Expected — behavioral:** Banner clears on New Chat (`activeSessionDelegated → null`). Started/closed times use relative-age formatting.
- **✅ Checks (tick each):**
  - [ ] Banner shows the repo, started-ago, and Active vs Closed(reason).
  - [ ] Banner disappears on New Chat.
- **🐞 Bug signals:** banner persists into a non-delegated/new session; shows pid instead of repo; "Active" on a closed session.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only (opening/viewing only).

### [HIS-29] Incoming delegation prompt in the top bar (accept flow)
- **Component(s):** `IncomingDelegationBar.tsx` (in `TopBar.tsx:209`), `startIncomingDelegation`
- **Preconditions:** This IDE receives an incoming delegation knock from another instance (live two-instance setup, or simulate the store entry if available).
- **Steps:** 1. When a knock arrives, observe the top-bar pill. 2. Watch the countdown. 3. Either click **Start** or let it expire.
- **Expected — visual:** A compact pill: an inbox arrow icon, **"Incoming from {repo}"** (repo accent-colored), a live **{N}s** countdown (turns red ≤10s), and a **Start** button (disabled at 0s).
- **Expected — behavioral:** Start → `kotlinBridge.startIncomingDelegation(key)` + optimistic removal from the bar (Kotlin also pushes a cleared event when the session starts). Countdown reaching 0 → the pill auto-removes (`onExpire`). The 1s interval is created once per delegation (countdown is stable, doesn't reset each render).
- **✅ Checks (tick each):**
  - [ ] Pill shows repo + counting-down seconds; turns red under 10s.
  - [ ] Start launches the delegated session and clears the pill.
  - [ ] Expiry auto-removes the pill; Start is disabled at 0s.
- **🐞 Bug signals:** countdown jumps/resets; Start enabled at 0; pill lingers after start/expiry; multiple pills for one knock.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** **Observe-only on the cross-IDE handshake.** Clicking Start launches a real delegated session — only do so with a genuine consenting delegator.

### [HIS-30] Delegation conversation cards (Asked → Answered → Result) on IDE-B's panel
- **Component(s):** `DelegationConversationCards.tsx` (`DelegationQuestionCard`/`DelegationAnswerCard`/`DelegationResultCard`), `ChatView.tsx:161-170` (`say==='DELEGATION_CARD'`)
- **Preconditions:** A delegated session (live or recorded) where IDE-B asked the delegator a question and produced a result.
- **Steps:** 1. Open the delegated session. 2. Locate the conversation cards interleaved in the transcript.
- **Expected — visual:** Three card types, all keyed to the **delegator's repo name** (never "IDE-A/B"):
  - **Asked** (`ASKED`): "↗ Asked {repo}" header with **⏳ waiting** that flips to **✓ answered**; question text; option chips if provided.
  - **Answered** (`ANSWERED`): indented "↘ {repo} answered" with the answer text (pairs visually under the matching Asked card).
  - **Result** (`RESULT`): "✓ Result sent to {repo}" with a status badge (**COMPLETED** green / **FAILED**/**REJECTED** red / **CANCELED** amber) + duration; result text; a red reason line when failed/rejected.
- **Expected — behavioral:** These cards narrate the delegation conversation only — they are distinct from the agent's actual work (prose/tool/sub-agent cards). They persist to `ui_messages.json`, so a reopened delegated session shows the full conversation.
- **✅ Checks (tick each):**
  - [ ] All three card types render with the repo name (never a pid/"IDE-B").
  - [ ] Asked card flips ⏳ waiting → ✓ answered when its answer arrives.
  - [ ] Result card status color + duration + reason match the outcome.
  - [ ] Cards survive reopening the session from History.
- **🐞 Bug signals:** cards show "IDE-A"/"IDE-B" or a pid; Asked stuck on ⏳ after an answer exists; Result status color wrong (e.g. FAILED shown green); cards missing after reopen.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only (viewing/observing the conversation cards).

### [HIS-31] Delegation question pending banner re-syncs when navigating to the session
- **Component(s):** `AgentController.refreshDelegationQuestionBanner` (`AgentController.kt:4425-4440`), `DelegationInboundService.hasPendingQuestion`
- **Preconditions:** A delegated session that has an unanswered routed question raised while you were viewing a *different* session.
- **Steps:** 1. While a delegated session has a pending question, navigate away to another session/History. 2. Open the delegated session back up.
- **Expected — visual:** On opening, the pending-question banner/state re-appears for that session (it was suppressed at raise-time because it wasn't the viewed session).
- **Expected — behavioral:** `refreshDelegationQuestionBanner` re-checks `hasPendingQuestion(sessionId)` after `viewedSessionId` is set and re-pushes the banner (or clears a stale one).
- **✅ Checks (tick each):**
  - [ ] Pending-question state reappears when you return to the delegated session.
  - [ ] A session with no pending question shows no stale banner.
- **🐞 Bug signals:** pending question never resurfaces (silently lost); a stale pending banner shows on an answered session.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** observe-only (cross-IDE question/answer routing).

---

## G. Session handoff (`new_task`) — fork vs keep

### [HIS-32] Handoff preview card — "Start fresh session" vs "Keep chatting"
- **Component(s):** `HandoffPreviewCard.tsx`, `ChatFooter.tsx:153` (`handoff`), bridges `_handoffFork`/`_handoffKeep`
- **Preconditions:** A long session where the agent proposes a handoff (`new_task` tool) — e.g. ask it to "wrap up and continue in a fresh session."
- **Steps:** 1. When the handoff card appears, click **Show summary** to expand. 2. Choose either **Keep chatting here** or **Start fresh session**.
- **Expected — visual:** A card titled **"Continue in a fresh session?"** with sub-text about a handoff summary, an expandable summary (markdown), and two buttons: **Keep chatting here** (outline) + **Start fresh session** (primary/glow).
- **Expected — behavioral:** Exactly one decision fires (buttons disable after `decided`). **Keep chatting** → loop continues in the same session (`LoopResult.Completed`). **Start fresh session** → forks into a new session carrying the summary (`LoopResult.SessionHandoff`); the prior session remains in History.
- **✅ Checks (tick each):**
  - [ ] Summary expands/collapses; markdown renders.
  - [ ] Both buttons disable after a single click (no double-fire).
  - [ ] Keep → same session continues; Fresh → a new session starts with the handoff context and the old one is preserved in History.
- **🐞 Bug signals:** both bridges fire; buttons re-clickable after decision; fork loses the summary; "keep chatting" still wipes the session.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** "Start fresh session" creates a new local session (expected, not destructive); executes the live agent on continue — throwaway files only.

---

## Open questions / uncertainties (flag during testing, do not assume bugs)

1. **No status badge on history cards.** `HistoryItem` has no `status` field and `SessionCard` renders none (HIS-2). The prompt's "status (running/completed/interrupted/error)" exists only as the *resume-bar gating* when a session is opened — there is no at-a-glance status in the list. Confirm this is acceptable UX.
2. **No "filter to favorites" control** (HIS-7). Only free-text task search exists. Favoriting changes only the star icon. Confirm whether a favorites filter was intended.
3. **Context-menu Delete has no confirmation** (HIS-8) while the card-inline Delete (HIS-19) and bulk Delete (HIS-20) both confirm. Verify whether the unconfirmed context-menu delete is intended.
4. **Sort order is unspecified in the UI** (HIS-5). Order = `loadGlobalIndex`/`sessions.json` order; verify empirically whether it is newest-first and whether activity re-orders.
5. **Startup "interrupted session" is a notification balloon, not an in-chat banner** (HIS-16). It only fires for sessions touched within 10 minutes that lack a `.lock`. There is no auto-resume and no startup resume banner inside the chat — confirm this matches expectations.
6. **Context-menu position is not viewport-clamped** (`SessionContextMenu` uses raw `clientX/clientY`) — right-clicking a card near the bottom/right edge may clip the menu. Verify on a small tool-window.
7. **Cross-IDE delegation (section F) needs two consenting IDE instances** — on a single Windows test machine, most of section F is observe-only via recorded delegated sessions. A "failed send" with no second instance is expected, not a bug.
8. **`runIde` cannot be exercised on the author's Mac** (Ultimate license) — these scenarios are authored from source and must be physically confirmed on the Windows Ultimate machine. Relative-age timers in the history list are computed at render time (`Date.now()`), so they update on re-render, not on a ticking clock — don't flag "stale" ages in a static list as a bug.
