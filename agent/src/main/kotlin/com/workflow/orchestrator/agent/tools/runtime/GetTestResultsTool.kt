package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

class GetTestResultsTool : AgentTool {
    override val name = "get_test_results"
    override val description = "Get structured test results from the IDE's most recent test run, returning pass/fail status, assertion messages, and stack traces for each individual test case. Use this after running tests with run_tests to check which tests passed or failed and to diagnose specific test failures from assertion details and stack traces. This only returns results from the most recent test run in the IDE — if you need to execute tests first, use run_tests. Do NOT use this for raw console output (use get_run_output instead). Results are ordered with failures first and output is capped at approximately 3000 tokens."
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
            var descriptor = if (configName != null) {
                allDescriptors.find { desc ->
                    desc.displayName?.contains(configName, ignoreCase = true) == true
                }
            } else {
                // Find the most recent test run descriptor (prefer one with results, fall back to running)
                allDescriptors.firstOrNull { desc -> hasTestResults(desc) }
                    ?: allDescriptors.firstOrNull { desc ->
                        desc.processHandler?.let { !it.isProcessTerminated } == true
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

            // Wait for process to terminate using callback (covers build + compilation + test execution).
            // Build/compilation can take minutes for large projects, so we use a generous limit.
            val handler = descriptor.processHandler
            if (handler != null && !handler.isProcessTerminated) {
                val processTerminated = awaitProcessTermination(handler, MAX_PROCESS_WAIT_SECONDS * 1000L)
                if (!processTerminated) {
                    return ToolResult(
                        "Process for '${descriptor.displayName}' is still running after ${MAX_PROCESS_WAIT_SECONDS}s " +
                            "(may still be building/compiling). Try again later.",
                        "Process still running",
                        20,
                        isError = true
                    )
                }
            }

            // Process terminated — wait for the test framework to finalize the SMTestProxy tree.
            // Uses TestResultsViewer.EventsListener.onTestingFinished() callback (same as RunTestsTool).
            val console = descriptor.executionConsole
            if (console is SMTRunnerConsoleView) {
                awaitTestingFinished(console.resultsViewer, TEST_TREE_FINALIZE_TIMEOUT_MS)
            } else if (console !is SMTRunnerConsoleView) {
                // Non-SMTRunner console — brief delay for any async result population
                delay(1000)
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

    /**
     * Await process termination using ProcessAdapter callback instead of polling.
     * Streams progress updates to the chat UI every 10 seconds.
     * Returns true if process terminated, false if timeout expired.
     */
    private suspend fun awaitProcessTermination(handler: ProcessHandler, timeoutMs: Long): Boolean {
        if (handler.isProcessTerminated) return true

        val toolCallId = com.workflow.orchestrator.agent.tools.builtin.RunCommandTool.currentToolCallId.get()
        val streamCallback = com.workflow.orchestrator.agent.tools.builtin.RunCommandTool.streamCallback

        val terminated = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                // Stream periodic progress updates so the agent isn't blind during long builds
                val progressJob = if (toolCallId != null && streamCallback != null) {
                    launch {
                        var elapsed = 0L
                        while (true) {
                            delay(PROGRESS_INTERVAL_MS)
                            elapsed += PROGRESS_INTERVAL_MS
                            streamCallback.invoke(toolCallId, "[waiting for process... ${elapsed / 1000}s elapsed]\n")
                        }
                    }
                } else null

                try {
                    suspendCancellableCoroutine { continuation ->
                        val listener = object : ProcessAdapter() {
                            override fun processTerminated(event: ProcessEvent) {
                                if (continuation.isActive) continuation.resume(true)
                            }
                        }
                        handler.addProcessListener(listener)
                        continuation.invokeOnCancellation {
                            handler.removeProcessListener(listener)
                        }
                        // Check again after registering — process may have terminated between our check and addProcessListener
                        if (handler.isProcessTerminated && continuation.isActive) {
                            continuation.resume(true)
                        }
                    }
                } finally {
                    progressJob?.cancel()
                }
            }
        }

        if (terminated == null && toolCallId != null) {
            streamCallback?.invoke(toolCallId, "[process still running after ${timeoutMs / 1000}s]\n")
        }

        return terminated ?: false
    }

    /**
     * Await test tree finalization using TestResultsViewer.EventsListener callback.
     * This is the official API — fires when the SMTestProxy tree is fully populated.
     */
    private suspend fun awaitTestingFinished(resultsViewer: TestResultsViewer, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : TestResultsViewer.EventsListener {
                    override fun onTestingFinished(sender: TestResultsViewer) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                resultsViewer.addEventsListener(listener)
                // Check again after registering — may have finished between our check and addEventsListener
                // Re-check after registering — may have finished between our check and addEventsListener
                if (continuation.isActive) {
                    // The listener will fire onTestingFinished when done; timeout handles the fallback
                }
            }
        }
    }

    private fun hasTestResults(descriptor: RunContentDescriptor): Boolean {
        val root = findTestRoot(descriptor) ?: return false
        return root.children.isNotEmpty()
    }

    /**
     * Find the SMTestProxy root from a descriptor's execution console.
     * Uses public API: SMTRunnerConsoleView.getResultsViewer().getTestsRootNode()
     */
    private fun findTestRoot(descriptor: RunContentDescriptor): SMTestProxy.SMRootTestProxy? {
        val console = descriptor.executionConsole ?: return null

        // Direct: console IS an SMTRunnerConsoleView
        if (console is SMTRunnerConsoleView) {
            return console.resultsViewer.testsRootNode as? SMTestProxy.SMRootTestProxy
        }

        // Wrapper: try getConsole() for inner view
        try {
            val getConsole = console.javaClass.getMethod("getConsole")
            val innerConsole = getConsole.invoke(console)
            if (innerConsole is SMTRunnerConsoleView) {
                return innerConsole.resultsViewer.testsRootNode as? SMTestProxy.SMRootTestProxy
            }
        } catch (_: Exception) {}

        return null
    }

    private fun collectTestResults(root: SMTestProxy): List<TestResultEntry> {
        return root.allTests
            .filterIsInstance<SMTestProxy>()
            .filter { it.isLeaf }
            .map { mapToTestResultEntry(it) }
    }

    private fun mapToTestResultEntry(proxy: SMTestProxy): TestResultEntry {
        val status = when {
            proxy.isDefect -> {
                if (proxy.stacktrace?.contains("AssertionError") == true ||
                    proxy.stacktrace?.contains("AssertionFailedError") == true
                ) TestStatus.FAILED else TestStatus.ERROR
            }
            proxy.isIgnored -> TestStatus.SKIPPED
            else -> TestStatus.PASSED
        }
        val stackTrace = proxy.stacktrace
            ?.lines()
            ?.filter { it.trimStart().startsWith("at ") || it.contains("Exception") || it.contains("Error") }
            ?.take(MAX_STACK_FRAMES)
            ?: emptyList()

        return TestResultEntry(
            name = proxy.name,
            status = status,
            durationMs = proxy.duration?.toLong() ?: 0L,
            errorMessage = proxy.errorMessage,
            stackTrace = stackTrace
        )
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
        private const val MAX_PROCESS_WAIT_SECONDS = 600 // 10 min — builds can be slow
        private const val TEST_TREE_FINALIZE_TIMEOUT_MS = 10_000L // 10s for test tree finalization after process ends
        private const val PROGRESS_INTERVAL_MS = 10_000L // Stream progress update every 10s
    }
}
