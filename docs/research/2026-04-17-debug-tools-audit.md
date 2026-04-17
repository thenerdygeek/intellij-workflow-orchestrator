# Debug Tools Audit — XDebugger Contract Compliance & Lifecycle Leaks

**Date:** 2026-04-17
**Scope:** `debug_breakpoints`, `debug_step`, `debug_inspect`, and the shared infrastructure (`AgentDebugController`, `IdeStateProbe`)
**Companion doc:** `2026-04-17-runtime-test-tool-audit.md` (documents systemic issues in runtime/test tools; similar patterns found here)

---

## 1. Executive Summary

The debug tools correctly implement **core stepping, evaluation, and breakpoint CRUD** against IntelliJ's XDebugger API. However, they exhibit **three architectural risks**:

1. **No lifecycle cleanup** — `XDebugSessionListener` registered but never removed; `Disposable` hygiene is missing. Sessions themselves are tracked in `AgentDebugController` but the sessions' `Disposable` ownership is unclear.
2. **Async callback timeouts without cancellation propagation** — `XValue.computePresentation`, `XExecutionStack.computeStackFrames`, and `XDebuggerEvaluator.evaluate` callbacks wrap 5-10 second timeouts via `suspendCancellableCoroutine`, but the **callback is never cancelled** if the timeout fires; the callback will still invoke later and attempt to resume an already-dead continuation.
3. **Output truncation not wired to ToolOutputSpiller** — `thread_dump`, `memory_view` stack traces, and variable trees are hard-truncated at 3000–10000 chars via `truncateOutput()`. Large debug outputs (deep object graphs, multi-threaded dumps) are silently lost rather than spilled to disk.

Additionally, **IdeStateProbe correctly solves a critical bug** — prior code ignored user-started debug sessions, only seeing agent-registered sessions — but the fix relies on `sessionName` string matching which is fragile when multiple sessions have the same display name.

