# Plan 3 — Cross-IDE Delegation Robustness Pass

**Status:** Draft for review · **Date:** 2026-05-23 · **Branch:** `feature/cross-ide-delegation` · **Worktree:** `.worktrees/cross-ide/`

**Parent spec:** [`2026-05-22-cross-ide-agent-delegation-design.md`](2026-05-22-cross-ide-agent-delegation-design.md) — the canonical v1 design. This document does not re-derive that spec; it specifies *which* of the v1 behaviors land in Plan 3 and *how* the work is organized.

**Predecessor plans:** Plans 0+1+2 shipped on this branch (see [`2026-05-23-cross-ide-handoff-status.md`](2026-05-23-cross-ide-handoff-status.md)). Plan 3 finishes the v1 spec by implementing the six behaviors deferred from Plan 1's MVP cut, plus folds in the seven LOW-severity findings from the Plans 1+2 code reviews.

---

## 1. Goals

Implement the six v1-spec behaviors that didn't land in the MVP, and fold in the LOW-severity review findings as a cleanup batch at the end. After Plan 3 merges, the v1 spec is fully shipped except for the explicitly-deferred Plan 4 items below.

## 2. Scope

### 2.1 In

| # | v1 spec ref | Behavior | Plan 3 task |
|---|---|---|---|
| 1 | §6.4 row 6 | Project-window-close → `FAILED { reason: "project_closed" }` | Task 2 |
| 2 | §6.4 row 4 | Cascade-cancel from parent session cancel | Task 3 |
| 3 | §6.1, §6.4 row 7 | Idle timeout with B-side heartbeat | Task 4 |
| 4 | §5.1 | Socket-glob cross-installation discovery | Task 5 |
| 5 | §5.4 | Auto-launch Closed picker targets | Task 6 |
| 6 | §4.4 | `delegation_fetch_transcript` tool | Task 7 |
| 7 | (handoff §7) | LOW review findings batch | Task 8 |

### 2.2 Out (deferred to Plan 4)

- `continue_with` argument on `delegation_send`
- `CHANNEL_RESUME` re-association protocol (v1 spec §9.3)
- History-list UI affordance for delegated sessions in IDE-B's chat panel
- IDE-B input-banner UX while a question is pending (Plan 2 review F7 was MVP-fixed with an inline nudge; full UX is Plan 4)

### 2.3 Out (per v1 spec §2 non-goals)

Cross-machine, cross-user, multi-hop, per-delegation tool restrictions, IPC encryption, conversation-as-default, non-plugin IDEs, web/Slack surfaces. Unchanged from v1.

## 3. Design decisions (resolved during brainstorming)

These four answers shaped the plan:

### 3.1 Transcript size handling

`delegation_fetch_transcript` writes the full transcript JSON to a file under the delegated session's storage dir (`agent/sessions/{id}/transcript-export.json` on IDE-B; the path is returned to IDE-A as a string for use with `read_file`). The tool result inlines the first ~2K characters of the transcript plus a token estimate. Matches the existing `ToolOutputSpiller` pattern for >30K tool outputs. Agent-A pages in only what it needs via `read_file`.

**Why not paginate by turn index:** the existing spill pattern is already in use throughout `:agent`; a second mechanism for the same problem adds surface area without payoff.

### 3.2 Idle timeout activity model

Agent-B sends a `Heartbeat` IPC message every 60 seconds while the session is in any non-terminal state (`AWAITING_ACCEPT`, `RUNNING`, `AWAITING_ANSWER`). Agent-A resets the per-channel `lastSeenAt` on *any* received message, heartbeat included. The 60s cadence is chosen so a 30-min default timeout has ~30 missed heartbeats before tripping — comfortable margin for transient socket pauses.

This deliberately *decouples* "B is alive" from "B is making LLM progress." A 35-minute `run_command` does not trip the timeout because the heartbeat scheduler runs independently of the LLM loop. If B's process is alive but its scheduler isn't sending (a bug), the timeout will catch it; that's correct behavior.

**Why B-side heartbeat rather than A-side pull-status:** push semantics use one message per interval rather than two (request + reply); B already knows its own liveness, so the push is direct evidence rather than indirect inference.

### 3.3 Idle timeout configuration

