# Plan 4 — Cross-IDE Delegation Continuity Pass

**Status:** Draft for review · **Date:** 2026-05-23 · **Branch:** `feature/cross-ide-delegation` · **Worktree:** `.worktrees/cross-ide/`

**Parent spec:** [`2026-05-22-cross-ide-agent-delegation-design.md`](2026-05-22-cross-ide-agent-delegation-design.md) — the canonical v1 design. This document does not re-derive that spec; it specifies *which* of the deferred v1 behaviors land in Plan 4 and *how* the work is organized.

**Predecessor plans:** Plans 0+1+2+3+3.1 shipped on this branch:
- [`2026-05-23-cross-ide-handoff-status.md`](2026-05-23-cross-ide-handoff-status.md) — pre-Plan-3 snapshot
- [`2026-05-23-cross-ide-plan-3-robustness-design.md`](2026-05-23-cross-ide-plan-3-robustness-design.md) — the just-shipped robustness pass

Plan 4 finishes the v1 spec by implementing the four "stateful continuity" behaviors deferred from Plan 1's MVP cut. After Plan 4 ships, the v1 spec is fully delivered.

---

## 1. Goals

Implement the four v1-spec behaviors that didn't land in Plans 1+2+3, completing the "stateful continuity" surface:

1. `continue_with` argument on `delegation_send` — reuse an existing channel for follow-up work without re-prompting (v1 spec §4.1)
2. `CHANNEL_RESUME` protocol — re-attach delegation handles after IDE-A restart (v1 spec §9.3)
3. History-list badge for delegated sessions in IDE-B's chat panel (v1 spec §9.1)
4. IDE-B input banner UX while a question is pending (replaces the Plan 2 review F7 MVP inline nudge)

After this plan merges, the v1 spec is fully shipped except for the explicitly-deferred non-goals in §2.

## 2. Scope

### 2.1 In

| # | v1 spec ref | Behavior | Plan 4 task |
|---|---|---|---|
| 1 | §4.1 (`continue_with`), §6.2 | `delegation_send(handle = X)` skips picker + Accept, routes to existing Agent-B session | Task 2 |
| 2 | §9.3 | `CHANNEL_RESUME` protocol — re-attach after IDE-A restart with lazy probe | Task 3 |
| 3 | §9.1 | "Incoming" badge on IDE-B session-list rows + banner in detail view | Task 4 |
| 4 | (handoff §6 row 4) | IDE-B input banner when a question is pending | Task 5 |

### 2.2 Out (non-goals, unchanged from v1 §2)

- Cross-machine, cross-user, multi-hop, per-delegation tool restrictions, encryption / TLS on IPC, non-plugin IDEs, web / Slack surfaces.

These remain non-goals. Anything in v1 §2 stays out.

## 3. Design decisions

### 3.1 `continue_with` semantics

Spec §4.1 prescribed:

- Skips the picker AND the Accept prompt — the human already accepted this channel when it was first established.
- Routes the message as the next user turn to the existing Agent-B session.
- Dead handle returns `DelegationExpired` immediately (no half-state).

No re-Accept on long idleness. Trust model is "same human both sides" (v1 §8). If the original Accept goes stale through user-perception drift, that's a UX concern handled by the IDE-B input banner (item 4) and the existing `delegation_close` tool, not by re-prompting.

### 3.2 Outbound handle persistence

Currently `DelegationOutboundService.activeChannels` + `handleToSessionId` are in-memory only. Plan 4 introduces:

- **New file `agent/sessions/{id}/delegation-handles.json`** storing the list of outbound handles owned by Agent-A's session ID:

  ```
  [
    {
      "handleId": "uuid-a",
      "targetProjectPath": "/Users/.../frontend-app",
      "targetRepoName": "frontend-app",
      "bSessionId": "sess-uuid-on-B",
      "lastSeenState": "RUNNING",
      "createdAt": 1716480000000,
      "schemaVersion": 1
    }
  ]
  ```

