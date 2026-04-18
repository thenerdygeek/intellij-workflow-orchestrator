# Phase 3 — IDE State Leak Fixes

**Fixes:** User incident #4 — "initialization error" on the user's own manual test runs after the agent finishes.
**Audit source:** `docs/research/2026-04-17-runtime-test-tool-audit.md` §2 Incident #4 + §5.1.
**Preconditions:** Branch `feature/tooling-architecture-enhancements`. Can run in parallel with Phases 1, 2, 6 — all touch disjoint code paths.
**Estimated:** 1–2 days. Small-medium complexity, high blast-radius-reduction impact.

---

## Context

Each agent `run_tests`/`run_with_coverage` call leaks four things:

1. `RunContentDescriptor` — never removed from `RunContentManager`. Run tool window accumulates orphan tabs; IntelliJ's per-type caches (especially JUnit's fork-JVM classpath state, Spring `ApplicationContext` cache) degrade.
2. `TestResultsViewer.EventsListener` — added but never removed. Viewer is project-scoped; listeners stack.
3. Raw `Thread` spawns for build watchdog + test-tree polling — retain `continuation`, `descriptor`, `MessageBusConnection` strong refs.
4. `ProcessListener` captures continuation — if run is killed by timeout, the listener stays attached to the zombie `ProcessHandler`.

After enough agent runs in one IDE session, the user's own `Run | Run 'MyTest'` fails with `java.lang.Exception: initializationError` — a generic JUnit runner failure that's secondary to corrupted state.

---

## Scope

**In:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt` — `executeWithNativeRunner`, `handleDescriptorReady`.
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt` — `executeRunWithCoverage`.
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` — expose a session-scoped `Disposable`.
- New shared helper: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RunInvocation.kt` (or similar).

**Out:**
- Debug tool leaks → Phase 4.
- Output spilling → Phase 6.

---

## Task list

### Task 2.1 — Session-scoped Disposable infrastructure

**Location:** New file `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RunInvocation.kt`.

Problem: Each launch needs its own disposal scope. IntelliJ's `Disposer.newDisposable(parent, name)` pattern is the canonical approach.

Add:
```kotlin
class RunInvocation(parent: Disposable, name: String) : Disposable {
    private val disposable = Disposer.newDisposable(parent, "agent-run-$name")
    val descriptorRef = AtomicReference<RunContentDescriptor?>(null)
    val processHandlerRef = AtomicReference<ProcessHandler?>(null)

    fun attachListener(listener: TestResultsViewer.EventsListener, viewer: TestResultsViewer) {
        viewer.addEventsListener(listener)
        Disposer.register(disposable) { viewer.removeEventsListener(listener) }
    }

    fun subscribeTopic(connection: MessageBusConnection) {
        Disposer.register(disposable) { connection.disconnect() }
    }

    fun attachProcessListener(handler: ProcessHandler, listener: ProcessListener) {
        handler.addProcessListener(listener)
        Disposer.register(disposable) { handler.removeProcessListener(listener) }
    }

    override fun dispose() {
        processHandlerRef.get()?.let { if (!it.isProcessTerminated) it.destroyProcess() }
        descriptorRef.get()?.let { desc ->
            // Remove from RunContentManager on EDT
            ApplicationManager.getApplication().invokeLater {
                val project = desc.component.let { ... } // acquire project
                val executor = ... // resolve executor
                ExecutionManager.getInstance(project).contentManager.removeRunContent(executor, desc)
            }
        }
        Disposer.dispose(disposable)
    }
}
```

Exact disposal of `RunContentDescriptor` needs the project + executor — store both in the invocation struct at construction time.

