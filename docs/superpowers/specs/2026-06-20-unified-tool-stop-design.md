# Unified Tool-Stop Mechanism — Design Spec

- **Date:** 2026-06-20
- **Status:** Approved design, ready for implementation plan
- **Branch / worktree:** `worktree-feature+unified-tool-stop` at `.claude/worktrees/feature+unified-tool-stop` (off `origin/main` `6cd1440ee`)
- **Module:** `:agent` (depends only on `:core`)

## 1. Goal

Today only the `run_command` tool can be stopped from the chat UI. Its Stop button kills an **OS process** via `ProcessRegistry.kill(toolCallId)`. Every other long-running tool (`web_fetch`, `web_search`, `build`, `db_query`, `run_inspections`, `find_references`, sub-`agent`, …) has no Stop affordance.

Provide a **unified, per-tool-call Stop** that works for *every* running tool, routed through the single common tool-execution funnel that already exists. Stopping a tool aborts **only that tool**, feeds a `"Stopped by user"` result back to the LLM, and lets the agent loop **continue** (it is not the global "stop the whole turn" action).

## 2. Approved decisions

| Decision | Choice |
|---|---|
| Stop semantics | **Abort the tool, agent continues.** The stopped tool returns a `ToolResult("[Stopped by user]")` to the LLM; the loop keeps running. Mirrors the existing `run_command` behavior (kill → partial result → loop continues). |
| UI scope | **Universal** — the Stop button shows on any tool card whose status is `RUNNING`. Fast tools finish too quickly to show it; it naturally surfaces only on slow ones. |
| Implementation scope | **Phases 1–3** (core + sub-agent stop + real hard-cancel hooks for blocking PSI/JDBC tools). Phase 4 (distinct `STOPPED` badge) deferred. |
| Location | New isolated git worktree off `origin/main`. |

## 3. Why this is the right shape (architecture facts, verified against code)

- **One funnel.** `AgentLoop.executeToolCalls()` is the *only* place `tool.execute(params, project)` is called for normal dispatch. The call lives in a `suspend fun executeOnce()` (`AgentLoop.kt` ~2035–2041) wrapped by `withTimeoutOrNull(tool.timeoutMs)`; the surrounding `try/catch/finally` is ~2027–2077. Execution is **sequential** (`for (call in toolCalls)` ~1833) — there is no parallel `coroutineScope{async}` path around `tool.execute` (the CLAUDE.md "parallel read-only" note is stale for the orchestrator funnel). One sequential execution point = one clean home for cancellation.
- **The UI→Kotlin bridge already exists and already carries a `toolCallId`.** `chatStore.killToolCall(id)` (`chatStore.ts:1999`) → `jcef-bridge.ts:737` `callKotlin('_killToolCall', id)` → `AgentCefPanel` `killToolCallQuery` (`:108,:653`, injected `:874`) → `onKillToolCall` (`:302`) → `AgentController.setCefKillCallback { id -> ProcessRegistry.kill(id) }` (`AgentController.kt:1115–1118`). We rewire **only the callback target**; no new bridge plumbing.
- **`ProcessRegistry.kill(id): Boolean`** (`ProcessRegistry.kt:88–93`) returns `true` IFF a process was registered for that id, else `false`. This makes "process-first precedence" sound. `kill()` is non-blocking (SIGTERM on caller thread; SIGTERM-wait→SIGKILL offloaded to a daemon `killExecutor`), so it is safe to call from the EDT-affine JCEF callback.
- **`AgentLoop` is unit-instantiable** (`AgentLoopTest.kt:187` builds it with a `mockk` `Project` + real `ContextManager` + fake `LlmBrain`/`AgentTool`). So the core "stop one tool, loop continues" behavior gets a *real* behavioral test, not just a source-text contract. (Only `AgentService` is not unit-instantiable.)

## 4. Architecture overview

```
            webview Stop button (any tool card, status === RUNNING)
                          │  killToolCall(toolCallId)         ← already exists
                          ▼
         _killToolCall bridge → AgentCefPanel → AgentController
                          │
                          ▼
            ToolStopCoordinator.requestStop(toolCallId)        ← NEW (rewires existing callback)
              │                 │                   │
   process?   │  sub-agent?     │      else         │
              ▼                 ▼                   ▼
   ProcessRegistry.kill   SubAgentRegistry.abort   ToolCancellationRegistry.cancel
   (unchanged hard kill)  (Phase 2)                (Phase 1: cancel per-call child Job
                                                    with UserStopCancellationException)
                                                         │
                                                         ▼
           executeToolCancellable() (the funnel wrapper) catches the cancel,
           returns ToolResult("[Stopped by user]"), the loop continues.
```