- **Atomic two-file write pattern** matching the existing session-persistence convention (`.tmp` + `Files.move(ATOMIC_MOVE)`), coroutine `Mutex`-serialized.
- **Lifecycle:** written on `DelegationOutboundService.send()` after the Accept succeeds; updated on every observed state transition (`lastSeenState` field); removed on `close()`.
- **Loaded** on session resume — handles are still in-memory but seeded from disk.
- **`schemaVersion: 1`** for forward-compat. Future migrations bump the field; the loader rejects unknown versions and logs a warning (handles for that session are silently dropped; user can `delegation_send` fresh).

The Agent-A's sessionId is the persistence key. The `bSessionId` is what gets sent in `CHANNEL_RESUME` requests.

### 3.3 `CHANNEL_RESUME` firing model

Lazy per spec §9.3. On Agent-A's first `delegation_*` tool call after restart:

1. Look up the requested handle in the now-rehydrated outbound map.
2. The channel is dead (no `SocketChannel` in memory after restart). Trigger probe.
3. Compute the deterministic socket path; PING.
4. On PONG, send `ChannelResume(bSessionId, lastSeenState)`.
5. Switch on the reply:
   - **`ChannelResumed(bSessionId, currentState)`** — IDE-B confirms the session is still alive. IDE-A re-attaches: re-open the SocketChannel, restart the reader loop, restart the idle timer + heartbeat-tracking on the lastSeenAt clock. Emit a single coalesced nudge to Agent-A: "Channel for {bRepoName} re-attached. Last-known state {lastSeenState}; current state {currentState}. Resuming."
   - **`SessionClosed(bSessionId, closeReason, summary?)`** — IDE-B's session is terminal. IDE-A treats the handle as expired, returns `DelegationExpired` with the recorded reason. The `summary` from the error payload surfaces in Agent-A's chat as a recovery nudge: "Delegated session to {bRepoName} closed during restart: {closeReason}. Summary: {summary}." So Agent-A sees the outcome even though it missed the live close event.
   - **`SessionNotFound(bSessionId)`** — IDE-B has pruned the session (or it was never seen, e.g., session JSON deleted). Treat as expired. No summary.
6. If PING fails (IDE-B not running, socket file missing, port-refused): return `DelegationExpired` with `reason: "ide_b_not_running"`.

Idempotency: repeated `ChannelResume` for the same `bSessionId` is safe — IDE-B returns the current state each time. If IDE-A resumes the same handle twice (e.g., parallel `continue_with` + `delegation_close`), the second call hits the now-live reader and just succeeds.

### 3.4 History-list badge

IDE-B's React HistoryView already receives `HistoryItem.delegated: DelegationMetadata?` via the existing `_loadSessionHistory` JCEF bridge (Plan 1 added the field). Plan 4 makes it visible:

- **Row badge** — when `delegated != null`, render a small "📨 delegated by {delegatorRepo}" tag on the session row. JB-themed colors.
- **Detail-view banner** — when an open session has `delegated != null`, render a top banner: "Delegated by {delegatorIde} from {delegatorRepo}. Started {time-ago}. {closeReason ? `Closed: ${closeReason} (${closedAt})` : "Active."}".
- **Empty-state guidance** — when the user is in the delegated-session view, the existing "Take over" / "Cancel" affordances continue working unchanged.

Render-only change. No new bridge call, no new metadata pipeline.

### 3.5 IDE-B input banner

Plan 2 review F7 MVP-fixed the "is a question pending?" UX by injecting an informational nudge message inline in the chat history. Plan 4 replaces that with a proper banner:

- When `DelegationInboundService.hasPendingQuestion(sessionId) == true` for the active session, the React InputBar renders a non-modal banner ABOVE the input field: "📤 Question forwarded to {delegatorRepo}. Type to short-circuit and answer it yourself."
- Banner clears on:
  - Question resolution (either side answered) — JCEF bridge notification
  - User input submission — the local-answer path takes the typed text
- The Plan 2 MVP inline-nudge is removed when the banner is present. Existing tests for `routeQuestion`'s nudge behavior need updating.

Banner must compose cleanly with existing input-area state (steering-mode badge, plan-mode badge). Use existing badge-row container; banner is a new sibling above the badges, not a replacement.

## 4. Protocol additions

