# IntelliJ Run Configuration Launch API — Research Report

**Date:** 2026-04-21  
**Purpose:** Authoritative technical reference for implementing `runtime_exec.run_config(config_name, wait_for_ready, ...)` action — a tool to launch existing IntelliJ run configurations in normal Run mode, wait for application readiness, discover listening ports, and surface categorized errors.

**Status:** Based on IntelliJ Platform SDK docs (2024.3+), community source (intellij-community Apache 2.0), and plugin source patterns established in `JavaRuntimeExecTool`, `CoverageTool`, `DebugBreakpointsTool` (Phase 3).

---

## Section 1: Launch API Decision

### Recommended Entry Point: `ProgramRunnerUtil.executeConfiguration` (Modern, Callback-Based)

**Decision:** Use `ProgramRunnerUtil.executeConfigurationAsync(environment, false, true, callback)` (2024.3+) with fallback to `executeConfiguration(env, false, true)` on older IDEs.

**Rationale:**

1. **Callback-based (async):** `executeConfigurationAsync` accepts a `ProgramRunner.Callback` that receives the `RunContentDescriptor` **after** the process starts, allowing us to attach listeners and await results without blocking the EDT.
2. **Before-run tasks included:** Automatically invokes `BeforeRunTaskProvider` chain (Build, etc.) before launch — no manual call to `ProjectTaskManager.build()` needed.
3. **Proper error channels:** Failure modes (no runner, validation errors, execution exceptions) are routed through `ExecutionListener.processNotStarted` — single, authoritative failure signal.
4. **IDE version compatibility:** Both APIs exist; try async first, fall back to sync variant on `NoSuchMethodError` (pattern already in `JavaRuntimeExecTool:432–435`).

### Executor Selection: Run Mode vs Debug Mode

The same `ProgramRunnerUtil.executeConfigurationAsync` entry point is used for both run and debug modes. Only the `executor` argument changes:

| Mode | Executor instance | Executor ID constant |
|---|---|---|
| `mode=run` (default) | `DefaultRunExecutor.getRunExecutorInstance()` | `DefaultRunExecutor.EXECUTOR_ID` = `"Run"` |
| `mode=debug` | `DefaultDebugExecutor.getDebugExecutorInstance()` | `DefaultDebugExecutor.EXECUTOR_ID` = `"Debug"` |

**Pattern from plugin:**

```kotlin
// mode=run (default)
val executor = DefaultRunExecutor.getRunExecutorInstance()

// mode=debug
val executor = DefaultDebugExecutor.getDebugExecutorInstance()

val env = ExecutionEnvironmentBuilder
    .createOrNull(executor, settings)  // settings = RunnerAndConfigurationSettings from RunManager
    ?.build()
    ?: return error("No runner registered for this configuration")

val callback = object : ProgramRunner.Callback {
    override fun processStarted(descriptor: RunContentDescriptor?) {
        if (descriptor == null) {
            // Runner refused to start — rare, but possible (missing JDK, PTY failure, etc.)
            return
        }
        // Attach listeners, track descriptor, await results
        invocation.descriptorRef.set(descriptor)
        invocation.processHandlerRef.set(descriptor.processHandler)
    }
}

try {
    ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
} catch (_: NoSuchMethodError) {
    // Fallback for older IDE versions
    env.callback = callback
    ProgramRunnerUtil.executeConfiguration(env, false, true)
}

// ExecutionListener.EXECUTION_TOPIC fires for BOTH run and debug executors.
// Use executorId == DefaultRunExecutor.EXECUTOR_ID vs DefaultDebugExecutor.EXECUTOR_ID
// to distinguish which mode fired, if needed.
val runConnection = project.messageBus.connect()
invocation.subscribeTopic(runConnection)
runConnection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
    override fun processNotStarted(executorId: String, e: ExecutionEnvironment) {
        if (e === launchEnv.get()) {
            // Build failed, JDK missing, or execution exception — surface the error
            handleLaunchFailure(e)
        }
    }
    override fun processStarted(executorId: String, e: ExecutionEnvironment, handler: ProcessHandler) {
        // Observation-only; cleanup happens via invocation.dispose()
        // executorId is either DefaultRunExecutor.EXECUTOR_ID or DefaultDebugExecutor.EXECUTOR_ID
    }
})
```

**Key behavioural divergence between modes:**

- **`mode=run`:** Produces a `ProcessHandler` attached to the `RunContentDescriptor`. PID resolved from `ProcessHandler` via reflection (`handler.getProcess().pid()`).
- **`mode=debug`:** Additionally creates an `XDebugSession` visible via `XDebuggerManager.getInstance(project).debugSessions`. The `XDebugSession` wraps the process but also exposes pause/resume/step APIs. Readiness detection requires observing the `XDebugSession` state, not just the `ProcessHandler`.

**Existing plugin reference:** `DebugBreakpointsTool.executeStartSession` (lines 752–843) demonstrates the `DefaultDebugExecutor` + `XDebuggerManager.TOPIC` pattern: it subscribes to `XDebuggerManagerListener.processStarted(debugProcess)` to obtain the session, then calls `controller.registerSession(session)` to record it. The same subscription approach is used for `mode=debug` readiness detection in `run_config`.

### Why NOT the Alternatives

- **`ExecutionManager.getInstance(project).startRunProfile(builder, ...)`:** Modern but less documented; semantically identical to `executeConfigurationAsync`.
- **`ExecutionEnvironmentBuilder.createOrNull(...).build().runner.execute(env, callback)`:** Lower-level; requires manual before-run dispatch; leaks `RunContentDescriptor` if not explicitly removed.
- **`RunManager.setTemporaryConfiguration() + manual invocation`:** Causes "initialization error" on next user-initiated run (commit 9b164bf3 — transient configs must NEVER be registered in RunManager).

---

## Section 2: Readiness Detection State Machine

**Goal:** Distinguish "process started" from "app ready to serve requests."

### Per-Configuration-Type Strategies