**Incident severity:** Medium (rare in practice — debugging sessions are short-lived and users don't usually debug while other debuggers are active, but the callback leak + timeout race could cause orphaned debugger state on repeated debug cycles).

---

## 2. Per-Tool Audit Against XDebugger Contract

### 2.1 `AgentDebugController` — Session Management & Callback Wrapping

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt`

#### Strengths

- **Session tracking** (`sessions: ConcurrentHashMap`, line 37) — Thread-safe per-session storage via UUID ids
- **Active session management** (`activeSessionId`, line 42) — tracks "current" session for default-parameter use cases
- **Pause event flow** (`pauseFlows: ConcurrentHashMap<String, MutableSharedFlow>`, line 38) — Clean async abstraction wrapping callback into coroutine-friendly channel
- **Proper listener attachment** (`addSessionListener` in `registerSession`, lines 56–85) — Listens for `sessionPaused()`, `sessionResumed()`, `sessionStopped()`

#### Critical Issues

**Issue 1: Session listeners are never removed** (lines 56–85, 449–475)

The XDebugSessionListener registered in `registerSession()` has no removal counterpart. If the same agent runs multiple debug cycles, listeners stack on the same session.

```kotlin
session.addSessionListener(object : XDebugSessionListener {
    override fun sessionPaused() { flow.tryEmit(...) }
    override fun sessionResumed() { flow.resetReplayCache() }
    override fun sessionStopped() { sessions.remove(sessionId); ... }
})
```

**Contract violation:** XDebugSessionListener is **not** `Disposable`; there is no API to remove it. The correct pattern is:

```kotlin
val connection = project.messageBus.connect(disposable)
connection.subscribe(XDebuggerManager.TOPIC, debuggerListener)
// On cleanup: disposable.dispose() → auto-removes listener
```

**Consequence:** Over 10+ debug cycles in a single IDE session, the same `sessionPaused()` callback fires 10+ times, all routing through the same `pauseFlows[sessionId]` MutableSharedFlow. If the agent crashed mid-cycle, the flow is never disposed and continues holding memory.

**Fix surface:** Wrap listener registration in a `Disposable` tied to the session. Either:
- Option A: Use `project.messageBus.connect()` with a project-level Disposable (not session-level)
- Option B: Keep an in-memory map `sessionListeners: Map<String, XDebugSessionListener>` and document that `removeAgentBreakpoints()` is the cleanup path (but the method doesn't actually remove listeners)

**Issue 2: Callback wrapping has timeout + race** (lines 145–182, 255–297, 320–348)

Three methods wrap XDebugger callbacks via `suspendCancellableCoroutine`:

- `getStackFrames()` — wraps `XExecutionStack.computeStackFrames(XStackFrameContainer)` (lines 145–182)
- `computeChildren()` (private, line 248) — wraps `XValueContainer.computeChildren(XCompositeNode)` (lines 261–297)
- `resolvePresentation()` (private, line 320) — wraps `XValue.computePresentation(XValueNode)` (lines 324–347)

All three patterns are identical: no `withTimeoutOrNull` — the callbacks are fire-and-forget without timeout. BUT:

Lines 199–201 (`findXValueByName`), 255, 321 **do** use `withTimeoutOrNull(5000L)`. If the timeout fires:

```kotlin
return @withTimeoutOrNull DebugState.Paused(target)
```

The coroutine exits. But the callback is still registered in the XDebugger machinery and will fire later, invoking `.resume()` on a dead continuation. **This is a race condition.**

Example from `findXValueByName` (lines 198–246):

```kotlin
suspend fun findXValueByName(frame: XStackFrame, name: String): XValue? {
    return withTimeoutOrNull(5000L) {
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { /* timeout cleanup */ }  // ← Not actually called!
            // ...
            frame.computeChildren(object : XCompositeNode {
                override fun addChildren(children: XValueChildrenList, last: Boolean) {
                    // If timeout fires before this callback:
                    // 1. cont.resume(found) returns an error (continuation already completed)
                    // 2. That error is silently swallowed
                    if (last) cont.resume(found)
                }
            })
        }
    } ?: Pair("unknown", "<timed out>")
}
```

**Contract violation:** `invokeOnCancellation` is invoked when the **coroutine is cancelled**, but `withTimeoutOrNull` doesn't cancel the coroutine — it just **returns null** and lets the scope exit. The continuation is left suspended, and the callback will fire into a completed continuation.

**Fix surface:** Either:
- Option A: Never use `withTimeoutOrNull` + `suspendCancellableCoroutine` together. Instead, implement timeout inside the callback: start a timer on entry, check elapsed time before invoking `.resume()`.
- Option B: Don't wrap XDebugger callbacks at all — keep the callback-based API and expose `suspend` functions that wrap channels/flows instead.
- Option C: Use a WeakReference or a `Disposable` to detect if the continuation has been garbage-collected, and silently ignore the callback if so.

**Issue 3: Session Disposable ownership unclear** (line 34, `implements Disposable`)

`AgentDebugController` is `Disposable`, and its `dispose()` (lines 449–475) calls `session.stop()` and removes breakpoints. But:

- Who owns the lifecycle of `AgentDebugController` itself?
- When is it disposed?
- If the project closes while `AgentDebugController` is still alive (e.g., dangling reference from a tool), breakpoints persist in the closed project's cache.

**Consequence:** The contract says `XBreakpoint` persists in the `XBreakpointManager` and survives project close. If the agent's controller isn't disposed, the breakpoints remain until IDE restart.

**Fix surface:** `AgentDebugController` should be instantiated per-session (not per-project) or tied to an explicit session-lifecycle Disposable.

#### Minor Issues

- **Line 209:** `bp.line == zeroBasedLine` — assumes all XLineBreakpoint types use 0-based line numbers. The contract uses 0-based, but some language plugins (e.g., Kotlin) may override.
- **Line 243:** `isObsolete()` on `XCompositeNode` — never implemented in the callback. Should return false (always valid).
- **Line 345:** `XValuePlace.TREE` hardcoded — should be parameterized if the tool needs VARIABLES_VIEW or TOOLTIP.

---

### 2.2 `DebugBreakpointsTool` — Breakpoint CRUD & Session Launch

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt`

