# Coverage Tool — Native IntelliJ Coverage Executor

**Date:** 2026-04-03
**Status:** Draft
**Module:** `:agent`

## Goal

Expose IntelliJ's built-in "Run with Coverage" as an agent tool so the AI agent can run tests with line-level coverage collection and verify that its code edits are covered by tests — a self-verification loop.

## Non-Goals

- No IDE gutter marker integration (structured text data only)
- No SonarQube quality gate comparison
- No coverage trend tracking or history
- No Gradle/JaCoCo fallback path

## Tool Definition

**Meta-tool name:** `coverage`
**Category:** `runtime`
**Actions:** 2

### `run_with_coverage`

Run a test class or method with IntelliJ's native coverage executor. Returns combined test results + line-level coverage for all exercised files.

**Parameters:**

| Param | Type | Required | Description |
|---|---|---|---|
| `test_class` | string | yes | Fully qualified test class name (e.g., `com.example.FooServiceTest`) |
| `method` | string | no | Specific test method. If omitted, runs all tests in the class. |
| `module` | string | no | Module name. Auto-detected from class location if omitted. |

**Returns:**

```
Tests: 12 passed, 1 failed, 0 skipped (4.2s)

FAILED:
  FooServiceTest.testEdgeCase — expected <true> but was <false>
    at FooServiceTest.kt:45

Coverage (8 files):
  FooService.kt          — 78.5% (lines 45-48, 62, 71-73 uncovered)
  FooRepository.kt       — 100%
  FooController.kt       — 65.2% (lines 22-30, 55-60 uncovered)
  ...

Overall: 82.3% line coverage
```

### `get_file_coverage`

Get line-level coverage for a specific file from the last coverage run. No re-execution — reads from cached `ProjectData`.

**Parameters:**

| Param | Type | Required | Description |
|---|---|---|---|
| `file_path` | string | yes | Relative path to the source file (e.g., `src/main/kotlin/.../FooService.kt`) |

**Returns:**

```
FooService.kt — 78.5% line coverage (51/65 lines)

Uncovered lines:
  45-48: 0 hits
  62:    0 hits
  71-73: 0 hits
```

## Architecture

### Execution Flow

```
Agent calls coverage.run_with_coverage(test_class="FooServiceTest")
  -> CoverageTool.execute()
    -> Resolve test class via JavaPsiFacade (readAction)
    -> Find/create JUnit run config (reuse RuntimeExecTool pattern)
    -> Get CoverageExecutor instance
    -> Launch via CoverageExecutor instead of DefaultRunExecutor
    -> Wait for process termination (suspendCancellableCoroutine + ProgramRunner.Callback)
    -> Extract test results from SMTestProxy (reuse TestConsoleUtils)
    -> Extract coverage data from CoverageDataManager.getInstance(project)
      -> Get active CoverageSuitesBundle -> ProjectData
      -> Iterate ClassData -> LineData (hit counts per line)
    -> Cache ProjectData for get_file_coverage
    -> Format and return combined ToolResult
```

### Coverage Data Extraction

```kotlin
val dataManager = CoverageDataManager.getInstance(project)
val suitesBundle = dataManager.currentSuitesBundle
val projectData: ProjectData = suitesBundle?.coverageData ?: error("No coverage data")

projectData.classes.forEach { (className, classData) ->
    val lines: Array<LineData?> = classData.lines  // 1-indexed
    var covered = 0
    var total = 0
    val uncoveredRanges = mutableListOf<IntRange>()

    lines.forEachIndexed { lineNum, lineData ->
        if (lineData != null) {
            total++
            if (lineData.hits > 0) covered++
            else uncoveredRanges.addLineToRange(lineNum)
        }
    }
    // Build per-file summary with uncovered line ranges
}
```

### State Management

- `lastProjectData: ProjectData?` cached in `CoverageTool` instance
- Cleared on each new `run_with_coverage` call
- `get_file_coverage` reads from this cache

### File Location

`agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt`

## Reuse from RuntimeExecTool

The following logic is shared with the existing `RuntimeExecTool.executeWithNativeRunner()`:

- **Test framework detection:** PSI annotation scan for `@Test` (JUnit vs TestNG)
- **Run config creation:** `RunManager.getInstance(project)` + `JUnitConfiguration`
- **Test result extraction:** `SMTestProxy` via `TestConsoleUtils.unwrapToTestConsole()`
- **Build watchdog:** Polls `CompilerManager.isCompilationActive` (max 5 min, 2s interval)
- **Module detection:** `JavaPsiFacade.findClass()` + `ModuleUtilCore.findModuleForPsiElement()`

These should be extracted into shared helpers or called via delegation, not copy-pasted.

## Dependencies

### New Bundled Plugin Dependency

Add to `gradle.properties`:
```
platformBundledPlugins = ..., com.intellij.java.coverage
```

### Key IntelliJ Coverage API Classes

- `com.intellij.coverage.CoverageExecutor` — The executor that runs with coverage (obtained via `Executor.EXECUTOR_EXTENSION_NAME.findExtensionOrFail(CoverageExecutor::class.java)` or `ExecutorRegistry.getInstance().getExecutorById(CoverageExecutor.EXECUTOR_ID)`)
- `com.intellij.coverage.CoverageDataManager` — Service to access coverage results
- `com.intellij.coverage.CoverageSuitesBundle` — Container for coverage suite(s) from a run
- `com.intellij.rt.coverage.data.ProjectData` — Root coverage data object
- `com.intellij.rt.coverage.data.ClassData` — Per-class coverage
- `com.intellij.rt.coverage.data.LineData` — Per-line hit count

## Tool Registration

- Register in `ToolRegistry` under category `runtime`
- Add to `DynamicToolSelector` keyword triggers: `"coverage"`, `"covered"`, `"uncovered"`, `"test coverage"`
- Tool is always-active when runtime tools are enabled (no separate toggle)

## Error Handling

| Scenario | Response |
|---|---|
| Coverage plugin not available | `ToolResult(isError=true, summary="Coverage plugin not available in this IDE")` |
| Test class not found | `ToolResult(isError=true, summary="Test class 'X' not found")` |
| Tests fail | Return coverage data anyway (coverage is collected regardless of test outcomes) |
| `get_file_coverage` with no prior run | `ToolResult(isError=true, summary="No coverage data available. Run run_with_coverage first.")` |
| File not in coverage data | `ToolResult(isError=true, summary="No coverage data for 'X' — it may not be exercised by the test")` |
| Compilation fails before test starts | `ToolResult(isError=true, summary="Build failed: <error>")` |

## Testing

### Unit Tests

- Action dispatch and parameter validation
- Coverage data formatting: mock `ProjectData` with known `LineData` arrays, verify output string matches expected format (uncovered ranges, percentages)
- Error cases: no coverage data, file not found in data, missing params

### Manual Integration Testing

Coverage executor requires a real IDE environment — verify via `runIde`:
- Run a test class with coverage, check that `CoverageDataManager` returns data
- Verify uncovered line ranges match what the IDE's gutter would show

## Agent Usage Pattern

```
1. Agent edits FooService.kt (adds new method)
2. Agent calls: coverage.run_with_coverage(test_class="FooServiceTest")
3. Response shows: "lines 45-52 uncovered" in FooService.kt
4. Agent writes test for the uncovered lines
5. Agent calls: coverage.run_with_coverage(test_class="FooServiceTest")
6. Response shows: 100% coverage for FooService.kt
7. Agent proceeds with attempt_completion
```