Task 1 lands the wire format. All additions extend the existing sealed `DelegationMessage` hierarchy in `core/delegation/DelegationProtocol.kt`; framing (4-byte length prefix + JSON, 10 MiB cap) is unchanged.

### 4.1 New message variants

Five new sealed subtypes:

| Variant | Direction | Carries |
|---|---|---|
| `ChannelResume(sessionId: String, lastSeenState: String)` | A → B | Re-attach request after IDE-A restart. `lastSeenState` is the most-recent known state from IDE-A's persisted handle (`RUNNING` / `AWAITING_ANSWER` / etc.); IDE-B uses it only as a diagnostic. |
| `ChannelResumed(sessionId: String, currentState: String)` | B → A | Confirmation that the session is still alive. `currentState` is authoritative. |
| `SessionClosed(sessionId: String, closeReason: String, summary: String?)` | B → A | Session reached a terminal state while IDE-A was offline. `summary` populated for `closeReason == "completed"`. |
| `SessionNotFound(sessionId: String)` | B → A | Session was never seen by IDE-B (e.g., pruned). |
| `UserTurn(sessionId: String, text: String)` | A → B | Append a new user turn to the existing Agent-B session. Used by `continue_with` (§5.2). |

`closeReason` enum-string values: `completed`, `canceled`, `rejected`, `failed`, `timed_out`, `project_closed`, `parent_canceled`, `idle_timeout` (matches existing `DelegationMessage.Result.reason` vocabulary).

### 4.2 `DelegationHandle.lastSeenState`

Add a new field to the handle struct that tracks the last observed remote state. Updated on every received `Result` / `Question` / `AnswerCanceled` / heartbeat. Persisted to `delegation-handles.json` on every update.

## 5. Component changes per task

### 5.1 Task 1 — Wire-protocol + PersistentHandleStore

Files touched:
- `core/.../delegation/DelegationProtocol.kt` — add 4 new sealed variants
- `agent/.../delegation/DelegationHandle.kt` — add `lastSeenState: String` field (default `"unknown"`)
- New: `agent/.../delegation/PersistentHandleStore.kt` — atomic two-file JSON persistence
- `agent/.../delegation/DelegationOutboundService.kt` — wire the store; persist on `send()`, update on `lastSeenAt` ticks, remove on `close()`
- New: `agent/src/test/.../delegation/PersistentHandleStoreTest.kt` — round-trip + atomic-write test via `@TempDir`
- `core/src/test/.../delegation/DelegationProtocolTest.kt` — JSON round-trip for the 4 new variants

Acceptance: protocol compiles, JSON round-trips, persistent store reads back what was written, all existing tests still pass. Existing handles seamlessly carry `lastSeenState = "unknown"` until first state observation.

### 5.2 Task 2 — `continue_with` arg on `delegation_send`

Files touched:
- `agent/.../tools/delegation/DelegationSendTool.kt` — accept optional `handle` arg; on present, branch to the continuation path
- `agent/.../delegation/DelegationOutboundService.kt` — new `sendContinuation(handleId, request, delegatorSessionId, onResult)` method
- `agent/src/test/.../tools/delegation/DelegationSendToolContinueWithTest.kt` — covers picker skip + Accept skip + message-as-user-turn

`sendContinuation` flow:

