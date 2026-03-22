# IDE Runtime, Debugging & Configuration Tools — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 23 new tools giving the AI agent access to IDE runtime output, interactive debugging, and run configuration management — capabilities no other AI coding tool offers.

**Architecture:** Three tool packages (`runtime/`, `debug/`, `config/`) with a shared `AgentDebugController` service that wraps IntelliJ's callback-based `XDebugger` API into Kotlin coroutines via `suspendCancellableCoroutine`. Runtime tools are read-only (NONE risk). Debug session control is MEDIUM risk (operates within approved sessions). Session launch is HIGH risk.

**Tech Stack:** IntelliJ Platform SDK (`XDebuggerManager`, `XBreakpointManager`, `XDebugSession`, `RunManager`, `ExecutionManager`, `SMTestProxy`), Kotlin coroutines, MockK for testing.

**Spec:** `docs/superpowers/specs/2026-03-22-ide-runtime-debugging-tools-design.md`

---

## File Structure

### New files (25)

```
agent/src/main/kotlin/com/workflow/orchestrator/agent/
  tools/
    runtime/
      GetRunConfigurationsTool.kt    — List run/debug configs (RunManager API)
      GetRunningProcessesTool.kt     — List active run/debug sessions (ExecutionManager API)
      GetRunOutputTool.kt            — Console output from run session (ConsoleView API)
      GetTestResultsTool.kt          — Structured test results (SMTestProxy tree)
    debug/
      AgentDebugController.kt        — Shared coroutine wrapper for XDebugger callbacks
      AddBreakpointTool.kt           — Set line breakpoint (XBreakpointManager)
      RemoveBreakpointTool.kt        — Remove breakpoint
      ListBreakpointsTool.kt         — List all breakpoints
      StartDebugSessionTool.kt       — Launch debug session (ProgramRunner)
      GetDebugStateTool.kt           — Current session status + position
      DebugStepOverTool.kt           — Step over
      DebugStepIntoTool.kt           — Step into
      DebugStepOutTool.kt            — Step out
      DebugResumeTool.kt             — Resume execution
      DebugPauseTool.kt              — Pause execution
      DebugRunToCursorTool.kt        — Run to specific line
      DebugStopTool.kt               — Stop debug session
      EvaluateExpressionTool.kt      — Evaluate expression in debug context
      GetStackFramesTool.kt          — Get stack trace
      GetVariablesTool.kt            — Get variables at frame
    config/
      CreateRunConfigTool.kt         — Create run/debug configuration
      ModifyRunConfigTool.kt         — Modify existing config
      DeleteRunConfigTool.kt         — Delete agent-created config
  resources/skills/
    interactive-debugging/SKILL.md   — New LLM-only debugging skill

agent/src/test/kotlin/com/workflow/orchestrator/agent/
  tools/
    runtime/
      GetRunConfigurationsToolTest.kt
      GetRunningProcessesToolTest.kt
      GetRunOutputToolTest.kt
      GetTestResultsToolTest.kt
    debug/
      AgentDebugControllerTest.kt
      AddBreakpointToolTest.kt
      StartDebugSessionToolTest.kt
      GetVariablesToolTest.kt
      EvaluateExpressionToolTest.kt
    config/
      CreateRunConfigToolTest.kt
      DeleteRunConfigToolTest.kt
```

### Modified files (7)

```
agent/src/main/kotlin/.../AgentService.kt:106-258          — Register 23 new tools
agent/src/main/kotlin/.../tools/ide/RunTestsTool.kt         — Upgrade to native test runner
agent/src/main/kotlin/.../tools/ToolCategoryRegistry.kt     — Add "Runtime & Debug" category
agent/src/main/kotlin/.../tools/DynamicToolSelector.kt      — Add debug/test/log keyword triggers
agent/src/main/kotlin/.../runtime/ApprovalGate.kt           — Add 23 tools to risk maps
agent/src/main/kotlin/.../orchestrator/PromptAssembler.kt   — Add debugging awareness hint
agent/src/main/resources/skills/systematic-debugging/SKILL.md — Add runtime tools + escalation
```

---

## Task 1: AgentDebugController — Shared XDebugger Service

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugControllerTest.kt`

This is the foundation — all debug tools depend on it.

- [ ] **Step 1: Write the data classes**

```kotlin
package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.*
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.frame.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class DebugPauseEvent(
    val sessionId: String,
    val file: String?,
    val line: Int?,
    val reason: String  // "breakpoint", "step", "pause", "exception"
)

data class FrameInfo(
    val index: Int,
    val methodName: String,
    val file: String?,
    val line: Int?,
    val className: String?
)

data class VariableInfo(
    val name: String,
    val type: String,
    val value: String,
    val children: List<VariableInfo> = emptyList()
)

data class EvaluationResult(
    val expression: String,
    val result: String,
    val type: String,
    val isError: Boolean = false
)
```

- [ ] **Step 2: Write the controller skeleton**

```kotlin
class AgentDebugController(private val project: Project) : Disposable {
    private val sessions = ConcurrentHashMap<String, XDebugSession>()
    private val sessionIdCounter = AtomicInteger(0)
    private val pauseFlows = ConcurrentHashMap<String, MutableSharedFlow<DebugPauseEvent>>()
    private val agentBreakpoints = mutableListOf<XLineBreakpoint<*>>()