`ToolStopCoordinator.requestStop` uses **layered precedence**:

```kotlin
fun requestStop(toolCallId: String): Boolean {
    if (ProcessRegistry.kill(toolCallId)) return true        // process tools: hard kill, partial output preserved
    if (SubAgentRegistry.abort(toolCallId)) return true      // agent tool: abort child (Phase 2)
    return ToolCancellationRegistry.cancel(toolCallId)       // cooperative coroutine cancel
}
```

Precedence matters: process tools and sub-agents have *better* teardown paths than a raw coroutine cancel, and using them preserves existing behavior (run_command's partial output; sub-agent stream + child-process teardown).

---

## 5. Phase 1 — Core mechanism (cooperative tools)

### 5.1 `ToolCancellationRegistry` (new)

Global `object`, sibling to `ProcessRegistry`, in a new `tools/cancel/` package. Named **deliberately not** `ToolCallRegistry` to avoid confusion with the existing `ToolRegistry` (which registers tool *definitions*).

```kotlin
object ToolCancellationRegistry {
    private val active = ConcurrentHashMap<String, Job>()   // toolCallId -> per-call child Job
    fun register(toolCallId: String, job: Job) { active[toolCallId] = job }
    fun unregister(toolCallId: String) { active.remove(toolCallId) }
    /** Cancels the per-call job with a UserStopCancellationException. Returns true if one was found. */
    fun cancel(toolCallId: String): Boolean {
        val job = active.remove(toolCallId) ?: return false
        job.cancel(UserStopCancellationException(toolCallId))
        return true
    }
}

/** Sentinel cause so the funnel can distinguish a user stop from any other cancellation. */
class UserStopCancellationException(toolCallId: String) :
    CancellationException("Tool call $toolCallId stopped by user")
```

`ConcurrentHashMap` is required: `cancel()` is invoked from the EDT/JCEF bridge thread while `register/unregister` run on `Dispatchers.IO`.

### 5.2 The funnel wrapper

Replace the current inline `withTimeoutOrNull(...)` execution block in `executeToolCalls()` with a wrapper using **`coroutineScope`** (cleaner structured-concurrency lifecycle than `Job() + withContext(job) + complete()`, and it auto-joins/cancels any children a tool spawned — important for sub-agents and any parallel work inside a tool):

```kotlin
val toolResult = try {
    coroutineScope {
        val callJob = coroutineContext[Job]!!                       // a real child of the loop's job
        ToolCancellationRegistry.register(toolCallId, callJob)
        try {
            if (timeout == Long.MAX_VALUE) executeOnce()
            else withTimeoutOrNull(timeout) { executeOnce() } ?: timeoutResult
        } finally {
            ToolCancellationRegistry.unregister(toolCallId)
        }
    }
} catch (e: CancellationException) {
    if (isUserStop(e)) {
        // The per-call child job was cancelled by the user. The loop's own job is alive.
        currentCoroutineContext().ensureActive()                    // guard: if loop itself died, propagate cleanly
        stoppedByUserResult(toolName)                               // ToolResult, isError = false → loop continues
    } else {
        throw e                                                     // genuine loop cancel → propagate (existing behavior)
    }
} catch (e: Exception) {
    /* existing error handling: log + reportToolError + continue */
} finally {
    /* EXISTING ThreadLocal cleanup MUST stay (AgentLoop.kt ~2073–2077):
       BackgroundProcessTool.currentSessionId.remove();
       if (toolName in STREAMING_TOOLS) { RunCommandTool.currentToolCallId.remove(); RunCommandTool.currentSessionId.remove() } */
}
```

### 5.3 The discriminator: cause sentinel, **not** `isActive` (review fix)

Both arrive as `CancellationException`: a user stop (cancel the per-call child job) and a genuine loop cancel (the whole turn is being aborted). Discriminate on the **cancellation cause**, walking the cause chain — *not* on `currentCoroutineContext().isActive`:

```kotlin
private fun isUserStop(e: CancellationException): Boolean =
    generateSequence(e as Throwable?) { it.cause }.any { it is UserStopCancellationException }
```

**Why not `isActive`:** after `coroutineScope`/`withContext` unwinds, `currentCoroutineContext()` is the *loop's* context, so `isActive` only reports whether the loop is alive — it would misclassify *any* foreign cancellation (a tool cancelling its own child, a leaked nested timeout) as a "user stop" and fabricate a fake result while the real cause was an error. The cause sentinel is robust against this. **Lesson: carry intent in the cancel cause; never infer it from coroutine liveness.**

**Why `ensureActive()` before returning:** if `isUserStop` ever false-positives on a cancellation that *was* the loop dying, the loop's job is now in `Cancelling`, and the next suspension point (`addToApiConversationHistory`'s `Mutex.withLock`, disk I/O, or the next `brain.chatStream`) would throw at a random later `await`. `currentCoroutineContext().ensureActive()` converts that latent corruption into an immediate, correct propagate.

