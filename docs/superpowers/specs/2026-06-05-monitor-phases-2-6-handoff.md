# Monitor Framework — Phases 2–6 Continuous Execution Handoff

**Written:** 2026-06-05 · **For:** the next session (user is asleep — run continuously, do not wait for check-ins).

---

## 0. Mission & operating contract

Implement **Phases 2–6** of the agent Monitor framework, end to end, **without stopping for approval between tasks or phases**. The user explicitly asked for uninterrupted completion overnight.

**Decisions already made by the user (do NOT re-ask):**
1. **Branch:** continue on the current branch `perf/token-context-optimization` (no worktree, no new branch).
2. **Release:** **ONE release at the very end** — after ALL phases are green + `verifyPlugin` passes. Bump patch → `clean buildPlugin` → push → `gh release create`. (Next version: `0.86.0-token-ctx.7`.)
3. **Scope:** Phases 2→3→4→5→6 below. **Defer Docker drift** (it alone needs new `:core` plumbing).
4. **Review rigor:** per-task **Sonnet** code review + fix loop, AND a per-phase whole-phase Sonnet review. (Same as Phase 1.)

**When you MAY stop and ask the user:** only if genuinely blocked — a real architectural fork not covered by the design spec, a destructive/irreversible decision, or a core-service interface that doesn't exist and can't be added cleanly. Otherwise keep going.

**Execution pattern (proven in Phase 1):** for each phase → `superpowers:writing-plans` (derive from the design spec; you do NOT need to re-brainstorm — the master spec is the approved design) → `superpowers:subagent-driven-development`: dispatch a **foreground** implementer subagent per task (model: **sonnet**), TDD (failing test first), then a **sonnet** review subagent per task with a fix loop, then a whole-phase sonnet review. Track with TaskCreate/TaskUpdate.

---

## 1. Current state (as of this handoff)

- **Branch:** `perf/token-context-optimization` (clean tree at handoff).
- **Released:** `v0.86.0-token-ctx.6` (Phase 1 framework + stop/lifecycle fixes). `gradle.properties` `pluginVersion = 0.86.0-token-ctx.6`.
- **Unreleased commits on the branch since `.6`** (will ride the end-of-night release):
  - `5f16a0d54` — monitor `status` action + case-sensitivity docs (another session).
  - `844552f1b` — retain exited monitors as `EXITED` (with code) instead of auto-removing; cap counts RUNNING only.
  - `7b7595abf` — forget MonitorManager state on exited-monitor prune; deterministic prune; soft-cap docs.
- **Full `:agent` suite green** at handoff (`./gradlew :agent:test --rerun` → BUILD SUCCESSFUL, ~3m).

### ⚠️ Concurrent-session hazard (important)
Another session has been actively editing `:agent` **runtime** tools. **NEVER `git add -A`.** Stage files explicitly. Files owned by the other session — do not touch or commit them:
`agent/.../tools/runtime/JavaRuntimeExecTool.kt`, `RuntimeExecTool.kt`, `RuntimeExecRunConfigTest.kt` (and siblings under `tools/runtime/`).
If a full-suite run fails ONLY in `tools/runtime/*` (not monitor code), it's the other session mid-edit — re-run once; if still failing there and not in your code, proceed and note it. If you go to edit `AgentService.kt`/`MonitorTool.kt` and find a conflicting concurrent edit, STOP and report rather than clobber.

---

## 2. Authoritative references (read these first)

- **Design spec (the source of truth for all phases):** `docs/superpowers/specs/2026-06-04-agent-monitor-framework-design.md` — esp. **§3** (architecture), **§4** (delivery/guardrails), **§6** (per-domain mapping with corrections), **§9** (phasing).
- **Phase 1 plan (pattern to mirror):** `docs/superpowers/plans/2026-06-04-agent-monitor-framework-phase1.md` (note: `docs/superpowers/plans/` is **gitignored** — the file exists on disk).
- **Module docs:** `agent/CLAUDE.md` → "Monitor Framework" section (includes a "Phase 2 follow-ups" subsection — see §6 below).
- **Reverse-engineered Monitor reference:** `docs/monitor-tool-reverse-engineered.md`.

---

## 3. Framework as-built (the seams you plug into)

Package `agent/src/main/kotlin/com/workflow/orchestrator/agent/monitor/`:

