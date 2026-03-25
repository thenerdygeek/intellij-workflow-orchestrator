# IntelliJ Platform SDK API Research: 12 Runtime/Debug Tools

**Date:** 2026-03-25
**Purpose:** Exact API specifications for implementing 12 new agent tools
**Baseline:** Existing patterns in `agent/src/main/kotlin/.../tools/debug/AgentDebugController.kt`

## Existing Pattern Reference

All new tools must follow the established patterns in `AgentDebugController`:

- **Callback wrapping:** `suspendCancellableCoroutine` for `XCompositeNode.computeChildren()`, `XDebuggerEvaluator.XEvaluationCallback`, `XExecutionStack.XStackFrameContainer`
- **Session events:** `MutableSharedFlow<DebugPauseEvent>(replay = 1)` for async session notifications
- **Breakpoint creation:** `Dispatchers.EDT` + `WriteAction.compute<>` (see `AddBreakpointTool`)
- **Session launch:** `suspendCancellableCoroutine` wrapping `XDebuggerManagerListener.processStarted()` via MessageBus (see `StartDebugSessionTool`)
- **Step operations:** Action on session -> `waitForPause()` -> auto-include variables (see `DebugStepUtils.kt`)
- **Console access:** `RunContentManager.getInstance(project).allDescriptors` -> `ConsoleViewImpl.text` (see `GetRunOutputTool`)

---

## 1. Exception Breakpoints (`exception_breakpoint`)

### Exact Classes and Methods

| Class | Package | Purpose |
|-------|---------|---------|
| `JavaExceptionBreakpointType` | `com.intellij.debugger.ui.breakpoints` | Breakpoint type for exception breakpoints |
| `JavaExceptionBreakpointProperties` | `org.jetbrains.java.debugger.breakpoints.properties` | Properties holding exception class name + caught/uncaught flags |
| `ExceptionBreakpoint` | `com.intellij.debugger.ui.breakpoints` | The internal breakpoint representation |
| `XBreakpointManager` | `com.intellij.xdebugger.breakpoints` | Manager for adding/removing breakpoints |
| `BreakpointManager` | `com.intellij.debugger.ui.breakpoints` | Java-specific breakpoint manager with `addExceptionBreakpoint()` |

### Key API Methods

```kotlin
// Option A: Via XBreakpointManager (preferred for agent tools — consistent with AddBreakpointTool)
val bpManager = XDebuggerManager.getInstance(project).breakpointManager
val bpType = XDebuggerUtil.getInstance().findBreakpointType(JavaExceptionBreakpointType::class.java)

// Add a specific exception breakpoint
val properties = JavaExceptionBreakpointProperties("java.lang.NullPointerException")
val bp = bpManager.addBreakpoint(bpType, properties)

// Configure caught vs uncaught on the properties
properties.NOTIFY_CAUGHT = true    // Break on caught exceptions
properties.NOTIFY_UNCAUGHT = true  // Break on uncaught exceptions

// Option B: Via BreakpointManager (Java-specific, lower level)
val debuggerManager = DebuggerManagerEx.getInstanceEx(project)
val breakpointManager = debuggerManager.breakpointManager
val exBp = breakpointManager.addExceptionBreakpoint("java.lang.NullPointerException")
```

### Threading
- **EDT + WriteAction** required for `bpManager.addBreakpoint()`, same pattern as `AddBreakpointTool`
- Read access only for `getDefaultBreakpoints()`

### Input Requirements
- `exception_class`: FQN of exception class (e.g., `"java.lang.NullPointerException"`, `"java.lang.RuntimeException"`)
- `caught`: boolean (default: true) — break on caught exceptions
- `uncaught`: boolean (default: true) — break on uncaught exceptions
- `condition`: optional conditional expression

### Output
- Returns `XBreakpoint<JavaExceptionBreakpointProperties>` (non-null on success, null if type not found)

