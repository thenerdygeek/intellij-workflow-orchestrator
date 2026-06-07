# Phase 3 — Smoke-Test Scenarios (Windows-ready)

Working handoff (untracked). Each Phase 3 extraction is a structural refactor left for human
`runIde` smoke because CI cannot exercise the JCEF/Swing/runtime surface. Run these in a `runIde`
sandbox or from the installed plugin. Each scenario lists: **setup → action → expected**.

Tested against release **`v0.86.0-phase3.1`** (ZIP attached to the GitHub release).

> Status legend: ☐ not run · ✅ pass · ❌ fail (note below)

## Run status (2026-06-07)

| Scenario | Status | Notes |
|---|---|---|
| S-D1 saved model honored | ✅ pass | tried on Windows, worked |
| S-D2 caller model override | ☐ not run | |
| S-D3 first-launch auto-pick | ☐ not run | |
| S-D4 missing URL error | ☐ not run | |
| S-D5 end-to-end brain + Compact | ☐ not run | |
| S-E1 monitor re-arm on resume | ☐ not run | |
| S-E2 notifications drain on resume | ☐ not run | |
| S-E3 new chat clears monitors | ☐ not run | |
| S-E4 live steering delivery | ☐ not run | |
| S-E5 idle-wake (budget-guarded) | ☐ not run | |
| S-E6 dispose / no leak | ☐ not run | |
| S-F1 background completion (live) | ☐ not run | |
| S-F2 background completion (idle auto-wake) | ☐ not run | |
| S-F3 resume replay when wake skipped | ☐ not run | |
| S-F4 listener disposal | ☐ not run | |

## ⚠ Windows shell notes (read before the monitor/background steps)

- The plugin's `ShellResolver` picks **Git Bash → PowerShell → cmd** on Windows. The original
  `bash -c '...'` examples only work if **Git Bash is installed**. PowerShell equivalents are
  inlined below and are the recommended form on Windows.
- Monitor `filter` is **case-sensitive by default** — prefix with `(?i)` for case-insensitive.
- Inside an agent-chat `monitor start ... command="..."` the inner PowerShell quotes must be
  escaped (`\"`). Both forms are given per scenario.

---

## PR #27 — `BrainFactory` (brain construction + model-selection precedence)

The precedence logic is unit-tested (`BrainModelResolutionTest`); smoke confirms live wiring + the
5x-over-billing guard end-to-end.

- ✅ **S-D1 — Saved model is honored (the money one).** *(PASSED on Windows.)*
  Setup: in the model chip/settings, pick a **non-default** model (e.g. a Sonnet tier). Action: start a fresh task. Expected: the API call uses **Sonnet**, NOT auto-swapped to Opus — confirm via the top-bar model indicator. (Guards the 2026-05-06 regression.)

- ☐ **S-D2 — Caller model override.**
  Setup: spawn a sub-agent (or any path passing `model=`) with an explicit model. Action: run it. Expected: that exact model is used, regardless of the saved setting.

- ☐ **S-D3 — First-launch auto-pick.**
  Setup: clear the saved model (blank `sourcegraphChatModel`). Action: start a task. Expected: a model is auto-picked (Opus tier), the task runs, and the picker/settings get populated with the pick.

- ☐ **S-D4 — Missing URL error.**
  Setup: blank the Sourcegraph URL. Action: start a task. Expected: clear error "No Sourcegraph URL configured. Set one in Settings > AI & Advanced." (not a stack trace).

- ☐ **S-D5 — End-to-end brain construction.**
  Action: any normal task. Expected: the brain builds and streams as before (no regression from `createBrain` → `BrainFactory.create`). The manual **Compact** button (which also calls `createBrain`) still works.

---

## PR #26 — `AgentMonitorCoordinator` (monitor cluster extraction)

Pure structure move. Focus = the wiring that only runs in a live IDE (bridge router, flush loop,
re-arm on resume, idle-wake).

