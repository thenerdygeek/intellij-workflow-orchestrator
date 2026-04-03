# Coverage Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `coverage` meta-tool that runs tests via IntelliJ's native CoverageExecutor and returns line-level coverage data so the agent can verify its code edits are tested.

**Architecture:** New `CoverageTool` class implementing `AgentTool` with 2 actions (`run_with_coverage`, `get_file_coverage`). Reuses `RuntimeExecTool`'s JUnit config creation and test result extraction patterns. Launches tests via `CoverageExecutor` instead of `DefaultRunExecutor`, then reads `ProjectData` from `CoverageDataManager` to extract per-file, per-line hit counts.

**Tech Stack:** IntelliJ Coverage API (`com.intellij.coverage.*`), IntelliJ Coverage Runtime (`com.intellij.rt.coverage.data.*`), existing `TestConsoleUtils` for test result extraction.

**Spec:** `docs/superpowers/specs/2026-04-03-coverage-tool-design.md`

---

### Task 1: Add Coverage Plugin Dependency

**Files:**
- Modify: `gradle.properties:17`

- [ ] **Step 1: Add `com.intellij.java.coverage` to bundled plugins**

In `gradle.properties`, update the `platformBundledPlugins` line:

```properties
platformBundledPlugins = com.intellij.java, org.jetbrains.idea.maven, com.intellij.spring, com.intellij.spring.boot, com.intellij.java.coverage
```

- [ ] **Step 2: Verify the dependency resolves**

Run:
```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL (the coverage plugin classes should now be available on the classpath)

- [ ] **Step 3: Commit**

```bash
git add gradle.properties
git commit -m "chore: add com.intellij.java.coverage bundled plugin dependency"
```

---

### Task 2: Create CoverageTool with Action Dispatch and Parameter Definitions

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt`

- [ ] **Step 1: Write the test for action dispatch and parameter validation**

Create test file `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageToolTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.runtime

import com.workflow.orchestrator.agent.tools.ToolResult
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoverageToolTest {

    private val tool = CoverageTool()

    @Test
    fun `tool name is coverage`() {
        assertEquals("coverage", tool.name)
    }

    @Test
    fun `missing action returns error`() = runTest {
        val params = JsonObject(emptyMap())
        val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.summary.contains("action"))
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val params = JsonObject(mapOf("action" to JsonPrimitive("unknown_action")))
        val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown action"))
    }

    @Test
    fun `run_with_coverage requires test_class`() = runTest {
        val params = JsonObject(mapOf("action" to JsonPrimitive("run_with_coverage")))
        val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("test_class"))
    }

    @Test
    fun `get_file_coverage requires file_path`() = runTest {
        val params = JsonObject(mapOf("action" to JsonPrimitive("get_file_coverage")))
        val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("file_path"))
    }

    @Test
    fun `get_file_coverage with no prior run returns error`() = runTest {
        val params = JsonObject(mapOf(
            "action" to JsonPrimitive("get_file_coverage"),
            "file_path" to JsonPrimitive("src/main/kotlin/Foo.kt")
        ))
        val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("No coverage data"))
    }

    @Test
    fun `parameters include both actions`() {
        val params = tool.parameters
        val actionProp = params.properties["action"]
        assertNotNull(actionProp)
        assertTrue(actionProp!!.enumValues!!.contains("run_with_coverage"))
        assertTrue(actionProp.enumValues!!.contains("get_file_coverage"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.runtime.CoverageToolTest" -x instrumentCode
```
Expected: FAIL — `CoverageTool` class does not exist yet

- [ ] **Step 3: Create the CoverageTool class with action dispatch**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

/**
 * Coverage meta-tool — runs tests with IntelliJ's native coverage executor
 * and returns line-level coverage data for agent self-verification.
 *
 * Actions:
 * - run_with_coverage: Run a test class/method with coverage, return test results + coverage
 * - get_file_coverage: Get line-level coverage for a specific file from the last run
 */
class CoverageTool : AgentTool {

    override val name = "coverage"

