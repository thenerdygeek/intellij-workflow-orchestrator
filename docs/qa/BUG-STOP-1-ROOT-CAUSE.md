# BUG-STOP-1 — Root Cause (Stop on a streaming `run_command`)

**Status:** root-caused (2026-06-27, systematic-debugging Phase 1; two independent read-only investigators, cross-validated). Reproduced by cowork (`BUG-REPRO-RESULTS.md`). **No fix applied yet.** Overlaps the merged unified-tool-stop (#62) + background tool execution (#66) — fixes must not regress those.

## Observed symptoms (cowork)
1. Stop is unresponsive for a long time while a command streams; UI logs `stop requested → Killing process → cancelTask`, yet stdout floods for 2+ min (3258+ lines **after** the kill).
2. The task eventually cancels ("Task cancelled.", output discarded to "No output.") — the loop does stop, just slowly.
3. The tool card is orphaned: ⟳ spinner spins forever, elapsed timer increments forever (2m14s→2m47s after cancel), Stop ■ stays visible but does nothing on re-click.
4. The timer glitches **both** ways on the same card: froze at "29.9s" while streaming, then runs away upward after cancel.
5. The OS `grep` may survive on the host despite "Killing process".

## One-line root cause
A task-level cancel emits **no per-tool terminal event** (`AgentLoop.kt:2165` re-throws a plain `CancellationException` instead of finalizing the in-flight tool), and the webview's cancel-time drains **copy the live `RUNNING` status verbatim** (`chatStore.ts:1470` `finalizeToolChain`, `:890` `completeSession`), so the card is finalized into a permanently-`RUNNING` state. Independently, the per-tool Stop **only kills the OS process and never cancels the tool coroutine**, and the kill itself **only force-kills the parent shell, not the `grep` child** — so the child keeps writing into a **blocking, non-cancellable reader thread**, flooding the UI for grep's whole life.

---

## Backend (Kotlin)

### (a) Stop takes ~2 min to actually cancel — *not* a timeout
- **Per-tool ■ short-circuits.** `ToolStopCoordinator.requestStop` (`tools/cancel/ToolStopCoordinator.kt:24-27`): `if (killProcess(toolCallId)) return true` then `cancelCoroutine(toolCallId)`. For `run_command` a process IS registered, so `killProcess` returns `true` and **`cancelCoroutine` is never reached** → the tool coroutine is never cancelled. The monitor loop *is* cooperative (`coroutineContext.ensureActive()` + `delay(500)`, `RunCommandTool.kt:808-809`) but receives no cancellation, so it exits only on `!process.isAlive` (`:813-819`) or the 300s in-tool cap (`:822-828`).
- Because the kill is ineffective (see (c)), the process doesn't die, so the loop spins until grep finishes traversing **naturally (~2 min)**. The "≈120s timeout" hypothesis is **REFUTED** — it's grep's natural runtime (below both the 300s in-tool and 600s outer caps).
- **Composer ■** (`cancelTask` → `AgentService.cancelCurrentTask`, `AgentService.kt:3707-3711`) cancels `task.job` + `backgroundToolExecutor.cancelAllForSession` → the monitor loop's `delay(500)` throws within ~500ms → loop unwinds → "Task cancelled / No output." But the user-visible flood persists regardless (see (b)).
- **`AgentLoop.cancel()`** (`loop/AgentLoop.kt:1815-1823`) only flips an `AtomicBoolean` (checked *between* tool calls, `:1855`) + aborts the HTTP request — it never cancels the in-flight tool.

### (b) stdout floods AFTER "Killing process"
- The stdout reader is a bare daemon `Thread` doing a **blocking native `reader.read()`** (`RunCommandTool.kt:752-774`), not coroutine/cancel-aware; it exits only on stdout **EOF (process death)**. There is no `readerThread.interrupt()` / `inputStream.close()` on cancel.
- The stream callback is captured as a **local** (`RunCommandTool.kt:742`) and is never gated by a "stopped" flag, so post-Stop chunks still push to the UI terminal while the un-killed grep keeps writing.

### (c) OS child process may survive the kill
- `ProcessRegistry.gracefulKill` (`tools/process/ProcessRegistry.kt:147-173`): descendants get a **single SIGTERM** (`child.destroy()`), `destroyForcibly()` (SIGKILL) is applied to the **parent only**. No process-group kill, no `setsid`/negative-PID, no SIGTERM→SIGKILL escalation for children. `ProcessHandle.descendants()` is unreliable on macOS and its failure is swallowed (`:149-155`).
- For `bash -l -c "grep …"`, if bash forks grep and `descendants()` misses it, only bash is killed; grep is **reparented to launchd and keeps running**, still holding the stdout pipe write-end → feeds (b).
- The per-tool ■ already `running.remove(toolCallId)`'d the entry, so the monitor `finally { ProcessRegistry.kill() }` and composer-cancel both hit a **no-op second kill** (`ProcessRegistry.kt:89`) — no stronger re-attempt.

### (root) No per-tool terminal event on task-cancel
- On loop-cancel the in-flight tool throws a plain `CancellationException` (job cancel), not `UserStopCancellationException`, so the funnel (`AgentLoop.kt:2158-2166`) takes `else { backgroundExecutor?.cancelOne(id); throw e }` — the per-tool completion `onToolCall(ToolCallProgress(...))` at `:2367-2382` **never fires**. Only the session-level `onComplete(LoopResult.Cancelled)` (`AgentService.kt:2688-2694`) reaches the UI, carrying **no tool-call id**. So `activeToolCalls[id].status` stays `RUNNING`.

---

## Frontend (React/TS, `agent/webview/src`)

### Symptom 1/2 — card never finalizes; Stop re-click no-op
- Both cancel-time drains copy status verbatim: `finalizeToolChain` (`stores/chatStore.ts:1456-1481`, `status: tc.status` at `:1470`) and `completeSession` (`:890`). A `RUNNING` tool becomes a **finalized message that is permanently `RUNNING`**. `ChatView.renderItem` (`components/chat/ChatView.tsx:90,108`) `?? 'COMPLETED'` does **not** rescue it (RUNNING is defined). → `isRunning` true (`ToolCallChain.tsx:329`) → `StatusIcon` returns `<Loader2 className="animate-spin">` (`:123-124`) forever.
- Stop re-click: card still `isRunning` → Stop renders (`terminal.tsx:139-149` / `ToolCallChain.tsx:395-406`) → `chatStore.killToolCall` is **bridge-only, no optimistic state** (`chatStore.ts:2016-2020`) → `requestStop` returns `false` (process + coroutine already gone) → silent no-op.

### Symptom 3 — timer freeze at 29.9s during streaming
- `LiveElapsedTimer` (`components/agent/ToolCallChain.tsx:101-110`) is a free-running `setInterval(…,100)` computing `Date.now()-startRef`. Freeze ⇒ the 100ms ticks **stop firing** = main-thread (JCEF) starvation. During the flood, `TerminalContent` re-renders per batched chunk and synchronously recomputes ANSI/highlight (`terminal.tsx:85-93`); `StreamBatcher` coalesces only ~16ms (`ui/StreamBatcher.kt`) → up to ~60 renders/sec monopolize the event loop → the interval is starved → display sticks (~29.9s). Cosmetic; aggravated by (b).

### Symptom 4 — timer runs away after cancel
- Corollary of 1+3: the card is permanently `RUNNING`, so `LiveElapsedTimer` stays mounted (`ToolCallChain.tsx:422-433`); once the flood stops the interval resumes, computes the real elapsed (~2m14s) and **keeps climbing forever** because the card never reaches terminal status.

---

## Proposed fixes (NOT applied; one change at a time + a failing test each, per systematic-debugging Phase 4)

**Backend (proper fixes):**
- B1. Per-tool Stop must cancel the coroutine too — don't short-circuit `requestStop` after a successful kill; also cancel the `ToolCancellationRegistry` job (and/or make `RunCommandTool` treat Stop as a first-class cooperative cancel). → fixes (a) immediacy.
- B2. Make the kill recursive + forceful — enumerate descendants and `destroyForcibly()` each (SIGTERM→SIGKILL escalation), and/or launch the shell in its own process group and kill the group; make the kill idempotent (re-verify tree dead). → fixes (c) leak.
- B3. Make the reader thread cancellable — `inputStream.close()` / `readerThread.interrupt()` on cancel, and gate the stream callback behind a per-call "stopped" flag so post-Stop chunks are dropped. → fixes (b) flood.
- B4. On loop-cancel, emit a per-tool terminal `ToolCallProgress` (e.g. `isError`/"[Cancelled]") for each still-running `toolCallId` before unwinding, so the card finalizes via the normal `updateLastToolCall` path. → fixes the root of Symptom 1/4.

**Frontend (defense-in-depth; B4-independent):**
- F1. Coerce non-terminal status on drain at `chatStore.ts:1470` (`finalizeToolChain`) and `:890` (`completeSession`): `RUNNING`/`PENDING` → terminal (add a `CANCELLED` `ToolCallStatus` + `StatusIcon` case, or fall back to `ERROR`). This finalizes the card even if the backend never emits a terminal event. (The `?? 'COMPLETED'` in `ChatView.tsx` is **not** a safety net.)
- F2. Optional: `killToolCall` flips the card terminal optimistically.
- F3. Timer freeze: decouple `LiveElapsedTimer` from main-thread starvation and/or throttle the terminal highlight (worker / `requestIdleCallback` / cap `TerminalContent` re-render frequency during RUNNING). Cosmetic.

**Minimal durable fix** = F1 (card always finalizes) + B4 (proper terminal event). The kill correctness (B1/B2/B3) is the separate, higher-value half that actually makes Stop fast and stops the OS leak.

## Open (need runtime evidence)
- Whether `bash -l -c` execs vs forks grep on the tester's macOS, and whether `ProcessHandle.descendants()` returns the grep handle there (decides "missed entirely" vs "under-escalated"). Settle with a `ProcessRegistry` debug log dumping `descendants().count()` + child PIDs at kill time + `ps -o pid,ppid,pgid,comm` before/after Stop.