| File | Role / key API |
|---|---|
| `MonitorEvent.kt` | `MonitorEvent(monitorId, severity, line, payload?)`; `Severity{INFO,NOTABLE,ALERT}`; `wakeEligible` (NOTABLE/ALERT); `formatLine()`. |
| `MonitorSource.kt` | `interface MonitorSource { val monitorId; val description; fun start(emit:(MonitorEvent)->Unit); fun stop() }`. **All domain sources implement this.** |
| `ShellCommandSource.kt` | Phase-1 source (process + regex). Reference impl; has `onExit`, `killTree`, `stopped` guard. |
| `MonitorManager.kt` | Per-session coordinator. `onEvent`, `flushDue`, `forget(id)`; coalesce window, per-monitor wake budget, flood auto-stop (`onFloodStop`), `wakeOutcomeFor(IdleWakeRoute)`. **Pure/injected — reused by all sources unchanged.** |
| `MonitorPool.kt` | `@Service(PROJECT)`. `register` (suspend, cap counts RUNNING only), `get/list/stop/killAll/markExited`, `forgetCallback`, retains ≤`MAX_EXITED_RETAINED=10` EXITED. |
| `MonitorHandle.kt` | `BackgroundHandle` impl; ring buffer (`appendLine`), `markExited(code)`, `state()` (RUNNING/EXITED/KILLED), `exitCode()`. **Reused by all sources** (wrap any MonitorSource). |
| `MonitorBridge.kt` | `object`: `setRouter(project, (sessionId,event)->Unit)`, `clearRouter(project)`, `emit(project, sessionId, event)`. Project-scoped routing of source events → MonitorManager. |
| `tools/builtin/MonitorTool.kt` | The `monitor` dispatcher tool (deferred, "Utilities"). Actions `start|list|stop|status`. `start` has a `source` param (**currently only `"shell"`**). `validateStart`, `renderStatus`. |

**AgentService wiring (already done):** `monitorManagerFor(sessionId)` (lazy), `ensureMonitorManager`, `forgetMonitor`, `MonitorBridge.setRouter`, `MonitorPool.forgetCallback`, a 200ms flush loop on the service `cs`, `disposeMonitorsForSession` (called from `AgentController.killBackgroundsOnTransition`). `EnvironmentDetailsBuilder.renderActiveMonitors` surfaces active monitors passively.

