# Phase 4 — Pytest Native Runner Port

**Fixes:** Structural — replaces raw-subprocess pytest with PyCharm's ExecutionManager pipeline. Gets us: proper interpreter resolution, teamcity-messages events, `SMTRunnerEventsListener` integration, and conftest-error fidelity. Indirectly fixes long-tail of "pytest worked in the IDE but not in the agent".
**Audit source:** `docs/research/2026-04-17-runtime-test-tool-audit.md` §3.4, §5.3.
**Preconditions:** Phase 3 (RunInvocation infrastructure). Optional but recommended: Phase 1 (so `interpretTestRoot` is the single result classifier). Phase 2's `BuildSystemValidator` pattern is reused here for pytest pre-flight.
**Estimated:** 3–4 days. Medium-large complexity — requires PyCharm SDK familiarity.

---

## Context

Current `python_runtime_exec.run_tests` → `PytestActions.executePytestRun` → raw `ProcessBuilder` invocation of `pytest` / `python -m pytest`. Side effects:

- **Wrong interpreter.** Uses whatever is on `$PATH`; misses PyCharm's configured venv/Sdk. User sees `test passed in IDE` but agent can't reproduce because the agent ran against system Python.
- **No conftest fidelity.** Collection errors (`_collect`-suffixed nodes in the teamcity-messages protocol) are silently lost. Agent gets `0 tests`, user sees `ERROR tests/conftest.py`.
- **No per-event streaming.** Agent waits for process exit, parses text. Can't surface mid-run progress, can't kill on first failure.
- **No `RunContentDescriptor`.** No Run tool window tab — user can't see the agent's run alongside their own.

Target: use `PyTestConfigurationType` (the PyCharm-bundled extension point) the same way `JavaRuntimeExecTool` uses `JUnit`. This goes through IntelliJ's execution pipeline and gets all the upsides.

---

## Scope

**In:**
- Rewrite `python_runtime_exec.run_tests` path.
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/PythonRuntimeExecTool.kt`.
- New file: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/PytestNativeLauncher.kt` — mirrors `executeWithNativeRunner` from Java.
- Keep `PytestActions.executePytestRun` as a fallback (rename to `executePytestShellFallback`) for when PyCharm is unavailable or the native runner fails.

**Out:**
- Unittest runner native port — deferred. Most users use pytest; unittest can stay on shell.
- Nose / nose2 — deprecated, skip.

---

## Task list

### Task 3.1 — Detect `PyTestConfigurationType` at runtime

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContextDetector.kt`.

Extend `IdeContext` with:
```kotlin
val hasPyTestConfigType: Boolean
```

Detect via: `ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.any { it.id == "PyTest" || it.id == "py.test" }`.

Use `hasPyTestConfigType` to guard the native runner path; fall back to shell if absent.

### Task 3.2 — Create pytest `RunnerAndConfigurationSettings` via reflection

**New file:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/PytestNativeLauncher.kt`.

Because `:agent` has **zero compile-time dependency on the Python plugin** (per CLAUDE.md), this must be reflective — same pattern as `PythonProvider` in the PSI intelligence layer.

Structure:
```kotlin
internal class PytestNativeLauncher(private val project: Project) {
    fun createSettings(
        pytestPath: String?,    // e.g. "tests/test_api.py" or "tests/test_api.py::test_login"
        keywordExpr: String?,   // -k expression
        markerExpr: String?     // -m expression
    ): RunnerAndConfigurationSettings? {
        val configType = findPyTestConfigurationType() ?: return null
        val factory = configType.configurationFactories.firstOrNull() ?: return null
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration("[Agent] pytest", factory)
        val config = settings.configuration

        // Reflectively set:
        // config.setTarget(pytestPath)
        // config.setKeyword(keywordExpr)
        // config.setMarker(markerExpr)
        // config.setWorkingDirectory(project.basePath)

        settings.isTemporary = true
        return settings
    }
}
```

Fields on `AbstractPythonTestRunConfiguration` (the pytest config class):
- `setTarget(String)` / `getTarget()` — the path or node id.
- `setKeyword(String)` / `setMarker(String)` — for `-k` / `-m`.
- `setWorkingDirectory(String)` — critical for conftest.py discovery.
- `setAdditionalArguments(String)` — extra pytest args.

Source reference: `PyTestConfiguration.java` in the Python plugin (inspect via decompilation or the bundled sources jar).