### 5.4 The "Stopped by user" result

```kotlin
private fun stoppedByUserResult(toolName: String) = ToolResult(
    content = "[Tool '$toolName' was stopped by the user before it finished. " +
              "No result was produced. You may continue with a different approach.]",
    summary = "Stopped by user",
    isError = false,            // a deliberate user action, not a failure
)
```

`isError = false` → maps to a `COMPLETED` tool-card (see §5.6), with the content making the stop explicit. The result is added to the conversation like any tool result; the loop continues.

### 5.5 `ToolStopCoordinator` + the one-line rewire

`AgentController.kt:1115–1118` changes the callback **target** only:

```kotlin
panel.setCefKillCallback { toolCallId ->
    LOG.info("AgentController: stop requested for tool call $toolCallId")
    ToolStopCoordinator.requestStop(toolCallId)
}
```

### 5.6 UI — generic Stop button

- **`ToolCallChain.tsx`:** today only `TerminalContent` (`:271–308`, rendered for `isCmdTool` at `:384`) wires a kill handler. Add a Stop control to the **generic** card header — `ChainOfThoughtTrigger` / `ToolCallItem` (`:344–382`), which already renders `<StatusIcon status={tc.status}/>` + `tc.name` + `<LiveElapsedTimer>` when running. Render the `Square` button (reuse from `terminal.tsx`) when `tc.status === 'RUNNING'`, calling the existing `useChatStore.getState().killToolCall(tc.id)`.
- **Status field:** `ToolCall.status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'ERROR'` (`bridge/types.ts:27`); `RUNNING` shows a spinning `Loader2` (`ToolCallChain.tsx:108–119`).
- **Suppression:** suppress the generic Stop button for **`ask_user_input` only** (name-based; `tc.name` is in scope at `:358`). The other interactive tools (`plan_mode_respond`, `ask_followup_question`, `ask_questions`, `attempt_completion`) already early-return in `AgentController.onToolCall` (`:3081`) and never render a generic running card, so they need no suppression.

### 5.7 Phase 1 invariants

- A per-tool Stop **must never** set the loop's `cancelled` AtomicBoolean (`AgentLoop.kt:515`) or call `AgentLoop.cancel()` (`:1794`). Those halt the whole turn. The per-tool path touches only the per-call child job.
- `toolCallId` is unique per call, so registry register/unregister cannot collide across tools.
- Race — Stop after completion / before register: `cancel()` returns `false`, `requestStop` returns `false`, no-op. Safe.

---

## 6. Phase 2 — Sub-agent stop (`agent` tool)

**Problem (review showstopper).** The `agent` tool runs a child `AgentLoop` via `SpawnAgentTool.executeSingle` → `runner.run` → `runInternal` (`SubagentRunner.kt`), where the child `loop.run(prompt)` is a direct child coroutine (`~:423`). But `runInternal` wraps everything in `catch (e: Exception)` (`:489`), and `CancellationException` **is** an `Exception`. It only maps a cancellation to a cancelled result when `abortRequested` is `true` (`:497`); a raw coroutine job-cancel never sets that flag, so the cancellation falls through to the generic `FAILED` branch (`:509–524`) and is **swallowed**. Net: a raw job-cancel of the `agent` tool would *not* stop the child's in-flight LLM stream or its child `run_command` processes.

