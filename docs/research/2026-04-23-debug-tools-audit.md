# Debug Tools Audit — Findings (2026-04-23)

> Companion to `docs/plans/2026-04-23-debug-tools-audit-fixes.md`.
> Provides the evidence base for every task in that plan. Read this before
> touching any of the source files listed in the plan.

---

## 1. Tool Surface Overview

Three meta-tools, 26 sub-actions total.

| Meta-Tool | Actions | File |
|-----------|---------|------|
| `debug_step` | 10: `get_state`, `step_over`, `step_into`, `step_out`, `force_step_into`, `force_step_over`, `resume`, `pause`, `run_to_cursor`, `stop` | `DebugStepTool.kt` |
| `debug_inspect` | 9: `evaluate`, `get_stack_frames`, `get_variables`, `set_value`, `thread_dump`, `memory_view`, `hotswap`, `force_return`, `drop_frame` | `DebugInspectTool.kt` |
| `debug_breakpoints` | 8: `add_breakpoint`, `method_breakpoint`, `exception_breakpoint`, `field_watchpoint`, `remove_breakpoint`, `list_breakpoints`, `start_session`, `attach_to_process` | `DebugBreakpointsTool.kt` |

**Structural strengths:**

- `IdeStateProbe` (`agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/platform/IdeStateProbe.kt`) unifies session resolution for all three meta-tools; both agent-started and user-started sessions are visible.
- `DebugInvocation` scopes per-session listener lifetimes — listeners are attached via the 2-arg `addSessionListener(listener, parent)` form, auto-removed on `Disposer.dispose`. Replaces the pre-Task-4.2 raw listener leak.
- Callbacks are wrapped with `awaitCallback` + `AtomicBoolean` gate so timed-out IntelliJ JDI callbacks become no-ops instead of corrupting a consumed continuation.
- `WriteAction` + EDT dispatching used for breakpoint creation (but NOT removal — see C6).

---

## 2. Canonical "Is Paused?" Predicate

**This is the central correctness insight. Every tool that requires suspension must use all three clauses.**

```kotlin
val paused = session.isSuspended
          && session.currentStackFrame != null
          && session.suspendContext != null
```

**Why all three are required:**

During a pause event, `isSuspended` flips to `true` before the JDI/XDebugger engine has finished populating `currentStackFrame` and `suspendContext`. A tool reading state in that window gets a non-null session with nothing to inspect — the exact cause of the "No active stack frame available" error the LLM hits immediately after a step command.

**Source:**
[XDebugSession.java L51-L69](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebugSession.java) — `isSuspended()`, `getCurrentStackFrame()`, `getSuspendContext()` are separate fields populated by separate callbacks in the debugger engine pump. `XDebuggerEvaluator`, `XValueContainer.computeChildren`, and `XExecutionStack.computeStackFrames` all internally guard their own work against null `suspendContext` or null frame — but `isSuspended` being `true` provides no such guarantee.

**Current state:** The codebase checks only `isSuspended` in two places (IdeStateProbe.kt:69, IdeStateProbe.kt:82, AgentDebugController.kt:181). The fix must propagate all three clauses through IdeStateProbe and waitForPause.

---

## 3. Correctness Bugs (C1–C7)

### C1 — Session Resolution Prefers Last-Focused Over Paused (HIGH)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/platform/IdeStateProbe.kt:88-99`

**Severity:** HIGH — root cause of the user-reported "session running but not paused" false negative.

**Evidence:** The no-`sessionId` fallback at lines 88–99:

```kotlin
val target: XDebugSession? = when {
    mgr.currentSession != null -> mgr.currentSession   // ← always wins
    all.size == 1 -> all.single()
    else -> null
}

return when {
    target != null && target.isSuspended -> DebugState.Paused(target)
    target != null -> DebugState.Running(target)       // ← "running" false negative
    ...
}
```

`XDebuggerManager.currentSession` returns the *last-focused* session — the one whose Run tab the user last clicked — not the paused one. When the user has two sessions (e.g. Spring Boot running + JUnit paused at a breakpoint) and focus-clicks the Spring Boot console, `currentSession` is the Spring Boot session. Every debug tool that omits `session_id` then targets the wrong session and returns `Running`, triggering `notSuspendedError`.

**Fix sketch:** Scan `mgr.debugSessions` for exactly one session satisfying the canonical paused predicate and prefer it; fall back to `currentSession` only when zero or >1 are paused.

