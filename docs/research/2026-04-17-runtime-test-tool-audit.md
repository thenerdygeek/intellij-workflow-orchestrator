# Runtime / Test Tool Audit — Current State vs. IntelliJ Contract

**Date:** 2026-04-17
**Scope:** `runtime_exec`, `java_runtime_exec`, `python_runtime_exec`, `coverage` (the tools that run/observe processes and tests inside the IDE).
**Companion doc:** `2026-04-17-intellij-run-test-execution-contract.md` (the authoritative contract). This document diffs the current implementation against that contract and diagnoses the four incidents the user reported.

---

## 1. Executive summary

The runtime/test tools implement the happy path, but **lose or misclassify signal at five of the six pipeline stages** the IntelliJ execution framework exposes. The consequences are exactly the bugs observed:

| Incident | Root cause class | Fix surface |
|---|---|---|
| #1 Test compile error reported as "test failed, proceeding with TDD" | Build stage: we count compile errors but never fetch the per-file `CompilerMessage[]` — the LLM sees `"1 errors, 0 warnings"` with no file/line/message | `JavaRuntimeExecTool.executeWithNativeRunner` — switch from `ProjectTaskManager.build` to `CompilerManager.compile(...)` *or* subscribe to `CompilerTopics.COMPILATION_STATUS` and keep the `CompileContext` |
| #2 UI shows "No tests found", LLM gets "1 passed" | Result stage: two independent bugs — (a) `interpretTestRoot`'s empty-suite detection isn't reachable in the shell fallback path; (b) `executeWithShell` treats Maven exit 0 as "tests passed" without checking Surefire's `Tests run: N` line for N==0 | `JavaRuntimeExecTool.executeWithShell` + use `interpretTestRoot` in `RuntimeExecTool.get_test_results` too |
| #3 Zero-output tests pass silently | Result stage: pytest parser trusts `PASSED` regex matches with no cross-check against summary line or stdout volume; the LLM has no way to notice "heavily mocked / no-op test" | `PytestActions.parsePytestRunOutput` — also reconcile `len(tests)` against the `=== N passed ===` summary line; flag when parsed tests drift from summary |
| #4 "initialization error" on user's own run after agent | Cleanup stage: every agent run leaks — `RunContentDescriptor`s never removed, `TestResultsViewer.EventsListener` never removed, `ProcessListener` keeps continuation strong ref, and `handleDescriptorReady`'s fallback path spawns a raw `Thread` that retains the descriptor | `JavaRuntimeExecTool` — dispose descriptor + remove listeners on completion/timeout/cancel |

Additionally: the `ToolOutputSpiller` disk-overflow path **is not wired into any of the runtime tools**. They all hard-truncate at 12K characters via `truncateOutput()` regardless of session spill settings. The CLAUDE.md claim that "auto-spills large outputs (>30K chars)" is only true for `RunCommandTool`.

---

## 2. Per-incident root cause

### Incident #1 — compile error looked like a test failure

**What happened:** The LLM wrote a test with a syntax error, called `run_tests`, got back a result that it interpreted as "test failed — that's what TDD expects", and proceeded to write the implementation without fixing the test.

**Code path** (`JavaRuntimeExecTool.kt:197-402`):

1. `ProjectTaskManager.build(testModule)` is invoked.
2. A `CompilerTopics.COMPILATION_STATUS` listener is registered **via reflection** and only captures the **counts** (errors, warnings, aborted) into a String:
   ```kotlin
   compilationErrors.set("$errors errors, $warnings warnings, aborted=$aborted")
   ```
3. On `buildResult.hasErrors()`, the tool returns:
   ```
   BUILD FAILED — test execution did not start.
   Compilation result: 1 errors, 0 warnings, aborted=false
   Fix the compilation errors and try again.
   ```
4. **The actual file/line/message is never surfaced.** The `CompileContext` received by the listener holds `CompilerMessage[]` (each with `getMessage()`, `getVirtualFile()`, `getLineNumber()`, `getNavigatable()`) but we discard them.

**Why the LLM misread it:** With only a count, the message "BUILD FAILED" is ambiguous to an LLM that's expecting a red test. In TDD mode the agent's prior belief is "a failing result is expected"; without the per-message payload, it cannot distinguish "test body has `public static viod main`" from "test fails because the implementation doesn't exist yet".

