# Runtime Exec Tool — IDE-Aware Split

**Status:** approved, ready to implement
**Date:** 2026-04-14
**Branch:** `feature/tooling-architecture-enhancements`

## Problem

`RuntimeExecTool` (`agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt`) exposes five actions (`get_running_processes`, `get_run_output`, `get_test_results`, `run_tests`, `compile_module`) and registers as a single "universal" tool in `AgentService` (L499-501):

```kotlin
// RuntimeExec and RuntimeConfig are universal (work in any IDE)
safeRegisterDeferred("Build & Run") { RuntimeExecTool() }
```

That comment is wrong. Two of the five actions are Java-specific in every relevant way:

- `run_tests` → `createJUnitRunSettings` is hardcoded to `ConfigurationType.id == "JUnit"`/`"TestNG"`, `findModuleForClass` uses `JavaPsiFacade.findClass`, shell fallback only detects Maven/Gradle.
- `compile_module` → `CompilerManager` is the Java/Kotlin AOT compiler. It has no Python analogue.

On PyCharm these actions fail silently, masquerading as failures with misleading messages. Separately, the Java `run_tests` path itself reports "test run: 1 passed, 0 failed" when JUnit's root node carries an "Internal Error" state and synthetic engine leaves get classified as PASSED.

The `ToolRegistrationFilter` object in `ide/IdeContext.kt` already distinguishes Java-capable IDEs (`shouldRegisterJavaBuildTools`) from Python-capable IDEs (`shouldRegisterPythonBuildTools`). `RuntimeExecTool` simply bypasses it.

## Decision

Split `RuntimeExecTool` into three tools along the capability axis of `ToolRegistrationFilter`:

| New tool | Actions | Registration gate |
|---|---|---|
| `runtime_exec` (trimmed) | `get_running_processes`, `get_run_output`, `get_test_results` | always (universal observation) |
| `java_runtime_exec` | `run_tests`, `compile_module` | `shouldRegisterJavaBuildTools(ideContext)` |
| `python_runtime_exec` | `run_tests`, `compile_module` | `shouldRegisterPythonBuildTools(ideContext)` |

Both `java_runtime_exec` and `python_runtime_exec` expose the same two action names. The LLM picks the tool appropriate to the IDE based on which one was registered (the other is simply not visible). Actions are named identically so the agent's mental model stays simple: "to run tests, call the runtime_exec tool my IDE provides."

Also fix the "misleading PASSED" Java bug while the file is being touched — the code being moved is the same code that needs the fix.

## New files

### `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecShared.kt`

Package-private helpers extracted from `RuntimeExecTool`:

- `TestStatus` enum (PASSED, FAILED, ERROR, SKIPPED)
- `TestResultEntry` data class
- `ProcessEntry` data class (if present)
- Stream callback resolver
- `truncateOutput` imports/re-exports (already exists in `tools/` — just keep using it)
- `formatDuration(ms: Long): String`
- `formatStructuredResults(allTests, runName, tokenCap): ToolResult`
- `collectTestResults(root: SMTestProxy): List<TestResultEntry>` — **fixed to filter synthetic engine leaves** (require `locationUrl?.startsWith("java:test://") == true` OR be a real Python test function locator; reject nodes with null/engine-error locations)
- `mapToTestResultEntry(proxy: SMTestProxy): TestResultEntry` — **fixed to honor `proxy.wasTerminated()` as ERROR**
- Constants: `MAX_STACK_FRAMES`, `MAX_PASSED_SHOWN`, `RUN_TESTS_DEFAULT_TIMEOUT`, `RUN_TESTS_MAX_TIMEOUT`, etc.

### `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt`

Class `JavaRuntimeExecTool : AgentTool`.
- `name = "java_runtime_exec"`
- Description explicitly mentions Java/Kotlin + JUnit/TestNG + Maven/Gradle.
- `execute()` dispatches `run_tests` → `executeRunTests`, `compile_module` → `executeCompileModule`, everything else → error "unknown action in java_runtime_exec".
- Copies the existing JUnit native-runner code (`executeWithNativeRunner`, `executeWithShell`, `handleDescriptorReady`, `createJUnitRunSettings`, `detectTestFramework`, `findModuleForClass`, `extractNativeResults`) verbatim, with the misleading-PASSED bug fix applied:
  - In `onTestingFinished`: before formatting results, check `root.isDefect || root.wasTerminated() || root.isEmptySuite`. If any is true, build a failure `ToolResult` with the root's `errorMessage` / `stacktrace` (joined) as content, `isError = true`, summary "Test runner error (no tests executed)".
  - In the "No tests found" fallback branch (after `allTests.isEmpty()`): flip `isError = true` and summary to `"No tests executed — check class name or runner error"`.
  - `extractNativeResults` gets the same root-health check before formatting.
- Copies `executeCompileModule` verbatim (CompilerManager, createModuleCompileScope, etc.).

### `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/PythonRuntimeExecTool.kt`