`PluginSettings.delegationIdleTimeoutMinutes: Int = 30` (per-project). Settings UI grows by one numeric field on the Cross-IDE Delegation page. Value `0` disables the timeout. The setting is read at idle-check time, so toggling takes effect immediately for new checks.

### 3.4 Plan shape

Item-by-item with a shared wire-protocol prelude as Task 1. Matches how Plans 1+2 shipped: protocol locked in first, then services, then tools, then UI. Reduces mid-plan protocol churn and keeps each task focused on one observable behavior.

## 4. Protocol additions

Task 1 lands the wire format. All additions extend the existing sealed `DelegationMessage` hierarchy in `core/delegation/DelegationProtocol.kt`; framing (4-byte length prefix + JSON, 10 MiB cap) is unchanged.

### 4.1 New message variants

| Variant | Direction | Carries |
|---|---|---|
| `Heartbeat(sessionId: String)` | B → A | Liveness signal. Emitted every 60s while session is non-terminal. |
| `FetchTranscript(sessionId: String, requestId: String)` | A → B | Request the on-disk transcript for a session. `requestId` is a UUID echoed back in the reply. |
| `FetchTranscriptReply(requestId: String, status: String, transcriptPath: String?, error: String?)` | B → A | `status ∈ {"ok", "not_found", "expired"}`. `transcriptPath` populated when `ok`; absolute path on IDE-B's filesystem. `error` populated when not `ok`. |

`status` rationale:
- `ok` — session exists, transcript written, path returned.
- `not_found` — session ID isn't in IDE-B's `sessions.json` index. Likely pruned.
- `expired` — session exists but the delegation has reached a terminal state and the transcript was already cleaned up (future-proofing for a TTL on exports; v1 keeps exports as long as the session itself).

### 4.2 SessionChannel state

`SessionChannel.lastSeenAt: Long` (epoch millis). Initialized at `Connect` accept; updated on every received IPC message. Idle timer reads this to decide whether to fire `IdleTimedOut`.

### 4.3 New exception variant

`DelegationException.IdleTimedOut(handle: DelegationHandle, lastSeenAt: Long)`. Raised by `DelegationOutboundService` when the idle timer fires. Surfaces to Agent-A's LLM via the existing `DelegationExpired { reason: "idle_timeout" }` error per v1 spec §10 — no new public error kind needed.

## 5. Component changes per task

### 5.1 Task 1 — wire-protocol additions

Files touched:
- `core/src/main/kotlin/.../delegation/DelegationProtocol.kt` — add 3 sealed variants
- `core/src/main/kotlin/.../delegation/DelegationFraming.kt` — no change (already type-agnostic)
- `agent/src/main/kotlin/.../delegation/DelegationException.kt` — add `IdleTimedOut`
- `agent/src/main/kotlin/.../delegation/SessionChannel.kt` — add `lastSeenAt` field (volatile or atomic)

Tests:
- `DelegationProtocolTest` — JSON round-trip assertions for all 3 new variants.
- `DelegationFramingTest` — sanity check that new variants frame/unframe cleanly.

Acceptance: protocol compiles, round-trips, all existing tests still pass. No behavioral change yet.

### 5.2 Task 2 — project-window-close listener

Files touched:
- `agent/src/main/kotlin/.../delegation/DelegationInboundService.kt` — implement `Disposable` cleanly already; add an explicit `ProjectManagerListener.projectClosing` registration in `DelegationInboundStartupActivity` (or directly in the service) that closes all active inbound channels with `Result(status="failed", reason="project_closed")` before the underlying socket dies.

Tests:
- `DelegationInboundServiceTest` — simulate `projectClosing`, assert all active channels received a `Result` write before the service stopped.

Acceptance: closing IDE-B's project window (without killing the IDE process) reports `FAILED { reason: "project_closed" }` to IDE-A. Existing process-death path still works as before.

### 5.3 Task 3 — cascade-cancel from parent session cancel

Files touched:
- `agent/src/main/kotlin/.../AgentService.kt` — when a session is canceled, look up active outbound delegation handles owned by that session (already tracked in `handleToSessionId` on `DelegationOutboundService`); for each, dispatch a close.
- `agent/src/main/kotlin/.../delegation/DelegationOutboundService.kt` — add a `cancelAllForSession(sessionId: String, reason: String)` method.