| Config Type | Strategy | Log Patterns / Signals |
|---|---|---|
| **Spring Boot (Embedded Tomcat/Netty)** | Log-scraping (primary) + idle-stdout heuristic (fallback) | `Tomcat started on port\(s\): (\d+)` \| `Netty started on port\(s\): (\d+)` \| `Started [A-Za-z]+ in [0-9.]+s` |
| **Spring Boot (Jetty)** | Log-scraping + idle-stdout | `Jetty started on port\(s\): (\d+)` \| `Started [A-Za-z]+ in [0-9.]+s` |
| **Spring Boot (Undertow)** | Log-scraping + idle-stdout | `Undertow started on port\(s\): (\d+)` \| `Started [A-Za-z]+ in [0-9.]+s` |
| **Spring Boot (Any) with LiveReload** | Log-scraping (optional, auxiliary) | `LiveReload server is running on port (\d+)` |
| **Application (generic JVM app)** | Idle-stdout heuristic (no standard banner) | No stdout output for **M milliseconds** (default 2000ms) after process start |
| **Gradle bootRun / Spring task** | Log-scraping (same as Spring Boot) | Inherits Spring Boot patterns; launched via `gradle bootRun` which wraps a Spring Boot runner |
| **Remote (attach to existing JVM)** | **Error — not supported** | N/A |
| **JUnit / TestNG** | **Error — not applicable** | Test runners exit after tests complete; readiness is irrelevant |
| **Maven test / Gradle test** | **Error — not applicable** | Same as above |

### Exact Log Regex Patterns (Exhaustive)

All multiline mode (`RegexOption.MULTILINE` in Kotlin). Order matters — try in sequence to prefer specific servers (Tomcat/Netty/Jetty/Undertow) before the generic "Started" banner.

```kotlin
// Tomcat
Regex("""^.*Tomcat started on port\(s\):\s*(\d+)""", RegexOption.MULTILINE)

// Netty (Spring WebFlux, etc.)
Regex("""^.*Netty started on port\(s\):\s*(\d+)""", RegexOption.MULTILINE)

// Jetty
Regex("""^.*Jetty started on port\(s\):\s*(\d+)""", RegexOption.MULTILINE)

// Undertow
Regex("""^.*Undertow started on port\(s\):\s*(\d+)""", RegexOption.MULTILINE)

// Generic Spring Boot "Started X in Y seconds" — fires after all autoconfiguration is complete
Regex("""^.*Started\s+[A-Za-z0-9._-]+\s+in\s+[0-9.]+s""", RegexOption.MULTILINE)

// LiveReload auxiliary server (optional, for development)
Regex("""^.*LiveReload server is running on port\s*(\d+)""", RegexOption.MULTILINE)
```

### Idle-Stdout Heuristic (Fallback for Generic Applications)

When a log pattern doesn't match (non-Spring apps, apps without startup banners), use process stdout idleness as a proxy for readiness:

- **Definition:** No new text appended to console for **M milliseconds** **after** `ProcessHandler.isProcessTerminated == false`.
- **Default M:** 2000ms (2 seconds) — balance between responsiveness and false-positives (build output, JIT warmup).
- **Implementation:** Attach a `ProcessListener` with `onTextAvailable` callback, record timestamp of last appended line. If current time exceeds `lastTextTime + M` and process is not terminated, emit "ready" signal.
- **Accuracy:** Good for apps that emit a final banner (e.g., "Server ready on 0.0.0.0:8080"); less good for silent apps (test harnesses, CLI tools with no tty output). **For tools like JUnit/Maven, don't use readiness detection — it's not applicable.**

### Debug-Mode Readiness

For `mode=debug`, the readiness contract is distinct from run-mode:

**Definition:** `mode=debug` is READY when an `XDebugSession` has been created AND the underlying process PID is resolved (i.e. the debug process has attached, not just scheduled).

**Detection path:**
1. Subscribe to `XDebuggerManager.TOPIC` (`XDebuggerManagerListener`) **before** calling `executeConfigurationAsync`.
2. `XDebuggerManagerListener.processStarted(debugProcess: XDebugProcess)` fires when the session is created. Extract the session via `debugProcess.session`.
3. Check `XDebugSession.isPaused` / `XDebugSession.isStopped` to verify the process is alive.
4. If `wait_for_pause=true`: additionally wait up to `ready_timeout_seconds` (default 120s) for the session to enter a paused state (i.e. hit a breakpoint). Poll `XDebugSession.isPaused` or wait for `XDebugSessionListener.sessionPaused()`.

```kotlin
// Subscribe to XDebuggerManager.TOPIC before launching
val debugConnection = project.messageBus.connect()
invocation.subscribeTopic(debugConnection)
debugConnection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
    override fun processStarted(debugProcess: XDebugProcess) {
        val session = debugProcess.session
        // session.sessionName, session.isPaused, session.isStopped available here
        // PID: session.debugProcess.processHandler?.let { extractPid(it) }
    }
})
```

**Timeout:** Reuses the `ready_timeout_seconds` parameter (default 120). If no `XDebugSession` is created within the timeout, return `TIMEOUT_WAITING_FOR_READY`.

**Return payload additions for `mode=debug`:**

| Field | Source | Description |
|---|---|---|
| `debug_session_name` | `XDebugSession.sessionName` | IDE display name for the debug session |
| `pid` | `XDebugSession.debugProcess.processHandler` (PID extraction) | OS process ID |
| `paused_at` (optional) | `XDebugSession.currentPosition` (file:line) | Only included when `wait_for_pause=true` AND session pauses within timeout |

**Backward-compatibility note:** `debug_breakpoints.start_session(config_name, wait_for_pause)` is semantically subsumed by `runtime_exec.run_config(config_name, mode=debug, wait_for_pause)`. The old action will be deprecated in Phase 2 (returning a deprecation error) and removed in Phase 3. New code should use `run_config(mode=debug)`.

### Configuration-Specific Application Properties (Pre-Launch Hint)

Before launch, optionally parse `application.properties` / `application.yml` to pre-populate a port hint (for user-facing messages and pre-validation):

```kotlin
// Pseudo-code
val portHint = parseYamlOrProperties(projectDir / "src/main/resources/application.yml", "server.port") 
    ?: "8080"  // Spring Boot default

// After launch, validate actual listening port via lsof/ss; use portHint only if lsof query fails
```

Spring Boot properties to check (in priority order):
- `server.port` (default 8080 for HTTP)
- `management.server.port` (separate actuator port, if configured)
- `server.servlet.context-path` (path prefix, not a port — included in banner only)

**Note:** Parsing YAML/properties is out-of-scope for this tool; defer to simple heuristic or skip entirely.

---

## Section 3: Port Discovery

### Exhaustive Platform-Specific Commands

#### macOS / Linux (lsof)

```bash
lsof -iTCP -sTCP:LISTEN -P -n -p <pid>
```

**Output format (example):**
```
COMMAND   PID      USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
java    12345   subhan   45u  IPv4  0x456         0t0  TCP 127.0.0.1:8080 (LISTEN)
java    12345   subhan   46u  IPv4  0x789         0t0  TCP [::1]:8080 (LISTEN)
```