### Task 2.2 — Parent Disposable from `AgentService`

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`.

`AgentService` is `@Service(PROJECT)` which is already a `Disposable` in IntelliJ (project services implement `Disposable` by default).

Change:
1. Add `fun newRunInvocation(name: String): RunInvocation = RunInvocation(this, name)` — or, better, wire a per-session `Disposable` that gets disposed on session end (new chat).
2. Wire `AgentLoop` to pass the session `Disposable` to tool executions via the coroutine context (similar to `WorkerContext` pattern already used for sub-agents).
3. Tools that need a parent `Disposable` grab it from the context: `coroutineContext[WorkerContext]?.sessionDisposable ?: project`.

### Task 2.3 — Convert `JavaRuntimeExecTool.executeWithNativeRunner` to `RunInvocation`

**File:** `JavaRuntimeExecTool.kt:197–422`.

Replace the manual `processHandlerRef`, `descriptorRef`, `buildConnectionRef` with a single `RunInvocation`:

```kotlin
val invocation = newRunInvocation("run_tests-${System.currentTimeMillis()}")
try {
    // subscribe via invocation.subscribeTopic(buildConnection)
    // addProcessListener via invocation.attachProcessListener(handler, listener)
    // addEventsListener via invocation.attachListener(testListener, resultsViewer)
    ...
} finally {
    Disposer.dispose(invocation)
}
```

All cleanup paths (success, timeout, exception, cancellation) flow through the single `finally`.

Delete:
- Line 362–367: raw `Thread` build-watchdog. Replace with `AppExecutorUtil.getAppScheduledExecutorService().schedule(...)` with the returned `ScheduledFuture` disposed via the invocation.
- Line 466–476: raw `Thread` test-tree polling. Replace with a coroutine launched in `AgentService.serviceScope` that respects cancellation.

### Task 2.4 — Convert `handleDescriptorReady` to use invocation

**File:** `JavaRuntimeExecTool.kt:424–492`.

Change signature: `private fun handleDescriptorReady(descriptor: ..., invocation: RunInvocation, ...)`. Use `invocation.attachListener(...)` for the `TestResultsViewer.EventsListener`. Use `invocation.attachProcessListener(...)` for the `ProcessAdapter` that streams text.

### Task 2.5 — Same refactor in `CoverageTool.executeRunWithCoverage`

**File:** `CoverageTool.kt:130–319`.

Same pattern — replace manual refs + raw `Timer.schedule` (line 214) with `RunInvocation`. The coverage listener `Disposable` (line 302) already exists; fold it into the invocation.

### Task 2.6 — `processNotStarted` disconnect timing

**File:** `JavaRuntimeExecTool.kt:329–359` + `CoverageTool.kt:249–270`.

Currently: `ExecutionListener` subscription is disconnected when `processNotStarted` or `processStarted` fires. Fine. But the 5-minute `Thread.sleep` disconnect watchdog (JavaRuntimeExecTool line 362–367) can disconnect prematurely if the build genuinely takes > 5 min.

Change: Rely on the invocation's dispose for cleanup. Drop the manual watchdog entirely — invocation disposes when the coroutine returns (success or exception or timeout).

### Task 2.7 — Leak regression test

**New file:** `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/RunInvocationLeakTest.kt`.

Approach:
1. Use `com.intellij.testFramework.LeakHunter.checkLeak()` (IntelliJ's built-in leak detector).
2. Run `java_runtime_exec.run_tests` 100 times against a minimal JUnit test class in a test fixture project.
3. Assert: number of `RunContentDescriptor` instances held by `RunContentManager` is bounded (ideally ≤ 2 — one currently running + one reserved by IntelliJ's cache).
4. Assert: number of `TestResultsViewer.EventsListener` instances held by the current viewer is 0 after disposal.

If `LeakHunter` isn't viable (often requires light-tests framework setup), substitute: `WeakReference` to each `RunContentDescriptor`, force-GC, assert queue empty.

---

## Validation

```bash
./gradlew :agent:test --tests "*RunInvocation*" --tests "*Leak*"
./gradlew :agent:test --tests "*Runtime*" --tests "*Coverage*"  # re-run existing tests to catch regressions
./gradlew verifyPlugin
```

## Manual verification

In sandbox IDE:

1. **Leak test:** Run `runIde`, trigger agent's `run_tests` 20× in one session. Open VisualVM → Heap Dump → search for `RunContentDescriptor`. Expect: ≤ 2 instances, not 20+.
2. **Manual run after agent:** After agent finishes, use the Gutter green arrow to run a test manually. Should succeed, not "initializationError".
3. **Session end cleanup:** Click "New chat" in the agent panel. All agent-originated Run tool window tabs should disappear.

## Exit criteria

- No raw `Thread { ... }` spawns in `JavaRuntimeExecTool.kt` or `CoverageTool.kt` (grep returns 0).
- All `MessageBusConnection`, `TestResultsViewer.EventsListener`, `ProcessListener` instances attached inside these tools are registered for disposal via `RunInvocation`.
- Manual-test regression cannot be reproduced after 50 agent runs.
- `LeakHunter` (or WeakReference proxy) test confirms no leak.

## Follow-ups (deferred)

- Debug tool equivalent of `RunInvocation` → Phase 5 will define `DebugInvocation`.
- Coverage `CoverageSuiteListener` via reflection — stable enough for now; if it breaks on a platform upgrade, move to direct API dependency.
- Progress reporting while long-running builds block — `BackgroundableTask` integration → separate spike.
