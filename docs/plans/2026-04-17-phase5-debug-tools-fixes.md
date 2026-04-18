# Phase 5 — Debug Tools Fixes

**Fixes:** Callback timeout races, session-listener leaks, output truncation, string-matching session resolution fragility.
**Audit source:** `docs/research/2026-04-17-debug-tools-audit.md`.
**Preconditions:** Phase 3 (RunInvocation / Disposable infrastructure). Can run in parallel with Phases 4 and 6.
**Estimated:** 2 days. Medium complexity.

---

## Context

The debug tools (`debug_breakpoints`, `debug_step`, `debug_inspect`) are functionally correct for happy-path usage (step over/into, set breakpoint, evaluate simple expressions). But the audit identified three structural risks:

1. **Callback timeout races.** `AgentDebugController` wraps XDebugger's async callbacks (evaluate, stack-frame children, variable presentation) with `withTimeoutOrNull(5–10s)`. If the timeout fires, the callback still invokes later on a dead `CancellableContinuation` → IllegalStateException in logs, possible memory corruption.
2. **`XDebugSessionListener` leaks.** Listeners added with `session.addSessionListener(listener)` but no corresponding `removeSessionListener`. Multi-cycle debug sessions stack listeners.
3. **Output truncation not spilled.** Thread dumps (50+ threads × stack traces), variable trees (deep object graphs), memory-view dumps all hard-truncate at 3K–10K chars via `truncateOutput()`. Large debug outputs lose data silently.

Plus one "semi-serious" issue:

4. **`IdeStateProbe.debugState()` uses `sessionName == sessionId` fallback.** When two debug sessions have identical display names (`"MyApp"` and `"MyApp (1)"` after restart), the lookup can resolve to the wrong session.

---

## Scope

**In:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/platform/IdeStateProbe.kt`

**Out:**
- Debug skill content (`interactive-debugging.md`) — separate concern.
- Cross-IDE debug protocol (service↔simulator IPC) — tracked separately.

---

## Task list

### Task 4.1 — Fix callback timeout races

**Files:** `AgentDebugController.kt` — search for `withTimeoutOrNull(` in combination with `suspendCancellableCoroutine`.

Problem: Pattern used in multiple places:
```kotlin
withTimeoutOrNull(10_000) {
    suspendCancellableCoroutine { cont ->
        evaluator.evaluate(expr, object : XEvaluationCallback {
            override fun evaluated(value: XValue) {
                if (cont.isActive) cont.resume(value)  // ← OK but late callback still enters
            }
            override fun errorOccurred(error: String) {
                if (cont.isActive) cont.resumeWithException(...)
            }
        }, position)
    }
}
```

When `withTimeoutOrNull` fires at 10s, the coroutine cancels. But the callback held by `XDebugger` still fires 2s later — enters `if (cont.isActive)`, which is false, so the call is a no-op BUT the XValue reference is now leaked (the evaluator never got a signal that we're done).

Fix:
1. Add `cont.invokeOnCancellation { evaluator.cancel() }` — but `XDebuggerEvaluator.evaluate` doesn't expose a cancel hook. Instead, wrap the callback in a `CancellableEvaluationCallback` that sets a `stopped: AtomicBoolean`; the XDebugger-facing callback methods check the flag before doing work.
2. Apply the same pattern to `XStackFrame.computeChildren`, `XValue.computePresentation`, `XValue.computeChildren`.

Template helper in `AgentDebugController.kt`:
```kotlin
private suspend fun <T> awaitCallback(
    timeoutMs: Long,
    register: (callbackWrapper: AtomicBoolean, resume: (T) -> Unit, resumeErr: (Throwable) -> Unit) -> Unit
): T? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        val stopped = AtomicBoolean(false)
        cont.invokeOnCancellation { stopped.set(true) }
        register(
            stopped,
            { value -> if (!stopped.getAndSet(true) && cont.isActive) cont.resume(value) },
            { err -> if (!stopped.getAndSet(true) && cont.isActive) cont.resumeWithException(err) }
        )
    }
}
```

### Task 4.2 — Fix session-listener leaks

**File:** `AgentDebugController.kt` — search for `addSessionListener`.

Every `addSessionListener` needs a matching `removeSessionListener` via `Disposable`. The `XDebugSession.addSessionListener(listener, disposable)` overload exists — use it:

```kotlin
session.addSessionListener(listener, sessionDisposable)
```

Where `sessionDisposable` is either:
- A `DebugInvocation` (new class, mirrors `RunInvocation` from Phase 2) — preferred.
- The session itself if short-lived (`session` implements `Disposable` in newer APIs).

### Task 4.3 — Create `DebugInvocation` mirroring `RunInvocation`

**New file:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInvocation.kt`.

