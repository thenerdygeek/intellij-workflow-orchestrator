# Code Walkthrough — Agent-Driven Guided Tours of Code

**Date:** 2026-06-11
**Status:** Approved design (brainstormed with user; approach 1 selected)
**Branch:** `feature/code-walkthrough`
**Module:** `:agent` only (no `:core` interface needed — all dependencies are platform APIs)

## 1. Overview

The agent can guide the user through a code flow like a product onboarding tour. It opens
files, highlights the relevant lines, and shows a small draggable callout box anchored
below the highlight containing a markdown explanation with **Back / Ask… / Next** controls.

The defining property is the **producer/consumer split**: the agent *streams* steps into a
queue via repeated tool calls while the user walks through them at their own pace. Neither
side blocks the other. If the user outruns the agent, the box shows a "Writing next step…"
loading state and auto-advances when the next step lands.

Decided in brainstorming:

| Decision | Choice |
|---|---|
| Box placement | Anchored callout **below** the highlighted block, arrow notch pointing up; flips above when no room (mockup A) |
| Draggable | Yes (`JBPopup.setMovable(true)`); drag pins for the current step only, arrow hides while dragged |
| Empty queue on Next | Loading state in the box; auto-advance on arrival |
| Box controls | Back, step counter ("Step 3 of 7+" while generating, "of 7" after finish), End tour (✕), Ask about this step |
| Q&A answers | Inline in the box, under the question |
| Persistence | Ephemeral v1 — tour dies with ✕/Done/project close; replay is v2 |
| Step generation | Streaming queue via meta-tool (instant Next, no LLM round-trip per step) |

## 2. Components (all in `:agent`)

| Component | Location | Purpose |
|---|---|---|
| `WalkthroughTool` | `tools/ide/WalkthroughTool.kt` | LLM-facing `AgentTool` (meta-tool, action-dispatched). Parses/validates params, delegates to service, returns `ToolResult` with queue/user-position feedback. |
| `WalkthroughService` | `walkthrough/WalkthroughService.kt` | Project-level `@Service`. Owns the tour lifecycle; thin shell that confines mutations to EDT and delegates to the state machine + UI seam. |
| `WalkthroughStateMachine` | `walkthrough/WalkthroughStateMachine.kt` | **Pure** state holder (no platform imports): step list, cursor, status, pending question, pending-next flag. Unit-testable headless. |
| `WalkthroughStep` | `walkthrough/WalkthroughStep.kt` | Data class: `file` (project-relative or absolute path, resolved like other file tools), `startLine`, `endLine` (1-based, inclusive), `title?`, `bodyMarkdown`. |
| `WalkthroughUi` (interface) + `WalkthroughUiImpl` | `walkthrough/` | UI seam so the service is testable with a fake. Impl composes the navigator and the popup. |
| `WalkthroughNavigator` | `walkthrough/WalkthroughNavigator.kt` | EDT helper: open file via `OpenFileDescriptor` → `FileEditorManager.openTextEditor`, scroll range to center, apply/remove the single active `RangeHighlighter`. |
| `WalkthroughCalloutPopup` | `walkthrough/ui/WalkthroughCalloutPopup.kt` | The draggable `JBPopup` callout: arrow notch, markdown body, header (counter + ✕), footer (Back / Ask… / Next), loading + answering states. |
| `WalkthroughMarkdown` | `walkthrough/ui/WalkthroughMarkdown.kt` | Pure markdown→HTML conversion via the `org.jetbrains:markdown` (intellij-markdown) library; rendered in `JBHtmlPane`. |

**State machine statuses:** `IDLE → ACTIVE(generating) → ACTIVE(complete) → ENDED`.
`finish` moves generating→complete. ✕/Done/project-close moves any → ENDED (popup disposed,
highlighter removed). A new `start` is only legal from IDLE/ENDED.

## 3. Tool contract

One **deferred** tool (registered via `registerDeferred(..., "IDE")`, discoverable through
`tool_search`; deliberately not core — §6c tool-defs are already half the prompt).

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
  would arrive broken (`run_maven_goal` bug, 2026-06-05). All four params MUST appear in
  `parameters.properties` (parser whitelist drops undeclared tags).
