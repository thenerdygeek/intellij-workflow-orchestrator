# Debug Tool Deep Audit — Failure Scenarios, Edge Cases, and Gaps

Date: 2026-04-14
Files audited:
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepUtils.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/platform/IdeStateProbe.kt`

---

## Cross-Cutting Issues (Affect Multiple Tools)

### CRITICAL: Session Resolution Inconsistency Between Tools

`DebugInspectTool` resolves sessions via `IdeStateProbe.debugState()`, which queries `XDebuggerManager` directly and detects sessions started by the user (gutter debug, run menu). It handles ambiguous sessions gracefully.

`DebugStepTool` and `DebugStepUtils` resolve sessions via `controller.getSession()` only, which only finds sessions the agent started itself via `start_session` or `attach_to_process`. If a user starts a debug session through the IDE and the agent tries to step, it reports "No debug session found" even though the debugger is paused.

**Severity: CRITICAL** — The LLM will see contradictory information: `debug_inspect(evaluate)` works fine on a user-started session, but `debug_step(step_over)` says "no session." This is exactly the kind of inconsistency that causes doom loops.

### HIGH: DebugBreakpointsTool has NO session resolution at all for start_session feedback

`start_session` registers sessions via `controller.registerSession()`. Other breakpoint actions (add, remove, list) don't need sessions. But `DebugBreakpointsTool` itself does not import or use `IdeStateProbe`, which is correct for its scope — but the gap means breakpoints set by the agent before a user-started debug session won't associate with that session in any agent-visible way.

### MEDIUM: No "session exited/crashed" detection

When a debug session's process exits, `AgentDebugController.sessionStopped()` removes it from the registry. But `DebugStepTool` and `DebugInspectTool` only check for session existence, not for a "recently stopped" state. The LLM gets "No debug session found" with no indication that a session *was* active and *just stopped* — making it hard to distinguish "never started" from "crashed/exited."

### LOW: Test coverage is minimal

All three test files only cover: tool name, action enum count, required params, worker types, schema validity, missing action, and unknown action. Zero integration-level tests for any actual action behavior. This is acknowledged (mocking IntelliJ debug APIs requires `BasePlatformTestCase`), but it means every finding below is unverified in automated tests.

---

## 1. DebugBreakpointsTool (`debug_breakpoints`) — 8 Actions

### 1.1 `start_session`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| No run config exists for specified name | Returns error: "Run configuration not found: '{name}'. Use get_run_configurations to list available configurations." | Well handled — includes actionable guidance | OK |
| Build fails before debug session starts | Listens on `EXECUTION_TOPIC.processNotStarted()`, resumes continuation with empty string, returns "Debug session failed to start. Check run configuration, build errors, or port conflicts." | The error message is generic — build failure info (compiler errors) is not captured. LLM has to separately run `diagnostics` to find build errors. | MEDIUM |
| Port already in use (remote debugging via start_session) | `start_session` launches any run config, not just remote ones. If a web server config fails due to port conflict, it triggers the generic `processNotStarted` path. | Port conflict produces same generic error as build failure — no differentiation. LLM cannot distinguish port conflict from compilation error. | LOW |
| Debug session already running | A new session starts alongside the existing one. `registerSession()` assigns a new ID and sets it as `activeSessionId`. No warning about existing sessions. | No warning that another session exists. LLM won't know it's accumulating sessions. Could cause resource leaks. | MEDIUM |
| Timeout waiting for debugger to connect | `withTimeoutOrNull(30_000L)` returns null, which falls through to the `sessionId == null || sessionId.isEmpty()` check and returns "Debug session failed to start." | The timeout is only 30s for the session to appear, not for a breakpoint to be hit. The separate `wait_for_pause` handles breakpoint wait. But if the JVM takes >30s to start (large Spring app), this reports failure even if it's still launching. | MEDIUM |
| JVM crashes during startup | `processNotStarted` callback fires → generic failure message | No crash details (exit code, stderr) are captured. LLM has no way to know *why* it crashed. | MEDIUM |
| Python interpreter not found (PyCharm) | Same generic path — processNotStarted fires | Error message says "port conflicts" which is misleading for Python. The debug tools are gated to `hasJavaPlugin` by `ToolRegistrationFilter`, so this shouldn't happen in practice. | LOW (gated) |
| `wait_for_pause` specified but no breakpoint hit | Returns "Status: running (no breakpoint hit within {N}s)" | Well handled — clear message | OK |
| Build succeeds but processStarted never fires | 30s timeout returns generic failure | No differentiation — could be slow build vs actual failure | LOW |

### 1.2 `add_breakpoint`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| File doesn't exist or not in project | `PathValidator.resolveAndValidate()` catches traversal + existence. `LocalFileSystem.findFileByPath()` returns null → "File not found: {path}" | Well handled | OK |
| Line number < 1 | Explicit check: "Line number must be >= 1, got: {line}" | Well handled | OK |
| Line number beyond file length | `addLineBreakpointSafe()` returns null → "Failed to add breakpoint at {file}:{line} — line may not be breakpointable" | Message doesn't indicate "line exceeds file length." LLM won't know to try a different line vs. a different approach. | LOW |
| Breakpoint on blank/comment line | Same null return from `addLineBreakpointSafe` → same error message | The IntelliJ API may actually succeed (line breakpoints on comments are valid — they bind to the next executable line). No issue in practice. | OK |
| Breakpoint on dead code (optimizer removes) | Breakpoint will be set successfully but never hit. No detection possible at set-time. | No warning. The LLM will wait forever at `wait_for_pause`. This is inherent to debuggers and not fixable. | OK (inherent) |
| Condition expression is invalid syntax | `XExpressionImpl.fromText(condition)` stores the raw text. Syntax validation happens at runtime when the breakpoint is hit. | No upfront validation of condition syntax. LLM won't know the condition is broken until the breakpoint is hit and silently fails to pause. | MEDIUM |
| Log expression has side effects | No warning or detection | No warning. This is hard to detect statically and is standard debugger behavior. | LOW |
| Duplicate breakpoint on same line | `addLineBreakpointSafe` via `bpManager.addLineBreakpoint()` — IntelliJ's API may return existing breakpoint or add a second one depending on the type. | No duplicate detection. The agent could accumulate breakpoints on the same line. `list_breakpoints` would show them. | LOW |
| `pass_count` silently ignored on non-Java debugger | Try-catch around Java-specific API → `catch (_: Exception) { /* API not available */ }` | Silent failure. LLM thinks pass_count is set but it isn't. Should return a warning in the output. | MEDIUM |
| `pass_count` reflection fails on newer/older API | Inner try-catch on `getMethod("setCountFilterEnabled")` → `catch (_: Exception) { /* API not available */ }` | Same silent failure. | MEDIUM |

### 1.3 `method_breakpoint`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Class doesn't exist | PSI lookup returns `ClassNotFound` → "Class not found: {className}. Verify the fully qualified class name is correct." | Well handled | OK |
| Method doesn't exist | PSI lookup returns `MethodNotFound` with available method list → "Method '{name}' not found in {class}. Available methods: ..." | Well handled — includes available methods | OK |
| Overloaded methods | Sets breakpoint on `methods.first()` and warns: "NOTE: Method is overloaded ({N} variants). Breakpoint set on first match." | Well handled — warns about ambiguity | OK |
| Method is in library (not project code) | PSI search uses `GlobalSearchScope.allScope(project)` which includes libraries. Breakpoint will be set. | No warning that the method is in a library. Performance impact (method breakpoints are 5-10x slower) is already warned. | LOW |
| Performance impact | Explicit warning: "PERFORMANCE WARNING: Method breakpoints are 5-10x slower than line breakpoints. Use sparingly." | Well handled | OK |
| `watch_entry=false, watch_exit=false` | Explicit validation: "Both watch_entry and watch_exit are false — the breakpoint would never trigger." | Well handled | OK |

### 1.4 `exception_breakpoint`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Exception class doesn't exist in classpath | Breakpoint is set anyway. Output includes: "Note: No validation that '{class}' exists in the classpath — verify the class name is correct" | Explicitly warned — good. But the breakpoint will silently never trigger if the class doesn't exist. | LOW |
| Already have an exception breakpoint for same type | `bpManager.addBreakpoint()` may add a duplicate. No check for existing exception breakpoints. | Duplicate exception breakpoints accumulate silently. | LOW |
| Caught vs uncaught distinction | Parameters `caught` and `uncaught` both default to `true`. Properly applied to `JavaExceptionBreakpointProperties`. | Well handled | OK |
| Java debugger plugin not installed | `findBreakpointType(JavaExceptionBreakpointType::class.java)` returns null → "Java exception breakpoint type not available — Java debugger plugin may not be installed" | Well handled | OK |
| `exception_class` is blank | Explicit check: "exception_class cannot be blank" | Well handled | OK |

### 1.5 `field_watchpoint`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Field doesn't exist | `findFieldInClass()` returns null → "Could not find field '{name}' in class '{class}'... For Kotlin properties, use the property name" | Well handled — includes Kotlin hint | OK |
| Class doesn't exist | PSI lookup in `findFieldInClass()` returns null for class → falls through to `filePath`-based fallback. If no `filePath` provided, returns null → same "field not found" error. | Error message says "field not found" but the root cause is "class not found." Misleading. | MEDIUM |
| `watch_read=false, watch_write=false` | Explicit validation: "both watch_read and watch_write are false — watchpoint will never trigger" | Well handled | OK |
| Not applicable for Python | Debug tools are gated by `ToolRegistrationFilter` → requires `hasJavaPlugin`. Field watchpoints won't be offered in PyCharm. | Gated at registration level — OK. But if somehow called, it would try Java PSI and fail with a cryptic error. | LOW (gated) |
| Fallback text search for field is unreliable | `findFieldLineInDocument()` does naive string matching: checks if line contains field name AND any of `private/protected/public/val/var/static`. | Could match wrong line (e.g., a comment mentioning the field name, or a method parameter with the same name). Would set watchpoint on wrong line. | MEDIUM |

### 1.6 `remove_breakpoint`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| No breakpoint at specified location | Searches `allBreakpoints` by fileUrl + line → "No breakpoint found at {file}:{line}" | Well handled | OK |
| File doesn't exist | `LocalFileSystem.findFileByPath()` returns null → "File not found" | Well handled | OK |
| Removing while paused on that breakpoint | `bpManager.removeBreakpoint(matchingBp)` succeeds. The session remains paused. | No warning that execution is currently stopped at this breakpoint. LLM might expect execution to resume. | LOW |
| Removing method/exception/field breakpoints | Only searches `XLineBreakpoint` instances. Method breakpoints are line breakpoints so they're found. Exception breakpoints are NOT `XLineBreakpoint` — cannot be removed via `remove_breakpoint`. | No way to remove exception breakpoints. Would need a separate action or the `remove_breakpoint` action should accept a breakpoint type parameter. | MEDIUM |
| Line number < 1 | Explicit validation | OK |

### 1.7 `list_breakpoints`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| No breakpoints set | Returns "No breakpoints found{qualifier}." with `isError=false` | Correctly returns non-error empty state | OK |
| Very many breakpoints (50+) | No truncation — all breakpoints are listed | Could produce very large output if hundreds of breakpoints exist. No cap. | LOW |
| Only lists line breakpoints | Filters `allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()` | Exception breakpoints and field watchpoints set by `exception_breakpoint`/`field_watchpoint` are NOT shown. LLM thinks there are no breakpoints when there are exception breakpoints active. | HIGH |
| File filter with non-existent file | Resolves to null fileUrl → no breakpoints match → "No breakpoints found in {file}" | Slightly misleading — says "no breakpoints" when the file doesn't even exist. | LOW |
| Shows disabled breakpoints | Lists with `[disabled]` trait | OK — shows status clearly | OK |
| Does not show method breakpoint metadata | Method breakpoints appear as line breakpoints without their class/method metadata | LLM can't tell which line breakpoint is a method breakpoint | LOW |

### 1.8 `attach_to_process`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Port out of range | Explicit validation: "Port must be between 1 and 65535" | Well handled | OK |
| Target not running / no JDWP agent | 30s timeout → "Failed to attach to {host}:{port} within 30 seconds. Verify the target JVM is running with JDWP agent enabled on port {port}." | Good error message with actionable guidance | OK |
| Port already in use by another debugger | The attach will fail — processNotStarted path. Returns empty sessionId → timeout. | Same generic timeout message — doesn't say "connection refused" vs. "already connected." | LOW |
| Firewall blocks connection | Same timeout path | Same generic message | LOW |
| Another session already attached to same port | A new attach session is created. No warning about existing attachment. | Multiple attachments to the same port — second one may cause JVM instability or one session to disconnect. | LOW |
| `name` param generates `[Agent] Remote Debug` prefix always | Display name defaults to `[Agent] Remote Debug {host}:{port}` | Well handled — clearly marks agent-created configs | OK |

---

## 2. DebugStepTool (`debug_step`) — 10 Actions (advertised as 8, actually 10)

**Note: The description says "8 actions" but `enumValues` lists 10 actions (includes `force_step_into` and `force_step_over`).**

### Cross-cutting: Session resolution via controller.getSession() only

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| User-started debug session (not agent-started) | `controller.getSession(null)` returns `activeSessionId?.let { sessions[it] }`. If no agent session exists, returns null → "No debug session found." | **CRITICAL**: Cannot step in user-started sessions. Contradicts `debug_inspect` which CAN see user sessions via `IdeStateProbe`. | CRITICAL |
| Multiple agent sessions | Returns the one marked as `activeSessionId`. No ambiguity detection. | If multiple sessions exist, silently uses whichever was last started. May step in the wrong session. | MEDIUM |
| Session exists in controller but process exited | `sessionStopped()` listener removes from map. But there's a race: between process exit and listener firing, `getSession()` returns a stale session → step action throws. | Caught by generic `catch (e: Exception)` → "Error during {action}: {message}". But the error message will be a low-level JVM/JDI error, not "session has ended." | MEDIUM |

### 2.1 `get_state`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| No debug session | "No debug session found. Start one with start_debug_session." | Correct but see cross-cutting issue above | See above |
| Session exists, running (not paused) | Status: RUNNING | OK | OK |
| Session exists, paused | Shows file:line, thread info, up to 5 non-active threads | Well handled | OK |
| Session stopped (process exited) | Cleaned up by listener → "No debug session found" | No indication session existed and stopped. See cross-cutting issue. | MEDIUM |
| Suspend reason always shows "breakpoint" | Hardcoded: `if (isSuspended) sb.append("Reason: breakpoint\n")` | Reason is always "breakpoint" even when paused by step completion, `pause()` call, or exception. Misleading. | MEDIUM |
| Thread count calculation incorrect | `val suspendedCount = allStacks.size.coerceAtLeast(1)` — always at least 1, even if 0 stacks are suspended. And it equals `totalThreads` always (both are `allStacks.size`). | "Suspended threads: X of X" is always "all threads suspended" which may not be true. The variable name is misleading — `allStacks` from `suspendContext.executionStacks` are only the paused stacks, but the total thread count should come from all JVM threads. | MEDIUM |
| Stack frame display uses `.toString()` | `"$currentFrame".takeIf { it != "null" }` — relies on XStackFrame.toString() which may produce cryptic output | May show internal object representation instead of meaningful frame info | LOW |

### 2.2 `step_over`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Session not suspended | "Session is not suspended. Cannot step_over while running." | Well handled | OK |
| Step into infinite loop (method doesn't return) | `waitForPause(id, 5000)` returns null after 5s → "Step completed but session did not pause within 5s (may have hit end of execution)" | 5s timeout is reasonable. Message is slightly misleading — "may have hit end of execution" when actually the step is still in progress inside a called method. Should say "step is still in progress — the called method may be long-running. Use pause to interrupt." | LOW |
| Step completes normally | Shows new position + auto-includes frame variables at depth 1 | Excellent — saves LLM a round trip | OK |
| Session becomes null between check and action | `session.stepOver(false)` called on a potentially-stale session | Race condition possible but extremely unlikely. Generic catch handles it. | LOW |

### 2.3 `step_into`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Step into native method | IntelliJ's debugger handles this — typically steps over native methods. No special handling needed. | The LLM may not understand why step_into behaved like step_over. No explanation in the output. | LOW |
| Step into library method without source | Debugger steps into the decompiled class. Agent sees the position. | LLM may not realize it's in decompiled code (no source). The file path will be a .class file or synthetic. | LOW |
| Same as step_over for session/suspension checks | Same handling | OK | OK |

### 2.4 `step_out`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Already at top-level frame | IntelliJ handles this — the step_out continues until the program exits or hits a breakpoint. `waitForPause` may timeout. | Message says "may have hit end of execution" which is accurate here | OK |
| Same as step_over for session/suspension checks | Same handling | OK | OK |

### 2.5 `force_step_into`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Steps into CGLIB proxy / Spring AOP | This is the intended use case. Calls `session.forceStepInto()` which bypasses step filters. | Well designed | OK |
| Session not suspended | Same handling as step_over | OK | OK |

### 2.6 `force_step_over`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Ignores breakpoints in called methods | Calls `session.stepOver(true)` — the `true` parameter means "ignore breakpoints" | Well documented in tool description | OK |

### 2.7 `resume`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Session not suspended | `session.resume()` on a running session. No pre-check for `isSuspended`. | May throw or be a no-op. The generic catch handles exceptions, but calling `resume()` on a running session is a logical error that should return a clear message. | MEDIUM |
| Hits another breakpoint immediately | `resume()` returns, message says "Session resumed." No info about re-pause. | LLM doesn't know the session immediately hit another breakpoint. Should call `get_state` next but isn't told to. | LOW |

### 2.8 `pause`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Session already paused | `session.pause()` on already-paused session. No pre-check. | May be a no-op or throw. Generic catch handles it. Should return "Session is already paused" for clarity. | LOW |
| Pause succeeds | Waits up to 5s for pause event via `waitForPause`. If received, shows position. Otherwise "Pause requested." | Well handled — graceful timeout | OK |

### 2.9 `run_to_cursor`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Session not suspended | Explicit check: "Session is not suspended. Pause or wait for a breakpoint first." | Well handled | OK |
| File doesn't exist | `PathValidator.resolveAndValidate()` + `LocalFileSystem.findFileByPath()` null check | Well handled | OK |
| Line < 1 | Explicit check | Well handled | OK |
| Target line won't be reached | `waitForPause(id, 30000)` returns null → "Run to cursor requested ({file}:{line}). Session did not pause within 30s." | 30s is reasonable. Message is clear. But the session is now running — LLM should know it needs to `stop` or `pause`. | LOW |
| Line beyond file length | `XDebuggerUtil.createPosition()` may create a position that can't be hit. Session runs but never stops there. | Same 30s timeout message. Not differentiated from "line exists but is unreachable." | LOW |

### 2.10 `stop`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Session already stopped | `controller.getSession()` returns null (cleaned up by listener) → "No debug session found" | Correct — but doesn't say "session already stopped" | LOW |
| Stop while evaluation in progress | `session.stop()` kills the process. Any pending evaluations will throw. | The stop itself works. But if `debug_inspect(evaluate)` is being called concurrently (shouldn't happen in single-threaded ReAct loop), it would get a confusing error. | LOW |
| Stop succeeds | "Debug session stopped. Session: {id}" | OK | OK |

---

## 3. DebugInspectTool (`debug_inspect`) — 9 Actions

### Session resolution (via IdeStateProbe)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| No session | "No debug session found. Start one with start_debug_session, or have the user start a debug session via the IDE (gutter Debug button or Run menu) and try again." | Excellent — acknowledges user-started sessions | OK |
| Session running but not paused | "Debug session is running but not paused. This action requires the debugger to be suspended..." | Excellent — clear guidance | OK |
| Multiple sessions, no session_id | "Multiple debug sessions are active ({count}: {names}). Pass session_id to disambiguate." | Excellent — lists session names | OK |
| Session stopped (cleaned up by listener) | `XDebuggerManager.debugSessions` returns empty → NoSession | Same "no session" as never-started | LOW |

### 3.1 `evaluate`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Invalid expression syntax | `XDebuggerEvaluator.XEvaluationCallback.errorOccurred()` fires → `EvaluationResult(isError=true)` → "Error: {message}" | Well handled — propagates evaluator error | OK |
| Expression with side effects | No detection or warning | Not feasible to detect. Standard debugger behavior. | OK (inherent) |
| Expression throws an exception | Same `errorOccurred` callback | Well handled | OK |
| Expression hangs (infinite loop in eval) | No timeout on `suspendCancellableCoroutine` in `controller.evaluate()`. The coroutine will hang indefinitely. | **No timeout on evaluation**. If the LLM evaluates `Thread.sleep(Long.MAX_VALUE)` or a recursive function, the agent is stuck forever. The tool-level timeout (120s default) is the only safety net. | HIGH |
| Blank expression | Explicit check: "Expression cannot be blank." | Well handled | OK |
| Frame index > 0 (non-current frame evaluation) | Code has a fallback: for non-zero frameIndex, it uses `session.currentStackFrame` anyway (comment says "Use current frame as fallback"). | **Silently evaluates in wrong frame**. If LLM requests evaluation in frame #3, it actually evaluates in frame #0. This is a known limitation documented in a code comment but the LLM isn't told. | HIGH |
| No evaluator available | Returns "No evaluator available for current frame" | Well handled | OK |

### 3.2 `get_stack_frames`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Session not suspended | `requireSuspendedSession()` handles this | OK | OK |
| No stack frames | Returns "No stack frames available." | OK | OK |
| `max_frames` clamped | `coerceIn(1, MAX_FRAMES_CAP=50)` | Well handled | OK |
| `thread_name` parameter present but not actually used for thread selection | Parameter is accepted but only used for display in the header. `controller.getStackFrames()` always gets frames from `session.suspendContext.activeExecutionStack` regardless of `thread_name`. | **LLM thinks it's selecting a thread but it isn't.** If LLM passes `thread_name="worker-5"`, it gets the active thread's stack but the header says "worker-5 thread." Misleading. | HIGH |
| `className` always null in FrameInfo | `FrameInfo.className = null` in `getStackFrames()` | Minor — className is available from the frame but not extracted. Could be useful for the LLM. | LOW |
| Callback timeout in `getStackFrames` | No timeout on `suspendCancellableCoroutine` in `controller.getStackFrames()` | Could hang if the callback never fires. Tool-level timeout is the safety net. | MEDIUM |
| `errorOccurred` in stack frame computation | `cont.resume(frames)` — returns partial results | Graceful degradation — good | OK |

### 3.3 `get_variables`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Session not suspended | `requireSuspendedSession()` handles this | OK | OK |
| No variables in frame | Returns "No variables in the current frame." | OK | OK |
| Variable name not found | Lists available variables: "Variable '{name}' not found in frame. Available: {list}" | Excellent — helps LLM correct | OK |
| Deep object graphs | `maxDepth` clamped to `MAX_DEPTH_CAP=4`. Character count capped at `MAX_VARIABLE_CHARS=3000` via `charCounter`. | Well handled | OK |
| Circular references | `computeChildren()` recurses based on depth count. IntelliJ's `XValue` API handles circularity at the presentation level. `charCounter` and depth limit prevent infinite recursion. | Protected by depth + char limits. But circular refs may produce confusing output (same nested structure repeated). | LOW |
| Lazy/proxy objects (Hibernate) | IntelliJ's Java debugger handles lazy proxies — it shows the proxy type. `computePresentation()` will show the proxy representation. | LLM sees proxy type, not the actual object. No warning that the variable is a lazy proxy. Could be confusing. | LOW |
| Very large collections (10K+ items) | `MAX_CHILDREN_PER_LEVEL=10` caps children per node. Only first 10 elements shown. | Well capped. But no indication to LLM that there are more children. `tooManyChildren` callback is handled but doesn't add a "... and N more" message. | MEDIUM |
| Output truncation | `MAX_OUTPUT_CHARS=3000` → truncates with "... (use variable_name to inspect specific variable)" | Good guidance on how to get more detail | OK |
| Variable presentation timeout | `resolvePresentation()` uses `withTimeoutOrNull(5000L)` → returns `("unknown", "<timed out>")` | Well handled — graceful degradation | OK |
| `computeChildren` timeout | `withTimeoutOrNull(5000L)` on `computeChildren` | Well handled | OK |

### 3.4 `set_value`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Variable not found | Falls back to evaluate-with-assignment. If that fails: "Failed to set '{name}': {error}. Variable may not exist in the current frame, or may be final/val." | Reasonable fallback chain | OK |
| Variable is `final`/`val` | Assignment fallback will fail → error mentions "may be final/val" | OK | OK |
| Type mismatch | `XValueModifier.XModificationCallback.errorOccurred()` fires → "Failed to set '{name}': {error}" | OK | OK |
| Verify read-back after set | Uses `controller.evaluate(session, variableName, 0)` to verify | Good — confirms the new value | OK |
| `findXValueByName` timeout | `withTimeoutOrNull(5000L)` in controller | Well handled | OK |
| Assignment expression injection | If `variableName` contains special characters, `"$variableName = $newValue"` could be a valid but unintended expression | Very unlikely since LLM controls the input, but no sanitization of variable name for the assignment fallback path | LOW |

### 3.5 `thread_dump`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Session not running at all | `requireSession()` (not `requireSuspendedSession`) — needs any session, doesn't need to be suspended | Correct — thread dump works on running sessions | OK |
| 100+ threads | All threads listed (filtered by daemon flag). No truncation. | Could produce very large output. No cap on thread count. A Java web server typically has 200+ threads. | MEDIUM |
| Daemon threads excluded by default | `includeDaemon` defaults to `false`. Most service threads are daemon. | LLM may miss important threads (GC, HTTP workers are often daemon). But user can set `include_daemon=true`. | OK |
| Thread frames unavailable | Shows "(frames unavailable — thread not suspended)" | Well handled | OK |
| `inferDaemon` uses reflection | Falls back to thread group name check → falls back to `false` | Graceful degradation. Non-HotSpot JVMs may misclassify daemon threads. | LOW |
| VM disconnected during dump | `executeOnManagerThread` may throw → generic catch | OK | OK |
| Thread status mapping | Maps JDI `ThreadReference.status()` to strings. All standard statuses covered. | OK | OK |

### 3.6 `memory_view`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Session required to be **suspended** | Uses `requireSuspendedSession()` | Correct for `instanceCounts` — JVM must be paused | OK |
| VM doesn't support instance info | Checks `vm.canGetInstanceInfo()` → "VM does not support instance info" | Well handled | OK |
| Class not loaded | `vm.classesByName()` returns empty → "Class '{name}' is not loaded in the JVM. It may not have been instantiated yet, or the name may be incorrect." | Excellent error message | OK |
| Large instance count (10K+) | Instance details capped at `MAX_INSTANCE_DETAILS=50`. Count-only by default (`max_instances=0`). | Well handled | OK |
| Class has subclasses | `vm.classesByName()` may return multiple `ReferenceType`s. Shows breakdown by type. | Well handled | OK |
| `max_instances` very large | `coerceAtMost(MAX_INSTANCE_DETAILS=50)` | Well capped | OK |

### 3.7 `hotswap`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Python debug session | `isPythonDebugSession()` check → "Hot swap is not supported for Python. Restart the debug session to apply changes." | Well handled | OK |
| Class structure changed (add/remove method) | HotSwap fails → `onFailure` callback → status "failure" → "Hot swap failed. Check for structural changes (new/removed methods, fields, or signature changes)." | Well handled — explains JVM HotSwap limitations | OK |
| No changes to reload | `onNothingToReload` callback → "No changed classes detected. Make code changes first." | Well handled | OK |
| Compilation fails | `compile_first=true` (default). Compilation failure → `onFailure` | OK — but doesn't distinguish compilation failure from HotSwap failure. Both produce "failure" status. | LOW |
| `onCancel` | "Hot swap was cancelled by the user or IDE." | OK | OK |
| Timeout (60s) | "Hot swap timed out after 60 seconds. Check compilation and IDE status." | OK | OK |
| Wrong debugger session matched | Code tries to match `DebuggerSession.xDebugSession === xSession`. Falls back to `sessions.firstOrNull()`. | The fallback to `firstOrNull()` could hot-swap the wrong session's classes if multiple sessions exist. | MEDIUM |
| Session doesn't need to be suspended | `requireSession()` (not `requireSuspendedSession`) | Correct — HotSwap works on running sessions | OK |

### 3.8 `force_return`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| JVM doesn't support force return | `vmProxy.canForceEarlyReturn()` check → IllegalStateException with clear message | Well handled | OK |
| Incompatible thread state | Catches `IncompatibleThreadStateException` → "Thread is not in a compatible state for force return. The thread must be suspended at a non-native frame." | Well handled | OK |
| Native method | Catches `NativeMethodException` → "Cannot force return from a native method. Step out of the native method first." | Excellent — actionable guidance | OK |
| Type mismatch | Catches `InvalidTypeException` → "Type mismatch: the return value type does not match the method's return type." | Well handled | OK |
| `return_value` not provided for non-void | `return_value ?: throw IllegalArgumentException("return_value required for {type} type")` | Well handled | OK |
| `return_type=auto` inference | `inferReturnType()` infers from value format: null→null, true/false→boolean, "..."→string, contains .→double, number→int/long, else→string | Reasonable heuristic but could misidentify (e.g., `"3.14"` without quotes → "double", but what if the method returns float?) | LOW |
| `forceEarlyReturn(value)` called on correct thread | Uses `suspendContext.thread` from `SuspendContextImpl` | OK | OK |
| Python session | No Python-specific guard (unlike hotswap and drop_frame) | `executeOnManagerThread` will throw "Not a Java debug session" since `DebugProcessImpl` cast fails. Error message is cryptic: "Not a Java debug session" vs the clearer Python-specific message used elsewhere. | MEDIUM |

### 3.9 `drop_frame`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Python session | Explicit check via `isPythonDebugSession()` → "Drop frame is not supported in Python debug sessions. Python's debugger does not support rewinding execution." | Well handled | OK |
| VM doesn't support frame popping | `vmProxy.canPopFrames()` check → clear error | Well handled | OK |
| Frame index out of range | Checks `frameIndex >= frames.size` → "frame_index {N} is out of range. Stack has {M} frames (0..{M-1})." | Well handled | OK |
| Frame index < 0 | Explicit pre-check: "frame_index must be >= 0" | Well handled | OK |
| Side effects not undone | Output includes: "Note: Variable state is NOT reset. Side effects are NOT undone." | Excellent warning | OK |
| Thread matching by name | `threads.firstOrNull { t -> t.name() == threadName && t.isSuspended }` with fallback to any suspended thread | The fallback could pop frames on the wrong thread if the name match fails. | MEDIUM |
| No suspended thread found | "No suspended thread found matching '{name}'." | Well handled | OK |
| No suspend context | "No suspend context available. Session may not be properly paused." | Well handled | OK |
| Drop frame at bottom of stack | Frame 0 (current frame) is valid. `thread.popFrames(targetFrame)` pops that frame. | JDI may throw if trying to pop the last frame. Generic catch handles it. | LOW |

---

## 4. AgentDebugController — Shared Infrastructure

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| `sessionStopped` race condition | Listener removes session from map + adjusts `activeSessionId` to `sessions.keys.firstOrNull()` | If `getSession()` is called between process exit and listener firing, the returned session is stale. Step/evaluate on it will throw. | MEDIUM |
| `activeSessionId` set to `sessions.keys.firstOrNull()` on session stop | Non-deterministic — ConcurrentHashMap key ordering is not guaranteed | When session A stops, active may switch to session B or C arbitrarily | LOW |
| `pauseFlows` MutableSharedFlow with `replay = 1` | `sessionResumed()` calls `flow.resetReplayCache()` to clear stale pause events | Correct design — prevents stale pause events from being consumed | OK |
| `getStackFrames` callback safety | `isObsolete()` returns `false` always | Could cause issues if the frame becomes obsolete during computation — but unlikely in practice | LOW |
| `resolvePresentation` timeout | 5s timeout → returns `("unknown", "<timed out>")` | Good fallback | OK |
| `findXValueByName` — multiple callbacks | `cont.resume(found)` called on `last=true` in `addChildren`. If `addChildren` is called multiple times with `last=false`, only the last batch triggers resume. | Correct for the API contract. But if `tooManyChildren` fires before all children are scanned, it resumes with `null` even if the variable exists in unscanned children. | LOW |
| `executeOnManagerThread` — non-Java session | Casts `session.debugProcess` to `DebugProcessImpl` → throws "Not a Java debug session" | Callers must handle this. Some do (hotswap, drop_frame check for Python), some don't (force_return). | MEDIUM |
| `dispose()` cleanup | Stops all sessions, removes all breakpoints from IDE. Triple try-catch for safety. | Well handled | OK |
| `removeAgentBreakpoints()` — no-arg version | Clears the tracking sets but does NOT remove from IDE breakpoint manager. Orphaned breakpoints remain. | The no-arg version exists alongside the `removeAgentBreakpoints(debuggerManager)` version that does proper cleanup. If the wrong overload is called, breakpoints are leaked. | MEDIUM |
| `MAX_VARIABLE_CHARS = 3000` | Hard limit on variable output size | Reasonable but not communicated to LLM — no "truncated" message when limit is hit | LOW |
| `MAX_CHILDREN_PER_LEVEL = 10` | Hard limit on children per node | See get_variables analysis above | See above |
| Pause reason always "breakpoint" | `sessionPaused()` hardcodes reason = "breakpoint" | Doesn't distinguish step completion, manual pause, or exception breakpoint hits | MEDIUM |

---

## 5. Summary of Findings by Severity

### CRITICAL (2)

1. **Session resolution inconsistency**: `DebugStepTool` uses `controller.getSession()` (agent-only registry) while `DebugInspectTool` uses `IdeStateProbe.debugState()` (platform-aware). User-started debug sessions are invisible to step/resume/pause/stop but visible to evaluate/variables/hotswap.

2. **Same root cause as #1**: Stepping actions fail on user-started sessions while inspect actions work, creating contradictory LLM experience that causes doom loops.

### HIGH (4)

1. **`evaluate` has no timeout on expression evaluation** — hanging expressions block the agent indefinitely (until tool-level 120s timeout).

2. **`evaluate` silently evaluates in wrong frame** — `frameIndex > 0` falls back to frame #0 without telling the LLM, producing incorrect results.

3. **`get_stack_frames` ignores `thread_name`** — parameter is accepted but only used for display header. Actual stack frames always come from the active thread. LLM thinks it's inspecting a specific thread but isn't.

4. **`list_breakpoints` only shows line breakpoints** — exception breakpoints and field watchpoints set by the agent are invisible. LLM may incorrectly conclude no breakpoints exist.

### MEDIUM (18)

1. `start_session` — generic failure message doesn't distinguish build failure from port conflict from timeout
2. `start_session` — no warning when another debug session is already running
3. `start_session` — 30s timeout may be too short for large applications
4. `add_breakpoint` — `pass_count` silently ignored on non-Java debugger (no warning in output)
5. `add_breakpoint` — condition expression not validated upfront
6. `field_watchpoint` — "class not found" reports as "field not found" (misleading)
7. `field_watchpoint` — `findFieldLineInDocument` naive text search can match wrong line
8. `remove_breakpoint` — cannot remove exception breakpoints (no mechanism exists)
9. `get_variables` — `tooManyChildren` callback doesn't indicate truncation to LLM
10. `get_state` — thread count calculation (`suspendedCount = allStacks.size.coerceAtLeast(1)`) is incorrect
11. `get_state` — suspend reason hardcoded to "breakpoint" for all pause types
12. `resume` — no pre-check for `isSuspended` (may throw or no-op silently on running session)
13. `thread_dump` — no truncation on 200+ thread applications
14. `hotswap` — `DebuggerSession` fallback to `firstOrNull()` could hot-swap wrong session
15. `force_return` — no Python-specific guard (gets cryptic cast error instead of clear message)
16. `drop_frame` — thread matching fallback could pop frames on wrong thread
17. `AgentDebugController.removeAgentBreakpoints()` (no-arg) — clears tracking but doesn't remove from IDE
18. Cross-tool: "session stopped" indistinguishable from "session never existed"

### LOW (20+)

Various minor issues: misleading timeout messages, missing truncation indicators, edge cases in type inference, stale session races, minor display issues.

---

## 6. Recommended Priority Fixes

### P0 — Fix Now (blocks correct agent behavior)

1. **Unify session resolution**: Port `DebugStepTool` and `DebugStepUtils` to use `IdeStateProbe.debugState()` + `requireSession()`/`requireSuspendedSession()` pattern from `DebugInspectTool`. This is the single biggest correctness issue.

2. **Fix `list_breakpoints` to include all breakpoint types**: Add exception breakpoints and field watchpoints to the listing. The LLM needs complete visibility.

### P1 — Fix Soon (causes confusion or silent errors)

3. **Add timeout to `controller.evaluate()`**: Wrap the `suspendCancellableCoroutine` in `withTimeoutOrNull(30_000)` to prevent hanging evaluations.

4. **Fix `thread_name` in `get_stack_frames`**: Either implement actual thread selection or remove the parameter and document that it always returns the active thread's stack.

5. **Fix `evaluate` frame index fallback**: Either implement actual frame selection or return an error when `frameIndex > 0` is requested. Don't silently fall back to frame #0.

6. **Fix `get_state` thread count**: Use `vm.allThreads().size` for total and `suspendContext.executionStacks.size` for suspended.

7. **Fix `get_state` pause reason**: Detect whether pause was caused by breakpoint, step, exception, or manual pause.

### P2 — Improve (quality of information)

8. Add warning when `pass_count` is silently ignored (non-Java debugger).
9. Add `remove_exception_breakpoint` action or extend `remove_breakpoint` to handle all types.
10. Add Python-specific guard to `force_return` (consistent with `hotswap` and `drop_frame`).
11. Fix `start_session` to differentiate build failure from timeout from port conflict.
12. Add truncation indicators when `tooManyChildren` fires in `get_variables`.
13. Cap `thread_dump` output at ~50 threads with a "... and N more" message.
