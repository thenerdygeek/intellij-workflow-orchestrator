# Code Walkthrough — Agent-Driven Guided Tours of Code

**Date:** 2026-06-11 (rev 2 — post adversarial review; findings 1-10 absorbed)
**Status:** Approved design (brainstormed with user; approach 1 selected)
**Branch:** `feature/code-walkthrough`
**Module:** `:agent` only (no `:core` interface needed — all dependencies are platform APIs)

## 1. Overview

The agent can guide the user through a code flow like a product onboarding tour. It opens
files, highlights the relevant lines, and shows a small draggable callout box positioned
below the highlight containing a markdown explanation with **Back / Ask… / Next** controls.

The defining property is the **producer/consumer split**: the agent *streams* steps into a
queue via repeated tool calls while the user walks through them at their own pace. Neither
side blocks the other. If the user outruns the agent, the box shows a "Writing next step…"
loading state and auto-advances when the next step lands.

Decided in brainstorming (rev-2 changes marked):

| Decision | Choice |
|---|---|
| Box placement | Callout positioned **below** the highlighted block; flips above when no room. Positioned at show-time; does **not** track scrolling (rev 2; tracking is v2). Arrow notch cut to v2 (rev 2 — needs shaped-window painting; position carries the meaning). |
| Draggable | Yes — slim header bar (step counter + ✕) doubles as the drag grip (rev 2: `setMovable(true)` drags only via a caption component). Drag pins for the current step only; next step re-anchors. |
| Empty queue on Next | Loading state in the box; auto-advance on arrival. If the loop parks waiting for chat input, the box switches to "Agent is waiting for your input in chat" (rev 2). |
| Box controls | Back, step counter ("Step 3 of 7+" while generating, "of 7" after finish), End tour (✕) in the header, Ask about this step |
| Q&A answers | Inline in the box, under the question; deadlock-proofed (Section 5) |
| Persistence | Ephemeral v1 — tour dies with ✕/Done/`newChat`/session switch/project close (rev 2: session-lifecycle rule added); replay is v2 |
| Step generation | Streaming queue via meta-tool (instant Next, no LLM round-trip per step) |

## 2. Components (all in `:agent`)

| Component | Location | Purpose |
|---|---|---|
| `WalkthroughTool` | `tools/ide/WalkthroughTool.kt` | LLM-facing `AgentTool` (meta-tool, action-dispatched). Parses/validates params, delegates to service, returns `ToolResult` with queue/user-position feedback. Service obtained via constructor-injected provider `(Project) -> WalkthroughServiceApi` defaulting to `project.getService(...)` so plain-JUnit tests can fake it (repo seam pattern). |
| `WalkthroughService` | `walkthrough/WalkthroughService.kt` | Project-level `@Service`. Owns the tour lifecycle; thin shell that confines mutations to EDT and delegates to the state machine + UI seam. Records the **owning session id** at tour start. |
| `WalkthroughStateMachine` | `walkthrough/WalkthroughStateMachine.kt` | **Pure** state holder (no platform imports): step list, cursor, status, generation-paused flag, pending question, pending-next flag. Unit-testable headless. |
| `WalkthroughStep` | `walkthrough/WalkthroughStep.kt` | Data class: `file` (project-relative or absolute path, resolved like other file tools), `startLine`, `endLine` (1-based, inclusive), `title?`, `bodyMarkdown`. |
| `WalkthroughUi` (interface) + `WalkthroughUiImpl` | `walkthrough/` | UI seam so the service is testable with a fake. Impl composes the navigator and the popup. |
| `WalkthroughNavigator` | `walkthrough/WalkthroughNavigator.kt` | EDT helper: open file via `OpenFileDescriptor` → `FileEditorManager.openTextEditor`, scroll range to center, apply/remove the single active `RangeHighlighter`. |
| `WalkthroughCalloutPopup` | `walkthrough/ui/WalkthroughCalloutPopup.kt` | The draggable `JBPopup` callout: header bar (drag grip, counter, ✕), markdown body, footer (Back / Ask… / Next), loading / paused / answering states. |
| `WalkthroughMarkdown` | `walkthrough/ui/WalkthroughMarkdown.kt` | Pure markdown→HTML conversion via `org.intellij.markdown` (already on the platform classpath, verified in `lib/lib-client.jar`; `org.jetbrains:markdown:0.7.3` already trusted in `gradle/verification-metadata.xml`); rendered in `JBHtmlPane` (in `app-client.jar`, available 2025.1). **No new dependency.** |