### The NEW abstraction Phase 2 must build FIRST
Domain monitors need two reusable `MonitorSource` base implementations (they do not exist yet):
- **`PollingSource`** — wraps a `:core` `SmartPoller`; each tick calls a `suspend` fetch of a `:core` service, **diffs** a state field against the previous snapshot, and `emit`s a `MonitorEvent` on change. Returns `true` from the poll action when state changed (SmartPoller's contract). Severity decided by the diff (e.g. terminal-failure → ALERT). `stop()` cancels the poller.
- **`EventBusSource`** — subscribes to `EventBus.flow` (`SharedFlow<WorkflowEvent>`), filters relevant subtypes, maps to `MonitorEvent`. **MUST do start-hydration**: EventBus is `replay=0`, so on `start()` fetch current state once (via the matching `:core` service) and emit a synthetic event if already in a terminal/notable state — otherwise pre-subscription events (e.g. an already-merged PR) are missed. (Spec §3.1.)

Build these two (with unit tests using fake services / injected flows) as **Phase 2, Task 1**, before the Bamboo source. The `MonitorTool.start` branch then selects the source class by the `source` param (extend the `enumValues` + a `when(source)` factory; keep `validateStart` per-source).

---

## 4. Per-phase breakdown (all "cheap" — reuse `:core` services/`EventBus`; `:agent → :core` only)

Reference `:core` files: `core/.../events/{EventBus,WorkflowEvent}.kt`, `core/.../polling/SmartPoller.kt`, `core/.../services/{BambooService,JiraService,SonarService,BitbucketService}.kt`, `core/.../network/NetworkStateService.kt`. Example existing poller: `bamboo/.../service/BuildMonitorService.kt`.

### Phase 2 — Framework bases + Bamboo (build / stage / job)
- **Task 1:** `PollingSource` + `EventBusSource` base classes (+ tests). Wire `MonitorTool` to pick a source by `source` param.
- **Bamboo source** (`PollingSource`): poll `BambooService.getLatestBuild(chainKey)`; diff `state`/`lifeCycleState` (build), `stages[].state` (stage), `stages[].jobs[].state` (job). `source=bamboo` params: `planKey` + `branch?` (resolve to a chainKey internally via `getRecentBuilds`/branch lookup — the LLM rarely has the chain key), `level: build|stage|job`, `stageName?`, `jobName?`. Severity: terminal `Failed` → ALERT, `Successful` → NOTABLE, transitions → NOTABLE. Existing `WorkflowEvent.BuildFinished` can be an EventBusSource fast-path for terminal state; poll covers stage/job granularity.

### Phase 3 — Pull Requests (state / reviews / comments)
- `source=pull_request` params: `prId`, `aspects:[state,reviews,comments]`.
- **state** (`EventBusSource` + start-hydration): `PullRequest{Merged,Declined,Approved}` events; on start call `BitbucketService.getPullRequestDetail(prId)` and emit if already MERGED/DECLINED. Severity: MERGED/approved → NOTABLE, DECLINED → ALERT.
- **reviews** (`PollingSource`): poll `BitbucketService.getPullRequestParticipants(prId)`, diff per-participant `state` (APPROVED/NEEDS_WORK).
- **comments**: `PrCommentsUpdated` event carries `total`/`unreadCount` (NO blocker count). For "new blocker comment" do a separate poll of `BitbucketService.getBlockerCommentsCount(prId)`.

### Phase 4 — Jira (ticket / sprint)
- `source=jira_ticket` params `{ticketKey}`: poll `JiraService.getTicket`, diff `status`/`assignee`. Add a new fine-grained event `WorkflowEvent.TicketStatusChanged(ticketKey, from, to)` in `:core` (additive to the sealed class) if useful; polling is sufficient otherwise.
- `source=jira_sprint` params `{boardId | sprintId}`: **two-step** — resolve the active sprint each cycle (`getAvailableSprints(boardId)` → `state=="active"`) then poll `getSprintIssues(sprintId)`, diff per-issue `status`/membership. (Consider a one-line `getActiveSprint(boardId)` helper on `JiraService`.)

### Phase 5 — Sonar (quality gate / issues)
- `source=sonar_gate` params `{projectKey, branch?}`: `EventBusSource` on `WorkflowEvent.QualityGateResult` — **note its field is `passed: Boolean`, NOT a status string** (render OK/ERROR in `MonitorEvent.line`); poll `SonarService.getQualityGateStatus` as start-hydration/fallback. ERROR → ALERT, OK → NOTABLE.
- `source=sonar_issues` params `{projectKey, branch?, minSeverity?}`: poll `SonarService.getIssues`, diff issue count / per-issue `status`; new blocker/critical → ALERT.

### Phase 6 — UI surfacing + resume
- Webview top-bar: surface active monitors (likely via the existing `BackgroundChanged`/snapshot path or a new monitor snapshot event — check how `AgentController` pushes background-process snapshots to the webview). Don't over-build; mirror the existing indicator.
- **Resume:** persist active monitors to session state and re-arm them on session load so they're described accurately on `[TASK RESUMPTION]`. Check `MessageStateHandler`/session persistence for the pattern.

---

## 5. Hard rules / gotchas (carry from Phase 1)

- **`:agent → :core` only.** Domain sources call `:core` service interfaces (return `ToolResult<T>`), never feature-module clients. New `WorkflowEvent`s go in `:core` (additive to the sealed class).
- **Never `git add -A`** — stage explicit file lists; the concurrent session owns `tools/runtime/*` (see §1).
- **`--no-build-cache`** only when a commit changes a lambda to/from `suspend` (documented `NoSuchMethodError` trap). Otherwise normal `:agent:test`.
- **EventBus `replay=0`** → every `EventBusSource` does start-hydration (§3).
- **SmartPoller** is the polling engine (1.5× backoff, network-gated via `NetworkStateService`); reuse it, don't hand-roll loops.
- **Severity gating:** INFO never wakes an idle agent; map domain transitions to NOTABLE/ALERT so failures (build FAILED, gate ERROR, PR DECLINED, blocker comment) are ALERT and can wake.
- **Implementer subagents foreground, model sonnet; reviews sonnet.** Read real signatures before writing code (subagents did this in Phase 1 and caught several mismatches).
- **Verification gate per phase:** monitor + touched tests green, then full `:agent:test`. Whole-phase sonnet review with fix loop before moving on.

---

## 6. Still-open Phase-2 follow-ups (documented in `agent/CLAUDE.md`)
Fold into Phase 6 (or a final cleanup task) — they touch already-shipped code:
1. Persist the idle-wake notification *before* waking (mirror `onBackgroundCompletion` persist-first) so `SKIP_GUARD`/`DEFER` routes replay on resume.
2. Mark monitors dormant on abnormal loop exit (`MaxIterationsReached`/`Cancelled`/`Failed`) so they don't wake a just-exhausted session.
3. Settings UI for the three monitor tunables (`monitorCoalesceWindowMs`/`monitorWakeBudgetPerMonitor`/`monitorFloodThresholdPerMin`) under AI Agent ▸ Advanced (user's "always add settings UI" rule).

---

## 7. Final release (after ALL phases green)
1. `./gradlew :agent:test --rerun` → green; `./gradlew verifyPlugin` → BUILD SUCCESSFUL.
2. Bump `gradle.properties` `pluginVersion` → `0.86.0-token-ctx.7`; commit (`chore(release): …`).
3. `./gradlew clean buildPlugin` → ZIP in `build/distributions/`.
4. `git push origin perf/token-context-optimization`.
5. `gh release create v0.86.0-token-ctx.7 <zip> --target perf/token-context-optimization --title "…" --notes "…"` — notes should cover Phases 2–6 (domain monitors) + the unreleased status/EXITED-retention work.

## 8. Verification commands
- Single class: `./gradlew :agent:test --tests "<ClassName>"`
- Monitor package: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.monitor.*"`
- Full: `./gradlew :agent:test` (add `--rerun` for a real run vs UP-TO-DATE cache)
- Plugin loads: `./gradlew verifyPlugin`

**Definition of done:** Phases 2–6 implemented + reviewed (per-task + per-phase), full `:agent` suite green, `verifyPlugin` green, single release `v0.86.0-token-ctx.7` published. Leave a short completion summary for the user.
