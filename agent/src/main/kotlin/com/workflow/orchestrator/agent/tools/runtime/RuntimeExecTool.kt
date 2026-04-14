package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.agent.util.ReflectionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Runtime observation — universal process and test-result observation for any JetBrains IDE.
 *
 * Split from the original RuntimeExecTool: the Java-specific `run_tests` / `compile_module`
 * actions moved to [JavaRuntimeExecTool], and pytest-specific equivalents live in
 * [PythonRuntimeExecTool]. This tool keeps only the three universal actions that work
 * against the already-running IntelliJ execution / test framework (any RunProfile, any
 * language).
 */
class RuntimeExecTool : AgentTool {

    override val name = "runtime_exec"

    override val description = """
Runtime observation — read console output and structured test results from running or recently finished run configurations.

Actions and their parameters:
- get_running_processes() → List active run/debug sessions with status and PID
- get_run_output(config_name, last_n_lines?, filter?) → Read process console output (last_n_lines default 200, max 1000; filter: regex pattern)
- get_test_results(config_name, status_filter?) → Get structured test results (status_filter: FAILED|ERROR|PASSED|SKIPPED)

To run tests or compile: use java_runtime_exec (on IntelliJ with Java plugin) or python_runtime_exec (on PyCharm). This tool only observes — it does not start new runs.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_running_processes", "get_run_output", "get_test_results"
                )
            ),
            "config_name" to ParameterProperty(
                type = "string",
                description = "Name of the run configuration — for get_run_output, get_test_results"
            ),
            "last_n_lines" to ParameterProperty(
                type = "integer",
                description = "Number of lines to return from the end (default: 200, max: 1000) — for get_run_output"
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Regex pattern to filter output lines — for get_run_output"
            ),
            "status_filter" to ParameterProperty(
                type = "string",
                description = "Filter by test status — for get_test_results",
                enumValues = listOf("FAILED", "ERROR", "PASSED", "SKIPPED")
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER
    )

    /** Resolve stream callback for live output. */
    private fun resolveStreamCallback(@Suppress("UNUSED_PARAMETER") project: Project): ((String, String) -> Unit)? {
        return RunCommandTool.streamCallback
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "get_running_processes" -> executeGetRunningProcesses(params, project)
            "get_run_output" -> executeGetRunOutput(params, project)
            "get_test_results" -> executeGetTestResults(params, project)
            "run_tests", "compile_module" -> ToolResult(
                content = "Action '$action' is handled by java_runtime_exec (on IntelliJ with the Java plugin) or python_runtime_exec (on PyCharm). This tool only provides process observation — use tool_search to load the IDE-specific variant.",
                summary = "Action '$action' moved to java_runtime_exec / python_runtime_exec",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: get_running_processes, get_run_output, get_test_results",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_running_processes
    // ══════════════════════════════════════════════════════════════════════

    private fun executeGetRunningProcesses(params: JsonObject, project: Project): ToolResult {
        return try {
            val entries = mutableListOf<ProcessEntry>()

            val runningProcesses = ExecutionManager.getInstance(project).getRunningProcesses()
            for (handler in runningProcesses) {
                val processName = extractProcessName(handler)
                val isDestroyed = handler.isProcessTerminated || handler.isProcessTerminating
                if (!isDestroyed) {
                    entries.add(ProcessEntry(
                        name = processName, type = "Running", status = "Active", pid = extractPid(handler)
                    ))
                }
            }

            val debugSessions = XDebuggerManager.getInstance(project).debugSessions
            for (session in debugSessions) {
                val sessionName = session.sessionName
                val isStopped = session.isStopped
                if (!isStopped && entries.none { it.name == sessionName && it.type == "Debug" }) {
                    val isPaused = session.isPaused
                    val status = when {
                        isPaused -> "Paused (at breakpoint)"
                        else -> "Active"
                    }
                    entries.add(ProcessEntry(name = sessionName, type = "Debug", status = status, pid = null))
                }
            }

            if (entries.isEmpty()) {
                return ToolResult("No active run/debug sessions.", "No processes", 10)
            }

            val sb = StringBuilder()
            sb.appendLine("Active Sessions (${entries.size}):")
            sb.appendLine()

            for (entry in entries) {
                sb.appendLine(entry.name)
                sb.appendLine("  Type: ${entry.type}")
                sb.appendLine("  Status: ${entry.status}")
                entry.pid?.let { sb.appendLine("  PID: $it") }
                sb.appendLine()
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "${entries.size} active sessions", content.length / 4)
        } catch (e: Exception) {
            ToolResult("Error listing processes: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun extractProcessName(handler: ProcessHandler): String {
        return try { handler.toString() } catch (_: Exception) { "Unknown process" }
    }

    private fun extractPid(handler: ProcessHandler): Long? {
        return try {
            val method = handler.javaClass.methods.find { it.name == "getProcess" }
            val process = method?.invoke(handler) as? Process
            process?.pid()
        } catch (_: Exception) { null }
    }

    private data class ProcessEntry(val name: String, val type: String, val status: String, val pid: Long?)

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_run_output
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeGetRunOutput(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'config_name' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val lastNLines = (params["last_n_lines"]?.jsonPrimitive?.intOrNull ?: RUN_OUTPUT_DEFAULT_LINES)
            .coerceIn(1, RUN_OUTPUT_MAX_LINES)

        val filterPattern = params["filter"]?.jsonPrimitive?.content?.let {
            try { Regex(it) }
            catch (e: Exception) {
                return ToolResult("Error: invalid regex pattern '${it}': ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }
        }

        return try {
            val contentManager = RunContentManager.getInstance(project)
            val allDescriptors = contentManager.allDescriptors

            val descriptor = allDescriptors.find { desc ->
                desc.displayName?.contains(configName, ignoreCase = true) == true
            }

            if (descriptor == null) {
                val available = allDescriptors.mapNotNull { it.displayName }
                val availableMsg = if (available.isNotEmpty()) {
                    "\nAvailable sessions: ${available.joinToString(", ")}"
                } else {
                    "\nNo run sessions available."
                }
                return ToolResult("No run session found matching '$configName'.$availableMsg", "Not found", 30, isError = true)
            }

            val consoleText = extractConsoleText(descriptor)

            if (consoleText == null || consoleText.isBlank()) {
                return ToolResult("Run session '${descriptor.displayName}' found but console output is empty.", "Empty output", 10)
            }

            var lines = consoleText.lines()
            if (filterPattern != null) {
                lines = lines.filter { filterPattern.containsMatchIn(it) }
            }

            val totalLines = lines.size
            lines = lines.takeLast(lastNLines)

            val sb = StringBuilder()
            sb.appendLine("Console Output: ${descriptor.displayName}")
            val processStatus = when {
                descriptor.processHandler?.isProcessTerminated == true -> "Terminated"
                descriptor.processHandler?.isProcessTerminating == true -> "Terminating"
                else -> "Running"
            }
            sb.appendLine("Status: $processStatus")
            if (filterPattern != null) {
                sb.appendLine("Filter: ${filterPattern.pattern}")
            }
            if (totalLines > lastNLines) {
                sb.appendLine("Showing last $lastNLines of $totalLines lines")
            }
            sb.appendLine("---")

            val startLineNum = (totalLines - lines.size) + 1
            for ((index, line) in lines.withIndex()) {
                sb.appendLine("${startLineNum + index}: $line")
            }

            val content = sb.toString().trimEnd()
            val capped = truncateOutput(content, RUN_OUTPUT_TOKEN_CAP_CHARS)

            ToolResult(capped, "${lines.size} lines from ${descriptor.displayName}", capped.length / 4)
        } catch (e: Exception) {
            ToolResult("Error getting run output: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private suspend fun extractConsoleText(descriptor: RunContentDescriptor): String? {
        val console = descriptor.executionConsole ?: return null

        val unwrapped = unwrapToConsoleView(console)
        if (unwrapped != null) {
            val text = readConsoleViewText(unwrapped)
            if (!text.isNullOrBlank()) return text
        }

        if (console is com.intellij.execution.impl.ConsoleViewImpl) {
            val text = readConsoleViewText(console)
            if (!text.isNullOrBlank()) return text
        }

        if (console is com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView) {
            val innerConsole = console.console
            if (innerConsole is com.intellij.execution.impl.ConsoleViewImpl) {
                val text = readConsoleViewText(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
            val text = readViaEditor(innerConsole)
            if (!text.isNullOrBlank()) return text
        }

        try {
            val getConsole = console.javaClass.getMethod("getConsole")
            val innerConsole = getConsole.invoke(console)
            if (innerConsole is com.intellij.execution.impl.ConsoleViewImpl) {
                val text = readConsoleViewText(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
            if (innerConsole != null) {
                val text = readViaEditor(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
        } catch (_: Exception) {}

        return readViaEditor(console)
    }

    private fun unwrapToConsoleView(console: Any): com.intellij.execution.impl.ConsoleViewImpl? {
        var current: Any? = console
        repeat(MAX_UNWRAP_DEPTH) {
            if (current is com.intellij.execution.impl.ConsoleViewImpl) return current

            val delegate = ReflectionUtils.tryInvoke(current, "getDelegate")
            if (delegate is com.intellij.execution.impl.ConsoleViewImpl) return delegate
            if (delegate != null && delegate !== current) {
                current = delegate
                return@repeat
            }

            val inner = ReflectionUtils.tryInvoke(current, "getConsole")
            if (inner is com.intellij.execution.impl.ConsoleViewImpl) return inner
            if (inner != null && inner !== current) {
                current = inner
                return@repeat
            }

            return null
        }
        return current as? com.intellij.execution.impl.ConsoleViewImpl
    }

    private suspend fun readConsoleViewText(console: com.intellij.execution.impl.ConsoleViewImpl): String? {
        return try {
            withContext(Dispatchers.EDT) {
                console.component
                console.flushDeferredText()
                console.editor?.document?.text
            }
        } catch (_: Exception) { null }
    }

    private suspend fun readViaEditor(console: Any): String? {
        return try {
            withContext(Dispatchers.EDT) {
                try { console.javaClass.getMethod("getComponent").invoke(console) } catch (_: Exception) {}
                val editorMethod = console.javaClass.getMethod("getEditor")
                val editor = editorMethod.invoke(console) as? com.intellij.openapi.editor.Editor
                editor?.document?.text
            }
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_test_results
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeGetTestResults(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
        val statusFilter = params["status_filter"]?.jsonPrimitive?.content?.uppercase()

        return try {
            val contentManager = RunContentManager.getInstance(project)
            val allDescriptors = contentManager.allDescriptors

            val descriptor = if (configName != null) {
                allDescriptors.find { desc ->
                    desc.displayName?.contains(configName, ignoreCase = true) == true
                }
            } else {
                allDescriptors.firstOrNull { desc -> hasTestResults(desc) }
                    ?: allDescriptors.firstOrNull { desc ->
                        desc.processHandler?.let { !it.isProcessTerminated } == true
                    }
            }

            if (descriptor == null) {
                val msg = if (configName != null) "No test run found matching '$configName'." else "No test run results available."
                return ToolResult(msg, "No test results", 10, isError = true)
            }

            val handler = descriptor.processHandler
            if (handler != null && !handler.isProcessTerminated) {
                val processTerminated = awaitProcessTermination(handler, MAX_PROCESS_WAIT_SECONDS * 1000L, project)
                if (!processTerminated) {
                    return ToolResult(
                        "Process for '${descriptor.displayName}' is still running after ${MAX_PROCESS_WAIT_SECONDS}s " +
                            "(may still be building/compiling). Try again later.",
                        "Process still running", 20, isError = true
                    )
                }
            }

            val testConsole = TestConsoleUtils.unwrapToTestConsole(descriptor.executionConsole)
            if (testConsole != null) {
                awaitTestingFinished(testConsole.resultsViewer, TEST_TREE_FINALIZE_TIMEOUT_MS)
            } else {
                // No test console — retry until the test tree is populated
                for (attempt in 1..TEST_TREE_RETRY_ATTEMPTS) {
                    if (TestConsoleUtils.findTestRoot(descriptor)?.children?.isNotEmpty() == true) break
                    delay(TEST_TREE_RETRY_INTERVAL_MS)
                }
            }

            val testRoot = TestConsoleUtils.findTestRoot(descriptor)
            if (testRoot == null) {
                return ToolResult(
                    "Run session '${descriptor.displayName}' found but no test results available. It may not be a test run.",
                    "No test data", 15, isError = true
                )
            }

            val allTests = collectTestResults(testRoot)
            val filtered = if (statusFilter != null) {
                allTests.filter { it.status.name == statusFilter }
            } else {
                allTests
            }

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
                    val reason = test.errorMessage
                    if (reason != null) sb.appendLine("${test.name} — $reason") else sb.appendLine(test.name)
                }
                sb.appendLine()
            }

            if (statusFilter == "PASSED" && passedTests.isNotEmpty()) {
                sb.appendLine("--- PASSED ---")
                for (test in passedTests) {
                    sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
                }
            } else if (passedTests.isNotEmpty() && statusFilter == null) {
                sb.appendLine("--- PASSED ($passed tests) ---")
                val shown = passedTests.take(MAX_PASSED_SHOWN)
                for (test in shown) {
                    sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
                }
                if (passedTests.size > MAX_PASSED_SHOWN) {
                    sb.appendLine("... and ${passedTests.size - MAX_PASSED_SHOWN} more passed tests")
                }
            }

            val content = sb.toString().trimEnd()
            val capped = truncateOutput(content, TEST_RESULTS_TOKEN_CAP_CHARS)

            ToolResult(capped, "$overallStatus: $passed passed, $failed failed", capped.length / 4)
        } catch (e: Exception) {
            ToolResult("Error getting test results: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private suspend fun awaitProcessTermination(handler: ProcessHandler, timeoutMs: Long, project: Project? = null): Boolean {
        if (handler.isProcessTerminated) return true

        val toolCallId = RunCommandTool.currentToolCallId.get()
        val streamCallback = if (project != null) resolveStreamCallback(project) else RunCommandTool.streamCallback

        val terminated = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
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

    private suspend fun awaitTestingFinished(resultsViewer: TestResultsViewer, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : TestResultsViewer.EventsListener {
                    override fun onTestingFinished(sender: TestResultsViewer) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                resultsViewer.addEventsListener(listener)
            }
        }
    }

    private fun hasTestResults(descriptor: RunContentDescriptor): Boolean {
        val root = TestConsoleUtils.findTestRoot(descriptor) ?: return false
        return root.children.isNotEmpty()
    }

    companion object {
        // get_run_output constants
        private const val RUN_OUTPUT_DEFAULT_LINES = 200
        private const val RUN_OUTPUT_MAX_LINES = 1000
        private const val RUN_OUTPUT_TOKEN_CAP_CHARS = 12000

        // Console unwrap depth
        private const val MAX_UNWRAP_DEPTH = 5

        // get_test_results constants
        private const val MAX_PROCESS_WAIT_SECONDS = 600
        private const val TEST_TREE_FINALIZE_TIMEOUT_MS = 10_000L
        private const val PROGRESS_INTERVAL_MS = 10_000L
        private const val TEST_RESULTS_TOKEN_CAP_CHARS = 12000
    }
}