Class `PythonRuntimeExecTool : AgentTool`.
- `name = "python_runtime_exec"`
- Description mentions pytest / unittest + optional `python -m py_compile`.
- `execute()` dispatches `run_tests` → `executePytestRun` (reuses `tools/framework/build/PytestActions.executePytestRun` with translated params: maps `class_name` → `path`, `method` → `pattern`). Honor `timeout` from input (clamped to RUN_TESTS_MAX_TIMEOUT from shared).
- `compile_module` → builds a `python -m py_compile` invocation over the specified module directory (or project base if omitted). Uses the same `ProcessBuilder`/streaming pattern as the Java shell fallback, but no Maven/Gradle. Returns structured pass/fail based on exit code and stderr content (SyntaxError detection).
- Parameters:
  - `class_name` (optional) — reinterpreted as a pytest path or node id. Description explicitly says so.
  - `method` (optional) — pytest `-k` pattern.
  - `markers` (optional) — pytest `-m` expression.
  - `timeout`, `description`, `module` — same as Java.
- Does NOT share a parameters block with the Java tool — different description text and different semantics for `class_name`.

### `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt` (rewritten, trimmed)

- Keeps `name = "runtime_exec"`.
- Description lists only the three universal actions.
- `execute()` dispatches only those three. `run_tests` / `compile_module` return an error pointing the LLM at the IDE-specific tool:
  - "Action 'run_tests' is handled by java_runtime_exec (on IntelliJ) or python_runtime_exec (on PyCharm). This tool only provides process observation."
- Keeps all the `get_*` implementations and their constants.

## Changes

### `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` L499-501

Replace:

```kotlin
// RuntimeExec and RuntimeConfig are universal (work in any IDE)
safeRegisterDeferred("Build & Run") { RuntimeExecTool() }
safeRegisterDeferred("Build & Run") { RuntimeConfigTool() }
```

with:

```kotlin
// Universal process observation (no compile or test runner)
safeRegisterDeferred("Build & Run") { RuntimeExecTool() }
safeRegisterDeferred("Build & Run") { RuntimeConfigTool() }

// Java/Kotlin native test runner + Java compiler
if (ToolRegistrationFilter.shouldRegisterJavaBuildTools(ideContext)) {
    safeRegisterDeferred("Build & Run") { JavaRuntimeExecTool() }
}

// Python pytest runner + py_compile
if (ToolRegistrationFilter.shouldRegisterPythonBuildTools(ideContext)) {
    safeRegisterDeferred("Build & Run") { PythonRuntimeExecTool() }
}
```

`RuntimeConfigTool` stays universal — it operates on IntelliJ's `RunManager`, which exists in every JetBrains IDE.

## Tests

### New

- `JavaRuntimeExecToolTest.kt` — move the existing JUnit-runner tests from `RuntimeExecToolTest` and add:
  - `run_tests returns error when root is defect with zero leaves` — mocks `SMTestProxy` root with `isDefect=true`, empty children, returns error ToolResult with `isError=true`, summary starts with "Test runner error".
  - `run_tests returns error when root wasTerminated` — same shape.
  - `run_tests filters synthetic engine leaves` — root with one real leaf (`locationUrl="java:test://..."`) and one framework leaf (`locationUrl=null` or `java:engine://`) returns a count of 1, not 2.
  - `No tests found branch flips isError to true` — previously `false`.
- `PythonRuntimeExecToolTest.kt` — covers pytest dispatch shape (can mock `PytestActions` or test via captured ProcessBuilder arguments) and py_compile.
- `RuntimeExecToolTest.kt` — slim down to universal actions; add a test `run_tests_returns_routing_error` verifying the stub points at java/python tools.

### Updated

- Any test that asserted "No tests found" → not an error should flip to `isError=true`.

## Prompt snapshots

Regenerate all 7 snapshots under `agent/src/test/resources/prompt-snapshots/` — the tool catalog line will now show `runtime_exec`, `java_runtime_exec` (IntelliJ variants), `python_runtime_exec` (PyCharm variants), or both (mixed).

Per `agent/CLAUDE.md`, regenerate with:
```
./gradlew :agent:test --tests "*generate all golden snapshots*"
./gradlew :agent:test --tests "*SNAPSHOT*"
```

## Docs

Update `agent/CLAUDE.md`:
- Meta-tool table: `runtime_exec` action count drops to 3; add `java_runtime_exec` (2 actions) and `python_runtime_exec` (2 actions).
- Core Tools table is unchanged (none of these are core).
- Deferred Tools → Build & Run row adds both new tools.
- The description of `ToolRegistrationFilter` adds these two new filtered categories in plain prose.

## Verification checklist

- [ ] `./gradlew :agent:test` green
- [ ] `./gradlew :agent:test --tests "*SNAPSHOT*"` green after regeneration
- [ ] `./gradlew verifyPlugin` green
- [ ] Grep confirms the "universal" misleading comment is gone from AgentService
- [ ] `runtime_exec` description no longer lists `run_tests` or `compile_module`
- [ ] `JavaRuntimeExecTool` contains the three root-health checks (isDefect, wasTerminated, isEmptySuite)
- [ ] `collectTestResults` rejects leaves with non-test `locationUrl`
- [ ] "No tests found" ToolResult has `isError = true`