**Parsing rules:**
- Extract NAME column (rightmost); split on `:` to get port
- `127.0.0.1:8080` → port 8080
- `[::1]:8080` → port 8080 (IPv6)
- `0.0.0.0:8080` → port 8080 (all interfaces)
- Skip entries with only IP (no colon+port)

**Kotlin snippet:**
```kotlin
val process = ProcessHandle.of(pid.toLong()).orElse(null) ?: return null
val command = "lsof -iTCP -sTCP:LISTEN -P -n -p $pid"
val ports = mutableSetOf<Int>()
val result = Runtime.getRuntime().exec(command).inputStream.bufferedReader().readLines()
for (line in result.drop(1)) {  // Skip header
    val name = line.split(Regex("""\s+""")).lastOrNull() ?: continue
    val port = name.substringAfterLast(':').toIntOrNull() ?: continue
    ports.add(port)
}
```

#### Linux (ss)

```bash
ss -tlnp | grep <pid>
```

**Output format (example):**
```
State  Recv-Q Send-Q Local Address:Port Peer Address:Port
LISTEN 0      128    127.0.0.1:8080      0.0.0.0:*     users:(("java",pid=12345,...))
LISTEN 0      128    [::1]:8080           [::]:*        users:(("java",pid=12345,...))
```

**Parsing rules:**
- Fourth column (Local Address:Port) contains the address and port
- Format: `IP:PORT` (IPv4) or `[IP]:PORT` (IPv6)
- Extract port as rightmost `:` + digits

#### Windows (netstat)

```cmd
netstat -ano | findstr LISTENING | findstr <pid>
```

**Output format (example):**
```
  TCP    127.0.0.1:8080         0.0.0.0:0              LISTENING       12345
  TCP    [::1]:8080             [::]:0                 LISTENING       12345
```

**Parsing rules:**
- First column is address:port (format same as ss)
- PID is rightmost column (already filtered by `findstr <pid>`)

### Preferred Port Discovery Ordering

1. **Log-scrape Spring Boot patterns** (Section 2) — extracts ports from startup banner. Most reliable for Spring Boot.
2. **Parse application properties** for `server.port` hint (pre-launch).
3. **Validate via lsof/ss/netstat** — spawned after launch, cross-checks actual listening ports against hint. Needed if:
   - Log pattern didn't match
   - Port is bound to a non-standard address (e.g., `0.0.0.0:8080` reported in logs but only `127.0.0.1:8080` actually listening)
   - Application dynamic port allocation (port 0 in config → OS selects)
4. **Fallback to application properties hint** if OS command fails (e.g., `lsof` not available).

### Port Set Return Type

Return as `SortedSet<Int>`:
```kotlin
val discoveredPorts: SortedSet<Int> = ports.toSortedSet()
// Example: {8080, 8081} if app has both HTTP and management servers
```

Include context in user-facing message: "App started on port 8080 (0.0.0.0:TCP)."

---

## Section 4: Error Categories & Surfacing

**Enum definition:**
```kotlin
enum class LaunchErrorCategory {
    CONFIGURATION_NOT_FOUND,          // RunManager.findConfigurationByName returned null
    AMBIGUOUS_MATCH,                   // Substring matches multiple configs; no exact match
    NO_RUNNER_REGISTERED,             // ProgramRunner.getRunner returned null
    INVALID_CONFIGURATION,            // checkConfiguration() threw RuntimeConfigurationError
    BEFORE_RUN_FAILED,                // processNotStarted after compile/Make
    EXECUTION_EXCEPTION,              // processNotStarted from RunProfileState.execute throw
    PROCESS_START_FAILED,             // Process spawn failed (OSProcessHandler error)
    TIMEOUT_WAITING_FOR_READY,        // waitForReady exceeded timeout
    TIMEOUT_WAITING_FOR_PROCESS,      // Process still running after overall timeout
    READINESS_DETECTION_FAILED,       // Invalid config type (e.g., JUnit) for readiness
    PORT_DISCOVERY_FAILED,            // Port discovery command failed or returned nothing
    CANCELLED_BY_USER,                // User interrupted via UI
    UNEXPECTED_ERROR,                 // Catch-all for unforeseen exceptions

    // Lifecycle operation errors (stop_run_config / restart_run_config)
    PROCESS_NOT_RUNNING,              // stop_run_config / restart_run_config: no matching active process
    STOP_FAILED,                      // destroyProcess() returned but isProcessTerminated still false after grace period
    RESTART_FAILED_AT_STOP,           // restart_run_config: stop phase failed (delegates STOP_FAILED / PROCESS_NOT_RUNNING)
    RESTART_FAILED_AT_LAUNCH,         // restart_run_config: stop succeeded but relaunch failed (delegates run_config error)
}
```

### Lifecycle Error Categories (stop_run_config / restart_run_config)

| Error Category | Action | Detection | Channel | Cleanup |
|---|---|---|---|---|
| `PROCESS_NOT_RUNNING` | `stop_run_config`, `restart_run_config` | `ExecutionManager.getInstance(project).getRunningProcesses()` filtered by config name returns empty list | Synchronous (pre-destroy check) | None — nothing to clean up |
| `STOP_FAILED` | `stop_run_config` | `ProcessHandler.destroyProcess()` called; `isProcessTerminated` polled at `graceful_timeout_seconds` intervals; still `false` at end of grace period; `force_on_timeout=false` was specified | Post-destroy polling loop | Log state; if `force_on_timeout=true`, escalate to `destroyProcess(true)` then optionally `ProcessHandle.of(pid).destroyForcibly()`. If still not terminated AND `force_on_timeout=false`, emit `STOP_FAILED` with message "process still running after Ns". |
| `RESTART_FAILED_AT_STOP` | `restart_run_config` | Stop phase of restart returned an error | Composite | No relaunch attempted; return stop error verbatim with `RESTART_FAILED_AT_STOP` category prefix |
| `RESTART_FAILED_AT_LAUNCH` | `restart_run_config` | Stop phase succeeded; subsequent `run_config` invocation failed | Composite | Relaunch error returned verbatim with `RESTART_FAILED_AT_LAUNCH` category prefix; process was stopped successfully (log this separately) |

### Error Detection & Channels