**State machine statuses:** `IDLE → ACTIVE(generating) → ACTIVE(complete) → ENDED`.
`finish` moves generating→complete. ✕/Done/`newChat`/session-switch/project-close moves
any → ENDED (popup disposed, highlighter removed). A new `start` is only legal from
IDLE/ENDED. Orthogonal flags on ACTIVE(generating): `generationPaused` (loop parked
awaiting chat input), `pendingQuestion`, `pendingNext`.

## 3. Tool contract

One **deferred** tool — registered in `AgentService.registerAllTools()` via
`safeRegisterDeferred("Code Intelligence") { WalkthroughTool(...) }` (existing category;
deliberately not core — §6c tool-defs are already half the prompt).

```
walkthrough
  action   (string, required): "start" | "append" | "finish" | "answer"
  title    (string, optional): tour title — used by start
  steps    (string): JSON array as a STRING — required for start/append
           [{"file": "agent/src/.../AgentLoop.kt", "start_line": 44,
             "end_line": 47, "title": "Entry point", "body_md": "..."}, ...]
  body_md  (string): markdown answer — required for answer
```

- `steps` is a **string** param parsed with kotlinx-serialization inside the tool.
  BrainRouter serializes all XML params as string primitives, so a schema-level array
  would arrive broken (`run_maven_goal` bug, 2026-06-05; pattern reference:
  `RunMavenGoalAction.parseModules`). All four params MUST appear in
  `parameters.properties` (parser whitelist drops undeclared tags —
  `AssistantMessageParser.parse` only recognizes tags in `registry.allParamNames()`).
- **Sub-agent exclusion (enforcement):** add `"walkthrough"` to the name-based hard
  filter in `SpawnAgentTool` (`resolveConfigToolsTiered`), alongside `render_artifact` —
  that filter is the *real* mechanism; `ToolRegistry.getToolsForWorker`/`allowedWorkers`
  is dead code and gates nothing. We still set `allowedWorkers = setOf(ORCHESTRATOR)` as
  documentation, but the contract test must assert the name filter, not `allowedWorkers`.
  Without this, a user YAML persona listing `walkthrough` would hand a sub-agent the tool.
