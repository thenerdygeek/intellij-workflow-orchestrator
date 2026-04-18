# IntelliJ Run / Test Execution Contract — Research Report

**Date:** 2026-04-17
**Purpose:** Authoritative reference for an AI-agent tool that programmatically invokes IntelliJ run configurations. Derived from the JetBrains SDK documentation, the `intellij-community` source tree (Apache 2.0), and the unofficial community API docs. Every interface/method named below is backed by a cited source — nothing below is invented.

## Primary Sources

- Execution overview — https://plugins.jetbrains.com/docs/intellij/execution.html
- Run Configurations — https://plugins.jetbrains.com/docs/intellij/run-configurations.html
- Threading Model — https://plugins.jetbrains.com/docs/intellij/threading-model.html
- `ExecutionListener` — https://dploeger.github.io/intellij-api-doc/com/intellij/execution/ExecutionListener.html
- `RunContentDescriptor` — https://dploeger.github.io/intellij-api-doc/com/intellij/execution/ui/RunContentDescriptor.html
- `CompileStepBeforeRun` (source) — https://github.com/JetBrains/intellij-community/blob/master/java/execution/impl/src/com/intellij/compiler/options/CompileStepBeforeRun.java
- `MakeProjectStepBeforeRun` (source) — https://github.com/JetBrains/intellij-community/blob/master/java/execution/impl/src/com/intellij/compiler/options/MakeProjectStepBeforeRun.java
- `ProgramRunnerUtil` (source) — https://github.com/JetBrains/intellij-community/blob/master/platform/execution-impl/src/com/intellij/execution/ProgramRunnerUtil.java
- `SMTRunnerEventsListener` (source) — https://github.com/JetBrains/intellij-community/blob/master/platform/smRunner/src/com/intellij/execution/testframework/sm/runner/SMTRunnerEventsListener.java
- `SMTestProxy` (source) — https://github.com/JetBrains/intellij-community/blob/master/platform/smRunner/src/com/intellij/execution/testframework/sm/runner/SMTestProxy.java
- TeamCity service messages — https://www.jetbrains.com/help/teamcity/service-messages.html
- PyCharm pytest plugin — https://github.com/JetBrains/intellij-community/blob/master/python/helpers/pycharm/teamcity/pytest_plugin.py
- `ProcessHandler` (source) — https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/execution/process/ProcessHandler.java
- `ProcessOutputTypes` (source) — https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/execution/process/ProcessOutputTypes.java

---

## 1. Pipeline Stages — What Happens, In Order

The JetBrains execution framework is a pipeline of six distinct stages. Each stage can fail in a different way and emits a different signal. A wrapper tool that only observes stage 5 ("process exited") will routinely report success when earlier stages silently dropped the launch.

### Stage 0 — Runner selection

Inputs: a `RunProfile` (usually a `RunConfiguration`) and an `Executor` (typically `DefaultRunExecutor.EXECUTOR_ID = "Run"` or `DefaultDebugExecutor.EXECUTOR_ID = "Debug"`; `CoverageExecutor` for coverage).

`ProgramRunner.getRunner(executorId, runProfile)` iterates registered `com.intellij.programRunner` extensions and returns the first whose `canRun(executorId, runProfile)` is true. If no runner matches, **the launch is abandoned silently** — no `ExecutionListener` callback fires at all. The tool must treat "no runner found" as a hard error it raises itself.

### Stage 1 — `ExecutionEnvironment` construction

`ExecutionEnvironmentBuilder.create(executor, settings).build()` produces an `ExecutionEnvironment` that aggregates: the chosen `ProgramRunner`, the `Executor`, the `RunProfile`, any `ExecutionTarget`, the data context, and the content to reuse. The environment is then passed to `ProgramRunner.execute(environment)` or to `ExecutionManager.getInstance(project).restartRunProfile(environment)`.

`ProgramRunnerUtil.executeConfiguration(settings, executor)` is the standard high-level entry point. It internally builds the environment and calls `executeConfigurationAsync(...)`. Its signature returns `void`; **errors do not bubble to the caller** — they are funneled through `ExecutionUtil.handleExecutionError()` which posts a balloon/notification in the Run tool window. This is a major source of "wrapper tool reports success while nothing ran" bugs.

### Stage 2 — Before-run tasks (synchronous, gating)

