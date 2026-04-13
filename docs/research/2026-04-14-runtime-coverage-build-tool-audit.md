# Runtime, Coverage, Build & Config Tool Audit

Deep audit of failure scenarios, edge cases, and gaps in the agent's runtime/build/coverage/config tools.

**Date:** 2026-04-14
**Files audited:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/BuildTool.kt` + `build/*.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeConfigTool.kt`

---

## 1. RuntimeExecTool (`runtime_exec`)

### 1.1 Action: `run_tests`

**IDE APIs used:** `RunManager`, `ExecutionEnvironmentBuilder`, `ProgramRunnerUtil.executeConfigurationAsync`, `SMTestProxy`, `TestResultsViewer`, `CompilerManager` (via message bus listener), `JavaPsiFacade` (for test framework detection + module resolution), `ProcessBuilder` (shell fallback).

**Full lifecycle:**
1. Parse params (class_name, method, timeout, use_native_runner)
2. If `use_native_runner=true` (default):
   a. `createJUnitRunSettings()` -- uses `JavaPsiFacade.findClass()` via `ReadAction` to detect test framework (JUnit/TestNG), resolves module via `ModuleUtilCore.findModuleForPsiElement()`, creates temporary run configuration via `RunManager.createConfiguration()` (NOT registered in RunManager -- good)
   b. `executeWithNativeRunner()` -- builds `ExecutionEnvironment`, executes via `ProgramRunnerUtil.executeConfigurationAsync()` with a `ProgramRunner.Callback`
   c. Subscribes to `EXECUTION_TOPIC` for `processNotStarted` (build failure detection)
   d. Also subscribes to `CompilationStatusListener` (via reflection) for error counts
   e. On `processStarted` callback, streams output to chat, awaits `TestResultsViewer.onTestingFinished`
   f. Collects structured results via `SMTestProxy` tree
3. If native runner returns `null` or throws, falls back to shell (`mvn test` / `./gradlew test`)
4. Shell fallback: `ProcessBuilder`, read output, parse exit code, detect BUILD FAILURE vs test failure via string matching

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Build fails before test run (native) | `ExecutionListener.processNotStarted` fires, returns "BUILD FAILED" with compilation error counts. Also captures error detail via `CompilationStatusListener`. | **Good handling.** Error message includes actionable guidance ("Fix compilation errors, use diagnostics tool"). | -- |
| Build fails before test run (shell) | Detects via string matching: "BUILD FAILURE", "COMPILATION ERROR", "compileTestJava FAILED", "compileTestKotlin FAILED" | **Misses some patterns**: e.g. Gradle `compileJava FAILED` (non-test compilation), Maven `[ERROR] ...cannot find symbol`. Shell output heuristic can false-negative. | MEDIUM |
| Tests fail | Native: structured `SMTestProxy` tree with PASSED/FAILED/ERROR/SKIPPED status, error messages, stack traces (capped at 5 frames). Shell: reports exit code + raw output. | **Native is good.** Shell fallback gives raw unstructured output -- LLM must parse Maven/Gradle test output format itself. | LOW |
| Tests error (exception in setup) | `mapToTestResultEntry` checks for `AssertionError`/`AssertionFailedError` in stacktrace -- if absent, classifies as `ERROR` rather than `FAILED`. | **Good**: Correct class names (`java.lang.AssertionError`, `org.opentest4j.AssertionFailedError`). Properly distinguishes assertion failures (test logic) from errors (infrastructure/setup). | -- |
| Tests manually stopped/cancelled | `continuation.invokeOnCancellation` destroys process handler and disconnects build bus connection. Good. | Process destruction is called but there is **no verification the process actually died**. `destroyProcess()` sends SIGTERM but doesn't `waitFor`. Zombie processes possible on Windows. | LOW |
| No test configuration exists | `createJUnitRunSettings` returns `null` if JUnit/TestNG config type not found, or if class can't be resolved via PSI. Falls through to shell fallback. | **Silent fallback with no explanation**: If native runner returns `null`, code calls `executeWithShell` without telling the LLM why the native runner was skipped. The shell might also fail if the class doesn't exist. | MEDIUM |
| Wrong module specified | N/A for `run_tests` -- module is auto-detected from PSI class location. | If `findModuleForClass` returns null (class found in PSI but not in any module), `createJUnitRunSettings` returns null and silently falls to shell. | LOW |
| Test output > 50KB | Shell: `RUN_TESTS_MAX_OUTPUT_CHARS = 4000` chars cap on shell output builder. Native: `RUN_TESTS_TOKEN_CAP_CHARS = 12000` chars cap on formatted results. | **Shell output severely truncated at 4KB** while formatted results cap at 12KB. The 4KB cap means long compilation errors or verbose test output will be silently chopped. LLM gets `"Tests FAILED for X.\n\n[first 4KB]"` with no indication of truncation at the output level (only the final `ToolResult` checks 12KB). | HIGH |
| Timeout (individual test hangs) | `withTimeoutOrNull(timeoutSeconds * 1000)` wraps the entire run. On timeout, calls `destroyProcess()`, attempts to extract partial results from `SMTestProxy`. | **Good**: partial results extracted on timeout. Error message includes `[TIMEOUT]` prefix. But if the test tree hasn't populated yet at timeout (common for single-test hangs), `extractNativeResults` returns `null` and the LLM gets "No results captured". | MEDIUM |
| JVM crashes during test | Process terminates with non-zero exit. Native: `processTerminated` fires, test tree may be empty. Shell: exit code captured. | Native: crash before test tree populates returns "no structured results available". LLM gets no crash diagnostic info (no core dump path, no OOM message). **No crash detection** -- treated same as normal test failure. | MEDIUM |
| Maven/Gradle sync not done | Native runner: `createJUnitRunSettings` depends on PSI (needs indexed project). If sync hasn't happened, `JavaPsiFacade.findClass` may return null. Shell: maven/gradle commands work independently of IDE sync. | **Native runner silently falls to shell** when PSI can't resolve the class. No explicit error about sync state. | LOW |
| class_name is a Kotlin file | `detectTestFramework` uses `JavaPsiFacade.findClass` which resolves Kotlin classes. Module detection also works. | **Works correctly** for Kotlin test classes. | -- |
| class_name doesn't exist at all | PSI `findClass` returns null, `findModuleForClass` returns null, `createJUnitRunSettings` returns null, falls to shell. Shell tries `mvn test -Dtest=NonExistent` which fails. | LLM gets shell build error output but **no clear "class not found" message**. Error will say "Tests FAILED" or "BUILD FAILED" depending on build tool behavior. | MEDIUM |
| `run_tests` without `class_name` | Returns error: "Error: 'class_name' parameter is required". | `class_name` is required per the code, but the tool description says `class_name?` (optional with `?`). **Description/code mismatch**: Description implies it's optional, code requires it. | HIGH |
| TestNG tests | `detectTestFramework` checks for `org.testng.` annotations. Uses `getPersistantData` (TestNG's actual method name). Config type ID is "TestNG". | **Good**: TestNG handled via separate path. `"getPersistantData"` is the correct (misspelled) TestNG API method name. | -- |
| Build watchdog thread leak | A daemon `Thread` is spawned with `BUILD_WATCHDOG_MAX_MS = 300_000L` (5 min) sleep to disconnect the build connection. | **Thread leak on fast builds**: The daemon thread sleeps for 5 minutes even if the build finishes in 2 seconds. `Thread.sleep(BUILD_WATCHDOG_MAX_MS)` is interrupted only by JVM shutdown, not by build completion. Not a functional bug but wastes a thread. | LOW |
| Concurrent `run_tests` calls | Run configuration is temporary and NOT added to RunManager. ProcessHandler is per-call. | **Safe**: No shared mutable state between concurrent calls. Each creates its own temp config. | -- |

### 1.2 Action: `compile_module`

**IDE APIs used:** `CompilerManager.make()`, `ModuleManager`, `CompilerMessageCategory`.

**Full lifecycle:**
1. Resolve module by name (or use project scope if omitted)
2. `CompilerManager.make(scope, callback)` on EDT
3. Callback receives (aborted, errors, warnings, context)
4. Extract up to 20 error messages with file/line/column

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Module not found | Lists available modules in error message | **Good** -- actionable error with module list. | -- |
| Compilation aborted (user cancelled) | Returns "Compilation of $target was aborted." | **Missing context**: doesn't tell LLM whether partial compilation succeeded, or what triggered the abort. | LOW |
| Compilation timeout | `withTimeoutOrNull(120_000L)` -- 120 seconds hard cap. | **Not configurable** by the LLM. For large projects 120s may not be enough. Returns "build may be stuck" which is misleading -- it might just be slow. | MEDIUM |
| Compilation succeeds with warnings | Reports "0 errors" with warning count but **does NOT include warning text**. | LLM knows warnings exist but **cannot see what they are**. For deprecation warnings or unsafe cast warnings this is useful info. | MEDIUM |
| Project has no compiler (Python project) | `CompilerManager.getInstance(project)` exists for all projects. `make()` should succeed with 0 errors, 0 warnings. | **Misleading success** for non-JVM projects -- tells LLM "Compilation successful" when there was nothing to compile. | LOW |
| `cont` already completed (race between scope error and make callback) | `if (!cont.isCompleted) cont.resume(compileResult)` -- checked before resume. | **Good**: race handled. | -- |

### 1.3 Action: `get_running_processes`

**IDE APIs used:** `ExecutionManager.getRunningProcesses()`, `XDebuggerManager.debugSessions`.

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| No running processes | Returns "No active run/debug sessions." | Good. | -- |
| Process name extraction fails | `extractProcessName` catches all exceptions, returns "Unknown process". | LLM gets a process entry named "Unknown process" -- **not useful** for identifying which process to interact with. | LOW |
| PID extraction fails | `extractPid` returns null, PID line omitted from output. | **Acceptable** -- PID is supplementary info. | -- |
| Debug session paused vs running | Checks `session.isPaused`, reports "Paused (at breakpoint)" vs "Active". | **Good differentiation**. But doesn't show which breakpoint or what line -- just "Paused (at breakpoint)". | LOW |
| Debug session name collision with run session | Deduplication check: `entries.none { it.name == sessionName && it.type == "Debug" }`. | Checks `type == "Debug"` but entries from `getRunningProcesses()` have `type = "Running"`. So a debug session named "MyApp" and a run session named "MyApp" would both appear. **Correct behavior** -- they are different sessions. | -- |

### 1.4 Action: `get_run_output`

**IDE APIs used:** `RunContentManager.allDescriptors`, `ConsoleViewImpl.flushDeferredText()`, `Editor.document.text`.

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Config name not found | Lists available session names in error message. | **Good**. | -- |
| Console output empty | Returns "found but console output is empty." | **Good**. | -- |
| Console has ANSI escape codes | No ANSI stripping. | LLM receives raw ANSI codes (`\033[31m`, etc.) which are **noise** in the context. Wastes tokens. | MEDIUM |
| Output > 12KB | `RUN_OUTPUT_TOKEN_CAP_CHARS = 12000` chars cap with "output truncated" message. | **Good**: truncation is explicit. But truncation is from the start -- `content.take(12000)`. This means the LLM sees the **header and first lines but loses the end** which often has the most important info (errors, summary). Should truncate from the end or keep head+tail. | HIGH |
| Regex filter is invalid | Catches `Regex` constructor exception, returns clear error. | **Good**. | -- |
| Console view is wrapped (Ultimate) | `unwrapToConsoleView()` follows delegate chain up to `MAX_UNWRAP_DEPTH = 5` levels. Tries `getDelegate()` then `getConsole()`, then reflection fallback, then `readViaEditor` (gets document text from any editor). | **Good**: thorough unwrapping strategy with multiple fallback paths. | -- |
| Config name matching is substring-based | `desc.displayName?.contains(configName, ignoreCase = true)` | **Ambiguous matching**: if user has "MyApp" and "MyApp-Debug", searching for "MyApp" matches the first one found. No exact match preference. | LOW |

### 1.5 Action: `get_test_results`

**IDE APIs used:** `RunContentManager.allDescriptors`, `SMTestProxy`, `TestResultsViewer`, `TestConsoleUtils`.

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| No config_name specified | Searches for first descriptor with test results, or first running process. | **Good**: auto-discovery of most recent test run. | -- |
| Process still running | Awaits termination up to `MAX_PROCESS_WAIT_SECONDS = 600` (10 min) with progress streaming. | **Good**: streams progress, returns clear error if still running. | -- |
| Test tree not populated after process exit | Polls up to `TEST_TREE_RETRY_ATTEMPTS = 10` at `500ms` intervals (5s total). | **Adequate** for normal cases. But some test frameworks (Spock, Cucumber) may take longer to finalize the tree. | LOW |
| Output > 12KB | `TEST_RESULTS_TOKEN_CAP_CHARS = 12000`. Truncation is head-biased. | Same issue as `get_run_output`: **loses tail content** including summary and potentially the most critical failure info if there are many tests. | MEDIUM |
| Stack trace truncation | `MAX_STACK_FRAMES = 5` -- takes first 5 frames matching "at " or "Exception"/"Error". | For deeply nested Spring/framework stack traces, 5 frames may all be framework code. **No intelligent frame selection** (e.g., prefer frames from project packages). | MEDIUM |
| Passed tests truncation | `MAX_PASSED_SHOWN = 20` with "and N more passed tests" message. | **Good**: prevents flooding context with passing tests. | -- |

---

## 2. CoverageTool (`coverage`)

### 2.1 Action: `run_with_coverage`

**IDE APIs used:** `ExecutionRegistry.getExecutorById("Coverage")`, `ProgramRunnerUtil.executeConfigurationAsync`, `CoverageDataManager` (via reflection), `CoverageSuiteListener` (via reflection + `java.lang.reflect.Proxy`), `SMTestProxy`, `JavaPsiFacade`.

**Full lifecycle:**
1. Create JUnit run config (same pattern as `RuntimeExecTool.createJUnitRunSettings`)
2. Check `CoverageExecutor` availability (`ExecutorRegistry.getExecutorById("Coverage")`)
3. Register `CoverageSuiteListener` via reflection proxy for data-ready callback
4. Build `ExecutionEnvironment` with coverage executor
5. Execute via `ProgramRunnerUtil.executeConfigurationAsync`
6. Subscribe to `EXECUTION_TOPIC.processNotStarted` for build failure
7. Await test results via `TestResultsViewer.onTestingFinished` or process termination fallback
8. Await coverage data via listener or extract directly
9. Extract coverage via `CoverageDataManager` reflection: Path A (bundle) then Path B (suites)
10. Build per-file coverage detail from `ProjectData.getClasses()`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Coverage plugin not installed | `ExecutorRegistry.getExecutorById("Coverage")` returns null. Returns clear error: "Coverage executor not available." | **Good**: explicit error with guidance. | -- |
| Build fails before coverage run | `processNotStarted` listener fires, returns "BUILD FAILED -- coverage run did not start." | **Good**: mirrors `RuntimeExecTool` pattern. | -- |
| Tests fail during coverage | Test results collected via `SMTestProxy`. Coverage data still collected via `CoverageSuiteListener` / direct extraction. | **Good**: test failures and coverage data are reported independently. Both appear in the response. | -- |
| Tests fail AND no coverage data | Response includes test results + "No coverage data available. The coverage tab in the IDE may still show results." | **Good**: tells LLM about potential IDE-only data and suggests `get_file_coverage` retry. Includes extraction diagnostics. | -- |
| Coverage data extraction via reflection fails | Returns extraction diagnostics in error message. Includes which path (A/B) was attempted and what went wrong. | **Good**: diagnostics are detailed (class names, method names, exception types). | -- |
| Timeout | `withTimeoutOrNull(timeoutSeconds * 1000)` kills process and disposes listener. | **Good**: cleanup on timeout. Returns `[TIMEOUT]` with timeout duration. | -- |
| Coverage listener registration fails (reflection) | Returns `null` Disposable. Falls back to `extractCoverageSnapshot` after `COVERAGE_FALLBACK_DELAY_MS = 5000ms`. | **5-second blind delay** as fallback. No progress indication during this wait. Could be shorter (coverage data is usually available immediately) or could poll instead. | LOW |
| Partial coverage (some tests pass, some fail) | Coverage data from passed tests IS collected. IntelliJ aggregates all coverage regardless of pass/fail. | **Good**: partial coverage is available. But there's **no indication of which tests contributed** to the coverage data. | LOW |
| JaCoCo vs IntelliJ native runner | Comment says IntelliJ converts JaCoCo `.exec` to `ProjectData` internally. Same reflection path handles both. | **Correct**: the extraction works on IntelliJ's internal `ProjectData` format regardless of runner. | -- |
| Coverage snapshot caching (`lastSnapshot`) | Stored as `@Volatile` instance field. Used by `get_file_coverage`. | **Race condition**: If two coverage runs execute concurrently (from different agent sessions), `lastSnapshot` is shared (single CoverageTool instance). The second run overwrites the first's snapshot. | MEDIUM |
| `test_class` doesn't exist | `createJUnitRunSettings` returns null, returns error: "Could not create run configuration." | **Good**: clear error. | -- |
| CoverageSuiteListener callback timing | Listener waits up to `COVERAGE_LISTENER_TIMEOUT_MS = 30s` for `coverageDataCalculated`. If timeout, falls back to direct extraction. | **Good**: dual-path (listener + direct extraction) handles timing issues. | -- |
| Timer leak in fallback path | `java.util.Timer().schedule(...)` with 2000ms delay for test tree finalization. Timer is not cancelled if the continuation is already resumed. | **Timer task leaks**: `Timer` + `TimerTask` are never cancelled if the continuation is already complete. The `TimerTask.run()` checks `continuation.isActive` but the Timer thread persists until JVM GC. Not a functional bug but creates garbage. | LOW |
| `method` parameter for single test | Constructs `"className#methodName"` test target. Config sets `TEST_OBJECT = "method"`, `METHOD_NAME = method`. | **Good**: properly handles single-method coverage. | -- |

### 2.2 Action: `get_file_coverage`

**IDE APIs used:** `CoverageDataManager` (via reflection), `JavaPsiFacade`, `PsiManager`, `LocalFileSystem`.

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| No prior coverage run | First tries `lastSnapshot`, then tries direct extraction from IDE. If both null, returns error with diagnostics. | **Good**: tries to recover from IDE state. | -- |
| File not in coverage data | Returns "No coverage data found for '$filePath'" with list of available classes. | **Good**: actionable error. | -- |
| File path vs class name matching | Three-tier matching: exact path match, then resolved class name (via PSI), then simple filename suffix match. | **Good**: handles multiple input formats (file path, fully qualified class name, simple class name). | -- |
| Kotlin file coverage | `resolveToClassName` handles both `PsiJavaFile` and `KtFile` (via reflection). | **Good**: Kotlin supported. | -- |
| Generated files / test files | Coverage data includes test files if they were instrumented. No filtering. | LLM can see coverage for test files themselves, which is **usually not useful**. No way to filter test vs source coverage. | LOW |
| Multiple coverage runs | `lastSnapshot` is overwritten on each `run_with_coverage`. | **Only latest run available.** No way to compare coverage between runs or access historical data. | LOW |
| Large class with many uncovered lines | `MAX_UNCOVERED_ENTRIES = 50` with range collapsing for consecutive NONE lines. | **Good**: range collapsing keeps output manageable. "and N more uncovered/partial lines" suffix. | -- |
| Groovy file coverage | `resolveToClassName` only handles `.java`, `.kt`, `.groovy` suffixes. | Groovy suffix is stripped but **no PSI resolution** for Groovy files. Will fall back to simple name matching. | LOW |

### Coverage Data Detail Level

The coverage output is exceptionally rich:

- **Line coverage**: Hit count, FULL/PARTIAL/NONE status per line
- **Branch coverage**: Jump (if/else/ternary) true/false hit counts, switch case hit counts with default
- **Method coverage**: Per-method line and branch rollups
- **Formatting**: Range-collapsed uncovered lines, method-level rollup with uncovered/partial markers

**This is among the richest coverage output of any agent tool.** No significant gaps in data detail.

---

## 3. BuildTool (`build`)

### Architecture

`BuildTool` is a thin dispatcher that routes to action-specific functions in `build/*.kt`. Each action function is a standalone `internal fun/suspend fun` that takes `(JsonObject, Project)` and returns `ToolResult`.

Two distinct implementation patterns:
1. **Maven actions**: Use `MavenUtils` which accesses IntelliJ's Maven plugin via reflection
2. **Gradle actions**: Parse `build.gradle`/`build.gradle.kts` files directly (regex-based)
3. **Python actions (pip/poetry/uv)**: Shell out to CLI tools (`pip`, `poetry`, `uv`)
4. **Pytest actions**: Shell out to `pytest` CLI
5. **Module/project actions**: Use IntelliJ `ModuleManager` API directly

### 3.1 Maven Actions

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Maven not configured | `MavenUtils.getMavenManager()` checks `isMavenizedProject()`. Returns "Maven not configured." | **Good**: clear error. | -- |
| Maven sync not done | Maven plugin's `MavenProjectsManager` returns projects even before full sync. Dependencies/properties come from POM parsing, not resolved artifacts. | **Stale data possible**: If POM was recently edited but sync hasn't happened, the data reflects the OLD POM. **No indication to LLM that data may be stale.** | MEDIUM |
| Maven plugin not installed | `Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")` throws `ClassNotFoundException`. `ReflectionUtils.tryReflective` returns null. Returns "Maven not configured." | **Misleading error**: "Maven not configured" when the real issue is "Maven plugin not installed." User may have Maven configured via CLI but not in IDE. | MEDIUM |
| Module not found | Returns error with list of available module names. | **Good**. | -- |
| Dependency conflict | Maven's `getDependencies()` returns the resolved dependency list. Conflicts are handled by Maven's dependency mediation. | **No conflict information**: dependency tree action shows transitive deps but doesn't flag version conflicts or exclusions. The LLM must infer conflicts from version differences. | LOW |
| `maven_dependency_tree` | Builds tree from `mavenProject.getDependencyTree()` via reflection. | **Falls back to flat list if tree not available.** Clear fallback message. | -- |
| `maven_effective_pom` | Gets plugin configurations from Maven's in-memory model via `getDeclaredPlugins()`, `getConfigurationElement()`, `getExecutions()`. | **Misleading action name**: "effective_pom" implies the full effective POM (with inheritance, interpolation, profile activation). Actually returns **plugin configuration details only** -- not dependencies, properties, build settings, or parent POM values. LLM asking for the effective POM will get a plugin list instead. | HIGH |
| Large POM/dependency list | No output truncation on Maven actions. | **Unbounded output size**: A project with 200+ dependencies will produce very large output. No cap like RuntimeExecTool has. Token estimate is calculated but the content itself is not capped. ToolOutputConfig should handle this at the tool framework level, but individual actions don't enforce a cap. | MEDIUM |

### 3.2 Gradle Actions

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Gradle not configured | `findGradleBuildFiles()` looks for `build.gradle` / `build.gradle.kts` on disk. Returns "No Gradle build files found." | **Good**: checks for files, not Gradle plugin presence. | -- |
| Gradle sync not done | N/A -- reads build files directly from disk, no IDE sync needed. | **Correct**: file-based parsing is sync-independent. | -- |
| Gradle wrapper not present | N/A -- doesn't execute Gradle. Only parses `build.gradle` files. | **No runtime dependency**: pure file parsing. | -- |
| Gradle daemon not running | N/A -- no Gradle execution. | Same as above. | -- |
| `settings.gradle.kts` parsing for modules | Regex: `include\s*\(\s*["']([^"']+)["']\s*\)|include\s+['"]([^'"]+)['"]` | **Misses multi-include syntax**: `include(":a", ":b", ":c")` (comma-separated). Only matches single-include calls. Also misses `include(`:a`)` if backtick-quoted. | HIGH |
| Gradle Kotlin DSL with `libs.` version catalog | Parses `implementation(libs.xxx.yyy)` as a dependency with notation `libs.xxx.yyy`. | **Correct**: recognizes version catalog references. But **doesn't resolve** the actual artifact -- LLM sees `libs.jackson.databind` not `com.fasterxml.jackson.core:jackson-databind:2.17.0`. | MEDIUM |
| Gradle map syntax | Parses `implementation(group: "x", name: "y", version: "z")`. | **Good**: handled explicitly. | -- |
| Complex dependency blocks | Regex `dependencies\s*\{([^}]*(?:\{[^}]*\}[^}]*)*)\}` for matching blocks. | **Fails on nested closures > 2 levels**: e.g., `dependencies { implementation("x") { exclude(group = "y") { ... } } }`. The regex allows one level of nested braces but not deeper. | MEDIUM |
| `gradle_tasks` | Parses `build.gradle` for task definitions via regex. | **Misses tasks defined in buildSrc, convention plugins, or applied scripts.** Only sees tasks in the direct build.gradle file. | MEDIUM |
| `gradle_properties` | Reads `gradle.properties` file + build file `ext { }` blocks. | **Doesn't read `local.properties`, `~/.gradle/gradle.properties`, or command-line `-P` props.** LLM may miss environment-specific settings. | LOW |
| Version catalog `.toml` file | Not parsed. Only `libs.` references in build files are captured. | **No `gradle/libs.versions.toml` reading.** LLM can't see the actual artifact coordinates behind version catalog aliases. | MEDIUM |

### 3.3 Python Actions (pip/poetry/uv)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| pip/poetry/uv not installed | Tries `pip` then `pip3` (etc.). Returns "pip is not available. Ensure Python and pip are installed and on PATH." | **Good**: clear error with guidance. | -- |
| CLI timeout | `CLI_TIMEOUT_SECONDS = 30L` for pip, 30L for poetry/uv, 60L for pytest discovery, 300L for pytest run. | **Adequate**: 30s should suffice for package listing. No output if timeout occurs -- just tries next fallback command. | -- |
| pip JSON parsing | Regex-based parsing of `pip list --format=json` output. | **Fragile**: regex `\{"name"\s*:\s*"([^"]+)"\s*,\s*"version"\s*:\s*"([^"]+)"\}` assumes specific field ordering. If pip changes the JSON field order (e.g., version before name), parsing silently returns empty. **Should use a proper JSON parser.** | HIGH |
| `pip_dependencies` from pyproject.toml | Parses `dependencies = [` section with regex. | **Misses `optional-dependencies`, `[tool.poetry.dependencies]` (handled by poetry actions), and PEP 735 dependency groups.** Only parses the top-level `dependencies` array. | MEDIUM |
| Network errors during `pip outdated` | `pip list --outdated` requires network access. If offline, pip may return non-zero exit code. | **Treated as "pip not found"** since the fallback loop only succeeds on exit code 0. Should distinguish between "pip not found" and "pip failed". | MEDIUM |
| Virtual environment not activated | Commands run in the project directory. If a venv exists but isn't activated, pip may point to system Python. | **No venv detection or activation.** Could check for `venv/bin/pip`, `.venv/bin/pip`, or use `python -m pip` which respects the Python path. | MEDIUM |
| Pipe buffer deadlock prevention | Uses `CompletableFuture.supplyAsync` to drain stdout concurrently with `process.waitFor`. | **Good**: prevents pipe deadlock. 5-second timeout on output retrieval after process exit. | -- |

### 3.4 Pytest Actions

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| pytest not installed | Tries `pytest`, `python -m pytest`, `python3 -m pytest`. Returns "pytest is not available." | **Good**: multiple fallback attempts. | -- |
| Path traversal in test path | `validatePytestPath` canonicalizes path and checks it starts with `basePath`. | **Good**: prevents path traversal attacks. | -- |
| Unsafe pytest expression chars | `SAFE_PYTEST_EXPR` regex allows only word chars, spaces, dots, hyphens, parens, brackets, commas. Rejects `__`. | **Good**: prevents code injection via `-k`/`-m` expressions. | -- |
| pytest output parsing | Regex-based parsing of verbose test output. | **Misses pytest-xdist output format** (parallel test execution). Also misses pytest-bdd and pytest-html specific output patterns. | LOW |
| `pytest_run` failure output cap | `results.failureOutput.take(5000)` -- caps failure detail at 5KB. | **Good**: prevents massive failure dumps. | -- |
| pytest exits with code 2 (usage error) | `exitCode in listOf(0, 1, 2, 5)` is treated as valid. | Code 2 means pytest usage error (bad args). Output is returned but **may be confusing** -- it's an error message from pytest itself, not test results. | LOW |
| pytest fixture filtering | Filters out `BUILTIN_FIXTURES` set (17 common pytest builtins). | **Good**: reduces noise. | -- |

### 3.5 Module/Project Actions

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| `project_modules` | Uses `ModuleManager.getInstance(project).modules`. Lists name, content roots, source roots. | **Good**: comprehensive module listing. | -- |
| `module_dependency_graph` | Uses `ModuleRootManager.getDependencies()`. Builds adjacency list. Optional cycle detection. | **Good**: cycle detection, transitive deps, library deps all configurable. | -- |

---

## 4. RuntimeConfigTool (`runtime_config`)

### 4.1 Action: `get_run_configurations`

**IDE APIs used:** `RunManager.allSettings`, reflection for config properties extraction.

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| No configurations | Returns "No run configurations found." | **Good**. | -- |
| Type filter matching | `matchesTypeFilter()` checks both `type.id` and `type.displayName` (lowercase contains). | **Broad matching**: `"junit"` filter also matches configs with "test" in their type ID. This could match TestNG or other test frameworks. | LOW |
| Env vars display | Shows `key=***` (masked values). | **Good**: doesn't leak secrets. | -- |
| Many configurations | No output cap on configuration listing. | For projects with 50+ configs, output could be very large. No pagination. | LOW |

### 4.2 Action: `create_run_config`

**IDE APIs used:** `RunManager.createConfiguration`, `ApplicationConfigurationType`, reflection for other config types.

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Invalid class name (non-existent) | Config is created with whatever class name is specified. No validation. | **No PSI validation of main_class/test_class.** Config is created but will fail on execution with "Class not found". LLM won't know until it tries to run. | MEDIUM |
| Config with same name exists | Checks `runManager.findConfigurationByName(prefixedName)`. Returns "already exists, use modify_run_config". | **Good**: prevents duplicates. | -- |
| Spring Boot plugin not installed | `resolveFactoryViaReflection` returns null. Returns "Could not resolve configuration factory... required plugin may not be installed." | **Good**: clear error with explanation. | -- |
| Reflection failures in config setup | `applyReflectionConfig`, `applyJUnitConfig`, `applyRemoteConfig`, `applyGradleConfig` all catch and **silently swallow** exceptions. | **Silent failure**: If setting main_class/vm_options/env_vars fails via reflection, the config is still created but with missing settings. **No error reported to LLM.** This could lead to a config that looks correct but doesn't work. | HIGH |
| [Agent] prefix | All created configs are prefixed with `[Agent] `. | **Good**: safety measure. Clearly identifies agent-created configs. | -- |
| Config type not in valid list | Explicit validation against `listOf("application", "spring_boot", "junit", "gradle", "remote_debug")`. | **No Python run config support** (for PyCharm). If agent is running in PyCharm, no run configs can be created. | MEDIUM |
| `invokeAndWait` for config registration | `ApplicationManager.getApplication().invokeAndWait { runManager.addConfiguration(settings) }` | **Could deadlock** if called from EDT. But `execute()` is `suspend` and should run on `Dispatchers.IO`. Safe in normal usage. | LOW |
| Module resolution for config | Module is optional. If omitted, module is auto-detected (for application/spring_boot types). | Actually, **module is NOT auto-detected** for `create_run_config`. It's only set if explicitly provided in params. The `applyApplicationConfig` etc. don't set module. Only `run_tests` and `run_with_coverage` auto-detect module via PSI. | MEDIUM |
| Gradle config `programArgs` interpretation | `applyGradleConfig` sets `setRawCommandLine` for programArgs. | **Confusing API**: `program_args` for Gradle means the Gradle task/arguments, not JVM program arguments. The parameter description doesn't clarify this. | LOW |

### 4.3 Action: `modify_run_config`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Config not found | Returns error with guidance to use `get_run_configurations`. | **Good**. | -- |
| No modifications specified | Returns error listing modifiable fields. | **Good**. | -- |
| Modifying a config that's currently running | No check for running status. Config is modified in-place. | **Unsafe**: Modifying a running config's env vars or VM options won't affect the running process. LLM may think changes take effect immediately. **No warning** about this. | MEDIUM |
| Reflection failure for non-Application configs | `modifyApplyVmOptions`, `modifyApplyEnvVars` etc. silently swallow exceptions. | Same as `create_run_config`: **silent failure** on reflection errors. Config modification reports success but changes may not have been applied. | HIGH |
| Env vars are replaced, not merged | `config.envs = envVars` replaces ALL env vars. | **Destructive**: if user had env vars A=1, B=2 and LLM sets C=3, A and B are lost. No merge behavior. **Description doesn't warn about this.** | HIGH |

### 4.4 Action: `delete_run_config`

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Deleting non-agent config | Safety check: `configName.startsWith("[Agent]")`. Returns error with explanation. | **Good**: strong safety constraint. | -- |
| Config not found | Returns error with guidance. | **Good**. | -- |
| Config is currently running | No check. Deletes the config but doesn't stop the running process. | Running process continues with a now-orphaned config. Not a crash risk but **confusing**. | LOW |
| Deleting a config the user manually renamed (removed [Agent] prefix) | Safety check blocks deletion since name no longer starts with `[Agent]`. | **Correct behavior**: user renamed it, it's now "theirs". | -- |

---

## Cross-Cutting Issues

### Issue 1: No ANSI/Terminal Code Stripping
**Affects:** `get_run_output`, console output in `run_tests` (shell fallback), `pytest_run`
**Severity:** MEDIUM
**Detail:** Console output from IntelliJ runners and CLI tools often contains ANSI escape sequences for colors. These are passed raw to the LLM, wasting tokens and potentially confusing parsing.

### Issue 2: Head-Biased Truncation
**Affects:** `get_run_output` (12KB), `get_test_results` (12KB), `run_tests` shell (4KB raw + 12KB formatted)
**Severity:** HIGH
**Detail:** All truncation uses `content.take(N)`, keeping the HEAD and losing the TAIL. For test/build output, the most important information (errors, summary, failures) is typically at the END. This is the opposite of what the LLM needs. Should use middle-truncation (head + tail) like the `RunCommandTool` does.

### Issue 3: Silent Reflection Failures
**Affects:** `RuntimeConfigTool` (create/modify), `CoverageTool` (listener registration), Maven actions
**Severity:** HIGH
**Detail:** Many config operations use reflection and silently catch all exceptions (`catch (_: Exception) { }`). When reflection fails (wrong method name, API changed, plugin not loaded), the tool reports success but the operation partially or fully failed. The LLM gets a false sense of success.

### Issue 4: No Maven/Gradle Sync State Awareness
**Affects:** Maven actions, `run_tests` native runner (PSI resolution)
**Severity:** MEDIUM
**Detail:** None of the tools check whether the project has been synced/indexed. If the user just opened the project or edited build files, PSI resolution and Maven data may be stale. No warning to the LLM.

### Issue 5: Shell Output Buffer Size Mismatch
**Affects:** `run_tests` shell fallback
**Severity:** HIGH
**Detail:** Shell output is captured into `StringBuilder` with a 4KB cap (`RUN_TESTS_MAX_OUTPUT_CHARS = 4000`), but the formatted result cap is 12KB. This means the shell fallback produces much less useful output than the native runner. A test with compilation errors that produces 8KB of output will be truncated to 4KB, losing the actual error messages.

### Issue 6: `run_tests` Description Says `class_name?` Is Optional But Code Requires It
**Affects:** `run_tests`
**Severity:** HIGH
**Detail:** The tool description shows `class_name?` with a question mark (suggesting optional), but the code has `?: return ToolResult("Error: 'class_name' parameter is required"...)`. LLM may attempt to call `run_tests` without `class_name` expecting to run all tests, which will fail. There is no "run all tests" capability.

### Issue 7: Gradle Dependency Parsing Is Regex-Based, Not IDE-Aware
**Affects:** All Gradle actions
**Severity:** MEDIUM
**Detail:** Gradle actions parse build files with regex instead of using IntelliJ's Gradle integration (`GradleProjectData`, `ExternalSystemManager`). This means: version catalog references are unresolved, tasks from plugins/convention plugins are invisible, multi-project dependencies via `configurations.create()` are missed. The Maven actions use IntelliJ's Maven plugin (via reflection), creating an asymmetry.

### Issue 8: No Python Run Configuration Support
**Affects:** `RuntimeConfigTool`
**Severity:** MEDIUM
**Detail:** `create_run_config` only supports 5 Java/JVM config types. In PyCharm, there's no way to create Python, Django, or Flask run configurations via this tool. The `pytest_run` action in BuildTool partially compensates but can't create persistent configs.

### Issue 9: Coverage Data Is Instance-Level, Not Session-Level
**Affects:** `CoverageTool`
**Severity:** MEDIUM
**Detail:** `lastSnapshot` is an instance field on `CoverageTool`. If the tool is a singleton (which it is, since it's registered in the ToolRegistry), the coverage data is shared across all agent sessions on the same project. One session's coverage run can be read by another session's `get_file_coverage`.

### Issue 10: pip JSON Parsing Uses Regex Instead of JSON Parser
**Affects:** `pip_list`, `pip_outdated`
**Severity:** HIGH
**Detail:** `parsePipJsonList` and `parsePipJsonOutdated` use regex to parse JSON output. This is fragile: field reordering, whitespace changes, or special characters in package names can break parsing. The project already has `kotlinx.serialization.json` available -- should use `Json.parseToJsonElement()`.

---

## Summary by Severity

### CRITICAL (0)
No critical issues found.

### HIGH (8)
1. **Shell output truncated at 4KB** (`run_tests` shell fallback) -- loses error messages
2. **Head-biased truncation** (all tools) -- loses tail content which is usually most important
3. **Silent reflection failures** (`RuntimeConfigTool` create/modify) -- false success reports
4. **`class_name` described as optional but required** (`run_tests`) -- LLM cannot run all tests
5. **`maven_effective_pom` returns plugin configs only, not the actual effective POM** -- misleading action name
6. **Gradle `settings.gradle.kts` multi-include parsing fails** -- misses modules
7. **pip JSON parsing via regex** -- fragile, field-order dependent
8. **`modify_run_config` env vars are replaced not merged** -- destructive, undocumented

### MEDIUM (14)
1. Shell fallback BUILD FAILURE detection misses some patterns
2. `compile_module` timeout not configurable, 120s may be insufficient
3. `compile_module` doesn't include warning text
4. `get_run_output` output truncated from head, not tail
5. ANSI codes not stripped from console output
6. Stack trace frame selection not intelligent (may all be framework)
7. Coverage snapshot shared across sessions (instance field)
8. Maven sync state not checked -- stale data possible
9. Maven plugin vs Maven configured error message misleading
10. Gradle version catalog references unresolved
11. Complex Gradle dependency closures fail regex matching
12. pip network errors indistinguishable from "not found"
13. No venv detection/activation for Python tools
14. No Python run config type support (PyCharm)

### LOW (15)
1. Process destruction doesn't verify termination
2. Native runner null result with no explanation
3. Misleading "Compilation successful" for non-JVM projects
4. Build watchdog thread leak on fast builds
5. Process name "Unknown process" not useful
6. Debug session doesn't show breakpoint location
7. Config name matching is substring-based (ambiguous)
8. Coverage for test files visible (usually not useful)
9. No coverage history comparison
10. Groovy file coverage falls back to name matching
11. Broad type filter matching (junit matches "test")
12. Modifying running config doesn't warn about no effect
13. Deleting config doesn't stop running process
14. Gradle properties misses local.properties
15. pytest exit code 2 (usage error) treated as valid output