### Silent Failure Modes
- Returns null if `JavaExceptionBreakpointType` is not registered (shouldn't happen in Java IDE)
- No validation that the exception class exists — breakpoint is set regardless, will never trigger if class doesn't exist
- `NOTIFY_CAUGHT`/`NOTIFY_UNCAUGHT` are public boolean fields, not validated

### JVM Requirements
- Target JVM must support exception event requests (all standard JVMs do)
- No special JDWP flags needed

### Example Usage Pattern

```kotlin
withContext(Dispatchers.EDT) {
    WriteAction.compute<XBreakpoint<*>, Exception> {
        val bpManager = XDebuggerManager.getInstance(project).breakpointManager
        val bpType = XDebuggerUtil.getInstance()
            .findBreakpointType(JavaExceptionBreakpointType::class.java)
        val props = JavaExceptionBreakpointProperties("java.lang.NullPointerException")
        props.NOTIFY_CAUGHT = false
        props.NOTIFY_UNCAUGHT = true
        bpManager.addBreakpoint(bpType, props)
    }
}
```

---

## 2. Thread Dump (`thread_dump`)

### Exact Classes and Methods

| Class | Package | Purpose |
|-------|---------|---------|
| `VirtualMachineProxyImpl` | `com.intellij.debugger.jdi` | Proxy wrapping JDI VirtualMachine |
| `ThreadReferenceProxyImpl` | `com.intellij.debugger.jdi` | Proxy wrapping JDI ThreadReference |
| `DebugProcessImpl` | `com.intellij.debugger.engine` | The Java debug process |
| `XDebugSession` | `com.intellij.xdebugger` | XDebugger session |
| `XSuspendContext` | `com.intellij.xdebugger.frame` | Contains execution stacks (one per thread) |
| `ThreadReference` | `com.sun.jdi` | JDI thread reference (status constants) |

### Key API Methods

```kotlin
// Approach 1: Via XDebugger API (preferred — works with existing AgentDebugController)
val session: XDebugSession = controller.getSession(sessionId)
val suspendContext = session.suspendContext
// Each execution stack = one thread
suspendContext?.executionStacks  // Array<XExecutionStack>

// Approach 2: Via JDI Proxy (deeper — gives thread status, deadlock detection)
val debugProcess = session.debugProcess as? DebugProcessImpl
val vmProxy: VirtualMachineProxyImpl = debugProcess?.virtualMachineProxy as VirtualMachineProxyImpl
val allThreads: Collection<ThreadReferenceProxyImpl> = vmProxy.allThreads()

// Thread state
val status: Int = threadProxy.status()
// Constants from com.sun.jdi.ThreadReference:
// THREAD_STATUS_UNKNOWN = -1, THREAD_STATUS_ZOMBIE = 0,
// THREAD_STATUS_RUNNING = 1, THREAD_STATUS_SLEEPING = 2,
// THREAD_STATUS_MONITOR = 3, THREAD_STATUS_WAIT = 4,
// THREAD_STATUS_NOT_STARTED = 5

// Stack frames for each thread
val frames: List<StackFrameProxyImpl> = threadProxy.frames()

// Deadlock detection via JDI (requires suspend all threads first)
val vm: VirtualMachine = vmProxy.virtualMachine  // underlying JDI VM
// JDK 6+: vm.findDeadlockedThreads() — returns long[] of thread IDs or null
```

### Threading
- `allThreads()` — must be called from **debugger manager thread** (assertion in VirtualMachineProxyImpl)
- Use `debugProcess.managerThread.invoke(...)` or `debugProcess.managerThread.schedule(...)` to run on manager thread
- Thread status/frames access — also manager thread
- Wrap in `suspendCancellableCoroutine` for agent tool pattern

### Input Requirements
- `session_id`: optional (uses active session)
- `include_stacks`: boolean (default: true) — whether to include full stack traces
- `max_frames`: int (default: 20) — max frames per thread

### Output
- List of threads with: name, id, status string, daemon flag, thread group, stack frames
- Deadlock info if detected

### Silent Failure Modes
- `vmProxy.allThreads()` returns empty collection if VM is disconnected
- `threadProxy.frames()` throws `EvaluateException` if thread is not suspended
- `session.suspendContext` is null if session is running (not paused)
- Deadlock detection (`findDeadlockedThreads`) returns null when no deadlocks — not an error

### JVM Requirements
- Standard JDWP — all JVMs support thread listing
- Deadlock detection: JDK 6+ (`findDeadlockedThreads()` on `ThreadMXBean`)
- Via JDI: `VirtualMachine` does NOT have built-in deadlock detection — must use `ThreadMXBean` approach via evaluate expression: `ManagementFactory.getThreadMXBean().findDeadlockedThreads()`

### Example Usage Pattern

```kotlin
// Must run on debugger manager thread
val result = suspendCancellableCoroutine<List<ThreadInfo>> { cont ->
    val debugProcess = (session.debugProcess as? DebugProcessImpl) ?: run {
        cont.resume(emptyList()); return@suspendCancellableCoroutine
    }
    debugProcess.managerThread.schedule(object : DebuggerCommandImpl() {
        override fun action() {
            val vmProxy = debugProcess.virtualMachineProxy as VirtualMachineProxyImpl
            val threads = vmProxy.allThreads().map { threadProxy ->
                ThreadInfo(
                    name = threadProxy.name(),
                    status = threadStatusToString(threadProxy.status()),
                    frames = try { threadProxy.frames().take(maxFrames).map { /* ... */ } }
                             catch (_: EvaluateException) { emptyList() }
                )
            }
            cont.resume(threads)
        }
        override fun commandCancelled() { cont.resume(emptyList()) }
    })
}
```

### Non-Debug Thread Dump
For running (non-debug) processes, cannot get JDI thread dump. Alternative: evaluate `Thread.getAllStackTraces()` if in debug mode, or use JMX attach API (requires tools.jar / JDK 9+ attach API).

---

## 3. Read Console Output (`read_console_output`)

### NOTE: Already implemented as `GetRunOutputTool`

The existing `GetRunOutputTool` at `agent/src/main/kotlin/.../tools/runtime/GetRunOutputTool.kt` already handles this. Key implementation details:

### Exact Classes and Methods

| Class | Package | Purpose |
|-------|---------|---------|
| `RunContentManager` | `com.intellij.execution.ui` | Manages run content tabs — `allDescriptors` |
| `RunContentDescriptor` | `com.intellij.execution.ui` | Descriptor for a run tab |
| `ConsoleViewImpl` | `com.intellij.execution.impl` | Console implementation with `text` property |
| `ProcessHandler` | `com.intellij.execution.process` | Process handler with `isProcessTerminated` |
| `ExecutionManager` | `com.intellij.execution` | `getRunningProcesses()` for active handlers |

### Threading
- `ConsoleViewImpl.text` — must be called on **EDT** (`invokeAndWaitIfNeeded`)
- `ConsoleViewImpl.flushDeferredText()` — EDT, ensures all buffered text is flushed
- `RunContentManager.allDescriptors` — can be called from any thread

### Buffer Limits
- `ConsoleViewImpl` has a configurable buffer limit (default ~1MB, controlled by `idea.cycle.buffer.size`)
- Old text is discarded when buffer fills — you may miss early output
- Use `ProcessHandler.addProcessListener(ProcessAdapter)` to capture ALL output from start

### Input Requirements
- `config_name`: string — matches against `RunContentDescriptor.displayName`
- `last_n_lines`: int (default: 200, max: 1000)
- `filter`: optional regex

### Silent Failure Modes
- `executionConsole` is null if the tab has been closed
- `ConsoleViewImpl.text` returns empty string if console not yet initialized
- Process may be terminated but console still accessible (text persists)

---

## 4. Field Watchpoints (`field_watchpoint`)

### Exact Classes and Methods

| Class | Package | Purpose |
|-------|---------|---------|
| `JavaFieldBreakpointType` | `com.intellij.debugger.ui.breakpoints` | Breakpoint type ID: `"java-field"` |
| `JavaFieldBreakpointProperties` | `org.jetbrains.java.debugger.breakpoints.properties` | Properties with className, fieldName, WATCH_ACCESS, WATCH_MODIFICATION |
| `FieldBreakpoint` | `com.intellij.debugger.ui.breakpoints` | Internal representation |
| `BreakpointManager` | `com.intellij.debugger.ui.breakpoints` | `addFieldBreakpoint()` |

### Key API Methods

```kotlin
// Option A: Via XBreakpointManager + line location (preferred)
val bpType = XDebuggerUtil.getInstance()
    .findBreakpointType(JavaFieldBreakpointType::class.java)
val props = JavaFieldBreakpointProperties(fieldName, className)
props.WATCH_ACCESS = true       // break on field read
props.WATCH_MODIFICATION = true // break on field write
val bp = bpManager.addLineBreakpoint(bpType, fileUrl, lineNumber, props, false)

// Option B: Via BreakpointManager (lower level)
val breakpointManager = DebuggerManagerEx.getInstanceEx(project).breakpointManager
val fieldBp = breakpointManager.addFieldBreakpoint(document, lineIndex, fieldName)
```

### Threading
- **EDT + WriteAction** for `addLineBreakpoint()` / `addFieldBreakpoint()`

### Input Requirements
- `class_name`: FQN (e.g., `"com.example.MyClass"`)
- `field_name`: field identifier (e.g., `"counter"`)
- `watch_access`: boolean (default: false) — break on reads
- `watch_modification`: boolean (default: true) — break on writes
- `file` + `line`: needed for `addLineBreakpoint()` approach — requires PSI lookup to find field declaration

### Kotlin Properties
- Kotlin properties compile to a backing field + getter/setter
- Field watchpoints work on the backing field (e.g., `myProperty` backing field)
- For `var x: Int`, the JVM field is typically `x` (no name mangling for simple properties)
- For `private var` with custom getter/setter, field name may differ

### Performance
- Field watchpoints use JDI `WatchpointRequest` which uses JDWP field access/modification events
- **Moderate performance impact** — slower than line breakpoints but faster than method breakpoints
- Each watchpoint creates a JDI event request, which the JVM must check on every field access/modification

### Silent Failure Modes
- `addLineBreakpoint()` returns null if the line doesn't contain a field declaration
- Watchpoint silently never triggers if class/field name doesn't match loaded class
- `WATCH_ACCESS = false && WATCH_MODIFICATION = false` = breakpoint exists but never triggers

### JVM Requirements
- `VirtualMachineProxy.canWatchFieldModification()` / `canWatchFieldAccess()` must return true
- All standard HotSpot JVMs support this

---

## 5. Force Return (`force_return`)

### Exact Classes and Methods

| Class | Package | Purpose |
|-------|---------|---------|
| `ThreadReferenceProxyImpl` | `com.intellij.debugger.jdi` | `forceEarlyReturn(Value)` |
| `VirtualMachineProxyImpl` | `com.intellij.debugger.jdi` | `canForceEarlyReturn()` capability check |
| `DebugProcessImpl` | `com.intellij.debugger.engine` | Debug process with manager thread |
| `DebuggerCommandImpl` | `com.intellij.debugger.engine.events` | Base class for manager thread commands |

### Key API Methods

```kotlin
// Capability check
val vmProxy = (debugProcess as DebugProcessImpl).virtualMachineProxy as VirtualMachineProxyImpl
val canForce = vmProxy.canForceEarlyReturn() // check first!

// Get the current thread proxy
val threadProxy: ThreadReferenceProxyImpl = /* from suspend context */

// Force return with a value
threadProxy.forceEarlyReturn(value)  // Value is com.sun.jdi.Value
```

### Method Signature
```java
// In ThreadReferenceProxyImpl:
public void forceEarlyReturn(Value value)
    throws ClassNotLoadedException, IncompatibleThreadStateException, InvalidTypeException
```

### Threading
- **Must run on debugger manager thread** via `debugProcess.managerThread.schedule()`
- Thread must be suspended at a breakpoint (not just any suspension)
- Wrap in `suspendCancellableCoroutine`

### Input Requirements
- `session_id`: optional
- `return_value`: the value to return — must match method return type
  - `null` for reference types: `vmProxy.mirrorOfNull()` (or `null` itself)
  - Primitives: `vmProxy.mirrorOf(42)`, `vmProxy.mirrorOf(true)`, etc.
  - `void` methods: `vmProxy.mirrorOfVoid()` (JDI `VoidValue`)
  - String: `vmProxy.mirrorOf("hello")`
  - Objects: Must create via evaluate expression or use existing object reference

### Exceptions on Failure
- `ClassNotLoadedException` — if return type class not loaded
- `IncompatibleThreadStateException` — thread not suspended at breakpoint
- `InvalidTypeException` — value type doesn't match method return type
- `NativeMethodException` — cannot force return from native methods
- `OpaqueFrameException` (JDK 19+) — virtual thread not at breakpoint

### Silent Failure Modes
- `canForceEarlyReturn()` returns false on older JVMs — must check before attempting
- No error if value type is wrong reference type — may cause ClassCastException at runtime

### JVM Requirements
- JVM must support `canForceEarlyReturn` capability (JDK 6+, HotSpot)
- Thread must be suspended at a breakpoint or step event

---

## 6. Drop Frame / Pop Frame (`drop_frame`)

### Exact Classes and Methods

| Class | Package | Purpose |
|-------|---------|---------|
| `ThreadReferenceProxyImpl` | `com.intellij.debugger.jdi` | `popFrames(StackFrameProxyImpl)` |
| `StackFrameProxyImpl` | `com.intellij.debugger.jdi` | Stack frame to pop to |
| `VirtualMachineProxyImpl` | `com.intellij.debugger.jdi` | `canPopFrames()` capability check |
| `DebugProcessImpl` | `com.intellij.debugger.engine` | Has `createPopFrameCommand()` |

### Key API Methods

```kotlin
// Capability check
val canPop = vmProxy.canPopFrames() // requires JDK 1.4+ AND JVM support

// Get the stack frame to pop to
val threadProxy: ThreadReferenceProxyImpl = /* current thread */
val frames = threadProxy.frames()
val targetFrame: StackFrameProxyImpl = frames[frameIndex]

// Pop frames — pops all frames above and including targetFrame
threadProxy.popFrames(targetFrame)

// Higher-level: DebugProcessImpl provides a command
val popCmd = debugProcess.createPopFrameCommand(debuggerContext, stackFrameProxy)
debugProcess.managerThread.schedule(popCmd)
```

### Method Signature
```java
// In ThreadReferenceProxyImpl:
public void popFrames(StackFrameProxyImpl stackFrame) throws EvaluateException
// Internally calls: threadRef.popFrames(stackFrame.getStackFrame())

// In DebugProcessImpl:
public PopFrameCommand createPopFrameCommand(DebuggerContextImpl context, StackFrameProxyImpl frame)
```

### Threading
- **Debugger manager thread** via `debugProcess.managerThread.schedule()`
- Thread must be suspended

### Input Requirements
- `session_id`: optional
- `frame_index`: int — which frame to drop to (0 = current, 1 = caller, etc.)

### Restrictions
- Cannot pop past a native method frame
- Cannot pop if thread holds monitors (locks) that were acquired in the popped frames
- Cannot pop the bottom-most frame
- Variable state is NOT reset — local variables keep their modified values
- Side effects (file writes, network calls) are NOT undone
- Virtual threads (JDK 19+): may throw `OpaqueFrameException`

### Silent Failure Modes
- `canPopFrames()` returns false on some JVMs — must check
- `popFrames()` with an invalid frame throws `EvaluateException`

### JVM Requirements
- `canPopFrames()` must return true (JDK 1.4+, all HotSpot JVMs)
- Target must NOT be a native frame

---

## 7. HotSwap (`hotswap`)

### Exact Classes and Methods

| Class | Package | Purpose |
|-------|---------|---------|
| `HotSwapUI` | `com.intellij.debugger.ui` | Abstract base — `getInstance(project)` |
| `HotSwapUIImpl` | `com.intellij.debugger.ui` | Implementation with `reloadChangedClasses()` |
| `HotSwapManager` | `com.intellij.debugger.impl` | Low-level — `scanForModifiedClasses()` |
| `DebuggerSession` | `com.intellij.debugger.impl` | Debug session reference |
| `HotSwapStatusListener` | `com.intellij.debugger.ui` | Callback for success/failure/cancel |
| `DebuggerManagerEx` | `com.intellij.debugger` | `getInstanceEx(project)` for session access |

### Key API Methods

```kotlin
// Get the HotSwap service
val hotSwapUI = HotSwapUI.getInstance(project) as HotSwapUIImpl

// Get the debugger session (not XDebugSession — the Java-specific one)
val debuggerManager = DebuggerManagerEx.getInstanceEx(project)
val debuggerSession: DebuggerSession = debuggerManager.sessions.firstOrNull()
    ?: error("No debug session")

// Trigger HotSwap (compile first, then reload)
hotSwapUI.reloadChangedClasses(
    debuggerSession,
    true,  // compileBeforeHotswap — builds project first
    object : HotSwapStatusListener {
        override fun onSuccess(sessions: List<DebuggerSession>) { /* classes reloaded */ }
        override fun onFailure(sessions: List<DebuggerSession>) { /* hotswap failed */ }
        override fun onCancel(sessions: List<DebuggerSession>) { /* user cancelled */ }
        override fun onNothingToReload(sessions: List<DebuggerSession>) { /* no changes */ }
    }
)

// Alternative: compile specific files then hotswap
hotSwapUI.compileAndReload(debuggerSession, *virtualFiles)
```

### Threading
- `reloadChangedClasses()` — can be called from **any thread** (it schedules work internally)
- Compilation runs as a background task
- Callback is invoked on EDT
- Wrap callback in `suspendCancellableCoroutine` for agent tool

### Input Requirements
- `session_id`: optional (finds active debug session)
- `compile_first`: boolean (default: true) — compile before reloading
- `files`: optional list of files to compile (if omitted, compiles changed files automatically)

### How Changed Classes Are Detected
1. `HotSwapUIImpl` registers a `ProjectTaskListener` that intercepts compilation events
2. Collects paths to generated `.class` files from `CompilerPaths.getOutputPaths()`
3. `HotSwapManager.scanForModifiedClasses()` compares loaded class timestamps with compiled class files
4. Only modified classes are sent for redefinition

### Error Handling
- HotSwap fails if class structure changed (added/removed methods, fields, changed signatures)
- Only method body changes are supported by standard JVM HotSwap
- DCEVM (Dynamic Code Evolution VM) supports full structural changes
- Failure reported via `HotSwapStatusListener.onFailure()`

### Silent Failure Modes
- `sessions` is empty if no debug session is active
- If `compileBeforeHotswap = false` and no classes were recently compiled, `onNothingToReload()` fires
- Silent success even if reloaded class has semantic errors

### JVM Requirements
- Standard JVM HotSwap: method body changes only
- DCEVM: structural changes (add/remove methods, fields)
- JVM must have debug agent attached (always true for debug sessions)

---

## 8. Memory View (`memory_view`)

### Exact Classes and Methods

| Class | Package | Purpose |
|-------|---------|---------|
| `ConstructorInstancesTracker` | `com.intellij.debugger.memory.tracking` | Tracks new instances since last suspension |
| `TrackerForNewInstances` | `com.intellij.debugger.memory.tracking` | Monitors newly created objects |
| `BackgroundTracker` | `com.intellij.debugger.memory.tracking` | Background tracking service |
| `InstanceJavaValue` | `com.intellij.debugger.memory.utils` | JavaValue wrapper for instances |
| `InstanceValueDescriptor` | `com.intellij.debugger.memory.utils` | Descriptor for instance values |

### JDI-Level Instance Counting

The instance counting is done via JDI `VirtualMachine` directly, not through IntelliJ wrappers:

```kotlin
// Via JDI VirtualMachine (requires debug session)
val debugProcess = session.debugProcess as DebugProcessImpl
val vm = (debugProcess.virtualMachineProxy as VirtualMachineProxyImpl).virtualMachine

// Get instance counts for specific classes
val refTypes: List<ReferenceType> = vm.classesByName("com.example.MyClass")
val counts: LongArray = vm.instanceCounts(refTypes) // JDK 6+

// Get actual instances (expensive!)
val instances: List<ObjectReference> = refType.instances(maxInstances) // JDK 6+
```

### Threading
- **Debugger manager thread** for JDI calls
- `vm.instanceCounts()` and `refType.instances()` suspend all threads briefly

### Input Requirements
- `session_id`: optional
- `class_name`: FQN of class to inspect
- `max_instances`: int (default: 100) — limit for `instances()` call
- `include_fields`: boolean (default: false) — inspect fields of found instances

### Retained Size
- JDI does NOT provide retained size
- To calculate retained size, you'd need a heap dump or manual graph traversal
- Not recommended for agent tools — too expensive

### Silent Failure Modes
- `vm.instanceCounts()` returns zeros if class hasn't been loaded yet
- `vm.classesByName()` returns empty list for unknown classes
- `refType.instances(0)` returns ALL instances — can be extremely large, causes OOM
- Returns empty if debug session is not paused

### JVM Requirements
- `vm.canGetInstanceInfo()` must return true (JDK 6+ HotSpot)
- **Target JVM must be started with `-XX:+AllowEnhancedClassRedefinition` or similar** — actually no, instance counting is separate from DCEVM
- Standard JDK 6+ HotSpot supports `instanceCounts()` and `instances()`

---

## 9. Method Breakpoints (`method_breakpoint`)

### Exact Classes and Methods

| Class | Package | Purpose |
|-------|---------|---------|
| `JavaMethodBreakpointType` | `com.intellij.debugger.ui.breakpoints` | Type ID: inherits from `JavaLineBreakpointTypeBase` |
| `JavaMethodBreakpointProperties` | `org.jetbrains.java.debugger.breakpoints.properties` | Properties with `myClassPattern`, `myMethodName`, `WATCH_ENTRY`, `WATCH_EXIT` |
| `MethodBreakpoint` | `com.intellij.debugger.ui.breakpoints` | Internal representation |
| `BreakpointManager` | `com.intellij.debugger.ui.breakpoints` | `addMethodBreakpoint(Document, int)` |

### Key API Methods

```kotlin
// Option A: Via XBreakpointManager + line location
val bpType = XDebuggerUtil.getInstance()
    .findBreakpointType(JavaMethodBreakpointType::class.java)
val props = JavaMethodBreakpointProperties()
props.myClassPattern = "com.example.MyClass"
props.myMethodName = "processOrder"
props.WATCH_ENTRY = true   // break on method entry
props.WATCH_EXIT = false   // don't break on method exit
val bp = bpManager.addLineBreakpoint(bpType, fileUrl, lineOfMethod, props, false)

// Option B: Via BreakpointManager
val methodBp = breakpointManager.addMethodBreakpoint(document, lineIndex)
```

### Threading
- **EDT + WriteAction** for `addLineBreakpoint()`
- For the PSI-based approach (finding method line): **ReadAction** to resolve PSI

### Input Requirements
- `class_name`: FQN (e.g., `"com.example.MyService"`)
- `method_name`: method name (e.g., `"processOrder"`)
- `watch_entry`: boolean (default: true)
- `watch_exit`: boolean (default: false)
- `file` + `line`: needed for `addLineBreakpoint()` — requires PSI resolution
- For class+method only approach: use PSI to find the method declaration line

### Performance Impact
- **Method breakpoints are SIGNIFICANTLY slower than line breakpoints**
- They use JDI `MethodEntryRequest`/`MethodExitRequest` which intercept EVERY method entry/exit in the JVM
- IntelliJ uses "emulated" method breakpoints (registry: `debugger.emulate.method.breakpoints`) that convert to line breakpoints at the method's first/last line — this is much faster
- Emulated mode: performance similar to line breakpoints
- Non-emulated mode: can slow debugging by 10-100x

### Works on Interface Methods?
- Yes — when set on an interface method, it triggers on any implementation that is called
- Emulated breakpoints resolve all implementations via PSI and set line breakpoints on each

### Silent Failure Modes
- `addLineBreakpoint()` returns null if line doesn't contain a method
- Method breakpoints on abstract/interface methods only work in non-emulated mode
- If class pattern doesn't match any loaded class, breakpoint never triggers

### JVM Requirements
- Standard JDWP method entry/exit events (all JVMs)
- Emulated mode requires PSI — only works for project source code, not library code

---

## 10. Attach to Process (`attach_to_process`)

### Exact Classes and Methods

| Class | Package | Purpose |
|-------|---------|---------|
| `RemoteConnection` | `com.intellij.execution.configurations` | Connection params (host, port, server mode) |
| `GenericDebuggerRunner` | `com.intellij.debugger.impl` | Runner that can attach to remote JVM |
| `RemoteConfiguration` | `com.intellij.execution.remote` | Run configuration for remote debug |
| `RemoteConfigurationType` | `com.intellij.execution.remote` | Configuration type |
| `DefaultDebugExecutor` | `com.intellij.execution.executors` | Debug executor |
| `ExecutionEnvironmentBuilder` | `com.intellij.execution.runners` | Builds execution environment |

### Key API Methods

```kotlin
// Create a Remote debug run configuration
val runManager = RunManager.getInstance(project)
val remoteConfigType = RemoteConfigurationType.getInstance()
val settings = runManager.createConfiguration("[Agent] Remote Debug", remoteConfigType.factory)
val remoteConfig = settings.configuration as RemoteConfiguration

// Configure connection
remoteConfig.HOST = "localhost"
remoteConfig.PORT = "5005"
remoteConfig.SERVER_MODE = false  // false = attach to running JVM
remoteConfig.USE_SOCKET_TRANSPORT = true

// Launch debug session
val executor = DefaultDebugExecutor.getDebugExecutorInstance()
val env = ExecutionEnvironmentBuilder.create(project, executor, remoteConfig).build()

// Use same pattern as StartDebugSessionTool — listen for session start
withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(env, true, true)
}
```

### Threading
- Configuration creation: any thread
- `executeConfiguration()`: **EDT**
- Session registration: use same `XDebuggerManagerListener` pattern as `StartDebugSessionTool`

### Input Requirements
- `host`: string (default: `"localhost"`)
- `port`: int (required — the JDWP debug port)
- `transport`: `"socket"` or `"shmem"` (default: `"socket"`)

### JDWP Settings for Target JVM
The target JVM must be started with JDWP agent:
```
# JDK 9+
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005

# JDK 8
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005
```

### Discovering Attachable JVMs
- `ProcessInfo` from `com.intellij.execution` can list running processes
- `AttachDebuggerAction` in IntelliJ scans for JVMs with debug port open
- JDK `VirtualMachine.list()` from `com.sun.tools.attach` lists attachable JVMs (requires tools.jar/JDK)
- No reliable IntelliJ API to enumerate debug-enabled JVMs

### Silent Failure Modes
- Connection refused if target JVM not started with JDWP
- Timeout if firewall blocks the port
- Silent disconnect if target JVM exits during attach
- `RemoteConfiguration` silently accepts invalid port numbers

### JVM Requirements
- Target must have JDWP agent enabled
- Transport must match (socket vs shared memory)
- Same JDI protocol version (almost always compatible)

---

## 11. Run with Coverage (`run_with_coverage`)

### Exact Classes and Methods

| Class | Package | Purpose |
|-------|---------|---------|
| `CoverageExecutor` | `com.intellij.coverage` | Executor for "Run with Coverage" |
| `CoverageDataManager` | `com.intellij.coverage` | `getInstance(project)` — manages suites |
| `CoverageSuite` | `com.intellij.coverage` | A single coverage run's data |
| `CoverageSuitesBundle` | `com.intellij.coverage` | Bundle of suites for display |
| `CoverageRunner` | `com.intellij.coverage` | Engine (JaCoCo, IntelliJ) |
| `DefaultJavaCoverageRunner` | `com.intellij.coverage` | IntelliJ's built-in Java coverage |
| `JaCoCoCoverageRunner` | `com.intellij.coverage` | JaCoCo engine |
| `CoverageEnabledConfiguration` | `com.intellij.execution.configurations.coverage` | Links run config to coverage |
| `CoverageSuiteListener` | `com.intellij.coverage` | Listener for suite events |
| `ProjectData` | `com.intellij.rt.coverage.data` | Coverage data (line hits, branch info) |

### Key API Methods

```kotlin
// Step 1: Get executor
val coverageExecutor = ExecutorRegistry.getInstance()
    .getExecutorById("Coverage") as CoverageExecutor

// Step 2: Get run configuration
val runManager = RunManager.getInstance(project)
val settings = runManager.findConfigurationByName(configName)

// Step 3: Configure coverage
val coverageConfig = CoverageEnabledConfiguration.getFrom(settings.configuration)
coverageConfig?.coverageRunner = DefaultJavaCoverageRunner() // or JaCoCoCoverageRunner

// Step 4: Build and execute
val env = ExecutionEnvironmentBuilder.createOrNull(coverageExecutor, settings)
    ?: error("Cannot create coverage environment")

// Step 5: Listen for coverage results
val dataManager = CoverageDataManager.getInstance(project)
dataManager.addSuiteListener(object : CoverageSuiteListener {
    override fun coverageDataCalculated(bundle: CoverageSuitesBundle) {
        val projectData: ProjectData = bundle.coverageData
        // Access per-file coverage:
        val classData = projectData.getClassData("com.example.MyClass")
        val lineData = classData?.getLineData(lineNumber) // LineCoverage
    }
}, parentDisposable)

// Launch
withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(env.build(), true, true)
}

// Step 6: Read results after completion
val currentBundle = dataManager.currentSuitesBundle
val coverageData = currentBundle?.coverageData
```

### Threading
- `executeConfiguration()`: **EDT**
- `CoverageSuiteListener` callbacks: **EDT**
- `CoverageDataManager` methods: any thread (but UI reads on EDT)
- Wrap listener in `suspendCancellableCoroutine`

### Input Requirements
- `config_name`: string — run configuration name
- `coverage_engine`: `"intellij"` or `"jacoco"` (default: `"intellij"`)
- `classes_to_include`: optional list of package patterns
- `classes_to_exclude`: optional list of patterns

### JaCoCo vs IntelliJ Coverage Engine
| Feature | IntelliJ | JaCoCo |
|---------|----------|--------|
| Branch coverage | Yes | Yes |
| Line coverage | Yes | Yes |
| Method coverage | No | Yes |
| Performance | Faster (sampling) | Slower (instrumentation) |
| Test-level | Limited | Full |
| Export format | Proprietary | XML/HTML/CSV |

### Output Format (from `ProjectData`)
```kotlin
val classData: ClassData = projectData.getClassData("com/example/MyClass")
val lineData: LineData = classData.getLineData(42)
lineData.hits        // int — number of times line was hit
lineData.status      // byte: 0=not covered, 1=partial, 2=full
```

### Silent Failure Modes
- `CoverageEnabledConfiguration.getFrom()` returns null for non-Java configs
- `CoverageDataManager.currentSuitesBundle` is null if no coverage run completed
- `getClassData()` returns null for classes not in coverage scope
- Coverage data is project-global — may include data from previous runs

### JVM Requirements
- IntelliJ coverage agent is injected automatically
- JaCoCo agent is injected automatically
- No special JVM flags needed (agents are added via run configuration)

---

## 12. Stream Debugger (`stream_debugger`)

### Architecture

The stream debugger is a separate IntelliJ plugin (`intellij.java.debugger.streams`) with these packages:
- `com.intellij.debugger.streams.psi` — PSI analysis to detect stream chains
- `com.intellij.debugger.streams.trace` — Execution tracing
- `com.intellij.debugger.streams.trace.impl` — Trace implementations
- `com.intellij.debugger.streams.trace.dsl.impl.java` — DSL for Java stream tracing
- `com.intellij.debugger.streams.lib.impl` — Library implementations (Java 8 Streams, StreamEx)
- `com.intellij.debugger.streams.ui.impl` — Visualization UI

### Programmatic API

**The stream debugger is primarily a UI feature.** There is no clean public API for programmatic stream tracing. However, the key classes are:

| Class | Package | Purpose |
|-------|---------|---------|
| `TraceStreamAction` | `com.intellij.debugger.streams.action` | Action triggered by "Trace Current Stream Chain" button |
| `StreamChainBuilder` | `com.intellij.debugger.streams.psi` | Detects stream chains in code |
| `StreamTracer` | `com.intellij.debugger.streams.trace` | Executes stream trace |
| `TracingResult` | `com.intellij.debugger.streams.trace` | Results of stream tracing |

### How It Works Internally
1. PSI analysis detects the stream chain at the current position
2. A trace expression is generated (evaluates each intermediate step)
3. The trace expression is evaluated via the debugger's evaluate API
4. Results are collected per stream operation stage
5. Visualization renders the data flow

### Data Collected
- Input/output elements at each stream stage (filter, map, flatMap, etc.)
- Mapping between input and output elements (which input produced which output)
- Terminal operation result

### Feasibility for Agent Tool
**Recommendation: Not worth implementing as a programmatic tool.**
- The stream debugger's API is internal and not designed for programmatic access
- The value is primarily in the visual data flow diagram
- Alternative: Use `evaluate_expression` to evaluate individual stream stages
- Example: evaluate `stream.filter(x -> x > 5).collect(Collectors.toList())` at the breakpoint

### If You Must Implement It
```kotlin
// 1. Find stream chain via PSI
// 2. Use StreamTracer to generate trace code
// 3. Evaluate trace code via debugger evaluator
// 4. Parse TracingResult

// This requires depending on the stream-debugger plugin:
// com.intellij.debugger.streams.trace.StreamTracer
// Not stable API — likely to break between IDE versions
```

### Silent Failure Modes
- Stream debugger plugin may not be installed
- Only works when paused on a line containing a stream chain
- Complex stream chains (parallel, custom collectors) may fail to trace
- Trace evaluation can time out for large streams

### JVM Requirements
- No special requirements beyond standard debugging
- Works with Java 8+ Streams API

---

## Summary: Threading Requirements Matrix

| Tool | EDT | WriteAction | Manager Thread | ReadAction | Callback Pattern |
|------|-----|-------------|----------------|------------|------------------|
| Exception Breakpoint | Yes | Yes | No | No | None |
| Thread Dump | No | No | Yes | No | `DebuggerCommandImpl` |
| Read Console | Yes (text) | No | No | No | `invokeAndWaitIfNeeded` |
| Field Watchpoint | Yes | Yes | No | Yes (PSI) | None |
| Force Return | No | No | Yes | No | `DebuggerCommandImpl` |
| Drop Frame | No | No | Yes | No | `DebuggerCommandImpl` |
| HotSwap | Any | No | No | No | `HotSwapStatusListener` |
| Memory View | No | No | Yes | No | `DebuggerCommandImpl` |
| Method Breakpoint | Yes | Yes | No | Yes (PSI) | None |
| Attach to Process | Yes (launch) | No | No | No | `XDebuggerManagerListener` |
| Coverage | Yes (launch) | No | No | No | `CoverageSuiteListener` |
| Stream Debugger | N/A | N/A | N/A | N/A | Not recommended |

## New Pattern Needed: Debugger Manager Thread

Several tools require a new wrapping pattern not in the existing `AgentDebugController`:

```kotlin
// New method for AgentDebugController:
suspend fun <T> executeOnManagerThread(
    session: XDebugSession,
    action: (DebugProcessImpl, VirtualMachineProxyImpl) -> T
): T? {
    val debugProcess = session.debugProcess as? DebugProcessImpl ?: return null
    return suspendCancellableCoroutine { cont ->
        debugProcess.managerThread.schedule(object : DebuggerCommandImpl() {
            override fun action() {
                try {
                    val vmProxy = debugProcess.virtualMachineProxy as VirtualMachineProxyImpl
                    val result = action(debugProcess, vmProxy)
                    cont.resume(result)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
            override fun commandCancelled() {
                cont.cancel()
            }
        })
    }
}
```

## Implementation Priority Recommendation

| Priority | Tool | Effort | Value |
|----------|------|--------|-------|
| P0 | Exception Breakpoints | Low | High — directly extends existing AddBreakpointTool pattern |
| P0 | Thread Dump | Medium | High — critical for debugging concurrency issues |
| P1 | Field Watchpoints | Low | Medium — extends breakpoint pattern |
| P1 | Method Breakpoints | Low | Medium — extends breakpoint pattern |
| P1 | Force Return | Medium | High — powerful debugging capability |
| P1 | Drop Frame | Medium | High — re-execute code with different state |
| P1 | HotSwap | Low | High — edit-and-continue during debug |
| P2 | Memory View | Medium | Medium — niche but useful for memory leaks |
| P2 | Attach to Process | Medium | Medium — extends StartDebugSessionTool pattern |
| P2 | Coverage | Medium | Medium — useful for test coverage analysis |
| P3 | Console Output | Done | Already implemented as GetRunOutputTool |
| P3 | Stream Debugger | High | Low — internal API, fragile, visual-first feature |