```kotlin
fun isPaused(s: XDebugSession): Boolean =
    s.isSuspended && s.currentStackFrame != null && s.suspendContext != null

val pausedSessions = all.filter(::isPaused)
val target: XDebugSession? = when {
    pausedSessions.size == 1 -> pausedSessions.single()   // prefer uniquely-paused
    mgr.currentSession != null -> mgr.currentSession
    all.size == 1 -> all.single()
    else -> null
}
```

Also update the `sessionId`-path paused checks at IdeStateProbe.kt:69 and IdeStateProbe.kt:82 to use the same three-clause predicate instead of bare `isSuspended`.

---

### C2 — evaluate at Non-Zero frameIndex Silently Falls Back to Current Frame (HIGH)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt:408-419`

**Severity:** HIGH — evaluate in a caller frame always evaluates in frame 0 with no error.

**Evidence:** Lines 408–419 of `AgentDebugController.kt`:

```kotlin
suspend fun evaluate(session: XDebugSession, expression: String, frameIndex: Int = 0): EvaluationResult {
    val frame = if (frameIndex == 0) {
        session.currentStackFrame
    } else {
        val frames = getStackFrames(session, frameIndex + 1)
        if (frames.size <= frameIndex) {
            return EvaluationResult(expression, "Frame $frameIndex not available", "error", isError = true)
        }
        // For non-zero frame indices, we need the actual XStackFrame reference
        // but getStackFrames returns FrameInfo DTOs. Use current frame as fallback.
        session.currentStackFrame   // ← BUG: ignores frameIndex silently
    }
    ...
}
```

`getStackFrames` returns `List<FrameInfo>` (DTOs), not `List<XStackFrame>`. Without the raw `XStackFrame` reference, the code cannot call `frame.evaluator` for the target frame. The workaround in the comment is wrong: it calls `currentStackFrame.evaluator` regardless of `frameIndex`, so the expression is always evaluated in frame 0's scope. The LLM receives a result that appears correct but is from the wrong stack frame.

**Fix sketch:** Add `getRawStackFrames(session, maxFrames): List<XStackFrame>` that keeps the raw `XStackFrame` references (same `computeStackFrames` callback path, without converting to DTOs), then use `frames[frameIndex].evaluator` and pass `frames[frameIndex].sourcePosition` for correct scope/import resolution.

```kotlin
val evaluator = frame.evaluator ?: ...
evaluator.evaluate(expression, callback, frame.sourcePosition)  // was: null
```

**Source:** [XDebuggerEvaluator.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/evaluation/XDebuggerEvaluator.java) — the 3rd parameter `position: XSourcePosition?` scopes language imports and local variables; `null` falls back to the LLM's current frame source position.

---

### C3 — set_value Uses Internal *.impl.* API (MEDIUM)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt:438`

**Severity:** MEDIUM — internal API subject to removal or signature change in any IntelliJ release.

**Evidence:** Line 438 of `DebugInspectTool.kt`:

```kotlin
modifier.setValue(
    com.intellij.xdebugger.impl.breakpoints.XExpressionImpl.fromText(newValue),
    ...
)
```

`XExpressionImpl` is in the `*.impl.breakpoints.*` package — an internal implementation detail. The `verifyPlugin` task may flag this as an unstable API use depending on the API mode configured.

**Fix sketch:** Replace with the public factory on [XDebuggerUtil.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebuggerUtil.java):

```kotlin
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.evaluation.EvaluationMode

modifier.setValue(
    XDebuggerUtil.getInstance().createExpression(
        newValue,
        /* language */ null,   // platform infers from active debug process
        /* customInfo */ null,
        EvaluationMode.EXPRESSION,
    ),
    ...
)
```

Remove the `import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl` import.

---

### C4 — tooManyChildren Silently Drops Excess Variables (MEDIUM)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt:327-330`

**Severity:** MEDIUM — LLM receives a truncated variable list with no indication it is partial.

**Evidence:** Lines 327–330 in `computeChildren`:

```kotlin
override fun tooManyChildren(remaining: Int) {
    if (stopped.get()) return
    resume(names.zip(result))   // ← silently returns what was collected; no marker
}
```

The `XCompositeNode.tooManyChildren` callback fires when the engine has more children than the node requested (typically capped at 100 by the platform). The current code resumes with whatever was collected — e.g. 100 of a HashMap with 500 entries — with no "there are more" signal. The LLM sees a list of 100 variables and cannot distinguish "there are exactly 100" from "there are 500 and 400 were dropped".