After environment construction but before the process is launched, the framework walks `RunManager.getBeforeRunTasks(configuration)` and invokes each `BeforeRunTaskProvider.executeTask(...)` **serially, synchronously**. Each returns a `boolean`: `true` = continue, `false` = abort the launch.

Two default providers ship with the JVM plugins:

- `CompileStepBeforeRun` — `Key<MakeBeforeRunTask> ID = Key.create("Make")`. `executeTask(...)` delegates to `CompileStepBeforeRun.doMake(project, configuration, env, false)`. Internally it builds a `ProjectTask` scoped to the configuration's modules (or the whole project) and runs it through `ProjectTaskManager`. It then inspects the result:
  ```java
  if ((!taskResult.hasErrors() || ignoreErrors) && !taskResult.isAborted())
    result.set(true);
  ```
  If compilation errors exist (and `ignoreErrors` is false), or the task was aborted, `doMake` returns `false`. Run execution is cancelled. The errors are surfaced via the normal Problems/Build tool windows, **not** as part of the later process output stream.
- `MakeProjectStepBeforeRun` — always builds the whole project. Its `executeTask` is literally `CompileStepBeforeRun.doMake(myProject, configuration, env, false, true)`.

**Failure semantics:** When any before-run task returns `false`, the framework fires `ExecutionListener.processNotStarted(executorId, env)` (see stage 3) and stops. **No process is ever launched.** The Run tool window typically shows no new tab; if one exists from a previous run it is left alone.

To intercept compile errors as structured data, subscribe to the Build tool window / `CompilerManager` event stream; `CompilerManager.make(...)` accepts a `CompileStatusNotification` callback with parameters `(aborted, errors, warnings, compileContext)` where `CompileContext.getMessages(CompilerMessageCategory.ERROR)` returns `CompilerMessage[]` (each has `getMessage()`, `getVirtualFile()`, `getLineNumber()`, `getColumnNumber()`).

### Stage 3 — `ExecutionListener` fan-out

The lifecycle topic is `ExecutionManager.EXECUTION_TOPIC` (a project-level `Topic<ExecutionListener>`). Subscribers implement any subset of the following default methods; every one is invoked on the EDT:

- `processStartScheduled(String executorId, ExecutionEnvironment env)` — environment accepted, launch queued.
- `processStarting(String executorId, ExecutionEnvironment env)` — called **after** before-run tasks have all succeeded, immediately before the OS process is spawned.
- `processNotStarted(String executorId, ExecutionEnvironment env)` — terminal failure before `processStarted` ever fires. Triggered by:
  - A before-run task returning `false` (most commonly: compile errors, Make aborted).
  - `RunProfileState.execute(...)` throwing `ExecutionException` (classpath resolution failure, missing SDK, PTY allocation failure, etc.).
  - User cancellation while the launch was queued.
- `processStarted(String executorId, ExecutionEnvironment env, ProcessHandler handler)` — the OS process is alive and `ProcessHandler.startNotify()` has been called.
- `processTerminating(String executorId, ExecutionEnvironment env, ProcessHandler handler)` — destroy/detach requested; process not yet reaped.
- `processTerminated(String executorId, ExecutionEnvironment env, ProcessHandler handler, int exitCode)` — process reaped. `exitCode` is the real OS exit code (or a synthetic one for detached / killed processes).

**Critical semantic distinction:** `processNotStarted` is fundamentally different from `processTerminated` with a non-zero exit code. The former means no OS process ever ran; the latter means it ran and exited. A tool that only listens for `processTerminated` will miss every compile-failure, every missing-SDK failure, every before-run-abort — it will simply hang forever waiting for a terminated event that will never come.

### Stage 4 — `RunProfileState.execute(...)` and process attachment

The `RunProfileState.execute(executor, runner)` call is what actually starts the child process. Common base classes:

- `CommandLineState` — for generic OS processes. Produces a `GeneralCommandLine`, spawns it via `OSProcessHandler`, wires a `ConsoleView` via `TextConsoleBuilderFactory`, returns a `DefaultExecutionResult(console, handler)`.
- `JavaCommandLineState` — JVM flavour; manages classpath, JVM params.
- `PythonCommandLineState` (PyCharm) — adds PyCharm helpers to the command line, injects `teamcity-messages` plugin.

The returned `ExecutionResult` carries an `ExecutionConsole` and a `ProcessHandler`. Handler lifecycle (from `ProcessHandler.java`):