#### Strengths

- **Comprehensive breakpoint types** — Line (lines 218–323), method (lines 327–437), exception (lines 441–496), field watchpoint (lines 500–575) all supported
- **4-way breakpoint listing** — `list_breakpoints` distinguishes line, exception, method, and field (lines 633–749) via type checking
- **Proper EDT dispatch** — `WriteAction.compute { ... }` (lines 236, 393, 453, 534, 591) ensures breakpoint modifications are thread-safe
- **Conditional breakpoints** — Condition expressions stored via `bp.conditionExpression = XExpressionImpl.fromText(condition)` (line 259)
- **Log breakpoints** — Log expression + suspend policy override (lines 261–272) for non-suspending breakpoints
- **Pass count via reflection** (lines 278–294) — Gracefully falls back if `COUNT_FILTER` API unavailable

#### Critical Issues

**Issue 1: Session launch has `processNotStarted` race** (lines 751–844)

The `start_session` action launches a debug session and waits for the first breakpoint hit via:

```kotlin
withTimeoutOrNull(30_000L) {
    suspendCancellableCoroutine<String> { cont ->
        val connection = project.messageBus.connect()
        connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) {
                val session = debugProcess.session
                val id = controller.registerSession(session)
                connection.disconnect()
                if (cont.isActive) cont.resume(id)
            }
        })

        invokeLater {
            val executor = DefaultDebugExecutor.getDebugExecutorInstance()
            val env = ExecutionEnvironmentBuilder.create(project, executor, settings.configuration).build()
            ProgramRunnerUtil.executeConfiguration(env, true, true)

            // Add ExecutionListener for build failures
            val buildConn = project.messageBus.connect()
            buildConn.subscribe(com.intellij.execution.ExecutionManager.EXECUTION_TOPIC,
                object : com.intellij.execution.ExecutionListener {
                    override fun processNotStarted(executorId: String, e: ExecutionEnvironment) {
                        if (e == env) {
                            buildConn.disconnect()
                            connection.disconnect()
                            if (cont.isActive) cont.resume("")
                        }
                    }
                    // ...
                }
            )
        }
    }
}
```

**Contract violation:** The code correctly listens for both `XDebuggerManager.processStarted` and `ExecutionManager.processNotStarted`, following the runtime-test audit's recommendations. However:

1. **Two separate `MessageBusConnection`s** are created (`connection` and `buildConn`). If the timeout fires, `connection.disconnect()` is called but `buildConn` may not be if the race is tight.
2. **`invokeLater` is not guaranteed to run before the timeout.** If EDT is blocked, the 30-second timeout could fire before `ProgramRunnerUtil.executeConfiguration(...)` even starts.
3. **No `ProcessHandler` listener.** The code waits for `processStarted` callback from XDebugger, but if the **debug process crashes on startup** (e.g., missing JDWP args), `processStarted` never fires. The timeout will silently elapse and return empty string.

**Fix surface:**
- Use a single connection for both topics via `subscribe(..., [ExecutionManager.TOPIC, XDebuggerManager.TOPIC])` (multi-topic subscription) or create a shared Disposable to clean up both on completion.
- Increase timeout to 60s and document that 30s is for fast breakpoint hits only. For slow startup, timeout extends.
- Add `ProcessListener` fallback: if no `processStarted` after 5s, subscribe to the ProcessHandler's termination event. If the process terminates without `processStarted`, return an error.

**Issue 2: Method breakpoint reflection is fragile** (lines 278–294)

Pass count is applied via reflection:

```kotlin
try {
    javaBp.javaClass.getMethod("setCountFilterEnabled", Boolean::class.javaPrimitiveType).invoke(javaBp, true)
    javaBp.javaClass.getMethod("setCountFilter", Int::class.javaPrimitiveType).invoke(javaBp, passCount)
} catch (_: Exception) { /* API not available */ }
```