Same pattern as Phase 3's `RunInvocation` but specialized for debug sessions. Wraps:
- Session-scope disposables for listeners.
- A `MutableSharedFlow<DebugEvent>(replay=1)` for session-event streaming (already used in `AgentDebugController` but ad-hoc).
- Cleanup of attached listeners, evaluator callbacks, and the `MutableSharedFlow` subscribers.

### Task 4.4 — Wire `ToolOutputSpiller` for debug output (depends on Phase 7 wiring)

**Files:** `DebugInspectTool.kt` — thread-dump, variable-tree, memory-view actions.

Currently: hard-truncate at 3K–10K chars. Mark tasks 4.4.1–4.4.4 as **blocked-on-Phase-7** but list them here so they're not forgotten:

- `debug_inspect.thread_dump` — output config `DEFAULT` with grep enabled; threshold 30K.
- `debug_inspect.get_variables` — same.
- `debug_inspect.memory_view` — same.
- `debug_inspect.evaluate` — smaller (usually one value); keep at current cap.

### Task 4.5 — Replace string-match session resolution with session UUID

**File:** `IdeStateProbe.kt` — `debugState()` helper.

Currently: session lookup by `sessionName` string compare. Breaks when names collide.

Fix:
1. Track started sessions in `AgentService` via a `ConcurrentHashMap<String, WeakReference<XDebugSession>>` keyed by an agent-generated UUID.
2. When agent starts a session via `DebugBreakpointsTool.start_session`, return the UUID as the handle.
3. Subsequent `debug_step` / `debug_inspect` calls accept `session_id` (UUID) and resolve via the map.
4. For user-started sessions not in the map, fall back to `XDebuggerManager.currentSession` or the only active one.

### Task 4.6 — Validate breakpoint type coverage

**File:** `DebugBreakpointsTool.kt` — `list_breakpoints` action.

Current CLAUDE.md claim: "shows line, exception, field watchpoint, and method breakpoints". Verify by test — for each type, set via the tool, then call `list_breakpoints` and assert presence.

Types to verify:
- `XLineBreakpointType`
- Java exception breakpoints (`com.intellij.debugger.breakpoints.JavaExceptionBreakpointType`).
- Field watchpoints (`JavaFieldBreakpointType`).
- Method breakpoints (`JavaMethodBreakpointType`).

### Task 4.7 — Unit tests for callback-race fix

**New file:** `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugControllerTest.kt`.

Scenario: call `evaluate(expression)` with a mock evaluator that delays 20s before calling back. Assert:
1. The coroutine returns `null` at the 10s timeout.
2. No exception logged from the late callback.
3. The `stopped` flag is observable and set to `true` post-cancellation.

---

## Validation

```bash
./gradlew :agent:test --tests "*Debug*"
./gradlew verifyPlugin
```

Manual: in sandbox IDE, set a conditional breakpoint, trigger it, run a slow evaluation (`Thread.sleep(30000)`), let the tool timeout. Check logs: no `IllegalStateException` from late callback. Check VisualVM: no growing count of `XDebugSessionListener` instances after 20 debug cycles.

## Exit criteria

- All `withTimeoutOrNull + suspendCancellableCoroutine` pairs in `AgentDebugController.kt` use the `awaitCallback` helper.
- All `addSessionListener` sites have a corresponding removal via `DebugInvocation`.
- `debugState()` resolves by UUID; string-match fallback kept for unknown (user-started) sessions only.
- Regression test confirms no late-callback exception.

## Follow-ups

- Task 4.4 (spiller wiring) → Phase 7.
- Smart step-into → deferred, tracked in `project_missing_debug_tool_features.md`.
- Dependent breakpoints / step filters → same.