1. Look up `handleId` in `activeChannels`. If absent → return `DelegationException.Expired("handle_not_found")`.
2. If the entry is live (real `SocketChannel`) → write a `UserTurn(bSessionId, request)` over it. IDE-B's inbound read-loop dispatches `UserTurn` into the existing session's steering queue (same code path as `AgentController.executeTaskInternal`'s typed-input intercept).
3. If the entry is dead (persisted-but-not-yet-resumed after IDE-A restart) → trigger the §5.3 `attemptResume` flow first. On `ChannelResumed`, proceed to step 2. On `SessionClosed` / `SessionNotFound` / probe failure, return `DelegationException.Expired` with the appropriate reason.

Wire choice: `UserTurn` is a new variant added in Task 1's protocol prelude (see §4.1). It's distinct from `Connect` because `Connect` is first-contact and carries delegator identity + the Accept handshake; `UserTurn` reuses an already-established channel.

Acceptance: `delegation_send(handle = "h-x", request = "follow up")` succeeds without showing the picker, without opening the Accept dialog. The text appears in IDE-B's session as a new user message and Agent-B continues iterating.

### 5.3 Task 3 — `CHANNEL_RESUME` re-attach flow

Files touched:
- `agent/.../delegation/DelegationOutboundService.kt` — on session resume, rehydrate persisted handles into `activeChannels` as **dead** entries (no live SocketChannel); first `delegation_*` referencing one triggers `attemptResume(handleId)`
- `agent/.../delegation/DelegationInboundService.kt` — handle `ChannelResume` in the read loop: look up session by ID, reply with one of the three outcomes
- `agent/.../AgentService.kt` — on session resume, invoke `outbound.loadPersistedHandles(sessionId)` to rehydrate. Hook site: the session-resume path that today rebuilds `ContextManager` and `MessageStateHandler` from disk (the writing-plans phase locates the exact line)
- `agent/src/test/.../delegation/ChannelResumeTest.kt` — simulate restart via fresh `DelegationOutboundService` from persisted state, drive all three reply paths

`attemptResume` flow:
1. `DelegationClient.ping(socketPath)` — if no PONG, return `DelegationExpired("ide_b_not_running")`.
2. Open a new `SocketChannel` to the socket, write `ChannelResume(bSessionId, lastSeenState)`.
3. Read the reply.
4. Dispatch as documented in §3.3.

Acceptance: after a simulated IDE-A restart with an in-flight `RUNNING` handle, calling `delegation_close(handle)` or `delegation_send(handle, ...)` causes the outbound service to re-probe + re-attach + perform the operation. `SessionClosed` and `SessionNotFound` paths return `DelegationExpired` with appropriate error payloads.

### 5.4 Task 4 — History-list badge + detail-view banner

Files touched:
- `agent/webview/src/components/history/HistoryView.tsx` (or equivalent) — render the badge when `historyItem.delegated != null`
- `agent/webview/src/components/history/SessionDetailView.tsx` (or equivalent) — render the top banner when the open session has `delegated != null`
- `agent/webview/src/types.ts` — type definitions for the `delegated` shape (mirror Plan 1's `DelegationMetadata`)
- `agent/.../ui/AgentController.kt` — confirm the existing `_loadSessionHistory` bridge already serializes `HistoryItem.delegated` (Plan 1 should have wired this; verify)
- React snapshot tests for both the badge and the banner

Acceptance: when IDE-B's chat panel opens an inbound delegation session in history, the row shows the "📨 delegated by {repo}" tag and the detail view shows the metadata banner.

### 5.5 Task 5 — IDE-B input banner

Files touched:
- `agent/webview/src/components/input/InputBar.tsx` — render the banner above the input field when a `delegationQuestionPending` flag is true for the active session
- `agent/webview/src/stores/chatStore.ts` — new field `delegationQuestionPending: boolean` synced from the Kotlin side via a new `_setDelegationQuestionPending` JCEF bridge
- `agent/.../ui/AgentController.kt` — push the pending-question state to the webview via a new `_setDelegationQuestionPending` bridge whenever `routeQuestion` runs / resolves. Source-of-truth is `DelegationInboundService.hasPendingQuestion(sessionId)`.
- `agent/.../delegation/DelegationInboundService.kt` — remove the Plan 2 F7 inline-nudge `enqueueNudgeForSession` call once the banner replaces it; update the existing Plan 2 tests accordingly
- React snapshot tests for banner visible / cleared states

Acceptance: when a delegated question is in-flight, the banner appears in IDE-B's input area. Typing an answer and submitting clears the banner. The remote-side answer also clears the banner via the existing AnswerCanceled path → new bridge call.

## 6. Cross-cutting concerns

### 6.1 Schema versioning

`PersistentHandleStore` writes a top-level `schemaVersion: 1` field. The loader:

- Reads the file.
- If `schemaVersion == 1`, parses normally.
- If `schemaVersion` is anything else, logs a warning and returns an empty list (no migrations in v1; future plans can layer them).

Forward-compat: tasks beyond Plan 4 that change the schema bump the version and add a migration.

### 6.2 Re-attach + cascade interactions

Plan 3's cascade-cancel (`AgentService.cancelCurrentTask` → `outbound.cancelAllForSession`) iterates the active handle map. After Plan 4, that map includes both live AND dead (post-restart, pre-resume) handles. `cancelAllForSession` must call `close()` on dead handles too — `close()` removes the persisted entry and is idempotent on already-closed handles. No code change in Plan 3's cascade path; the persistence layer transparently handles both.

### 6.3 Continue-with + plan-mode interaction

If Agent-B's session is in plan mode (Plan 0 added per-session plan mode), a `UserTurn` arriving via `continue_with` must respect plan mode just like a normal user turn. No special handling — the existing per-session plan-mode state covers it.

### 6.4 React state model

InputBar already juggles multiple "status pills" (steering-mode, plan-mode, model selection). The new banner is a separate `<DelegationQuestionBanner />` component, rendered as a sibling above the existing pill container. No state coupling needed.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Handle-store schema is new; future fields might force migration | `schemaVersion: 1` upfront; loader bails on unknown versions rather than corrupting state |
| `lastSeenState` is a hint, not authoritative | IDE-B's `currentState` in `ChannelResumed` is the source of truth; coalesced nudge surfaces any state diff to Agent-A |
| InputBar state model already juggles 3-4 badges | New banner is a separate component sibling, not a state merge |
| `UserTurn` introduces a fifth new wire message — Task 1 broader | Acceptable; alternative ("smuggle through Connect") would muddy the protocol |
| Manual smoke test in two IDEs still pending from Plan 3 | Recommend running it BEFORE Plan 4 ships, so we know the baseline works |

## 8. Test plan summary

- **Unit:** `PersistentHandleStoreTest` (atomic write + version handling), `DelegationProtocolTest` (5 new variant round-trips), `ContinueWithTest`, `ChannelResumeTest`, React component snapshot tests for badge + banner.
- **Integration:** Extend `DelegationE2ETest` with `continue_with` happy path + `CHANNEL_RESUME` simulated-restart path.
- **Manual:** Smoke test in two real IDEs per v1 §8 + Plan 3 §8 + the new Plan 4 behaviors:
  1. `delegation_send` then `delegation_send(handle = X, ...)` continuation → no picker re-shown.
  2. Open delegated session in IDE-B history → badge visible, banner visible in detail.
  3. Have Agent-B ask a question → input banner appears in IDE-B; type an answer and submit → banner clears.
  4. (Hard) Quit IDE-A while delegation is RUNNING in IDE-B; restart IDE-A; trigger `delegation_close` on the persisted handle → CHANNEL_RESUME flow surfaces correct outcome.

## 9. Acceptance criteria for Plan 4 as a whole

1. `./gradlew :core:test :agent:test --rerun` — 0 failures.
2. `./gradlew verifyPlugin` — passes (against the now-refreshed verification metadata).
3. All 4 behaviors observable in code, each task's acceptance criteria met.
4. Outbound handles persist across IDE-A restart; `CHANNEL_RESUME` re-attaches or expires cleanly.
5. React HistoryView shows badge for inbound delegated sessions; InputBar shows banner when a delegated question is pending.
6. No new public API in `:core` beyond the 5 protocol variants and the persistence file format.
7. Plan 2 review F7 inline-nudge code path removed (replaced by Task 5's banner).
8. Manual smoke test (deferred to author) passes.

## 10. Out of scope (repeated)

This plan does **not** touch: cross-machine, cross-user, multi-hop chains, per-delegation tool restrictions, IPC encryption, conversation-as-default delegation, non-plugin IDEs, web / Slack surfaces. Anything in v1 §2 stays a non-goal.

---

*This spec lives alongside [`2026-05-22-cross-ide-agent-delegation-design.md`](2026-05-22-cross-ide-agent-delegation-design.md) (parent v1) and [`2026-05-23-cross-ide-plan-3-robustness-design.md`](2026-05-23-cross-ide-plan-3-robustness-design.md) (just-shipped). When Plan 4 merges, the v1 spec is fully shipped.*