**Contrast:** `compile_module` (same file, lines 793-870) does this correctly — it uses `CompilerManager.make(scope)` directly and walks `context.getMessages(CompilerMessageCategory.ERROR)` to emit per-file messages. The knowledge exists; `run_tests` just doesn't use it.

**Secondary issue:** The `isError=true` flag is set on the build-failure result, but the LLM's system prompt doesn't differentiate "compile failure before tests ran" from "tests ran and failed" in its TDD guidance. The `summary` field reads `"Build failed before tests (1 errors, 0 warnings, aborted=false)"` — an LLM skimming tool results can easily conflate this with a red test.

### Incident #2 — UI "No tests found", LLM "1 passed"

**What happened:** LLM wrote a test, called `run_tests`, IntelliJ's JUnit tab showed "No tests were found", but the tool returned a success result.

**Most likely path:** The LLM passed a `class_name` that is not a test class (or the module lookup failed in a multi-module project). The native runner fell through to `executeWithShell` (Maven). Maven ran `mvn test -Dtest=NotATest` — Surefire runs but skips (no methods match), emits `Tests run: 0, Failures: 0, Errors: 0, Skipped: 0`, and **exits 0** because the build itself succeeded.

Then in `JavaRuntimeExecTool.executeWithShell` (line 762-766):
```kotlin
val exitCode = process.exitValue()
if (exitCode == 0) {
    ToolResult("Tests PASSED for $testTarget.\n\n$truncatedOutput", "Tests PASSED: $testTarget", ...)
}
```

Result: the summary reads `Tests PASSED: com.example.NotATest`. The `truncatedOutput` contains the `Tests run: 0` line, but the LLM sees the strong "PASSED" header first.

**Alternative path (native runner):** `collectTestResults` filters by `locationUrl.startsWith("java:test://") || "java:suite://")`. When the root has only a synthetic "No tests were found" node (or is itself leaf + defect-less + has a valid-looking URL), the synthetic proxy passes the filter and `mapToTestResultEntry` returns `TestStatus.PASSED` (default branch of the `when` expression). Result: `1 passed, 0 failed`.

`interpretTestRoot` (RuntimeExecShared.kt:221) *does* handle this correctly — it checks `allTests.isNotEmpty()` first, falls through to `wasTerminated / isDefect / empty-suite` branches otherwise. But `RuntimeExecTool.executeGetTestResults` (RuntimeExecTool.kt:367-497) **does not call `interpretTestRoot`** — it duplicates the logic inline and the inline version maps 0 failures + 0 errors to `overallStatus = "PASSED"`:
```kotlin
val overallStatus = when {
    errors > 0 || failed > 0 -> "FAILED"
    else -> "PASSED"      // ← reports PASSED for empty suite
}
```

### Incident #3 — tests passed with zero output

**What happened:** Tests "passed" (3 tests, exit code 0) but the tests themselves did nothing substantial — either the LLM wrote over-mocked tests or the test body was empty. The tool reported `3 passed` and the LLM moved on.

**Code path** (`PytestActions.parsePytestRunOutput`, lines 375-409):
```kotlin
val testPattern = Regex("""^(.+::\S+)\s+(PASSED|FAILED|SKIPPED|ERROR|XFAIL|XPASS)""")
val match = testPattern.find(trimmed)
if (match != null) {
    tests.add(TestResult(match.groupValues[1], match.groupValues[2]))
    continue
}
```

The parser takes **any** line matching `::something PASSED` as a passed test. There is:
- No reconciliation against the pytest summary line (`=== 3 passed in 0.01s ===`).
- No stdout-volume check (a real test usually logs setup/fixture activity, or at least some application logs; an empty test body produces only the status line).
- No detection of "0 assertions executed" — which pytest cannot distinguish by itself, but a test that imports `pytest` and has only `pass` produces a distinct *duration* signature (<1 ms per test) that's suspicious.

**Tool-quality concern vs test-quality concern:** Detecting *semantic* test emptiness is outside the tool's remit. But the tool should at least flag "all tests under 1 ms with zero stdout" as a soft warning so the LLM can decide whether to investigate.

### Incident #4 — "initialization error" on user's manual run after agent finished

**What happened:** Some agent runs leave the IDE's test infrastructure in a state where the user's own manually-triggered tests fail with "initialization error".