Tests:
- `DelegationOutboundServiceTest` — register N handles for a session ID, call `cancelAllForSession`, assert all channels sent close + were removed from registries.
- `AgentServiceCascadeCancelTest` — full session cancel triggers `cancelAllForSession`.

Acceptance: canceling Agent-A's session in IDE-A propagates close to every open child channel; each IDE-B session reports `CANCELED { reason: "parent_canceled" }`.

### 5.4 Task 4 — idle timeout with B-side heartbeat

Files touched:
- `core/src/main/kotlin/.../settings/PluginSettings.kt` — add `delegationIdleTimeoutMinutes: Int = 30`.
- `core/src/main/kotlin/.../settings/CrossIdeDelegationConfigurable.kt` — add numeric `JBIntSpinner` with min=0, max=720 (12 hours), tooltip "0 disables idle timeout."
- `agent/src/main/kotlin/.../delegation/DelegationInboundService.kt` — per-session `HeartbeatScheduler` (coroutine that loops `delay(60_000)` + writes `Heartbeat` while session is non-terminal). Cancel on session terminal transition.
- `agent/src/main/kotlin/.../delegation/DelegationOutboundService.kt` — per-channel `IdleTimer` (coroutine that polls `lastSeenAt` every 30s; fires `IdleTimedOut` if `now - lastSeenAt > timeoutMs`). Updates `lastSeenAt` in the message reader loop.
- `agent/src/main/kotlin/.../delegation/Clock.kt` (new) — `interface Clock { fun nowMillis(): Long }` + default `SystemClock` impl. Injected into schedulers so tests can fake-time.

Tests:
- `HeartbeatSchedulerTest` — fake clock, advance 60s, assert one Heartbeat written; advance to terminal, assert scheduler stopped.
- `IdleTimerTest` — fake clock, no message arrivals, advance past timeout, assert `IdleTimedOut` raised.
- `IdleTimerTest` — message arrives, clock advances, no timeout (resets correctly).
- `PluginSettings` round-trip test for the new field.

Acceptance: with timeout = 5 min and B not sending heartbeats, A trips `IdleTimedOut` within 5 min + 30s. With heartbeats flowing, no timeout for long-running sessions. With timeout = 0, no idle check runs.

### 5.5 Task 5 — socket-glob cross-installation discovery

Files touched:
- `agent/src/main/kotlin/.../delegation/ui/DelegationPicker.kt` — augment recents loading with a glob of `~/.workflow-orchestrator/ipc/*.sock`; for each, `PING` and on `PONG` parse the project path from the reply.
- `core/src/main/kotlin/.../delegation/DelegationProtocol.kt` — no change here; existing `Pong(projectPath: String)` (verified at `core/.../DelegationProtocol.kt:32`) already carries what we need.
- Picker UI: new section header "Discovered (not in recents)" rendered when any discovered-only entries exist.

Tests:
- `DelegationPickerSocketGlobTest` — `@TempDir`-based; create fake socket files; mock `DelegationClient.ping`; assert discovered entries appear under the new section and dedupe against recents.

Acceptance: with an IDE-B that has *Accept incoming delegations* on but the project not in IDE-A's recents, the picker shows it under "Discovered (not in recents)" and Delegate works.

### 5.6 Task 6 — auto-launch with Toolbox detection

Files touched:
- `agent/src/main/kotlin/.../delegation/LauncherResolver.kt` (new) — interface + default impl that resolves the IntelliJ launcher binary from `PathManager.getHomePath()` + platform suffix (`bin/idea.sh` mac/linux, `bin/idea64.exe` Windows). Detects Toolbox layout (`.../Toolbox/apps/.../ch-0/...` in home path).
- `agent/src/main/kotlin/.../delegation/ProcessSpawner.kt` (new) — interface + default impl wrapping `ProcessBuilder`. Allows mock-based testing.
- `agent/src/main/kotlin/.../delegation/ToolboxFlavorReader.kt` (new) — reads the target project's IntelliJ workspace metadata to detect last-used IDE flavor/version. Returns `null` when unknown.
- `agent/src/main/kotlin/.../delegation/ui/DelegationPicker.kt` — wire **Launch & Delegate** button for Closed rows: show Toolbox mismatch confirm dialog if needed; spawn process; poll deterministic socket every 500ms for up to 90s with progress UI; on success auto-progress to delegation; on timeout/failure fall through to manual flow with inline reason.