- `startNotify()` — arms the output pumps; must be called **exactly once**.
- State machine: `INITIAL → RUNNING → TERMINATING → TERMINATED` (atomic). `isProcessTerminated()` and `isStartNotified()` let a caller probe state.
- `addProcessListener(ProcessListener, Disposable)` — callback surface:
  - `startNotified(ProcessEvent)`
  - `onTextAvailable(ProcessEvent, Key outputType)`
  - `processWillTerminate(ProcessEvent, boolean willBeDestroyed)`
  - `processTerminated(ProcessEvent)` — `event.getExitCode()` holds the OS exit code.

`outputType` is one of the keys defined on `ProcessOutputType` (the non-deprecated replacement for `ProcessOutputTypes`). The three base streams are `STDOUT`, `STDERR`, `SYSTEM`. Colored output wraps these; equality checks must go through `ProcessOutputType.isStdout(key)` / `.isStderr(key)` rather than `==`, because ANSI-wrapped keys are distinct object instances.

### Stage 5 — `RunContentDescriptor` & tool-window tab

`RunContentBuilder` (invoked inside `ProgramRunner.execute`) wraps the `ExecutionResult` into a `RunContentDescriptor` and adds it to the Run or Debug tool window via `ExecutionManager.getInstance(project).getContentManager().showRunContent(executor, descriptor)`. The descriptor holds:

- `ProcessHandler` (`getProcessHandler()`).
- `ExecutionConsole` (`getExecutionConsole()`).
- The `JComponent` displayed in the tab (`getComponent()`).
- Optional restart actions, icon, activation callback.

It implements `Disposable`; disposal is usually driven by `RunContentManager.removeRunContent(executor, descriptor)` rather than by the caller. Not removing descriptors manually is normally fine — IntelliJ reuses tabs by `RunProfile` identity and caps the stack. Tools that bypass `RunContentBuilder` and attach `RunContentDescriptor`s directly are the common source of leaked tabs and orphaned `ProcessHandler`s.

---

## 2. The Correct "Run Test" Contract for a Wrapper Tool

An agent-callable tool that invokes a run configuration and reports results to an LLM **must** observe and surface every one of the following signals; an LLM cannot reason correctly about a test run without them.

1. **Resolution** — Did `RunManager.getInstance(project).findConfigurationByName(name)` or `findConfigurationByTypeAndName(type, name)` actually find the config? Report "configuration not found" distinctly from "tests failed".
2. **Runner match** — Did `ProgramRunner.getRunner(executorId, runProfile)` return non-null? Report "no runner registered for executor X" distinctly from a runtime failure.
3. **Validation** — Did `RunConfiguration.checkConfiguration()` pass? It throws `RuntimeConfigurationError` (fatal), `RuntimeConfigurationWarning` (non-blocking), or `RuntimeConfigurationException` (non-fatal). Surface errors verbatim.
4. **Before-run gating** — Register an `ExecutionListener` *before* calling `executeConfiguration` and observe `processNotStarted`. If it fires without a prior `processStarted`, the launch was cancelled by a before-run task (nearly always a compile failure on JVM configs). Fetch the latest errors from the Problems view / `WolfTheProblemSolver` or subscribe to `CompilerTopics.COMPILATION_STATUS` for structured data.
5. **Process launch** — Observe `processStarted(..., handler)` and store the `ProcessHandler`. Add a `ProcessListener` to capture stdout/stderr/system streams separately (branch on `ProcessOutputType.isStdout/isStderr`).
6. **Test-event attachment** — For SM-based test frameworks, also subscribe to `SMTRunnerEventsListener.TEST_STATUS` (project-level topic). The key callbacks are: `onTestingStarted(SMRootTestProxy)`, `onTestsReporterAttached` (implicit — `onSuiteTreeStarted` / `onSuiteTreeNodeAdded` mark that the reporter has started streaming), `onTestStarted`, `onTestFinished`, `onTestFailed`, `onTestIgnored`, `onSuiteStarted`, `onSuiteFinished`, and finally `onTestingFinished(SMRootTestProxy)` — this is the definitive "tests are done streaming" signal.
7. **Process termination** — Observe `processTerminated(..., exitCode)`. `exitCode == 0` does **not** mean all tests passed — pytest returns 0 when no tests collected is configured as success, JUnit runners return 0 even with failures depending on wrapper, etc. Always prefer the SM tree.
8. **Result extraction** — After `onTestingFinished`, walk the `SMRootTestProxy`:
   - `getAllTests()` returns every node (root + suites + tests).
   - Per node: `isPassed()`, `isDefect()`, `isIgnored()`, `isInterrupted()`, `isLeaf()`, `getErrorMessage()`, `getStacktrace()`, `getDuration()`, `getName()`, `getLocationUrl()`.
   - Root has `isEmptySuite()` / cached `myIsEmpty` — this is how "no tests were found" should be surfaced separately from "all tests passed".
