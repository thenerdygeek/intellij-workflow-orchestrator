# IDE Runtime, Debugging & Configuration Tools

**Date**: 2026-03-22
**Status**: Design
**Module**: `:agent`
**Scope**: 23 new tools + 1 tool upgrade + `AgentDebugController` service + 2 skill modifications

---

## Problem Statement

The AI agent currently operates blind to runtime state. It can read code, edit files, and run shell commands — but it cannot:

1. **See what happened** — no access to test results (structured), application logs, or debug console output from IDE run sessions
2. **Interactively debug** — cannot set breakpoints, step through code, inspect variables, or evaluate expressions at runtime
3. **Manage run configurations** — cannot create or modify IntelliJ run/debug configs needed to launch debug sessions

CLI-based AI tools (Claude Code, Aider, OpenCode) are fundamentally limited to shell `stdout`. An IDE-native agent has access to structured execution data — test results as objects, stack frames as navigable trees, breakpoints as first-class entities. This is the primary differentiator.

### Prior Art

| Tool | Debugging Capability |
|---|---|
| Claude Code | None (CLI only) |
| Cursor / Windsurf | None |
| Devin | Browser-based test execution, no stepping |
| JetBrains AI Assistant | Read-only context from open editor, no debugger integration |
| **Microsoft DebugMCP** | 14 MCP tools wrapping VS Code DAP — breakpoints, stepping, variables, evaluation |
| **JetBrains Debugger MCP Plugin** | 22 MCP tools wrapping IntelliJ XDebugger — full session lifecycle, variables, stack, evaluation |

Both Microsoft DebugMCP and the JetBrains Debugger MCP plugin validate this approach. Our design is informed by both, adapted to our agent's ReAct loop architecture.

---

## Architecture Overview

```
tools/
  runtime/                          ← Category: Runtime & Logs (read-only)
    GetTestResultsTool                 get_test_results
    GetRunOutputTool                   get_run_output
    GetRunningProcessesTool            get_running_processes
    GetRunConfigurationsTool           get_run_configurations

  debug/                            ← Category: Interactive Debugging
    AgentDebugController.kt            Shared coroutine wrapper for XDebugger callbacks
    AddBreakpointTool                  add_breakpoint
    RemoveBreakpointTool               remove_breakpoint
    ListBreakpointsTool                list_breakpoints
    StartDebugSessionTool              start_debug_session
    GetDebugStateTool                  get_debug_state
    DebugStepOverTool                  debug_step_over
    DebugStepIntoTool                  debug_step_into
    DebugStepOutTool                   debug_step_out
    DebugResumeTool                    debug_resume
    DebugPauseTool                     debug_pause
    DebugRunToCursorTool               debug_run_to_cursor
    DebugStopTool                      debug_stop
    EvaluateExpressionTool             evaluate_expression
    GetStackFramesTool                 get_stack_frames
    GetVariablesTool                   get_variables

  config/                           ← Category: Run Configuration Management
    CreateRunConfigTool                create_run_config
    ModifyRunConfigTool                modify_run_config
    DeleteRunConfigTool                delete_run_config

  ide/
    RunTestsTool                    ← UPGRADED: native IntelliJ test runner
```

### Dependency Graph

```
                    AgentDebugController
                    (coroutine wrapper)
                   /        |         \
          debug tools    config tools   runtime tools
              |               |              |
        XDebuggerManager  RunManager   ExecutionManager
        XBreakpointManager             ConsoleView
        XDebugSession                  SMTestProxy
        XDebuggerEvaluator
```

