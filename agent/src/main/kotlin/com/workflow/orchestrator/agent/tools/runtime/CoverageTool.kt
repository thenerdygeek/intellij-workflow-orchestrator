package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Coverage tool — runs tests with IntelliJ's native coverage executor and returns
 * line-level coverage data.
 *
 * Actions:
 * - run_with_coverage: Launch tests via CoverageExecutor, return test results + line-level coverage
 * - get_file_coverage: Re-read coverage for a specific file from cached data (no re-run)
 */
class CoverageTool : AgentTool {

    override val name = "coverage"

    override val description = """
Run tests with coverage analysis and retrieve line-level coverage data.

Actions and their parameters:
- run_with_coverage(test_class, method?, timeout?) → Run tests via IntelliJ Coverage executor, return test results + line-level coverage data
- get_file_coverage(file_path) → Re-read coverage for a specific file from the last coverage run (no re-run)
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
                description = "Path to source file to get coverage for — for get_file_coverage"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER
    )

    /** Cached coverage snapshot from the last run_with_coverage execution. */
    @Volatile
    internal var lastSnapshot: CoverageSnapshot? = null

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "run_with_coverage" -> executeRunWithCoverage(params, project)
            "get_file_coverage" -> executeGetFileCoverage(params)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: run_with_coverage, get_file_coverage",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: run_with_coverage
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeRunWithCoverage(params: JsonObject, project: Project): ToolResult {
        val testClass = params["test_class"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'test_class' parameter is required for run_with_coverage",
                "Error: missing test_class",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val method = params["method"]?.jsonPrimitive?.contentOrNull
        val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: DEFAULT_TIMEOUT)
            .coerceIn(1, MAX_TIMEOUT)

        val testTarget = if (method != null) "$testClass#$method" else testClass

        // Create JUnit run configuration
        val settings = createJUnitRunSettings(project, testClass, method)
            ?: return ToolResult(
                "Error: Could not create run configuration for '$testTarget'. " +
                    "Ensure the class exists and JUnit plugin is available.",
                "Error: config creation failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Find coverage executor
        val coverageExecutor = com.intellij.execution.ExecutorRegistry.getInstance()
            .getExecutorById("Coverage")
            ?: return ToolResult(
                "Error: Coverage executor not available. Ensure the Coverage plugin is enabled.",
                "Error: no coverage executor",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val processHandlerRef = AtomicReference<ProcessHandler?>(null)

        val result = withTimeoutOrNull<CoverageRunResult>(timeoutSeconds * 1000) {
            suspendCancellableCoroutine { continuation ->
                invokeLater {
                    try {
                        val env = ExecutionEnvironmentBuilder
                            .createOrNull(coverageExecutor, settings)
                            ?.build()

                        if (env == null) {
                            if (continuation.isActive) continuation.resume(
                                CoverageRunResult("Error: could not build execution environment", "Error")
                            )
                            return@invokeLater
                        }

                        val callback = object : ProgramRunner.Callback {
                            override fun processStarted(descriptor: RunContentDescriptor?) {
                                if (descriptor == null) {
                                    if (continuation.isActive) continuation.resume(
                                        CoverageRunResult("Error: no descriptor from coverage run", "Error")
                                    )
                                    return
                                }
                                val handler = descriptor.processHandler
                                processHandlerRef.set(handler)

                                val testConsole = TestConsoleUtils.unwrapToTestConsole(descriptor.executionConsole)
                                if (testConsole != null) {
                                    testConsole.resultsViewer.addEventsListener(object : TestResultsViewer.EventsListener {
                                        override fun onTestingFinished(sender: TestResultsViewer) {
                                            val root = sender.testsRootNode as? SMTestProxy.SMRootTestProxy
                                            if (root != null && continuation.isActive) {
                                                continuation.resume(formatTestResults(root, testTarget))
                                            }
                                        }
                                    })
                                } else if (handler != null) {
                                    handler.addProcessListener(object : ProcessAdapter() {
                                        override fun processTerminated(event: ProcessEvent) {
                                            // Delay to let the test tree finalize (matches RuntimeExecTool pattern)
                                            java.util.Timer().schedule(object : java.util.TimerTask() {
                                                override fun run() {
                                                    if (continuation.isActive) {
                                                        val root = TestConsoleUtils.findTestRoot(descriptor)
                                                        if (root != null) {
                                                            continuation.resume(formatTestResults(root, testTarget))
                                                        } else {
                                                            continuation.resume(
                                                                CoverageRunResult(
                                                                    "Tests completed for $testTarget but no structured results available.",
                                                                    "Tests completed"
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }, 2000)
                                        }
                                    })
                                } else {
                                    if (continuation.isActive) continuation.resume(
                                        CoverageRunResult("Error: no process handler", "Error")
                                    )
                                }
                            }
                        }

                        try {
                            ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
                        } catch (_: NoSuchMethodError) {
                            env.callback = callback
                            ProgramRunnerUtil.executeConfiguration(env, false, true)
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(
                            CoverageRunResult(
                                "Error launching coverage run: ${e.message}",
                                "Error: ${e.javaClass.simpleName}"
                            )
                        )
                    }
                }
            }
        }

        if (result == null) {
            processHandlerRef.get()?.destroyProcess()
            return ToolResult(
                "[TIMEOUT] Coverage run timed out after ${timeoutSeconds}s for $testTarget.",
                "Coverage timeout",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Extract coverage data
        val snapshot = extractCoverageSnapshot(project)
        lastSnapshot = snapshot

        val coverageSummary = if (snapshot != null && snapshot.files.isNotEmpty()) {
            formatCoverageSummary(snapshot)
        } else {
            "\nCoverage: No coverage data available (coverage engine may not have reported yet)."
        }

        val content = "${result.testResult}\n$coverageSummary"
        return ToolResult(
            content = content,
            summary = result.testSummary + if (snapshot != null) " | ${snapshot.files.size} files covered" else "",
            tokenEstimate = content.length / 4
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_file_coverage
    // ══════════════════════════════════════════════════════════════════════

    private fun executeGetFileCoverage(params: JsonObject): ToolResult {
        val filePath = params["file_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'file_path' parameter is required for get_file_coverage",
                "Error: missing file_path",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val snapshot = lastSnapshot
            ?: return ToolResult(
                "No coverage data available. Run 'run_with_coverage' first to collect coverage data.",
                "No coverage data",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Try to find the file by exact path or by filename
        val fileName = filePath.substringAfterLast('/')
        val entry = snapshot.files[filePath]
            ?: snapshot.files.entries.find { it.key.endsWith(fileName) }?.value
            ?: return ToolResult(
                "No coverage data found for '$filePath'. " +
                    "Available files: ${snapshot.files.keys.joinToString(", ")}",
                "File not in coverage",
                20,
                isError = true
            )

        val content = formatFileCoverage(fileName, entry)
        return ToolResult(
            content = content,
            summary = "$fileName — ${String.format("%.1f", entry.coveragePercent)}% coverage",
            tokenEstimate = content.length / 4
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Coverage data extraction (uses reflection to avoid compile-time
    // dependency on coverage agent classes)
    // ══════════════════════════════════════════════════════════════════════

    private fun extractCoverageSnapshot(project: Project): CoverageSnapshot? {
        return try {
            // CoverageDataManager is in intellij.platform.coverage module
            val dataManagerClass = Class.forName("com.intellij.coverage.CoverageDataManager")
            val getInstanceMethod = dataManagerClass.getMethod("getInstance", Project::class.java)
            val dataManager = getInstanceMethod.invoke(null, project) ?: return null

            val getCurrentSuitesBundleMethod = dataManagerClass.getMethod("getCurrentSuitesBundle")
            val suitesBundle = getCurrentSuitesBundleMethod.invoke(dataManager) ?: return null

            val getCoverageDataMethod = suitesBundle.javaClass.getMethod("getCoverageData")
            val projectData = getCoverageDataMethod.invoke(suitesBundle) ?: return null

            val getClassesMethod = projectData.javaClass.getMethod("getClasses")
            @Suppress("UNCHECKED_CAST")
            val classes = getClassesMethod.invoke(projectData) as? Map<String, Any> ?: return null

            val files = mutableMapOf<String, FileCoverageResult>()

            for ((className, classData) in classes) {
                val getLinesMethod = classData.javaClass.getMethod("getLines")
                val lines = getLinesMethod.invoke(classData) as? Array<*> ?: continue

                var covered = 0
                var total = 0
                val uncoveredLines = mutableListOf<Int>()

                for (element in lines) {
                    if (element == null) continue
                    total++
                    val getHitsMethod = element.javaClass.getMethod("getHits")
                    val hits = getHitsMethod.invoke(element) as? Int ?: 0
                    if (hits > 0) {
                        covered++
                    } else {
                        val getLineNumberMethod = element.javaClass.getMethod("getLineNumber")
                        val lineNumber = getLineNumberMethod.invoke(element) as? Int ?: continue
                        uncoveredLines.add(lineNumber)
                    }
                }

                if (total > 0) {
                    files[className] = FileCoverageResult(
                        coveredLines = covered,
                        totalLines = total,
                        uncoveredRanges = collapseToRanges(uncoveredLines)
                    )
                }
            }

            CoverageSnapshot(files)
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Formatting
    // ══════════════════════════════════════════════════════════════════════

    internal fun formatCoverageSummary(snapshot: CoverageSnapshot): String {
        val sb = StringBuilder()
        sb.appendLine("\nCoverage (${snapshot.files.size} files):")

        val sortedFiles = snapshot.files.entries.sortedBy { it.value.coveragePercent }
        for ((name, result) in sortedFiles) {
            val shortName = name.substringAfterLast('.')
            val pct = String.format("%.1f", result.coveragePercent)
            if (result.uncoveredRanges.isEmpty()) {
                sb.appendLine("  $shortName — ${pct}%")
            } else {
                val ranges = result.uncoveredRanges.joinToString(", ") { range ->
                    if (range.first == range.last) "line ${range.first}" else "lines ${range.first}-${range.last}"
                }
                sb.appendLine("  $shortName — ${pct}% ($ranges uncovered)")
            }
        }

        val totalCovered = snapshot.files.values.sumOf { it.coveredLines }
        val totalLines = snapshot.files.values.sumOf { it.totalLines }
        val overallPct = if (totalLines == 0) 0.0 else totalCovered.toDouble() / totalLines * 100
        sb.appendLine()
        sb.appendLine("Overall: ${String.format("%.1f", overallPct)}% line coverage")

        return sb.toString().trimEnd()
    }

    private fun formatFileCoverage(fileName: String, result: FileCoverageResult): String {
        val sb = StringBuilder()
        val pct = String.format("%.1f", result.coveragePercent)
        sb.appendLine("$fileName — ${pct}% line coverage (${result.coveredLines}/${result.totalLines} lines)")

        if (result.uncoveredRanges.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Uncovered lines:")
            for (range in result.uncoveredRanges) {
                if (range.first == range.last) {
                    sb.appendLine("  ${range.first}: 0 hits")
                } else {
                    sb.appendLine("  ${range.first}-${range.last}: 0 hits")
                }
            }
        }

        return sb.toString().trimEnd()
    }

    private fun formatTestResults(root: SMTestProxy.SMRootTestProxy, testTarget: String): CoverageRunResult {
        val allTests = collectAllTests(root)
        val passed = allTests.count { it.isPassed }
        val failed = allTests.count { it.isDefect && !it.isErrorProxy() }
        val errors = allTests.count { it.isErrorProxy() }
        val skipped = allTests.count { it.isIgnored }
        val duration = root.duration?.let { it / 1000.0 } ?: 0.0

        val sb = StringBuilder()
        sb.appendLine("Tests: $passed passed, $failed failed, $errors error, $skipped skipped (${String.format("%.1f", duration)}s)")

        val failedTests = allTests.filter { it.isDefect }
        if (failedTests.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("FAILED:")
            for (test in failedTests) {
                val errorMsg = test.errorMessage ?: "unknown error"
                sb.appendLine("  ${test.name} — $errorMsg")
                val stackTrace = test.stacktrace
                if (!stackTrace.isNullOrBlank()) {
                    val firstLine = stackTrace.lines().firstOrNull { it.contains("at ") }
                    if (firstLine != null) {
                        sb.appendLine("    ${firstLine.trim()}")
                    }
                }
            }
        }

        val summary = if (failed > 0 || errors > 0) "FAILED" else "PASSED"
        return CoverageRunResult(sb.toString().trimEnd(), "$summary: $passed passed, $failed failed")
    }

    private fun SMTestProxy.isErrorProxy(): Boolean {
        return magnitudeInfo?.title?.contains("error", ignoreCase = true) == true ||
            errorMessage?.startsWith("java.lang.") == true
    }

    private fun collectAllTests(proxy: SMTestProxy): List<SMTestProxy> {
        if (proxy.children.isEmpty() && proxy !is SMTestProxy.SMRootTestProxy) {
            return listOf(proxy)
        }
        return proxy.children.flatMap { collectAllTests(it) }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Run configuration creation (follows RuntimeExecTool pattern)
    // ══════════════════════════════════════════════════════════════════════

    private fun createJUnitRunSettings(
        project: Project, className: String, method: String?
    ): com.intellij.execution.RunnerAndConfigurationSettings? {
        return try {
            val runManager = RunManager.getInstance(project)

            val testFramework = detectTestFramework(project, className)
            val configTypeId = when (testFramework) {
                "TestNG" -> "TestNG"
                else -> "JUnit"
            }
            val testConfigType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.find { type ->
                type.id == configTypeId || type.displayName == configTypeId
            } ?: return null

            val factory = testConfigType.configurationFactories.firstOrNull() ?: return null
            val configName = "[Coverage] ${className.substringAfterLast('.')}${if (method != null) ".$method" else ""}"
            val settings = runManager.createConfiguration(configName, factory)

            val config = settings.configuration
            val isTestNG = testFramework == "TestNG"

            try {
                val dataMethodName = if (isTestNG) "getPersistantData" else "getPersistentData"
                val getDataMethod = config.javaClass.methods.find { it.name == dataMethodName }
                val data = getDataMethod?.invoke(config)
                if (data != null) {
                    val testObjectField = data.javaClass.getField("TEST_OBJECT")
                    val mainClassField = data.javaClass.getField("MAIN_CLASS_NAME")

                    val testType = if (method != null) {
                        if (isTestNG) "METHOD" else "method"
                    } else {
                        if (isTestNG) "CLASS" else "class"
                    }
                    testObjectField.set(data, testType)
                    mainClassField.set(data, className)

                    try {
                        val packageField = data.javaClass.getField("PACKAGE_NAME")
                        packageField.set(data, className.substringBeforeLast('.', ""))
                    } catch (_: Exception) {}

                    if (method != null) {
                        val methodField = data.javaClass.getField("METHOD_NAME")
                        methodField.set(data, method)
                    }
                } else {
                    return null
                }
            } catch (_: Exception) {
                return null
            }

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

    private fun detectTestFramework(project: Project, className: String): String {
        return try {
            ReadAction.compute<String, Exception> {
                val psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(className, GlobalSearchScope.projectScope(project))
                    ?: return@compute "Unknown"

                val annotations = psiClass.annotations.map { it.qualifiedName.orEmpty() } +
                    psiClass.methods.flatMap { m -> m.annotations.map { it.qualifiedName.orEmpty() } }

                when {
                    annotations.any { it.startsWith("org.testng.") } -> "TestNG"
                    annotations.any { it.startsWith("org.junit.") } -> "JUnit"
                    else -> "Unknown"
                }
            }
        } catch (_: Exception) { "Unknown" }
    }

    private fun findModuleForClass(project: Project, className: String): com.intellij.openapi.module.Module? {
        return try {
            ReadAction.compute<com.intellij.openapi.module.Module?, Exception> {
                val psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(className, GlobalSearchScope.projectScope(project))
                    ?: return@compute null
                ModuleUtilCore.findModuleForPsiElement(psiClass)
            }
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════════════════

    companion object {
        private const val DEFAULT_TIMEOUT = 300L
        private const val MAX_TIMEOUT = 900L

        /**
         * Collapse a sorted list of line numbers into contiguous ranges.
         * E.g., [3, 4, 5, 10, 12, 13] -> [3..5, 10..10, 12..13]
         */
        internal fun collapseToRanges(lines: List<Int>): List<IntRange> {
            if (lines.isEmpty()) return emptyList()
            val sorted = lines.sorted()
            val ranges = mutableListOf<IntRange>()
            var start = sorted[0]
            var end = sorted[0]
            for (i in 1 until sorted.size) {
                if (sorted[i] == end + 1) {
                    end = sorted[i]
                } else {
                    ranges.add(start..end)
                    start = sorted[i]
                    end = sorted[i]
                }
            }
            ranges.add(start..end)
            return ranges
        }

        /**
         * Test helper — exposes formatCoverageSummary for unit tests.
         */
        fun formatCoverageSnapshotPublic(snapshot: CoverageSnapshot): String =
            CoverageTool().formatCoverageSummary(snapshot)
    }
}

// ══════════════════════════════════════════════════════════════════════════
// Data classes
// ══════════════════════════════════════════════════════════════════════════

/** Internal result holder for test output + summary from a coverage run. */
internal data class CoverageRunResult(
    val testResult: String,
    val testSummary: String
)

data class CoverageSnapshot(val files: Map<String, FileCoverageResult>)

data class FileCoverageResult(
    val coveredLines: Int,
    val totalLines: Int,
    val uncoveredRanges: List<IntRange>
) {
    val coveragePercent: Double
        get() = if (totalLines == 0) 0.0 else coveredLines.toDouble() / totalLines * 100
}