**Fix.**
1. **`SubAgentRegistry` (new):** maps `toolCallId → child agentId(s)`, populated by `SpawnAgentTool` when it spawns (single and `executeParallel`/`supervisorScope` at `:925`). `abort(toolCallId): Boolean` calls `SpawnAgentTool.cancelAgent(agentId)` for each child → `runner.abort()` (`:160`), which sets `abortRequested = true` **and** calls `brain.cancelActiveRequest()` (cancels the child's LLM stream) and tears the child down. Returns `true` if it was an agent tool-call. This is the **precedence** entry in `ToolStopCoordinator` (§4), mirroring run_command's process-kill precedence.
2. **Fix the swallow:** `SubagentRunner.runInternal`'s `catch (e: Exception)` must **re-throw `CancellationException`** before the generic branch — complying with the codebase's own "never swallow cancellation" rule (the orchestrator funnel already does `catch (CancellationException) { throw e }`).

Because `abort()` sets `abortRequested`, the child loop exits cleanly and `runInternal` returns a *cancelled* `SubagentRunResult` mapped to a normal `ToolResult` — so the parent funnel sees a normal result and the parent loop continues (consistent with §2 semantics). The exact child-abort API (`SpawnAgentTool.cancelAgent` / `runner.abort`) and how `toolCallId → agentId` is known at spawn time must be confirmed during implementation.

---

## 7. Phase 3 — Make Stop actually *bite* for blocking tools

Coroutine cancellation is **cooperative**. Several tools do truly *blocking*, non-suspending work, so a coroutine cancel only *abandons the result* — the underlying work keeps running (CPU or a pinned DB connection) until it finishes or hits its own timeout. Returning "Stopped by user" while work continues would be misleading and leak resources. Wire real cancel hooks for the two common blocking patterns:

| Tool(s) | Blocking pattern (evidence) | Hard-cancel hook |
|---|---|---|
| `find_references` (`FindReferencesTool.kt:135,:178`), `find_definition`, `run_inspections` (`RunInspectionsTool.kt:166–172,:233`), `diagnostics` | `ReadAction.nonBlocking{…}.inSmartMode(project).executeSynchronously()` parks the thread; PSI walk runs to completion | Drive the read action with a `ProgressIndicator` tied to the per-call `callJob`; cancel the indicator on stop so `executeSynchronously()` aborts |
| `db_query` (`DbQueryTool.kt`) | JDBC `stmt.executeQuery(sql)` inside `withContext(Dispatchers.IO)` is blocking; cancel abandons the IO task but the query runs until `queryTimeout` | Hold the `Statement`; call `Statement.cancel()` on stop |
| `web_fetch` (`WebFetchTool.kt`) | OkHttp `.execute()` inside `withContext(Dispatchers.IO)`, re-throws `CancellationException` | Already cooperative — coroutine cancel abandons promptly. No extra hook. |

Add an opt-out hatch on the tool interface:

```kotlin
// AgentTool.kt — mirrors the existing defaulted `timeoutMs` (:89) / `outputConfig` (:92); forces no implementor changes
val interruptible: Boolean get() = true
```

YAGNI: do **not** flip any tool's `interruptible` to `false` pre-emptively — provide the hook only. (Cancellation cannot tear a single `WriteCommandAction` in half because write actions are synchronous/non-suspending, so single-write mutating tools are safe by construction; the flag exists for any future multi-step mutator that needs it.)

---

## 8. Out of scope (Phase 4 — optional polish)

A distinct `STOPPED` status/badge. It would require opening **two** status-collapse gates plus enum/union additions: `AgentCefPanel.updateLastToolCall` (`:1175`) collapses to `ERROR`/`COMPLETED`, and `jcef-bridge.ts:215` collapses again; plus `RichStreamingPanel.ToolCallStatus` (Kotlin enum), `ToolCallStatus` union (`types.ts:27`), and a `StatusIcon` case. For v1, a stopped tool renders as `COMPLETED` with the `"[Stopped by user]"` content, which is clear enough.

## 9. Testing plan

- **`ToolCancellationRegistryTest`** (pure JVM): register/cancel/unregister; `cancel` returns `false` when absent; `cancel` actually cancels the job with a `UserStopCancellationException` cause.
- **`ToolStopCoordinatorTest`**: layered precedence — process found → only `ProcessRegistry.kill`; agent tool-call → only `SubAgentRegistry.abort`; otherwise → `ToolCancellationRegistry.cancel`. Use seams/fakes for the three registries.
- **Behavioral funnel test via the `AgentLoopTest` harness** (real, not source-text): a fake tool that suspends forever → `requestStop(toolCallId)` → assert the tool result is "Stopped by user", the loop continues to the next iteration, the loop's `cancelled` flag stays `false`, and the ThreadLocal cleanup ran. Add a sibling test where the *whole loop* is cancelled → the funnel re-throws (propagates).
- **`isUserStop` unit test**: sentinel directly, sentinel nested as `cause`, and an unrelated `CancellationException` → only the first two are user-stops.
- **Sub-agent teardown test** (Phase 2): stopping an `agent` tool-call aborts the child (sets `abortRequested`, cancels the child brain stream); `SubagentRunner` no longer swallows `CancellationException`.
- **Source-text contract test** for the `AgentController` callback rewire — **mind the sentinel-slice trap**: place any new private helper *outside* the source-text-sliced ranges other tests assert on, and run the **full** `:agent:test`, not just `--tests`.
- **vitest** (`ToolCallChain.test.tsx`): generic Stop button renders on `RUNNING`, hidden otherwise, suppressed for `ask_user_input`, and calls `killToolCall(tc.id)`.

Build/verify: `./gradlew :agent:clean :agent:test --rerun --no-build-cache` (suspend-signature/ctor changes can trigger the build-cache `NoSuchMethodError` trap) + `./gradlew verifyPlugin`.

## 10. Files touched (map)

**New**
- `tools/cancel/ToolCancellationRegistry.kt` (+ `UserStopCancellationException`)
- `tools/cancel/ToolStopCoordinator.kt`
- `tools/cancel/SubAgentRegistry.kt` (Phase 2)
- Tests: `ToolCancellationRegistryTest`, `ToolStopCoordinatorTest`, funnel behavioral test, `isUserStop` test, sub-agent teardown test, `AgentController` source-text contract test.

**Modified**
- `loop/AgentLoop.kt` — funnel wrapper (`coroutineScope` + register/unregister), discriminating `catch`, keep existing `finally` cleanup; `isUserStop`/`stoppedByUserResult` helpers (placed outside source-text-sliced ranges).
- `ui/AgentController.kt:1115–1118` — rewire `setCefKillCallback` target to `ToolStopCoordinator.requestStop`.
- `tools/AgentTool.kt` — add `interruptible: Boolean get() = true` (Phase 3).
- `tools/builtin/SpawnAgentTool.kt` + `.../SubagentRunner.kt` — populate `SubAgentRegistry`; re-throw `CancellationException` (Phase 2).
- `FindReferencesTool.kt` / `FindDefinitionTool.kt` / `RunInspectionsTool.kt` / `SemanticDiagnosticsTool.kt` — `ProgressIndicator` hook (Phase 3).
- `DbQueryTool.kt` — `Statement.cancel()` hook (Phase 3).
- webview `components/agent/ToolCallChain.tsx` — generic Stop button + `ask_user_input` suppression; vitest.

## 11. Risks & open questions

1. **Sub-agent abort API** — confirm `SpawnAgentTool.cancelAgent` / `runner.abort()` exist and how `toolCallId → agentId` is captured at spawn (single + parallel). (Phase 2.)
2. **run_command spawn-after-cancel TOCTOU** — between funnel `register` and the tool's `ProcessRegistry.register`, a Stop finds no process and falls back to coroutine cancel; if the process spawns *after*, it could orphan. Mitigation: process-precedence usually wins (process registers early); optionally have `RunCommandTool` check `callJob.isActive` around spawn. Low severity.
3. **ProgressIndicator wiring** for `ReadAction.nonBlocking().executeSynchronously()` — confirm the indicator-cancel actually aborts the synchronous read action in this platform version. (Phase 3.)
4. **Status double-collapse** — accepted for v1 (renders as `COMPLETED`); revisit only if a distinct badge is wanted (Phase 4).
