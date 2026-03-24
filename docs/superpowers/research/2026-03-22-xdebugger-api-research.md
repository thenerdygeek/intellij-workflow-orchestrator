# IntelliJ XDebugger API Research for AI Agent Integration

**Date:** 2026-03-22
**Purpose:** Understand how an AI agent can programmatically control IntelliJ's debugger

---

## 1. XDebuggerManager API — Entry Point

The singleton entry point for all debugger operations in a project.

```kotlin
// Get the manager
val manager = XDebuggerManager.getInstance(project)

// Access breakpoints
val breakpointManager: XBreakpointManager = manager.breakpointManager

// Access sessions
val sessions: Array<XDebugSession> = manager.debugSessions
val current: XDebugSession? = manager.currentSession

// Listen for session lifecycle (manager-level)
project.messageBus.connect(disposable).subscribe(
    XDebuggerManager.TOPIC,
    object : XDebuggerManagerListener {
        override fun processStarted(debugProcess: XDebugProcess) { }
        override fun processStopped(debugProcess: XDebugProcess) { }
        override fun currentSessionChanged(prev: XDebugSession?, curr: XDebugSession?) { }
    }
)

// Start a new session (modern API — requires EDT)
val session = manager.newSessionBuilder(processStarter)
    .showTab()        // XDebugSessionBuilder methods
    .startSession()
```

**Key types:**
- `XDebuggerManager` — project-level service, get via `getInstance(project)`
- `XDebuggerManagerListener` — 3 callbacks: `processStarted`, `processStopped`, `currentSessionChanged`
- `XDebugSessionBuilder` — modern fluent API for session creation (replaces deprecated `startSession`/`startSessionAndShowTab`)

---

## 2. XDebugSession — Session Control

The main interface for controlling an active debug session.

### Execution Control
```kotlin
session.stepOver(ignoreBreakpoints: Boolean)
session.stepInto()
session.stepOut()
session.forceStepInto()
session.runToPosition(position: XSourcePosition, ignoreBreakpoints: Boolean)
session.pause()
session.resume()
session.stop()
```

### State Inspection
```kotlin
session.isSuspended: Boolean
session.currentStackFrame: XStackFrame?
session.suspendContext: XSuspendContext?
session.currentPosition: XSourcePosition?
session.topFramePosition: XSourcePosition?
session.project: Project
session.debugProcess: XDebugProcess
```

### Breakpoint Operations (from session)
```kotlin
session.setBreakpointVerified(breakpoint: XLineBreakpoint<*>)
session.setBreakpointInvalid(breakpoint: XLineBreakpoint<*>, errorMessage: String?)
session.breakpointReached(breakpoint: XBreakpoint<*>, logExpr: String?, ctx: XSuspendContext): Boolean
session.setBreakpointMuted(muted: Boolean)
session.areBreakpointsMuted(): Boolean
```

### Listener Registration
```kotlin
session.addSessionListener(listener: XDebugSessionListener, parentDisposable: Disposable)
session.addSessionListener(listener: XDebugSessionListener)
session.removeSessionListener(listener: XDebugSessionListener)
```

---

## 3. XDebugSessionListener — Session Event Callbacks

All methods are `default` (empty implementation). Override only what you need.

```kotlin
interface XDebugSessionListener : EventListener {
    fun sessionPaused()            // Breakpoint hit OR step completed
    fun sessionResumed()           // Execution resumed
    fun sessionStopped()           // Session terminated
    fun stackFrameChanged()        // Frame selection changed
    fun stackFrameChanged(changedByUser: Boolean)  // @Experimental — true if user clicked frame
    fun beforeSessionResume()      // About to resume
    fun settingsChanged()          // Debug settings changed
    fun breakpointsMuted(muted: Boolean)  // Mute state changed
}
```

**Critical insight:** `sessionPaused()` is the universal callback for both breakpoint hits AND step completion. There is NO separate "breakpoint hit" callback. To distinguish breakpoint hit from step completion, check the `XSuspendContext` after `sessionPaused()` fires.

---

## 4. XSuspendContext — Paused State Access

When `sessionPaused()` fires, access the suspend context to inspect threads and stacks.