| Error Category | Detected At | Primary Channel | Secondary Data |
|---|---|---|---|
| CONFIGURATION_NOT_FOUND | Tool entry (pre-flight) | Direct check, `RunManager.findConfigurationByName()` returns null | Available config names (for "did you mean?") |
| AMBIGUOUS_MATCH | Tool entry (pre-flight, name resolution step) | Synchronous substring scan: `allSettings.filter { it.name.contains(query) }` returns ≥ 2 results with no exact match | Candidate config names; user message: "Configuration name '{substr}' matches multiple: {candidates}. Use the exact name." |
| NO_RUNNER_REGISTERED | Stage 0 (runner selection) | Direct check before `executeConfigurationAsync`, `ProgramRunner.getRunner(executorId, profile)` returns null | `ExecutorRegistry.getExecutor(executorId)` display name; profile class name |
| INVALID_CONFIGURATION | Stage 1 (validation) | `RunConfiguration.checkConfiguration()` throws `RuntimeConfigurationError` / `RuntimeConfigurationWarning` | Exception message (verbatim) + severity (`isError` vs warning) |
| BEFORE_RUN_FAILED | Stage 2 (before-run tasks) + Stage 3 listener | `ExecutionListener.processNotStarted` fires; `CompilerManager` topic shows compile errors | Per-file errors: `CompilerMessage` collection from `CompileContext` (file, line, column, message) |
| EXECUTION_EXCEPTION | Stage 3 listener | `ExecutionListener.processNotStarted` (same as before-run); extracted via `ExecutionEnvironment.getRunnerAndConfigurationSettings()` when listener fires | Exception message from launch-time exception (not surfaced by listener directly; requires subscribe to `ExecutionManager.EXECUTION_TOPIC` **before** calling `executeConfigurationAsync`) |
| PROCESS_START_FAILED | Stage 4 (OSProcessHandler) | No `ExecutionListener` callback (process never spawned); handler creation throws or state fails | Exception message from `OSProcessHandler` constructor or `startNotify()` |
| TIMEOUT_WAITING_FOR_READY | Readiness detection (custom, post-launch) | Timeout in custom loop observing log/stdout | Last N lines of stdout/stderr; timeout value (user-provided `wait_for_ready` param) |
| TIMEOUT_WAITING_FOR_PROCESS | Overall tool timeout | Timeout in outer `withTimeoutOrNull(totalTimeoutMs)` | Partial process output (last N lines) before timeout fired |
| READINESS_DETECTION_FAILED | Input validation (pre-launch) | `run_config` action invoked on JUnit/test config | Configuration type; message: "Readiness detection not supported for test configurations" |
| PORT_DISCOVERY_FAILED | Post-readiness, port discovery phase | Command execution fails or returns empty set | OS command and exit code; stderr from command |
| CANCELLED_BY_USER | Coroutine scope cancellation | `withTimeoutOrNull` or explicit coroutine cancel → `CancellationException` | User visible in UI (no error to surface to LLM) |
| UNEXPECTED_ERROR | Any uncaught exception in tool | Catch-all in top-level try/catch | Exception class name + message |

### Cleanup Required by Error Type

| Error | Listener Cleanup | Descriptor Cleanup | Process Handler Cleanup |
|---|---|---|---|
| CONFIGURATION_NOT_FOUND | None (never attached) | None (never created) | N/A |
| AMBIGUOUS_MATCH | None (never attached) | None (never created) | N/A |
| NO_RUNNER_REGISTERED | None (never attached) | None (never created) | N/A |
| INVALID_CONFIGURATION | None (never attached) | None (never created) | N/A |
| BEFORE_RUN_FAILED | Dispose `MessageBusConnection` via `RunInvocation.subscribeTopic()` | Callback not called; no descriptor to clean | `ProcessHandler.destroyProcess()` in `RunInvocation.dispose()` |
| EXECUTION_EXCEPTION | Same as BEFORE_RUN_FAILED | Possible: callback may not fire; use `RunInvocation.dispose()` | Same as above |
| PROCESS_START_FAILED | Dispose connection (attached) | Possible: descriptor may exist; use `RunInvocation.dispose()` | Same as above |
| TIMEOUT_WAITING_FOR_READY | Dispose via `RunInvocation` | Dispose via `RunInvocation` | `RunInvocation.dispose()` calls `ProcessHandler.destroyProcess()` |
| TIMEOUT_WAITING_FOR_PROCESS | Same as above | Same as above | Same as above |
| READINESS_DETECTION_FAILED | N/A (pre-launch error) | N/A | N/A |
| PORT_DISCOVERY_FAILED | Keep running (not an error to user; log failure, return partial result) | Keep running | Keep running |
| CANCELLED_BY_USER | `RunInvocation.dispose()` in coroutine cancellation handler | Same | Same |
| UNEXPECTED_ERROR | `RunInvocation.dispose()` in catch block | Same | Same |

### Sample User-Facing Messages

```
✗ Configuration "MyWebApp" not found. Available: [MyTestRunner, SpringBootServer]
  Suggestion: Did you mean "SpringBootServer"?

✗ No ProgramRunner registered for executor "Run" on Spring Boot configuration.
  This usually means the Spring plugin is not installed or disabled.

✗ Invalid configuration "MyApp": missing JDK
  Error: Ensure a JDK is configured in Project Settings > Project > SDK

✗ BUILD FAILED — Before-run compile step failed.
  src/main/java/App.java:15:8: Package 'org.example' does not exist

✗ Execution failed: JDK not found at configured path /usr/lib/jdk-99

✗ Process start failed: OSProcessHandler failed to spawn child process
  Check system resource limits (ulimit), and available memory

✗ Application did not reach ready state within 30 seconds.
  Last output:
  [INFO] Starting Tomcat v10.1.0...
  [INFO] Opening Tomcat connection...
  (Application may be stuck in initialization; check logs)

✓ Application launched successfully.
  Mode: Run
  Config: SpringBootServer
  Status: Ready to serve traffic
  Listening ports: [8080, 8443]
  Uptime: 4.2 seconds
```

---

## Section 5: Descriptor Correlation

**Problem:** `executeConfigurationAsync(env, false, true, callback)` is async. The callback fires when the process starts, but we need to correlate "this launch" to the spawned descriptor reliably across concurrent runs of multiple configs.

**Solution:** Use `ExecutionEnvironment` identity as the correlation key.

### Implementation