Tests:
- `LauncherResolverTest` — Toolbox path detection (with/without `Toolbox/apps/...` segments); platform suffix resolution.
- `ToolboxFlavorReaderTest` — read fixture workspace metadata files; assert correct flavor extraction; unknown returns null.
- `AutoLaunchPollerTest` — mocked `DelegationClient.ping`; advance fake clock; assert exit on first success and exit on 90s timeout.
- No live subprocess in tests; manual smoke covers the real spawn.

Acceptance:
- Closed picker row + click Launch & Delegate → IntelliJ process spawns at the project path; socket comes up green within 90s → delegation proceeds.
- Toolbox detected + flavor mismatch detected → confirm dialog shown before spawn; user can cancel into manual path.
- Toolbox detected + flavor unknown → softer banner inside the picker; user can cancel into manual path.
- Spawn failure or 90s timeout → fall through to manual flow with inline reason; **Retry probe** button enables once the user opens the project manually.

### 5.7 Task 7 — `delegation_fetch_transcript` tool + spill writer

Files touched:
- `agent/src/main/kotlin/.../tools/delegation/DelegationFetchTranscriptTool.kt` (new) — implements the tool. Takes `handle` arg. Sends `FetchTranscript` on the channel; awaits `FetchTranscriptReply`; on `ok`, returns `transcriptPath`, head, token estimate; on `not_found`/`expired`, returns `DelegationExpired` with reason.
- `agent/src/main/kotlin/.../delegation/DelegationInboundService.kt` — handle inbound `FetchTranscript`: look up session by ID; serialize the session's `api_conversation_history.json` (already on disk) to a sidecar `transcript-export.json` under the same session dir; reply with absolute path.
- `agent/src/main/kotlin/.../AgentService.kt` — register the new tool in `reregisterCrossIdeDelegationTools` (gated by outbound setting).
- `agent/src/main/kotlin/.../delegation/DelegationOutboundService.kt` — extend message reader to dispatch `FetchTranscriptReply` to the awaiting request (correlate by `requestId`).

Tests:
- `DelegationFetchTranscriptToolTest` — register fake outbound channel; tool sends `FetchTranscript`; pretend reply arrives; assert tool result includes path + head + token estimate.
- `DelegationInboundServiceTest` — receive `FetchTranscript` for a known session ID; assert sidecar file written and reply contains correct path.
- `DelegationInboundServiceTest` — receive `FetchTranscript` for an unknown session ID; assert reply has `status="not_found"`.

Acceptance: Agent-A calls `delegation_fetch_transcript` on a live or recently-closed handle → tool returns a path + head; Agent-A `read_file`s the path to inspect detail. Pruned sessions return `DelegationExpired`.

### 5.8 Task 8 — LOW review findings batch

Cleanup commit at the end. From handoff §7:

| # | Severity | Item | Action |
|---|---|---|---|
| Plan 1 F7 | LOW | Double-bind race in `DelegationInboundService.start()` | Add `@Synchronized` to `start()`. |
| Plan 1 F8 | LOW | Missing `finally { closeChannel() }` on some exception paths in `startDelegatedSession` | Wrap the relevant `cs.launch` body in try/finally. |
| Plan 1 F9 | LOW | Commit message claims `TIMED_OUT` handling | Skip (git history, per handoff). |
| Plan 1 F10 | LOW | `DelegationException.Expired` defined but never thrown (Plan 4 scaffolding) | Add `// TODO Plan 4` comment. |
| Plan 2 F8 | LOW | Outbound reader keeps FD open under N unknown messages | Counter + break after 16 unknown messages. |
| Plan 2 F9 | LOW | `DelegationOutboundService.close()` non-atomic map removals | Acquire mutex around both removals. |
| Plan 2 F10 | LOW | `delegation_answer` returns same error for "handle not in map" vs "write failed" | Two distinct error kinds (`DelegationHandleNotFound`, `DelegationWriteFailed`). |

Tests:
- `DelegationInboundServiceConcurrencyTest` — invoke `start()` from N threads, assert socket bound exactly once.
- `DelegationOutboundReaderTest` — inject 17 unknown messages, assert reader exits the loop after the 16th.
- `DelegationOutboundServiceTest` — assert close() removes from both maps atomically (no observable mid-state).
- `DelegationAnswerToolTest` — covers both error kinds.