```kotlin
abstract class XSuspendContext {
    // The stack shown by default in Frames panel
    open fun getActiveExecutionStack(): XExecutionStack?

    // All available stacks (threads)
    open fun getExecutionStacks(): Array<XExecutionStack>

    // Async computation of execution stacks
    open fun computeExecutionStacks(container: XExecutionStackContainer)

    // Inner interface for async stack delivery
    interface XExecutionStackContainer : XValueCallback, Obsolescent {
        fun addExecutionStack(stacks: List<XExecutionStack>, last: Boolean)
    }
}
```

### XExecutionStack — Thread/Stack Access
```kotlin
abstract class XExecutionStack(displayName: String) {
    fun getDisplayName(): String
    abstract fun getTopFrame(): XStackFrame?
    fun getTopFrameAsync(): CompletableFuture<XStackFrame?>

    // Async frame computation — callback-based
    abstract fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer)

    interface XStackFrameContainer : Obsolescent, XValueCallback {
        fun addStackFrames(frames: List<XStackFrame>, last: Boolean)
    }
}
```

### XStackFrame — Individual Frame
```kotlin
abstract class XStackFrame : XValueContainer() {
    open fun getEvaluator(): XDebuggerEvaluator?       // For expression eval
    open fun getSourcePosition(): XSourcePosition?      // File + line
    open fun getEqualityObject(): Any?                  // Identity
    open fun customizePresentation(component: ColoredTextContainer) // Display
    // Inherited from XValueContainer:
    open fun computeChildren(node: XCompositeNode)      // Variables in this frame
}
```

---

## 5. XDebuggerEvaluator — Expression Evaluation

**Callback-based async pattern**, NOT coroutine-based.

```kotlin
abstract class XDebuggerEvaluator {
    // Core evaluation — ASYNC via callback
    abstract fun evaluate(
        expression: String,
        callback: XEvaluationCallback,
        expressionPosition: XSourcePosition?
    )

    // Convenience overload with XExpression
    open fun evaluate(
        expression: XExpression,
        callback: XEvaluationCallback,
        expressionPosition: XSourcePosition?
    )

    // Callback interface
    interface XEvaluationCallback : XValueCallback {
        fun evaluated(result: XValue)
        fun invalidExpression(error: String)  // default → errorOccurred(error)
    }

    // Async expression info
    fun getExpressionInfoAtOffsetAsync(): Promise<ExpressionInfo>

    // Capabilities
    fun isCodeFragmentEvaluationSupported(): Boolean
    fun getEvaluationMode(): EvaluationMode  // EXPRESSION or CODE_FRAGMENT
}
```

**To wrap in coroutines:**
```kotlin
suspend fun evaluateExpression(evaluator: XDebuggerEvaluator, expr: String): XValue {
    return suspendCancellableCoroutine { cont ->
        evaluator.evaluate(expr, object : XDebuggerEvaluator.XEvaluationCallback() {
            override fun evaluated(result: XValue) = cont.resume(result)
            override fun errorOccurred(errorMessage: String) =
                cont.resumeWithException(EvaluationException(errorMessage))
        }, null)
    }
}
```

---

## 6. XBreakpointManager — Programmatic Breakpoint Management

```kotlin
interface XBreakpointManager {
    // Add breakpoints
    fun <T : XBreakpointProperties> addLineBreakpoint(
        type: XLineBreakpointType<T>,
        fileUrl: String,      // VirtualFile URL: "file:///path/to/File.java"
        line: Int,            // 0-based line number
        properties: T?,
        temporary: Boolean
    ): XLineBreakpoint<T>

    fun <T : XBreakpointProperties> addBreakpoint(
        type: XBreakpointType<XBreakpoint<T>, T>,
        properties: T?
    ): XBreakpoint<T>

    // Remove
    fun removeBreakpoint(breakpoint: XBreakpoint<*>)

    // Query
    fun getAllBreakpoints(): Array<XBreakpoint<*>>
    fun <B : XBreakpoint<*>> getBreakpoints(type: XBreakpointType<B, *>): Collection<B>
    fun <B : XLineBreakpoint<P>, P : XBreakpointProperties> findBreakpointsAtLine(
        type: XLineBreakpointType<P>, file: VirtualFile, line: Int
    ): Collection<B>

    // Default breakpoints
    fun isDefaultBreakpoint(breakpoint: XBreakpoint<*>): Boolean
    fun <B : XBreakpoint<*>> getDefaultBreakpoints(type: XBreakpointType<B, *>): Set<B>

    // Breakpoint listeners
    fun <B : XBreakpoint<P>, P : XBreakpointProperties> addBreakpointListener(
        type: XBreakpointType<B, P>, listener: XBreakpointListener<B>, parent: Disposable
    )

    // UI
    fun updateBreakpointPresentation(bp: XLineBreakpoint<*>, icon: Icon?, error: String?)
}
```