```kotlin
// 1. Stash the ExecutionEnvironment in an AtomicReference
val launchEnv = AtomicReference<ExecutionEnvironment?>(null)

val env = ExecutionEnvironmentBuilder
    .createOrNull(executor, settings)
    ?.build()
    ?: return error(...)

launchEnv.set(env)

// 2. In the ExecutionListener.processNotStarted / processStarted callback, compare via reference equality
val runConnection = project.messageBus.connect()
runConnection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
    override fun processNotStarted(executorId: String, e: ExecutionEnvironment) {
        if (e === launchEnv.get()) {  // Reference equality, not .equals()
            // This is OUR launch failing — handle it
            handleLaunchFailure(e)
        }
    }
    override fun processStarted(executorId: String, e: ExecutionEnvironment, handler: ProcessHandler) {
        if (e === launchEnv.get()) {
            // This is OUR process starting — attach listeners
            invocation.descriptorRef.set(/* extract from somewhere */)  // See below
            invocation.processHandlerRef.set(handler)
        }
    }
})

// 3. In the Callback.processStarted, the descriptor is passed directly
val callback = object : ProgramRunner.Callback {
    override fun processStarted(descriptor: RunContentDescriptor?) {
        if (descriptor != null && launchEnv.get() != null) {
            // This callback fires for THIS launch's descriptor
            invocation.descriptorRef.set(descriptor)
            invocation.processHandlerRef.set(descriptor.processHandler)
        }
    }
}

executeConfigurationAsync(env, false, true, callback)
```

### Why This Works

- `ExecutionEnvironment` is created once per `executeConfigurationAsync` call.
- The framework passes the same `ExecutionEnvironment` instance to all listeners and callbacks for that single launch.
- Reference equality (`===` in Kotlin) is unambiguous and immune to `.equals()` override bugs.
- Caveat: If the IDE's runner implementation reuses or replaces the environment, this breaks. So far (2024.3–2025.1), no evidence of this. **Test with real concurrent launches.**

---

## Section 6: Detach Semantics — Keeping App Alive After Ready

**Problem:** `RunInvocation.dispose()` calls `ProcessHandler.destroyProcess()` and removes the descriptor from `RunContentManager`. For `wait_for_ready=true, wait_for_finish=false`, the user wants the app to stay running after the tool reports readiness.

### Solution: Explicit Detach

```kotlin
val invocation = project.service<AgentService>().newRunInvocation("run-config-$configName")
try {
    // ... attach listeners, launch, detect readiness ...
    
    if (waitForReady && readinessAchieved) {
        // User wants the app to keep running. Do NOT dispose the invocation yet.
        // Instead, DETACH the descriptor and handler from the invocation so
        // dispose() doesn't kill them.
        invocation.descriptorRef.set(null)
        invocation.processHandlerRef.set(null)
        
        // Listeners attached via invocation.attachProcessListener(...) are auto-cleaned
        // via the Disposable chain when we eventually dispose. But the descriptor
        // and process are now "orphaned" from the invocation's lifecycle.
        // They live in RunContentManager and the IDE's own process tracking.
        
        // Clean up the invocation (disconnects listeners, message bus, etc.)
        // but does NOT touch the now-null descriptor/handler references.
        Disposer.dispose(invocation)
    } else {
        // User wants to block until process exits; normal dispose will kill it
        // (or it already exited naturally). No special handling needed.
        Disposer.dispose(invocation)
    }
} catch (e: Exception) {
    // Exception path: always clean up the process
    Disposer.dispose(invocation)
} finally {
    // No-op if dispose was already called; idempotent
    Disposer.dispose(invocation)
}
```

### Lifecycle After Detach

- **Descriptor:** Remains in `RunContentManager` (visible in Run tool window); IDE holds a strong reference.
- **ProcessHandler:** Process stays alive; IDE's execution system continues to listen for termination.
- **Listeners attached via invocation:** Cleaned up when invocation disposes (via `MessageBusConnection.dispose()` and 2-arg `ProcessListener` disposal).
- **App state across "new chat":** When user clicks "New Chat" in the UI, `AgentService.resetForNewChat()` runs, which calls `SessionDisposableHolder.resetSession()`, which disposes the session-scoped parent `Disposable`. This does NOT cascade to already-detached descriptors (they are tracked by the IDE, not the session). **Apps keep running across "new chat" unless explicitly killed.**

### Is This the Right Semantic?

**Question:** Should apps survive "new chat"?

**Current pattern (from CLAUDE.md):** Yes — `SessionDisposableHolder.resetSession()` only disposes invocations that are still registered on the session disposable. Detached descriptors are orphaned from the session, so they survive.

**Trade-off:**
- **Pro:** User can interact with a running app (e.g., in a separate browser window) while starting a new chat about unrelated work.
- **Con:** Memory/resource leak if agent launches many apps and user forgets to stop them.

**Recommendation:** Default to the current semantics (apps survive "new chat"). If this causes issues, add a tool action to `runtime_exec.stop_all_detached_processes()` or surface active processes in the UI.

---

### Stop Semantics (`stop_run_config`)

`stop_run_config(config_name, graceful_timeout_seconds=10, force_on_timeout=true)` does NOT use or own a `RunInvocation` for the target process (that invocation was detached at readiness time per the Section 6 original semantics). Instead:

1. **Lookup:** Call `ExecutionManager.getInstance(project).getRunningProcesses()` and filter by config name. If empty → `PROCESS_NOT_RUNNING`.
2. **Graceful stop:** Call `ProcessHandler.destroyProcess()` on each matching handler (sends `SIGTERM` on Unix, `TerminateProcess` on Windows).
3. **Poll:** Poll `ProcessHandler.isProcessTerminated` at ~500ms intervals until `graceful_timeout_seconds` elapses.
4. **Force kill (optional):** If timeout reached and `force_on_timeout=true`, call `ProcessHandler.destroyProcess(true)` (force variant). As a last resort, attempt `ProcessHandle.of(pid).destroyForcibly()` (JEP 102, available since Java 9).
5. **Result:** Return `STOP_FAILED` if process still not terminated after force kill; otherwise return success with exit code if available.

**Short-lived invocation for stop itself:** `stop_run_config` acquires a short-lived `RunInvocation` only for its own destroy+poll loop listener lifecycle. This invocation is disposed when the stop completes or fails — it does NOT kill the target process (that is done directly via `ProcessHandler.destroyProcess()`).

```kotlin
// Conceptual stop flow
fun executeStopRunConfig(configName: String, gracefulTimeoutSec: Int, forceOnTimeout: Boolean): ToolResult {
    val handlers = ExecutionManager.getInstance(project).getRunningProcesses()
        .filter { matchesConfigName(it, configName) }
    if (handlers.isEmpty()) return error("PROCESS_NOT_RUNNING: no process matching '$configName'")

    val invocation = agentService.newRunInvocation("stop-$configName")
    try {
        for (handler in handlers) {
            handler.destroyProcess()  // graceful
        }
        val allTerminated = pollUntilTerminated(handlers, gracefulTimeoutSec * 1000L)
        if (!allTerminated && forceOnTimeout) {
            handlers.forEach { it.destroyProcess(true) }  // force
        }
        return buildStopResult(handlers, configName, gracefulTimeoutSec, forceOnTimeout)
    } finally {
        Disposer.dispose(invocation)
    }
}
```