If the Java debugger plugin changes (IntelliJ 2026 vs 2025), these methods may not exist or may have different signatures. The code silently ignores the absence — the breakpoint is created, but the pass count is not applied. The LLM thinks it set pass_count=100 but it didn't.

**Fix surface:** Either (1) use `@Suppress("UNCHECKED_CAST")` reflection of `JavaBreakpointProperties` directly (same pattern as exception breakpoints, line 464) or (2) document that pass_count requires Java plugin 2025.1+.

#### Medium Issues

- **Line 246:** `resolveBreakpointType(vFile.name)` — Infers breakpoint type from file extension only. For polyglot projects (Java + Kotlin), a `.kt` file may still want a Java breakpoint if it's in a Java-interop module. Should check PSI language instead.
- **Line 1015:** `findFieldLineInDocument()` uses regex pattern matching (`trimmed.contains(fieldName)`) — too broad. Could match field names in comments or strings. Should use PSI `PsiField.getTextOffset()` like the method breakpoint path does.
- **Lines 856–870:** `attach_to_process` hardcodes `SERVER_MODE = false` and `USE_SOCKET_TRANSPORT = true`. These are correct for JDWP attach, but there's no validation that the target JVM is actually listening on the specified port. The 30-second timeout will silently elapse if the port is wrong.

---

### 2.3 `DebugStepTool` — Session Resolution & Step Actions

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt`

#### Strengths

- **Excellent session resolution** — `IdeStateProbe.debugState()` (lines 140, 155) fixes the prior bug where user-started sessions were invisible
- **Dual resolution modes** — `requireSession()` (line 139) for pause-agnostic actions (resume, stop) vs `requireSuspendedSession()` (line 154) for step actions
- **Proper error reporting** — Distinct errors for "no session", "ambiguous", "running but not suspended" (lines 164–187)
- **Step action wiring** (lines 259–267) — Delegates to `executeStep()` helper from `DebugStepUtils.kt` which is clean
- **Automatic top-frame variables** (lines 72–79 in `DebugStepUtils.kt`) — After each step, auto-includes the top-frame's variables (depth 1) so the agent doesn't need a separate `get_variables` call

#### Issues

**Issue 1: `run_to_cursor` has no JDI source position validation** (lines 320–361)

```kotlin
val position = withContext(Dispatchers.EDT) {
    val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
        ?: return@withContext null
    XDebuggerUtil.getInstance().createPosition(vFile, line - 1)
}
session.runToPosition(position, false)
```

If the file isn't in the source path of the debugged JVM (e.g., user asks to run to a library source that isn't attached), `createPosition()` may create an invalid position. The subsequent `session.runToPosition()` **silently ignores invalid positions** and execution continues. The agent reports "Reached file:line" even though the position was never meaningful.

**Fix surface:** After creating the position, check if the position's file is mapped in the debug process's source locator. If not, return an error before calling `runToPosition()`.

**Issue 2: Ambiguous session error doesn't help the user pick one** (lines 182–187, 235–240)

When multiple sessions exist, the error lists session names:

```
"Multiple debug sessions are active (3: main, test-suite, server). Pass session_id to disambiguate."
```

But the agent (and user) don't know which session is at which breakpoint. A better error would include the session state:

```
"Multiple debug sessions are active:
  - session-1 (main): paused at MyClass.java:42
  - session-2 (test-suite): running
  - session-3 (server): paused at Server.kt:100
Choose one via session_id."
```

**Fix surface:** When `AmbiguousSession`, fetch each session's suspend context and current position, include that in the error message.

---

### 2.4 `DebugInspectTool` — Expression Evaluation & Variable Inspection

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt`

#### Strengths