**To add a Java line breakpoint:**
```kotlin
import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType

ApplicationManager.getApplication().runWriteAction {
    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
    val fileUrl = virtualFile.url  // "file:///path/to/MyClass.java"
    val bp = bpManager.addLineBreakpoint(
        JavaLineBreakpointType::class.java.let {
            XBreakpointType.EXTENSION_POINT_NAME.findExtension(it)!!
        } as XLineBreakpointType<*>,
        fileUrl,
        lineNumber,  // 0-based
        null,        // properties
        false        // temporary
    )
    // Optionally set condition:
    bp.conditionExpression = XExpressionImpl.fromText("myVar > 10")
}
```

---

## 7. XValue — Variable Inspection

Variables are `XValue` objects. Inspection is **fully callback-based**.

```kotlin
abstract class XValue : XValueContainer() {
    // Get display representation — async callback
    abstract fun computePresentation(node: XValueNode, place: XValuePlace)

    // Get child values (fields, array elements) — async callback
    // Inherited from XValueContainer:
    open fun computeChildren(node: XCompositeNode)

    // Modification
    open fun getModifier(): XValueModifier?

    // Navigation
    open fun canNavigateToSource(): Boolean
    open fun canNavigateToTypeSource(): Boolean

    // Expression for re-evaluation
    open fun getEvaluationExpression(): String?
}
```

**To read variables from a frame:**
```kotlin
suspend fun getFrameVariables(frame: XStackFrame): List<Pair<String, String>> {
    return suspendCancellableCoroutine { cont ->
        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                val vars = (0 until children.size()).map { i ->
                    children.getName(i) to children.getValue(i)
                }
                if (last) cont.resume(vars.map { (name, value) ->
                    // Further resolve each value's presentation...
                    name to resolvePresentation(value)
                })
            }
            override fun isObsolete(): Boolean = false
            override fun setAlreadySorted(alreadySorted: Boolean) {}
            override fun setErrorMessage(errorMessage: String) {
                cont.resumeWithException(RuntimeException(errorMessage))
            }
            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                cont.resumeWithException(RuntimeException(errorMessage))
            }
            override fun setMessage(message: String, icon: Icon?, attrs: SimpleTextAttributes?, link: XDebuggerTreeNodeHyperlink?) {}
            override fun tooManyChildren(remaining: Int) {}
            override fun tooManyChildren(remaining: Int, addNextChildren: Runnable?) {}
        })
    }
}
```

---

## 8. Launching a Debug Session Programmatically

Two approaches:

### Approach A: Use existing run configuration
```kotlin
val runManager = RunManager.getInstance(project)
val config = runManager.allSettings.first { it.name == "MyApp" }

// Execute in debug mode
val executor = DefaultDebugExecutor.getDebugExecutorInstance()
ProgramRunnerUtil.executeConfiguration(config, executor)
```

### Approach B: Use XDebuggerManager session builder
```kotlin
// Requires custom XDebugProcessStarter
val starter = object : XDebugProcessStarter() {
    override fun start(session: XDebugSession): XDebugProcess {
        return MyDebugProcess(session)
    }
}

// Must be called on EDT
invokeLater {
    val session = XDebuggerManager.getInstance(project)
        .newSessionBuilder(starter)
        .showTab()
        .startSession()
}
```

### Approach C: ExecutionEnvironmentBuilder (most flexible)
```kotlin
val config = runManager.findConfigurationByName("MyApp")!!
val env = ExecutionEnvironmentBuilder.create(
    DefaultDebugExecutor.getDebugExecutorInstance(),
    config.configuration
).build()

ProgramRunnerUtil.executeConfiguration(env, false, true)
```