    override val description = """
Code coverage — run tests with line-level coverage collection.

Actions and their parameters:
- run_with_coverage(test_class, method?, timeout?) → Run tests with coverage, returns test results + per-file line coverage with uncovered line ranges
- get_file_coverage(file_path) → Get line-level coverage for a specific file from the last coverage run (no re-execution)

Use run_with_coverage after editing code to verify your changes are tested. Check uncovered lines and write additional tests if needed.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("run_with_coverage", "get_file_coverage")
            ),
            "test_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class name — for run_with_coverage"
            ),
            "method" to ParameterProperty(
                type = "string",
                description = "Specific test method name — for run_with_coverage"
            ),
            "timeout" to ParameterProperty(
                type = "integer",
                description = "Seconds before test process is killed (default: 300, max: 900) — for run_with_coverage"
            ),
            "file_path" to ParameterProperty(
                type = "string",
                description = "Relative source file path (e.g. src/main/kotlin/.../Foo.kt) — for get_file_coverage"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description shown in approval dialog — for run_with_coverage"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.TOOLER
    )

    /** Cached coverage data from the last run_with_coverage invocation. */
    private val lastCoverageData = AtomicReference<CoverageSnapshot?>(null)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'action' parameter is required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "run_with_coverage" -> executeRunWithCoverage(params, project)
            "get_file_coverage" -> executeGetFileCoverage(params)
            else -> ToolResult(
                "Unknown action '$action'. Available: run_with_coverage, get_file_coverage",
                "Unknown action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    private suspend fun executeRunWithCoverage(params: JsonObject, project: Project): ToolResult {
        val className = params["test_class"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'test_class' parameter is required for run_with_coverage",
                "Error: missing test_class",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        // Placeholder — implemented in Task 3
        return ToolResult("Not yet implemented", "Not implemented", 5, isError = true)
    }

    private fun executeGetFileCoverage(params: JsonObject): ToolResult {
        val filePath = params["file_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'file_path' parameter is required for get_file_coverage",
                "Error: missing file_path",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val snapshot = lastCoverageData.get()
            ?: return ToolResult(
                "No coverage data available. Run run_with_coverage first.",
                "No coverage data",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val fileCoverage = snapshot.files.entries.find { (key, _) ->
            key.endsWith(filePath) || filePath.endsWith(key)
        }?.value
            ?: return ToolResult(
                "No coverage data for '$filePath' — it may not be exercised by the test.",
                "File not in coverage data",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val output = formatFileCoverage(filePath.substringAfterLast('/'), fileCoverage)
        return ToolResult(output, "Coverage for $filePath", output.length / 4)
    }

    companion object {
        private const val DEFAULT_TIMEOUT = 300L
        private const val MAX_TIMEOUT = 900L
    }
}

/** Snapshot of coverage data from a single run. */
data class CoverageSnapshot(
    val files: Map<String, FileCoverageResult>
)

data class FileCoverageResult(
    val coveredLines: Int,
    val totalLines: Int,
    val uncoveredRanges: List<IntRange>
) {
    val coveragePercent: Double
        get() = if (totalLines == 0) 0.0 else (coveredLines.toDouble() / totalLines * 100)
}
```

- [ ] **Step 4: Add the formatFileCoverage helper**

Add to `CoverageTool.kt` (inside the class, after `executeGetFileCoverage`):

```kotlin
    private fun formatFileCoverage(fileName: String, coverage: FileCoverageResult): String {
        val sb = StringBuilder()
        sb.appendLine("$fileName — ${"%.1f".format(coverage.coveragePercent)}% line coverage (${coverage.coveredLines}/${coverage.totalLines} lines)")
        if (coverage.uncoveredRanges.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Uncovered lines:")
            for (range in coverage.uncoveredRanges) {
                if (range.first == range.last) {
                    sb.appendLine("  ${range.first}: 0 hits")
                } else {
                    sb.appendLine("  ${range.first}-${range.last}: 0 hits")
                }
            }
        }
        return sb.toString().trimEnd()
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run:
```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.runtime.CoverageToolTest" -x instrumentCode
```
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageToolTest.kt
git commit -m "feat(agent): add CoverageTool skeleton with action dispatch and parameter validation"
```

---

### Task 3: Implement `run_with_coverage` — Launch Tests via CoverageExecutor

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt`

- [ ] **Step 1: Replace the `executeRunWithCoverage` placeholder with the full implementation**

Replace the `executeRunWithCoverage` method and add helper methods in `CoverageTool.kt`:

```kotlin
    private suspend fun executeRunWithCoverage(params: JsonObject, project: Project): ToolResult {
        val className = params["test_class"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'test_class' parameter is required for run_with_coverage",
                "Error: missing test_class",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val method = params["method"]?.jsonPrimitive?.contentOrNull
        val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: DEFAULT_TIMEOUT)
            .coerceIn(1, MAX_TIMEOUT)

        val testTarget = if (method != null) "$className#$method" else className

        // Check that the coverage executor is available
        val coverageExecutor = try {
            com.intellij.execution.ExecutorRegistry.getInstance().getExecutorById("Coverage")
        } catch (_: Exception) { null }
            ?: return ToolResult(
                "Coverage plugin not available in this IDE. Ensure com.intellij.java.coverage is installed.",
                "Coverage plugin unavailable",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Create the JUnit run configuration (reuse RuntimeExecTool pattern)
        val settings = createJUnitRunSettings(project, className, method)
            ?: return ToolResult(
                "Failed to create test run configuration for '$className'. " +
                    "Verify the class exists and has @Test methods.",
                "Config creation failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val processHandlerRef = AtomicReference<com.intellij.execution.process.ProcessHandler?>(null)
        val descriptorRef = AtomicReference<com.intellij.execution.ui.RunContentDescriptor?>(null)

        // Launch with CoverageExecutor and wait for completion
        val testResult = withTimeoutOrNull(timeoutSeconds * 1000) {
            suspendCancellableCoroutine { continuation ->
                com.intellij.openapi.application.invokeLater {
                    try {
                        val env = com.intellij.execution.runners.ExecutionEnvironmentBuilder
                            .createOrNull(coverageExecutor, settings)
                            ?.build()

                        if (env == null) {
                            if (continuation.isActive) continuation.resume(null)
                            return@invokeLater
                        }

                        val callback = object : com.intellij.execution.runners.ProgramRunner.Callback {
                            override fun processStarted(descriptor: com.intellij.execution.ui.RunContentDescriptor?) {
                                if (descriptor == null) {
                                    if (continuation.isActive) continuation.resume(null)
                                    return
                                }
                                descriptorRef.set(descriptor)
                                val handler = descriptor.processHandler
                                processHandlerRef.set(handler)

                                val testConsole = com.workflow.orchestrator.agent.tools.TestConsoleUtils
                                    .unwrapToTestConsole(descriptor.executionConsole)
                                if (testConsole != null) {
                                    testConsole.resultsViewer.addEventsListener(
                                        object : com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer.EventsListener {
                                            override fun onTestingFinished(sender: com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer) {
                                                val root = sender.testsRootNode
                                                    as? com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy
                                                if (root != null && continuation.isActive) {
                                                    val results = collectTestResults(root)
                                                    continuation.resume(results)
                                                }
                                            }
                                        })
                                } else if (handler != null) {
                                    handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                                        override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                                            // Delay to let test tree finalize
                                            java.util.Timer().schedule(object : java.util.TimerTask() {
                                                override fun run() {
                                                    if (continuation.isActive) {
                                                        val root = com.workflow.orchestrator.agent.tools.TestConsoleUtils
                                                            .findTestRoot(descriptor)
                                                        val results = root?.let { collectTestResults(it) } ?: emptyList()
                                                        continuation.resume(results)
                                                    }
                                                }
                                            }, 2000)
                                        }
                                    })
                                } else {
                                    if (continuation.isActive) continuation.resume(emptyList())
                                }
                            }
                        }

                        try {
                            com.intellij.execution.ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
                        } catch (_: NoSuchMethodError) {
                            env.callback = callback
                            com.intellij.execution.ProgramRunnerUtil.executeConfiguration(env, false, true)
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }

        // Handle timeout
        if (testResult == null && processHandlerRef.get() != null) {
            processHandlerRef.get()?.destroyProcess()
            return ToolResult(
                "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s for $testTarget.",
                "Test timeout",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val allTests = testResult ?: emptyList()

        // Extract coverage data from CoverageDataManager
        val coverageSnapshot = extractCoverageData(project)
        lastCoverageData.set(coverageSnapshot)

        // Format combined output
        val output = formatCombinedResult(allTests, coverageSnapshot, testTarget)
        val hasFailures = allTests.any { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
        return ToolResult(
            content = output,
            summary = if (hasFailures) "Tests failed with coverage" else "Tests passed with coverage",
            tokenEstimate = output.length / 4,
            isError = hasFailures
        )
    }
```

- [ ] **Step 2: Add the JUnit config creation helper (delegates to RuntimeExecTool pattern)**

Add to `CoverageTool.kt`:

```kotlin
    private fun createJUnitRunSettings(
        project: Project, className: String, method: String?
    ): com.intellij.execution.RunnerAndConfigurationSettings? {
        return try {
            val runManager = com.intellij.execution.RunManager.getInstance(project)
            val configTypeId = "JUnit"
            val testConfigType = com.intellij.execution.configurations.ConfigurationType
                .CONFIGURATION_TYPE_EP.extensionList
                .find { it.id == configTypeId || it.displayName == configTypeId }
                ?: return null

            val factory = testConfigType.configurationFactories.firstOrNull() ?: return null
            val configName = "[Agent:Coverage] ${className.substringAfterLast('.')}${if (method != null) ".$method" else ""}"
            val settings = runManager.createConfiguration(configName, factory)
            val config = settings.configuration

            try {
                val getDataMethod = config.javaClass.methods.find { it.name == "getPersistentData" }
                val data = getDataMethod?.invoke(config) ?: return null
                val testObjectField = data.javaClass.getField("TEST_OBJECT")
                val mainClassField = data.javaClass.getField("MAIN_CLASS_NAME")
                testObjectField.set(data, if (method != null) "method" else "class")
                mainClassField.set(data, className)

                try {
                    val packageField = data.javaClass.getField("PACKAGE_NAME")
                    packageField.set(data, className.substringBeforeLast('.', ""))
                } catch (_: Exception) {}

                if (method != null) {
                    val methodField = data.javaClass.getField("METHOD_NAME")
                    methodField.set(data, method)
                }
            } catch (_: Exception) { return null }

            // Set the module
            val testModule = findModuleForClass(project, className) ?: return null
            try {
                val setModuleMethod = config.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                setModuleMethod.invoke(config, testModule)
            } catch (_: Exception) {
                try {
                    val getConfigModule = config.javaClass.getMethod("getConfigurationModule")
                    val configModule = getConfigModule.invoke(config)
                    val setModule = configModule.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                    setModule.invoke(configModule, testModule)
                } catch (_: Exception) {}
            }

            settings.isTemporary = true
            settings
        } catch (_: Exception) { null }
    }

    private fun findModuleForClass(project: Project, className: String): com.intellij.openapi.module.Module? {
        return try {
            com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.module.Module?, Exception> {
                val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                    ?: return@compute null
                com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(psiClass)
            }
        } catch (_: Exception) { null }
    }
```

- [ ] **Step 3: Add imports at the top of the file**

Add these imports to `CoverageTool.kt`:

```kotlin
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
```

- [ ] **Step 4: Verify compilation**

Run:
```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt
git commit -m "feat(agent): implement run_with_coverage — launch tests via CoverageExecutor"
```

---

### Task 4: Implement Coverage Data Extraction from CoverageDataManager

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageToolTest.kt`

- [ ] **Step 1: Write the test for coverage data formatting**

Add to `CoverageToolTest.kt`:

```kotlin
    @Test
    fun `formatCoverageSnapshot formats summary correctly`() {
        val snapshot = CoverageSnapshot(
            files = mapOf(
                "com/example/FooService" to FileCoverageResult(
                    coveredLines = 8,
                    totalLines = 10,
                    uncoveredRanges = listOf(45..48, 62..62)
                ),
                "com/example/BarService" to FileCoverageResult(
                    coveredLines = 20,
                    totalLines = 20,
                    uncoveredRanges = emptyList()
                )
            )
        )

        val output = CoverageTool.formatCoverageSnapshotPublic(snapshot)
        assertTrue(output.contains("FooService"))
        assertTrue(output.contains("80.0%"))
        assertTrue(output.contains("45-48"))
        assertTrue(output.contains("62"))
        assertTrue(output.contains("BarService"))
        assertTrue(output.contains("100.0%"))
    }

    @Test
    fun `FileCoverageResult calculates percentage`() {
        val result = FileCoverageResult(coveredLines = 75, totalLines = 100, uncoveredRanges = emptyList())
        assertEquals(75.0, result.coveragePercent, 0.01)
    }

    @Test
    fun `FileCoverageResult handles zero total lines`() {
        val result = FileCoverageResult(coveredLines = 0, totalLines = 0, uncoveredRanges = emptyList())
        assertEquals(0.0, result.coveragePercent, 0.01)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.runtime.CoverageToolTest" -x instrumentCode
```
Expected: FAIL — `formatCoverageSnapshotPublic` does not exist

- [ ] **Step 3: Add the `extractCoverageData` method**

Add to `CoverageTool.kt`:

```kotlin
    private fun extractCoverageData(project: Project): CoverageSnapshot {
        return try {
            val dataManager = com.intellij.coverage.CoverageDataManager.getInstance(project)
            val suitesBundle = dataManager.currentSuitesBundle
            val projectData = suitesBundle?.coverageData
                ?: return CoverageSnapshot(emptyMap())

            val files = mutableMapOf<String, FileCoverageResult>()

            for ((className, classData) in projectData.classes) {
                val lines = classData.lines ?: continue
                var covered = 0
                var total = 0
                val uncoveredRanges = mutableListOf<IntRange>()
                var uncoveredStart: Int? = null

                for (i in lines.indices) {
                    val lineData = lines[i] ?: continue
                    total++
                    if (lineData.hits > 0) {
                        covered++
                        // Close any open uncovered range
                        if (uncoveredStart != null) {
                            uncoveredRanges.add(uncoveredStart..i - 1)
                            uncoveredStart = null
                        }
                    } else {
                        if (uncoveredStart == null) uncoveredStart = i
                    }
                }
                // Close trailing uncovered range
                if (uncoveredStart != null && total > 0) {
                    val lastInstrumentedLine = lines.indices.last { lines[it] != null }
                    uncoveredRanges.add(uncoveredStart..lastInstrumentedLine)
                }

                if (total > 0) {
                    files[className] = FileCoverageResult(covered, total, uncoveredRanges)
                }
            }

            CoverageSnapshot(files)
        } catch (e: Exception) {
            CoverageSnapshot(emptyMap())
        }
    }
```

- [ ] **Step 4: Add the `formatCombinedResult` and `formatCoverageSummary` methods**

Add to `CoverageTool.kt`:

```kotlin
    private fun formatCombinedResult(
        tests: List<TestResultEntry>,
        snapshot: CoverageSnapshot,
        testTarget: String
    ): String {
        val sb = StringBuilder()

        // Test results section
        val passed = tests.count { it.status == TestStatus.PASSED }
        val failed = tests.count { it.status == TestStatus.FAILED }
        val errors = tests.count { it.status == TestStatus.ERROR }
        val skipped = tests.count { it.status == TestStatus.SKIPPED }
        val totalDuration = tests.sumOf { it.durationMs }

        sb.appendLine("Tests: $passed passed, $failed failed, $errors error, $skipped skipped (${formatDuration(totalDuration)})")
        sb.appendLine()

        val failedTests = tests.filter { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
        if (failedTests.isNotEmpty()) {
            sb.appendLine("FAILED:")
            for (test in failedTests) {
                sb.appendLine("  ${test.name} — ${test.errorMessage ?: "unknown error"}")
                for (frame in test.stackTrace.take(3)) {
                    sb.appendLine("    $frame")
                }
            }
            sb.appendLine()
        }

        // Coverage section
        sb.append(formatCoverageSummary(snapshot))

        return sb.toString().trimEnd()
    }

    internal fun formatCoverageSummary(snapshot: CoverageSnapshot): String {
        if (snapshot.files.isEmpty()) return "Coverage: No data collected.\n"

        val sb = StringBuilder()
        val totalCovered = snapshot.files.values.sumOf { it.coveredLines }
        val totalLines = snapshot.files.values.sumOf { it.totalLines }
        val overallPercent = if (totalLines == 0) 0.0 else totalCovered.toDouble() / totalLines * 100

        sb.appendLine("Coverage (${snapshot.files.size} files):")
        for ((className, cov) in snapshot.files.entries.sortedBy { it.value.coveragePercent }) {
            val shortName = className.substringAfterLast('/').substringAfterLast('.')
            val pct = "%.1f".format(cov.coveragePercent)
            if (cov.uncoveredRanges.isEmpty()) {
                sb.appendLine("  $shortName — $pct%")
            } else {
                val ranges = cov.uncoveredRanges.joinToString(", ") { r ->
                    if (r.first == r.last) "${r.first}" else "${r.first}-${r.last}"
                }
                sb.appendLine("  $shortName — $pct% (lines $ranges uncovered)")
            }
        }
        sb.appendLine()
        sb.appendLine("Overall: ${"%.1f".format(overallPercent)}% line coverage")
        return sb.toString()
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        else -> "${"%.1f".format(ms / 1000.0)}s"
    }

    companion object {
        private const val DEFAULT_TIMEOUT = 300L
        private const val MAX_TIMEOUT = 900L

        /** Exposed for testing. */
        fun formatCoverageSnapshotPublic(snapshot: CoverageSnapshot): String {
            return CoverageTool().formatCoverageSummary(snapshot)
        }
    }
```

- [ ] **Step 5: Add the test result helper types**

Add to `CoverageTool.kt` (inside the class):

```kotlin
    private fun collectTestResults(root: com.intellij.execution.testframework.sm.runner.SMTestProxy): List<TestResultEntry> {
        return root.allTests
            .filterIsInstance<com.intellij.execution.testframework.sm.runner.SMTestProxy>()
            .filter { it.isLeaf }
            .map { proxy ->
                val status = when {
                    proxy.isDefect -> {
                        if (proxy.stacktrace?.contains("AssertionError") == true ||
                            proxy.stacktrace?.contains("AssertionFailedError") == true
                        ) TestStatus.FAILED else TestStatus.ERROR
                    }
                    proxy.isIgnored -> TestStatus.SKIPPED
                    else -> TestStatus.PASSED
                }
                TestResultEntry(
                    name = proxy.name,
                    status = status,
                    durationMs = proxy.duration?.toLong() ?: 0L,
                    errorMessage = proxy.errorMessage,
                    stackTrace = proxy.stacktrace?.lines()
                        ?.filter { it.trimStart().startsWith("at ") || it.contains("Exception") || it.contains("Error") }
                        ?.take(5) ?: emptyList()
                )
            }
    }

    private data class TestResultEntry(
        val name: String,
        val status: TestStatus,
        val durationMs: Long,
        val errorMessage: String?,
        val stackTrace: List<String>
    )

    private enum class TestStatus { PASSED, FAILED, ERROR, SKIPPED }
```

- [ ] **Step 6: Run tests to verify they pass**

Run:
```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.runtime.CoverageToolTest" -x instrumentCode
```
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageToolTest.kt
git commit -m "feat(agent): implement coverage data extraction and result formatting"
```

---

### Task 5: Register CoverageTool in ToolRegistry and DynamicToolSelector

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:286`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`

- [ ] **Step 1: Register CoverageTool in AgentService**

In `AgentService.kt`, after line 286 (`register(RuntimeExecTool())`), add:

```kotlin
            register(CoverageTool())
```

Add the import at the top:
```kotlin
import com.workflow.orchestrator.agent.tools.runtime.CoverageTool
```

- [ ] **Step 2: Add coverage tool to DynamicToolSelector keyword triggers**

In `DynamicToolSelector.kt`, add `"coverage"` to `RUNTIME_TOOL_NAMES`:

```kotlin
    private val RUNTIME_TOOL_NAMES = setOf("runtime_config", "runtime_exec", "coverage")
```

And add a dedicated `ToolGroup` for coverage-specific keywords. Find the runtime ToolGroup (the one with keywords "run", "execute", "process", etc.) and add after it:

```kotlin
        ToolGroup(
            setOf("coverage", "covered", "uncovered", "test coverage", "line coverage",
                "code coverage", "coverage report", "run with coverage"),
            RUNTIME_TOOL_NAMES
        ),
```

- [ ] **Step 3: Verify compilation**

Run:
```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt
git commit -m "feat(agent): register CoverageTool in ToolRegistry and DynamicToolSelector"
```

---

### Task 6: Update Documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Add coverage to the tools table in agent/CLAUDE.md**

In the `## Tools` table, add a new row in the Runtime section:

```markdown
| Coverage | **coverage** (2 actions: run_with_coverage, get_file_coverage) |
```

- [ ] **Step 2: Update root CLAUDE.md if needed**

Update the `:agent` module description to mention coverage:

```
| `:agent` | AI coding agent — ReAct loop, 110 tools (67 registered, 15 meta-tools), agent tool for subagent spawning, plan persistence, JCEF chat UI, real-time steering, code coverage |
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md agent/CLAUDE.md
git commit -m "docs: add coverage tool to agent module documentation"
```