    fun registerSession(session: XDebugSession): String {
        val id = "debug-${sessionIdCounter.incrementAndGet()}"
        sessions[id] = session
        pauseFlows[id] = MutableSharedFlow(replay = 1)  // replay=1 prevents race condition
        session.addSessionListener(createListener(id), this)
        return id
    }

    fun getSession(sessionId: String? = null): XDebugSession? {
        if (sessionId != null) return sessions[sessionId]
        return XDebuggerManager.getInstance(project).currentSession
    }

    fun getActiveSessionId(): String? = sessions.entries.firstOrNull()?.key

    fun trackBreakpoint(bp: XLineBreakpoint<*>) { agentBreakpoints.add(bp) }

    private fun createListener(sessionId: String) = object : XDebugSessionListener {
        override fun sessionPaused() {
            val session = sessions[sessionId] ?: return
            val pos = session.currentPosition
            pauseFlows[sessionId]?.tryEmit(DebugPauseEvent(
                sessionId = sessionId,
                file = pos?.file?.path,
                line = pos?.line?.plus(1),  // 0-based to 1-based
                reason = if (session.activeNonLineBreakpoint != null || session.currentBreakpoint != null) "breakpoint" else "step"
            ))
        }
        override fun sessionResumed() {}
        override fun sessionStopped() {
            sessions.remove(sessionId)
            pauseFlows.remove(sessionId)
        }
    }

    suspend fun waitForPause(sessionId: String, timeoutMs: Long = 5000): DebugPauseEvent? {
        val session = sessions[sessionId] ?: return null
        val flow = pauseFlows[sessionId] ?: return null
        return withTimeoutOrNull(timeoutMs) {
            if (session.isSuspended) {
                val pos = session.currentPosition
                return@withTimeoutOrNull DebugPauseEvent(sessionId, pos?.file?.path, pos?.line?.plus(1), "breakpoint")
            }
            flow.first()
        }
    }