All debug tools delegate to `AgentDebugController` for async operations. Config and runtime tools use IntelliJ APIs directly (they're simpler — no callbacks needed).

---

## Category 1: Runtime & Logs Tools

Read-only tools that give the agent visibility into IDE execution state. All **NONE risk** — auto-approved.

### 1.1 `get_run_configurations`

**Purpose**: List all available run/debug configurations.

**Parameters**:
| Parameter | Type | Required | Description |
|---|---|---|---|
| `type_filter` | string | no | Filter by type: `application`, `spring_boot`, `junit`, `gradle`, `remote_debug` |

**IntelliJ API**: `RunManager.getInstance(project).allSettings`

**Output**:
```
Run Configurations (5):

[Application] MyApp
  Main class: com.example.Application
  Module: app-main
  VM options: -Xmx512m
  Active profiles: dev

[JUnit] UserServiceTest
  Test class: com.example.service.UserServiceTest
  Module: app-main

[Spring Boot] Backend-Dev
  Main class: com.example.BackendApplication
  Active profiles: dev,local
  Env vars: DB_HOST=localhost, LOG_LEVEL=DEBUG

[Gradle] clean build
  Tasks: clean build
  Arguments: --no-daemon

[Remote Debug] Remote-8000
  Host: localhost
  Port: 8000
  Transport: Socket
```

### 1.2 `get_running_processes`

**Purpose**: List currently active run/debug sessions in the IDE.

**Parameters**: None.

**IntelliJ API**: `ExecutionManager.getInstance(project).getRunningProcesses()` + `XDebuggerManager.getInstance(project).debugSessions`

**Output**:
```
Active Sessions (2):

[Running] Backend-Dev (Spring Boot)
  PID: 54321
  Started: 2m 34s ago
  Port: 8080

[Debug - PAUSED] UserServiceTest (JUnit)
  Session ID: debug-1
  Paused at: UserService.kt:78
  Breakpoint: line 78 (conditional: user.id == 42)
  Threads: 3 (1 suspended)
```

### 1.3 `get_run_output`

**Purpose**: Get console output from an active or recently completed run session.

**Parameters**:
| Parameter | Type | Required | Description |
|---|---|---|---|
| `config_name` | string | yes | Name of the run configuration |
| `last_n_lines` | int | no | Last N lines (default 200, max 1000) |
| `filter` | string | no | Regex pattern to filter lines (e.g., `ERROR\|WARN`, `UserService`) |

**IntelliJ API**: Content from `ConsoleView` attached to the `ProcessHandler` of the matching run session. Uses `ConsoleViewImpl.getText()` for completed sessions, or captures via `ConsoleViewContentType` listener for active ones.

**Output** (with `filter="ERROR"`):
```
Console output for [Backend-Dev] (filtered: ERROR):
Lines 1842-1847 of 1847:

2026-03-22 14:32:01 ERROR UserService - Failed to create user: duplicate email
  at com.example.service.UserService.createUser(UserService.kt:78)
  at com.example.controller.UserController.create(UserController.kt:34)
  Caused by: org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint "users_email_key"
    at org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(QueryExecutorImpl.java:2725)
```

### 1.4 `get_test_results`

**Purpose**: Get structured test results from the most recent (or specified) test run.

**Parameters**:
| Parameter | Type | Required | Description |
|---|---|---|---|
| `config_name` | string | no | Specific test run config name. If omitted, uses most recent test execution. |
| `status_filter` | string | no | `FAILED`, `ERROR`, `PASSED`, `SKIPPED` — only show tests with this status |

**IntelliJ API**: `SMTestProxy` tree from `SMTestRunnerConnectionUtil`. Each `SMTestProxy` has:
- `getName()` — test method name
- `getMagnitude()` — pass/fail/error/skip
- `getDuration()` — milliseconds
- `getErrorMessage()` — assertion failure message
- `getStacktrace()` — full stack trace
- `getOutput()` — stdout captured during test

**Output**:
```
Test Run: UserServiceTest (JUnit 5)
Config: [JUnit] UserServiceTest
Status: FAILED (12 passed, 2 failed, 1 error, 0 skipped)
Duration: 3.4s

--- FAILED ---

testCreateUser_duplicateEmail (120ms)
  Assertion: expected <409> but was <500>
  Stack:
    UserServiceTest.testCreateUser_duplicateEmail(UserServiceTest.kt:45)
    UserService.createUser(UserService.kt:78)
  Output: [SQL] INSERT INTO users (email, name) VALUES ('test@example.com', 'Test')

testDeleteUser_notFound (45ms)
  Assertion: Expected NotFoundException but no exception thrown
  Stack:
    UserServiceTest.testDeleteUser_notFound(UserServiceTest.kt:92)

--- ERROR ---

testUpdateUser_concurrentModification (230ms)
  Exception: org.hibernate.StaleObjectStateException: Row was updated or deleted by another transaction
  Stack:
    UserServiceTest.testUpdateUser_concurrentModification(UserServiceTest.kt:120)
    UserService.updateUser(UserService.kt:112)
    HibernateTemplate.merge(HibernateTemplate.java:289)
```

### 1.5 `run_tests` Upgrade

**Current**: Shells out to `mvn test` / `./gradlew test` via ProcessBuilder. Returns raw stdout (4000 char cap).

**Upgraded**: Uses IntelliJ's native test runner API:

1. Build a `JUnitConfiguration` or `TestNGConfiguration` programmatically
2. Execute via `ExecutionUtil.runConfiguration()` with `DefaultRunExecutor`
3. Attach `SMTestRunnerConnectionUtil` listener to capture structured results
4. Wait for completion via `ProcessHandler.addProcessListener()` → `processTerminated()`
5. Return structured results (same format as `get_test_results`)

**Benefits**:
- Structured pass/fail/error per test method (not raw stdout)
- Assertion messages and stack traces as separate fields
- Test duration per method
- Output capture per test (not interleaved)
- Results available via `get_test_results` for follow-up queries

**Fallback**: If native runner fails (unsupported test framework, indexing in progress), fall back to current Maven/Gradle shell execution with a warning.

**Parameters** (unchanged): `class_name`, `method?`

**New parameters**:
- `use_native_runner` (boolean, default true) — allows explicit fallback to Maven/Gradle shell
- `timeout` (int, default 120, max 600) — seconds before `ProcessHandler.destroyProcess()` is called. On timeout, returns partial results collected so far with a `"[TIMEOUT] Test execution exceeded ${timeout}s. Partial results shown."` warning.

**Timeout mechanism**: Register a `ScheduledExecutorService.schedule()` callback that calls `processHandler.destroyProcess()` after the timeout. The `processTerminated()` listener fires normally, and partial `SMTestProxy` results are returned.

---

## Category 2: Interactive Debugging Tools

### 2.1 `AgentDebugController` — Shared Service

Central coroutine wrapper for IntelliJ's callback-based XDebugger API. All debug tools delegate to this controller.

```kotlin
class AgentDebugController(private val project: Project) : Disposable {

    // --- Session tracking ---
    private val sessions = ConcurrentHashMap<String, XDebugSession>()
    private val sessionIdCounter = AtomicInteger(0)
    private val pauseFlows = ConcurrentHashMap<String, MutableSharedFlow<DebugPauseEvent>>()
    // SharedFlow with replay=1 to prevent race condition:
    // if sessionPaused() fires between isSuspended check and first() subscription,
    // the event is retained in the replay buffer.

    // --- Lifecycle ---
    init {
        // Subscribe to XDebuggerManager.TOPIC for session start/stop
        project.messageBus.connect(this).subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: XDebugProcess) { ... }
                override fun processStopped(debugProcess: XDebugProcess) { ... }
            }
        )
    }

    // --- Session control ---
    fun launchDebug(configName: String): String  // returns session ID
    fun getSession(sessionId: String?): XDebugSession?  // null = current
    fun getActiveSessionId(): String?

    // --- Async wait ---
    suspend fun waitForPause(sessionId: String, timeoutMs: Long = 5000): DebugPauseState?
    // Uses suspendCancellableCoroutine + XDebugSessionListener.sessionPaused()

    // --- Inspection (all suspend, all callback-wrapped) ---
    suspend fun getStackFrames(session: XDebugSession, maxFrames: Int = 20): List<FrameInfo>
    // Wraps XExecutionStack.computeStackFrames(container) callback

    suspend fun getVariables(frame: XStackFrame, maxDepth: Int = 2): List<VariableInfo>
    // Wraps XStackFrame.computeChildren(node) callback, recursive to maxDepth

    suspend fun evaluate(session: XDebugSession, expression: String, frameIndex: Int = 0): EvaluationResult
    // Wraps XDebuggerEvaluator.evaluate(expression, callback)

    // --- Cleanup (N5: orphaned session protection) ---
    fun stopAllSessions() {
        sessions.values.forEach { session ->
            if (!session.isStopped) session.stop()
        }
        sessions.clear()
        pauseFlows.clear()
    }

    fun removeAgentBreakpoints() {
        // Remove breakpoints that were added by agent tools (tracked via metadata)
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

**Data classes**:
```kotlin
data class DebugPauseEvent(
    val sessionId: String,
    val position: SourcePosition?,  // file + line
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
    val children: List<VariableInfo>  // nested fields (controlled by maxDepth)
)

data class EvaluationResult(
    val expression: String,
    val result: String,
    val type: String,
    val isError: Boolean = false
)
```

**Threading rules** (from research):
| Operation | Thread Requirement |
|---|---|
| Add/remove breakpoint | EDT + WriteAction |
| Create/launch session | EDT |
| step/resume/pause/stop | Any thread |
| computeStackFrames | Background, callback-based |
| computeChildren (variables) | Background, callback-based |
| evaluate | Background, callback-based |

All EDT requirements handled internally by the controller using `withContext(Dispatchers.EDT)`.

**Listener pattern**:
```kotlin
// When a debug session is registered, attach a listener
session.addSessionListener(object : XDebugSessionListener {
    override fun sessionPaused() {
        val position = session.currentPosition
        pauseFlows[sessionId]?.tryEmit(DebugPauseEvent(
            sessionId = sessionId,
            position = position,
            reason = if (session.currentBreakpoint != null) "breakpoint" else "step"
        ))
    }
    override fun sessionResumed() { /* update state */ }
    override fun sessionStopped() {
        sessions.remove(sessionId)
        pauseFlows.remove(sessionId)
    }
}, this)  // disposable = controller
```

**`waitForPause` implementation**:
```kotlin
suspend fun waitForPause(sessionId: String, timeoutMs: Long): DebugPauseState? {
    val session = sessions[sessionId] ?: return null
    val flow = pauseFlows[sessionId] ?: return null

    // Subscribe FIRST to avoid race condition, then check isSuspended.
    // With replay=1, even if sessionPaused() fired before we subscribe,
    // the event is in the replay buffer.
    return withTimeoutOrNull(timeoutMs) {
        // If already paused, the replay buffer has the event
        if (session.isSuspended) {
            return@withTimeoutOrNull buildPauseState(session, sessionId)
        }
        // Otherwise wait for next sessionPaused() emission
        flow.first()
        buildPauseState(session, sessionId)
    }
}

// When creating flows for new sessions:
// pauseFlows[sessionId] = MutableSharedFlow(replay = 1)
```

### 2.2 Breakpoint Management Tools

#### `add_breakpoint`

**Parameters**:
| Parameter | Type | Required | Description |
|---|---|---|---|
| `file` | string | yes | File path (relative to project root or absolute) |
| `line` | int | yes | Line number (1-based) |
| `condition` | string | no | Conditional expression (e.g., `user.getId() == 42`) |
| `log_expression` | string | no | Expression to log when hit without stopping (non-suspending breakpoint) |
| `temporary` | boolean | no | Auto-remove after first hit (default false) |

**Implementation**:
```kotlin
// Must run on EDT inside WriteAction
withContext(Dispatchers.EDT) {
    WriteAction.run<Exception> {
        val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
        val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
        // Dispatch breakpoint type based on file extension
        val type = when {
            absolutePath.endsWith(".kt") || absolutePath.endsWith(".kts") ->
                KotlinLineBreakpointType::class.java
            else -> JavaLineBreakpointType::class.java
        }
        val bpType = XDebuggerUtil.getInstance().findBreakpointType(type)
        val bp = breakpointManager.addLineBreakpoint(
            bpType, vFile!!.url, line - 1, /* 0-based */ null, temporary ?: false
        )
        bp?.let {
            if (condition != null) it.conditionExpression = XExpression.fromText(condition)
            if (logExpression != null) {
                it.logExpressionObject = XExpression.fromText(logExpression)
                it.isSuspendPolicy = SuspendPolicy.NONE  // log only, don't stop
            }
        }
    }
}
```

**Output**:
```
Breakpoint added at UserService.kt:78
  Condition: user.getId() == 42
  Type: conditional, suspend
```

#### `remove_breakpoint`

**Parameters**: `file` (string, required), `line` (int, required)

**Implementation**: `XBreakpointManager.findBreakpointsAtLine()` + `removeBreakpoint()`

**Output**: `Breakpoint removed at UserService.kt:78`

#### `list_breakpoints`

**Parameters**: `file` (string, optional — filter by file)

**Implementation**: `XBreakpointManager.getAllBreakpoints()`, filter by file if specified

**Output**:
```
Breakpoints (3):

UserService.kt:78 [enabled, conditional: user.getId() == 42]
UserService.kt:95 [enabled, temporary]
UserController.kt:34 [disabled, log: "Request: ${request}"]
```

### 2.3 Session Control Tools

#### `start_debug_session`

**Parameters**:
| Parameter | Type | Required | Description |
|---|---|---|---|
| `config_name` | string | yes | Run configuration name to launch in debug mode |
| `wait_for_pause` | int | no | Wait up to N seconds for first breakpoint hit (default: 0 = return immediately) |

**Implementation**:
```kotlin
// 1. Find the run configuration
val runManager = RunManager.getInstance(project)
val settings = runManager.findConfigurationByName(configName)
    ?: return ToolResult(isError = true, content = "Run configuration '$configName' not found")

// 2. Launch in debug mode on EDT
val sessionId = withContext(Dispatchers.EDT) {
    val environment = ExecutionEnvironmentBuilder
        .create(project, DefaultDebugExecutor.getDebugExecutorInstance(), settings)
        .build()
    ProgramRunnerUtil.executeConfiguration(environment, true)
    controller.registerPendingSession(configName)
}

// 3. Optionally wait for first pause
if (waitForPause > 0) {
    val state = controller.waitForPause(sessionId, waitForPause * 1000L)
    if (state != null) {
        return ToolResult(content = "Debug session started and paused.\n$state")
    }
}

return ToolResult(content = "Debug session '$configName' started. Session ID: $sessionId\nStatus: running")
```

**Output** (with `wait_for_pause=30`):
```
Debug session started and paused.
Session ID: debug-1
Status: PAUSED at UserService.kt:78
Breakpoint: line 78 (conditional: user.getId() == 42)
Top frame variables:
  request: CreateUserRequest {email="test@example.com", name="Test"}
  this: UserService@7a3d1
```

#### `debug_step_over` / `debug_step_into` / `debug_step_out`

**Parameters**: `session_id` (string, optional — defaults to current session)

**Implementation** (same pattern for all three):
```kotlin
val session = controller.getSession(sessionId) ?: return sessionNotFound()
if (!session.isSuspended) return ToolResult(isError = true, content = "Session not paused")

session.stepOver()  // or stepInto() / stepOut()

// Wait for next pause (step completion) — always fast (<100ms for step operations)
val state = controller.waitForPause(session.id, timeoutMs = 5000)
    ?: return ToolResult(content = "Step executed. Session is running (no immediate pause).")

return ToolResult(content = "Stepped to ${state.file}:${state.line}\n\nVariables:\n${state.topFrameVariables}")
```

**Output**:
```
Stepped to UserService.kt:79

Variables:
  request: CreateUserRequest {email="test@example.com", name="Test"}
  existingUser: User? = null
  validationResult: ValidationResult {valid=true, errors=[]}
```

Step tools auto-include top-frame variables in the response to save the agent a separate `get_variables` call. This is the pattern used by the JetBrains Debugger MCP plugin — `get_debug_session_status` returns variables + stack + source in a single call to minimize round-trips.

#### `debug_resume`

**Parameters**: `session_id` (optional)

Calls `session.resume()`. Returns `"Session resumed. Status: running"`.

#### `debug_pause`

**Parameters**: `session_id` (optional)

Calls `session.pause()` + `waitForPause(5000)`. Returns pause state or `"Pause requested"`.

#### `debug_run_to_cursor`

**Parameters**: `file` (string, required), `line` (int, required), `session_id` (optional)

Calls `session.runToPosition(position, false)` + `waitForPause(30000)`.

#### `debug_stop`

**Parameters**: `session_id` (optional)

Calls `session.stop()`. Returns `"Debug session stopped."`. Cleans up from `AgentDebugController.sessions`.

### 2.4 Inspection Tools

#### `get_debug_state`

**Parameters**: `session_id` (optional)

Returns current state without modifying it:
```
Session: debug-1
Status: PAUSED at UserService.kt:78
Reason: breakpoint (conditional: user.getId() == 42)
Suspended threads: 1 of 3
  main (SUSPENDED) at UserService.createUser(UserService.kt:78)
  pool-1-thread-1 (RUNNING)
  Finalizer (WAITING)
```

Also works for checking if a session is still running (after `start_debug_session` without `wait_for_pause`).

#### `get_stack_frames`

**Parameters**:
| Parameter | Type | Required | Description |
|---|---|---|---|
| `session_id` | string | no | Default: current session |
| `thread_name` | string | no | Specific thread (default: suspended thread) |
| `max_frames` | int | no | Default 20, max 50 |

**Output**:
```
Stack trace (main thread, 8 frames):

#0  UserService.createUser(UserService.kt:78)
#1  UserController.create(UserController.kt:34)
#2  sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
#3  org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:255)
#4  org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)
#5  javax.servlet.http.HttpServlet.service(HttpServlet.java:764)
#6  org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:166)
#7  org.springframework.web.filter.RequestContextFilter.doFilterInternal(RequestContextFilter.java:100)
```

#### `get_variables`

**Parameters**:
| Parameter | Type | Required | Description |
|---|---|---|---|
| `session_id` | string | no | Default: current session |
| `frame_index` | int | no | Stack frame index (default 0 = top frame) |
| `max_depth` | int | no | Object expansion depth (default 2, max 4) |
| `variable_name` | string | no | Specific variable to inspect (expands fully to max_depth) |

**Output** (depth 2):
```
Frame #0: UserService.createUser(UserService.kt:78)

Variables:
  request: CreateUserRequest
    .email: String = "test@example.com"
    .name: String = "Test User"
    .role: Role = ADMIN
    .metadata: Map<String, String> (2 entries)
      ["source"] = "api"
      ["version"] = "2.1"
  existingUser: User? = null
  this: UserService
    .userRepository: UserRepository$$SpringCGLIB@4f2e
    .passwordEncoder: BCryptPasswordEncoder@8c1a
    .eventPublisher: ApplicationEventPublisher@9d3f
```

**Depth control**:
- Depth 0: names and types only
- Depth 1: names, types, and primitive values (strings, numbers, booleans, enums)
- Depth 2: expand one level of object fields (default)
- Depth 3-4: deeper expansion (for specific `variable_name` only, to control token usage)

**Token budget**: Variables are capped at 3000 characters. If exceeded, deeper levels are truncated with `"... (use variable_name parameter to inspect specific variable)"`.

#### `evaluate_expression`

**Parameters**:
| Parameter | Type | Required | Description |
|---|---|---|---|
| `expression` | string | yes | Java/Kotlin expression to evaluate in current context |
| `session_id` | string | no | Default: current session |
| `frame_index` | int | no | Stack frame for evaluation context (default 0) |

**Examples**:
- `user.getEmail()` — method call
- `request.metadata.size()` — chained access
- `list.stream().filter(x -> x.isActive()).count()` — lambda
- `String.format("User %s has %d orders", user.name, orders.size)` — static method
- `user instanceof AdminUser` — type check

**Output**:
```
Expression: userRepository.findByEmail(request.email)
Result: User {id=42, email="test@example.com", name="Existing User", createdAt=2026-03-20T10:00:00}
Type: com.example.model.User
```

**Error output**:
```
Expression: nonExistentVar.method()
Error: Cannot find local variable 'nonExistentVar'
```

**Security**: Expressions are evaluated in the debugged JVM, not the IDE's JVM. Side effects are possible (e.g., `userRepository.delete(user)` would execute against the database). For this reason, `evaluate_expression` is classified as **MEDIUM risk** — when `approvalRequired=true` (the default), the user sees the expression text in the approval dialog before it runs. This prevents the agent from executing arbitrary method calls without user awareness.

---

## Category 3: Run Configuration Management

### 3.1 `create_run_config`

**Parameters**:
| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Config name (auto-prefixed with `[Agent]`) |
| `type` | string | yes | `application`, `spring_boot`, `junit`, `gradle`, `remote_debug` |
| `main_class` | string | conditional | Required for `application` and `spring_boot` |
| `module` | string | no | Module name (auto-detected if omitted) |
| `test_class` | string | conditional | Required for `junit` |
| `test_method` | string | no | Specific test method (JUnit only) |
| `env_vars` | object | no | Environment variables as key-value pairs |
| `vm_options` | string | no | JVM options (e.g., `-Xmx512m -Dspring.profiles.active=dev`) |
| `program_args` | string | no | Program arguments |
| `working_dir` | string | no | Working directory (default: project root) |
| `active_profiles` | string | no | Spring profiles (Spring Boot only, comma-separated) |
| `port` | int | no | Remote debug port (remote_debug only, default 5005) |

**Implementation**:
```kotlin
// 1. Validate class exists (PSI lookup)
if (mainClass != null) {
    val found = ReadAction.nonBlocking<Boolean> {
        JavaPsiFacade.getInstance(project).findClass(mainClass, GlobalSearchScope.projectScope(project)) != null
    }.inSmartMode(project).executeSynchronously()
    if (!found) return ToolResult(isError = true, content = "Class '$mainClass' not found in project")
}

// 2. Create configuration on EDT
val configName = "[Agent] $name"
withContext(Dispatchers.EDT) {
    val runManager = RunManager.getInstance(project)
    val factory = when (type) {
        "application" -> ApplicationConfigurationType.getInstance().configurationFactories[0]
        "spring_boot" -> SpringBootApplicationConfigurationType::class.java...
        "junit" -> JUnitConfigurationType.getInstance().configurationFactories[0]
        "gradle" -> GradleExternalTaskConfigurationType.getInstance().factory
        "remote_debug" -> RemoteConfigurationType.getInstance().configurationFactories[0]
    }
    val settings = runManager.createConfiguration(configName, factory)
    val config = settings.configuration

    // Apply parameters based on type...
    when (config) {
        is ApplicationConfiguration -> {
            config.mainClassName = mainClass
            config.programParameters = programArgs
            config.vmParameters = vmOptions
            config.envs = envVars ?: emptyMap()
        }
        is JUnitConfiguration -> {
            config.persistentData.TEST_OBJECT = if (testMethod != null) "method" else "class"
            config.persistentData.MAIN_CLASS_NAME = testClass
            config.persistentData.METHOD_NAME = testMethod ?: ""
        }
        // ... other types
    }

    runManager.addConfiguration(settings)
}
```

**Output**:
```
Created run configuration: [Agent] Debug-UserServiceTest
  Type: JUnit
  Test class: com.example.service.UserServiceTest
  Test method: testCreateUser_duplicateEmail
  Module: app-main (auto-detected)
```

### 3.2 `modify_run_config`

**Parameters**:
| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Config name to modify |
| `env_vars` | object | no | Environment variables (merged with existing) |
| `vm_options` | string | no | JVM options (replaces existing) |
| `program_args` | string | no | Program arguments (replaces existing) |
| `working_dir` | string | no | Working directory |
| `active_profiles` | string | no | Spring profiles (Spring Boot only) |

Can modify any config — user's or agent-created. Modifying `[Agent]`-prefixed configs is MEDIUM risk. Modifying user configs (non-`[Agent]` prefix) is elevated to HIGH risk — the approval dialog shows what will change.

### 3.3 `delete_run_config`

**Parameters**: `name` (string, required)

**Safety guardrail**: Only deletes configs with `[Agent]` prefix. If the user passes a non-agent config name:
```
Error: Cannot delete 'Backend-Dev' — only agent-created configurations (prefixed with [Agent]) can be deleted.
Use IntelliJ's Run Configuration editor to manage your own configurations.
```

---

## Category 4: Skills

### 4.1 `systematic-debugging` — Updates

**Changes to existing skill:**

1. **Add `get_test_results` and `get_run_output` to preferred-tools**:
```yaml
preferred-tools: [diagnostics, search_code, read_file, run_command, find_references,
  find_definition, git_status, git_blame, think, run_tests, compile_module,
  get_test_results, get_run_output, get_running_processes]
```

2. **Update Phase 1, Step 1 — "Read Error Messages"**: Add instruction to use `get_test_results` for structured test failures instead of parsing raw stdout:
```markdown
1. **Read Error Messages Carefully**
   - Use `get_test_results` to get structured test failures (assertion messages, stack traces per test)
   - Use `get_run_output` to check application logs for errors (filter with `ERROR|WARN|Exception`)
   - Use `diagnostics` tool to get IDE-level error analysis
   - Don't skip past errors — read stack traces completely
   - Note line numbers, file paths, error codes
```

3. **Update Phase 1, Step 2 — "Reproduce Consistently"**: Reference `get_running_processes` to check if the app is already running:
```markdown
2. **Reproduce Consistently**
   - Use `get_running_processes` to check if the application is already running
   - Use `run_tests` to run the failing test in isolation (now returns structured results)
   - Use `compile_module` to verify compilation errors
```

4. **Add new section between Phase 1 and Phase 2 — "Escalation Decision Point"**:
```markdown
### Escalation: Do You Need the Debugger?

After Phase 1 investigation, decide your next approach:

**Stay with static analysis (default path) when:**
- Error message + stack trace clearly points to the bug
- The bug is in logic you can read and reason about
- A log statement or assertion would confirm the hypothesis
- The fix is obvious from code reading

**Escalate to interactive debugging when:**
- The bug depends on runtime state you can't determine from code reading alone
- You need to inspect what value a variable actually holds at a specific execution point
- The call chain is too complex to trace statically (e.g., Spring proxies, AOP, dynamic dispatch)
- You suspect a race condition, timing issue, or ordering problem
- Previous static analysis (Phase 1) didn't reveal the root cause
- You need to verify what a method actually returns vs. what you expect

**If escalating:** activate the `interactive-debugging` skill, then return here for Phase 2 after.

Most bugs (>80%) are solvable with static analysis. Reserve the debugger for the cases where
you genuinely need to observe runtime state.
```

5. **Update Phase 3, Step 4 — "When You Don't Know"**: Reference the agent tool instead of deprecated `delegate_task`:
```markdown
4. **When You Don't Know**
   - Say "I don't understand X"
   - Use `agent` to spawn an explorer subagent to investigate a specific aspect
   - Check SonarQube for related issues: use `sonar_issues` filtered to the affected file
```

### 4.2 `interactive-debugging` — New Skill

**File**: `agent/src/main/resources/skills/interactive-debugging/SKILL.md`

```markdown
---
name: interactive-debugging
description: Use when you need to set breakpoints, step through code, inspect runtime variables, or evaluate expressions in a live debug session. Escalation from systematic-debugging when static analysis is insufficient.
user-invocable: false
preferred-tools: [add_breakpoint, remove_breakpoint, list_breakpoints, start_debug_session,
  get_debug_state, debug_step_over, debug_step_into, debug_step_out, debug_resume,
  debug_stop, debug_pause, debug_run_to_cursor, evaluate_expression, get_stack_frames, get_variables,
  get_run_configurations, create_run_config, get_test_results, get_run_output, think]
---

# Interactive Debugging

## Overview

You have full access to IntelliJ's debugger. You can set breakpoints, launch debug sessions,
step through code, inspect variables, and evaluate expressions — all programmatically.

**This is powerful but expensive.** Each step+inspect cycle costs 2 iterations of your budget.
A typical debug session uses 6-12 iterations. Plan carefully.

## When to Use

You should already be here via systematic-debugging's escalation decision. You need runtime
state observation that code reading alone cannot provide.

## The Three Patterns

### Pattern 1: Strategic Breakpoint (Most Common, Most Efficient)

Set a breakpoint at the suspicious location, run, inspect state. No stepping needed.

1. Use `think` to identify the exact line where you need to see runtime state
2. `add_breakpoint` at that line (use `condition` to filter if in a loop/high-traffic path)
3. `start_debug_session` with `wait_for_pause` (or `get_debug_state` to poll)
4. `get_variables` to inspect local state
5. `evaluate_expression` for computed values (e.g., method return values, collection sizes)
6. You now have the information. `debug_stop` and return to systematic-debugging Phase 2.

**Cost: 4-6 iterations.**

### Pattern 2: Observation Breakpoints (Zero-Pause Debugging)

Use log breakpoints to observe values at multiple points without stopping execution.
Best for understanding data flow across methods.

1. `add_breakpoint` at point A with `log_expression: "Point A: user=${user}, valid=${isValid}"`
2. `add_breakpoint` at point B with `log_expression: "Point B: result=${result}"`
3. `add_breakpoint` at point C with `log_expression: "Point C: saved=${savedEntity}"`
4. Run (not debug — log breakpoints work in debug mode) with `start_debug_session`
5. Trigger the code path (via test or manually)
6. `get_run_output` with `filter="Point [ABC]"` to see the logged values
7. Remove breakpoints. Analyze the flow.

**Cost: 5-7 iterations.** But gives you the full data flow picture.

### Pattern 3: Step-Through (Last Resort)

Single-step through code. Use only when Patterns 1-2 didn't give you enough information.

1. Set breakpoint at the START of the suspicious region
2. `start_debug_session` with `wait_for_pause`
3. `debug_step_over` — examine each line's effect on variables
4. At method calls of interest: `debug_step_into` to follow the call
5. When deep enough: `debug_step_out` to return to caller
6. When you understand the issue: `debug_stop`

**Cost: 8-15+ iterations.** Use `debug_run_to_cursor` to skip uninteresting sections.

## Budget Rules

- **Set a budget before starting**: decide how many iterations you'll spend on debugging
- **10 debug iterations max** without finding the issue = STOP
- If 10 iterations didn't help: `debug_stop`, summarize observations, ask the user
- **Prefer conditional breakpoints** over stepping through loops
- **Prefer `evaluate_expression`** over stepping to a variable assignment
- **Prefer `debug_run_to_cursor`** over repeated `debug_step_over`

## Session Lifecycle

ALWAYS follow this lifecycle:

1. Check `get_run_configurations` — does a suitable config exist?
2. If not: `create_run_config` for the specific test/class you need
3. Set breakpoints BEFORE launching
4. `start_debug_session`
5. Inspect/step as needed
6. **ALWAYS `debug_stop` when done** — never leave a debug session running
7. **ALWAYS `remove_breakpoint`** for temporary breakpoints — never leave breakpoint litter

## Conditional Breakpoint Tips

Instead of stepping through a loop of 1000 items:
```
add_breakpoint(file="OrderService.kt", line=45, condition="order.total > 10000")
```

Instead of pausing on every request:
```
add_breakpoint(file="UserController.kt", line=20, condition="request.getHeader('X-Debug') != null")
```

## Common Pitfalls

- **Don't step through framework code.** Spring proxies, Hibernate internals, reflection — use
  `debug_step_out` or `debug_run_to_cursor` to skip past them.
- **Don't debug without a hypothesis.** "Let me just step through and see" wastes iterations.
  Have a specific question: "What value does X have at line Y?"
- **Don't forget to stop.** An abandoned debug session holds resources and can affect IDE performance.
- **Don't evaluate side-effecting expressions carelessly.** `userRepository.delete(user)` in
  `evaluate_expression` WILL execute against the database.

## Tool Quick Reference

| Phase | Tools | Purpose |
|-------|-------|---------|
| Setup | `get_run_configurations`, `create_run_config` | Ensure config exists |
| Breakpoints | `add_breakpoint`, `remove_breakpoint`, `list_breakpoints` | Set observation points |
| Launch | `start_debug_session` | Begin debug session |
| Navigate | `debug_step_over/into/out`, `debug_run_to_cursor`, `debug_resume` | Move through code |
| Inspect | `get_debug_state`, `get_variables`, `get_stack_frames`, `evaluate_expression` | Observe state |
| Cleanup | `debug_stop`, `remove_breakpoint` | Always clean up |
```

---

## Risk Levels & ApprovalGate Integration

### New risk assignments

| Risk Level | Tools | Rationale |
|---|---|---|
| **NONE** | `get_run_configurations`, `get_running_processes`, `get_run_output`, `get_test_results`, `list_breakpoints`, `get_debug_state`, `get_stack_frames`, `get_variables` | Read-only — observe state without modifying it |
| **MEDIUM** | `add_breakpoint`, `remove_breakpoint`, `create_run_config`, `modify_run_config`, `evaluate_expression`, `debug_step_over`, `debug_step_into`, `debug_step_out`, `debug_resume`, `debug_pause`, `debug_run_to_cursor` | Modify IDE state or execute within approved debug session context. Step/resume/pause are MEDIUM (not HIGH) because they only operate within an already-approved debug session — the meaningful approval gate is `start_debug_session`. |
| **HIGH** | `start_debug_session`, `debug_stop`, `delete_run_config` | Launch processes or perform destructive cleanup |

### ApprovalGate updates

Add to `ApprovalGate.kt`:

```kotlin
// NONE_RISK_TOOLS — add:
"get_run_configurations", "get_running_processes", "get_run_output", "get_test_results",
"list_breakpoints", "get_debug_state", "get_stack_frames", "get_variables",

// MEDIUM_RISK_TOOLS — add:
"add_breakpoint", "remove_breakpoint", "create_run_config", "modify_run_config",
"evaluate_expression",
"debug_step_over", "debug_step_into", "debug_step_out",
"debug_resume", "debug_pause", "debug_run_to_cursor",

// HIGH (default) covers:
// start_debug_session, debug_stop, delete_run_config
```

---

## ToolCategoryRegistry Integration

### New category: "Runtime & Debug"

```kotlin
ToolCategory(
    id = "runtime_debug",
    displayName = "Runtime & Debug",
    color = "#E91E63",  // pink — distinct from existing categories
    badgePrefix = "DBG",
    description = "Access run output, test results, breakpoints, interactive debugging, and run configuration management",
    alwaysActive = false  // activated by keyword detection
)
```

### DynamicToolSelector keyword triggers

```kotlin
// New keyword triggers:
"debug" → all runtime + debug + config tools
"breakpoint" → debug tools
"test result" / "test output" / "test fail" → runtime tools (get_test_results, get_run_output)
"run config" / "run configuration" → config tools
"log" / "output" / "console" → get_run_output, get_running_processes
"step" / "stepping" / "step through" → debug tools
"variable" / "inspect" / "evaluate" → debug inspection tools
```

---

## Token Budget Considerations

### Tool definitions overhead

23 new tools at ~150 tokens each = ~3,450 tokens for tool definitions when the category is active. This is comparable to the existing Bitbucket category (24 tools, ~3,600 tokens).

The tools are NOT always active — they're activated by keyword detection (`DynamicToolSelector`) or explicit `request_tools` call, so they don't consume budget in non-debugging conversations.

### Tool output sizes

| Tool | Typical output | Max output |
|---|---|---|
| `get_test_results` | 500-1500 tokens | 3000 tokens (truncation strategy below) |
| `get_run_output` | 200-800 tokens | 2000 tokens (200 lines cap) |
| `get_variables` | 300-1000 tokens | 3000 tokens (depth + char cap) |
| `get_stack_frames` | 200-500 tokens | 1000 tokens (20 frames cap) |
| `evaluate_expression` | 50-200 tokens | 500 tokens |
| Step tools (with auto-variables) | 300-800 tokens | 2000 tokens |

A typical debug session (Pattern 1: strategic breakpoint) consumes ~4000-6000 tokens of tool output across 4-6 iterations. Well within budget.

### `get_test_results` truncation strategy

When raw output exceeds 3000 tokens (common with 50+ test failures):
1. Failed/error tests listed first, with full assertion messages
2. Stack traces capped at 5 frames per test (framework frames stripped)
3. Test stdout (`Output:`) capped at 3 lines per test
4. Passed tests shown as summary count only (e.g., `42 passed — omitted for brevity`)
5. Skipped tests shown as summary count
6. Truncation footer: `"[Truncated] Showing 15 of 47 failed tests. Use status_filter='FAILED' for full details of specific failures."`

### Session cleanup (orphaned debug session protection)

`AgentDebugController` implements `Disposable` and is tied to the `ConversationSession` lifecycle. When the conversation ends (user closes chat, agent hits iteration limit, user cancels):
1. `stopAllSessions()` — stops any running debug sessions
2. `removeAgentBreakpoints()` — removes breakpoints added by agent tools (tracked internally)
3. Agent-created run configs (`[Agent]` prefix) are NOT auto-deleted — they persist for user reference

### Spring Boot plugin dependency (N4)

`create_run_config` with `type="spring_boot"` requires the Spring Boot plugin. Implementation checks for `SpringBootApplicationConfigurationType` class availability via reflection. If absent, returns:
```
Error: Spring Boot run configuration requires the Spring Boot plugin.
Alternative: Use type="application" with main_class and vm_options="-Dspring.profiles.active=dev"
```

---

## Testing Strategy

### Unit tests (MockK-based)

| Test class | Coverage |
|---|---|
| `AgentDebugControllerTest` | Session tracking, waitForPause with mock listener callbacks, variable resolution, cleanup |
| `AddBreakpointToolTest` | Valid/invalid file, conditional breakpoint, log breakpoint, temporary flag |
| `StartDebugSessionToolTest` | Config not found, successful launch, wait_for_pause timeout |
| `GetVariablesToolTest` | Depth 0/1/2, token cap truncation, specific variable_name |
| `EvaluateExpressionToolTest` | Valid expression, error expression, side-effect warning |
| `GetTestResultsToolTest` | Structured output parsing, status filter, empty results |
| `GetRunOutputToolTest` | Line cap, regex filter, no active session |
| `CreateRunConfigToolTest` | Each config type, class validation, [Agent] prefix |
| `DeleteRunConfigToolTest` | Agent prefix guard, non-agent rejection |
| `RunTestsToolTest` | Native runner path, fallback to shell, structured results |

### Integration tests (runIde sandbox)

Manual verification in `runIde`:
1. Create a simple Spring Boot test project
2. Agent: "run UserServiceTest and show me the results" → structured test output
3. Agent: "debug the failing test — set a breakpoint at line 78" → breakpoint + debug session
4. Agent: "what are the variable values?" → variable inspection
5. Agent: "evaluate `request.email`" → expression evaluation
6. Agent: "step over" → next line with updated variables
7. Agent: "stop debugging" → cleanup

---

## Implementation Order

```
Phase 1 — Foundation (no dependencies):
  1. AgentDebugController (shared service)
  2. Runtime tools (get_run_configurations, get_running_processes, get_run_output, get_test_results)
  3. Config tools (create_run_config, modify_run_config, delete_run_config)

Phase 2 — Debugging (depends on Phase 1):
  4. Breakpoint tools (add_breakpoint, remove_breakpoint, list_breakpoints)
  5. Session control (start_debug_session, debug_stop, debug_resume, debug_pause)
  6. Step tools (debug_step_over, debug_step_into, debug_step_out, debug_run_to_cursor)
  7. Inspection tools (get_debug_state, get_stack_frames, get_variables, evaluate_expression)

Phase 3 — Integration (depends on Phases 1-2):
  8. run_tests upgrade (native IntelliJ test runner)
  9. ApprovalGate + ToolCategoryRegistry + DynamicToolSelector updates
  10. systematic-debugging skill update
  11. interactive-debugging skill creation
  12. PromptAssembler — add debugging awareness to system prompt
```

---

## Files Summary

### New files (25)

| File | Purpose |
|---|---|
| `tools/debug/AgentDebugController.kt` | Shared XDebugger coroutine wrapper |
| `tools/debug/AddBreakpointTool.kt` | `add_breakpoint` |
| `tools/debug/RemoveBreakpointTool.kt` | `remove_breakpoint` |
| `tools/debug/ListBreakpointsTool.kt` | `list_breakpoints` |
| `tools/debug/StartDebugSessionTool.kt` | `start_debug_session` |
| `tools/debug/GetDebugStateTool.kt` | `get_debug_state` |
| `tools/debug/DebugStepOverTool.kt` | `debug_step_over` |
| `tools/debug/DebugStepIntoTool.kt` | `debug_step_into` |
| `tools/debug/DebugStepOutTool.kt` | `debug_step_out` |
| `tools/debug/DebugResumeTool.kt` | `debug_resume` |
| `tools/debug/DebugRunToCursorTool.kt` | `debug_run_to_cursor` |
| `tools/debug/DebugPauseTool.kt` | `debug_pause` |
| `tools/debug/DebugStopTool.kt` | `debug_stop` |
| `tools/debug/EvaluateExpressionTool.kt` | `evaluate_expression` |
| `tools/debug/GetStackFramesTool.kt` | `get_stack_frames` |
| `tools/debug/GetVariablesTool.kt` | `get_variables` |
| `tools/runtime/GetTestResultsTool.kt` | `get_test_results` |
| `tools/runtime/GetRunOutputTool.kt` | `get_run_output` |
| `tools/runtime/GetRunningProcessesTool.kt` | `get_running_processes` |
| `tools/runtime/GetRunConfigurationsTool.kt` | `get_run_configurations` |
| `tools/config/CreateRunConfigTool.kt` | `create_run_config` |
| `tools/config/ModifyRunConfigTool.kt` | `modify_run_config` |
| `tools/config/DeleteRunConfigTool.kt` | `delete_run_config` |
| `resources/skills/interactive-debugging/SKILL.md` | New debugging skill |

### Modified files (6)

| File | Change |
|---|---|
| `tools/ide/RunTestsTool.kt` | Upgrade to native IntelliJ test runner with fallback |
| `runtime/ApprovalGate.kt` | Add 22 new tools to risk level maps |
| `tools/ToolCategoryRegistry.kt` | Add "Runtime & Debug" category |
| `tools/DynamicToolSelector.kt` | Add keyword triggers for debug/test/log |
| `orchestrator/PromptAssembler.kt` | Add debugging awareness hints |
| `resources/skills/systematic-debugging/SKILL.md` | Add runtime tools, escalation decision, update references |

### New test files (~10)

| File | Methods |
|---|---|
| `AgentDebugControllerTest.kt` | 6 (session tracking, waitForPause, variables, cleanup) |
| `AddBreakpointToolTest.kt` | 4 (valid, invalid, conditional, log) |
| `StartDebugSessionToolTest.kt` | 3 (not found, launch, wait timeout) |
| `GetVariablesToolTest.kt` | 4 (depth levels, cap, specific variable) |
| `EvaluateExpressionToolTest.kt` | 3 (valid, error, type) |
| `GetTestResultsToolTest.kt` | 3 (structured, filter, empty) |
| `GetRunOutputToolTest.kt` | 3 (cap, filter, no session) |
| `CreateRunConfigToolTest.kt` | 4 (types, validation, prefix) |
| `DeleteRunConfigToolTest.kt` | 2 (agent prefix, guard) |
| `RunTestsToolUpgradeTest.kt` | 3 (native, fallback, structured results) |
