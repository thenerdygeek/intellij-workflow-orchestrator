package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class GetTestResultsTool : AgentTool {
    override val name = "get_test_results"
    override val description = "Get structured test results from the most recent test run with pass/fail status, assertions, and stack traces per test"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "config_name" to ParameterProperty(
                type = "string",
                description = "Name of the test run configuration (optional — uses most recent test run if omitted)"
            ),
            "status_filter" to ParameterProperty(
                type = "string",
                description = "Filter by test status",
                enumValues = listOf("FAILED", "ERROR", "PASSED", "SKIPPED")
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
        val statusFilter = params["status_filter"]?.jsonPrimitive?.content?.uppercase()

        return try {
            val contentManager = RunContentManager.getInstance(project)
            val allDescriptors = contentManager.allDescriptors

            // Find matching descriptor
            val descriptor = if (configName != null) {
                allDescriptors.find { desc ->
                    desc.displayName?.contains(configName, ignoreCase = true) == true
                }
            } else {
                // Find the most recent test run descriptor
                allDescriptors.firstOrNull { desc ->
                    hasTestResults(desc)
                }
            }

            if (descriptor == null) {
                val msg = if (configName != null) {
                    "No test run found matching '$configName'."
                } else {
                    "No test run results available."
                }
                return ToolResult(msg, "No test results", 10, isError = true)
            }

            // Extract test proxy tree from the descriptor
            val testRoot = findTestRoot(descriptor)
            if (testRoot == null) {
                return ToolResult(
                    "Run session '${descriptor.displayName}' found but no test results available. It may not be a test run.",
                    "No test data",
                    15,
                    isError = true
                )
            }

            // Collect all leaf test results
            val allTests = collectTestResults(testRoot)

            // Apply status filter
            val filtered = if (statusFilter != null) {
                allTests.filter { it.status.name == statusFilter }
            } else {
                allTests
            }

            // Compute summary counts
            val passed = allTests.count { it.status == TestStatus.PASSED }
            val failed = allTests.count { it.status == TestStatus.FAILED }
            val errors = allTests.count { it.status == TestStatus.ERROR }
            val skipped = allTests.count { it.status == TestStatus.SKIPPED }
            val totalDuration = allTests.sumOf { it.durationMs }

            val sb = StringBuilder()
            sb.appendLine("Test Run: ${descriptor.displayName ?: "Unknown"}")

            val overallStatus = when {
                errors > 0 || failed > 0 -> "FAILED"
                else -> "PASSED"
            }
            sb.appendLine("Status: $overallStatus ($passed passed, $failed failed, $errors error, $skipped skipped)")
            sb.appendLine("Duration: ${formatDuration(totalDuration)}")
            sb.appendLine()

            // Order: failed/error first, then skipped, then passed (as summary)
            val failedTests = filtered.filter { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
            val skippedTests = filtered.filter { it.status == TestStatus.SKIPPED }
            val passedTests = filtered.filter { it.status == TestStatus.PASSED }

            if (failedTests.isNotEmpty()) {
                sb.appendLine("--- FAILED ---")
                for (test in failedTests) {
                    sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
                    test.errorMessage?.let { sb.appendLine("  Assertion: $it") }
                    if (test.stackTrace.isNotEmpty()) {
                        sb.appendLine("  Stack:")
                        for (frame in test.stackTrace.take(MAX_STACK_FRAMES)) {
                            sb.appendLine("    $frame")
                        }
                    }
                    sb.appendLine()
                }
            }

            if (skippedTests.isNotEmpty()) {
                sb.appendLine("--- SKIPPED ---")
                for (test in skippedTests) {
                    sb.appendLine("${test.name}")
                }
                sb.appendLine()
            }

            // For passed tests, show summary if not filtered to PASSED
            if (statusFilter == "PASSED" && passedTests.isNotEmpty()) {
                sb.appendLine("--- PASSED ---")
                for (test in passedTests) {
                    sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
                }
            } else if (passedTests.isNotEmpty() && statusFilter == null) {
                sb.appendLine("--- PASSED ($passed tests) ---")
                // Only show names, no details, to save tokens
                val shown = passedTests.take(MAX_PASSED_SHOWN)
                for (test in shown) {
                    sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
                }
                if (passedTests.size > MAX_PASSED_SHOWN) {
                    sb.appendLine("... and ${passedTests.size - MAX_PASSED_SHOWN} more passed tests")
                }
            }

            val content = sb.toString().trimEnd()
            // Cap at ~3000 tokens
            val capped = if (content.length > TOKEN_CAP_CHARS) {
                content.take(TOKEN_CAP_CHARS) + "\n... (results truncated)"
            } else {
                content
            }

            ToolResult(capped, "$overallStatus: $passed passed, $failed failed", capped.length / 4)
        } catch (e: Exception) {
            ToolResult("Error getting test results: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun hasTestResults(descriptor: RunContentDescriptor): Boolean {
        return findTestRoot(descriptor) != null
    }

    private fun findTestRoot(descriptor: RunContentDescriptor): SMTestProxy.SMRootTestProxy? {
        return try {
            // The test framework stores the root proxy on the execution console
            val console = descriptor.executionConsole ?: return null
            // Try to access the test results via the console's properties
            val method = console.javaClass.methods.find { it.name == "getResultsViewer" }
            val viewer = method?.invoke(console) ?: return null
            val rootMethod = viewer.javaClass.methods.find { it.name == "getTestsRootNode" }
            rootMethod?.invoke(viewer) as? SMTestProxy.SMRootTestProxy
        } catch (_: Exception) {
            null
        }
    }

    private fun collectTestResults(root: SMTestProxy): List<TestResultEntry> {
        val results = mutableListOf<TestResultEntry>()
        collectLeafTests(root, results)
        return results
    }

    private fun collectLeafTests(proxy: AbstractTestProxy, results: MutableList<TestResultEntry>) {
        if (proxy.isLeaf) {
            val smProxy = proxy as? SMTestProxy
            val status = when {
                smProxy?.isDefect == true -> {
                    if (smProxy.stacktrace?.contains("AssertionError") == true ||
                        smProxy.stacktrace?.contains("AssertionFailedError") == true
                    ) {
                        TestStatus.FAILED
                    } else {
                        TestStatus.ERROR
                    }
                }
                smProxy?.isIgnored == true -> TestStatus.SKIPPED
                else -> TestStatus.PASSED
            }

            val errorMessage = smProxy?.errorMessage
            val stackTrace = smProxy?.stacktrace
                ?.lines()
                ?.filter { it.trimStart().startsWith("at ") || it.contains("Exception") || it.contains("Error") }
                ?.take(MAX_STACK_FRAMES)
                ?: emptyList()

            results.add(TestResultEntry(
                name = proxy.name,
                status = status,
                durationMs = smProxy?.duration?.toLong() ?: 0L,
                errorMessage = errorMessage,
                stackTrace = stackTrace
            ))
        } else {
            for (child in proxy.children) {
                collectLeafTests(child, results)
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            else -> "${"%.1f".format(ms / 1000.0)}s"
        }
    }

    private enum class TestStatus { PASSED, FAILED, ERROR, SKIPPED }

    private data class TestResultEntry(
        val name: String,
        val status: TestStatus,
        val durationMs: Long,
        val errorMessage: String?,
        val stackTrace: List<String>
    )

    companion object {
        private const val MAX_STACK_FRAMES = 5
        private const val MAX_PASSED_SHOWN = 20
        private const val TOKEN_CAP_CHARS = 12000 // ~3000 tokens
    }
}