Acceptance: all 7 LOW items addressed (six fixed, F9 documented as skip).

## 6. Cross-cutting concerns

### 6.1 Cancellation ordering

The heartbeat scheduler and idle timer both run as coroutines. The cancel rule:

1. When a session transitions to a terminal state, the *scheduler's parent scope* is cancelled before the terminal `Result` message is enqueued. This prevents a heartbeat from racing the terminal message and confusing the receiver.
2. The idle timer on IDE-A side cancels when the channel transitions to terminal (any terminal state from any source).
3. Cascade-cancel sends `Result{ status="canceled" }` to each child; the inbound service's terminal-transition handler stops its heartbeat scheduler in turn.

### 6.2 Settings interactions

- Idle timeout = 0 disables the IDE-A side timer entirely. The B-side heartbeat scheduler still runs (it's cheap; gating it on a remote-side setting requires Connect-time exchange we don't have).
- Outbound disabled while a delegation is active: existing behavior (tools fail with `DelegationOutboundDisabled`) is preserved.

### 6.3 Test infrastructure additions

- `Clock` interface lets us write fake-time tests for both schedulers — no `Thread.sleep` in tests.
- `LauncherResolver` + `ProcessSpawner` + `ToolboxFlavorReader` are interfaces with default impls so auto-launch is mock-testable without spawning live processes.
- All new files follow existing patterns; no new test infrastructure beyond these three interfaces.

### 6.4 Build/cache traps

If any task changes a lambda's `suspend` modifier (likely on the heartbeat scheduler or idle timer construction sites), follow the rebase guidance in the project `CLAUDE.md` — use `--no-build-cache` on the affected gradle invocation.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Auto-launch Toolbox detection has filesystem dependencies hard to mock | Extract `ToolboxFlavorReader` interface; tests use fixture files under `@TempDir`. |
| Heartbeat + idle timer interact with terminal-transition ordering | Document the ordering rule in §6.1; cover with a unit test that triggers terminal mid-heartbeat-tick. |
| Auto-launch spawning a real `idea.sh` in tests would be flaky/slow | Mock `ProcessSpawner`; live spawn only in the manual smoke test. |
| `FetchTranscript` for a very large session blocks the inbound reader while serializing | Serialize on `Dispatchers.IO`; reply is sent after the file write completes. Large sessions are bounded by disk write speed, not parsed in memory beyond what `MessageStateHandler` already does. |
| Plan 4 work (`continue_with`, `CHANNEL_RESUME`) may need to revisit Task 1's protocol additions | Acceptable — protocol is meant to evolve; `Heartbeat`/`FetchTranscript` are independent of `CHANNEL_RESUME`. |

## 8. Test plan summary

- **Unit:** all 8 tasks add focused unit tests. Existing 3921 `:agent` tests + ~1063 `:core` tests continue to pass.
- **Integration:** none beyond unit + the existing `AgentLoopExitDrainTest`-style tests.
- **Manual smoke:** deferred per session direction — author runs the full smoke after Plan 3 lands, following v1-spec §8 plus the new behaviors (project-window-close, cascade-cancel, idle timeout, socket-glob discovery row, auto-launch, fetch_transcript tool).

## 9. Acceptance criteria for Plan 3 as a whole

1. `./gradlew :core:test :agent:test --rerun` — 0 failures.
2. `./gradlew verifyPlugin` — passes.
3. All 6 v1-spec behaviors observable in code (each task's acceptance criteria met).
4. All 7 LOW review findings addressed (6 fixed, F9 documented).
5. Settings UI has the one new numeric field.
6. No new public API in `:core` beyond the 3 protocol variants + 1 settings field.
7. Manual smoke test (deferred to author) passes.

## 10. Out of scope (repeated)

This plan does **not** touch: `continue_with`, `CHANNEL_RESUME`, IDE-B history-list affordance, IDE-B input-banner UX. Those are Plan 4. Anything in v1-spec §2 non-goals remains a non-goal.

---

*This spec lives alongside [`2026-05-22-cross-ide-agent-delegation-design.md`](2026-05-22-cross-ide-agent-delegation-design.md) and [`2026-05-23-cross-ide-handoff-status.md`](2026-05-23-cross-ide-handoff-status.md). When Plan 3 merges, this doc can be archived or referenced from the next handoff snapshot.*