- `allowedWorkers = setOf(WorkerType.ORCHESTRATOR)` — sub-agents must not pop UI
  (same rationale as `render_artifact`'s exclusion).
- **Not** added to `WRITE_TOOLS` → usable in plan mode (read-only feature; "explain this
  code" is a natural plan-mode ask).
- `timeoutMs` = default (every action returns immediately; nothing blocks on the user).
- The tool **description must teach the streaming pattern**: "call start as soon as you
  know the first 1-2 steps, then keep exploring and append further steps as you find
  them; call finish when the tour is complete". Without this, models batch everything
  into one start call and the async UX degenerates to upfront generation. The deferred
  catalog one-liner: "Guided code tour: open files, highlight ranges, explain step-by-step
  in an anchored box the user pages through."

### Action semantics

| Action | Behavior | Errors |
|---|---|---|
| `start` | Validate all steps; create tour; show step 1; status=generating. | Tour already active → "finish or end the current tour first". All steps invalid → error, no tour created. |
| `append` | Validate; enqueue. If the user is waiting in the loading state, auto-advance. | No active tour → error. |
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

If the user ended the tour (✕), subsequent `append`/`finish` return an error stating the
tour was ended by the user — the natural signal for the agent to stop producing.
`summary` example: `Walkthrough: appended 2 steps (5 total, user on step 2)`.

## 4. UI behavior

**Showing a step:** open file (reuses existing editor if open) → scroll range to center
(`ScrollingModel.scrollToCaret(ScrollType.CENTER)` after caret move) → apply one
`RangeHighlighter` full-line band (theme-aware: `JBColor` pair ≈ selection blue at ~18%
alpha, layer `HighlighterLayer.SELECTION - 1`) → position popup below the block via
`editor.offsetToXY(lineEndOffset)`, flipping above (arrow flips to bottom edge) when the
visible area has no room below.

**Popup rules:**

- `createComponentPopup(...)`: `setMovable(true)`, `setRequestFocus(false)` (no focus
  steal; the Ask field requests focus on click), `setCancelOnClickOutside(false)`, Esc
  disabled. The tour ends only via ✕ or Done.
- Drag pins the box for the current step; arrow notch hides while pinned; the next step
  re-anchors and clears the pin.
- Highlighters are `RangeMarker`-backed → user edits mid-tour shift the band correctly.
  (Back re-creates the highlight from stored line numbers, which may drift after heavy
  edits — accepted v1 limitation.)
- The user may scroll, click, edit, or close files freely. The tour survives file close;
  Next/Back re-open whatever file the target step needs.
- Loading state: Next on empty queue → spinner + "Writing next step…", Next disabled;
  `append` arrival auto-advances.
- Lifecycle: popup + highlighter are registered with a `Disposable` tied to the service;
  project close disposes everything and ends the tour.

**Auto-finish:** if the agent run ends (completes, fails, cancelled) while the tour is
`ACTIVE(generating)`, `AgentController` calls `WalkthroughService.markGenerationEnded()` →
status becomes `complete`. The user is never stuck on a spinner that cannot resolve.
(Wiring: the same place run-teardown already notifies other UI state.)

## 5. "Ask about this step" flow

1. **Ask…** expands an inline text field in the box; Enter submits; one pending question
   at a time (input disabled while pending; "Answering…" shown).
2. The question is wrapped with context by a pure formatter:
   `[Walkthrough question about step 3 — agent/src/.../AgentLoop.kt:44-47] <question>
   (Reply using the walkthrough tool with action="answer".)`
3. Routing reuses existing transports verbatim — no new channel:
   - run active → **steering queue** (drained at the next iteration boundary, existing behavior);
   - run completed → **new user turn**, exactly like typing in chat.
4. The agent replies with `walkthrough(action=answer, body_md=...)`; the markdown renders
   under the question in the box. Plain assistant text still goes to the chat transcript —
   tool calls own the box, text owns the chat; one rule, no ambiguity.
5. After answering, the agent resumes appending if it wasn't finished.

## 6. Error handling & edge cases

| Case | Behavior |
|---|---|
| `start` while a tour is active | Tool error; existing tour untouched. |
| `append`/`finish`/`answer` with no tour | Tool error naming the missing precondition. |
| `answer` with no pending question | Tool error ("no pending question"). |
| Some steps invalid in a call | Valid steps kept; invalid ones itemized in the tool result for correction. |
| Step's file deleted before the user reaches it | At show-time the box shows "File no longer exists — step skipped"; Next stays enabled. |
| File shrank below `endLine` by show-time | Clamp range to EOF; show normally. |
| User mashes Next | EDT-confined state machine; pending-next is a single flag — idempotent. |
| User ends tour while agent still appending | Next `append` returns "tour ended by user" error; agent stops producing. |
| Agent run dies mid-generation | Auto-finish (Section 4); queue freezes at current size. |
| Sub-agent tries to call the tool | Not available (`allowedWorkers = ORCHESTRATOR` only). |
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
- The only cross-thread signal is "question submitted" → steering queue / new turn, which
  already has thread-safe transports.

## 8. Testing strategy

Unit tests (plain JUnit, no platform fixture):

- `WalkthroughStateMachineTest` — transitions: start/append/next/back/finish/end,
  pending-next auto-advance, counter text ("3 of 5+" vs "3 of 5"), pending-question gating.
- `WalkthroughToolTest` — action dispatch, steps-JSON-string parsing (including the
  array-as-string trap), per-step validation messages, error preconditions, fake service.
- `WalkthroughMarkdownTest` — markdown→HTML conversion (code spans, bold, lists).
- `QuestionEnvelopeTest` — exact wrapper format incl. the action="answer" instruction.
- Plan-mode/registration contract — `walkthrough` ∉ `WRITE_TOOLS`, survives plan-mode
  schema filtering, registered deferred with `ORCHESTRATOR`-only workers.

Platform-fixture tests (`BasePlatformTestCase`, **one test method per class** — known
indexing-timeout gotcha; `LightVirtualFile` for documents):

- `WalkthroughNavigatorTest` — open + highlight + range clamping on a light file.

Manual smoke (runIde): popup drag, arrow flip near viewport bottom, theme switch
light/dark, loading state with a slow model, Ask round-trip in both transports.

Vitest/webview: untouched (no webview changes).

## 9. Out of scope (v2 candidates)

- Saved/replayable tours (steps are plain data; persistence is additive later).
- Webview tour card fallback.
- Code Vision "resume tour here" affordance.
- Multiple simultaneous tours (state machine assumes one).
- Streaming the explanation text token-by-token into the box (v1 renders complete steps).
- Back-compat of highlights across heavy edits (RangeMarker drift accepted).