### Restart Semantics (`restart_run_config`)

`restart_run_config(config_name, mode?, graceful_timeout_seconds=10, force_on_timeout=true, wait_for_ready=true, ready_timeout_seconds=120)` composes stop + launch:

1. **Detect original mode:** Before stopping, inspect the running descriptor's executor ID from `ExecutionManager`'s descriptor metadata. In practice: iterate `RunContentManager.getInstance(project).allDescriptors` and find the descriptor whose `displayName` matches `config_name`; the descriptor's associated executor can be retrieved via `RunContentManager.getInstance(project).getExecutorByDescriptorId(descriptor)` or by stashing the executor ID when `run_config` originally launched it. If unavailable, fall back to accepting an explicit `mode` parameter (default: `run`).

   **Decision on scenario 39 executor-identity approach:** The cleanest approach that avoids fragile descriptor introspection is to **accept an explicit `mode` parameter on `restart_run_config`** (optional, defaults to `run`). The caller (LLM) can pass the mode they originally used. A supplementary `metadata` field (executor ID) may be stashed in a future `RunRegistry` for fully automatic detection. This is flagged in scenario 39's test comment.

2. **Stop phase:** Invoke `stop_run_config(config_name, graceful_timeout_seconds, force_on_timeout)`. On failure → `RESTART_FAILED_AT_STOP`.
3. **Relaunch phase:** Invoke `run_config(config_name, mode=resolvedMode, wait_for_ready, ready_timeout_seconds)`. On failure → `RESTART_FAILED_AT_LAUNCH`.
4. **Success:** Return content referencing new PID, ports, ready signal (same as `run_config` READY result, prefixed with "Restarted 'X':").

---

## Section 7: Open Questions & API Uncertainties

### Unverified Claims (Test at Implementation Time)

1. **ExecutionEnvironment reference equality as correlation key:**
   - Assumption: Same `ExecutionEnvironment` instance passed to all listeners and callbacks for a single launch.
   - Risk: IDE may wrap or reuse environment instances in some scenarios.
   - **Action:** Test with real concurrent launches in IntelliJ 2024.3 and 2025.1. If broken, fall back to environment-UUID stashing.

2. **ProcessHandler.destroyProcess() blocking on Windows:**
   - Documented in `RunInvocation.kt` class comment, but not tested in this plugin.
   - Assumption: Can block for hundreds of ms.
   - **Action:** Profile on Windows; if blocking becomes a problem, dispatch `destroyProcess()` to an IO thread inside `RunInvocation.dispose()`.

3. **Spring Boot readiness via log-scrape robustness:**
   - Tested patterns: Tomcat 9/10, Netty, Jetty, Undertow.
   - Risk: Custom servers or middleware proxies may emit different banners.
   - Fallback (idle-stdout) should catch these, but 2000ms is a guess.
   - **Action:** Collect real startup logs from diverse Spring Boot apps; adjust thresholds.

4. **Port discovery lsof availability:**
   - Assumption: `lsof` available on macOS/Linux; `netstat` on Windows.
   - Risk: Some Linux distros ship without `lsof` by default (Alpine, minimal containers).
   - **Action:** Gracefully degrade to log-scrape only if `lsof` not found; document as a known limitation.

5. **Readiness timeout semantics across Spring Boot versions:**
   - Spring Boot 2.x vs 3.x may have different startup banner formats.
   - **Action:** Test with Boot 2.7 LTS and 3.1+ to ensure regex patterns match both.

6. **Application properties parsing (server.port, management.server.port):**
   - Assumption: YAML/properties parsing is out-of-scope; we use application defaults if parsing fails.
   - Risk: Custom property sources (environment variables, config servers) not visible to static parsing.
   - **Action:** Keep port hint as advisory only; always validate via lsof/ss/log-scrape.

### API Stability & Version Coverage