9. **Cleanup** — The wrapper should not hold a strong reference to `RunContentDescriptor` after reading results. If it created any transient subscriptions, it should dispose them using the `Disposable` tied to the descriptor.

A minimal correct report to the LLM therefore has these fields:

```
status: NOT_FOUND | NO_RUNNER | INVALID_CONFIG | BEFORE_RUN_FAILED | LAUNCH_FAILED | NO_TESTS | ALL_PASSED | SOME_FAILED | ERRORED | CANCELLED | TIMED_OUT
exitCode: int | null               // null if process never started
beforeRunErrors: [ {file, line, message} ]   // present iff BEFORE_RUN_FAILED
tests: {
  total, passed, failed, ignored, errored, skipped,
  durationMs,
  failures: [ {name, locationUrl, errorMessage, stacktrace, expected?, actual?} ]
}
stdoutTail: string  // last N KB of STDOUT only
stderrTail: string  // last N KB of STDERR only
```

---

## 3. Common Bugs & Anti-Patterns

1. **Only listening to `processTerminated`.** You will miss every compile-gate failure because `processNotStarted` fires instead. Symptom: tool reports "run finished, 0 tests executed" when reality is "build failed, nothing ran." Fix: always subscribe to `processNotStarted` as a distinct failure branch.
2. **Treating `exitCode == 0` as success.** pytest exits 0 for `--exitfirst` with no collection; JUnit platform launchers can be wrapped to swallow failures. The SM test tree is authoritative. Fix: always use `SMRootTestProxy.isEmptySuite()` + `getAllTests().filter { it.isDefect }` rather than exit code.
3. **Calling `ProgramRunner.execute()` from a background thread.** `RunContentBuilder` touches Swing. The platform threading-model doc states writes and UI mutations must happen on EDT. Fix: wrap the launch in `ApplicationManager.getApplication().invokeLater {}` (or use `ProgramRunnerUtil.executeConfiguration`, which already dispatches correctly). Observing events back is fine on any thread if the listener itself does not touch UI.
4. **Ignoring `RuntimeConfigurationError` from `checkConfiguration()`.** The platform will still attempt to execute, but misconfigured runs often produce confusing `processNotStarted` events with opaque causes. Fix: always call `checkConfiguration()` explicitly and treat exceptions as a distinct status before invoking execute.
5. **Not subscribing to `SMTRunnerEventsListener.TEST_STATUS` early enough.** The topic is project-level; if you subscribe after `processStarted`, you may miss `onTestingStarted`. Fix: subscribe inside `processStarting` (or immediately when your tool receives the launch request).
6. **Collecting output only from `ConsoleView`.** `ConsoleView` mangles TeamCity service messages (they are filtered out of the visible stream). The raw `ProcessHandler.addProcessListener(onTextAvailable)` still sees them but the `##teamcity[...]` lines are consumed by the SM parser. Fix: do not try to parse TeamCity messages yourself; trust `SMTRunnerEventsListener`.
7. **Merging STDOUT and STDERR.** The `onTextAvailable` callback gives you a `Key outputType`; branch on `ProcessOutputType.isStderr(key)`. An LLM needs STDERR as a separate channel (it usually contains the stack trace root-cause line).
8. **Leaving descriptors in the content manager.** Every leaked `RunContentDescriptor` keeps its `ProcessHandler`, `ConsoleView`, and the whole `JComponent` tree alive. For short-lived tool invocations, remove via `ExecutionManager.getInstance(project).getContentManager().removeRunContent(executor, descriptor)` after reading results.
9. **Assuming `onTestingFinished` fires on test runs with zero tests.** It does (SM always closes the root), but `onTestsCountInSuite(0)` may precede it and `SMRootTestProxy.isEmptySuite()` will be true. A wrapper that only looks at "any tests failed?" will claim success on an empty run.
10. **Building an `ExecutionEnvironment` without a `dataContext`.** Some before-run providers read from the data context (e.g. current editor file) and abort silently if missing. Fix: use `ExecutionEnvironmentBuilder.create(executor, settings).activeTarget().build()` and, when running from a background action, pass `SimpleDataContext.getProjectContext(project)`.

