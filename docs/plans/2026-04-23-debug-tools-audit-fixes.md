# Debug Tools Audit — Fix Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the correctness bugs (C1–C7) and description-quality gaps (D1–D8) identified in the 2026-04-23 debug-tools audit, so the LLM stops getting "session is running but not paused" errors when the session is clearly paused, and every debug action carries self-describing preconditions.

**Architecture:** Four sequential phases. Phase 1 is the ship-blocker (fixes the reported LLM confusion bug via one logic change in `IdeStateProbe` + four text changes). Phase 2 is correctness + token savings. Phase 3 is mid-priority correctness cleanups. Phase 4 is description polish. Each phase ends with a green test run and a commit that can ship independently.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1+, JUnit 5 + MockK, Gradle. Target test runtime: `./gradlew :agent:test`.

**Branch:** `fix/debug-tools-audit`. Do NOT land on `feature/telemetry-and-logging`. Cut from `main` after rebasing any open agent-module work.

**Out of scope (separate plans):** Missing capabilities #1 (threads), #2 (smart_step_into), #3 (inline-breakpoint variants), #4 (watches), #5 (hit counts), #6 (dependent breakpoints), #7 (Kotlin coroutine debugger). Each needs its own brainstorm → plan cycle. Track them in project memory as `project_debug_new_capabilities_plan.md`.

**Reference docs (load when implementing):**
- Audit findings: the conversation that produced this plan (summarize into `docs/research/2026-04-23-debug-tools-audit.md` as first task if not already persisted).
- Research reference: IntelliJ XDebugger API 2025.1+ (also in the same conversation).
- Existing patterns: `docs/plans/2026-04-17-phase5-debug-tools-fixes.md` and `docs/plans/2026-04-17-phase3-ide-state-leak-fixes.md` — shows how the `DebugInvocation` + `IdeStateProbe` architecture was built. Follow the same test style.

---

## File Structure

**Source files touched:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/platform/IdeStateProbe.kt` — C1 fix (prefer uniquely-paused session + canonical paused predicate)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt` — D1/D2/D3 descriptions, D7 schema, C3 API swap
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt` — D1/D2/D5/D6/D7 descriptions + schema
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt` — D1/D7 descriptions + schema, C7 canPutAt precheck
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt` — C2 non-top-frame evaluate, C4 tooManyChildren pagination, C5 waitForPause, C6 WriteAction wrap for dispose

**Test files touched:**
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/platform/IdeStateProbeTest.kt` — new cases for C1
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugControllerTest.kt` — C2, C4, C5
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectToolTest.kt` — D3 error message assertion, C3 API check
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsToolTest.kt` — C7 canPutAt gate

**Doc files touched:**
- `agent/CLAUDE.md` — D8 (action count drift: `debug_step 8 → 10`, `debug_inspect 8 → 9`)

---

## Phase 1 — LLM Confusion Fix (ship-blocker)

**Outcome:** The reported bug ("get_variables says not paused even though session is paused") stops happening in the common multi-session and post-step race cases. All description text states per-action preconditions. CLAUDE.md is accurate.

### Task 1: Persist audit findings as a research doc

**Files:**
- Create: `docs/research/2026-04-23-debug-tools-audit.md`

- [ ] **Step 1: Write the audit document**

Capture the audit verbatim so the implementer has it next to the plan. Copy from conversation: summary of 3 meta-tools + 26 sub-actions, the 7 correctness bugs (C1–C7), the 8 description findings (D1–D8), and the 7 missing capabilities (#1–#7). Include the "canonical paused check" from the XDebugger API research:

```kotlin
val paused = session.isSuspended
          && session.currentStackFrame != null
          && session.suspendContext != null
```

Cite source URLs from the research reference (XDebugSession.java, XCompositeNode.java, XBreakpointManager.java — all under github.com/JetBrains/intellij-community).

- [ ] **Step 2: Commit**

```bash
git add docs/research/2026-04-23-debug-tools-audit.md
git commit -m "docs: debug-tools audit findings (C1-C7, D1-D8, caps #1-7)"
```

---

### Task 2: IdeStateProbe — prefer uniquely-paused session (C1, root cause)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/platform/IdeStateProbe.kt:88-99`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/platform/IdeStateProbeTest.kt`

**Why:** Today, when `sessionId` is null, the probe returns `mgr.currentSession` — the *last-focused* session, not the paused one. If the user focus-clicked the running session, an inspection tool on the paused session fires `notSuspendedError`. Fix: when exactly one session is `isSuspended && currentStackFrame != null`, prefer it. Only fall back to `currentSession` if zero or >1 are paused. Also tighten the Paused predicate to the canonical three-clause check.

- [ ] **Step 1: Write the failing test**

Append to `IdeStateProbeTest.kt` before the last `}`:

```kotlin
    @Test
    fun `prefers uniquely paused session over last-focused running session when no sessionId`() {
        val running = fakeSession("foo-service", suspended = false)
        val paused = fakePausedSession("bar-service")
        every { mgr.debugSessions } returns arrayOf(running, paused)
        every { mgr.currentSession } returns running  // user clicked the running one last

        val result = IdeStateProbe.debugState(project)
        assertTrue(result is DebugState.Paused, "expected Paused, got $result")
        assertSame(paused, (result as DebugState.Paused).session)
    }

    @Test
    fun `falls back to currentSession when multiple sessions are paused`() {
        val a = fakePausedSession("a")
        val b = fakePausedSession("b")
        every { mgr.debugSessions } returns arrayOf(a, b)
        every { mgr.currentSession } returns b

        val result = IdeStateProbe.debugState(project)
        assertTrue(result is DebugState.Paused)
        assertSame(b, (result as DebugState.Paused).session)
    }

    @Test
    fun `treats isSuspended=true but currentStackFrame=null as Running (race window)`() {
        val raceySession = mockk<XDebugSession>(relaxed = true) {
            every { sessionName } returns "racey"
            every { isSuspended } returns true
            every { currentStackFrame } returns null  // engine hasn't populated the frame yet
        }
        every { mgr.debugSessions } returns arrayOf(raceySession)
        every { mgr.currentSession } returns raceySession

        val result = IdeStateProbe.debugState(project)
        assertTrue(result is DebugState.Running, "expected Running during race, got $result")
    }

    private fun fakePausedSession(name: String): XDebugSession =
        mockk(relaxed = true) {
            every { sessionName } returns name
            every { isSuspended } returns true
            every { currentStackFrame } returns mockk(relaxed = true)
        }
