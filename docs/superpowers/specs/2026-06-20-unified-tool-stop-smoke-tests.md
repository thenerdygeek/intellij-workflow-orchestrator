# Unified Tool-Stop — In-IDE Smoke Tests

> Companion to `2026-06-20-unified-tool-stop-handoff.md`. These are **manual** — they
> can't be automated, because the whole point is proving the Stop button *bites* at
> runtime (a real OS process dies, server-side query is cancelled, CPU drops). The
> Phase-3 unit tests are source-text contracts: they prove the mechanism is *wired*,
> not that it *works*. This runbook is the only thing that proves the latter.
>
> Branch `worktree-feature+unified-tool-stop` @ `d4532da6a` (PR #62). ~15 min total.

## 0. The three Stop affordances (know which one each test uses)

| Affordance | Where | What it does |
|---|---|---|
| **Per-tool Stop** | small `Stop` button on a *running* tool card's header (chat timeline) | cancels **only that tool**, feeds `"[Stopped by user]"` to the LLM, **loop continues**. Shown for every tool **except** `run_command`, `background_process`, `ask_user_input`, `agent`. |
| **Global Stop** | `Stop` button in the chat **input bar** (right side, replaces Send while running) | `loop.cancel()` + `job.cancel()` → cancels the in-flight tool **and ends the whole turn**. |
| **Kill Sub-Agent** | `Kill Sub-Agent` button on a running sub-agent card | cancels **only that sub-agent** + its in-flight tool; **orchestrator continues** (gets a FAILED tool result). The parent `agent` card has **no** Stop button by design. |

## 1. Pre-flight

```bash
cd .claude/worktrees/feature+unified-tool-stop
./gradlew runIde            # launches a sandbox IntelliJ with the plugin
```
In the sandbox IDE:
1. **Open a large Java/Kotlin project** (the plugin repo itself is ideal, or intellij-community) — needed for tests #1 and #2 to have enough work to observe.
2. Open the **Workflow** tool window (bottom dock) → **agent chat**.
3. Make sure **plan mode is OFF** (act mode) so tools actually execute.
4. Keep a terminal open for `ps`/`top` and (optionally) a DB console — the "observe" column below.

**Observation toolkit:**
```bash
# process-death checks (#5, #6, cascade)
watch -n1 'ps aux | grep -E "[s]leep 6|[p]g_sleep" '
# CPU / hot-thread checks (#1, #2)
top -o cpu        # or Activity Monitor → CPU; watch the IDE/JBR process
# Postgres server-side cancel check (#3)
psql -c "select pid, state, query from pg_stat_activity where query like '%pg_sleep%';"
```

---

## 2. The tests

Each: **type the Trigger prompt verbatim** into the agent chat, wait for the tool card
to appear and show *running*, then perform the **Action**, then check **PASS**.

### #1 — `run_inspections` / `diagnostics` → per-tool Stop → CPU drops
- **Proves:** cooperative `checkCanceled()` in the visitor walk actually aborts (the *Critical* `cfdd55635` fix — PCE was being swallowed and Stop was dead while the contract test passed green).
- **Trigger:** `Run run_inspections on the whole project (or on <a very large source file>). Don't do anything else.`
- **Action:** when the `run_inspections` card is running and the IDE CPU is pinned, click its **per-tool Stop**.
- **PASS:** IDE CPU **drops within ~1–2 s**; card resolves to `[Stopped by user]`; the agent continues (asks what's next / responds). 
- **FAIL (regression):** CPU stays pinned and inspections run to completion despite "stopped" — the PCE-swallow is back.

### #2 — `find_references` (high fanout) → per-tool Stop → prompt abort
- **Proves:** `smartReadAction` + cooperative cancel on PSI traversal.
- **Trigger:** `Find all references to the Project type (com.intellij.openapi.project.Project) across the whole project using find_references.` *(any ultra-common symbol = huge fanout)*
- **Action:** click the card's **per-tool Stop** within the first second or two.
- **PASS:** card stops **promptly** (≪ the time a full traversal would take) → `[Stopped by user]`; loop continues.
- **FAIL:** card only resolves after the full multi-second traversal completes.

### #3 — `db_query` (`pg_sleep`) → per-tool Stop → **server-side** cancel
- **Setup (heaviest):** configure a Postgres datasource in the sandbox IDE (Database tool window) and confirm `db_list_profiles` sees it. *(MySQL alt: `SELECT SLEEP(60)`.)*
- **Proves:** JDBC `Statement.cancel()` fires on cancel (not just abandoning the coroutine).
- **Trigger:** `Run this query with db_query against <profile>: SELECT pg_sleep(60);`
- **Action:** while it hangs, click the card's **per-tool Stop**.
- **PASS:** in `pg_stat_activity` the `pg_sleep` row **disappears / state flips to cancelled** well before 60 s; card → `[Stopped by user]`; loop continues.
- **FAIL:** the query keeps running server-side for the full 60 s (only the client coroutine was abandoned).
- *(No DB handy? Skip and note it as untested — don't fake a pass.)*

### #4 — `web_fetch` → per-tool Stop → "[Stopped by user]" + loop continues
- **Setup:** network reachable (mind corporate proxy).
- **Proves:** OkHttp `Call.cancel()`; and that a per-tool Stop is *not* a turn-ender.
- **Trigger:** `Fetch https://httpbin.org/delay/30 with web_fetch.` *(any slow/large URL)*
- **Action:** click the card's **per-tool Stop** mid-fetch.
- **PASS:** card → `[Stopped by user]` quickly; **the agent keeps going** (this is the headline "stop one tool, turn survives" behavior).
- **FAIL:** stop hangs until the fetch's own timeout, or the whole turn ends.

### #5 — `run_command` → **GLOBAL** Stop → OS process actually killed
- **Proves:** the graceful-teardown `finally { ProcessRegistry.kill }` on the global loop-cancel path (commit `6f640ccce`). This is the path that previously **orphaned** the process.
- **Trigger:** `Run the shell command: sleep 60` → **Approve** the run_command card (it's always per-invocation).
- **Observe first:** `ps aux | grep [s]leep` shows the `sleep 60` PID.
- **Action:** click the **GLOBAL Stop** (input-bar Stop, not a card button — `run_command` has no header Stop by design).
- **PASS:** the `sleep 60` process is **gone immediately** from `ps`; the turn ends.
- **FAIL:** `sleep 60` lingers in `ps` for the full 60 s after Stop = orphaned process = the bug this fix targets.

### #6 — Sub-agent → **Kill Sub-Agent** → child stream + grandchild process die, orchestrator continues
- **Proves:** `cancelAgent → runner.abort → abortableRunJob.cancel` cancels the sub-agent's loop *and* its in-flight grandchild tool (commit `52604db65`), while the orchestrator survives. Also proves the parent `agent` card shows **no** universal Stop.
- **Trigger:** `Spawn a coder sub-agent whose only task is to run the shell command "sleep 90" with run_command and report back.` → approve the grandchild run_command if prompted.
- **Observe first:** `ps aux | grep [s]leep` shows `sleep 90`; confirm the **parent `agent` card has NO Stop button**, only the sub-agent card has **Kill Sub-Agent**.
- **Action:** click **Kill Sub-Agent** on the running sub-agent card.
- **PASS:** sub-agent card → aborted/FAILED; `sleep 90` **gone** from `ps`; the **orchestrator loop continues** (receives the "aborted by user" tool result and responds).
- **FAIL:** orphaned `sleep 90` lingers, or the whole turn dies (should *not* — Kill is targeted), or a Stop button appears on the parent card.

### #C (bonus) — GLOBAL Stop cascades into a running sub-agent
- **Proves:** structured-concurrency cascade — `task.job.cancel()` reaches a sub-agent's grandchild tool (the path I traced: `supervisorScope`/inline `runner.run` are structured children of the orchestrator job).
- **Trigger:** same as #6 (`sleep 90` in a sub-agent).
- **Action:** this time click the **GLOBAL Stop** (input bar) instead of Kill Sub-Agent.
- **PASS:** `sleep 90` grandchild process **dies** and the **whole turn ends**.
- **FAIL:** grandchild lingers after the turn ends = cascade gap.

---

## 3. Recording results

| # | Tool | Stop type | Result | Notes |
|---|------|-----------|--------|-------|
| 1 | run_inspections | per-tool | ☐ pass ☐ fail | CPU drop within __s |
| 2 | find_references | per-tool | ☐ pass ☐ fail | |
| 3 | db_query | per-tool | ☐ pass ☐ fail ☐ skipped (no DB) | server-side cancel? |
| 4 | web_fetch | per-tool | ☐ pass ☐ fail ☐ skipped (no net) | loop continued? |
| 5 | run_command | global | ☐ pass ☐ fail | process gone from ps? |
| 6 | sub-agent | Kill | ☐ pass ☐ fail | grandchild gone + orchestrator continued? |
| C | sub-agent | global | ☐ pass ☐ fail | cascade |

**Highest-value if short on time:** #5 (the orphaned-process fix), #6 (sub-agent teardown),
#1 (the Critical PCE-swallow fix). #3/#4 are skippable if DB/network aren't handy — mark
them skipped, don't infer a pass.