---

## 4. Per-Stage Data the LLM Needs

| Stage | Signal | Data fields the LLM needs |
|---|---|---|
| 0 Resolution | `RunManager.findConfigurationByName` returned null | `configName`, list of known names (did-you-mean) |
| 0 Runner selection | `ProgramRunner.getRunner` returned null | `executorId`, `runProfileClassName` |
| 1 Validation | `checkConfiguration()` throw | `RuntimeConfigurationException.getMessage()`, severity |
| 2 Before-run | `processNotStarted` after Make | compile errors as `[{file, line, column, message, severity}]` from `CompilerManager` / Problems view |
| 3 Launch fail | `processNotStarted` with `ExecutionException` | the exception message (classpath, missing SDK, etc.) |
| 4 Running | `onTextAvailable` stream | STDOUT and STDERR tails, separately |
| 4 Test events | `SMTRunnerEventsListener` callbacks | per-test: name, locationUrl, status, duration, errorMessage, stacktrace; for comparison failures: `expected`, `actual` |
| 5 Termination | `processTerminated(exitCode)` | raw `exitCode`, termination reason (normal vs `destroyProcess`) |
| 6 Aggregate | Traversal of `SMRootTestProxy` | `total`, `passed`, `failed`, `ignored`, `errored`, `durationMs`, `isEmptySuite` |

A single "error message" string is never enough. JUnit's "Initialization error" node in the tree (a child of the suite with `isDefect() == true` and `getName() == "initializationError"`) typically means: the test class couldn't be reflectively constructed (usually a missing JUnit runner on the classpath, a `@BeforeClass` throwing, or a dependency version mismatch). Surface the node's `getStacktrace()` — the first non-framework frame is the real clue.

---

## 5. PyCharm / Python-Specific Wrinkles

### 5.1 pytest runner invocation

`_jb_pytest_runner.py` (in `python/helpers/pycharm/`) is the entry point. It:

1. Parses PyCharm-passed args into pytest args (using `::` separator for selection).
2. Loads the `teamcity-messages` plugin (`teamcity.pytest_plugin.Plugin`). The plugin is shipped as a PyPI package; PyCharm prepends the bundled version to `sys.path` so the user's env doesn't need it installed. When the user **does** have a different version installed, the "plugin already registered" check applies.
3. Calls `pytest.main(args, plugins_to_load + [Plugin])` and returns whatever pytest returns.

### 5.2 pytest exit codes (honored by the IDE only indirectly)

The pytest process exit codes are:

| Code | Meaning |
|---|---|
| 0 | All collected tests passed |
| 1 | Some tests failed |
| 2 | Test execution interrupted by the user |
| 3 | Internal pytest error |
| 4 | pytest command-line usage error |
| 5 | No tests collected |

Crucially, `_jb_pytest_runner.py` **does not special-case exit code 5**. It delegates to pytest and lets the exit code bubble out. In the IDE, "no tests found" therefore manifests as `processTerminated(exitCode=5)` plus an `SMRootTestProxy` whose `isEmptySuite()` is true. An LLM that only looks at exit code will report "pytest errored (exit 5)"; it should instead read the SM tree and say "no tests matched the selector".

### 5.3 `teamcity.pytest_plugin.Plugin` event mapping

From the plugin source:

- `pytest_collection_finish(session)` — emits `##teamcity[testCount count='N']` with `len(session.items)`. This is the **only** collection-phase hook; there is no `pytest_collectstart` / `pytest_internalerror` handling.
- `pytest_collectreport(report)` — if `report.failed`, emits `testStarted`/`testFailed`/`testFinished` for a synthetic test id of `"<nodeid>_collect"`. That is how conftest import errors and collection failures appear in the IntelliJ test tree — as distinct nodes named `<path>_collect`. They are test failures from the framework's point of view, not infrastructure errors.
- `pytest_runtest_logreport(report)` — distinguishes phases via `report.when`:
  - `report.when == 'call'` + `report.failed` → real test failure.
  - `report.when == 'setup'` + `report.failed` → emits `testFailed` with `message="test setup failed"` (typical of a failing fixture).
  - `report.when == 'teardown'` + `report.failed` → same treatment, tagged as teardown failure.
  - `report.skipped` → `testIgnored`.