**Cleanup leaks in `JavaRuntimeExecTool.executeWithNativeRunner`:**

1. `RunContentDescriptor` — the tool sets `descriptorRef.set(descriptor)` but **never** calls `ExecutionManager.getInstance(project).contentManager.removeRunContent(executor, descriptor)`. Every call accumulates a new Run tab. IntelliJ's runner uses `RunProfile` identity for reuse; since each agent call creates a **fresh** `RunnerAndConfigurationSettings` (with a fresh name like `ClassNameTest.methodName`), reuse never fires.

2. `TestResultsViewer.EventsListener` — `handleDescriptorReady` adds an `EventsListener` to `resultsViewer`. There is no `removeEventsListener` on completion, timeout, or cancellation. If the viewer is long-lived (it often is — IntelliJ caches the viewer per-project), listeners stack up.

3. `ProcessListener` retention — the `ProcessAdapter` added inside `handleDescriptorReady` captures `continuation` and `activeStreamCallback`. When the timeout fires and the outer `withTimeoutOrNull` returns null, the listener is **not removed** and keeps the coroutine continuation reachable.

4. Raw `Thread` for fallback test-tree polling (lines 468-477):
   ```kotlin
   Thread {
       for (attempt in 1..TEST_TREE_RETRY_ATTEMPTS) {
           if (!continuation.isActive) return@Thread
           if (TestConsoleUtils.findTestRoot(descriptor)?.children?.isNotEmpty() == true) break
           Thread.sleep(TEST_TREE_RETRY_INTERVAL_MS)
       }
       ...
   }.apply { isDaemon = true; name = "test-tree-finalize"; start() }
   ```
   A raw `Thread` outside IntelliJ's `ProgressManager`/coroutine infrastructure — it won't participate in shutdown, it retains `descriptor` + `continuation`. Plus the build watchdog (line 362):
   ```kotlin
   Thread {
       try { Thread.sleep(BUILD_WATCHDOG_MAX_MS) ... }
   }
   ```

5. Transient config "leakage" via `RunManager.createConfiguration()`. Even with `settings.isTemporary = true` and the deliberate avoidance of `setTemporaryConfiguration` (comment documents the regression), the `RunnerAndConfigurationSettingsImpl` returned from `createConfiguration` is **still attached** to the `RunManagerImpl`'s internal listener bus for the duration of its lifetime. Its `Disposable` is released only when the descriptor is removed — which, per leak #1, never happens. Over time, stale per-type caches in the JUnit `ConfigurationType` can return corrupt state for the user's own runs.

**Why manual runs hit "initialization error":** The JUnit runner caches fork-JVM state, classpath composition, and working-directory per `RunnerAndConfigurationSettings`. With dozens of leaked temporary settings, IntelliJ's state can degrade — especially when Spring Boot `@SpringBootTest` runs (which allocate large context caches keyed by settings identity).

---

## 3. Per-tool audit against the IntelliJ contract

The contract lists six pipeline stages and nine signals a correct wrapper must observe. Here's how each tool scores.

### 3.1 `java_runtime_exec.run_tests` (native runner path)

| Stage | Contract signal | Current behavior | Gap |
|---|---|---|---|
| 0 Resolution | `RunManager.findConfigurationByName` | N/A — we create transient config | OK |
| 0 Runner pick | `ProgramRunner.getRunner` null-check | Checks `ExecutionEnvironmentBuilder.createOrNull() == null` and surfaces reason | OK (verbose reason string) |
| 1 Validation | `checkConfiguration()` | **Not called** | Missing. `RuntimeConfigurationException` would surface invalid SDK/classpath before launch |
| 2 Before-run | `processNotStarted` + compile errors | `ProjectTaskManager.build` called explicitly; listener for `CompilerTopics.COMPILATION_STATUS` counts errors but discards messages | **Incident #1** — per-file messages never captured |
| 3 Launch | `processNotStarted` w/ExecutionException | Subscribes to `ExecutionManager.EXECUTION_TOPIC.processNotStarted` — good | OK for detection, but the message is generic (no `ExecutionException.getMessage()`) |
| 4 Process | STDOUT/STDERR separation | `ProcessAdapter.onTextAvailable` dumps `event.text` to stream without branching on `ProcessOutputType.isStderr(key)` | Missing. STDERR is merged into the stream; LLM can't see it as a separate channel |
| 4 Test events | `SMTRunnerEventsListener.TEST_STATUS` | Uses `TestResultsViewer.EventsListener.onTestingFinished` (only) | Partial. Misses `onTestingStarted`, `onTestsCountInSuite`, `onTestFailed` per-event. Can't stream progress |
| 5 Termination | `exitCode` | Not captured | Missing |
| 6 Aggregate | `interpretTestRoot` | Used via `extractNativeResults` | OK |
| 9 Cleanup | remove descriptor, dispose listeners | **Not done** | **Incident #4** — leak |