---

## 9. Complete Agent Debug Loop Pattern

The recommended async pattern for an AI agent controlling the debugger:

```kotlin
class AgentDebugController(
    private val project: Project,
    private val scope: CoroutineScope
) : Disposable {

    private val pauseSignal = MutableSharedFlow<XSuspendContext>(extraBufferCapacity = 1)
    private val sessionSignal = MutableSharedFlow<XDebugSession>(extraBufferCapacity = 1)

    init {
        // Listen for new debug sessions
        project.messageBus.connect(this).subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: XDebugProcess) {
                    val session = debugProcess.session
                    sessionSignal.tryEmit(session)

                    session.addSessionListener(object : XDebugSessionListener {
                        override fun sessionPaused() {
                            session.suspendContext?.let { pauseSignal.tryEmit(it) }
                        }
                    }, this@AgentDebugController)
                }
            }
        )
    }

    // 1. Set breakpoint
    suspend fun setBreakpoint(filePath: String, line: Int): XLineBreakpoint<*> {
        return withContext(Dispatchers.EDT) {
            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)!!
            val bpManager = XDebuggerManager.getInstance(project).breakpointManager
            bpManager.addLineBreakpoint(
                findJavaLineBreakpointType(),
                vFile.url, line, null, false
            )
        }
    }

    // 2. Launch debug
    suspend fun launchDebug(configName: String) {
        withContext(Dispatchers.EDT) {
            val config = RunManager.getInstance(project)
                .allSettings.first { it.name == configName }
            ProgramRunnerUtil.executeConfiguration(
                config, DefaultDebugExecutor.getDebugExecutorInstance()
            )
        }
    }

    // 3. Wait for breakpoint hit
    suspend fun waitForPause(timeout: Duration = 30.seconds): XSuspendContext {
        return withTimeout(timeout) { pauseSignal.first() }
    }

    // 4. Inspect variables
    suspend fun getVariables(session: XDebugSession): Map<String, String> {
        val frame = session.currentStackFrame ?: error("No frame")
        return suspendCancellableCoroutine { cont ->
            frame.computeChildren(CollectingCompositeNode { children, last ->
                if (last) cont.resume(children)
            })
        }
    }

    // 5. Evaluate expression
    suspend fun evaluate(session: XDebugSession, expr: String): XValue {
        val evaluator = session.currentStackFrame?.evaluator ?: error("No evaluator")
        return suspendCancellableCoroutine { cont ->
            evaluator.evaluate(expr, object : XDebuggerEvaluator.XEvaluationCallback() {
                override fun evaluated(result: XValue) = cont.resume(result)
                override fun errorOccurred(msg: String) =
                    cont.resumeWithException(RuntimeException(msg))
            }, null)
        }
    }

    // 6. Step and wait
    suspend fun stepOver(session: XDebugSession): XSuspendContext {
        session.stepOver(false)
        return waitForPause()
    }

    suspend fun stepInto(session: XDebugSession): XSuspendContext {
        session.stepInto()
        return waitForPause()
    }

    suspend fun stepOut(session: XDebugSession): XSuspendContext {
        session.stepOut()
        return waitForPause()
    }

    // 7. Get stack trace
    suspend fun getStackTrace(ctx: XSuspendContext): List<XStackFrame> {
        val stack = ctx.activeExecutionStack ?: return emptyList()
        return suspendCancellableCoroutine { cont ->
            stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
                private val frames = mutableListOf<XStackFrame>()
                override fun addStackFrames(list: List<XStackFrame>, last: Boolean) {
                    frames.addAll(list)
                    if (last) cont.resume(frames)
                }
                override fun isObsolete() = false
                override fun errorOccurred(msg: String) =
                    cont.resumeWithException(RuntimeException(msg))
            })
        }
    }

    override fun dispose() {}
}
```

---

## 10. Existing Implementations & Prior Art

### Microsoft DebugMCP (VS Code)
- **Architecture:** MCP server wrapping VS Code's built-in DAP debugging
- **22 tools** covering: session management, breakpoints, stepping, variables, expression eval, threads, stack navigation
- **Key pattern:** Single `get_debug_session_status` call returns variables + stack + source in one response
- **Transport:** Streamable HTTP on port 3001
- **GitHub:** https://github.com/microsoft/DebugMCP