- There is **no** separate "infrastructure error" channel. A conftest syntax error, a missing fixture, and a failing `assert 1 == 2` all surface through the same `testFailed` mechanism with different node ids and messages. Consumers must disambiguate by node name suffix (`_collect`) and the `message` attribute.

### 5.4 Differences between unittest, pytest, nose in PyCharm

- **unittest** — PyCharm uses its own `_jb_unittest_runner.py` which parses `unittest` test outcomes directly via a `TestResult` subclass and emits TeamCity messages. No collection phase; all discovery is driver-side. "No tests found" surfaces as an empty `unittest.TestLoader.discover` result and produces an empty SM root with no tests executed (exit code 0 typically).
- **pytest** — as above.
- **nose/nose2** — legacy path, similar to unittest but via `_jb_nosetest_runner.py`. Being deprecated.

Only pytest emits collection errors as pseudo-tests; unittest does not. A wrapper that expects `_collect`-suffixed nodes only gets them under pytest.

### 5.5 PyCharm-specific fragile points

- **Working directory** — pytest discovers `conftest.py` relative to `rootdir`, which is computed from the CWD and `pytest.ini` location. PyCharm's "Working directory" field in the run config directly controls this. A wrapper that overrides the working directory via `GeneralCommandLine.setWorkDirectory` can break conftest discovery silently.
- **Env var `_JB_PPRINT_PRIMITIVES`** — PyCharm sets this so that the plugin formats `expected`/`actual` in a diff-friendly way. If you reconstruct the command line yourself, omitting this changes the TeamCity comparison-failure payload shape.
- **Interpreter/venv resolution** — Python run configs defer to the `Sdk` attached to the module. If the Sdk is misconfigured, `RunProfileState.execute` throws `ExecutionException("invalid Python SDK")` at stage 4, surfacing as `processNotStarted`, not as a test failure.

---

## 6. Failure-Mode Signal Matrix

| Real-world situation | Signals the wrapper receives |
|---|---|
| Config name misspelled | `RunManager.findConfiguration...` returns null. No ExecutionListener events fire. |
| Executor id unknown | `ProgramRunner.getRunner` returns null. No events. |
| `checkConfiguration` throws `RuntimeConfigurationError` | Framework fires `processNotStarted` immediately (or `executeConfiguration` shows an error dialog and never calls runner). |
| Compile failed (Make before-run) | `processStartScheduled` → `processNotStarted`. **No** `processStarting`, **no** `processStarted`. Errors live in the Build/Problems view. |
| Missing SDK / bad classpath at launch | `processStarting` → `processNotStarted` (ExecutionException). |
| Process forked but crashed before first output | `processStarted` → `processTerminated(exitCode != 0)` with no SM events. |
| Pytest: no tests collected | `processStarted` → `onTestingStarted` → `onTestsCountInSuite(0)` → `onTestingFinished` (root `isEmptySuite()` true) → `processTerminated(5)`. |
| JUnit: `initializationError` | `onSuiteStarted` → a child test node named `initializationError` with `isDefect()` true → `onTestingFinished` → `processTerminated`. Often exit code is 0 even though tests errored. |
| All tests pass | Normal stream of `onTestStarted`/`onTestFinished`, final root has all passed, `processTerminated(0)`. |
| Some tests fail | Same as above but some nodes have `isDefect()` true; `processTerminated` may or may not be non-zero depending on runner. |
| User cancels the run | `processTerminating` → `processTerminated`, root tests marked `isInterrupted() == true`. |
| IDE shutdown mid-run | `ProcessHandler` is `destroyProcess()`'d; `processTerminated` fires with synthetic exit code. `onTestingFinished` may or may not fire depending on whether SM parser was flushed. |

---

## 7. Threading Contract Summary

From the JetBrains Threading Model doc (2023.3+):

- **Write access and most `RunContentManager` / UI attachment calls require EDT.** Under the hood, `ProgramRunnerUtil.executeConfiguration` and `ExecutionManager.restartRunProfile` marshal to EDT themselves, so calling them from a background thread is safe **if** you accept that error UI (toasts, balloons) will appear on EDT.
- **Read actions can run on any thread.** Reading `RunManager.getAllConfigurationsList()` / `findConfiguration...` needs at most an implicit read action, which `invokeLater`/`invokeAndWait` provide automatically; from a background thread wrap in `ReadAction.compute { ... }`.
- **`ExecutionListener` callbacks fire on EDT.** Your handler must not block or do long I/O on EDT; if you need to call the LLM, bounce to a background coroutine immediately.
- **`ProcessListener.onTextAvailable` fires on a pooled thread.** Safe to accumulate buffers; do not touch Swing.
- **`SMTRunnerEventsListener` callbacks fire on EDT** (they are driven by the same message bus dispatch used for UI updates). Again: delegate LLM work off-thread.