### 3.2 `java_runtime_exec.run_tests` (shell fallback path)

| Stage | Contract signal | Current behavior | Gap |
|---|---|---|---|
| All | Structured tool output | Captures `process.inputStream` line-by-line, joined into a single string; parsed only via text markers (`BUILD FAILURE`, `COMPILATION ERROR`) | **Incident #2** — no test tree. Exit code 0 with `Tests run: 0` is misreported as "Tests PASSED" |
| Build failure detection | Structured compile errors | Regex for build-failure markers in stdout | Unreliable. `compileTestJava FAILED` is a Gradle string; Maven's `COMPILATION ERROR` can fire even on test failures |
| Test failure vs test count | SMTestProxy | Not available in shell mode | A shell fallback CAN'T get the SM tree unless we parse Surefire XML (`target/surefire-reports/TEST-*.xml`) or Gradle's `build/test-results/*/TEST-*.xml`. We do not. |

### 3.3 `java_runtime_exec.compile_module`

Well-implemented relative to the contract. `CompilerManager.make(scope, callback)` is the right API; the callback returns `(aborted, errors, warnings, context)` and the tool walks `context.getMessages(CompilerMessageCategory.ERROR)` correctly. One minor gap: 120-s timeout is baked in, not configurable. Large monorepo full compiles can exceed this.

### 3.4 `python_runtime_exec.run_tests`