### Task 3.3 — Port `executeWithNativeRunner` pattern to pytest

**File:** `PythonRuntimeExecTool.kt:120–138` (current `executeRunTests`).

Replace the delegate-to-shell with:

```kotlin
private suspend fun executeRunTests(params: JsonObject, project: Project): ToolResult {
    val ideContext = AgentService.get(project).ideContext
    if (!ideContext.hasPyTestConfigType) {
        return executePytestShellFallback(params, project)
    }

    val launcher = PytestNativeLauncher(project)
    val settings = launcher.createSettings(
        pytestPath = params["class_name"]?.jsonPrimitive?.contentOrNull,
        keywordExpr = params["method"]?.jsonPrimitive?.contentOrNull,
        markerExpr = params["markers"]?.jsonPrimitive?.contentOrNull
    ) ?: return executePytestShellFallback(params, project)

    return runPytestNative(settings, timeoutSeconds = ...)
}

private suspend fun runPytestNative(
    settings: RunnerAndConfigurationSettings,
    timeoutSeconds: Long
): ToolResult {
    // Mirror JavaRuntimeExecTool.executeWithNativeRunner:
    // - Use RunInvocation from Phase 2
    // - Subscribe ExecutionManager.EXECUTION_TOPIC for processNotStarted
    // - Subscribe TestResultsViewer.EventsListener.onTestingFinished
    // - Extract results via interpretTestRoot (from Phase 1 refactor)
    // - Handle timeout via withTimeoutOrNull
}
```

### Task 3.4 — Extend `interpretTestRoot` for pytest URL schemes

**File:** `RuntimeExecShared.kt:56–65` (`collectTestResults`).

Currently filters only `java:test://` and `java:suite://`. Pytest produces:
- `python://` URLs for function-level tests.
- `file://` URLs for module-level tests.
- `python<FQN>` for class-scope parameters.

Extend the filter: `listOf("java:test://", "java:suite://", "python://", "file://").any { url.startsWith(it) }`.

Also: `_collect`-suffixed node names are valid tests (synthetic collection-error failures). Don't filter them out.

### Task 3.5 — Unit tests against fake pytest SMTestProxy trees

**Extend:** `InterpretTestRootTest.kt` (from Phase 1).

Add fixtures:
- pytest happy path — 3 `python://` leaves, all passed.
- pytest collection error — 1 leaf named `tests/test_foo.py::test_bar_collect` with `isDefect=true`.
- pytest fixture error — setup failure surfaces as `isDefect=true` + errorMessage containing "test setup failed".
- pytest no collection (exit code 5 equivalent) — empty root.

### Task 3.6 — End-to-end test in a fixture PyCharm project

**New fixture:** `agent/src/test/testData/pytest-fixture-project/` with minimal `pytest.ini`, `tests/test_sample.py`, `conftest.py`.

Test: boot a heavyweight `BasePlatformTestCase` with PyCharm plugin enabled, run `python_runtime_exec.run_tests` via the tool, assert structured output matches expectations.

This may require running against PyCharm Community (PC) build — configure `intellijPlatform` Gradle plugin's `pyCharmCommunity` flavour for tests.

---

## Validation

```bash
./gradlew :agent:test --tests "*Pytest*" --tests "*Python*"
./gradlew verifyPlugin  # ensure PyCharm flavour still works
```

Manual: in PyCharm runIde:
1. Create pytest project with venv-scoped pytest.
2. Agent `python_runtime_exec run_tests`: verify Run tool window shows a new tab.
3. Induce conftest error: verify agent receives a structured failure with file/line, not "0 tests found".
4. Induce fixture error: verify `test setup failed` message surfaces.

## Exit criteria

- `python_runtime_exec.run_tests` uses `PyTestConfigurationType` when available.
- Shell fallback kept as `executePytestShellFallback` for pytest-not-available / non-PyCharm cases.
- `interpretTestRoot` handles pytest URL schemes.
- Manual test in PyCharm confirms Run tool window integration.
- conftest.py import error surfaces with file/line.

## Follow-ups (deferred)

- Unittest native port (same pattern, `PyUnitTestConfigurationType`).
- Stream per-event progress via `SMTRunnerEventsListener.TEST_STATUS` — upgrade the `ToolResult` streaming path so the LLM sees incremental progress. Requires streaming tool-result architecture change.
- Surface PyCharm's configured "Working directory" as a user-visible tool parameter override.