**Fix sketch:** Append a sentinel `VariableInfo(name="<truncated>", type="truncated", value="…and N more children (use variable_name to expand a specific one)", truncated=true)` to the result list. Render it distinctly in `formatVariables`.

**Source:** [XCompositeNode.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/frame/XCompositeNode.java) — `tooManyChildren(remaining)` contract: `remaining` is the count that was NOT added.

---

### C5 — waitForPause Short-Circuit Checks Only isSuspended (LOW)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt:181`

**Severity:** LOW — race window between step completion and frame population.

**Evidence:** Lines 181–189 of `AgentDebugController.kt`:

```kotlin
return withTimeoutOrNull(timeoutMs) {
    if (session.isSuspended) {        // ← only one clause; misses the race window
        val pos = session.currentPosition
        return@withTimeoutOrNull DebugPauseEvent(
            sessionId = sessionId,
            file = pos?.file?.path,
            line = pos?.line?.plus(1),
            reason = "breakpoint"
        )
    }
    flow.first()
}
```

After a step command, `isSuspended` becomes `true` before `currentStackFrame` is populated. If a tool calls `waitForPause` and then immediately calls `getVariables`, `getVariables` sees `currentStackFrame == null` and returns "No active stack frame available." The `pauseFlow` subscriber (set up in `sessionPaused` in `registerSession`) fires only after the engine has populated state, so falling through to `flow.first()` would be safe — but the short-circuit bypasses it.

**Fix sketch:** Require all three clauses before short-circuiting:

```kotlin
if (session.isSuspended
    && session.currentStackFrame != null
    && session.suspendContext != null
) {
    // ... build DebugPauseEvent ...
}
flow.first()
```

---

### C6 — Breakpoint Removal in Dispose Paths Missing WriteAction (LOW)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt:519-544`

**Severity:** LOW — threading violation that may cause rare "Write-unsafe" assertion errors.

**Evidence:** Lines 519–544 in `dispose()` and lines 496–516 in `removeAgentBreakpoints(XDebuggerManager)`:

```kotlin
override fun dispose() {
    ...
    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
    agentBreakpoints.forEach { bp ->
        bpManager.removeBreakpoint(bp as XLineBreakpoint<Nothing>)  // ← no WriteAction
    }
    ...
}
```

Per [IntelliJ threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html), `XBreakpointManager` mutations (add, remove) must be called under the write lock. `WriteAction.runAndWait` is already imported for breakpoint creation elsewhere in the file. Removal is missing the lock.

**Fix sketch:** Wrap each mutation block with `WriteAction.runAndWait<RuntimeException> { ... }`. The `import com.intellij.openapi.application.WriteAction` is already present in `DebugBreakpointsTool.kt`; add it to `AgentDebugController.kt` if not already present.

---

### C7 — add_breakpoint Doesn't Pre-Check canPutBreakpointAt (LOW)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt:950-959`

**Severity:** LOW — silently creates a disabled breakpoint that never triggers, with no feedback to the LLM.

**Evidence:** `addLineBreakpointSafe` at lines 950–959 delegates directly to `bpManager.addLineBreakpoint(...)` without first checking whether the line is breakpointable. Placing a breakpoint on a comment, blank line, import statement, or inside a multi-line string creates a disabled breakpoint that appears in the gutter but never fires.

**Fix sketch:** Insert a pre-flight check at the top of `addLineBreakpointSafe` using the public API on [XDebuggerUtil.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebuggerUtil.java):

```kotlin
if (!XDebuggerUtil.getInstance().canPutBreakpointAt(project, file, line)) {
    throw BreakpointNotAllowedException(
        "Line ${line + 1} in ${file.name} is not breakpointable " +
        "(comment, blank line, or outside executable code). " +
        "Pick a line with a statement or expression."
    )
}
```

Catch `BreakpointNotAllowedException` in the `add_breakpoint` action handler and return it as a structured `ToolResult(isError=true)`.

---

## 4. Description-Quality Findings (D1–D8)

### D1 — Per-Action Preconditions Buried in Tail (HIGH-IMPACT)

**Severity:** HIGH-IMPACT — LLM has to read to the very last line of each description to learn which actions require suspension.