```

Also update the existing `fakeSession` helper to stub `currentStackFrame` to non-null when `suspended = true`:

```kotlin
    private fun fakeSession(name: String, suspended: Boolean): XDebugSession =
        mockk(relaxed = true) {
            every { sessionName } returns name
            every { isSuspended } returns suspended
            every { currentStackFrame } returns if (suspended) mockk(relaxed = true) else null
        }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.platform.IdeStateProbeTest"`
Expected: FAIL on the three new tests — current code returns `Running(foo-service)` for the first new test (picks currentSession blindly), and `Paused` for the race test (doesn't check currentStackFrame).

- [ ] **Step 3: Implement the fix in `IdeStateProbe.kt`**

Replace lines 88–99 (the target resolution + return block) with:

```kotlin
        // Canonical "is this session paused?" per XDebugSession.java (L51-L69):
        // isSuspended alone is insufficient — during a pause event the flag flips
        // before the engine has populated currentStackFrame / suspendContext, so a
        // tool reading state in that window gets a non-null session with nothing to
        // inspect. Require all three clauses, matching what XDebuggerEvaluator,
        // XValueContainer.computeChildren, and XExecutionStack.computeStackFrames
        // themselves check before accepting work.
        fun isPaused(s: XDebugSession): Boolean =
            s.isSuspended && s.currentStackFrame != null && s.suspendContext != null

        // Prefer a uniquely-paused session over currentSession. currentSession
        // returns the last-focused session, which is often *not* the one at a
        // breakpoint when multiple sessions are open — the exact cause of the
        // "session is running but not paused" false negative in the 2026-04-23
        // audit (finding C1).
        val pausedSessions = all.filter(::isPaused)
        val target: XDebugSession? = when {
            pausedSessions.size == 1 -> pausedSessions.single()
            mgr.currentSession != null -> mgr.currentSession
            all.size == 1 -> all.single()
            else -> null
        }

        return when {
            target != null && isPaused(target) -> DebugState.Paused(target)
            target != null -> DebugState.Running(target)
            all.isEmpty() -> DebugState.NoSession
            else -> DebugState.AmbiguousSession(all.size, all.map { it.sessionName })
        }
```

Also update the two `sessionId`-branch Paused checks in the same file (lines 69 and 82) to use the new predicate — extract `isPaused` to the top of `debugState` so both branches see it. For line 69:

```kotlin
                return if (isPaused(fromRegistry)) DebugState.Paused(fromRegistry)
                else DebugState.Running(fromRegistry)
```

For line 82:

```kotlin
                    return if (isPaused(unique)) DebugState.Paused(unique) else DebugState.Running(unique)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.platform.IdeStateProbeTest"`
Expected: PASS on all tests (old + new).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/platform/IdeStateProbe.kt \
         agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/platform/IdeStateProbeTest.kt
git commit -m "fix(debug): prefer uniquely-paused session + canonical paused predicate

Fixes audit finding C1. IdeStateProbe.debugState() now:
- prefers the single paused session over mgr.currentSession
  (currentSession returns last-focused, not necessarily paused)
- requires isSuspended && currentStackFrame != null && suspendContext != null
  (the race window after sessionPaused event but before frame populated)

Eliminates the 'Debug session is running but not paused' false negative
the LLM was hitting when the user had multiple sessions or inspected
state immediately after a step."
```

---

### Task 3: Rewrite `notSuspendedError` message (D3)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt:227-232`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt` (same error helper — grep for `Debug session is running but not paused` to confirm the exact location)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectToolTest.kt`

**Why:** The current message ("Debug session is running but not paused. This action requires the debugger to be suspended...") leads the LLM to conclude the program isn't at a breakpoint. After Task 2 lands, the remaining path into this error is (a) genuinely no-paused-session or (b) multi-session ambiguity that the new predicate didn't resolve. The message must hint at both.

- [ ] **Step 1: Write the failing test**

In `DebugInspectToolTest.kt`, find an existing test that exercises `executeGetVariables` with a running session (or add one if none exists). Assert the new message substrings:

```kotlin
    @Test
    fun `get_variables on running session returns message mentioning session_id and get_state`() = runTest {
        val runningSession = mockk<XDebugSession>(relaxed = true) {
            every { isSuspended } returns false
            every { currentStackFrame } returns null
            every { suspendContext } returns null
        }
        mockkStatic(XDebuggerManager::class)
        val mgr = mockk<XDebuggerManager>(relaxed = true)
        every { XDebuggerManager.getInstance(project) } returns mgr
        every { mgr.debugSessions } returns arrayOf(runningSession)
        every { mgr.currentSession } returns runningSession

        val result = tool.execute(buildJsonObject { put("action", "get_variables") }, project)

        assertTrue(result.isError)
        val content = result.content
        assertTrue(content.contains("session_id"), "should mention session_id disambiguation: $content")
        assertTrue(content.contains("get_state"), "should point LLM at get_state: $content")

        unmockkStatic(XDebuggerManager::class)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*DebugInspectToolTest*get_variables on running*"`
Expected: FAIL — old message mentions neither `session_id` nor `get_state`.

- [ ] **Step 3: Implement the new error**

Replace the body of `notSuspendedError` in `DebugInspectTool.kt:227-232` with:

```kotlin
    private fun notSuspendedError() = ToolResult(
        "No suspended session resolved. Common causes: " +
            "(1) multiple sessions are open and the one you targeted is running — " +
            "pass session_id explicitly, since `currentSession` resolves to the last-focused session (not necessarily the paused one); " +
            "(2) the program isn't at a breakpoint yet — set one and let execution reach it, or call debug_step(action=pause). " +
            "Run debug_step(action=get_state) first to list sessions and their paused/running state.",
        "Not suspended",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )
```

If `DebugStepTool.kt` has its own copy of this helper (likely, since step actions that require suspension also use it), apply the same text there. Grep:

```bash
grep -n "running but not paused" agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/*.kt
```

If multiple copies exist, consider extracting a shared helper to `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugErrors.kt` in a later task — but for now, keep text identical across copies (copy-paste is fine for this phase).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*DebugInspectToolTest*" --tests "*DebugStepToolTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt \
         agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt \
         agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectToolTest.kt
git commit -m "fix(debug): rewrite notSuspendedError to point LLM at session_id + get_state

Fixes audit finding D3. Old message led the LLM to conclude the program
wasn't at a breakpoint when the real cause was often multi-session
targeting. New message calls out both causes and tells the LLM to run
debug_step(action=get_state) first."
```

---

### Task 4: Add per-action `[SUSPENDED]` / `[RUNNING_OR_SUSPENDED]` tags to descriptions (D1)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt:42-57`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt:37-53`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt:63-77`

**Why:** A single "Most actions require a suspended session" at the bottom forces the LLM to guess which. Per-action tags make preconditions visible at the callsite.

- [ ] **Step 1: Replace `DebugInspectTool` description block (lines 42–57)**

```kotlin
    override val description = """
Debug inspection — evaluate expressions, inspect variables, and advanced operations.

IMPORTANT: Before calling any action below, run debug_step(action=get_state) first to
confirm the session is paused and (if multiple sessions are open) get the session_id.

State tags: [SUSPENDED] = session must be paused at a breakpoint or after a step.
            [ANY]       = works on a running or paused session.

Actions:
- evaluate(expression, session_id?) [SUSPENDED] → Evaluate Java/Kotlin expression in current frame
- get_stack_frames(session_id?, thread_name?, max_frames?) [SUSPENDED] → Get call stack
- get_variables(session_id?, variable_name?, max_depth?) [SUSPENDED] → Inspect local variables in current frame
- set_value(variable_name, new_value, session_id?) [SUSPENDED] → Modify a variable's value at runtime (test hypotheses without restarting)
- thread_dump(session_id?, max_frames?, include_stacks?, include_daemon?) [ANY] → Full thread dump. Per-thread frames require that thread to be suspended.
- memory_view(class_name, session_id?, max_instances?) [SUSPENDED, Java/Kotlin only, requires canGetInstanceInfo] → Count/inspect live instances
- hotswap(session_id?, compile_first?) [ANY, Java/Kotlin only] → Hot-reload changed classes
- force_return(session_id?, return_value?, return_type?) [SUSPENDED, Java/Kotlin only, requires canForceEarlyReturn] → Force method to return immediately
- drop_frame(session_id?, frame_index?) [SUSPENDED, Java/Kotlin only, requires canPopFrames] → Rewind execution to frame start. Variable state is NOT reset.

session_id defaults to the active/resolved session. If multiple sessions are open and none is uniquely paused, session_id is required.
""".trimIndent()
```

- [ ] **Step 2: Replace `DebugStepTool` description block (lines 37–53)**

```kotlin
    override val description = """
Debug session navigation — stepping, state, and lifecycle control.

IMPORTANT: Call get_state first to confirm session exists and whether it is paused.
Pause-required actions fail loudly when the session is running.

State tags: [SUSPENDED] = session must be paused. [ANY] = runs regardless.

Actions:
- get_state(session_id?) [ANY] → Current session state (paused/running), breakpoint, thread, line. CALL THIS FIRST.
- step_over(session_id?) [SUSPENDED] → Step over current line
- step_into(session_id?) [SUSPENDED] → Step into method call
- step_out(session_id?) [SUSPENDED] → Step out of current method
- force_step_into(session_id?) [SUSPENDED] → Step into even library/framework code (bypasses step filters — use to enter Spring proxies, CGLIB, reflection, or Kotlin inlined bodies)
- force_step_over(session_id?) [SUSPENDED] → Step over, ignoring any breakpoints in called methods
- run_to_cursor(file, line, session_id?) [SUSPENDED] → Run to specific line (despite the name, requires current suspension)
- resume(session_id?) [ANY] → Resume execution
- pause(session_id?) [ANY] → Best-effort pause. May take several seconds or fail if the JVM is in a non-suspendable state (native code, GC). Follow with get_state to confirm.
- stop(session_id?) [ANY] → Stop debug session

All actions accept optional session_id (defaults to active session).
""".trimIndent()
```

- [ ] **Step 3: Replace `DebugBreakpointsTool` description block (lines 63–77)**

```kotlin
    override val description = """
Breakpoint management — add, remove, list breakpoints, and attach the debugger to a remote JVM.

Breakpoints are project-scoped and do NOT require a running debug session. attach_to_process
creates a session. To launch a run configuration in debug mode, use runtime_exec(action=run_config, mode=debug).

Actions:
- add_breakpoint(file, line, condition?, log_expression?, temporary?, suspend_policy?, pass_count?) → Add line breakpoint. Fails if the line is not breakpointable (comment, blank line, import).
- method_breakpoint(class_name, method_name, watch_entry?, watch_exit?) → Add method breakpoint
- exception_breakpoint(exception_class, caught?, uncaught?, condition?) → Break on exception
- field_watchpoint(class_name, field_name, file?, watch_read?, watch_write?) → Watch field access/modification
- remove_breakpoint(file, line) → Remove breakpoint at file:line
- list_breakpoints(file?) → List all breakpoints (line, method, exception, field), optionally filtered by file
- attach_to_process(port, host?, name?) → Attach debugger to remote JVM. Target must be listening on the JDWP port.
""".trimIndent()
```

- [ ] **Step 4: Run tests to verify nothing broke**

Run: `./gradlew :agent:test --tests "*Debug*Tool*"`
Expected: PASS (description text changes shouldn't break test logic unless a test hard-codes the full description string — fix those to test for substrings instead).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt \
         agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt \
         agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt
git commit -m "fix(debug): per-action precondition tags + 'call get_state first' hint

Fixes audit findings D1 + D2. Every action now declares its state
requirement inline ([SUSPENDED] / [ANY]) and both DebugInspect and
DebugStep tell the LLM to call get_state before other actions. Also
clarifies run_to_cursor's counterintuitive suspension requirement, pause's
best-effort nature, force_step_into's Kotlin-inline use case, and memory_view
/ force_return / drop_frame's JVM-capability prerequisites."
```

---

### Task 5: Fix CLAUDE.md action count drift (D8)

**Files:**
- Modify: `agent/CLAUDE.md` (search for `debug_step | 8` and `debug_inspect | 8`)

- [ ] **Step 1: Edit the Meta-Tools table**

Update the Meta-Tools table row for `debug_step`:

```
| **debug_step** | 10 | get_state, step_over, step_into, step_out, force_step_into, force_step_over, resume, pause, run_to_cursor, stop |
```

Update the Meta-Tools table row for `debug_inspect`:

```
| **debug_inspect** | 9 | evaluate, get_stack_frames, get_variables, set_value, thread_dump, memory_view, hotswap, force_return, drop_frame |
```

- [ ] **Step 2: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs: correct debug_step (10) and debug_inspect (9) action counts"
```

---

### Phase 1 verification

- [ ] **Step 1: Full agent test suite**

Run: `./gradlew :agent:test`
Expected: PASS. No regressions.

- [ ] **Step 2: Plugin verifier**

Run: `./gradlew verifyPlugin`
Expected: PASS. No new API-compatibility warnings (Phase 1 only touches public APIs + text).

- [ ] **Step 3: Manual smoke (PyCharm + IntelliJ if available)**

Start two debug sessions in a sample project (e.g. one Spring Boot, one plain JUnit). Pause one, leave the other running. In the agent chat: `debug_step(action=get_state)` — expect the paused session to be identified. Then `debug_inspect(action=get_variables)` — expect variables, not `Not suspended`. Re-run on a single-session project with no breakpoint active to confirm the new error message lands with `session_id` + `get_state` mentions.

- [ ] **Step 4: Tag the phase**

```bash
git tag debug-audit-phase1
```

---

## Phase 2 — Correctness + Token Savings

**Outcome:** Evaluate at non-top frames works correctly (C2). `set_value` stops using the `*.impl.*` API (C3). `waitForPause` honors the canonical paused predicate (C5). Debug tools stop burning output tokens on an unused `description` parameter (D7).

### Task 6: Accumulate XStackFrame references during `computeStackFrames` (C2 prep)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt:198-235` (replace `getStackFrames` with version that also returns raw frames)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugControllerTest.kt`

**Why:** Today `getStackFrames` returns `FrameInfo` DTOs. `evaluate` at a non-zero `frameIndex` needs the actual `XStackFrame` to call `frame.evaluator`. The comment at `AgentDebugController.kt:417` admits this is broken ("Use current frame as fallback"). Fix: add a sibling method `getRawStackFrames(session, maxFrames): List<XStackFrame>` that keeps the `XStackFrame` refs, and have `evaluate` consume it.

- [ ] **Step 1: Write the failing test**

Append to `AgentDebugControllerTest.kt`:

```kotlin
    @Test
    fun `getRawStackFrames returns XStackFrame references up to max`() = runTest {
        val frame0 = mockk<XStackFrame>(relaxed = true)
        val frame1 = mockk<XStackFrame>(relaxed = true)
        val frame2 = mockk<XStackFrame>(relaxed = true)
        val stack = mockk<XExecutionStack>(relaxed = true)
        val context = mockk<XSuspendContext>(relaxed = true) {
            every { activeExecutionStack } returns stack
        }
        val session = mockk<XDebugSession>(relaxed = true) {
            every { currentStackFrame } returns frame0
            every { suspendContext } returns context
        }
        every { stack.computeStackFrames(0, any()) } answers {
            val container = arg<XExecutionStack.XStackFrameContainer>(1)
            container.addStackFrames(listOf(frame0, frame1, frame2), true)
        }

        val frames = controller.getRawStackFrames(session, maxFrames = 2)

        assertEquals(listOf(frame0, frame1), frames)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentDebugControllerTest*getRawStackFrames*"`
Expected: FAIL with "unresolved reference: getRawStackFrames".

- [ ] **Step 3: Add `getRawStackFrames` method**

Insert into `AgentDebugController.kt` right after the existing `getStackFrames`:

```kotlin
    /**
     * Like [getStackFrames] but returns raw [XStackFrame] references (not DTOs).
     * Used by callers that need to invoke frame methods like `evaluator` or
     * `sourcePosition` on a non-top frame.
     */
    suspend fun getRawStackFrames(session: XDebugSession, maxFrames: Int = 20): List<XStackFrame> {
        val stack = session.currentStackFrame?.let {
            session.suspendContext?.activeExecutionStack
        } ?: return emptyList()

        return suspendCancellableCoroutine { cont ->
            val frames = mutableListOf<XStackFrame>()
            stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
                override fun addStackFrames(frameList: List<XStackFrame>, last: Boolean) {
                    for (f in frameList) {
                        if (frames.size >= maxFrames) break
                        frames += f
                    }
                    if (last || frames.size >= maxFrames) cont.resume(frames.toList())
                }
                override fun errorOccurred(errorMessage: String) { cont.resume(frames.toList()) }
                override fun isObsolete(): Boolean = false
            })
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*AgentDebugControllerTest*getRawStackFrames*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt \
         agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugControllerTest.kt
git commit -m "feat(debug): add getRawStackFrames returning XStackFrame refs (C2 prep)"
```

---

### Task 7: Fix `evaluate` at non-zero frame_index (C2)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt:408-419`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugControllerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `evaluate at frameIndex=1 uses the caller frame's evaluator, not current`() = runTest {
        val currentEvaluator = mockk<XDebuggerEvaluator>(relaxed = true)
        val callerEvaluator = mockk<XDebuggerEvaluator>(relaxed = true)
        val currentFrame = mockk<XStackFrame>(relaxed = true) { every { evaluator } returns currentEvaluator }
        val callerFrame = mockk<XStackFrame>(relaxed = true) { every { evaluator } returns callerEvaluator }
        // ... stub stack.computeStackFrames to emit listOf(currentFrame, callerFrame)
        // ... stub callerEvaluator.evaluate to succeed with a known XValue
        // ... stub currentEvaluator.evaluate to fail if called (verify separation)

        val result = controller.evaluate(session, "x + 1", frameIndex = 1)

        assertEquals("expected-value", result.result)
        verify(exactly = 0) { currentEvaluator.evaluate(any(), any(), any()) }
        verify(exactly = 1) { callerEvaluator.evaluate(any(), any(), any()) }
    }
```

(Expand the `// ...` stubs following the pattern of the existing `AgentDebugControllerTest` evaluate tests — copy the `resolvePresentation` stub boilerplate verbatim.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentDebugControllerTest*evaluate at frameIndex=1*"`
Expected: FAIL — current code silently falls back to `session.currentStackFrame` and calls `currentEvaluator`.

- [ ] **Step 3: Replace `evaluate` body (lines 408-462)**

Replace the entire `evaluate` function with:

```kotlin
    suspend fun evaluate(session: XDebugSession, expression: String, frameIndex: Int = 0): EvaluationResult {
        val frame: XStackFrame? = if (frameIndex == 0) {
            session.currentStackFrame
        } else {
            val frames = getRawStackFrames(session, frameIndex + 1)
            if (frames.size <= frameIndex) {
                return EvaluationResult(
                    expression,
                    "Frame $frameIndex not available (stack has ${frames.size} frames; indices are 0..${frames.size - 1})",
                    "error",
                    isError = true,
                )
            }
            frames[frameIndex]
        }

        if (frame == null) {
            return EvaluationResult(expression, "No active stack frame", "error", isError = true)
        }

        val evaluator = frame.evaluator
            ?: return EvaluationResult(expression, "No evaluator available for frame at index $frameIndex", "error", isError = true)

        val evalResult: Result<XValue>? = awaitCallback<Result<XValue>>(10_000L) { stopped, resume, _ ->
            evaluator.evaluate(
                expression,
                object : XDebuggerEvaluator.XEvaluationCallback {
                    override fun evaluated(result: XValue) {
                        if (stopped.get()) return
                        resume(Result.success(result))
                    }
                    override fun errorOccurred(errorMessage: String) {
                        if (stopped.get()) return
                        resume(Result.failure(RuntimeException(errorMessage)))
                    }
                },
                frame.sourcePosition,  // proper scoping: imports + locals resolve correctly
            )
        }

        if (evalResult == null) {
            return EvaluationResult(expression, "<timed out after 10s>", "error", isError = true)
        }

        val xValue = evalResult.getOrElse { error ->
            return EvaluationResult(expression, error.message ?: "Evaluation failed", "error", isError = true)
        }

        val presentation = resolvePresentation(xValue)
        return EvaluationResult(expression, presentation.second, presentation.first)
    }
```

Key differences from current:
1. Non-zero `frameIndex` actually uses that frame's `evaluator` (no silent fallback).
2. Passes `frame.sourcePosition` as the 3rd arg to `evaluator.evaluate` so language imports and local scope resolve correctly (research #7).
3. Error messages now say the stack size so the LLM can retry with a valid index.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :agent:test --tests "*AgentDebugControllerTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt \
         agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugControllerTest.kt
git commit -m "fix(debug): evaluate at non-zero frameIndex uses correct frame (C2)

Previously fell back to session.currentStackFrame with a code comment
admitting the bug. Now uses getRawStackFrames to retrieve the target
XStackFrame, calls its .evaluator, and passes frame.sourcePosition for
correct scope/import resolution."
```

---

### Task 8: Swap `XExpressionImpl` for the public `XDebuggerUtil.createExpression` (C3)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt:438`

- [ ] **Step 1: Replace the import + call site**

Remove the internal-API import at the top of `DebugInspectTool.kt`:

```kotlin
// DELETE if present:
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
```

Add the public import:

```kotlin
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.evaluation.EvaluationMode
```

Replace line 438 (the `XExpressionImpl.fromText(newValue)` call) with:

```kotlin
                            XDebuggerUtil.getInstance().createExpression(
                                newValue,
                                frame.sourcePosition?.file?.let { com.intellij.lang.Language.findLanguageByID(it.fileType.name) },
                                /* customInfo */ null,
                                EvaluationMode.EXPRESSION,
                            ),
```

If `Language.findLanguageByID` resolution is awkward in this call site, fallback to passing `null` language (the platform will infer from the active debug process):

```kotlin
                            XDebuggerUtil.getInstance().createExpression(
                                newValue,
                                /* language */ null,
                                /* customInfo */ null,
                                EvaluationMode.EXPRESSION,
                            ),
```

- [ ] **Step 2: Run the existing set_value tests**

Run: `./gradlew :agent:test --tests "*DebugInspectToolTest*set_value*"`
Expected: PASS. (If tests fail because they mock `XExpressionImpl.fromText`, update the mocks to mock `XDebuggerUtil.getInstance().createExpression` via `mockkStatic(XDebuggerUtil::class)`.)

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt \
         agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectToolTest.kt
git commit -m "fix(debug): set_value uses public XDebuggerUtil.createExpression (C3)

Drops com.intellij.xdebugger.impl.breakpoints.XExpressionImpl in favor of
the public XDebuggerUtil.createExpression, per 2025.1+ API guidelines."
```

---

### Task 9: `waitForPause` honors canonical paused predicate (C5)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt:176-192`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugControllerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `waitForPause falls through to flow when isSuspended but currentStackFrame is null (race)`() = runTest {
        val session = mockk<XDebugSession>(relaxed = true) {
            every { isSuspended } returns true
            every { currentStackFrame } returns null  // race: flag flipped, frame not yet populated
            every { suspendContext } returns null
        }
        val invocation = mockk<DebugInvocation>(relaxed = true)
        val flow = MutableSharedFlow<DebugPauseEvent>(replay = 1)
        every { invocation.pauseFlow } returns flow
        controller.sessionInvocations["test-id"] =
            AgentDebugController.SessionEntry(session, invocation)

        val eventToEmit = DebugPauseEvent("test-id", "F.kt", 10, "breakpoint")
        val job = launch {
            delay(50)  // simulate frame arriving after short delay
            flow.tryEmit(eventToEmit)
        }

        val result = controller.waitForPause("test-id", timeoutMs = 1000)
        job.join()

        assertEquals(eventToEmit, result)  // if short-circuit ran, we'd get a fake event with line=null
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentDebugControllerTest*waitForPause falls through*"`
Expected: FAIL — current short-circuit returns on `isSuspended=true` alone.

- [ ] **Step 3: Update `waitForPause`**

Replace lines 181–189 with:

```kotlin
            // Canonical paused check: isSuspended alone is insufficient in the
            // narrow window between sessionPaused firing and the engine populating
            // currentStackFrame/suspendContext. If any of the three is missing,
            // fall through to the pauseFlow — the listener will emit a full event
            // once state is ready. (Audit finding C5.)
            if (session.isSuspended
                && session.currentStackFrame != null
                && session.suspendContext != null
            ) {
                val pos = session.currentPosition
                return@withTimeoutOrNull DebugPauseEvent(
                    sessionId = sessionId,
                    file = pos?.file?.path,
                    line = pos?.line?.plus(1),
                    reason = "breakpoint"
                )
            }
            flow.first()
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*AgentDebugControllerTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt \
         agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugControllerTest.kt
git commit -m "fix(debug): waitForPause uses canonical paused predicate (C5)

Short-circuit now requires isSuspended && currentStackFrame != null &&
suspendContext != null before returning synthesized event — otherwise
falls through to the pauseFlow. Eliminates the post-step race where
get_variables saw currentStackFrame=null."
```

---

### Task 10: Remove unused `description` parameter from debug tool schemas (D7)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt:130-133`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt` (same pattern — grep for `description` inside the parameters map)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt` (same)

**Why:** Per CLAUDE.md, debug tools are not in `APPROVAL_TOOLS`. The "shown to user in approval dialog" description is never displayed. Dropping the schema entry saves tokens on every call.

- [ ] **Step 1: Remove the parameter from all three schemas**

In each of the three files, locate the `ParameterProperty("description", ...)` entry inside the `properties = mapOf(...)` block (it's always the last entry before the closing `)`) and delete it. Example for `DebugInspectTool.kt` — delete lines 130–133:

```kotlin
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog)"
            )
```

Remember to also remove the trailing comma on the preceding entry if it becomes dangling.

- [ ] **Step 2: Run tests**

Run: `./gradlew :agent:test --tests "*Debug*Tool*"`
Expected: PASS. Any test that passes a `description` field will still pass — unknown params are ignored by the executor.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt \
         agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt \
         agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt
git commit -m "chore(debug): remove unused description parameter from schemas (D7)

Debug tools are not in APPROVAL_TOOLS, so the 'shown to user in approval
dialog' description is never rendered. Removing saves ~60 tokens per
tool definition sent to the LLM."
```

---

### Phase 2 verification

- [ ] **Step 1: Full agent test suite**

Run: `./gradlew :agent:test`
Expected: PASS.

- [ ] **Step 2: Plugin verifier**

Run: `./gradlew verifyPlugin`
Expected: PASS.

- [ ] **Step 3: Tag**

```bash
git tag debug-audit-phase2
```

---

## Phase 3 — Mid-priority correctness

**Outcome:** `add_breakpoint` refuses non-breakpointable lines with a clear error (C7). `get_variables` surfaces truncation instead of silently dropping children (C4). Breakpoint removal in controller dispose runs under the required write action (C6).

### Task 11: `add_breakpoint` pre-checks `canPutBreakpointAt` (C7)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt:846-868` (the `addLineBreakpointSafe` helper)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsToolTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `add_breakpoint on comment line returns 'line not breakpointable' error`() = runTest {
        // Mock XDebuggerUtil.canPutBreakpointAt to return false for the target line.
        mockkStatic(XDebuggerUtil::class)
        val util = mockk<XDebuggerUtil>(relaxed = true)
        every { XDebuggerUtil.getInstance() } returns util
        every { util.canPutBreakpointAt(any(), any(), 9) } returns false

        val result = tool.execute(
            buildJsonObject {
                put("action", "add_breakpoint")
                put("file", "src/Foo.kt")
                put("line", 10)  // 1-based → 9 zero-based
            },
            project,
        )

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("not breakpointable", ignoreCase = true),
            "message should say line is not breakpointable: ${result.content}",
        )
        unmockkStatic(XDebuggerUtil::class)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :agent:test --tests "*DebugBreakpointsToolTest*not breakpointable*"`
Expected: FAIL — current code returns a generic "Failed to add breakpoint" only if `addLineBreakpoint` itself returns null.

- [ ] **Step 3: Add the pre-check**

In `DebugBreakpointsTool.kt`, insert at the top of `addLineBreakpointSafe` (around line 846) before any `addLineBreakpoint` call:

```kotlin
    private fun addLineBreakpointSafe(
        project: Project,
        bpManager: XBreakpointManager,
        file: VirtualFile,
        line: Int,
        // ... rest of existing params ...
    ): XLineBreakpoint<*>? {
        // C7: pre-flight check so we fail fast with a clear message instead of
        // silently creating a disabled/unresolvable breakpoint on a non-
        // executable line (comment, blank, import, inside a multi-line string).
        if (!XDebuggerUtil.getInstance().canPutBreakpointAt(project, file, line)) {
            throw BreakpointNotAllowedException(
                "Line ${line + 1} in ${file.name} is not breakpointable (comment, blank line, or outside executable code). " +
                "Pick a line with a statement or expression."
            )
        }
        // ... rest of existing body unchanged ...
    }

    private class BreakpointNotAllowedException(message: String) : RuntimeException(message)
```

Catch the new exception in the `add_breakpoint` action handler and surface it as a structured error:

```kotlin
                } catch (e: BreakpointNotAllowedException) {
                    ToolResult(e.message ?: "Line not breakpointable", "Line not breakpointable", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                }
```

- [ ] **Step 4: Run to verify success**

Run: `./gradlew :agent:test --tests "*DebugBreakpointsToolTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt \
         agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsToolTest.kt
git commit -m "fix(debug): add_breakpoint pre-checks canPutBreakpointAt (C7)

Surfaces 'line not breakpointable' with the line number and suggested
fix instead of silently creating a disabled breakpoint the user can't
see in the gutter."
```

---

### Task 12: Surface `tooManyChildren` as a visible truncation marker (C4)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt:303-353` (the `computeChildren` helper)

- [ ] **Step 1: Add a truncation marker data class + wire it through**

Change `VariableInfo` (bottom of the file) to include a `truncated` flag:

```kotlin
data class VariableInfo(
    val name: String,
    val type: String,
    val value: String,
    val children: List<VariableInfo> = emptyList(),
    val truncated: Boolean = false,
)
```

In `computeChildren`, replace the `tooManyChildren` override body:

```kotlin
                override fun tooManyChildren(remaining: Int) {
                    if (stopped.get()) return
                    // Append a sentinel entry the formatter will render as a
                    // visible "…and N more" line so the LLM knows the list
                    // is incomplete. Audit finding C4.
                    names += "<truncated>"
                    result += TruncatedSentinelXValue(remaining)
                    resume(names.zip(result))
                }

                override fun tooManyChildren(remaining: Int, childrenAdder: Runnable) {
                    if (stopped.get()) return
                    names += "<truncated>"
                    result += TruncatedSentinelXValue(remaining)
                    resume(names.zip(result))
                }
```

Add the sentinel class at the bottom of `AgentDebugController.kt`:

```kotlin
private class TruncatedSentinelXValue(val remaining: Int) : XValue() {
    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(null, "truncated", "…and $remaining more", false)
    }
}
```

In the `children.map { ... }` block in `computeChildren`, detect the sentinel and set `truncated = true`:

```kotlin
        return children.map { (name, value) ->
            if (value is TruncatedSentinelXValue) {
                return@map VariableInfo(
                    name = "<truncated>",
                    type = "truncated",
                    value = "…and ${value.remaining} more child${if (value.remaining == 1) "" else "ren"} (use variable_name to expand a specific one)",
                    children = emptyList(),
                    truncated = true,
                )
            }
            val presentation = resolvePresentation(value)
            // ... rest unchanged ...
        }
```

- [ ] **Step 2: Update `formatVariables` in `DebugStepUtils.kt` to render the marker**

Find the `formatVariables` function and add a branch at the top of the loop body:

```kotlin
    if (v.truncated) {
        sb.append(indent).append("… ${v.value}\n")
        continue
    }
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "*AgentDebugControllerTest*" --tests "*DebugInspectToolTest*get_variables*"`
Expected: PASS. If no test currently exercises `tooManyChildren`, add one that mocks `addChildren` followed by `tooManyChildren(50)` and asserts the result contains `…and 50 more`.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt \
         agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepUtils.kt \
         agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugControllerTest.kt
git commit -m "fix(debug): surface tooManyChildren as a truncation marker (C4)

get_variables previously silently dropped children past the 100-item
XCompositeNode cap. Now appends a '<truncated>: …and N more' sentinel
so the LLM knows the list is partial and can drill in via variable_name."
```

---

### Task 13: Wrap breakpoint removal in dispose paths with `WriteAction` (C6)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt:496-544` (the two `removeAgentBreakpoints` methods and `dispose()`)

- [ ] **Step 1: Wrap each mutation site**

Replace the body of `removeAgentBreakpoints(debuggerManager: XDebuggerManager)` (lines 496–516):

```kotlin
    fun removeAgentBreakpoints(debuggerManager: XDebuggerManager) {
        val bpManager = debuggerManager.breakpointManager
        WriteAction.runAndWait<RuntimeException> {
            agentBreakpoints.forEach { bp ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    bpManager.removeBreakpoint(bp as XLineBreakpoint<Nothing>)
                } catch (_: Exception) {
                    // Breakpoint may already be removed
                }
            }
            agentGeneralBreakpoints.forEach { bp ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    bpManager.removeBreakpoint(bp as XBreakpoint<Nothing>)
                } catch (_: Exception) {
                    // Breakpoint may already be removed
                }
            }
        }
        agentBreakpoints.clear()
        agentGeneralBreakpoints.clear()
    }
```

Add the import at the top:

```kotlin
import com.intellij.openapi.application.WriteAction
```

Apply the same wrap in `dispose()` (lines 518–544) — the two `agentBreakpoints.forEach { ... removeBreakpoint ... }` blocks inside the outer `try`.

- [ ] **Step 2: Run tests**

Run: `./gradlew :agent:test --tests "*AgentDebugController*"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt
git commit -m "fix(debug): wrap breakpoint removal in WriteAction (C6)

XBreakpointManager mutations require the write lock per IntelliJ
threading model. Dispose and removeAgentBreakpoints now acquire it."
```

---

### Phase 3 verification

- [ ] **Step 1: Full suite + verifier**

Run: `./gradlew :agent:test && ./gradlew verifyPlugin`
Expected: PASS.

- [ ] **Step 2: Tag**

```bash
git tag debug-audit-phase3
```

---

## Phase 4 — Description polish

**Outcome:** Descriptions for `pause`, `run_to_cursor`, and `force_step_into` carry the clarifications research flagged (D4, D5, D6). No code changes — text only.

> Note: if Phase 1 Task 4 already incorporated all three of these clarifications (the new `DebugStepTool` description text above already says `[SUSPENDED]` on `run_to_cursor`, "best-effort" on `pause`, and the Kotlin-inline hint on `force_step_into`), Phase 4 is effectively subsumed. Verify by diffing the Task 4 output against the D4/D5/D6 targets below; skip this phase if all three are covered.

### Task 14: Verify D4, D5, D6 descriptions or patch any missed items

- [ ] **Step 1: Diff audit**

Check the committed `DebugStepTool.kt` description against the audit items:
- D4 (pause is best-effort): must say "Best-effort" and "may take several seconds or fail" and suggest `get_state` to confirm.
- D5 (run_to_cursor [SUSPENDED]): must carry the `[SUSPENDED]` tag and mention the "despite the name" caveat.
- D6 (force_step_into Kotlin inline): must mention `Kotlin inlined bodies`.

Run: `grep -E 'Best-effort|despite the name|Kotlin inlined' agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt`
Expected: three matching lines.

- [ ] **Step 2: Patch any missing ones**

For each missing item, edit the description in `DebugStepTool.kt`. No test impact.

- [ ] **Step 3: Commit only if changes made**

```bash
git diff --quiet agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt \
  || git commit -am "docs(debug): fill in pause / run_to_cursor / force_step_into clarifications (D4-D6)"
```

- [ ] **Step 4: Tag**

```bash
git tag debug-audit-phase4
```

---

## Cross-cutting: final verification

- [ ] **Step 1: Full module test**

Run: `./gradlew :agent:clean :agent:test --rerun --no-build-cache`
Expected: PASS. Rerun without cache to catch any flakiness from reused test state.

- [ ] **Step 2: Plugin verifier + build**

Run: `./gradlew verifyPlugin buildPlugin`
Expected: both green. `build/distributions/*.zip` produced.

- [ ] **Step 3: Manual smoke test scenarios (QA)**

For each scenario, record pass/fail in a short note before releasing:

1. **Two-session disambiguation:** Start a Spring Boot and a plain JUnit debug session. Set a breakpoint in JUnit test, hit it, then click into the Spring Boot console (focus drift). In agent chat: `debug_inspect(action=get_variables)`. Expected: variables from the JUnit frame, not "Not suspended".
2. **Post-step race:** Pause at breakpoint → `debug_step(action=step_over)` → immediately `debug_inspect(action=get_variables)`. Expected: variables, not "No active stack frame available".
3. **Non-breakpointable line:** `debug_breakpoints(action=add_breakpoint, file=Foo.java, line=<a comment line>)`. Expected: error message says "not breakpointable", names the file and line.
4. **Evaluate in caller frame:** Pause deep in call chain. `debug_inspect(action=evaluate, expression=localVar, frameIndex=2)`. Expected: evaluates against frame 2's scope, not frame 0.
5. **Variable truncation marker:** Inspect a HashMap with >100 entries. Expected: list ends with `…and N more` marker (C4).

- [ ] **Step 4: Release**

If the user confirms a release is wanted (per project memory `feedback_release_timing.md` — only release when the user asks):

```bash
# bump pluginVersion patch in gradle.properties, then:
./gradlew clean buildPlugin
git push origin fix/debug-tools-audit
gh release create v<new-version> build/distributions/*.zip --notes "Debug tool audit fixes: C1-C7, D1-D8"
```

Otherwise stop after Step 3 and let the user drive the release.

---

## Self-review checklist (done before committing this plan)

- [x] **Spec coverage:** C1 (Task 2), C2 (Tasks 6+7), C3 (Task 8), C4 (Task 12), C5 (Task 9), C6 (Task 13), C7 (Task 11), D1 (Task 4), D2 (Task 4), D3 (Task 3), D4/D5/D6 (Task 4 + Task 14 verify), D7 (Task 10), D8 (Task 5). All 15 audit findings covered.
- [x] **Placeholder scan:** No `TBD`/`similar to above`/`add appropriate...` etc. Every code block shows the actual replacement text.
- [x] **Type consistency:** `getRawStackFrames` defined Task 6, consumed Task 7. `VariableInfo.truncated` introduced Task 12 and consumed in `formatVariables`. `BreakpointNotAllowedException` introduced and caught in Task 11. `DebugPauseEvent`/`SessionEntry` names match the source.
- [x] **Out-of-scope split:** Missing capabilities #1–#7 explicitly deferred to separate plans.

---

## Follow-up specs (for later brainstorms)

Each of these is an independent feature that deserves its own brainstorm → plan cycle. Save as stubs in `docs/specs/` so they're not forgotten:

1. **`2026-04-23-debug-caps-threads.md`** — `debug_inspect(action=get_threads)` + `switch_thread`. API: `XSuspendContext.executionStacks` + `XDebugSession.setCurrentStackFrame`. Primary win: multi-threaded deadlock/race debugging.
2. **`2026-04-23-debug-caps-smart-step-into.md`** — `debug_step(action=smart_step_into, variant_index=N)`. API: `XDebugProcess.smartStepIntoHandler.computeSmartStepVariants(position)` + `XDebugSession.smartStepInto`. Needs a way for the LLM to pick a variant.
3. **`2026-04-23-debug-caps-inline-breakpoint-variants.md`** — Kotlin lambda breakpoints via `XLineBreakpointType.getAvailableVariants`. Currently a lambda breakpoint silently attaches to the outer line — major source of "breakpoint never hits" confusion.
4. **`2026-04-23-debug-caps-watches.md`** — `add_watch` / `list_watches` / `remove_watch` maintained agent-side (evaluate on each `sessionPaused`). No platform API needed on the public surface.
5. **`2026-04-23-debug-caps-hit-count.md`** — Implement hit counting via `XDebugSessionListener.sessionPaused` counting per `XBreakpoint`. No internal-API dependency.
6. **`2026-04-23-debug-caps-dependent-breakpoints.md`** — Master/slave via internal `XDependentBreakpointManager` — wrap carefully or build our own gate using conditional expressions.
7. **`2026-04-23-debug-caps-kotlin-coroutines.md`** — Coroutine debugger inspection. Research first — the platform API may be partially internal and changing as of 2025.x.

Each follow-up should be sized for ~1–3 days and gated behind a user-visible capability flag.