### JetBrains Debugger MCP Plugin (jetbrains-debugger-mcp-plugin)
- **Architecture:** IntelliJ plugin running MCP server, bridges to XDebugger API
- **22 tools** matching DebugMCP's capabilities but for JetBrains IDEs
- **Per-IDE ports:** IntelliJ 29190, PyCharm 29192, WebStorm 29193
- **Key insight:** Already implements exactly what we need — MCP-to-XDebugger bridge
- **GitHub:** https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin

### JetBrains Built-in MCP Server
- **21 tools** for file ops, search, refactoring, run configs, terminal
- **NO debugging tools** in the built-in server
- Debugging requires the separate Debugger MCP plugin

### JetBrains Agent Client Protocol (ACP)
- Standardizes agent-IDE communication (like LSP for agents)
- Does NOT currently expose debugging capabilities
- Agents access tools through MCP servers configured in the IDE

### Other AI Debug Tools (DAP-based)
- **agent-debugger** — CLI DAP tool for AI agents (Python/Node)
- **debug-skill** — Go CLI, integrated with Claude Code skills
- **dapi** — TypeScript DAP client, supports breakpoints + expression eval
- All use Debug Adapter Protocol (DAP) as the bridge layer

### AutoHotKey IntelliJ Debugger (fornever.me)
- Complete tutorial on implementing XDebugProcess from scratch
- Critical patterns: mutex-guarded event ordering, coroutine scope per suspend context
- Async computeStackFrames/computeChildren with container callbacks
- Reference: https://fornever.me/en/posts/2026-01-04.intellij-debugger.html

---

## 11. Async Pattern Recommendations for AI Agent

### Pattern: Coroutine wrappers around callback APIs

The XDebugger API is entirely **callback-based** (not coroutine-based). The recommended pattern is:

1. **Wrap callbacks with `suspendCancellableCoroutine`** for one-shot results
2. **Use `SharedFlow`** for event streams (sessionPaused, sessionResumed)
3. **Use `withContext(Dispatchers.EDT)`** for operations requiring EDT (breakpoint management, session creation)
4. **Use `withTimeout`** for operations that might never complete (waiting for breakpoint hit)

### Threading Rules
| Operation | Thread | Pattern |
|---|---|---|
| Add/remove breakpoints | EDT (WriteAction) | `withContext(Dispatchers.EDT)` |
| Create debug session | EDT | `withContext(Dispatchers.EDT)` |
| Step/resume/pause | Any | Direct call |
| Read session state | Any | Direct call |
| computeChildren | Background | `suspendCancellableCoroutine` |
| evaluate expression | Background | `suspendCancellableCoroutine` |
| sessionPaused listener | Any | `SharedFlow.emit` |

### Critical Considerations
1. **No breakpoint-specific callback** — `sessionPaused()` fires for both breakpoints and steps
2. **Callback ordering** — use mutex if multiple async ops must be ordered
3. **Obsolescence** — check `isObsolete()` in containers to handle cancelled operations
4. **Variable resolution is recursive** — `computeChildren` gives XValue objects that themselves need `computePresentation` to get display strings
5. **Expression evaluation context** — evaluator is per-frame, not per-session
6. **Session lifecycle** — always register listeners with a `Disposable` parent

---

## 12. Summary: What to Build

For the agent module, the recommended approach is:

1. **Service Interface in `:core`** — `DebugService` with `ToolResult<T>` returns
2. **Implementation in a debug module** — wraps XDebugger API with coroutine wrappers
3. **Agent tools** — thin wrappers calling `DebugService` methods

The existing **jetbrains-debugger-mcp-plugin** is the closest reference implementation. Its 22-tool API surface is the right granularity for AI agent debugging.

**Minimum viable tool set (ordered by priority):**
1. `set_breakpoint` / `remove_breakpoint` / `list_breakpoints`
2. `start_debug_session` / `stop_debug_session`
3. `get_debug_session_status` (combined variables + stack + source)
4. `step_over` / `step_into` / `step_out` / `resume`
5. `evaluate_expression`
6. `get_stack_trace` / `get_variables`
