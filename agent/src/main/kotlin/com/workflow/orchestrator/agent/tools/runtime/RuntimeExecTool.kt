package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.util.ReflectionUtils
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Runtime execution — process management, test running, compilation.
 *
 * Split from the monolithic RuntimeTool to isolate execution operations
 * (which have simple params) from the heavy config CRUD operations.
 */
class RuntimeExecTool : AgentTool {

    override val name = "runtime_exec"

    override val description = """
Runtime execution — process management, test running, and compilation.

Actions and their parameters:
- get_running_processes() → List active run/debug sessions with status and PID
- get_run_output(config_name, last_n_lines?, filter?) → Read process console output (last_n_lines default 200, max 1000; filter: regex pattern)
- get_test_results(config_name, status_filter?) → Get structured test results (status_filter: FAILED|ERROR|PASSED|SKIPPED)
- run_tests(class_name?, method?, timeout?, use_native_runner?) → Run tests via IntelliJ runner or shell fallback (timeout default 300s, max 900s)
- compile_module(module?) → Compile module or entire project if omitted

description optional: for approval dialog on run_tests, compile_module.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_running_processes", "get_run_output", "get_test_results", "run_tests", "compile_module"
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
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class name — for run_tests"
            ),
            "method" to ParameterProperty(
                type = "string",
                description = "Specific test method name — for run_tests"
            ),
            "timeout" to ParameterProperty(
                type = "integer",
                description = "Seconds before test process is killed (default: 300, max: 900) — for run_tests"
            ),
            "use_native_runner" to ParameterProperty(
                type = "boolean",
                description = "Use IntelliJ native test runner (true) or Maven/Gradle shell (false). Default: true — for run_tests"
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Module name — for compile_module (compiles entire project if omitted)"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog) — for run_tests, compile_module"
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
            "run_tests" -> executeRunTests(params, project)
            "compile_module" -> executeCompileModule(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: get_running_processes, get_run_output, get_test_results, run_tests, compile_module",
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
            val capped = if (content.length > RUN_OUTPUT_TOKEN_CAP_CHARS) {
                content.take(RUN_OUTPUT_TOKEN_CAP_CHARS) + "\n... (output truncated)"
            } else {
                content
            }

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
                    sb.appendLine(test.name)
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
            val capped = if (content.length > TEST_RESULTS_TOKEN_CAP_CHARS) {
                content.take(TEST_RESULTS_TOKEN_CAP_CHARS) + "\n... (results truncated)"
            } else {
                content
            }

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

    // ══════════════════════════════════════════════════════════════════════
    // Action: run_tests
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeRunTests(params: JsonObject, project: Project): ToolResult {
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'class_name' parameter is required", "Error: missing class_name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val method = params["method"]?.jsonPrimitive?.content
        val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: RUN_TESTS_DEFAULT_TIMEOUT)
            .coerceIn(1, RUN_TESTS_MAX_TIMEOUT)
        val useNativeRunner = params["use_native_runner"]?.jsonPrimitive?.booleanOrNull ?: true

        val testTarget = if (method != null) "$className#$method" else className

        if (useNativeRunner) {
            try {
                val result = executeWithNativeRunner(project, className, method, testTarget, timeoutSeconds)
                if (result != null) return result
            } catch (e: Exception) {
                val shellResult = executeWithShell(project, testTarget, timeoutSeconds)
                val warning = "[WARNING] Native test runner failed (${e.javaClass.simpleName}: ${e.message}), used shell fallback.\n\n"
                return shellResult.copy(content = warning + shellResult.content)
            }
        }

        return executeWithShell(project, testTarget, timeoutSeconds)
    }

    private suspend fun executeWithNativeRunner(
        project: Project, className: String, method: String?,
        testTarget: String, timeoutSeconds: Long
    ): ToolResult? {
        val settings = createJUnitRunSettings(project, className, method) ?: return null

        val processHandlerRef = AtomicReference<ProcessHandler?>(null)
        val descriptorRef = AtomicReference<RunContentDescriptor?>(null)

        val result = withTimeoutOrNull(timeoutSeconds * 1000) {
            suspendCancellableCoroutine { continuation ->
                com.intellij.openapi.application.invokeLater {
                    try {
                        val executor = DefaultRunExecutor.getRunExecutorInstance()
                        val env = ExecutionEnvironmentBuilder
                            .createOrNull(executor, settings)
                            ?.build()

                        if (env == null) {
                            if (continuation.isActive) continuation.resume(null)
                            return@invokeLater
                        }

                        val callback = object : ProgramRunner.Callback {
                            override fun processStarted(descriptor: RunContentDescriptor?) {
                                if (descriptor == null) {
                                    if (continuation.isActive) continuation.resume(null)
                                    return
                                }
                                handleDescriptorReady(descriptor, continuation, testTarget, descriptorRef, processHandlerRef, project)
                            }
                        }

                        try {
                            ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
                        } catch (_: NoSuchMethodError) {
                            env.callback = callback
                            ProgramRunnerUtil.executeConfiguration(env, false, true)
                        }

                        // Build watchdog: uses ExecutionListener.processNotStarted() to detect
                        // when before-run tasks (Make) fail. This is the official IntelliJ API —
                        // processNotStarted fires exactly when the execution framework aborts,
                        // with zero race condition (unlike CompilationStatusListener which fires
                        // BEFORE the process has a chance to start).
                        //
                        // Additionally subscribes to CompilationStatusListener to capture error
                        // counts for richer error messages.
                        val buildConnection = project.messageBus.connect()
                        val compilationErrors = java.util.concurrent.atomic.AtomicReference<String?>(null)

                        // Secondary: capture compilation error details
                        try {
                            val topicsClass = Class.forName("com.intellij.openapi.compiler.CompilerTopics")
                            val topicField = topicsClass.getField("COMPILATION_STATUS")
                            @Suppress("UNCHECKED_CAST")
                            val topic = topicField.get(null) as com.intellij.util.messages.Topic<Any>
                            val listenerClass = Class.forName("com.intellij.openapi.compiler.CompilationStatusListener")

                            val listener = java.lang.reflect.Proxy.newProxyInstance(
                                listenerClass.classLoader,
                                arrayOf(listenerClass)
                            ) { _, method, args ->
                                if (method.name == "compilationFinished") {
                                    val aborted = args?.getOrNull(0) as? Boolean ?: false
                                    val errors = args?.getOrNull(1) as? Int ?: 0
                                    val warnings = args?.getOrNull(2) as? Int ?: 0
                                    if (aborted || errors > 0) {
                                        compilationErrors.set("$errors errors, $warnings warnings, aborted=$aborted")
                                    }
                                }
                                null
                            }

                            @Suppress("UNCHECKED_CAST")
                            (topic as com.intellij.util.messages.Topic<Any>).let { t ->
                                buildConnection.subscribe(t, listener)
                            }
                        } catch (_: Exception) {
                            // Reflection failed — CompilerTopics not available, error details will be generic
                        }

                        // Primary: ExecutionListener detects run abort with no race condition
                        buildConnection.subscribe(com.intellij.execution.ExecutionManager.EXECUTION_TOPIC,
                            object : com.intellij.execution.ExecutionListener {
                                override fun processNotStarted(executorId: String, e: com.intellij.execution.runners.ExecutionEnvironment) {
                                    if (e == env) {
                                        buildConnection.disconnect()
                                        if (continuation.isActive) {
                                            val errorDetail = compilationErrors.get() ?: "before-run task failed"
                                            continuation.resume(ToolResult(
                                                content = "BUILD FAILED — test execution did not start.\n\n" +
                                                    "Compilation result: $errorDetail\n\n" +
                                                    "Fix the compilation errors and try again. " +
                                                    "Use diagnostics tool to check for errors in the test class.",
                                                summary = "Build failed before tests ($errorDetail)",
                                                tokenEstimate = 30,
                                                isError = true
                                            ))
                                        }
                                    }
                                }

                                override fun processStarted(executorId: String, e: com.intellij.execution.runners.ExecutionEnvironment, handler: com.intellij.execution.process.ProcessHandler) {
                                    if (e == env) buildConnection.disconnect()
                                }
                            }
                        )

                        // Safety: disconnect on timeout to prevent leaks
                        Thread {
                            try {
                                Thread.sleep(BUILD_WATCHDOG_MAX_MS)
                                buildConnection.disconnect()
                            } catch (_: InterruptedException) { /* normal */ }
                        }.apply { isDaemon = true; name = "build-watchdog-timeout"; start() }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }

        if (result == null && processHandlerRef.get() != null) {
            processHandlerRef.get()?.destroyProcess()
            val descriptor = descriptorRef.get()
            val partialResult = descriptor?.let { extractNativeResults(it, testTarget) }
            return if (partialResult != null) {
                partialResult.copy(
                    content = "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s. Partial results:\n\n${partialResult.content}",
                    isError = true
                )
            } else {
                ToolResult(
                    "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s for $testTarget. No results captured.",
                    "Test timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }
        }

        return result
    }

    private fun handleDescriptorReady(
        descriptor: RunContentDescriptor,
        continuation: CancellableContinuation<ToolResult?>,
        testTarget: String,
        descriptorRef: AtomicReference<RunContentDescriptor?>,
        processHandlerRef: AtomicReference<ProcessHandler?>,
        project: Project? = null
    ) {
        descriptorRef.set(descriptor)
        val handler = descriptor.processHandler
        processHandlerRef.set(handler)

        val toolCallId = RunCommandTool.currentToolCallId.get()
        val activeStreamCallback = if (project != null) resolveStreamCallback(project) else RunCommandTool.streamCallback

        if (handler != null && toolCallId != null) {
            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    val text = event.text ?: return
                    if (text.isNotBlank()) {
                        activeStreamCallback?.invoke(toolCallId, text)
                    }
                }
            })
        }

        val testConsole = TestConsoleUtils.unwrapToTestConsole(descriptor.executionConsole)
        if (testConsole != null) {
            val resultsViewer = testConsole.resultsViewer
            resultsViewer.addEventsListener(object : TestResultsViewer.EventsListener {
                override fun onTestingFinished(sender: TestResultsViewer) {
                    val root = sender.testsRootNode as? SMTestProxy.SMRootTestProxy
                    if (root != null && continuation.isActive) {
                        val allTests = collectTestResults(root)
                        val resultVal = if (allTests.isNotEmpty()) {
                            formatStructuredResults(allTests, descriptor.displayName ?: testTarget)
                        } else {
                            ToolResult("Test run completed for $testTarget but no test methods found in results.", "No tests found", 10)
                        }
                        continuation.resume(resultVal)
                    }
                }
            })
        } else {
            // Fallback: no test console available — wait for process exit, then retry
            // until the test tree is populated. No TestResultsViewer.EventsListener is
            // available here, so we poll with short intervals instead of a blind 2s Timer.
            if (handler != null) {
                val doExtract = {
                    Thread {
                        for (attempt in 1..TEST_TREE_RETRY_ATTEMPTS) {
                            if (!continuation.isActive) return@Thread
                            if (TestConsoleUtils.findTestRoot(descriptor)?.children?.isNotEmpty() == true) break
                            Thread.sleep(TEST_TREE_RETRY_INTERVAL_MS)
                        }
                        if (continuation.isActive) {
                            continuation.resume(extractNativeResults(descriptor, testTarget))
                        }
                    }.apply { isDaemon = true; name = "test-tree-finalize"; start() }
                }

                if (handler.isProcessTerminated) {
                    doExtract()
                } else {
                    handler.addProcessListener(object : ProcessAdapter() {
                        override fun processTerminated(event: ProcessEvent) {
                            doExtract()
                        }
                    })
                }
            } else {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun createJUnitRunSettings(
        project: Project, className: String, method: String?
    ): RunnerAndConfigurationSettings? {
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
            val configName = "${className.substringAfterLast('.')}${if (method != null) ".$method" else ""}"
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
                        val packageName = className.substringBeforeLast('.', "")
                        packageField.set(data, packageName)
                    } catch (_: Exception) { }

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
            run {
                try {
                    val setModuleMethod = config.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                    setModuleMethod.invoke(config, testModule)
                } catch (_: Exception) {
                    try {
                        val getConfigModule = config.javaClass.getMethod("getConfigurationModule")
                        val configModule = getConfigModule.invoke(config)
                        val setModule = configModule.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                        setModule.invoke(configModule, testModule)
                    } catch (_: Exception) { }
                }
            }

            settings.isTemporary = true
            runManager.addConfiguration(settings)
            runManager.selectedConfiguration = settings

            settings
        } catch (_: Exception) { null }
    }

    private fun detectTestFramework(project: Project, className: String): String {
        return try {
            com.intellij.openapi.application.ReadAction.compute<String, Exception> {
                val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
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
            com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.module.Module?, Exception> {
                val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                    ?: return@compute null
                com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(psiClass)
            }
        } catch (_: Exception) { null }
    }

    private fun extractNativeResults(descriptor: RunContentDescriptor, testTarget: String): ToolResult? {
        val testRoot = TestConsoleUtils.findTestRoot(descriptor)
        if (testRoot == null) {
            return ToolResult(
                "Test run completed for $testTarget but no structured results available.\nRun session: ${descriptor.displayName}",
                "Tests completed, no structured data", 20
            )
        }

        val allTests = collectTestResults(testRoot)
        if (allTests.isEmpty()) {
            return ToolResult("Test run completed for $testTarget but no test methods found in results.", "No tests found", 10)
        }

        return formatStructuredResults(allTests, descriptor.displayName ?: testTarget)
    }

    private fun executeWithShell(project: Project, testTarget: String, timeoutSeconds: Long): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult("Error: no project base path available", "Error: no project", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val baseDir = File(basePath)
        val hasMaven = File(baseDir, "pom.xml").exists()
        val hasGradle = File(baseDir, "build.gradle").exists() || File(baseDir, "build.gradle.kts").exists()

        val command = when {
            hasMaven -> "mvn test -Dtest=$testTarget -Dsurefire.useFile=false -q"
            hasGradle -> {
                val gradleWrapper = if (File(baseDir, "gradlew").exists()) "./gradlew" else "gradle"
                val gradleTarget = testTarget.replace('#', '.')
                "$gradleWrapper test --tests '$gradleTarget' --no-daemon -q"
            }
            else -> return ToolResult(
                "No Maven (pom.xml) or Gradle (build.gradle) build file found in project root.",
                "No build tool found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val processBuilder = if (isWindows) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }

            processBuilder.directory(baseDir)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()
            val toolCallId = RunCommandTool.currentToolCallId.get()
            val activeStreamCallback = resolveStreamCallback(project)

            val outputBuilder = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            if (outputBuilder.length < RUN_TESTS_MAX_OUTPUT_CHARS) {
                                outputBuilder.appendLine(line)
                            }
                            if (toolCallId != null) {
                                activeStreamCallback?.invoke(toolCallId, line + "\n")
                            }
                            line = reader.readLine()
                        }
                    }
                } catch (_: Exception) { }
            }.apply {
                isDaemon = true
                name = "RunTests-Output-${toolCallId ?: "shell"}"
                start()
            }

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                readerThread.join(1000)
                val truncatedOutput = outputBuilder.toString()
                return ToolResult(
                    "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s for $testTarget.\nPartial output:\n$truncatedOutput",
                    "Test timeout", TokenEstimator.estimate(truncatedOutput), isError = true
                )
            }

            readerThread.join(2000)
            val truncatedOutput = outputBuilder.toString()

            val exitCode = process.exitValue()
            if (exitCode == 0) {
                ToolResult("Tests PASSED for $testTarget.\n\n$truncatedOutput", "Tests PASSED: $testTarget", TokenEstimator.estimate(truncatedOutput))
            } else {
                ToolResult(
                    "Tests FAILED for $testTarget (exit code $exitCode).\n\n$truncatedOutput",
                    "Tests FAILED: $testTarget", TokenEstimator.estimate(truncatedOutput), isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult("Error running tests: ${e.message}", "Test execution error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: compile_module
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeCompileModule(params: JsonObject, project: Project): ToolResult {
        val moduleName = params["module"]?.jsonPrimitive?.content

        return try {
            val result = withTimeoutOrNull(120_000L) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { }
                    ApplicationManager.getApplication().invokeLater {
                        val compiler = CompilerManager.getInstance(project)

                        val scope = if (moduleName != null) {
                            val module = ModuleManager.getInstance(project).modules.find { it.name == moduleName }
                            if (module != null) {
                                compiler.createModuleCompileScope(module, false)
                            } else {
                                val available = ModuleManager.getInstance(project).modules
                                    .map { it.name }
                                    .joinToString(", ")
                                if (!cont.isCompleted) {
                                    cont.resume(
                                        ToolResult(
                                            "Module '$moduleName' not found. Available modules: $available",
                                            "Module not found", TokenEstimator.estimate(available), isError = true
                                        )
                                    )
                                }
                                return@invokeLater
                            }
                        } else {
                            compiler.createProjectCompileScope(project)
                        }

                        val target = moduleName ?: "project"

                        compiler.make(scope) { aborted, errors, warnings, context ->
                            val compileResult = when {
                                aborted -> ToolResult(
                                    "Compilation of $target was aborted.",
                                    "Compilation aborted", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                                )
                                errors > 0 -> {
                                    val messages = context.getMessages(CompilerMessageCategory.ERROR)
                                        .take(COMPILE_MAX_ERROR_MESSAGES)
                                        .joinToString("\n") { msg ->
                                            val file = msg.virtualFile?.name ?: "<unknown>"
                                            val nav = msg.navigatable
                                            val location = if (nav is com.intellij.openapi.fileEditor.OpenFileDescriptor) {
                                                "$file:${nav.line + 1}:${nav.column + 1}"
                                            } else {
                                                file
                                            }
                                            "  $location: ${msg.message}"
                                        }
                                    val content = "Compilation of $target failed: $errors error(s), $warnings warning(s).\n\nErrors:\n$messages"
                                    ToolResult(content, "$errors errors, $warnings warnings", TokenEstimator.estimate(content), isError = true)
                                }
                                else -> {
                                    val warningNote = if (warnings > 0) " with $warnings warning(s)" else ""
                                    ToolResult(
                                        "Compilation of $target successful$warningNote: 0 errors.",
                                        "Build OK", ToolResult.ERROR_TOKEN_ESTIMATE
                                    )
                                }
                            }
                            if (!cont.isCompleted) cont.resume(compileResult)
                        }
                    }
                }
            }

            result ?: ToolResult(
                "Compilation timed out after 120 seconds. The build may be stuck.",
                "Compile timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } catch (e: Exception) {
            ToolResult("Compilation error: ${e.message}", "Compilation error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Shared helpers
    // ══════════════════════════════════════════════════════════════════════

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

    private fun formatStructuredResults(allTests: List<TestResultEntry>, runName: String): ToolResult {
        val passed = allTests.count { it.status == TestStatus.PASSED }
        val failed = allTests.count { it.status == TestStatus.FAILED }
        val errors = allTests.count { it.status == TestStatus.ERROR }
        val skipped = allTests.count { it.status == TestStatus.SKIPPED }
        val totalDuration = allTests.sumOf { it.durationMs }

        val overallStatus = when {
            errors > 0 || failed > 0 -> "FAILED"
            else -> "PASSED"
        }

        val sb = StringBuilder()
        sb.appendLine("Test Run: $runName")
        sb.appendLine("Status: $overallStatus ($passed passed, $failed failed, $errors error, $skipped skipped)")
        sb.appendLine("Duration: ${formatDuration(totalDuration)}")
        sb.appendLine()

        val failedTests = allTests.filter { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
        if (failedTests.isNotEmpty()) {
            sb.appendLine("--- FAILED ---")
            for (test in failedTests) {
                sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
                test.errorMessage?.let { sb.appendLine("  Assertion: $it") }
                if (test.stackTrace.isNotEmpty()) {
                    sb.appendLine("  Stack:")
                    for (frame in test.stackTrace) {
                        sb.appendLine("    $frame")
                    }
                }
                sb.appendLine()
            }
        }

        val skippedTests = allTests.filter { it.status == TestStatus.SKIPPED }
        if (skippedTests.isNotEmpty()) {
            sb.appendLine("--- SKIPPED ---")
            for (test in skippedTests) {
                sb.appendLine(test.name)
            }
            sb.appendLine()
        }

        val passedTests = allTests.filter { it.status == TestStatus.PASSED }
        if (passedTests.isNotEmpty()) {
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
        val capped = if (content.length > RUN_TESTS_TOKEN_CAP_CHARS) {
            content.take(RUN_TESTS_TOKEN_CAP_CHARS) + "\n... (results truncated)"
        } else {
            content
        }

        return ToolResult(
            capped,
            "$overallStatus: $passed passed, $failed failed",
            capped.length / 4,
            isError = overallStatus == "FAILED"
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

        // run_tests constants
        private const val RUN_TESTS_DEFAULT_TIMEOUT = 300L
        private const val RUN_TESTS_MAX_TIMEOUT = 900L
        /** Test tree finalization retry — when no TestResultsViewer is available,
         *  poll for the test root to be populated after process termination. */
        private const val TEST_TREE_RETRY_ATTEMPTS = 10
        private const val TEST_TREE_RETRY_INTERVAL_MS = 500L  // 10 * 500ms = 5s max wait

        /** Build watchdog timeout — how long to wait for CompilationStatusListener callback. */
        private const val BUILD_WATCHDOG_MAX_MS = 300_000L     // Hard cap at 5 min (matches test timeout)
        private const val RUN_TESTS_MAX_OUTPUT_CHARS = 4000
        private const val RUN_TESTS_TOKEN_CAP_CHARS = 12000

        // Shared test result constants
        private const val MAX_STACK_FRAMES = 5
        private const val MAX_PASSED_SHOWN = 20

        // compile_module constants
        private const val COMPILE_MAX_ERROR_MESSAGES = 20
    }
}
