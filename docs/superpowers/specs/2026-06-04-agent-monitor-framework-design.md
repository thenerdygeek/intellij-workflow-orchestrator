# Agent Monitor Framework — Design Spec

**Date:** 2026-06-04
**Module:** `:agent` (depends ONLY on `:core`)
**Status:** Approved design — pending implementation plan

---

## 1. Summary

Give the agent a **Monitor framework**: the ability to watch a long-running source (a shell
command, or the plugin's own domain state — Bamboo builds, Jira tickets, Pull Requests, Sonar) and
**proactively push events into the agent conversation** when something matches, rather than waiting
for the agent to passively glance at `environment_details` on its next turn.

The defining new capability is **filter-driven proactive push with idle-wake**: when a watched event
fires, it is injected at the next ReAct iteration boundary; if the agent loop has gone idle, the
event re-wakes the loop (within a strict budget) to process it.

This is modeled on Claude Code's `Monitor` tool, adapted to the plugin's architecture and extended
with domain-aware sources that leverage existing `:core` services and the `EventBus`.

---

## 2. Goals / Non-Goals

### Goals
- A reusable `MonitorSource` abstraction with shell + domain implementations.
- Proactive event delivery via the existing steering path; idle-loop re-wake with guardrails.
- Coalescing (debounce), per-monitor wake budget, and flood auto-stop.
- v1 domain coverage: **Bamboo (build/stage/job), Pull Requests (state/reviews/comments),
  Jira (ticket/sprint), Sonar (quality gate/issues)** + the generic shell monitor.
- Session-scoped lifecycle mirroring `BackgroundPool`; passive surfacing in `environment_details`
  for free by reusing the `BackgroundHandle` abstraction.

### Non-Goals (v1)
- **Docker tag / drift monitoring** — deferred; it is the only domain requiring NEW `:core`
  plumbing (a `DockerDriftService` interface + `DockerDriftDetected` event + registry-query logic).
- Mid-tool event injection — impossible by design; steering drains only at iteration boundaries.
- Cross-session / persistent-across-restart monitors — monitors die on new chat (session-scoped).
- A bespoke notifications UI — reuse the existing background-process snapshot UI path.

---

## 3. Architecture

New package: `agent/src/main/kotlin/com/workflow/orchestrator/agent/monitor/`.

Three units, each with one clear responsibility:

### 3.1 `MonitorSource` — produces events
Abstract base. Each implementation detects changes for one domain and emits a normalized event:

```
data class MonitorEvent(
    val monitorId: String,
    val severity: Severity,        // INFO | NOTABLE | ALERT  (drives wake eligibility)
    val line: String,              // human-readable one-liner shown in the conversation
    val payload: Any? = null       // optional structured detail
)
```

Source families:
- **`PollingSource`** — wraps a `:core` `SmartPoller`, calls a `:core` service interface, diffs a
  state field against the previous snapshot, emits `MonitorEvent` on change. The poll action returns
  `true` when state changed (SmartPoller's backoff contract).
- **`EventBusSource`** — subscribes to `EventBus.flow` (`SharedFlow<WorkflowEvent>`), filters the
  relevant `WorkflowEvent` subtypes, maps to `MonitorEvent`.
  **Initial hydration (required):** `EventBus` is `replay=0`, so any event that fired *before*
  subscription is lost. On start, every `EventBusSource` performs a one-shot state fetch via the
  corresponding `:core` service to seed its snapshot, and emits a synthetic `MonitorEvent` if the
  resource is already in a terminal/notable state. This is critical for events that carry no
  pollable state stream — e.g. `pull_request` calls `BitbucketService.getPullRequestDetail(prId)`
  on start, so an already-MERGED PR is reported immediately rather than silently missed.
- **`ShellCommandSource`** — the generic Monitor. Spawns a process via the existing background-process
  machinery and applies a user-supplied regex `filter` to each stdout line; a match → `MonitorEvent`.

### 3.2 `MonitorManager` + `MonitorPool` — session-scoped coordinator
A dedicated `MonitorPool` (separate from `BackgroundPool`, structured the same way: project service,
keyed by `sessionId`, `killAll(sessionId)` on new chat) holds the monitor handles. A separate pool
is deliberate: registering monitors in `BackgroundPool` would consume its
`concurrentBackgroundProcessesPerSession` cap (shell processes and monitors would starve each other)
and pollute `background_process(action=list)`. `MonitorManager` is the coordinator over the pool and
is responsible for:
- Register / list / stop monitors; enforce its **own** `maxConcurrentMonitorsPerSession` cap.
- **Coalesce** events within a debounce window (default ~2s) into a single notification.
- Maintain the **wake-budget ledger** per monitor (pre-filter before the global wake guard — see §4).
- **Flood auto-stop** when a monitor exceeds an events/min threshold.
- Bridge events to the loop (see §4).

### 3.3 `MonitorHandle : BackgroundHandle` — reuse the existing interface
A monitor is a background unit, so `MonitorHandle` implements the existing `BackgroundHandle`
interface for `state()`, `readOutput(sinceOffset, tailLines)`, `onComplete()`, and `kill()` — but is
held in `MonitorPool`, not `BackgroundPool`. Consequences:
- `EnvironmentDetailsBuilder` gets a small new `appendActiveMonitors` section (~3–5 lines) that
  iterates `MonitorPool.list(sessionId)` and shows each monitor's delta output, exactly like the
  existing `appendRunningProcesses`. (Correction from an earlier draft: surfacing is NOT free —
  it needs this small addition, because the existing section reads `BackgroundPool`, not `MonitorPool`.)
- `kill()` on a `MonitorHandle` **cancels the underlying coroutine scope** (SmartPoller job /
  EventBus subscription / shell process) and unregisters from `MonitorPool`. For non-shell sources
  there is no OS process — "kill" is job cancellation, not a SIGTERM/SIGKILL sequence.
- The new proactive-push layer (§4) is the only net-new delivery path.

---

## 4. Event delivery & guardrails

The plugin **already has** an idle-wake mechanism that monitor delivery must route through rather
than reinvent: `AgentService.autoWakeIdleSession(sessionId, syntheticText, source)` →
`IdleSessionWaker` → `autoWakeListener` (wired by `AgentController`, drives a `resumeSession`),
gated by the shared `AutoWakeGuardState` (global per-session cap + cooldown) and the
`safeToResume` check (`active == null || active == sessionId`). It returns an `IdleWakeRoute`:
`WAKE` / `SKIP_GUARD` (global cap/cooldown hit) / `DEFER_ACTIVE_SESSION` (a *different* session is
running). This same path is used today by background-process completions and cross-IDE delegation.

`MonitorManager` is injected with a route callback `(sessionId, text) -> IdleWakeRoute` at
construction (it does **not** depend on `AgentService` directly). Flow when a `MonitorSource` emits:

1. Event enters `MonitorManager`; the **coalesce window** batches a burst into one notification.
2. **Determine loop liveness atomically** via the injected `activeLoopForSession(sessionId)`
   reference (same check `onBackgroundCompletion` uses) — NOT a stale boolean. This closes the race
   where an event fires exactly as the loop exits: `enqueueSteeringMessage` on an already-exited
   `AgentLoop` instance would `offer()` successfully but never drain (silently lost).
3. **If the loop is live** → `AgentLoop.enqueueSteeringMessage(formatted_event)`. Delivered at the
   next iteration boundary (never mid-tool), identical to user steering today.
4. **If the loop is idle / exited** → first consult the monitor's **wake budget** (default 3) as a
   pre-filter; if budget remains, persist the synthetic message first (mirroring
   `onBackgroundCompletion`'s persist-before-wake ordering) then call the injected route:
   - `WAKE` → decrement the monitor's wake budget (decrement **only** on `WAKE`).
   - `SKIP_GUARD` (global guard hit) or `DEFER_ACTIVE_SESSION` (another session active) → do **not**
     decrement; hold the event for passive surfacing in `environment_details` at the next poll cycle.
   When the per-monitor budget is exhausted, the monitor goes **dormant**: events still surface
   passively in `environment_details`, but it spends no more wakes. The per-monitor budget and the
   global `AutoWakeGuardState` are two layers — the per-monitor pre-filter prevents one chatty
   monitor from exhausting the global guard that background-process completions also depend on.
5. **Flood auto-stop**: a monitor exceeding the events/min threshold is stopped, emitting a final
   "monitor auto-stopped (flood)" notice.

Severity gating: only `NOTABLE`/`ALERT` events are wake-eligible; `INFO` events coalesce into the
next delivery but never spend wake budget on their own.

Network awareness: `PollingSource` inherits `SmartPoller`'s offline-pause/online-resume for free
(SmartPoller already integrates `NetworkStateService`).

```
Defaults (tunable; settings-backed):
  coalesceWindowMs = 2000
  wakeBudgetPerMonitor = 3
  floodThresholdEventsPerMin = 20
  maxConcurrentMonitorsPerSession = 5
```

---

## 5. Tool surface

A single dispatcher tool `monitor` (deferred-loaded to save schema tokens), styled after the
existing `BackgroundProcessTool` dispatcher. `action` discriminator:

### `action: "start"` — `source` discriminated union
| `source` | params |
|---|---|
| `shell` | `command`, `filter` (regex), `description` |
| `bamboo` | `planKey` + `branch?` (resolved to a `chainKey` internally via `getRecentBuilds`/branch lookup; `getLatestBuild` needs the chain key, which the LLM rarely knows), `level: build\|stage\|job`, `stageName?`, `jobName?` |
| `jira_ticket` | `ticketKey` |
| `jira_sprint` | `boardId` \| `sprintId` |
| `pull_request` | `prId`, `aspects: [state, reviews, comments]` |
| `sonar_gate` | `projectKey`, `branch?` |
| `sonar_issues` | `projectKey`, `branch?`, `minSeverity?` |

Common to all: `lifecycle: until_exit \| persistent \| timeout`, `timeout_ms?`, `description`.
Returns a `monitorId`.

### `action: "list"`
Returns active monitors for the session (id, source, description, state, wake-budget remaining).

### `action: "stop"`
`{ monitorId }` → stops and unregisters the monitor.

---

## 6. Per-domain mapping (v1)

All v1 domains are "cheap" — they reuse existing `:core` services / `EventBus` events. The agent
never touches feature-module clients directly (respects `:agent → :core`).

| Source | Mechanism | State field(s) diffed | New `:core` work |
|---|---|---|---|
| Bamboo build | poll `BambooService.getLatestBuild` | `state`, `lifeCycleState` | none |
| Bamboo stage | same poll | `stages[].state` | none |
| Bamboo job | same poll | `stages[].jobs[].state` (+ `resultKey`) | none |
| Jira ticket | poll `JiraService.getTicket` | `status`, `assignee` | optional `TicketStatusChanged` event |
| Jira sprint | **two-step**: resolve active sprint, then poll its issues | per-issue `status` membership | optional `getActiveSprint(boardId)` helper |
| PR state | `PullRequest{Merged,Declined,Approved}` events + start-hydration | `state` (OPEN/MERGED/DECLINED) | none |
| PR reviews | poll `BitbucketService.getPullRequestParticipants` | per-participant `state` | none |
| PR comments | `PrCommentsUpdated` event (`total`, `unreadCount`) + poll `getBlockerCommentsCount` for blockers | `unreadCount`; blocker count via separate poll | none |
| Sonar gate | `QualityGateResult` event (`passed: Boolean`, mapped to OK/ERROR in `MonitorEvent.line`) + poll fallback | `passed` | none |
| Sonar issues | poll `SonarService.getIssues` | issue count / per-issue `status` | none |

Corrections baked into the rows above (from spec review against the real types):
- `WorkflowEvent.QualityGateResult` exposes only `passed: Boolean` (no `status` string) — the OK/ERROR
  rendering happens in `MonitorEvent.line`. The polling fallback `SonarService.getQualityGateStatus`
  *does* return a `status` string.
- `WorkflowEvent.PrCommentsUpdated` carries `total`/`unreadCount` but **no blocker count**; the
  "new blocker comment" aspect requires a separate `BitbucketService.getBlockerCommentsCount(prId)` poll.
- `jira_sprint` with a `boardId` must resolve the active sprint each cycle
  (`getAvailableSprints(boardId)` → `state == "active"`) before polling `getSprintIssues(sprintId)`;
  a one-line `getActiveSprint(boardId)` helper on `JiraService` is the cleaner option.

Reference files: `core/services/{BambooService,JiraService,SonarService,BitbucketService}.kt`,
`core/events/{EventBus,WorkflowEvent}.kt`, `core/polling/SmartPoller.kt`,
`core/network/NetworkStateService.kt`, `bamboo/.../service/BuildMonitorService.kt` (existing
SmartPoller usage example).

---

## 7. Persistence, lifecycle & UI

- **Session-scoped:** `MonitorManager.killAll(sessionId)` on new chat (same path as `BackgroundPool`).
- **Lifecycle modes:** `persistent` monitors survive across loop runs within the session;
  `until_exit` ends when its source completes; `timeout` self-bounds.
- **Resume:** active monitors persist to session state and are re-armed on session load, so they are
  described accurately on `[TASK RESUMPTION]`.
- **UI:** monitor start/stop and each pushed event surface as a lightweight "monitor" message in the
  JCEF chat via the existing `BackgroundChanged` snapshot path — visible even when the agent is idle.

---

## 8. Testing strategy (TDD)

- `MonitorManager`: coalesce window, wake-budget decrement→dormant, flood auto-stop, concurrency cap
  — pure logic, no IDE, fully unit-testable.
- Each `PollingSource`: inject a fake `*Service` returning canned `ToolResult<T>` sequences; assert
  events fire only on the intended state-field change and not on no-op polls.
- `EventBusSource`: inject a flow of `WorkflowEvent`s; assert correct filtering/mapping.
- `ShellCommandSource`: feed canned stdout lines; assert regex filter + line→event mapping.
- `AgentLoop` integration: assert events drain at iteration boundaries (not mid-tool); that idle-wake
  respects the per-monitor budget and goes dormant when exhausted; that `SKIP_GUARD`/
  `DEFER_ACTIVE_SESSION` routes do **not** decrement budget; and the exit race — an event emitted as
  the loop exits is routed to idle-wake (persisted), not silently `offer()`ed to a dead queue.
- `EventBusSource` late-subscriber: a qualifying `WorkflowEvent` fired *before* the monitor starts is
  missed by the subscription but recovered by start-hydration (assert the synthetic initial event).
- Build-cache caution: if any suspend-lambda signatures change, run `:agent` tests with
  `--no-build-cache --rerun-tasks` (documented project trap).

---

## 9. Phasing (within v1)

1. **Framework**: `MonitorSource` / `MonitorManager` / `MonitorHandle` + delivery & guardrails +
   `ShellCommandSource` + the `monitor` dispatcher tool.
2. **Bamboo** build/stage/job (`PollingSource` against `BambooService`).
3. **Pull Requests** (state/reviews/comments — mix of `EventBusSource` + `PollingSource`).
4. **Jira** ticket/sprint (`PollingSource` + optional new fine-grained events).
5. **Sonar** quality gate/issues (`EventBusSource` for gate + `PollingSource` for issues).
6. **UI surfacing + resume** wiring.

Each phase is independently testable and shippable.

---

## 10. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Idle-wake runaway / cost | Per-monitor wake budget → dormant; flood auto-stop; only NOTABLE/ALERT wake. |
| Poll storms against Jira/Bamboo/Sonar | SmartPoller backoff (1.5×, cap 300s, 4× when unfocused) + network gating. |
| Monitors leaking across sessions | Session-scoped registry; `killAll` on new chat (proven `BackgroundPool` pattern). |
| Schema bloat from many domains | One dispatcher tool, deferred-loaded; domains are sub-schemas, not separate tools. |
| "Silence ≠ success" for shell source | Tool docs instruct failure-inclusive filters (carry over Monitor's coverage discipline). |
| `:agent → :core` violation | All domain access goes through existing `:core` service interfaces; no feature-module calls. |
| Monitors outliving their loop (max-iter / cancel / FAILED) | `MonitorManager` subscribes to loop-outcome callbacks: on `MaxIterationsReached`/`Cancelled`/`Failed`, monitors keep running but are marked dormant (no wakes) until a new loop is resumed or `killAll` fires on new chat. Persisted idle-wake messages replay correctly on `resumeSession`. |
| Global wake guard starvation | Per-monitor wake budget is a pre-filter; budget is spent only on a `WAKE` route, so chatty monitors can't drain the shared `AutoWakeGuardState` that background-process completions rely on. |
| Idle-wake dropped for a backgrounded session | When a *different* session is active, `IdleSessionWaker` returns `DEFER_ACTIVE_SESSION`; the event is held for passive `environment_details` surfacing rather than lost. |
```
