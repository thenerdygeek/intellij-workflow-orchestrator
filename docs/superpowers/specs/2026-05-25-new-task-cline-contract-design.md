# Restore the Cline `new_task` Contract + Fix the Chat-Wipe Bug

**Date:** 2026-05-25
**Module:** `:agent`
**Status:** Design — approved, pending spec review

## Problem

The `new_task` tool (session handoff) currently **auto-fires**: when the orchestrator LLM
calls it, `AgentLoop` exits with `LoopResult.SessionHandoff` and `AgentController` immediately
starts a fresh session, with no user confirmation. This diverges from Cline's contract — and
from `NewTaskTool`'s own description (`NewTaskTool.kt:44-45`), which promises *"The user will
be presented with a preview of your generated context and can choose to create a new task or
keep chatting in the current conversation."*

The auto-fire also surfaces a misleading `"Context limit reached. Starting fresh session with
preserved context."` caption (`AgentController.kt:2556`) — nothing on the path actually checks
a token limit; the handoff is purely LLM-initiated.

### The chat-wipe bug (same code path)

After a handoff (and, intermittently, after resuming a session), sending the next message
makes the entire visible chat history **vanish**. Root cause:

- `handleUserMessage` decides "is this a brand-new chat?" solely from
  `isFirstMessage = (contextManager == null)` (`AgentController.kt:1849`).
- When `true`, it calls `dashboard.startSession(uiText)` → `chatStore.startSession` →
  `set({ messages: [firstMessage], ... })` (`chatStore.ts:613`), a **hard reset** of the
  visible message list.
- Session-entry paths that bypass `handleUserMessage` leave the controller's `contextManager`
  field `null`:
  - **Handoff:** `AgentController.kt:2569` nulls it; `startHandoffSession` →
    `executeTask(contextManager = null)` builds a *local* `ctx` (`AgentService.kt:1742`) that is
    never returned to the controller. `onSessionStarted` is not wired, so `currentSessionId`
    also stays stale.
  - **Resume:** `AgentController.kt:3178` explicitly nulls it; the resumed session builds its
    own `ContextManager` inside the service. (Resume *does* set `currentSessionId`, but that
    does not gate the wipe.)
- The next user-typed message → `handleUserMessage` → `isFirstMessage == true` →
  `startSession()` → **chat view wiped to just the new message.**

No data is lost on disk (the session's `ui_messages.json` is intact; reopening from History
reloads it) — the bug is purely the in-memory webview message list being replaced.

## Goal

Make `new_task` a **suspend-and-confirm** flow (LLM proposes → user decides), structurally
identical to the existing `plan_mode_respond` flow, and fix the root-cause desync so neither
the fork path nor the resume path can silently wipe the chat.

Non-goals (YAGNI): no "edit summary before fork," no automatic/token-pressure trigger, no
settings toggle. `new_task` stays purely LLM-initiated; the only behavioral change is that it
now asks before forking.

## Design

### 1. Loop behavior — reuse the plan-mode machinery

- `NewTaskTool` returns a new `ToolResultType.HandoffProposed(context)` instead of
  `ToolResult.sessionHandoff(...)`.
- `AgentLoop`, on `HandoffProposed`:
  1. fires a new `onHandoffProposed(context: String)` callback (renders the card),
  2. **suspends on `userInputChannel`** — the same await `plan_mode_respond` uses after
     presenting a plan. The loop does **not** exit at this point.
- The card's two buttons feed a sentinel string back through `userInputChannel`:
  - `__HANDOFF_FORK__` → loop returns `LoopResult.SessionHandoff(context)` (existing fork
    outcome, unchanged downstream).
  - `__HANDOFF_DECLINE__` → loop returns control to the user (Completed-style exit). The
    proposed summary is discarded; the current session stays fully intact.

Rationale for reuse: `plan_mode_respond` already solves "present something, suspend the loop
mid-run, let only the user advance via a JCEF card, resume on a channel." `new_task` is the
same shape, so we mirror it rather than inventing a parallel mechanism.

### 2. The preview card UI

- New React `<HandoffPreviewCard>` rendered in `ChatFooter` from a `chatStore.handoff`
  projection (mirrors the plan-card wiring; `ChatFooter` is the stable footer that subscribes
  to `chatStore` directly).
- Contents: the 5-section summary, **collapsible** (long summaries must not dominate the view),
  and exactly two buttons — **"Start fresh session"** and **"Keep chatting here"**.