**Evidence:** All three meta-tool descriptions use the pattern:

```
- action1(params) → What it does
- action2(params) → What it does
...
Most actions require a suspended session.    ← buried at the end
```

**Fix:** Inline `[SUSPENDED]` / `[ANY]` tags per action line, and move them to the front. Example:

```
- get_variables(session_id?, variable_name?, max_depth?) [SUSPENDED] → Inspect local variables in current frame
- thread_dump(session_id?, ...) [ANY] → Full thread dump
```

---

### D2 — No Guidance to Call get_state First (HIGH)

**Severity:** HIGH — LLM often calls an inspection action without first confirming session state.

**Evidence:** None of the three meta-tool descriptions tell the LLM to call `debug_step(action=get_state)` before proceeding. When the LLM calls `debug_inspect(action=get_variables)` directly and hits `notSuspendedError`, it typically retries the same call rather than diagnosing session state.

**Fix:** Add an `IMPORTANT:` block at the top of `DebugInspectTool` and `DebugStepTool` descriptions:

```
IMPORTANT: Before calling any action below, run debug_step(action=get_state) first to
confirm the session is paused and (if multiple sessions are open) get the session_id.
```

---

### D3 — notSuspendedError Message Misleads LLM (MEDIUM)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt:227-232`

**Severity:** MEDIUM — LLM concludes the program is not at a breakpoint when the real cause is often multi-session mis-targeting.

**Evidence:** Current message at lines 227–232:

```kotlin
private fun notSuspendedError() = ToolResult(
    "Debug session is running but not paused. This action requires the debugger to be " +
    "suspended (at a breakpoint or after a step). Set a breakpoint and let execution " +
    "reach it, or call debug_step.pause first.",
    ...
)
```

After C1 is fixed, the remaining path into this error is: (a) genuinely no-paused-session, or (b) multi-session ambiguity that the new predicate didn't resolve. The message mentions neither. The LLM reads "running but not paused" and sets another breakpoint when it should instead pass `session_id`.

**Fix:** Rewrite:

```kotlin
private fun notSuspendedError() = ToolResult(
    "No suspended session resolved. Common causes: " +
        "(1) multiple sessions are open and the one you targeted is running — " +
        "pass session_id explicitly, since `currentSession` resolves to the last-focused " +
        "session (not necessarily the paused one); " +
        "(2) the program isn't at a breakpoint yet — set one and let execution reach it, " +
        "or call debug_step(action=pause). " +
        "Run debug_step(action=get_state) first to list sessions and their paused/running state.",
    "Not suspended",
    ToolResult.ERROR_TOKEN_ESTIMATE,
    isError = true,
)
```

Check `DebugStepTool.kt` for its own copy via:
```bash
grep -n "running but not paused" agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/*.kt
```
Apply the same text there if present.

---

### D4 — pause Described as Deterministic (LOW)

**Severity:** LOW — LLM expects pause to succeed immediately and doesn't retry or check state.

**Evidence:** `DebugStepTool.kt` description:

```
- pause(session_id?) → Pause execution
```

No mention of the best-effort nature. JVM pause via JDWP's `VirtualMachine.suspend()` can take seconds on a busy JVM or fail entirely when the VM is in native code or GC.

**Fix:** Add "(Best-effort — may take several seconds or fail if JVM is in native code or GC. Follow with get_state to confirm.)" to the `pause` entry.

---

### D5 — run_to_cursor Suspension Requirement Not Documented (LOW)

**Severity:** LOW — despite the name implying "resume to this line", it requires the session to already be suspended.

**Evidence:** `DebugStepTool.kt` description:

```
- run_to_cursor(file, line, session_id?) → Run to specific line
```

No `[SUSPENDED]` tag, no caveat. After C1 is fixed, the LLM may try `run_to_cursor` on a running session and get a confusing error.

**Fix:** Tag with `[SUSPENDED]` and add "(despite the name, requires current suspension — it resumes execution and pauses again at the target line)".

---

### D6 — No Guidance on force_step_into for Kotlin Inlined Bodies (LOW)

**Severity:** LOW — LLM prefers `step_into` for Kotlin lambda/inline inspection and gets confused when it steps over instead.

**Evidence:** `DebugStepTool.kt` description:

```
- force_step_into(session_id?) → Step into even library/framework code (bypasses step filters — use to enter Spring proxies, CGLIB, reflection)
```

