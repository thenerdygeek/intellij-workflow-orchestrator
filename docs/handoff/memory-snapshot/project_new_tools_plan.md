---
name: New agent tools implementation plan
description: 21 new IntelliJ agent tools planned — 9 IDE intelligence + 12 runtime/debug. Research-first approach required before implementation.
type: project
---

## Planned Agent Tools (21 total)

### IDE Intelligence Tools (9)

| Tool | IntelliJ API | Type | Notes |
|------|-------------|------|-------|
| **type_inference** | `PsiTypesUtil`, `CommonDataflow` | Read-only | Resolve generic types, nullability. Silent fail: unresolved types in incomplete code |
| **structural_search** | `StructuralSearchUtil`, `StructuralSearchProfile` | Read-only | Semantic pattern matching. Silent fail: invalid SSR pattern syntax is hard to diagnose |
| **dataflow_analysis** | `DataFlowRunner`, `DFAEngine` | Read-only | Nullability, dead code, constant ranges. Silent fail: analysis may be incomplete on non-compiled code |
| **read_write_access** | `ReadWriteAccessSearch` | Read-only | Find all reads vs writes to a field/variable. Silent fail: may miss reflection-based access |
| **test_finder** | `TestFinder`, `TestCreator` | Read-only | Find tests for a class, generate test skeletons. Silent fail: custom test naming conventions not detected |
| **module_dependency_graph** | `ModuleRootManager`, `LibraryTable` | Read-only | Module deps, circular dep detection, classpath analysis. Silent fail: Gradle sync must be complete |
| **changelist_shelve** | `ChangeListManager`, `ShelveChangesManager` | Write | Create changelists, shelve/unshelve work. Silent fail: shelved changes may conflict on unshelve |
| **problem_view** | `WolfTheProblemSolver`, `ProblemsView` | Read-only | Read IDE problems window. Silent fail: problems may be stale if analysis hasn't completed |
| **terminal** | `TerminalExecutionConsole`, `TerminalShellCommandHandler` | Write | Run in embedded IDE terminal. Silent fail: terminal may not initialize on all platforms |

### Runtime & Debug Tools (12)

**Tier 1 — Highest debug efficiency gain:**

| Tool | IntelliJ API | Type | Notes |
|------|-------------|------|-------|
| **exception_breakpoint** | `JavaExceptionBreakpointType`, `XBreakpointManager.addDefaultBreakpoint()` | Write | Break on exception type (caught/uncaught). Silent fail: FQN must match exactly |
| **thread_dump** | `DebugProcessImpl.getVirtualMachineProxy().allThreads()` | Read-only | All threads + states + stacks. Detects deadlocks. Trivial to implement |
| **read_console_output** | `ProcessHandler`, `ConsoleViewImpl`, `ExecutionManager` | Read-only | Read stdout/stderr from running process. Silent fail: buffer may be truncated |
| **field_watchpoint** | `JavaFieldBreakpointType`, `XBreakpointManager` | Write | Break on field read/write. Silent fail: inner classes need `$` syntax |

**Tier 2 — Powerful but complex:**

| Tool | IntelliJ API | Type | Notes |
|------|-------------|------|-------|
| **force_return** | `DebugProcessImpl.forceReturn()` | Write (approval) | Force method to return specific value. Silent fail: can't work on native/synthetic methods |
| **drop_frame** | `DebugProcessImpl.popFrame()` | Write (approval) | Rewind to method start. Silent fail: can't drop frames with monitors or native frames |
| **hotswap** | `HotSwapManager.reloadChangedClasses()` | Write (approval) | Replace bytecode without restart. Silent fail: only method body changes work |
| **memory_view** | `VirtualMachineProxy.instanceCounts()` | Read-only | Count live objects by type for leak detection |

**Tier 3 — Specialized:**

| Tool | IntelliJ API | Type | Notes |
|------|-------------|------|-------|
| **method_breakpoint** | `JavaMethodBreakpointType` | Write | Break on method entry/exit. Caveat: MUCH slower than line breakpoints (JVM limitation) |
| **attach_to_process** | `RemoteConnection`, `GenericDebuggerRunner` | Write | Attach debugger to running JVM. Needs `-agentlib:jdwp` |
| **run_with_coverage** | `CoverageEngine`, `CoverageDataManager` | Read-only | Line/branch coverage data per file |
| **stream_debugger** | `StreamTraceFactory` | Read-only | Step through Java Stream pipeline. Only Java 8+ Streams |

## Implementation Rules

**Why:** Research-first approach required. Each tool interacts with IntelliJ's internal APIs which have undocumented behaviors, threading requirements, and silent failure modes.

**How to apply:**
1. Before implementing each tool, research the exact API surface — read IntelliJ source/docs for the specific classes
2. Identify all silent failure modes (listed in Notes column) and handle them with explicit error messages
3. Test on both macOS and Windows (user develops on macOS, tests on Windows)
4. All write tools need ApprovalGate integration
5. Follow existing tool pattern: `AgentTool` interface, `FunctionParameters`, `ToolResult` return
6. Add to `ToolCategoryRegistry` and `AgentService.registerTools()`
