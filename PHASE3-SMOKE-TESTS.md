# Phase 3 — Smoke-Test Scenarios (deferred to end)

Working handoff (untracked). Each Phase 3 extraction is a separate PR, left for human review +
`runIde` smoke because CI cannot exercise the JCEF/Swing/runtime surface. Run these in a `runIde`
sandbox when ready. Each scenario lists: **setup → action → expected**.

> Status legend: ☐ not run · ✅ pass · ❌ fail (note below)

---

## PR #26 — `AgentMonitorCoordinator` (monitor cluster extraction)

Pure structure move; no behavior should differ from before. Focus = the wiring that only runs in a
live IDE (bridge router, flush loop, re-arm on resume, idle-wake).

- ☐ **S-E1 — Monitor re-arm on resume (the key one).**
  Setup: in agent chat, `monitor start source=shell command="bash -c 'while true; do echo tick; sleep 2; done'" filter="tick"` (note the returned `shell-XXXX` id). Action: interrupt the task / close & reopen the session from history (resume). Expected: the monitor re-arms with the **same id** — a subsequent `monitor stop shell-XXXX` resolves it; `monitor list` shows it running; no duplicate id.

- ☐ **S-E2 — Pending notifications drain on resume.**
  Setup: start a shell monitor whose filter matches an ALERT line (e.g. filter `ERROR`, command that prints `ERROR` after a delay). Action: leave the session idle/paused so the event fires while away, then resume. Expected: the resume preamble contains a `# Monitor notifications while away` section listing the fired event; it appears exactly once (not re-shown on a second resume).

- ☐ **S-E3 — New chat clears persisted monitors.**
  Setup: start any monitor. Action: click **New Chat**. Expected: the new session does **not** inherit the old monitor; `monitor list` is empty; `monitors.json` for the old session is cleared.

- ☐ **S-E4 — Live steering delivery.**
  Setup: start a shell monitor while a task is actively running (loop live). Action: trigger a matching event. Expected: the event surfaces at the next iteration boundary as a steering message (agent "sees" it mid-task), not dropped.

- ☐ **S-E5 — Idle-wake (budget-guarded).**
  Setup: start a monitor, let the task finish so the session is idle. Action: trigger a NOTABLE/ALERT event. Expected: the idle session is auto-woken (same global guard cap/cooldown as background-completion wakes); within budget it resumes, over budget it goes dormant but still surfaces passively.

- ☐ **S-E6 — Dispose / no leak.**
  Action: close the project (or switch). Expected: no errors on close; the `MonitorBridge` router is torn down (a late event for the disposed project does nothing). `# Active Monitors` passive section renders correctly during the session.

---

## PR #27 — `BrainFactory` (brain construction + model-selection precedence)

Pure structure move. The precedence logic is now unit-tested (`BrainModelResolutionTest`); smoke
confirms the live wiring + the 5x-over-billing guard end-to-end.

- ☐ **S-D1 — Saved model is honored (the money one).**
  Setup: in the model chip/settings, pick a **non-default** model (e.g. a Sonnet tier). Action: start a fresh task. Expected: the API call uses **Sonnet**, NOT auto-swapped to Opus — confirm via the top-bar model indicator and/or the log line `[Agent] Resolved model: …sonnet…`. (This is the 2026-05-06 regression.)

- ☐ **S-D2 — Caller model override.**
  Setup: spawn a sub-agent (or use any path that passes `model=`) with an explicit model. Action: run it. Expected: that exact model is used, regardless of the saved setting.

- ☐ **S-D3 — First-launch auto-pick.**
  Setup: clear the saved model (blank `sourcegraphChatModel`). Action: start a task. Expected: a model is auto-picked (Opus tier), the task runs, and the picker/settings get populated with the pick.

- ☐ **S-D4 — Missing URL error.**
  Setup: blank the Sourcegraph URL. Action: start a task. Expected: clear error "No Sourcegraph URL configured. Set one in Settings > AI & Advanced." (not a confusing stack trace).

- ☐ **S-D5 — End-to-end brain construction.**
  Action: any normal task. Expected: the brain builds and streams as before (no regression from `createBrain` → `BrainFactory.create`). Manual `Compact` button (which calls `createBrain` too) still works.

---

---

## PR (cut F) — `BackgroundCompletionCoordinator` (background-process completion routing)

Pure structure move. The shared auto-wake substrate (`IdleSessionWaker`/guard) stays on
`AgentService` and is injected; only the background-completion-specific routing + message builders
moved out. Routing decision + message format are now unit-tested
(`BackgroundCompletionCoordinatorTest`); smoke confirms the live BackgroundPool wiring.

- ☐ **S-F1 — Live-loop steering.**
  Setup: while a task is **actively running**, start a background process (e.g. `run_command` with `background:true`, or `background_process`) that finishes during the task. Action: let it exit. Expected: the agent receives a `[BACKGROUND COMPLETION]` steering message at the next iteration boundary (one message per process exit, no batching).

- ☐ **S-F2 — Idle auto-wake.**
  Setup: start a background process, then let the task complete so the session is **idle**. Action: let the process exit while idle. Expected: the idle session auto-wakes with a `[BACKGROUND COMPLETION — AUTO-RESUMED]` synthetic message (subject to the shared guard cap/cooldown — same guard as monitor & delegation wakes; `autoWakeOnBackgroundCompletion` setting must be on).

- ☐ **S-F3 — Resume replay when wake is skipped.**
  Setup: disable auto-wake (or exhaust the per-session cap), start a background process, let the task finish, let the process exit. Action: manually resume the session later. Expected: the completion is **persisted** and replayed in the resume preamble (`[BACKGROUND COMPLETIONS — delivered on resume]`), delivered exactly once.

- ☐ **S-F4 — Listener disposal.**
  Action: close the project. Expected: the BackgroundPool completion listener is torn down with the service (no leak / no errors on close).

---

_Last updated after cut F. New scenarios will be appended as further cuts (G → C → B) land._
