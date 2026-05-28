# Cross-IDE Delegation — IDE-B agent reuse (receiver UX)

Date: 2026-05-28 · Branch: `feature/cross-ide-delegation` · Status: design (Phase 1 / MVP approved)

## Problem

When IDE-A delegates a task to IDE-B, the receiving side was supposed to run the work as a
**normal, visible, interactive agent session** in IDE-B (spec `2026-05-22-...-design.md` §3.2:
"a new session tab appears … the human can intervene at any time by virtue of the session tab
being live and interactive"). That receiver UI was never wired.

What exists today: `DelegationInboundService.handleConnect` → `AgentService.startDelegatedSession`
runs the agent loop with the UI **stripped off** — it passes only `onComplete`; **no** streaming
callbacks (`onStreamChunk`/`onToolCall`), **no** `approvalGate`, **no** `onSessionStarted`, and never
activates the tool window. The delegated agent therefore runs headless: writes auto-execute with no
IDE-B approval, nothing streams to the webview, and the session is only reachable after the fact via
History. That stripped-down invocation is the "separate background implementation" we are removing.

## Goal

An accepted inbound delegation runs as a **first-class IDE-B agent session that reuses the full
agent** — same tools, same system prompt, same persistence, and **IDE-B's own approval gate +
per-tool auto-approve policy** — with the only delegation-specific behaviors being (a) clarifying
questions route to IDE-A and (b) a **verbose** final result is sent home to IDE-A.

### Requirements (approved)

1. **Full-agent reuse.** The delegated session executes through the same `service.executeTask`
   callback bundle a normal session uses (the `startHandoffSession` precedent), not the stripped
   invocation. IDE-B's `approvalGate` + `SessionApprovalStore` + per-tool auto-approve settings apply
   — the IDE-B human authorizes writes exactly as in their own sessions ("same authority, same
   approvals").
2. **Clarifying questions → IDE-A.** Unchanged. `AskQuestionsTool` already detects the delegated
   context and routes via `DelegationInboundService.routeQuestion`; Agent-A answers. Keep the
   question-routing / pending-token / answered-locally machinery.
3. **Verbose result home.** On completion IDE-B sends `DelegationMessage.Result` carrying Agent-B's
   **full `attempt_completion` text** (not a truncated summary) plus structured fields (filesChanged,
   branch, commit). Wire frame cap is 10 MiB (`DelegationFraming.MAX_FRAME_BYTES`), so full verbosity
   is safe.
4. **Concurrent + non-hijacking.** The delegated session runs as an **independent** session/job so it
   coexists with whatever the IDE-B human is doing. It never steals the agent tab from an in-progress
   local session. Surfacing:
   - agent tab **idle** (no active local session) → the delegated session opens automatically and
     streams live (it becomes the displayed session via the normal path);
   - agent tab **busy** → a non-hijacking "incoming delegation from `<repo>`" entry/badge appears;
     clicking it opens the session to view. Both sessions keep running.
5. **No dead code / no parallel path.** The old stripped headless execution path is **removed**, not
   kept alongside the new one. After the switch, the receiver has exactly one way to run a delegated
   session (full-agent reuse). Any helper, branch, or field that exists solely to support the headless
   invocation and is unused afterward is deleted in the same change. (Question-routing, heartbeat,
   resume, and consent machinery stay — they are still used. Only the UI-stripped execution path and
   its now-orphaned support code go.)

### Non-goals (Phase 2 — deferred, noted only)

- Real-time token streaming of a **background/unfocused** delegated session into the webview the
  instant the human focuses it (Phase 1 shows the persisted conversation on open; live token
  streaming while focused-after-the-fact is Phase 2).
- Concurrent approval cards across multiple live sessions in one webview.
- Resuming a delegated session across an IDE-B restart (already a v1 non-goal).
- Cross-machine / cross-user / multi-hop delegation (out of scope as before).

## Design

### Components

| Component | Change |
|---|---|
| `AgentService.startDelegatedSession` | The current UI-stripped execution path is **removed**. `AgentController` owns UI wiring (see below), so this method is either deleted outright or reduced to a thin shim only if a caller still needs a non-UI entry (none is expected — verify and delete if orphaned). No parallel headless run survives. |
| `AgentController` | New `startDelegatedSession(briefing, delegationMetadata, replyWith, onResult)` mirroring `startHandoffSession`: calls `service.executeTask` with the full bundle (`onStreamChunk`/`onToolCall`/`approvalGate`/`sessionApprovalStore`/`onComplete`), registers the session, runs it as an **independent job** (not clobbering the human's `currentJob`), and decides surfacing (auto-open if idle, else incoming badge). Provides a **non-cancelling** "open a delegated session for viewing" path (today's `showSession` calls `cancelCurrentTask` — must not cancel a still-running delegated session). |
| `DelegationInboundService.handleConnect` | On accept (or preauth), activate IDE-B's Workflow/Agent tool window (initializing the controller via the `AgentChatRedirectImpl` pattern) and route the session start through `AgentController.startDelegatedSession` instead of the headless `AgentService` path. Keep `registerSessionChannel` (question routing) and the terminal-result send. |
| Result mapping | Map `LoopResult.Completed` → `Result(summary = <full attempt_completion text>, filesChanged = SessionCheckpointStore.aggregateDiff(), …)`. Remove the `.take(200)` truncation on the handoff branch. |
| Webview / session list | Render the `delegated`/"incoming" marker on the session entry; show a "needs approval" sub-state when the session's approval gate is waiting and the session is not focused. |

### Data flow (accepted delegation)

```
IDE-A delegation(send) ──IPC Connect──▶ IDE-B DelegationInboundService.handleConnect
  (consent/accept already gated — see consent fix 2026-05-28)
        │
        ├─ activate Workflow▸Agent tool window (init controller)
        ├─ registerSessionChannel(sid, replyWith)            # question routing to IDE-A
        └─ AgentController.startDelegatedSession(briefing, meta, replyWith, onResult)
                │  runs service.executeTask(... full callbacks ...) as an INDEPENDENT job
                │    • onStreamChunk/onToolCall → webview (when focused) + persist
                │    • approvalGate → IDE-B human (per-tool auto-approve policy)
                │    • ask_followup_question → routeQuestion → IPC Question ─▶ IDE-A answers
                │  surfacing: idle → open+display; busy → incoming badge, open-to-view
                ▼
        on LoopResult.Completed → onResult(verbose Result) ──IPC Result──▶ IDE-A nudge
```

### Approvals (Phase 1)

The delegated session uses IDE-B's normal `approvalGate` + per-tool auto-approve settings.
- Auto-approved tools run without prompting.
- A tool needing approval suspends the gate (existing `suspendCancellableCoroutine`); the session's
  entry shows a "needs approval" badge. When the human opens the session it becomes focused and the
  pending approval card renders via the normal gate; approving/denying resumes the loop. (Concurrent
  approval cards for multiple focused-vs-background sessions are Phase 2.)

### Concurrency model

The delegated session is an independent `AgentSession` with its own loop job — it does **not** become
`AgentController.currentJob` unless the tab was idle and it auto-opened. This preserves the existing
"single displayed session" invariant of the webview while allowing the delegated loop to run in
parallel and persist live. Focus is a view concern, not an execution concern.

## Error handling

- Controller not initialized (agent tab never opened): activating the tool window initializes it; if
  activation fails (headless/no tool window), fall back to the current independent-run path so the
  delegation still completes and returns a result (degraded: no live view, result still sent home).
- IDE-B human closes the project mid-session: existing `closeAllForProjectClose` sends a terminal
  `FAILED{project_closed}` to IDE-A (unchanged).
- Loop failure/cancel: map to `Result(FAILED/CANCELED)` as today; verbose `reason` included.

## Testing strategy

- **Result verbosity** (unit): `startDelegatedSession`/result-mapping emits the full completion text,
  not a truncated summary (assert no `.take(200)` and full text passes through).
- **Approval gate wiring** (unit/source-contract): the delegated execution path passes a non-null
  `approvalGate` + `sessionApprovalStore` (today it passes none) — pin so writes can't silently
  auto-execute in a delegated session.
- **Question routing preserved** (existing tests): `routeQuestion` / pending-token behavior unchanged.
- **Surfacing decision** (unit): idle → auto-open (controller has no active session) vs busy → badge,
  driven by a testable predicate on `currentSessionId`/`currentJob`.
- **Non-cancelling open** (source-contract/unit): opening a running delegated session for viewing does
  NOT call `cancelCurrentTask` on it.
- Headless/EDT-dependent UI (tool-window activation, badge render) pinned by source-contract tests in
  the established codebase style where unit coverage isn't feasible.
- **No-dead-code verification** (Requirement 5): a source-contract test asserts the headless
  delegated-execution path is gone (e.g., `DelegationInboundService` no longer calls the stripped
  `AgentService.startDelegatedSession`, and the removed symbols have no remaining references); the
  build compiles with no unused-symbol warnings for the deleted helpers.

## Relationship to the 2026-05-28 consent fix

The consent path (doorbell "Allow once" / Accept) fixed earlier today is the **gate before** this
flow. Sequence: doorbell consent → Accept (or preauth) → **this design** runs the full-agent session.
The consent fixes (modeless await, Windows agent-dir keying, doorbell-aware status, preauth ordering)
are a prerequisite — a delegation must actually be accepted for any session to start.

## Code removal (Requirement 5 — explicit list to verify during implementation)

- The UI-stripped invocation in `AgentService.startDelegatedSession` (the `executeTask` call passing
  only `onComplete`, no `approvalGate`, no streaming callbacks). Replaced by the controller path.
- `AgentService.startDelegatedSession` itself if no caller remains after `DelegationInboundService`
  routes through `AgentController` (grep callers; delete or shrink to a verified-needed shim).
- The `.take(200)` summary truncation on the `SessionHandoff`/result-mapping branch (replaced by the
  verbose result).
- Any field/helper that existed only to support the headless run and is unused after the switch.
- KEEP (still used): question-routing (`routeQuestion`/pending-token/`AnswerCanceled`), heartbeat,
  `ChannelResume`/resume, doorbell + consent. Do not remove these.

## Open items for the implementation plan

- The "open a running session for viewing without cancelling it" capability — likely a variant of
  `showSession` guarded to skip `cancelCurrentTask` when the target is a live delegated session.
- Where the "incoming delegation" badge lives in the session-list webview component.