- **Expression evaluation with timeout** (lines 244–286) — `withTimeoutOrNull(10s)` wraps `controller.evaluate()` which is correct contract following
- **Recursive variable traversal** (lines 334–400) — `getVariables()` walks variable trees with configurable depth (default 2, max 4)
- **Thread dump via JDI** (lines 501–580) — Uses `executeOnManagerThread()` to safely call JDI APIs on the manager thread
- **Memory view for live instances** (lines 584–651) — Counts instances via `vm.instanceCounts()` and optionally lists first N
- **Hot swap integration** (lines 655–725) — Calls `HotSwapUI.reloadChangedClasses()` with proper callback handling
- **Force return and drop frame** (lines 729–921) — Advanced JDI operations wrapped with full error handling

#### Critical Issues

**Issue 1: Evaluate callback has timeout + race (lines 373–398)**

```kotlin
val evalResult: Result<XValue> = suspendCancellableCoroutine { cont ->
    evaluator.evaluate(
        expression,
        object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(result: XValue) {
                cont.resume(Result.success(result))
            }
            override fun errorOccurred(errorMessage: String) {
                cont.resume(Result.failure(RuntimeException(errorMessage)))
            }
        },
        null
    )
}
```

This is wrapped in `withTimeoutOrNull(EVALUATE_TIMEOUT_MS)` where `EVALUATE_TIMEOUT_MS = 10_000L` (line 1005). **Same race as in AgentDebugController:** the callback fires after timeout, resuming a dead continuation.

**Contract says:** `XDebuggerEvaluator.evaluate()` can take arbitrarily long (network eval, blocking call, etc.). The contract does **not** define a timeout. A 10-second timeout is reasonable for agent responsiveness, but the callback must be **cancelled** if timeout fires.

**Fix surface:** Use a `Job` to wrap the callback and cancel it explicitly:

```kotlin
val evalJob = Job()
val evalResult: Result<XValue> = try {
    withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            try {
                evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback { ... }, null)
            } catch (e: Exception) {
                evalJob.cancel(e)
            }
        }
    }
} finally {
    evalJob.cancel()
}
```

Or use a `AtomicBoolean` to gate the callback:

```kotlin
val shouldResume = AtomicBoolean(true)
val evalResult = withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
    suspendCancellableCoroutine { cont ->
        evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(result: XValue) {
                if (shouldResume.getAndSet(false)) cont.resume(Result.success(result))
            }
        }, null)
    }
}
if (!shouldResume.get()) return ToolResult("Timeout", ..., isError=true)
```

**Issue 2: Variable tree truncation at 3000 chars** (lines 385–387)

```kotlin
var content = sb.toString()
if (content.length > MAX_OUTPUT_CHARS) {
    content = truncateOutput(content, MAX_OUTPUT_CHARS) +
        "\n(use variable_name to inspect specific variable)"
}
```

Where `MAX_OUTPUT_CHARS = 3000` (line 1014). For deeply nested object graphs (e.g., Hibernate proxies with lazy collections, Jackson serializers), the tree is silently truncated. The agent doesn't know whether the truncation hid critical fields.

**Fix surface:** Route through `ToolOutputSpiller` instead:

```kotlin
if (content.length > TRUNCATION_THRESHOLD) {
    content = ToolOutputSpiller.spill(toolName="debug_inspect", content) + "\n(full tree saved to disk)"
}
```

**Issue 3: `memory_view` instance counting via JDI without `canGetInstanceInfo` fallback** (lines 598–614)

```kotlin
if (!vm.canGetInstanceInfo()) {
    return@executeOnManagerThread ToolResult(
        "VM does not support instance info (canGetInstanceInfo=false). ...",
        "Not supported",
        ...
        isError = true
    )
}
```