- **Interactive-controller guard:** the tool errors ("walkthrough requires the interactive
  agent chat") unless the executing session is the one owned by the project's
  `AgentControllerRegistry` controller. Delegated sessions (`startDelegatedSession`) and
  monitor/background auto-wake runs never reach `AgentController.onComplete`, so the
  auto-finish guarantee cannot hold there — they are excluded at the tool level.
- **Not** added to `WRITE_TOOLS` → survives plan-mode schema filtering
  (`ToolDefinitionFilter.shouldInclude`); usable in plan mode ("explain this code" is a
  natural plan-mode ask).
- `timeoutMs` = default (every action returns immediately; nothing blocks on the user).
- The tool **description must teach the streaming pattern**: "call start as soon as you
  know the first 1-2 steps, then keep exploring and append further steps as you find
  them; call finish when the tour is complete". Without this, models batch everything
  into one start call and the async UX degenerates to upfront generation. Deferred
  catalog one-liner: "Guided code tour: open files, highlight ranges, explain
  step-by-step in a callout box the user pages through."

### Action semantics

| Action | Behavior | Errors |
|---|---|---|
| `start` | Validate all steps; create tour; record owning session; show step 1; status=generating. | Tour already active → "finish or end the current tour first". All steps invalid → error, no tour created. Non-interactive context → error (guard above). |
| `append` | Validate; enqueue. If the user is waiting in the loading state, auto-advance. | No active tour → error. Tour ended by user → error saying so. |
| `finish` | Mark queue complete. Counter drops the "+"; last step's Next becomes **Done**. | No active tour → error. |
| `answer` | Render `body_md` under the pending question in the box; clear pending state. | No active tour or no pending question → error. |

Per-step validation (at start/append time, inside a `readAction`): file resolves to an
existing `VirtualFile`; `1 ≤ startLine ≤ endLine ≤ document line count`. Invalid steps are
rejected **individually** with precise messages (e.g. "step 3: line 580 exceeds file length
412") while valid steps in the same call are kept; the tool result lists both.

### Tool result feedback

Every call returns instantly with the tour's live status in `content`, e.g.:

```
Tour "How a tool call flows": 5 steps queued (queue complete: no), user is on step 2.
```

If the user ended the tour (✕/newChat/session switch), subsequent `append`/`finish`
return an error stating the tour was ended — the natural signal for the agent to stop
producing. `summary` example: `Walkthrough: appended 2 steps (5 total, user on step 2)`.

## 4. UI behavior

**Showing a step:** open file (reuses existing editor if open) → scroll range to center
(`ScrollingModel.scrollToCaret(ScrollType.CENTER)` after caret move) → apply one
`RangeHighlighter` full-line band (theme-aware: `JBColor` pair ≈ selection blue at ~18%
alpha, layer `HighlighterLayer.SELECTION - 1`) → position popup below the block via
`editor.offsetToXY(lineEndOffset)`, flipping above when the visible area has no room below.

**Popup construction** (`createComponentPopup`):

- `setMovable(true)` **plus a slim header bar as the drag grip** — movable popups drag
  via a caption component only; a captionless component popup has no grip. The header
  holds the step counter and ✕, so the grip costs no extra chrome.
- `setFocusable(true)` + `setRequestFocus(false)` — no focus steal on show; the Ask
  field requests focus on click.
- `setCancelOnClickOutside(false)` + `setCancelOnWindowDeactivation(false)` + Esc
  disabled — clicking back into the editor must not dismiss the tour; it ends only via
  ✕ or Done (or the session-lifecycle rules below).
- **Positioning is show-time only.** The popup does not track scrolling, editor resize,
  or window moves in v1 — the user is free to scroll away and the box stays where it is
  (they can drag it; the next step re-anchors). A `VisibleAreaListener`-based tracker is
  v2. No arrow notch in v1 (shaped-window painting; v2).
- Drag pins the box for the current step; the next step re-anchors and clears the pin.

**Other rules:**

- Highlighters are `RangeMarker`-backed → user edits mid-tour shift the band correctly.
  (Back re-creates the highlight from stored line numbers, which may drift after heavy
  edits — accepted v1 limitation.)
- The user may scroll, click, edit, or close files freely. The tour survives file close;
  Next/Back re-open whatever file the target step needs.
- Loading state: Next on empty queue → spinner + "Writing next step…", Next disabled;
  `append` arrival auto-advances.
- **Paused state:** when the loop parks awaiting chat input (plan approval,
  `ask_followup_question`, doom-loop recovery — `onLoopAwaitingUserInput` and the
  followup-question pending path), the service sets `generationPaused` and the box's
  loading state reads "Agent is waiting for your input in chat ↗" instead of an
  unresolvable spinner. Cleared when the loop resumes.
- Lifecycle: popup + highlighter are registered with a `Disposable` tied to the service;
  project close disposes everything and ends the tour.

**Auto-finish:** `AgentController.onComplete(result)` calls
`WalkthroughService.markGenerationEnded()` **before** dispatching on the result kind —
the `SessionHandoff` branch early-returns before the cleanup footer, so a footer
placement would leak a permanent spinner. `markGenerationEnded()` (idempotent): moves
ACTIVE(generating)→ACTIVE(complete), clears `generationPaused`, and resolves any pending
question with a fallback note (Section 5). Covers Completed, Failed, Cancelled, and
SessionHandoff. Non-controller runs can't start tours (Section 3 guard), so no other
teardown path needs wiring.

**Session lifecycle rule:** `AgentController.newChat` and `showSession` (switching to a
different session) end the tour exactly like ✕. This is what makes the Q&A routing sound
(Section 5): a live tour always belongs to the currently-shown session, so questions can
never target a stale or merely-viewed session.

## 5. "Ask about this step" flow

1. **Ask…** expands an inline text field in the box; Enter submits; one pending question
   at a time (input disabled while pending; "Answering…" shown).
2. The question is wrapped by a pure formatter:
   `[Walkthrough question about step 3 — agent/src/.../AgentLoop.kt:44-47] <question>
   (Reply using the walkthrough tool with action="answer".)`
3. **Routing — explicitly mapped to `AgentController.executeTaskInternal`'s real branches**
   (review finding: the router has five branches that can consume input; each is handled):
   - **Pending `ask_followup_question` wizard or delegation question:** Ask is *disabled*
     with tooltip "The agent is waiting for your answer in chat — reply there first."
     (Otherwise the walkthrough question would be consumed as the wizard answer.)
   - **Loop parked on `userInputChannel`** (plan-mode dialogue, new_task preview): submit
     through the channel — in that state the channel *is* the user-turn transport. The
     envelope arrives as the user turn; `walkthrough` is plan-mode-legal so the agent can
     `answer` and then re-park/continue its plan flow.
   - **Loop running:** `AgentLoop.enqueueSteeringMessage` (drained at the next iteration
     boundary, existing behavior).
   - **No active run:** new user turn via `executeTask` on the owning session. The
     viewed-session hijack branch is unreachable: by the Section 4 lifecycle rule a live
     tour implies the shown session == owning session.
   - **Chat surface:** both transports already echo the message to chat (queued-steering
     pill / `displayUserMessage`); pass `displayText` = the bare question so the envelope
     never renders raw in the transcript (the question correctly appears in history).
4. The agent replies with `walkthrough(action=answer, body_md=...)`; the markdown renders
   under the question in the box. Plain assistant text still goes to the chat transcript —
   tool calls own the box, text owns the chat; one rule, no ambiguity.
5. **Deadlock-proofing:** if the run ends (or hands off) while a question is pending —
   including the common failure where the model answers in plain chat text instead of
   calling `answer` — `markGenerationEnded()` clears the pending state, re-enables Ask,
   and the box shows "The agent may have answered in the chat panel ↗". The box can
   never wedge on "Answering…".
6. After answering, the agent resumes appending if it wasn't finished.

## 6. Error handling & edge cases

| Case | Behavior |
|---|---|
| `start` while a tour is active | Tool error; existing tour untouched. |
| `start` from delegated session / background run | Tool error (interactive-controller guard, Section 3). |
| `append`/`finish`/`answer` with no tour | Tool error naming the missing precondition. |
| `answer` with no pending question | Tool error ("no pending question"). |
| Some steps invalid in a call | Valid steps kept; invalid ones itemized in the tool result for correction. |
| Step's file deleted before the user reaches it | At show-time the box shows "File no longer exists — step skipped"; Next stays enabled. |
| File shrank below `endLine` by show-time | Clamp range to EOF; show normally. |
| User mashes Next | EDT-confined state machine; pending-next is a single flag — idempotent. |
| User ends tour while agent still appending | Next `append` returns "tour ended by user" error; agent stops producing. |
| Agent run ends mid-generation (any result kind incl. handoff) | Auto-finish before result dispatch (Section 4); queue freezes; pending question resolved with fallback note. |
| Loop parks awaiting chat input mid-generation | Box shows paused state, not an unresolvable spinner (Section 4). |
| `newChat` or switch to another session mid-tour | Tour ends (Section 4 lifecycle rule). |
| Sub-agent tries to call the tool | Excluded via SpawnAgentTool name filter (Section 3) — including persona-YAML allowlists. |
| Project closes mid-tour | Disposable chain removes popup + highlighter; state → ENDED. Ephemeral by design. |
| Split editors / multiple editors of same file | Navigator uses the editor returned by `openTextEditor` — the focused/primary one. |

Codebase-specific traps honored: params declared in `parameters.properties` (whitelist),
`steps` as JSON-string (BrainRouter primitive serialization), no `runBlocking` in main
sources (pre-commit hook; use `Dispatchers.EDT`), no `window.confirm` (no webview changes
at all in v1), `--no-build-cache` if any suspend signature changes during implementation.

## 7. Threading model

- Tool `execute()` runs in the loop's coroutine context; validation in `readAction`;
  all state mutation + UI via `withContext(Dispatchers.EDT)`.
- User clicks (Next/Back/✕/Ask) are already on EDT and call the service directly.
- The state machine is therefore single-thread-confined (EDT) — no locks.
- The only cross-thread signal is "question submitted" → channel/steering/new turn, all
  of which already have thread-safe transports.

## 8. Testing strategy

Unit tests (plain JUnit, no platform fixture):

- `WalkthroughStateMachineTest` — transitions: start/append/next/back/finish/end,
  pending-next auto-advance, generation-paused flag, counter text ("3 of 5+" vs "3 of
  5"), pending-question gating + fallback resolution on `markGenerationEnded`.
- `WalkthroughToolTest` — action dispatch, steps-JSON-string parsing (including the
  array-as-string trap), per-step validation messages, error preconditions,
  interactive-controller guard, constructor-injected fake service.
- `WalkthroughMarkdownTest` — markdown→HTML conversion (code spans, bold, lists).
- `QuestionEnvelopeTest` — exact wrapper format incl. the action="answer" instruction.
- Registration/exclusion contracts — `walkthrough` ∉ `WRITE_TOOLS`; survives plan-mode
  schema filtering; registered deferred; **present in SpawnAgentTool's name-based hard
  filter** (the real sub-agent gate — do NOT assert `allowedWorkers`, it gates nothing).
- Auto-finish placement — source-text contract test pinning that
  `markGenerationEnded` is invoked in `onComplete` before the result-kind dispatch
  (the SessionHandoff early-return leak). ⚠ Place any new AgentController functions
  OUTSIDE existing sentinel-sliced ranges (DelegationConversationNarrationTest trap);
  run the FULL `:agent:test`, not `--tests` filters.

Platform-fixture tests (`BasePlatformTestCase`, **one test method per class** — known
indexing-timeout gotcha; `LightVirtualFile` for documents):

- `WalkthroughNavigatorTest` — open + highlight + range clamping on a light file.

Manual smoke (runIde): popup drag via header, flip near viewport bottom, theme switch
light/dark, loading + paused states, Ask round-trip in all three transports (parked /
running / idle), tour end on newChat.

Vitest/webview: untouched (no webview changes).

## 9. Out of scope (v2 candidates)

- Saved/replayable tours (steps are plain data; persistence is additive later).
- Arrow notch on the callout (shaped-window painting) and scroll-tracking reposition
  (`VisibleAreaListener`) — cut by review to de-risk v1 UI.
- Webview tour card fallback.
- Code Vision "resume tour here" affordance.
- Multiple simultaneous tours (state machine assumes one).
- Streaming the explanation text token-by-token into the box (v1 renders complete steps).
- Back-compat of highlights across heavy edits (RangeMarker drift accepted).
- Walkthroughs from delegated/background sessions (blocked by the controller guard).