- ☐ **S-E1 — Monitor re-arm on resume (the key one).**
  Setup (PowerShell, in agent chat):
  `monitor start source=shell command="powershell -Command \"while ($true) { echo tick; Start-Sleep 2 }\"" filter="tick"`
  (note the returned `shell-XXXX` id). Action: interrupt the task / close & reopen the session from history (resume). Expected: the monitor re-arms with the **same id** — `monitor stop shell-XXXX` resolves it; `monitor list` shows it running once; no duplicate id.

- ☐ **S-E2 — Pending notifications drain on resume.**
  Setup (PowerShell): a monitor whose filter matches an ALERT line —
  `monitor start source=shell command="powershell -Command \"Start-Sleep 5; echo ERROR\"" filter="ERROR"`.
  Action: leave the session idle/paused so the event fires while away, then resume. Expected: the resume preamble contains a `# Monitor notifications while away` section listing the fired event; it appears exactly once (not re-shown on a second resume).

- ☐ **S-E3 — New chat clears persisted monitors.**
  Setup: start any monitor. Action: click **New Chat**. Expected: the new session does **not** inherit the old monitor; `monitor list` is empty; `monitors.json` for the old session is cleared.

- ☐ **S-E4 — Live steering delivery.**
  Setup: start a shell monitor while a task is actively running (loop live). Action: trigger a matching event. Expected: the event surfaces at the next iteration boundary as a steering message (agent "sees" it mid-task), not dropped.

- ☐ **S-E5 — Idle-wake (budget-guarded).**
  Setup: start a monitor, let the task finish so the session is idle. Action: trigger a NOTABLE/ALERT event. Expected: the idle session is auto-woken (same global guard cap/cooldown as background-completion wakes); within budget it resumes, over budget it goes dormant but still surfaces passively.

- ☐ **S-E6 — Dispose / no leak.**
  Action: close the project (or switch). Expected: no errors on close; the `MonitorBridge` router is torn down (a late event for the disposed project does nothing). `# Active Monitors` passive section renders correctly during the session.

---

## PR #28 — `BackgroundCompletionCoordinator` (background-process completion routing)

Pure structure move. The shared auto-wake substrate (`IdleSessionWaker`/guard) stays on
`AgentService` and is injected; only the background-completion-specific routing + message builders
moved out. Routing + message format are unit-tested (`BackgroundCompletionCoordinatorTest`).

- ☐ **S-F1 — Live-loop steering.**
  Setup: while a task is **actively running**, start a background process that finishes during the task. Windows example (PowerShell): `run_command command="powershell -Command \"Start-Sleep 8; echo done\"" background:true` (or use `background_process`). Action: let it exit. Expected: the agent receives a `[BACKGROUND COMPLETION]` steering message at the next iteration boundary (one message per process exit, no batching).

- ☐ **S-F2 — Idle auto-wake.**
  Setup: start a background process, then let the task complete so the session is **idle**. Requires `autoWakeOnBackgroundCompletion` ON (Settings → AI Agent → Advanced). Action: let the process exit while idle. Expected: the idle session auto-wakes with a `[BACKGROUND COMPLETION — AUTO-RESUMED]` synthetic message (subject to the shared guard cap/cooldown — same guard as monitor & delegation wakes).

- ☐ **S-F3 — Resume replay when wake is skipped.**
  Setup: disable auto-wake (or exhaust the per-session cap), start a background process, let the task finish, let the process exit. Action: manually resume the session later. Expected: the completion is **persisted** and replayed in the resume preamble (`[BACKGROUND COMPLETIONS — delivered on resume]`), delivered exactly once.

- ☐ **S-F4 — Listener disposal.**
  Action: close the project. Expected: the BackgroundPool completion listener is torn down with the service (no leak / no errors on close).

---

## PR #29 — `NetworkRecoveryPolicy` (executeTask incision 1)

Internal rewire only; pure gating logic is unit-tested (`NetworkRecoveryPolicyTest`).

- ☐ **S-B1 — Normal task launch/stream.**
  Action: any task. Expected: launches and streams exactly as before (the L2 tier-escalation gating decision moved to a pure helper; no user-visible change).

---

_Last updated 2026-06-07 (release v0.86.0-phase3.1). New scenarios will be appended as further cuts (more executeTask incisions → C → G) land._