No mention of Kotlin inlined function bodies, which are common in coroutine-heavy code. `step_into` is filtered by the Kotlin debugger plugin for `inline fun` calls and steps over them; `force_step_into` bypasses that filter.

**Fix:** Append "or Kotlin inlined bodies" to the existing `force_step_into` description.

---

### D7 — Unused description Parameter in All Three Debug Tool Schemas (LOW)

**Severity:** LOW — wastes ~60 output tokens per tool definition sent to the LLM.

**Evidence:**

- `DebugInspectTool.kt:130-133`: `"description" to ParameterProperty(type="string", description="Brief description ... (shown to user in approval dialog)")`
- `DebugStepTool.kt:78-81`: same pattern
- `DebugBreakpointsTool.kt:178-181`: same pattern

Debug tools are not in `APPROVAL_TOOLS` (per `agent/CLAUDE.md` — APPROVAL_TOOLS are edit_file, create_file, run_command, revert_file). The approval dialog is never shown for debug tools, so the `description` parameter is dead schema that the LLM sends bytes for and the tool ignores.

**Fix:** Delete the `"description" to ParameterProperty(...)` entry from all three schemas. Also remove any trailing comma on the preceding entry.

---

### D8 — CLAUDE.md Action Counts Out of Sync with Code (LOW)

**Severity:** LOW — stale documentation confuses developers and automated doc-readers.

**Evidence:** `agent/CLAUDE.md` lines 131–133:

```
| **debug_step** | 8 | get_state, step_over, step_into, step_out, resume, pause, run_to_cursor, stop |
| **debug_inspect** | 8 | evaluate, get_stack_frames, get_variables, thread_dump, memory_view, hotswap, force_return, drop_frame |
```

Actual enum values in `DebugStepTool.kt`:

```kotlin
enumValues = listOf(
    "get_state", "step_over", "step_into", "step_out",
    "force_step_into", "force_step_over",    // ← 2 additions not in CLAUDE.md
    "resume", "pause", "run_to_cursor", "stop"
)
```

That is 10 actions. Similarly, `DebugInspectTool.kt` enum has `set_value` in addition to the 8 listed, totaling 9 actions.

**Fix:** Update the CLAUDE.md Meta-Tools table:
- `debug_step`: `8 → 10`, add `force_step_into, force_step_over`
- `debug_inspect`: `8 → 9`, add `set_value`

---

## 5. Missing Capabilities (Deferred to Separate Plans)

These are out-of-scope for the current fix plan. Each needs its own brainstorm → plan cycle. Track as `project_debug_new_capabilities_plan.md`.

| # | Capability | Key API |
|---|-----------|---------|
| #1 | `get_threads` / `switch_thread` | [XSuspendContext.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/frame/XSuspendContext.java) — `getExecutionStacks()` + `XDebugSession.setCurrentStackFrame()` |
| #2 | `smart_step_into` with variant picker | [XSmartStepIntoHandler.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/stepping/XSmartStepIntoHandler.java) — `computeSmartStepVariants(position)` + `XDebugSession.smartStepInto()` |
| #3 | Kotlin lambda / inline breakpoint variants | `XLineBreakpointType.getAvailableVariants(project, position)` — currently a lambda breakpoint silently attaches to the outer line |
| #4 | `add_watch` / `list_watches` / `remove_watch` | No public platform API; build agent-side list, re-evaluate on each `sessionPaused` event |
| #5 | Conditional hit-count breakpoints | No public API; implement via `XDebugSessionListener.sessionPaused` counting per `XBreakpoint` identity |
| #6 | Dependent (master/slave) breakpoints | Internal [XDependentBreakpointManager](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-impl/src/com/intellij/xdebugger/impl/breakpoints/XDependentBreakpointManager.java) — wrap carefully or build a conditional expression gate instead |
| #7 | Kotlin coroutine debugger inspection | Research first — platform API may be partially internal and changing as of 2025.x |

---

## 6. Key IntelliJ Platform API Sources

All under `github.com/JetBrains/intellij-community`. Links are to the `master` branch; pin to the `2025.1` tag when referencing stable behavior.