**Recommended launch sequence for a wrapper tool called from a background coroutine:**

```kotlin
withContext(Dispatchers.EDT) {
    val settings = RunManager.getInstance(project).findConfigurationByName(name)
        ?: return@withContext null
    settings.configuration.checkConfiguration()   // throws RuntimeConfigurationException
    val executor = DefaultRunExecutor.getRunExecutorInstance()
    // Subscribe BEFORE executing so processStarting/processNotStarted cannot race us
    val conn = project.messageBus.connect(disposable)
    conn.subscribe(ExecutionManager.EXECUTION_TOPIC, executionListener)
    conn.subscribe(SMTRunnerEventsListener.TEST_STATUS, testListener)
    ProgramRunnerUtil.executeConfiguration(settings, executor)
}
```

Collection, aggregation, and reporting to the LLM happen back on a background coroutine using signals the listeners have pushed onto a `Channel`/`SharedFlow`.

---

## 8. Quick-Reference Cheat Sheet

```
// Topics
ExecutionManager.EXECUTION_TOPIC                              // Topic<ExecutionListener>
SMTRunnerEventsListener.TEST_STATUS                           // Topic<SMTRunnerEventsListener>
CompilerTopics.COMPILATION_STATUS                             // Topic<CompilationStatusListener>

// Launch entry points
ProgramRunnerUtil.executeConfiguration(settings, executor)     // EDT, void, errors go to UI
ExecutionEnvironmentBuilder.create(executor, settings).build() // manual environment
ExecutionManager.getInstance(project).restartRunProfile(env)   // with reuse

// Runner pick
ProgramRunner.getRunner(executorId, runProfile)                // nullable

// Result extraction
SMTestProxy.SMRootTestProxy root = listener onTestingFinished argument
root.isEmptySuite()                                            // "no tests found"
root.getAllTests()                                             // flat list
each.isDefect()/isPassed()/isIgnored()/isInterrupted()
each.getErrorMessage(), getStacktrace(), getLocationUrl(), getDuration()

// Process output classification
ProcessOutputType.isStdout(key) / isStderr(key) / isSystem(key)

// Executor IDs
DefaultRunExecutor.EXECUTOR_ID   == "Run"
DefaultDebugExecutor.EXECUTOR_ID == "Debug"
CoverageExecutor.EXECUTOR_ID     == "Coverage"
```

---

## 9. Open Questions / Source Gaps

The following were asked but not conclusively answered by accessible sources during this research:

- **Exact order of `processStartScheduled` vs before-run task execution.** The `ExecutionListener` docs say "scheduled but not begun"; source inspection of `ExecutionManagerImpl` would confirm whether the scheduled event fires before or after the before-run task loop. The community support article that normally answers this returned 403 during fetching; a future pass should re-fetch or read `ExecutionManagerImpl` directly from a local `intellij-community` checkout.
- **Whether `onTestsReporterAttached` is a separate callback in newer versions.** The modern interface (see `SMTRunnerEventsListener.java` above) uses `onSuiteTreeStarted` / `onSuiteTreeNodeAdded` for tree prelude; older code referenced an explicit `onTestsReporterAttached`. Newer code paths may still fire something equivalent on `SMRootTestProxy` — verify against the branch of the platform you build against.
- **Formal contract for `CompileStatusNotification`.** The community API stub page lists the callback but omits parameter names. The real signature (from `intellij-community`) is:
  `void finished(boolean aborted, int errors, int warnings, CompileContext compileContext)`.
  `CompileContext.getMessages(CompilerMessageCategory)` returns `CompilerMessage[]`, each exposing `getMessage()`, `getVirtualFile()`, `getLineNumber()`, `getColumnNumber()`, and `getNavigatable()`.

These gaps should not block the audit — every claim in sections 1–7 is backed by fetched sources — but anyone productising this should re-verify them against a local source checkout of the exact platform version the plugin targets (currently 2025.1+).