Delegates to `PytestActions.executePytestRun` which is a **raw ProcessBuilder subprocess** (not using IntelliJ's execution framework). That means:

| Stage | Contract signal | Current behavior | Gap |
|---|---|---|---|
| Interpreter resolution | Python `Sdk` from run config | Tries `pytest`, then `python -m pytest`, then `python3 -m pytest` on PATH | Misses PyCharm's configured interpreter. If project uses a venv that isn't activated, tests run against the wrong interpreter. Result: LLM gets a result, but not the one the user would get via the IDE runner |
| conftest.py discovery | `rootdir` computed from CWD | Uses `project.basePath` as CWD | Works in simple projects; breaks when the user has conftest.py in a subdirectory and `rootdir` inference matters |
| Collection errors | `_collect`-suffixed TeamCity test failures | Bypassed — we parse `pytest` textual output directly, not teamcity-messages | **Incident #3 adjacent** — conftest import errors come through as `ERRORS` in the summary line; but if `parsePytestRunOutput` doesn't match the `::test` pattern (because the failure is `::test_foo_collect`), it's silently dropped |
| Exit code 5 (no tests collected) | Distinct from "all passed" | `runPytestCommand` returns output if exit in `{0, 1, 2, 5}`, but `executePytestRun` then parses `tests` list which will be empty; summary shows `0 tests` | Partial — LLM gets "0 tests: 0 passed, 0 failed" but no explicit "no tests collected" message. Easy to miss |
| Pytest markers (-m) | Expression validation | Whitelist regex `SAFE_PYTEST_EXPR` | Good, prevents shell injection |
| Timeout behavior | Surface partial results | `process.destroyForcibly()` + `continue` loop through alternate interpreters | **Bug:** on timeout we don't return the partial output, we try the next interpreter, which then also probably times out |
| Empty tests / zero output | Detection | None | **Incident #3** — see below |

### 3.5 `python_runtime_exec.compile_module`

Uses `python -m py_compile` via `ProcessBuilder`. Functional but lightweight; misses PyCharm's richer `compileall` and doesn't integrate with the IDE Problems view. Acceptable as a first-order signal.

### 3.6 `coverage.run_with_coverage`

Same architecture as `java_runtime_exec` native path. Same leaks (RunContentDescriptor not removed, listeners not disposed), plus a reflective `CoverageSuiteListener` subscription that we **do** dispose (`listenerDisposable?.let { Disposer.dispose(it) }`). Better than the run_tests path on listener cleanup, still leaks descriptors.

The `CoverageSuiteListener` registration via reflection + `Proxy.newProxyInstance` is fragile — the listener interface signature is version-specific. This works today but would silently stop working on a future platform release (proxy just wouldn't fire, we'd fall back to polling).

### 3.7 `runtime_exec` (observation)

- `get_run_output`: Matches the **first** descriptor whose `displayName.contains(configName, ignoreCase=true)`. If two runs are active with overlapping names (e.g. `Test1` and `Test12`), the wrong one is returned.
- `get_test_results`: Duplicates the pass/fail computation instead of delegating to `interpretTestRoot`. Reports empty suite as `PASSED (0 passed, ...)`. Uses the Java-URL scheme filter — **will always return "0 tests" for Python test runs**.

---

## 4. Missing scenarios (contract vs. implementation)

Signals the contract lists that **no** current tool emits to the LLM:

| # | Signal | Consequence to LLM |
|---|---|---|
| 1 | `RunManager.findConfigurationByName` returned null (configName typo) | LLM retries with the same name, treats absence as "tests don't exist" |
| 2 | `ProgramRunner.getRunner` returned null (no runner for executor) | LLM thinks the tool is broken, falls back to shell |
| 3 | `RuntimeConfigurationError` from `checkConfiguration()` | LLM sees a launch error with no validation context |
| 4 | Per-file compile errors with file/line/message | **Incident #1** |
| 5 | `ExecutionException.getMessage()` (e.g. "invalid SDK", "classpath conflict") | LLM gets generic "run aborted after successful build" |
| 6 | STDERR vs STDOUT distinction | Stack trace root cause buried in mixed stream |
| 7 | Per-test events during run (not just final snapshot) | Can't stream progress, can't detect hang mid-suite |
| 8 | Empty suite (`SMRootTestProxy.isEmptySuite()`) as distinct status | **Incident #2** |
| 9 | Runner-level `initializationError` with stack trace | Surfaced generically |
| 10 | User-cancelled run (`isInterrupted()` on tests) | LLM thinks the run completed normally |
| 11 | Process destroyed mid-run vs exited normally | Exit code misleading |
| 12 | Expected vs actual for comparison failures (JUnit `AssertionFailedError.getExpected`/`getActual`) | LLM gets string representation, can't diff structurally |
| 13 | pytest collection phase failures (`_collect` suffix nodes) | Conftest errors misclassified |
| 14 | Per-test duration anomalies (all < 1 ms = likely no-op) | **Incident #3 adjacent** |
| 15 | Working directory, env vars, effective JVM args | LLM can't reproduce via shell if needed |
| 16 | "Before-run task was Make, Make succeeded" vs "no before-run task configured" | LLM can't tell if compile actually happened |

---

## 5. Architectural issues (cross-cutting)

### 5.1 Two independent result-aggregation paths

`RuntimeExecShared.interpretTestRoot` is the correct aggregation function (handles empty suite, terminated, engine defect distinctly). But:

- `JavaRuntimeExecTool.extractNativeResults` uses it → correct.
- `RuntimeExecTool.executeGetTestResults` duplicates the logic inline → **incorrect**.
- `CoverageTool.formatTestResults` has its **own third** pass/fail classifier → subtly different from the other two.

All three should go through `interpretTestRoot`. `CoverageTool.formatTestResults` additionally has `isErrorProxy()` based on string matching (`errorMessage?.startsWith("java.lang.")`) which is exactly the brittle heuristic `mapToTestResultEntry` was refactored away from (the comment says "String matching on the stacktrace is NOT used"). The refactor didn't reach `CoverageTool`.

### 5.2 Shell fallback has no structured parsing

`JavaRuntimeExecTool.executeWithShell` is a last resort when the native runner can't resolve a module. But it's a binary pass/fail based on exit code. It should parse:
- **Surefire XML** — `target/surefire-reports/TEST-*.xml` (JUnit XML schema) — authoritative test tree, works for any Maven project.
- **Gradle XML** — `build/test-results/*/TEST-*.xml` — same schema.

Both exist regardless of exit code. Parsing them post-run would give the same fidelity as the native runner and would catch incident #2 (no tests found vs. tests passed).

### 5.3 Pytest runs outside the IntelliJ execution framework

`python_runtime_exec.run_tests` bypasses the whole ExecutionManager pipeline (no `ExecutionListener`, no `SMTRunnerEventsListener`, no `RunContentDescriptor`). Benefits: no descriptor leaks (no descriptor). Drawbacks: no access to PyCharm's configured interpreter, no per-event streaming, no structured collection-error handling, no integration with the user-visible Run tool window.

PyCharm has `PyUnitTestConfigurationType` / `PyTestConfigurationType` registered as `ConfigurationType`s. The reflective approach already used in `JavaRuntimeExecTool` (`ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.find { it.id == "py.test" }`) would work for pytest too — it'd give us the same native integration as Java.

### 5.4 `ToolOutputSpiller` is not wired into runtime tools

`ToolOutputSpiller` (30K threshold, writes preview + file path) is used only by `RunCommandTool`. Runtime tools all route through `truncateOutput(content, 12000)`:

- `RuntimeExecTool.get_run_output` → `truncateOutput(content, RUN_OUTPUT_TOKEN_CAP_CHARS)` (12K)
- `RuntimeExecTool.get_test_results` → `truncateOutput(content, TEST_RESULTS_TOKEN_CAP_CHARS)` (12K)
- `RuntimeExecShared.formatStructuredResults` → `truncateOutput(content, RUN_TESTS_TOKEN_CAP_CHARS)` (12K)
- `JavaRuntimeExecTool.executeWithShell` → `truncateOutput(rawOutput, RUN_TESTS_MAX_OUTPUT_CHARS)` (12K)
- `PytestActions.executePytestRun` → `truncateOutput(results.failureOutput, 5000)` (5K per failure block)

**A 500-failure JUnit run produces 80K of stack traces — all but the first 7K (60%) + last 5K (40%) are discarded, and the spiller is never consulted.** The LLM cannot use `read_file` to recover the full output because it was never saved.

### 5.5 No structured `ToolResult.data` — everything is string

The CLAUDE.md emphasizes the ToolResult<T> pattern for services (typed `.data`, string `.summary`). Runtime tools return only strings. The LLM can't programmatically read `tests[3].stacktrace` — it has to regex the prose format. Every formatting decision we make is locked in. For a tool with a complex result shape (test results), a structured JSON payload in addition to the prose `.content` would let the LLM query specific failures.

### 5.6 No per-tool unit tests against a fake test tree

The `SMTestProxy` / `SMRootTestProxy` classes are testable — you can construct a fake tree, feed it to `interpretTestRoot`, and assert the ToolResult. I didn't find tests that cover the failure matrix (empty suite, engine defect, mixed pass/fail, terminated, comparison failure). Without these tests, the regression from the "1 passed for empty suite" path will recur.

---

## 6. Output-spilling confirmation

You asked: *"we have proper output handling architecture right where overflows to disk?"*

**Partial.** The architecture exists (`ToolOutputSpiller` at `agent/tools/ToolOutputSpiller.kt`, with 30K threshold and session-dir spill). It is wired into `RunCommandTool` (via `ToolOutputConfig` + `OutputCollector`). It is **not wired** into:

- `runtime_exec` (any action)
- `java_runtime_exec.run_tests` (native or shell)
- `java_runtime_exec.compile_module`
- `python_runtime_exec.run_tests` (delegates to `PytestActions`, which uses its own `truncateOutput`)
- `python_runtime_exec.compile_module`
- `coverage.run_with_coverage`
- `coverage.get_file_coverage`

All of those hard-truncate at 12K/5K before the spiller can see the content. Fix: either change their truncation to go through `ToolOutputConfig.applyGrep` + `ToolOutputSpiller.spill`, or (simpler) raise their cap to 30K and let `ToolOutputSpiller` decide.

---

## 7. Recommendations (not a fix plan — just the surface area)

Grouped by the user-visible outcome each would fix.

### Incident #1 (compile error invisible)

- **R1.** In `executeWithNativeRunner`, replace the reflective `CompilerTopics.COMPILATION_STATUS` string-accumulator with a real `CompilationStatusListener` that stores the full `CompileContext`. After `buildResult.hasErrors()`, walk `context.getMessages(CompilerMessageCategory.ERROR)` and format per-file messages (reuse the code from `compile_module`).
- **R2.** Distinguish the build-failure `ToolResult` more aggressively in `summary` — e.g. `summary = "COMPILE FAILED: <file>:<line> <message>"` rather than counts-only — so the LLM's skim-read doesn't conflate it with test red.
- **R3.** Add a system-prompt rule: "BUILD FAILED before tests is an agent error, not a test failure — fix the compile first."

### Incident #2 (empty suite → passed)

- **R4.** Route `RuntimeExecTool.executeGetTestResults` through `interpretTestRoot` — delete the duplicate inline logic.
- **R5.** Unify `CoverageTool.formatTestResults` with `interpretTestRoot` (remove the string-based `isErrorProxy` heuristic).
- **R6.** In `executeWithShell`, parse Surefire/Gradle XML reports after the process exits. Fall back to "exit code 0 → parse XML → if no XML and no test lines, report NO_TESTS_FOUND".

### Incident #3 (zero-output pass)

- **R7.** In `parsePytestRunOutput`, cross-check `tests.size` against the summary line (`=== N passed in X.XXs ===`). If they disagree, flag `PARSE_MISMATCH` and include raw output.
- **R8.** When all parsed tests have duration < 1 ms and total stdout lines < parsedTests * 3, prepend a soft warning: `"[NOTE] All tests passed in near-zero time with minimal stdout — verify tests actually exercised the code under test."`
- **R9.** Switch pytest invocation to use the teamcity-messages plugin (pass `-p teamcity.pytest_plugin`), parse the structured events instead of text. This also catches `_collect`-suffixed collection errors correctly.

### Incident #4 (IDE state pollution)

- **R10.** In `executeWithNativeRunner` + `CoverageTool.run_with_coverage`: wrap the whole run in a `Disposable disposable = Disposer.newDisposable("agent-run-${uuid}")`. Add descriptor disposal + listener removal as a child Disposable. Dispose on success, timeout, cancellation, AND on any error path.
- **R11.** Remove the `RunContentDescriptor` from `RunContentManager` in a `finally`: `ExecutionManager.getInstance(project).contentManager.removeRunContent(executor, descriptor)`.
- **R12.** Replace the two raw `Thread` spawns (`test-tree-finalize`, `build-watchdog-timeout`) with coroutines tied to a `CoroutineScope(SupervisorJob())` tied to the agent session Disposable.

### Cross-cutting

- **R13.** Wire `ToolOutputSpiller` into all runtime tools. Simplest: bump their truncation cap to 30K and route the content through `ToolOutputSpiller.spill(toolName, content)` before `ToolResult(...)`.
- **R14.** Add an optional `.data` JSON payload to `ToolResult` for test results. The LLM can opt in via a `format=json` parameter or we always include it for tool results > 500 chars.
- **R15.** Add unit tests that construct fake `SMTestProxy` trees for every failure-matrix scenario (empty suite, engine defect, mixed pass/fail, terminated, comparison failure, Python `_collect` suffix) and assert the formatted output. This is the first thing future changes will break if not guarded.
- **R16.** Consider a single `RunInvocation` abstraction with stages as first-class objects (`ResolutionStage`, `ValidationStage`, `BeforeRunStage`, `LaunchStage`, `RunStage`, `ResultStage`) — each returns a typed `Result<T>` with success + structured error. Current code has the stages tangled in a 400-line function. This is the refactor that would make R1–R12 cheap.

---

## 8. Not covered by this audit (worth follow-up)

- **Debug tools** (`debug_breakpoints`, `debug_step`, `debug_inspect`): I read only the structure. Likely similar leak patterns (XDebugSession listeners, `MutableSharedFlow(replay=1)` references).
- **`RunInspectionsTool` / `ProblemViewTool`**: Similar contract to `CompilerMessage[]` — per-message structured output vs. count-only.
- **Framework tools** (`spring`, `django`, `fastapi`, `flask`): File-scan-based; no runtime execution, so not affected by this audit.
- **`DbQueryTool`**: Runs SQL via the Database plugin — has its own "query result truncation" logic that should also use the spiller.

These should get a separate audit pass.