| Class | Path | Relevance |
|-------|------|-----------|
| [XDebugSession.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebugSession.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/` | `isSuspended`, `getCurrentStackFrame`, `getSuspendContext`, `sessionPaused` event ordering |
| [XDebuggerManager.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebuggerManager.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/` | `getCurrentSession()` (last-focused, not necessarily paused), `getDebugSessions()` |
| [XBreakpointManager.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/breakpoints/XBreakpointManager.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/breakpoints/` | `removeBreakpoint` — requires write lock |
| [XDebuggerEvaluator.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/evaluation/XDebuggerEvaluator.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/evaluation/` | `evaluate(expression, callback, sourcePosition)` — 3rd param scopes locals/imports |
| [XValue.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/frame/XValue.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/frame/` | `computePresentation`, `getModifier` |
| [XValueContainer.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/frame/XValueContainer.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/frame/` | `computeChildren(XCompositeNode)` |
| [XStackFrame.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/frame/XStackFrame.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/frame/` | `getEvaluator()`, `getSourcePosition()` — both null-safe per frame |
| [XCompositeNode.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/frame/XCompositeNode.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/frame/` | `tooManyChildren(remaining)` contract |
| [XSuspendContext.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/frame/XSuspendContext.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/frame/` | `getActiveExecutionStack()`, `getExecutionStacks()` (threads) |
| [XExecutionStack.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/frame/XExecutionStack.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/frame/` | `computeStackFrames(firstFrameIndex, container)` |
| [XDebugSessionListener.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebugSessionListener.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/` | `sessionPaused` fires before frame fully populated |
| [XDebuggerUtil.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebuggerUtil.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/` | `createExpression(text, language, customInfo, mode)` (replaces internal XExpressionImpl), `canPutBreakpointAt(project, file, line)` |
| [XValueModifier.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/frame/XValueModifier.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/frame/` | `setValue(XExpression, XModificationCallback)` — must run on EDT |
| [XBreakpoint.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/breakpoints/XBreakpoint.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/breakpoints/` | Base breakpoint interface |
| [XLineBreakpoint.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/breakpoints/XLineBreakpoint.java) | `platform/xdebugger-api/src/com/intellij/xdebugger/breakpoints/` | Line-specific breakpoint type |
| [XDependentBreakpointManager.java](https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-impl/src/com/intellij/xdebugger/impl/breakpoints/XDependentBreakpointManager.java) | `platform/xdebugger-impl/src/com/intellij/xdebugger/impl/breakpoints/` | Internal — master/slave breakpoint dependency (capability #6) |

**Threading reference:** [IntelliJ Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html) — breakpoint mutations need write lock; `XValueModifier.setValue` needs EDT.

---

## 7. Findings Cross-Reference

| Finding | Task | File(s) | Severity |
|---------|------|---------|----------|
| C1 prefer uniquely-paused session | Task 2 | `IdeStateProbe.kt:88-99` | HIGH |
| C2 evaluate at non-zero frame | Tasks 6+7 | `AgentDebugController.kt:408-419` | HIGH |
| C3 XExpressionImpl internal API | Task 8 | `DebugInspectTool.kt:438` | MEDIUM |
| C4 tooManyChildren silent drop | Task 12 | `AgentDebugController.kt:327-330` | MEDIUM |
| C5 waitForPause race | Task 9 | `AgentDebugController.kt:181` | LOW |
| C6 WriteAction missing on dispose | Task 13 | `AgentDebugController.kt:519-544` | LOW |
| C7 canPutBreakpointAt missing | Task 11 | `DebugBreakpointsTool.kt:950-959` | LOW |
| D1 per-action precondition tags | Task 4 | All three tool description blocks | HIGH-IMPACT |
| D2 call get_state first hint | Task 4 | `DebugInspectTool.kt:42-57`, `DebugStepTool.kt:37-53` | HIGH |
| D3 notSuspendedError message | Task 3 | `DebugInspectTool.kt:227-232` | MEDIUM |
| D4 pause best-effort | Task 4 (verify Task 14) | `DebugStepTool.kt:37-53` | LOW |
| D5 run_to_cursor [SUSPENDED] | Task 4 (verify Task 14) | `DebugStepTool.kt:37-53` | LOW |
| D6 force_step_into Kotlin inline | Task 4 (verify Task 14) | `DebugStepTool.kt:37-53` | LOW |
| D7 unused description parameter | Task 10 | `DebugInspectTool.kt:130-133`, `DebugStepTool.kt:78-81`, `DebugBreakpointsTool.kt:178-181` | LOW |
| D8 CLAUDE.md action count drift | Task 5 | `agent/CLAUDE.md:131-133` | LOW |