`canGetInstanceInfo()` returns false for:
- Remote JVMs (JDWP doesn't support HeapReferencingObjects)
- J9 JVM (IBM JVM, not HotSpot)
- JVMs with `--enable-preview` in some versions

For those VMs, the agent can't use `memory_view` at all. A fallback (counting objects via `vm.classesByName()` + iterating loaded instances) would be slow but better than "not supported".

**Fix surface:** Document that `memory_view` requires HotSpot JVM. Alternatively, fall back to a slower enumeration: `allThreads()` → inspect each thread's local refs → count manually (O(thread count × local vars), slow but works).

#### Medium Issues

- **Line 945–957:** `inferReturnType()` heuristic for `force_return` — infers type from return value string. If the agent passes `"1.5"`, the tool infers `double`. But the method's return type is `float`. Mismatch causes `InvalidTypeException`. Should require explicit `return_type` parameter.
- **Line 979–990:** `inferDaemon()` uses reflection `thread.isDaemon()` — fragile. Should use JDI standard method if available (`ThreadReference.isThreadGroup()` alternative).
- **Lines 1019–1027:** Thread status constants (`THREAD_STATUS_ZOMBIE`, etc.) are JDI standard codes. But they're hardcoded. Should use JDI enum if available.

---

### 2.5 `IdeStateProbe` — Platform Truth Query (Excellent)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/platform/IdeStateProbe.kt`

#### Strengths

- **Solves critical bug** — Prior code only saw agent-registered sessions. Now `XDebuggerManager.debugSessions` is always consulted (lines 51–52, 68)
- **Clear resolution order** (lines 54–62) — Registry lookup → sessionName match → currentSession → single session fallback
- **Coarse state classification** (lines 74–89) — Sealed class `DebugState` with `Paused`, `Running`, `NoSession`, `AmbiguousSession` is clean API
- **No caching** — Always queries platform state, never stale

#### Issues

**Issue 1: `sessionName` string matching is fragile** (line 57)

```kotlin
all.firstOrNull { it.sessionName == sessionId }
```

If the user starts two debug sessions for the same run config, they get names like `"MyApp"` and `"MyApp (1)"`. If the agent has `sessionId = "MyApp"` (from a prior run), the lookup will incorrectly match the first session even if the user intended to target the second.

**Fix surface:** If `registryLookup` returns null, fall back to **exact name match first**, then pattern match. Or better: have the agent tools always pass the UUID from `AgentDebugController` (never rely on sessionName string match).

**Issue 2: `currentSession` may be the wrong session for multi-debug workflows** (line 59)

`XDebuggerManager.currentSession` returns the **user-focused** session (the one the user is actively clicking in). But if the user is debugging two services simultaneously and switches focus to Service A while the agent is inspecting Service B, `currentSession` silently changes. The tool may start inspecting the wrong service.

**Fix surface:** When the agent starts a session, store its UUID from `AgentDebugController` and always pass that sessionId explicitly. Don't fall back to `currentSession`.

---

## 3. Output Spilling Issues

All debug tools that produce variable/stack/memory dumps use `truncateOutput(content, CAP)` with hardcoded caps:

| Action | Cap | Example output |
|--------|-----|-----------------|
| `get_variables` | 3000 | Variable tree with children |
| `get_stack_frames` | (no cap) | Stack trace list |
| `thread_dump` | (no cap) | Thread + frame listings |
| `memory_view` | (no cap) | Instance list |

**Consequence:** A thread dump with 50 threads × 30 frames × 1KB per frame = 1.5 MB, hard-truncated to ~3KB if wrapped in `get_variables`. The LLM sees only the top frames of thread 0 and thread 1.

**Fix surface:** Route through `ToolOutputSpiller`:

```kotlin
var content = buildString { /* assemble debug output */ }
if (content.length > SPILL_THRESHOLD) {
    content = ToolOutputSpiller.spill(toolName="debug_inspect", content) + "\nFull output saved to: ${spiller.path}"
}
```

---

## 4. Missing Scenarios (Contract vs Implementation)

Signals the contract lists that **no current debug tool emits**:

| # | Signal | Consequence to Agent |
|---|--------|---------------------|
| 1 | `XDebugSession.isSuspended` is false but user set a breakpoint | Agent calls `step_over`, gets "not suspended" error, must manually call `pause` first |
| 2 | Breakpoint set on a non-executable line (comment, blank) | Breakpoint creation silently fails; agent thinks breakpoint is set but `list_breakpoints` doesn't show it |
| 3 | Evaluation expression contains syntax error | XDebuggerEvaluator.XEvaluationCallback.errorOccurred() fires; tool returns error. **Correct.** |
| 4 | JDI operation fails due to missing capability (canPopFrames, canForceEarlyReturn) | Tool checks capability before operating and returns error. **Correct.** |
| 5 | Session is a Python debug process (PyDebugProcess) | `debug_step` fork/step_into fire into Python runtime, which doesn't support stepping into C-code boundaries. Tool checks via `isPythonDebugSession()`. **Correct.** |
| 6 | Hot swap fails due to structural changes (method signature changed) | HotSwapUI callback reports `onFailure()`. Tool returns error. **Correct.** |
| 7 | Run-to-cursor position is invalid or unreachable | Session.runToPosition() ignores invalid positions silently. Agent thinks it worked. **BUG.** |
| 8 | Multiple sessions, agent tries to step default session but user switched focus | Agent steps the now-wrong session. **BUG.** |
| 9 | Breakpoint condition expression is invalid (syntax error) | Condition stored; on hit, XDebugger evaluates condition. If eval fails, breakpoint still triggers. No pre-validation. |
| 10 | Stack frame is native method (JNI) | `force_return` fails with `NativeMethodException`. Tool returns error. **Correct.** |

---

## 5. Lifecycle & Disposable Hygiene

### Session Lifecycle

1. **Agent starts session** → `registerSession(xSession)` (AgentDebugController, line 48) → listener attached, stored in `sessions` map
2. **Session paused** → listener fires `sessionPaused()` → event emitted to `pauseFlows[sessionId]` → agent's `waitForPause()` returns
3. **Session ends (user stops, user closes IDE, breakpoint hits exception)** → listener fires `sessionStopped()` → `sessions.remove(sessionId)`, `pauseFlows.remove(sessionId)`
4. **Agent disposes** → `AgentDebugController.dispose()` (line 449) → calls `session.stop()` on all tracked sessions → removes all breakpoints

**Leak vectors:**

- **XDebugSessionListener never removed** (lines 56–85) — Listener stays in `XDebugSession.listeners` even after session ends. If a new session is created with the same name, the old listener fires too.
- **MutableSharedFlow never disposed** — `pauseFlows[sessionId]` remains in memory. With 10 debug cycles, 10 flows accumulate.
- **Breakpoints persist after dispose** — AgentDebugController.removeAgentBreakpoints() removes tracked breakpoints, but if the IDE has other breakpoints with the same file/line (user-set), the XDebugger may keep a reference.

**Fix surface:**

```kotlin
private val disposables = ConcurrentHashMap<String, Disposable>()

fun registerSession(session: XDebugSession): String {
    val sessionId = "debug-${sessionCounter.incrementAndGet()}"
    sessions[sessionId] = session
    
    val disposable = Disposer.newDisposable("debug-session-$sessionId")
    disposables[sessionId] = disposable
    
    val connection = project.messageBus.connect(disposable)
    connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener { ... })
    
    // When session ends:
    connection.subscribe(sessionStopped event) {
        sessions.remove(sessionId)
        pauseFlows.remove(sessionId)
        Disposer.dispose(disposables.remove(sessionId))
    }
    return sessionId
}
```

---

## 6. Recommendations (Not a Fix Plan — Just Surface Area)

### Critical (Blocks Correctness)

- **R1.** Fix callback timeout race in `AgentDebugController.evaluate()` (line 373) — Use `AtomicBoolean` gate or explicit job cancellation. Prevents orphaned callback stack. File: `AgentDebugController.kt:373–398`
- **R2.** Fix callback timeout race in `AgentDebugController.findXValueByName()` (line 199) — Same pattern. File: `AgentDebugController.kt:199–246`
- **R3.** Fix session listener leak — Register listeners via `project.messageBus.connect(disposable)` with explicit Disposable lifecycle. File: `AgentDebugController.kt:56–85`
- **R4.** Validate `run_to_cursor` position before calling `session.runToPosition()` — Check source locator availability. File: `DebugStepTool.kt:334–344`

### High Priority (Prevents Data Loss)

- **R5.** Wire `ToolOutputSpiller` into `debug_inspect.get_variables` (truncation at 3000 chars) — Route large trees to disk. File: `DebugInspectTool.kt:385–387`
- **R6.** Wire `ToolOutputSpiller` into `debug_inspect.thread_dump` — Thread dumps with 50+ threads exceed truncation. File: `DebugInspectTool.kt:501–580`
- **R7.** Add fallback for `memory_view` on non-HotSpot JVMs — Count instances via manual thread enumeration (slow but works). File: `DebugInspectTool.kt:598–614`

### Medium Priority (Improves Reliability)

- **R8.** Fix `start_session` build-failure detection race — Use single MessageBusConnection for both EXECUTION_TOPIC and XDebuggerManager.TOPIC. File: `DebugBreakpointsTool.kt:767–810`
- **R9.** Add JDI source position validation to `run_to_cursor` — Ensure file is in the debugged JVM's source path. File: `DebugStepTool.kt:338–342`
- **R10.** Improve "ambiguous session" error message — Include session state (paused/running + position). File: `DebugStepTool.kt:182–187`
- **R11.** Document that pass_count requires Java plugin 2025.1+ — Reflection API stability warning. File: `DebugBreakpointsTool.kt:278–294`
- **R12.** Fix method breakpoint line inference — Use PSI language instead of file extension. File: `DebugBreakpointsTool.kt:929–948`

### Nice to Have (Future Work)

- **R13.** Add test coverage for callback timeout races — Build a mock XDebugSession that intentionally delays callbacks past timeout, verify continuation safety.
- **R14.** Investigate whether `XDebugSessionListener` can be wrapped in a Disposable-aware proxy to auto-remove on session stop (platform SDK feature request).
- **R15.** Add a SessionLifecycleTracker integration test that starts/stops 10 sessions back-to-back and verifies no listener stack-up via reflection inspection of the session.listeners field.

---

## 7. Comparison to Runtime-Test Tools Audit

| Issue Class | Runtime/Test Tools | Debug Tools | Severity |
|-------------|-------------------|------------|----------|
| Signal loss at pipeline stages | YES (compile, before-run, empty-suite) | NO (debug protocol is simpler) | Higher in runtime |
| Callback cleanup on timeout | N/A | YES (4 races) | High in debug |
| Listener leaks | YES (TestResultsViewer.EventsListener) | YES (XDebugSessionListener) | Equal |
| Output spilling not wired | YES (hard-truncate at 12K) | PARTIAL (thread_dump, memory_view OK; get_variables broken at 3K) | High in both |
| Descriptor lifecycle leak | YES (RunContentDescriptor) | N/A (no UI content manager) | Higher in runtime |
| Reflection-based API access | NO (uses XDebugger directly) | SOME (pass_count, daemon detection) | Medium in debug |

**Key difference:** Runtime/test tools lose **signal at the pipeline stage level** (compile errors, test count). Debug tools correctly implement stepping/evaluation but have **callback safety races** specific to the async callback pattern.

---

## 8. Not Covered by This Audit

- Unit test for debug tools (coverage is unknown — no test files found in module)
- Performance of variable tree traversal with very deep nesting (>100 levels)
- Interaction between hot swap and on-demand expression evaluation (could race)
- Python-specific debug behaviors (PyDebugProcess API contract)
- Integration with IntelliJ's Debug Console (chat-mode expressions)

---

## Summary

**Overall Assessment:** The debug tools are **functionally correct for the happy path** (set breakpoint, step, inspect variable, evaluate expression). However, they have **three structural risks**: (1) callback timeouts without proper cancellation, (2) listener lifecycle leaks, and (3) output truncation not routed through the spiller. The IdeStateProbe fix correctly solves visibility of user-started sessions, but string-matching sessionName is fragile. Recommend addressing R1–R4 immediately, then R5–R7 within the sprint.