    suspend fun getStackFrames(session: XDebugSession, maxFrames: Int = 20): List<FrameInfo> {
        val context = session.suspendContext ?: return emptyList()
        val stack = context.activeExecutionStack ?: return emptyList()
        val frames = mutableListOf<FrameInfo>()
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            stack.computeStackFrames(0, object : XStackFrameContainerEx {
                override fun addStackFrames(frameList: MutableList<out XStackFrame>, last: Boolean) {
                    frameList.forEachIndexed { i, frame ->
                        if (frames.size < maxFrames) {
                            val pos = frame.sourcePosition
                            frames.add(FrameInfo(
                                index = frames.size,
                                methodName = frame.toString(),
                                file = pos?.file?.path,
                                line = pos?.line?.plus(1),
                                className = null
                            ))
                        }
                    }
                    if (last || frames.size >= maxFrames) {
                        if (!cont.isCompleted) cont.resumeWith(Result.success(Unit))
                    }
                }
                override fun errorOccurred(errorMessage: String) {
                    if (!cont.isCompleted) cont.resumeWith(Result.success(Unit))
                }
            })
        }
        return frames
    }

    suspend fun getVariables(frame: XStackFrame, maxDepth: Int = 2): List<VariableInfo> {
        if (maxDepth < 0) return emptyList()
        val children = mutableListOf<XValue>()
        val names = mutableListOf<String>()
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            frame.computeChildren(object : XCompositeNode {
                override fun addChildren(childList: XValueChildrenList, last: Boolean) {
                    for (i in 0 until childList.size()) {
                        names.add(childList.getName(i))
                        children.add(childList.getValue(i))
                    }
                    if (last && !cont.isCompleted) cont.resumeWith(Result.success(Unit))
                }
                override fun tooManyChildren(remaining: Int) {
                    if (!cont.isCompleted) cont.resumeWith(Result.success(Unit))
                }
                override fun setAlreadySorted(alreadySorted: Boolean) {}
                override fun setErrorMessage(errorMessage: String) {
                    if (!cont.isCompleted) cont.resumeWith(Result.success(Unit))
                }
                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                    if (!cont.isCompleted) cont.resumeWith(Result.success(Unit))
                }
            })
        }
        return names.zip(children).map { (name, value) ->
            val presentation = resolvePresentation(value)
            VariableInfo(
                name = name,
                type = presentation.first,
                value = presentation.second,
                children = if (maxDepth > 0) resolveChildren(value, maxDepth - 1) else emptyList()
            )
        }
    }

    private suspend fun resolvePresentation(value: XValue): Pair<String, String> {
        var type = ""
        var valueStr = ""
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            value.computePresentation(object : XValueNode {
                override fun setPresentation(icon: javax.swing.Icon?, t: String?, v: String, hasChildren: Boolean) {
                    type = t ?: ""; valueStr = v
                    if (!cont.isCompleted) cont.resumeWith(Result.success(Unit))
                }
                override fun setPresentation(icon: javax.swing.Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                    type = presentation.type ?: ""; valueStr = buildString { presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                        override fun renderValue(v: String) { append(v) }
                        override fun renderStringValue(v: String) { append("\"$v\"") }
                        override fun renderNumericValue(v: String) { append(v) }
                        override fun renderKeywordValue(v: String) { append(v) }
                        override fun renderError(v: String) { append("ERROR: $v") }
                        override fun renderComment(v: String) { append("/* $v */") }
                        override fun renderSpecialSymbol(v: String) { append(v) }
                    })}
                    if (!cont.isCompleted) cont.resumeWith(Result.success(Unit))
                }
                override fun setFullValueEvaluator(evaluator: XFullValueEvaluator) {}
            }, XValuePlace.TREE)
        }
        return Pair(type, valueStr)
    }

    private suspend fun resolveChildren(value: XValue, depth: Int): List<VariableInfo> {
        // Recursive child resolution — same pattern as getVariables but on XValue
        if (depth < 0) return emptyList()
        val result = mutableListOf<VariableInfo>()
        // Simplified: resolve first 10 children
        val childValues = mutableListOf<Pair<String, XValue>>()
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            value.computeChildren(object : XCompositeNode {
                override fun addChildren(childList: XValueChildrenList, last: Boolean) {
                    for (i in 0 until minOf(childList.size(), 10)) {
                        childValues.add(childList.getName(i) to childList.getValue(i))
                    }
                    if (last && !cont.isCompleted) cont.resumeWith(Result.success(Unit))
                }
                override fun tooManyChildren(remaining: Int) {
                    if (!cont.isCompleted) cont.resumeWith(Result.success(Unit))
                }
                override fun setAlreadySorted(alreadySorted: Boolean) {}
                override fun setErrorMessage(errorMessage: String) {
                    if (!cont.isCompleted) cont.resumeWith(Result.success(Unit))
                }
                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                    if (!cont.isCompleted) cont.resumeWith(Result.success(Unit))
                }
            })
        }
        for ((name, child) in childValues) {
            val pres = resolvePresentation(child)
            result.add(VariableInfo(name, pres.first, pres.second, if (depth > 0) resolveChildren(child, depth - 1) else emptyList()))
        }
        return result
    }

    suspend fun evaluate(session: XDebugSession, expression: String, frameIndex: Int = 0): EvaluationResult {
        val frame = session.currentStackFrame ?: return EvaluationResult(expression, "No active frame", "", isError = true)
        val evaluator = frame.evaluator ?: return EvaluationResult(expression, "Evaluator not available", "", isError = true)
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            evaluator.evaluate(
                com.intellij.xdebugger.evaluation.XDebuggerEvaluator.XExpressionImpl(expression),
                object : com.intellij.xdebugger.evaluation.XDebuggerEvaluator.XEvaluationCallback {
                    override fun evaluated(result: XValue) {
                        // Resolve presentation synchronously-ish
                        kotlinx.coroutines.GlobalScope.launch {
                            val pres = resolvePresentation(result)
                            if (!cont.isCompleted) cont.resumeWith(Result.success(
                                EvaluationResult(expression, pres.second, pres.first)
                            ))
                        }
                    }
                    override fun errorOccurred(errorMessage: String) {
                        if (!cont.isCompleted) cont.resumeWith(Result.success(
                            EvaluationResult(expression, errorMessage, "", isError = true)
                        ))
                    }
                },
                session.currentPosition
            )
        }
    }

    fun stopAllSessions() {
        sessions.values.forEach { if (!it.isStopped) it.stop() }
        sessions.clear()
        pauseFlows.clear()
    }

    fun removeAgentBreakpoints() {
        val bpManager = XDebuggerManager.getInstance(project).breakpointManager
        agentBreakpoints.forEach { bpManager.removeBreakpoint(it) }
        agentBreakpoints.clear()
    }

    override fun dispose() {
        stopAllSessions()
        removeAgentBreakpoints()
    }
}
```

- [ ] **Step 3: Write tests for AgentDebugController**

```kotlin
// AgentDebugControllerTest.kt
package com.workflow.orchestrator.agent.tools.debug