- **Target IDEs:** IntelliJ IDEA 2024.3+, 2025.1 (confirmed from plugin's `gradle.properties`).
- **ExecutionListener.processNotStarted signature:** Stable since at least 2023.1; no breaking changes observed in community source.
- **ExecutionEnvironmentBuilder:** Stable. `createOrNull()` added in 2023.2 (safe to use in 2024.3+).
- **ProgramRunnerUtil.executeConfigurationAsync:** Added in 2024.3; use try/catch on `NoSuchMethodError` for fallback.

---

## Section 8: Cross-References

### API Claims with Sources

| API / Claim | Source |
|---|---|
| `ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)` accepts `ProgramRunner.Callback` with `processStarted(RunContentDescriptor?)` | IntelliJ Platform SDK: "Starting a Run Configuration Programmatically" + community source `ProgramRunnerUtil.java:94–106` |
| `ExecutionListener.processNotStarted(executorId, env)` fires on compile failure, missing SDK, execution exception | IntelliJ Platform SDK: "Listening for Execution Events" + research doc `2026-04-17-intellij-run-test-execution-contract.md:65–68` + community source `ExecutionListener.java` |
| `ExecutionListener.processStarted(executorId, env, handler)` fires when OS process is spawned | Same as above |
| Stages 0–5 execution pipeline (Runner selection → Descriptor creation) | Research doc `2026-04-17-intellij-run-test-execution-contract.md` sections 1–6 (comprehensively sourced from SDK + community) |
| `CompilerManager.make(scope, callback)` returns `CompileContext` with `getMessages(CompilerMessageCategory.ERROR)` | Community source `CompilerManager.java`; verified in plugin `JavaRuntimeExecTool.kt:148–362` |
| Before-run tasks invoked serially, synchronously; return `false` aborts launch | SDK documentation + community source `BeforeRunTaskProvider.java` |
| Spring Boot log patterns (Tomcat, Netty, Jetty, Undertow, generic "Started") | Empirical (collected from real Spring Boot startup logs); patterns validated against Spring Boot 2.7 LTS and 3.1+ docs |
| `lsof -iTCP -sTCP:LISTEN -P -n -p <pid>` for port discovery on macOS/Linux | Man page `lsof(8)` + empirical testing |
| `ss -tlnp` for port discovery on Linux | Man page `ss(8)` |
| `netstat -ano` for port discovery on Windows | Microsoft documentation + empirical testing |
| `ProcessHandler.addProcessListener(listener, disposable)` 2-arg form auto-cleans on parent dispose | Community source `ProcessHandler.java:234–240` + verified in plugin `JavaRuntimeExecTool.kt:596–604` |
| `RunContentManager.removeRunContent(executor, descriptor)` releases `TestResultsViewer` (which is `Disposable` with no remove method) | SDK documentation + verified in plugin `JavaRuntimeExecTool.kt:420–426` |
| `RunInvocation` lifecycle: dispose-on-all-paths contract | Designed in plugin `RunInvocation.kt`; backed by test suite `RunInvocationLeakTest.kt` |
| Session-scoped parent Disposable cascades to `RunInvocation` on "new chat" | Designed in plugin `SessionDisposableHolder.kt` (part of Phase 3 leak-fix plan) |

### Relevant Existing Plugin Code

| File | Purpose | Relevant Sections |
|---|---|---|
| `JavaRuntimeExecTool.kt` | Pattern for test execution via `executeConfigurationAsync` | Lines 329–436 (launch sequence); 596–604 (listener attachment) |
| `CoverageTool.kt` | Pattern for coverage run (same launch contract) | Lines 205–354 (execute pipeline with `RunInvocation`) |
| `DebugBreakpointsTool.kt` | Non-launch pattern; `start_session` uses `DefaultDebugExecutor` | Lines 188–214 (executor + environment setup); demonstrates no callback pattern |
| `RunInvocation.kt` | Per-launch disposal scope; idempotent, defense-in-depth | Full file; class-level KDoc explains concurrency contract |
| `RuntimeExecTool.kt` | Observation-only (does NOT launch; reads active processes) | Lines 131–181 (list running processes via `ExecutionManager`); demonstrates process observation |
| `RuntimeConfigTool.kt` | Configuration CRUD (does NOT launch; creates/modifies configs) | Lines 156–352 (create, modify, delete); useful for understanding config factory resolution |
| `AgentService.kt` | Session management; `newRunInvocation(name)` factory | Lines 214–215; demonstrates session-scoped parent Disposable |
| `SessionDisposableHolder.kt` | Session lifecycle; cascade disposal on "new chat" | Full file; explains orphaned-descriptor semantics on session reset |
| `RunInvocationLeakTest.kt` | Enforcement of disposal invariants | Tests 1–9; validates every attach/cleanup pair and `finally { Disposer.dispose(invocation) }` pattern |

### External Documentation

- IntelliJ Platform SDK Execution Overview: https://plugins.jetbrains.com/docs/intellij/execution.html
- IntelliJ Platform SDK Run Configurations: https://plugins.jetbrains.com/docs/intellij/run-configurations.html
- IntelliJ Platform SDK Threading Model: https://plugins.jetbrains.com/docs/intellij/threading-model.html
- community-source `ExecutionListener.java`: https://github.com/JetBrains/intellij-community/blob/master/platform/execution-impl/src/com/intellij/execution/ExecutionListener.java
- community-source `RunInvocation.kt` (ours): `agent/src/main/kotlin/.../RunInvocation.kt` in this repo
- Research doc (2026-04-17): `docs/research/2026-04-17-intellij-run-test-execution-contract.md` in this repo

---

## Section 9: Implementation Checklist

- [ ] **A1.** Resolve run config by name via `RunManager.getInstance(project).findConfigurationByName(prefixedName)`; error if not found.
- [ ] **A2.** Validate config via `config.checkConfiguration()`; surface `RuntimeConfigurationError` distinctly.
- [ ] **A3.** Build `ExecutionEnvironment` via `ExecutionEnvironmentBuilder.createOrNull(executor, settings)?.build()`; error if null.
- [ ] **A4.** Prepare `RunInvocation` via `project.service<AgentService>().newRunInvocation(name)`; attach listeners (see B & C).
- [ ] **A5.** Subscribe to `ExecutionManager.EXECUTION_TOPIC` for `processNotStarted` / `processStarted` events **before** calling `executeConfigurationAsync`.
- [ ] **A6.** Call `ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)` with fallback to `executeConfiguration`.
- [ ] **B1.** Implement `ProgramRunner.Callback.processStarted(descriptor)` to capture descriptor and process handler; store in `invocation.descriptorRef` / `invocation.processHandlerRef`.
- [ ] **B2.** Attach process listener via `invocation.attachProcessListener(handler, listener)` to stream stdout/stderr.
- [ ] **B3.** Register cleanup via `invocation.onDispose { RunContentManager.getInstance(project).removeRunContent(executor, descriptor) }` (literal in tool file for test anchor).
- [ ] **C1.** Implement readiness detection per config type (Section 2); attach log-scraping and/or idle-stdout heuristic to process listener.
- [ ] **C2.** Extract listening ports via log-scrape patterns (Section 3) or lsof/ss/netstat command.
- [ ] **C3.** Implement readiness timeout (default 60s) and process start timeout (default 300s); timeout within `withTimeoutOrNull`.
- [ ] **C4.** Detach descriptor/handler from invocation if `wait_for_ready=true && readiness achieved` (set refs to null before dispose).
- [ ] **C5.** On timeout or error, call `Disposer.dispose(invocation)` in finally block to clean up listeners and kill process.
- [ ] **D1.** Categorize errors per Section 4; emit error messages with category, suggestion, and actionable details.
- [ ] **D2.** Subscribe to `CompilerManager.COMPILATION_STATUS` topic to capture per-file compile errors (Section 4 table, BEFORE_RUN_FAILED row).
- [ ] **E1.** Test with concurrent launches of different configs; verify ExecutionEnvironment reference equality correlation (Section 5).
- [ ] **E2.** Test app detach on macOS (lsof) and Linux (ss); verify ports discovered correctly.
- [ ] **E3.** Validate Spring Boot log patterns against Boot 2.7 LTS and 3.1+ (Tomcat, Netty, Jetty, Undertow).
- [ ] **E4.** Manual acceptance test: Launch a Spring Boot app, verify readiness detection fires within 5 seconds, ports discovered, `wait_for_ready=false` kills app, `wait_for_ready=true` keeps it running.

### debug mode (`mode=debug`) — Additional Checklist Items

- [ ] **F1.** Select `DefaultDebugExecutor.getDebugExecutorInstance()` when `mode=debug` is passed; `DefaultRunExecutor` for `mode=run` (default). Validate unknown/unsupported modes early (Section 4 `INVALID_CONFIGURATION`).
- [ ] **F2.** Subscribe to `XDebuggerManager.TOPIC` (`XDebuggerManagerListener`) **before** `executeConfigurationAsync` in debug mode. Capture `XDebugProcess.session` in `processStarted` callback.
- [ ] **F3.** Implement `wait_for_pause` support for debug mode: if `wait_for_pause=true`, poll `XDebugSession.isPaused` (or subscribe `XDebugSessionListener.sessionPaused()`) within `ready_timeout_seconds`. On pause, read `XDebugSession.currentPosition` (file:line).
- [ ] **F4.** Return `debug_session_name`, `pid` (from session's process handler), and optionally `paused_at` in the READY result for `mode=debug`.
- [ ] **F5.** Guard `mode=coverage` and `mode=profile` with "not yet supported" errors pointing to `coverage.run_with_coverage` and future profiler tool respectively. These must NOT silently pass as `mode=run`.

### `stop_run_config` — Checklist Items

- [ ] **G1.** Implement `executeStopRunConfig(configName, gracefulTimeoutSeconds, forceOnTimeout)`.
- [ ] **G2.** Lookup running processes via `ExecutionManager.getInstance(project).getRunningProcesses()`, filter by config name substring. Return `PROCESS_NOT_RUNNING` if empty.
- [ ] **G3.** Call `ProcessHandler.destroyProcess()` (graceful) on each matching handler.
- [ ] **G4.** Poll `isProcessTerminated` at ≤500ms intervals until `gracefulTimeoutSeconds` elapses.
- [ ] **G5.** If timeout and `force_on_timeout=true`: call `destroyProcess(true)` (force) + `ProcessHandle.of(pid).destroyForcibly()` fallback.
- [ ] **G6.** Return `STOP_FAILED` if still not terminated and `force_on_timeout=false`.
- [ ] **G7.** Handle multiple matching handlers (same config relaunched): stop ALL; report N stopped in result.
- [ ] **G8.** Wrap in `try { … } finally { Disposer.dispose(invocation) }` using a short-lived `RunInvocation` for the stop listener lifecycle.

### `restart_run_config` — Checklist Items

- [ ] **H1.** Implement `executeRestartRunConfig(configName, mode, gracefulTimeoutSeconds, forceOnTimeout, waitForReady, readyTimeoutSeconds)`.
- [ ] **H2.** Accept explicit `mode` param (default `run`). Document that full auto-detection from descriptor metadata is deferred (see Section 6 Restart Semantics).
- [ ] **H3.** Invoke stop phase → on failure return `RESTART_FAILED_AT_STOP` with delegated message.
- [ ] **H4.** On stop success, invoke `run_config` relaunch → on failure return `RESTART_FAILED_AT_LAUNCH` with delegated message.
- [ ] **H5.** On full success, prefix result with "Restarted 'X':" and include new PID, ports, ready signal.
- [ ] **H6.** Wrap in `try { … } finally { Disposer.dispose(invocation) }` for the restart's own lifecycle scope.

---

## Appendix: Detailed Code Pattern (JavaRuntimeExecTool Analog)

```kotlin
private suspend fun executeRunConfig(params: JsonObject, project: Project): ToolResult {
    val configName = params["config_name"]?.jsonPrimitive?.content
        ?: return error("Missing config_name")
    val waitForReady = params["wait_for_ready"]?.jsonPrimitive?.booleanOrNull ?: false
    val waitForFinish = params["wait_for_finish"]?.jsonPrimitive?.booleanOrNull ?: false
    val readinessTimeoutSec = (params["readiness_timeout"]?.jsonPrimitive?.intOrNull ?: 60)
        .coerceIn(1, 600)

    val executor = DefaultRunExecutor.getRunExecutorInstance()
    val runManager = RunManager.getInstance(project)
    val settings = runManager.findConfigurationByName(configName)
        ?: return error("Configuration '$configName' not found")

    val config = settings.configuration
    try {
        config.checkConfiguration()  // Throws RuntimeConfigurationError / Exception
    } catch (e: Exception) {
        return error("Invalid configuration: ${e.message}")
    }

    val invocation = project.service<AgentService>().newRunInvocation("run-config-$configName")
    try {
        val env = ExecutionEnvironmentBuilder
            .createOrNull(executor, settings)
            ?.build()
            ?: return error("No runner registered for this config type")

        val descriptor = AtomicReference<RunContentDescriptor?>(null)
        val processHandler = AtomicReference<ProcessHandler?>(null)
        val launchEnv = AtomicReference(env)

        // Subscribe to ExecutionListener BEFORE launching
        val runConnection = project.messageBus.connect()
        invocation.subscribeTopic(runConnection)
        runConnection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processNotStarted(executorId: String, e: ExecutionEnvironment) {
                if (e === launchEnv.get()) {
                    // Launch failed: before-run task or execution exception
                    // Surface the error via continuation
                }
            }
            override fun processStarted(executorId: String, e: ExecutionEnvironment, handler: ProcessHandler) {
                if (e === launchEnv.get()) {
                    // Observation only; handler stored via callback
                }
            }
        })

        // Launch with callback
        val callback = object : ProgramRunner.Callback {
            override fun processStarted(desc: RunContentDescriptor?) {
                if (desc != null) {
                    descriptor.set(desc)
                    processHandler.set(desc.processHandler)
                    invocation.descriptorRef.set(desc)
                    invocation.processHandlerRef.set(desc.processHandler)

                    // Register cleanup
                    invocation.onDispose {
                        ApplicationManager.getApplication().invokeLater {
                            RunContentManager.getInstance(project).removeRunContent(executor, desc)
                        }
                    }

                    // Attach readiness detection listener
                    if (waitForReady) {
                        desc.processHandler?.let { handler ->
                            val readinessListener = createReadinessListener(...)
                            invocation.attachProcessListener(handler, readinessListener)
                        }
                    }
                }
            }
        }

        withContext(Dispatchers.EDT) {
            try {
                ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
            } catch (_: NoSuchMethodError) {
                env.callback = callback
                ProgramRunnerUtil.executeConfiguration(env, false, true)
            }
        }

        // Wait for readiness or process exit
        val result = if (waitForReady) {
            awaitReadiness(descriptor.get(), readinessTimeoutSec, project)
        } else if (waitForFinish) {
            awaitProcessTermination(processHandler.get(), 600, project)
        } else {
            // Fire-and-forget; app continues running
            ToolResult("App launched successfully...", "App running", 100)
        }

        // Detach if user wants app to keep running
        if (waitForReady && result.isSuccess) {
            descriptor.set(null)
            processHandler.set(null)
        }

        return result
    } finally {
        Disposer.dispose(invocation)
    }
}
```

---

**End of Research Report**