- Decision is delivered exactly once: an `decidedRef` guard (same pattern as the artifact
  pipeline's `reportedRef`) rejects stale/double clicks.
- Bridge: Kotlin → JS push to open the card (`onHandoffProposed`); JS → Kotlin
  `JBCefJSQuery` carrying the decision sentinel, fed into `userInputChannel`. Reuse the
  plan-approval bridge shape.

### 3. Fork path + the root-cause bug fix

When the user clicks **Start fresh session**:

- Loop returns `LoopResult.SessionHandoff(context)`. Old session saved as `COMPLETED`
  (unchanged). The forked session opens with a **"↪ Continued from previous session"** banner
  (collapsible summary) at the top, instead of the current fake `"Continue from the previous
  session…"` user bubble.
- Clearing the view for the fork is now *intentional and consented*, but the **next** message
  must append. Root-cause fixes:
  - **Decouple the wipe trigger from `contextManager`.** Introduce a dedicated
    `sessionActive: Boolean` flag on `AgentController`, set `true` by *every* session-entry path
    (`handleUserMessage` first message, `startHandoffSession`, `resumeSession`) and reset to
    `false` only by `newChat()`. Gate the `startSession()` view-reset on `!sessionActive`
    instead of `contextManager == null`. This is robust against the pre-allocated-`sessionId`
    case (`AgentController.kt:614`, where an early image upload sets `currentSessionId` before
    the first message), which a `currentSessionId == null` gate would mishandle. A handoff or
    resume marks the session active, so the next message appends instead of wiping; the "New
    Chat" button clears the flag, so a genuinely fresh chat still resets the view.
  - **Wire `onSessionStarted`** into `startHandoffSession`'s `executeTask` call so
    `currentSessionId` updates to the new forked session.
  - **Propagate the new `ContextManager` back** to the controller (a callback analogous to
    `onSessionStarted`, e.g. `onContextManagerReady`), so token-usage display and subsequent
    `isFirstMessage` checks see a live manager.
- Remove the misleading `"Context limit reached…"` caption.

### 4. Decline path

**Keep chatting here** discards the proposed summary, drops the spinner, and returns control.
The current session's `currentSessionId` and `contextManager` are already non-null (it is a
normally-started, in-progress session), so the user's next message appends — no wipe. A brief
status line (`"Staying in this session."`) confirms the choice.

### 5. Desync sweep

Audit every session-entry path that bypasses `handleUserMessage` for the
`contextManager` / `currentSessionId` desync and apply the section-3 fix uniformly:

- `startHandoffSession` (fork) — primary.
- `resumeSession` (`AgentController.kt:3077`) — confirmed variant.
- `revertToUserMessage` (`AgentController.kt:3245`) — nulls `contextManager` at line 3261;
  verify the next message appends.
- Auto-wake resume (`setAutoWakeListener`, `AgentController.kt:553`) — routes through
  `resumeSession`; covered transitively, but assert it.

## Components touched

| Component | Change |
|---|---|
| `tools/AgentTool.kt` | New `ToolResultType.HandoffProposed(context)`; deprecate/remove `sessionHandoff` factory once callers move. |
| `tools/builtin/NewTaskTool.kt` | Return `HandoffProposed` instead of `sessionHandoff`. Doc/KDoc updated. |
| `loop/AgentLoop.kt` | Handle `HandoffProposed`: fire `onHandoffProposed`, suspend on `userInputChannel`, branch on decision sentinel. New callback field. |
| `AgentService.kt` | `executeTask`/`startHandoffSession`: expose `onContextManagerReady`; ensure `onSessionStarted` is forwarded on the handoff path. |
| `ui/AgentController.kt` | Render card via `onHandoffProposed`; feed decision into `userInputChannel`; fix `isFirstMessage` gating; wire `onSessionStarted` + `onContextManagerReady` on handoff/resume; remove "Context limit reached" caption; add "↪ Continued" banner. |
| `webview/.../ChatFooter.tsx` | Mount `<HandoffPreviewCard>`. |
| `webview/.../HandoffPreviewCard.tsx` | New component (collapsible summary + two buttons + exactly-once guard). |
| `webview/.../chatStore.ts` | `handoff` projection + open/clear actions; ensure it resets on `startSession`/`endStream`/`clearChat`. |
| `webview/.../jcef-bridge.ts` | Open-card push + decision query. |

## Testing

- **Failing test first** (TDD): reproduce the chat-wipe — start a session, fork via handoff,
  send a message, assert the prior messages survive (the view-reset is skipped because
  `sessionActive == true`).
- `AgentLoop`: `HandoffProposed` fires `onHandoffProposed` and suspends (no exit); fork
  sentinel → `LoopResult.SessionHandoff`; decline sentinel → control-return result.
- `AgentController`: after fork, `currentSessionId` and `contextManager` are non-null; resume
  and revert paths likewise leave a subsequent message appending.
- React: `<HandoffPreviewCard>` renders two buttons + collapsible summary; decision delivered
  exactly once; card clears on session reset.
- Desync sweep assertions per section 5.

## Risks / notes

- `plan_mode_respond` and `new_task` will both suspend on `userInputChannel`. Confirm the
  channel's single-consumer contract isn't violated (they are mutually exclusive in time — a
  turn is either presenting a plan or proposing a handoff, never both). Pin with a test.
- Steering-during-card: if the user types while the card is open, follow the plan-card
  precedent (typed text is the decision channel input, not a steering message). Reuse, don't
  reinvent.