import io.mockk.*
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class AgentDebugControllerTest {
    private val project = mockk<Project>(relaxed = true)

    @Test
    fun `registerSession assigns unique IDs`() {
        val controller = AgentDebugController(project)
        val session1 = mockk<XDebugSession>(relaxed = true)
        val session2 = mockk<XDebugSession>(relaxed = true)
        val id1 = controller.registerSession(session1)
        val id2 = controller.registerSession(session2)
        assertNotEquals(id1, id2)
        assertTrue(id1.startsWith("debug-"))
    }

    @Test
    fun `getSession returns registered session`() {
        val controller = AgentDebugController(project)
        val session = mockk<XDebugSession>(relaxed = true)
        val id = controller.registerSession(session)
        assertSame(session, controller.getSession(id))
    }

    @Test
    fun `waitForPause returns immediately when already suspended`() = runTest {
        val controller = AgentDebugController(project)
        val session = mockk<XDebugSession>(relaxed = true) {
            every { isSuspended } returns true
            every { currentPosition } returns null
        }
        val id = controller.registerSession(session)
        val result = controller.waitForPause(id, 1000)
        assertNotNull(result)
    }

    @Test
    fun `waitForPause returns null on timeout`() = runTest {
        val controller = AgentDebugController(project)
        val session = mockk<XDebugSession>(relaxed = true) {
            every { isSuspended } returns false
        }
        val id = controller.registerSession(session)
        val result = controller.waitForPause(id, 100)
        assertNull(result)
    }

    @Test
    fun `stopAllSessions stops all tracked sessions`() {
        val controller = AgentDebugController(project)
        val s1 = mockk<XDebugSession>(relaxed = true) { every { isStopped } returns false }
        val s2 = mockk<XDebugSession>(relaxed = true) { every { isStopped } returns false }
        controller.registerSession(s1)
        controller.registerSession(s2)
        controller.stopAllSessions()
        verify { s1.stop() }
        verify { s2.stop() }
        assertNull(controller.getActiveSessionId())
    }

    @Test
    fun `evaluate returns error when no active frame`() = runTest {
        val controller = AgentDebugController(project)
        val session = mockk<XDebugSession>(relaxed = true) {
            every { currentStackFrame } returns null
        }
        val result = controller.evaluate(session, "1 + 1")
        assertTrue(result.isError)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*.AgentDebugControllerTest" --rerun --no-build-cache`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugControllerTest.kt
git commit -m "feat(agent): add AgentDebugController — coroutine wrapper for XDebugger API"
```

---

## Task 2: Runtime & Logs Tools (4 read-only tools)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunConfigurationsTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunningProcessesTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunOutputTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetTestResultsTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunConfigurationsToolTest.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunOutputToolTest.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetTestResultsToolTest.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunningProcessesToolTest.kt`

Follow the existing tool pattern from `GitStatusTool.kt` — simple `AgentTool` implementation, no EDT requirements for read-only operations.

**Key APIs:**
- `RunManager.getInstance(project).allSettings` for run configs
- `ExecutionManager.getInstance(project).getRunningProcesses()` for active sessions
- `ProcessHandler` → `ConsoleView` content for run output
- `SMTestProxy` tree for structured test results

- [ ] **Step 1: Write `GetRunConfigurationsTool.kt`**

Use `RunManager.getInstance(project).allSettings` to list configs. Format each with type, main class, module, VM options, env vars. Optional `type_filter` parameter. Token estimate: 50 per config.

`allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)`

- [ ] **Step 2: Write `GetRunningProcessesTool.kt`**

Use `ExecutionManager.getInstance(project).getRunningProcesses()` combined with `XDebuggerManager.getInstance(project).debugSessions`. Format each with name, type (Running/Debug), PID, duration, port (if Spring Boot).

- [ ] **Step 3: Write `GetRunOutputTool.kt`**

Parameters: `config_name` (required), `last_n_lines` (default 200, max 1000), `filter` (regex).
Find active `ProcessHandler` by matching config name. Get `ConsoleView` content.
If no active session, check recently terminated sessions.
Apply line cap and regex filter.

- [ ] **Step 4: Write `GetTestResultsTool.kt`**

Parameters: `config_name` (optional), `status_filter` (FAILED/ERROR/PASSED/SKIPPED).
Walk `SMTestProxy` tree from most recent test execution.
Format structured output: summary line, then failed/error tests with assertion message + stack trace (5 frames max), then passed count.
Truncation strategy: 3000 token cap, failed tests first, stack traces capped at 5 frames.

- [ ] **Step 5: Write tests for all 4 runtime tools**

Pattern: `mockk<Project>`, `mockk<RunManager>`, `mockk<ExecutionManager>`. Verify output format, parameter validation, empty state handling. Use `runTest` for coroutines.

Key test cases:
- `GetRunConfigurationsTool`: with/without type_filter, empty configs
- `GetRunningProcessesTool`: no processes, mixed running/debug
- `GetRunOutputTool`: line cap, regex filter, config not found
- `GetTestResultsTool`: all pass, mixed results, status_filter, truncation

- [ ] **Step 6: Run tests**

Run: `./gradlew :agent:test --tests "*.tools.runtime.*" --rerun --no-build-cache`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/
git commit -m "feat(agent): add 4 runtime tools — get_run_configurations, get_running_processes, get_run_output, get_test_results"
```

---

## Task 3: Breakpoint Management Tools (3 tools)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AddBreakpointTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/RemoveBreakpointTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/ListBreakpointsTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AddBreakpointToolTest.kt`

**Threading:** Add/remove breakpoints MUST run on EDT inside `WriteAction`. Use `withContext(Dispatchers.EDT) { WriteAction.run { ... } }` pattern from `CompileModuleTool.kt`.

**Breakpoint type dispatch:** `.kt`/`.kts` → `KotlinLineBreakpointType`, else → `JavaLineBreakpointType`.

- [ ] **Step 1: Write `AddBreakpointTool.kt`**

Parameters: `file` (required), `line` (required), `condition` (optional), `log_expression` (optional), `temporary` (optional boolean).

Key implementation:
```kotlin
withContext(Dispatchers.EDT) {
    WriteAction.run<Exception> {
        val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
        val bpManager = XDebuggerManager.getInstance(project).breakpointManager
        val bpType = when {
            absolutePath.endsWith(".kt") || absolutePath.endsWith(".kts") ->
                XDebuggerUtil.getInstance().findBreakpointType(KotlinLineBreakpointType::class.java)
            else ->
                XDebuggerUtil.getInstance().findBreakpointType(JavaLineBreakpointType::class.java)
        }
        val bp = bpManager.addLineBreakpoint(bpType, vFile.url, line - 1, null, temporary ?: false)
        bp?.let { ... set condition, log_expression ... }
        controller.trackBreakpoint(bp)
    }
}
```

`allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER)`

- [ ] **Step 2: Write `RemoveBreakpointTool.kt`**

Parameters: `file` (required), `line` (required). Find breakpoint via `XBreakpointManager.findBreakpointsAtLine()`, remove it.

- [ ] **Step 3: Write `ListBreakpointsTool.kt`**

Parameters: `file` (optional filter). List all breakpoints with file, line, enabled/disabled, condition, log expression.

- [ ] **Step 4: Write `AddBreakpointToolTest.kt`**

Test cases: valid file+line, invalid file (error), conditional breakpoint sets condition, log breakpoint sets suspend policy to NONE, temporary flag. Mock `XBreakpointManager`, `XDebuggerManager`.

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test --tests "*.AddBreakpointToolTest" --rerun --no-build-cache`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AddBreakpointTool.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/RemoveBreakpointTool.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/ListBreakpointsTool.kt
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AddBreakpointToolTest.kt
git commit -m "feat(agent): add breakpoint tools — add_breakpoint, remove_breakpoint, list_breakpoints"
```

---

## Task 4: Debug Session Control Tools (8 tools)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/StartDebugSessionTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepOverTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepIntoTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepOutTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugResumeTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugPauseTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugRunToCursorTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStopTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/StartDebugSessionToolTest.kt`

All tools delegate to `AgentDebugController` for async operations.

- [ ] **Step 1: Write `StartDebugSessionTool.kt`**

Parameters: `config_name` (required), `wait_for_pause` (optional int, seconds).

Key implementation:
```kotlin
val runManager = RunManager.getInstance(project)
val settings = runManager.findConfigurationByName(configName)
    ?: return ToolResult(isError = true, content = "Run configuration '$configName' not found...")
val sessionId = withContext(Dispatchers.EDT) {
    val env = ExecutionEnvironmentBuilder
        .create(project, DefaultDebugExecutor.getDebugExecutorInstance(), settings)
        .build()
    ProgramRunnerUtil.executeConfiguration(env, true)
    // Register with controller after session starts (via XDebuggerManagerListener)
}
if (waitForPause != null && waitForPause > 0) {
    val event = controller.waitForPause(sessionId, waitForPause * 1000L)
    // Format response with pause state + top-frame variables
}
```

- [ ] **Step 2: Write step tools (3 files — DebugStepOverTool, DebugStepIntoTool, DebugStepOutTool)**

All follow same pattern. Parameters: `session_id` (optional). Get session from controller, verify suspended, call `session.stepOver()`/`stepInto()`/`stepOut()`, wait for pause (5s timeout), return new position + top-frame variables.

Step tools auto-include top-frame variables to save the agent a round-trip.

- [ ] **Step 3: Write DebugResumeTool, DebugPauseTool, DebugRunToCursorTool, DebugStopTool**

- `DebugResumeTool`: `session.resume()`, return "resumed"
- `DebugPauseTool`: `session.pause()` + `waitForPause(5000)`, return pause state
- `DebugRunToCursorTool`: params `file`, `line`, `session_id`. Call `session.runToPosition()` + `waitForPause(30000)`
- `DebugStopTool`: `session.stop()`, return "stopped"

- [ ] **Step 4: Write `StartDebugSessionToolTest.kt`**

Test cases: config not found (error), successful launch, wait_for_pause timeout, wait_for_pause hit. Mock `RunManager`, `ExecutionEnvironmentBuilder`, `AgentDebugController`.

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test --tests "*.StartDebugSessionToolTest" --rerun --no-build-cache`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/StartDebugSessionTool.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStep*.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugResumeTool.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugPauseTool.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugRunToCursorTool.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStopTool.kt
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/StartDebugSessionToolTest.kt
git commit -m "feat(agent): add 8 debug session control tools — start, step, resume, pause, run-to-cursor, stop"
```

---

## Task 5: Inspection Tools (4 tools)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/GetDebugStateTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/GetStackFramesTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/GetVariablesTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/EvaluateExpressionTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/GetVariablesToolTest.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/EvaluateExpressionToolTest.kt`

All read-only inspection tools. Delegate to `AgentDebugController` for variable resolution and expression evaluation.

- [ ] **Step 1: Write `GetDebugStateTool.kt`**

Parameters: `session_id` (optional). Returns: status (PAUSED/RUNNING/STOPPED), position (file:line), reason, suspended thread count. No controller delegation needed — reads directly from `XDebugSession`.

- [ ] **Step 2: Write `GetStackFramesTool.kt`**

Parameters: `session_id` (optional), `thread_name` (optional), `max_frames` (default 20). Delegates to `controller.getStackFrames()`. Format: `#0 ClassName.method(File.kt:42)` per frame.

- [ ] **Step 3: Write `GetVariablesTool.kt`**

Parameters: `session_id` (optional), `frame_index` (default 0), `max_depth` (default 2, max 4), `variable_name` (optional — deep inspect specific var). Delegates to `controller.getVariables()`. 3000-char output cap.

- [ ] **Step 4: Write `EvaluateExpressionTool.kt`**

Parameters: `expression` (required), `session_id` (optional), `frame_index` (default 0). Delegates to `controller.evaluate()`. Return: expression, result, type, or error message.

- [ ] **Step 5: Write tests**

`GetVariablesToolTest`: depth 0 (names+types only), depth 2 (expanded), specific variable_name, 3000-char cap truncation, no active session error.
`EvaluateExpressionToolTest`: valid expression result, error expression, no active frame.

- [ ] **Step 6: Run tests**

Run: `./gradlew :agent:test --tests "*.tools.debug.*" --rerun --no-build-cache`
Expected: All debug tool tests PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/GetDebugStateTool.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/GetStackFramesTool.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/GetVariablesTool.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/EvaluateExpressionTool.kt
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/GetVariablesToolTest.kt
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/EvaluateExpressionToolTest.kt
git commit -m "feat(agent): add 4 debug inspection tools — get_debug_state, get_stack_frames, get_variables, evaluate_expression"
```

---

## Task 6: Run Configuration Management Tools (3 tools)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/config/CreateRunConfigTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/config/ModifyRunConfigTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/config/DeleteRunConfigTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/config/CreateRunConfigToolTest.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/config/DeleteRunConfigToolTest.kt`

- [ ] **Step 1: Write `CreateRunConfigTool.kt`**

Parameters: `name` (required), `type` (required — application/spring_boot/junit/gradle/remote_debug), `main_class`, `test_class`, `test_method`, `module`, `env_vars`, `vm_options`, `program_args`, `working_dir`, `active_profiles`, `port`.

Auto-prefix name with `[Agent]`. Validate class exists via PSI lookup. Create config on EDT via `RunManager.createConfiguration()`. Handle Spring Boot plugin absence via reflection check.

Config type dispatch:
```kotlin
val factory = when (type) {
    "application" -> ApplicationConfigurationType.getInstance().configurationFactories[0]
    "junit" -> JUnitConfigurationType.getInstance().configurationFactories[0]
    "gradle" -> GradleExternalTaskConfigurationType.getInstance().factory
    "remote_debug" -> RemoteConfigurationType.getInstance().configurationFactories[0]
    "spring_boot" -> {
        try { Class.forName("com.intellij.spring.boot.run.SpringBootApplicationConfigurationType") ... }
        catch (e: ClassNotFoundException) { return ToolResult(isError = true, "Spring Boot plugin required...") }
    }
}
```

- [ ] **Step 2: Write `ModifyRunConfigTool.kt`**

Parameters: `name` (required), then any of `env_vars`, `vm_options`, `program_args`, `working_dir`, `active_profiles`. Find config by name, apply changes on EDT.

Dynamic risk: if name starts with `[Agent]` → MEDIUM, else → HIGH (checked at execution time, not in static ApprovalGate).

- [ ] **Step 3: Write `DeleteRunConfigTool.kt`**

Parameters: `name` (required). Safety: only delete if name starts with `[Agent]`. Otherwise return error with helpful message.

- [ ] **Step 4: Write tests**

`CreateRunConfigToolTest`: each config type, class validation (missing class = error), `[Agent]` prefix applied, Spring Boot plugin missing fallback.
`DeleteRunConfigToolTest`: agent config deleted OK, non-agent config rejected.

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test --tests "*.tools.config.*" --rerun --no-build-cache`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/config/
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/config/
git commit -m "feat(agent): add 3 run config tools — create_run_config, modify_run_config, delete_run_config"
```

---

## Task 7: Upgrade RunTestsTool to Native IntelliJ Test Runner

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunTestsTool.kt`

- [ ] **Step 1: Add `timeout` and `use_native_runner` parameters**

Add to `FunctionParameters`:
```kotlin
"timeout" to ParameterProperty("int", "Timeout in seconds (default 120, max 600)")
"use_native_runner" to ParameterProperty("boolean", "Use IntelliJ native test runner (default true)")
```

- [ ] **Step 2: Implement native test runner path**

When `use_native_runner = true`:
1. Build `JUnitConfiguration` programmatically for the test class/method
2. Execute via `ExecutionUtil.runConfiguration()` with `DefaultRunExecutor`
3. Capture `SMTestProxy` tree via test runner listener
4. Wait for `processTerminated()` with timeout (use `ScheduledExecutorService.schedule()` + `ProcessHandler.destroyProcess()`)
5. Format structured results (same format as `GetTestResultsTool`)

- [ ] **Step 3: Keep existing shell fallback**

When `use_native_runner = false` OR native runner fails (ClassNotFoundException, indexing in progress): fall back to current Maven/Gradle shell execution.

- [ ] **Step 4: Run existing RunTestsTool tests**

Run: `./gradlew :agent:test --tests "*.RunCommandToolTest" --rerun --no-build-cache`
(RunTestsTool tests are in RunCommandToolTest or separate — verify no regressions)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunTestsTool.kt
git commit -m "feat(agent): upgrade run_tests to native IntelliJ test runner with structured output"
```

---

## Task 8: Registry Integration (AgentService, ToolCategoryRegistry, DynamicToolSelector, ApprovalGate)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:106-258`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt`

- [ ] **Step 1: Register 23 new tools in AgentService.kt**

Add after line 225 (`register(RunTestsTool())`):

```kotlin
// Runtime & Debug tools
register(GetRunConfigurationsTool())
register(GetRunningProcessesTool())
register(GetRunOutputTool())
register(GetTestResultsTool())
register(AddBreakpointTool())
register(RemoveBreakpointTool())
register(ListBreakpointsTool())
register(StartDebugSessionTool())
register(GetDebugStateTool())
register(DebugStepOverTool())
register(DebugStepIntoTool())
register(DebugStepOutTool())
register(DebugResumeTool())
register(DebugPauseTool())
register(DebugRunToCursorTool())
register(DebugStopTool())
register(EvaluateExpressionTool())
register(GetStackFramesTool())
register(GetVariablesTool())
register(CreateRunConfigTool())
register(ModifyRunConfigTool())
register(DeleteRunConfigTool())
```

Add import: `import com.workflow.orchestrator.agent.tools.debug.*`
Add import: `import com.workflow.orchestrator.agent.tools.runtime.*`
Add import: `import com.workflow.orchestrator.agent.tools.config.*`

- [ ] **Step 2: Add "Runtime & Debug" category in ToolCategoryRegistry.kt**

Add after the `sonar` category (line 103), before `bitbucket`:

```kotlin
ToolCategory(
    id = "runtime_debug",
    displayName = "Runtime & Debug",
    color = "#E91E63",
    badgePrefix = "DBG",
    description = "Run output, test results, breakpoints, interactive debugging, expression evaluation, run configuration management",
    tools = listOf(
        "get_run_configurations", "get_running_processes", "get_run_output", "get_test_results",
        "add_breakpoint", "remove_breakpoint", "list_breakpoints",
        "start_debug_session", "get_debug_state",
        "debug_step_over", "debug_step_into", "debug_step_out",
        "debug_resume", "debug_pause", "debug_run_to_cursor", "debug_stop",
        "evaluate_expression", "get_stack_frames", "get_variables",
        "create_run_config", "modify_run_config", "delete_run_config"
    )
),
```

- [ ] **Step 3: Add keyword triggers in DynamicToolSelector.kt**

Add to `TOOL_TRIGGERS` map (after Sonar triggers, before Bitbucket):

```kotlin
// Runtime & Debug tools
"debug" to setOf("add_breakpoint", "remove_breakpoint", "list_breakpoints", "start_debug_session", "get_debug_state", "debug_step_over", "debug_step_into", "debug_step_out", "debug_resume", "debug_pause", "debug_run_to_cursor", "debug_stop", "evaluate_expression", "get_stack_frames", "get_variables", "get_run_configurations", "create_run_config"),
"breakpoint" to setOf("add_breakpoint", "remove_breakpoint", "list_breakpoints"),
"step over" to setOf("debug_step_over", "get_debug_state", "get_variables"),
"step into" to setOf("debug_step_into", "get_debug_state", "get_variables"),
"step through" to setOf("debug_step_over", "debug_step_into", "debug_step_out", "start_debug_session"),
"test result" to setOf("get_test_results", "get_run_output"),
"test output" to setOf("get_test_results", "get_run_output"),
"test fail" to setOf("get_test_results", "run_tests"),
"run config" to setOf("get_run_configurations", "create_run_config", "modify_run_config"),
"run configuration" to setOf("get_run_configurations", "create_run_config", "modify_run_config"),
"console" to setOf("get_run_output", "get_running_processes"),
"log output" to setOf("get_run_output"),
"evaluate" to setOf("evaluate_expression"),
"inspect" to setOf("get_variables", "get_stack_frames", "get_debug_state"),
"stack trace" to setOf("get_stack_frames"),
"stack frame" to setOf("get_stack_frames"),
```

- [ ] **Step 4: Update ApprovalGate.kt risk maps**

Add to `NONE_RISK_TOOLS` (after the Bitbucket read-only block, ~line 113):
```kotlin
// Runtime & Debug read-only
"get_run_configurations", "get_running_processes", "get_run_output", "get_test_results",
"list_breakpoints", "get_debug_state", "get_stack_frames", "get_variables",
```

Add to `MEDIUM_RISK_TOOLS` (after `jira_transition`, ~line 148):
```kotlin
// Debug tools (operate within approved session context)
"add_breakpoint", "remove_breakpoint",
"evaluate_expression",
"debug_step_over", "debug_step_into", "debug_step_out",
"debug_resume", "debug_pause", "debug_run_to_cursor",
"create_run_config", "modify_run_config",
```

HIGH (default) covers: `start_debug_session`, `debug_stop`, `delete_run_config`

- [ ] **Step 5: Run full test suite**

Run: `./gradlew :agent:test --rerun --no-build-cache`
Expected: All tests PASS (existing + new)

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt
git commit -m "feat(agent): register 23 debug tools in AgentService, category, keywords, approval gate"
```

---

## Task 9: Skills — Update systematic-debugging + Create interactive-debugging

**Files:**
- Modify: `agent/src/main/resources/skills/systematic-debugging/SKILL.md`
- Create: `agent/src/main/resources/skills/interactive-debugging/SKILL.md`

- [ ] **Step 1: Update systematic-debugging SKILL.md**

Changes:
1. Update `preferred-tools` to add `get_test_results`, `get_run_output`, `get_running_processes`
2. Update Phase 1 Step 1 — add `get_test_results` and `get_run_output` instructions
3. Update Phase 1 Step 2 — add `get_running_processes` reference
4. Add new "Escalation: Do You Need the Debugger?" section between Phase 1 and Phase 2
5. Update Phase 3 Step 4 — replace `delegate_task` with `agent` tool
6. Update Tool Usage Quick Reference table with new tools

See spec Section 4.1 for exact content.

- [ ] **Step 2: Create interactive-debugging SKILL.md**

Create `agent/src/main/resources/skills/interactive-debugging/SKILL.md` with full content from spec Section 4.2:
- `user-invocable: false` (LLM-only, activated via escalation)
- Three patterns: strategic breakpoint, observation breakpoints, step-through
- Budget rules (10 iteration max)
- Session lifecycle (always stop, always remove breakpoints)
- Tool quick reference table

- [ ] **Step 3: Verify skill loading**

Check that `SkillManager` discovers the new skill. Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SkillManager.kt` to confirm resource-based skill loading includes the new directory.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/resources/skills/systematic-debugging/SKILL.md
git add agent/src/main/resources/skills/interactive-debugging/SKILL.md
git commit -m "feat(agent): update systematic-debugging skill, add interactive-debugging skill"
```

---

## Task 10: PromptAssembler + CLAUDE.md Documentation

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Add debugging awareness to PromptAssembler**

In the `RULES` section of `buildSingleAgentPrompt()`, add:

```kotlin
// Debugging
"When debugging, start with get_test_results and get_run_output for structured error data. " +
"Only escalate to interactive debugging (breakpoints, stepping) when static analysis is insufficient. " +
"The interactive-debugging skill teaches efficient debugging patterns.",
```

- [ ] **Step 2: Update agent CLAUDE.md**

Update the Tools section:
- Change tool count from 64 to 87 (64 + 23)
- Add "Runtime & Debug" row to the category table:
  ```
  | Runtime & Debug | get_run_configurations, get_running_processes, get_run_output, get_test_results, add_breakpoint, remove_breakpoint, list_breakpoints, start_debug_session, get_debug_state, debug_step_over, debug_step_into, debug_step_out, debug_resume, debug_pause, debug_run_to_cursor, debug_stop, evaluate_expression, get_stack_frames, get_variables, create_run_config, modify_run_config, delete_run_config |
  ```
- Update Built-in skills to include `interactive-debugging`
- Add "Interactive Debugging" section describing `AgentDebugController` and the async pattern

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git add agent/CLAUDE.md
git commit -m "docs(agent): update CLAUDE.md and PromptAssembler for 23 debug tools"
```

---

## Task 11: Final Verification

- [ ] **Step 1: Run full agent test suite**

Run: `./gradlew :agent:clean :agent:test --rerun --no-build-cache`
Expected: All tests pass (existing ~470 + ~35 new)

- [ ] **Step 2: Run compilation check**

Run: `./gradlew :agent:compileKotlin`
Expected: No errors

- [ ] **Step 3: Run plugin verification**

Run: `./gradlew verifyPlugin`
Expected: No API compatibility issues

- [ ] **Step 4: Final commit (if any fixes needed)**

```bash
git add -A
git commit -m "fix(agent): address test/compilation issues from debug tools integration"
```

---

## Dependency Graph

```
Task 1: AgentDebugController (foundation)
  ↓
Task 2: Runtime Tools (independent of Task 1, no XDebugger)
  ↓
Task 3: Breakpoint Tools (depends on Task 1)
  ↓
Task 4: Session Control Tools (depends on Tasks 1, 3)
  ↓
Task 5: Inspection Tools (depends on Tasks 1, 4)
  ↓
Task 6: Config Tools (independent of Tasks 3-5)
  ↓
Task 7: RunTestsTool Upgrade (independent)
  ↓
Task 8: Registry Integration (depends on all tool tasks 1-7)
  ↓
Task 9: Skills (depends on Task 8 for tool names)
  ↓
Task 10: Docs (depends on Task 8-9)
  ↓
Task 11: Final Verification (depends on all)
```

**Parallelizable groups:**
- Tasks 1 + 2 can run in parallel (no shared dependencies)
- Tasks 6 + 7 can run in parallel (both independent)
- Tasks 9 + 10 can run in parallel (both are non-code)
