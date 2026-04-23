package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.workflow.orchestrator.agent.tools.debug.DebugInvocation
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.util.ReflectionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
Runtime observation and launch — read console output and structured test results from running or recently finished run configurations, and launch/stop existing run configurations.

Actions and their parameters:
- get_running_processes() → List active run/debug sessions with status and PID
- get_run_output(config_name, last_n_lines?, filter?) → Read process console output (last_n_lines default 200, max 1000; filter: regex pattern). When multiple sessions share the same config name (e.g. a terminated Run tab and a live Debug session), the live one is selected; the result's Note line lists the other matches. The Launch Mode line shows whether the selected session is Run or Debug.
- get_test_results(config_name, status_filter?) → Get structured test results (status_filter: FAILED|ERROR|PASSED|SKIPPED)
- run_config(config_name, mode?, wait_for_ready?, wait_for_pause?, readiness_strategy?, ready_pattern?, ready_timeout_seconds?, wait_for_finish?, timeout_seconds?, tail_lines?, discover_ports?) → Launches fresh. If an instance of the same configuration is already running, it is stopped first (graceful then force). Returns READY with port info or DEBUG with session info.
- stop_run_config(config_name, graceful_timeout_seconds?, force_on_timeout?) → Stop a running process gracefully (and force-kill if timeout exceeded).

To run tests or compile: use java_runtime_exec (on IntelliJ with Java plugin) or python_runtime_exec (on PyCharm).
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_running_processes", "get_run_output", "get_test_results",
                    "run_config", "stop_run_config"
                )
            ),
            "config_name" to ParameterProperty(
                type = "string",
                description = "Name of the run configuration"
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
            "mode" to ParameterProperty(
                type = "string",
                description = "Launch mode — for run_config",
                enumValues = listOf("run", "debug", "coverage", "profile")
            ),
            "wait_for_ready" to ParameterProperty(
                type = "boolean",
                description = "Wait until app signals readiness (default true) — for run_config"
            ),
            "wait_for_pause" to ParameterProperty(
                type = "boolean",
                description = "Wait for first breakpoint pause in debug mode (default false) — for run_config"
            ),
            "readiness_strategy" to ParameterProperty(
                type = "string",
                description = "Override readiness detection strategy — for run_config",
                enumValues = listOf("auto", "process_started", "log_pattern", "idle_stdout", "explicit_pattern", "http_probe")
            ),
            "ready_url" to ParameterProperty(
                type = "string",
                description = "Full URL to probe for readiness (e.g. http://localhost:8080/health). Overrides auto-detection when readiness_strategy=http_probe — for run_config"
            ),
            "ready_pattern" to ParameterProperty(
                type = "string",
                description = "Custom regex to match readiness in stdout (required when readiness_strategy=explicit_pattern) — for run_config"
            ),
            "ready_timeout_seconds" to ParameterProperty(
                type = "integer",
                description = "Timeout for readiness detection in seconds (default 120) — for run_config"
            ),
            "wait_for_finish" to ParameterProperty(
                type = "boolean",
                description = "Block until process exits (default false) — for run_config"
            ),
            "timeout_seconds" to ParameterProperty(
                type = "integer",
                description = "Overall process timeout in seconds (default 600, only when wait_for_finish=true) — for run_config"
            ),
            "tail_lines" to ParameterProperty(
                type = "integer",
                description = "Lines of output to include in result (default 200) — for run_config"
            ),
            "discover_ports" to ParameterProperty(
                type = "boolean",
                description = "Attempt port discovery via log patterns and lsof/ss/netstat (default true) — for run_config"
            ),
            "graceful_timeout_seconds" to ParameterProperty(
                type = "integer",
                description = "Seconds to wait for graceful shutdown before force-killing (default 10) — for stop_run_config"
            ),
            "force_on_timeout" to ParameterProperty(
                type = "boolean",
                description = "Force-kill if graceful timeout exceeded (default true) — for stop_run_config"
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
            "run_config" -> executeRunConfig(params, project)
            "stop_run_config" -> executeStopRunConfig(params, project)
            "run_tests", "compile_module" -> ToolResult(
                content = "Action '$action' is handled by java_runtime_exec (on IntelliJ with the Java plugin) or python_runtime_exec (on PyCharm). This tool only provides process observation — use tool_search to load the IDE-specific variant.",
                summary = "Action '$action' moved to java_runtime_exec / python_runtime_exec",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: get_running_processes, get_run_output, get_test_results, run_config, stop_run_config",
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

            val match = selectDescriptorByName(allDescriptors, configName)

            if (match == null) {
                val available = allDescriptors.mapNotNull { it.displayName }
                val availableMsg = if (available.isNotEmpty()) {
                    "\nAvailable sessions: ${available.joinToString(", ")}"
                } else {
                    "\nNo run sessions available."
                }
                return ToolResult("No run session found matching '$configName'.$availableMsg", "Not found", 30, isError = true)
            }

            val descriptor = match.descriptor
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
            sb.appendLine("Launch Mode: ${describeLaunchMode(descriptor, project)}")
            sb.appendLine("Status: ${describeProcessStatus(descriptor)}")
            if (match.others.isNotEmpty()) {
                val othersDesc = match.others.joinToString(", ") { d ->
                    "${d.displayName} [${describeLaunchMode(d, project)} / ${describeProcessStatus(d)}]"
                }
                sb.appendLine("Note: ${match.others.size + 1} sessions matched '$configName'. " +
                    "Selected the ${if (match.pickedLive) "live" else "most recent"} one. Other matches: $othersDesc")
            }
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
            val spilled = spillOrFormat(content, project)

            ToolResult(
                content = spilled.preview,
                summary = "${lines.size} lines from ${descriptor.displayName}",
                tokenEstimate = spilled.preview.length / 4,
                spillPath = spilled.spilledToFile,
            )
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
                selectDescriptorByName(allDescriptors, configName)?.descriptor
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

            // Route through the canonical interpreter — correctly handles empty-suite /
            // terminated / defect cases (fixes the "0/0/0/0 → PASSED" bug where the old
            // inline classifier mapped an empty suite to PASSED). formatStructuredResults
            // routes through spillOrFormat so full output lands on disk.
            interpretTestRoot(testRoot, descriptor.displayName ?: "unknown", this, project, statusFilter)
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
                // M1 fix: tie the listener lifecycle to a child Disposable registered on
                // resultsViewer so it is released when the viewer is disposed. Also wire
                // invokeOnCancellation so coroutine cancel/timeout disposes the child
                // immediately — preventing a listener from outliving the coroutine.
                // TestResultsViewer has no removeEventsListener API; Disposer is the only
                // documented release path (mirrors JavaRuntimeExecTool.handleDescriptorReady).
                val child = Disposer.newDisposable(resultsViewer, "agent-awaitTestingFinished")
                val listener = object : TestResultsViewer.EventsListener {
                    override fun onTestingFinished(sender: TestResultsViewer) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                resultsViewer.addEventsListener(listener)
                continuation.invokeOnCancellation {
                    Disposer.dispose(child)
                }
            }
        }
    }

    private fun hasTestResults(descriptor: RunContentDescriptor): Boolean {
        val root = TestConsoleUtils.findTestRoot(descriptor) ?: return false
        return root.children.isNotEmpty()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: run_config — unified launcher (run + debug modes)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Launch an existing run configuration in run or debug mode. Waits for readiness
     * (log-pattern / idle-stdout / XDebugSession attached) then detaches — app stays alive.
     * Returns READY (run) or DEBUG (debug mode) payload with PID, ports, and ready signal.
     */
    private suspend fun executeRunConfig(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "CONFIGURATION_NOT_FOUND: Missing required parameter 'config_name'.",
                "Missing config_name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        val mode = params["mode"]?.jsonPrimitive?.content ?: "run"
        val waitForReady = params["wait_for_ready"]?.jsonPrimitive?.booleanOrNull ?: true
        val waitForPause = params["wait_for_pause"]?.jsonPrimitive?.booleanOrNull ?: false
        val readinessStrategy = params["readiness_strategy"]?.jsonPrimitive?.content ?: "auto"
        val readyPattern = params["ready_pattern"]?.jsonPrimitive?.content
        val readyUrl = params["ready_url"]?.jsonPrimitive?.content
        val readyTimeoutSec = (params["ready_timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 120).coerceIn(1, 600)
        val waitForFinish = params["wait_for_finish"]?.jsonPrimitive?.booleanOrNull ?: false
        val timeoutSec = (params["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 600).coerceIn(1, 3600)
        val tailLines = (params["tail_lines"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 1000)
        val discoverPorts = params["discover_ports"]?.jsonPrimitive?.booleanOrNull ?: true

        // Guard unsupported modes early
        if (mode == "coverage") return ToolResult(
            "INVALID_CONFIGURATION: mode=coverage is not yet supported by run_config. " +
                "Use coverage.run_with_coverage instead.",
            "mode=coverage not supported", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )
        if (mode == "profile") return ToolResult(
            "INVALID_CONFIGURATION: mode=profile is not yet supported by run_config. " +
                "A dedicated profiler tool is planned for a future release.",
            "mode=profile not supported", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )
        if (mode != "run" && mode != "debug") return ToolResult(
            "INVALID_CONFIGURATION: Unknown mode '$mode'. Supported: run, debug.",
            "Unknown mode", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )

        // DumbService guard — IDE is indexing, running now would be unreliable
        if (DumbService.isDumb(project)) return ToolResult(
            "DUMB_MODE: IDE is currently indexing. Please wait for indexing to complete " +
                "before launching a run configuration.",
            "DUMB_MODE: indexing in progress", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )

        // Name resolution: exact match → unique substring → error
        // M3 fix: RunManager.getInstance + allSettings access must run inside a ReadAction
        // (same pattern as JavaRuntimeExecTool.detectTestFramework / findModuleForClass and
        // DebugBreakpointsTool.executeMethodBreakpoint which all use ReadAction.compute).
        val runManager = RunManager.getInstance(project)
        val allSettings = ReadAction.compute<List<com.intellij.execution.RunnerAndConfigurationSettings>, Throwable> {
            runManager.allSettings
        }
        val settings = ReadAction.compute<com.intellij.execution.RunnerAndConfigurationSettings?, Throwable> {
            runManager.findConfigurationByName(configName)
        } ?: run {
            val matches = allSettings.filter { it.name.contains(configName, ignoreCase = false) }
            when {
                matches.size == 1 -> matches[0]
                matches.size > 1 -> return ToolResult(
                    "AMBIGUOUS_MATCH: '$configName' matches multiple configurations: " +
                        matches.joinToString(", ") { it.name } +
                        ". Use the exact name.",
                    "AMBIGUOUS_MATCH", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
                else -> return ToolResult(
                    "CONFIGURATION_NOT_FOUND: No run configuration named '$configName'. " +
                        "Available: [${allSettings.joinToString(", ") { it.name }}]",
                    "CONFIGURATION_NOT_FOUND", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }
        }

        // Config-type guardrails
        val config = settings.configuration
        val configTypeId = config.type.id
        if (configTypeId == "Remote") return ToolResult(
            "INVALID_CONFIGURATION: '$configName' is a remote debug configuration. " +
                "Use debug_breakpoints.attach_to_process instead.",
            "INVALID_CONFIGURATION: remote", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )
        if (configTypeId == "JUnit" || configTypeId == "TestNG") return ToolResult(
            "INVALID_CONFIGURATION: '$configName' is a test configuration. " +
                "Use java_runtime_exec.run_tests instead.",
            "INVALID_CONFIGURATION: junit", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )

        // checkConfiguration — errors block, warnings pass.
        // Must run inside a read action: Spring Boot's checkConfiguration() resolves the
        // main class via JavaPsiFacade.findClass(), which triggers FileBasedIndex.ensureUpToDate()
        // and asserts read access. We're on a Dispatchers.IO worker here, so wrap in ReadAction.
        try {
            ReadAction.compute<Unit, Throwable> {
                config.checkConfiguration()
            }
        } catch (e: RuntimeConfigurationError) {
            return ToolResult(
                "INVALID_CONFIGURATION: ${e.message}",
                "INVALID_CONFIGURATION", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } catch (_: RuntimeConfigurationWarning) {
            // Warnings are non-fatal — proceed with launch
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Other exceptions from checkConfiguration — proceed
        }

        // Idempotent stop: if an instance of this config is already running, stop it first.
        // Uses the resolved settings.name so substring-matched configs target the specific name.
        val preLaunchNote: String? = when (val stopOutcome = stopProcessesForConfig(project, settings.name)) {
            is StopOutcome.NotRunning -> null
            is StopOutcome.StoppedGracefully ->
                "Stopped existing '${settings.name}' (PID ${stopOutcome.pids}, graceful) before relaunch."
            is StopOutcome.StoppedForced ->
                "Stopped existing '${settings.name}' (PID ${stopOutcome.pids}, forced) before relaunch."
            is StopOutcome.FailedToStop ->
                return ToolResult(
                    "STOP_FAILED: Could not stop existing instance of '${settings.name}' " +
                        "(PID ${stopOutcome.pids}): ${stopOutcome.message}",
                    "STOP_FAILED", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
        }

        // Executor selection
        val executor = when (mode) {
            "debug" -> DefaultDebugExecutor.getDebugExecutorInstance()
            else -> DefaultRunExecutor.getRunExecutorInstance()
        }

        // Build ExecutionEnvironment
        val envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings)
            ?: return ToolResult(
                "NO_RUNNER_REGISTERED: No ProgramRunner registered for executor '${executor.id}' " +
                    "on configuration type '$configTypeId'. Check that the required plugin is installed.",
                "NO_RUNNER_REGISTERED", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        val invocation = project.service<AgentService>().newRunInvocation("run-config-${config.name}")
        try {
            val env = envBuilder.build()
            val launchEnv = AtomicReference<ExecutionEnvironment?>(env)

            // Readiness state
            val processStartFailed = AtomicBoolean(false)
            val processStartFailedMsg = AtomicReference<String?>(null)
            val descriptorRef = AtomicReference<RunContentDescriptor?>(null)
            val processHandlerRef = AtomicReference<ProcessHandler?>(null)

            // Follow-up A: Subscribe to CompilerTopics.COMPILATION_STATUS BEFORE launching
            // so we can capture per-file compile errors from the implicit before-run Build task.
            // The AtomicReference pattern is identical to JavaRuntimeExecTool's explicit build
            // phase (JavaRuntimeExecTool.kt:340-362) — stores CompileContext for use in the
            // processNotStarted callback below when discriminating BEFORE_RUN_FAILED vs
            // PROCESS_START_FAILED (research doc Section A4-A5).
            val compileContextRef = AtomicReference<CompileContext?>(null)
            val compileConnection = project.messageBus.connect()
            invocation.subscribeTopic(compileConnection)
            compileConnection.subscribe(
                CompilerTopics.COMPILATION_STATUS,
                object : CompilationStatusListener {
                    override fun compilationFinished(
                        aborted: Boolean,
                        errors: Int,
                        warnings: Int,
                        compileContext: CompileContext,
                    ) {
                        if (aborted || errors > 0) {
                            compileContextRef.set(compileContext)
                        }
                    }
                }
            )

            // Subscribe to EXECUTION_TOPIC before launching (Section 5 correlation).
            // processNotStarted: discriminate BEFORE_RUN_FAILED (compile errors captured
            // in compileContextRef) vs PROCESS_START_FAILED (generic launch failure).
            val runConnection = project.messageBus.connect()
            invocation.subscribeTopic(runConnection)
            runConnection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processNotStarted(executorId: String, e: ExecutionEnvironment) {
                    if (e === launchEnv.get()) {
                        val compileCtx = compileContextRef.get()
                        if (compileCtx != null) {
                            // Before-run Build task failed: surface per-file compile errors
                            // (research doc Section A5, checklist D1f).
                            val compileResult = formatCompileErrors(
                                context = compileCtx,
                                target = config.name,
                                leadingLine = "BEFORE_RUN_FAILED: Build task failed before launch of '${config.name}':"
                            )
                            processStartFailed.set(true)
                            processStartFailedMsg.set(compileResult.content)
                        } else {
                            processStartFailed.set(true)
                            processStartFailedMsg.set("PROCESS_START_FAILED: Process failed to start.")
                        }
                    }
                }
                override fun processStarted(
                    executorId: String,
                    e: ExecutionEnvironment,
                    handler: ProcessHandler,
                ) {
                    // Observation only; handler stored via ProgramRunner.Callback
                }
            })

            // Accumulated log output and readiness signal
            val logBuffer = StringBuilder()
            val readinessAchieved = AtomicBoolean(false)
            val readySignal = AtomicReference<String?>(null)
            val discoveredPorts = mutableSetOf<Int>()

            // Spring Boot readiness patterns — used ONLY as readiness signals (app has finished
            // bootstrapping). The matched port number in groups[1] is intentionally NOT stored as
            // the port value reported to the LLM: run configurations can override the port via VM
            // options, env vars, active profiles, programmatic setDefaultProperties, cloud config,
            // or random port mode (server.port=0). Only OS PID discovery (lsof/ss/netstat) is the
            // authoritative port source. See discoverListeningPorts() and the design note in
            // SpringBootConfigParser.kt.
            val springReadinessPatterns = listOf(
                Regex("""^.*Tomcat started on port\(?s?\)?:?\s*(\d+)""", RegexOption.MULTILINE),
                Regex("""^.*Netty started on port\(?s?\)?:?\s*(\d+)""", RegexOption.MULTILINE),
                Regex("""^.*Jetty started on port\(?s?\)?:?\s*(\d+)""", RegexOption.MULTILINE),
                Regex("""^.*Undertow started on port\(?s?\)?:?\s*(\d+)""", RegexOption.MULTILINE),
                Regex("""^.*Started\s+[A-Za-z0-9._-]+\s+in\s+[0-9.]+s""", RegexOption.MULTILINE),
            )

            // Launch callback
            val callbackSlot = AtomicReference<ProgramRunner.Callback?>(null)
            val processListenerAttached = AtomicBoolean(false)

            val callback = object : ProgramRunner.Callback {
                override fun processStarted(descriptor: RunContentDescriptor?) {
                    if (descriptor == null) return
                    descriptorRef.set(descriptor)
                    val handler = descriptor.processHandler ?: return
                    processHandlerRef.set(handler)
                    invocation.descriptorRef.set(descriptor)
                    invocation.processHandlerRef.set(handler)

                    // Register cleanup (source-text anchor for leak test A3).
                    // Only remove descriptor from RunContentManager when NOT detached.
                    // Detach path (wait_for_ready=true, wait_for_finish=false) sets
                    // invocation.descriptorRef to null before Disposer.dispose() so the
                    // process keeps running; removeRunContent must be skipped in that case.
                    invocation.onDispose {
                        try {
                            if (invocation.descriptorRef.get() != null) {
                                RunContentManager.getInstance(project).removeRunContent(executor, descriptor)
                            }
                        } catch (_: Exception) {}
                    }

                    if (waitForReady && mode == "run") {
                        val readinessListener = object : ProcessAdapter() {
                            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                                if (readinessAchieved.get()) return
                                val text = event.text ?: return
                                logBuffer.append(text)

                                // Check explicit_pattern first
                                if (readinessStrategy == "explicit_pattern" && readyPattern != null) {
                                    if (Regex(readyPattern).containsMatchIn(text)) {
                                        readinessAchieved.set(true)
                                        readySignal.set(text.trim())
                                    }
                                    return
                                }

                                // Check log patterns (Spring Boot banners) — readiness signal only.
                                // The matched port number in groups[1] is intentionally discarded:
                                // run-config overrides make static log-scraped ports unreliable.
                                // Authoritative port comes from discoverListeningPorts() via OS PID.
                                for (pattern in springReadinessPatterns) {
                                    val match = pattern.find(logBuffer.toString())
                                    if (match != null) {
                                        readinessAchieved.set(true)
                                        readySignal.set(text.trim())
                                        // Port from log banner intentionally not stored here —
                                        // OS PID discovery is the only authoritative port source.
                                        break
                                    }
                                }
                            }

                            override fun processTerminated(event: ProcessEvent) {
                                if (!readinessAchieved.get()) {
                                    // Process exited before ready — mark as failed
                                    processStartFailed.set(true)
                                    processStartFailedMsg.set(
                                        "EXITED_BEFORE_READY: Process exited with code ${event.exitCode} " +
                                            "before reaching ready state."
                                    )
                                }
                            }
                        }
                        invocation.attachProcessListener(handler, readinessListener)
                        processListenerAttached.set(true)
                    }
                }
            }
            callbackSlot.set(callback)

            // Launch: ProgramRunnerUtil.executeConfigurationAsync handles its own EDT dispatch
            // internally (2025.1+ implementation posts to EDT via getApplication().invokeLaterIfEdtRequired).
            // We do NOT wrap in withContext(Dispatchers.EDT) here because that requires an initialized
            // IntelliJ Application context and would fail in unit tests that mock the static APIs.
            // The callback and ExecutionListener wired above receive results asynchronously regardless
            // of which thread calls executeConfigurationAsync.
            try {
                ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
            } catch (_: NoSuchMethodError) {
                env.callback = callback
                ProgramRunnerUtil.executeConfiguration(env, false, true)
            }

            // Check for immediate launch failure
            if (processStartFailed.get()) {
                val msg = processStartFailedMsg.get() ?: "PROCESS_START_FAILED: Unknown launch failure."
                return ToolResult(msg, "Launch failed", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            // Debug mode path: wait for XDebugSession
            if (mode == "debug") {
                val debugSessionName = AtomicReference<String?>(null)
                val debugPid = AtomicReference<Long?>(null)
                val debugPausedAt = AtomicReference<String?>(null)
                val debugSessionReady = AtomicBoolean(false)

                // Subscribe to XDebuggerManager.TOPIC for debug session detection
                val debugConnection = project.messageBus.connect()
                invocation.subscribeTopic(debugConnection)
                debugConnection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                    override fun processStarted(debugProcess: XDebugProcess) {
                        val session = debugProcess.session
                        if (!session.isStopped) {
                            debugSessionName.set(session.sessionName)
                            debugPid.set(extractPid(debugProcess.processHandler ?: return))
                            debugSessionReady.set(true)
                            if (waitForPause && session.isPaused) {
                                val pos = session.currentPosition
                                if (pos != null) {
                                    debugPausedAt.set("${pos.file.path}:${pos.line + 1}")
                                }
                            }
                        }
                    }
                })

                val debugReady = withTimeoutOrNull(readyTimeoutSec * 1000L) {
                    while (!debugSessionReady.get() && !processStartFailed.get()) {
                        coroutineContext.ensureActive()
                        delay(100)
                    }
                    debugSessionReady.get()
                }

                if (processStartFailed.get()) {
                    val msg = processStartFailedMsg.get() ?: "PROCESS_START_FAILED: Debug process failed to start."
                    return ToolResult(msg, "Debug launch failed", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                }

                if (debugReady != true) {
                    return ToolResult(
                        "TIMEOUT_WAITING_FOR_READY: Debug session was not established within ${readyTimeoutSec}s. " +
                            "Check run configuration and build errors.",
                        "TIMEOUT_WAITING_FOR_READY", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }

                // S5 fix: replace poll-on-isPaused with XDebugSessionListener.sessionPaused()
                // via DebugInvocation.attachListener (2-arg addSessionListener form — auto-removes
                // via Disposer). This matches the pattern used by AgentDebugController.registerSession.
                if (waitForPause) {
                    val sessions = XDebuggerManager.getInstance(project).debugSessions
                    val sessionName = debugSessionName.get()
                    val session = sessions.firstOrNull { it.sessionName == sessionName }
                    if (session != null && !session.isPaused && !session.isStopped) {
                        val debugInvocation = project.service<AgentService>().newDebugInvocation(
                            "run-config-wait-pause-${config.name}"
                        )
                        try {
                            withTimeoutOrNull(readyTimeoutSec * 1000L) {
                                suspendCancellableCoroutine { cont ->
                                    debugInvocation.attachListener(session, object : XDebugSessionListener {
                                        override fun sessionPaused() {
                                            if (cont.isActive) cont.resume(Unit)
                                        }
                                        override fun sessionStopped() {
                                            if (cont.isActive) cont.resume(Unit)
                                        }
                                    })
                                    cont.invokeOnCancellation { Disposer.dispose(debugInvocation) }
                                }
                            }
                        } finally {
                            Disposer.dispose(debugInvocation)
                        }
                    }
                    if (session != null && session.isPaused) {
                        val pos = session.currentPosition
                        if (pos != null) {
                            debugPausedAt.set("${pos.file.path}:${pos.line + 1}")
                        }
                    }
                }

                // Detach: null refs before dispose so process survives
                invocation.descriptorRef.set(null)
                invocation.processHandlerRef.set(null)

                val sb = StringBuilder()
                sb.appendLine("DEBUG")
                sb.appendLine("Config: ${config.name}")
                sb.appendLine("Session: ${debugSessionName.get() ?: "unknown"}")
                debugPid.get()?.let { sb.appendLine("PID: $it") }
                if (waitForPause) {
                    val paused = debugPausedAt.get()
                    if (paused != null) sb.appendLine("paused_at: $paused")
                    else sb.appendLine("Status: running (no breakpoint hit within ${readyTimeoutSec}s)")
                } else {
                    sb.appendLine("Status: DEBUG session active")
                }
                val content = sb.toString().trimEnd()
                val spilled = spillOrFormat(content, project)
                return ToolResult(
                    content = spilled.preview,
                    summary = "DEBUG: ${debugSessionName.get() ?: config.name}",
                    tokenEstimate = spilled.preview.length / 4,
                    spillPath = spilled.spilledToFile
                )
            }

            // Run mode: wait_for_ready path
            if (waitForReady) {
                val isSpring = configTypeId.contains("SpringBoot", ignoreCase = true) ||
                    configTypeId.contains("spring", ignoreCase = true) ||
                    SpringBootConfigParser.isSpringBootConfig(settings)

                // Validate http_probe without Spring config or explicit ready_url
                if (readinessStrategy == "http_probe" && !isSpring && readyUrl == null) {
                    return ToolResult(
                        "READINESS_DETECTION_FAILED: http_probe requires a Spring Boot config or an explicit ready_url parameter.",
                        "READINESS_DETECTION_FAILED", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }

                // Determine effective strategy
                val useHttpProbe = readyUrl != null ||
                    readinessStrategy == "http_probe" ||
                    (readinessStrategy == "auto" && isSpring)

                val readyResult: Boolean?
                val httpProbeSignal = AtomicReference<String?>(null)

                if (useHttpProbe) {
                    // Resolve probe URL.
                    // If the user supplied a ready_url, use it verbatim — the user owns correctness.
                    // Otherwise, we MUST have an OS-discovered port before probing: static config
                    // parsing is intentionally skipped because run configurations override the port
                    // via VM options, env vars, active profiles, programmatic setDefaultProperties,
                    // cloud config, or random-port mode (server.port=0) that static parsing cannot see.
                    val probeUrl = if (readyUrl != null) {
                        readyUrl
                    } else {
                        // Wait briefly for the process to start binding, then attempt OS port discovery.
                        // We poll up to HTTP_PROBE_GRACE_MS (5 s) before giving up.
                        val pid = processHandlerRef.get()?.let { extractPid(it) }
                        var osPort: Int? = null
                        if (pid != null) {
                            val probeStartMs = System.currentTimeMillis()
                            while (osPort == null && (System.currentTimeMillis() - probeStartMs) < HTTP_PROBE_GRACE_MS) {
                                coroutineContext.ensureActive()
                                val discovered = discoverListeningPorts(pid)
                                if (discovered.isNotEmpty()) {
                                    osPort = discovered.min()
                                    discoveredPorts.addAll(discovered)
                                } else {
                                    delay(OS_PROBE_POLL_MS)
                                }
                            }
                        }
                        if (osPort == null) {
                            // No port observed via OS commands — skip HTTP probe and fall through
                            // to log-pattern + idle-stdout strategies. Per "no info > wrong info"
                            // principle, we do NOT fall back to static config parsing.
                            null
                        } else {
                            val paths = SpringBootConfigParser.parseActuatorPaths(settings, project)
                            "http://localhost:$osPort${paths.actuatorBasePath}${paths.healthPath}"
                        }
                    }

                    if (probeUrl != null) {
                        // Race HTTP probe against log-pattern (first wins).
                        readyResult = withTimeoutOrNull(readyTimeoutSec * 1000L) {
                            coroutineScope {
                                val httpJob = async {
                                    // Port already discovered above; grace period is minimal (probe won't
                                    // hit connection-refused since we found the port binding via lsof/ss).
                                    val probe = HttpReadinessProbe()
                                    val result = probe.poll(
                                        url = probeUrl,
                                        timeoutMs = readyTimeoutSec * 1000L,
                                        gracePeriodMs = HTTP_PROBE_GRACE_MS,
                                    )
                                    result is HttpReadinessProbe.ProbeResult.Success
                                }
                                val logJob = async {
                                    // Background log-pattern fallback
                                    while (!readinessAchieved.get() && !processStartFailed.get()) {
                                        coroutineContext.ensureActive()
                                        delay(LOG_PATTERN_POLL_MS)
                                    }
                                    readinessAchieved.get()
                                }

                                // First wins
                                var result = false
                                while (!httpJob.isCompleted && !logJob.isCompleted && !processStartFailed.get()) {
                                    coroutineContext.ensureActive()
                                    delay(READY_RACE_POLL_MS)
                                }
                                if (httpJob.isCompleted && httpJob.await()) {
                                    httpProbeSignal.set("HTTP probe 200 OK: $probeUrl")
                                    // Port already in discoveredPorts from OS discovery above
                                    result = true
                                } else if (logJob.isCompleted && logJob.await()) {
                                    result = true
                                } else if (!processStartFailed.get()) {
                                    // Neither completed — wait for both
                                    val httpOk = httpJob.await()
                                    val logOk = logJob.await()
                                    result = httpOk || logOk
                                    if (httpOk) httpProbeSignal.set("HTTP probe 200 OK: $probeUrl")
                                }
                                httpJob.cancel()
                                logJob.cancel()
                                result
                            }
                        }
                    } else {
                        // No OS-discovered port yet — skip HTTP probe, fall back to log-pattern only.
                        // Per "no info > wrong info", we do NOT guess a port from static config parsing.
                        readyResult = withTimeoutOrNull(readyTimeoutSec * 1000L) {
                            while (!readinessAchieved.get() && !processStartFailed.get()) {
                                coroutineContext.ensureActive()
                                delay(LOG_PATTERN_POLL_MS)
                            }
                            readinessAchieved.get()
                        }
                    }
                } else {
                    readyResult = withTimeoutOrNull(readyTimeoutSec * 1000L) {
                        when {
                            readinessStrategy == "process_started" -> true
                            readinessStrategy == "explicit_pattern" || readinessStrategy == "log_pattern" || isSpring -> {
                                while (!readinessAchieved.get() && !processStartFailed.get()) {
                                    coroutineContext.ensureActive()
                                    delay(LOG_PATTERN_POLL_MS)
                                }
                                readinessAchieved.get()
                            }
                            else -> {
                                // idle_stdout or auto for non-Spring: wait for stdout to go idle
                                var lastLogLength = logBuffer.length
                                delay(IDLE_STDOUT_INITIAL_WAIT_MS)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    if (processStartFailed.get()) return@withTimeoutOrNull false
                                    val currentLength = logBuffer.length
                                    if (currentLength == lastLogLength) break
                                    lastLogLength = currentLength
                                    delay(IDLE_STDOUT_POLL_MS)
                                }
                                !processStartFailed.get()
                            }
                        }
                    }
                }

                if (processStartFailed.get()) {
                    val msg = processStartFailedMsg.get() ?: "EXITED_BEFORE_READY"
                    return ToolResult(msg, "Process failed before ready", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                }

                if (readyResult != true) {
                    val lastLines = logBuffer.toString().lines()
                        .takeLast(tailLines)
                        .joinToString("\n")
                    return ToolResult(
                        "TIMEOUT_WAITING_FOR_READY: Application '${config.name}' did not reach ready state within " +
                            "${readyTimeoutSec}s.\nLast output:\n$lastLines",
                        "TIMEOUT_WAITING_FOR_READY", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }

                // Port discovery: lsof/ss/netstat after readiness
                if (discoverPorts) {
                    val pid = processHandlerRef.get()?.let { extractPid(it) }
                    if (pid != null) {
                        val osPorts = discoverListeningPorts(pid)
                        discoveredPorts.addAll(osPorts)
                    }
                }

                // Detach: null refs so dispose() doesn't kill the process
                invocation.descriptorRef.set(null)
                invocation.processHandlerRef.set(null)

                val pid = processHandlerRef.get()?.let { extractPid(it) }
                val sb = StringBuilder()
                sb.appendLine("READY")
                sb.appendLine("Config: ${config.name}")
                sb.appendLine("Type: $configTypeId")
                sb.appendLine("Mode: run")
                pid?.let { sb.appendLine("PID: $it") }
                if (discoveredPorts.isNotEmpty()) {
                    sb.appendLine("Listening ports: ${discoveredPorts.toSortedSet()}")
                }
                val effectiveSignal = httpProbeSignal.get() ?: readySignal.get()
                effectiveSignal?.let { sb.appendLine("Ready signal: $it") }
                sb.appendLine("Status: READY to serve traffic")
                preLaunchNote?.let { sb.appendLine(it) }
                val content = sb.toString().trimEnd()
                val spilled = spillOrFormat(content, project)
                return ToolResult(
                    content = spilled.preview,
                    summary = "READY: ${config.name}${if (discoveredPorts.isNotEmpty()) " on ports $discoveredPorts" else ""}",
                    tokenEstimate = spilled.preview.length / 4,
                    spillPath = spilled.spilledToFile
                )
            }

            // wait_for_finish path
            if (waitForFinish) {
                val handler = processHandlerRef.get()
                val finishResult: Boolean? = if (handler != null) {
                    withTimeoutOrNull(timeoutSec * 1000L) {
                        awaitProcessTermination(handler, timeoutSec * 1000L, project)
                    }
                } else null

                if (finishResult == null || finishResult == false) {
                    val lastLines = logBuffer.toString().lines()
                        .takeLast(tailLines)
                        .joinToString("\n")
                    return ToolResult(
                        "TIMEOUT_WAITING_FOR_PROCESS: Process '${config.name}' is still running after ${timeoutSec}s. " +
                            "Last output:\n$lastLines",
                        "TIMEOUT_WAITING_FOR_PROCESS: still running after timeout",
                        ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }

                val lastLines = logBuffer.toString().lines()
                    .takeLast(tailLines)
                    .joinToString("\n")
                val content = "Process '${config.name}' finished.\nExit code: 0\nOutput tail:\n$lastLines".trimEnd()
                val spilled = spillOrFormat(content, project)
                return ToolResult(
                    content = spilled.preview,
                    summary = "Process ${config.name} finished (exit 0)",
                    tokenEstimate = spilled.preview.length / 4,
                    spillPath = spilled.spilledToFile
                )
            }

            // Fire-and-forget: launched, not waiting
            if (processStartFailed.get()) {
                val msg = processStartFailedMsg.get() ?: "PROCESS_START_FAILED: Process did not start."
                return ToolResult(msg, "Launch failed", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }
            val ffBase = "Config '${config.name}' launched in run mode (fire-and-forget)."
            val content = if (preLaunchNote != null) "$ffBase\n$preLaunchNote" else ffBase
            return ToolResult(content, "Launched ${config.name}", content.length / 4)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult(
                "UNEXPECTED_ERROR: ${e.javaClass.simpleName}: ${e.message}",
                "UNEXPECTED_ERROR", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } finally {
            Disposer.dispose(invocation)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Shared stop helper — used by stop_run_config and run_config (idempotent)
    // ══════════════════════════════════════════════════════════════════════

    private sealed class StopOutcome {
        object NotRunning : StopOutcome()
        data class StoppedGracefully(val pids: List<Long>) : StopOutcome()
        data class StoppedForced(val pids: List<Long>) : StopOutcome()
        data class FailedToStop(val pids: List<Long>, val message: String) : StopOutcome()
    }

    /**
     * Stop all running processes matching [configName] (exact display-name match against
     * RunContentDescriptor.displayName). Performs graceful destroy → poll → force destroy → poll.
     * Handles coroutine cancellation by re-throwing [CancellationException].
     */
    private suspend fun stopProcessesForConfig(
        project: Project,
        configName: String,
        gracefulMs: Long = 10_000L,
        forceMs: Long = 5_000L,
    ): StopOutcome {
        val handlers = ExecutionManager.getInstance(project).getRunningProcesses()
            .filter { h ->
                val displayName = try {
                    val mgr = RunContentManager.getInstance(project)
                    mgr.allDescriptors.firstOrNull { d -> d.processHandler === h }?.displayName
                } catch (_: Exception) { null }
                displayName == configName || h.toString().contains(configName, ignoreCase = true)
            }
            .filter { !it.isProcessTerminated }

        if (handlers.isEmpty()) return StopOutcome.NotRunning

        val pids = handlers.mapNotNull { extractPid(it) }

        // Graceful destroy
        for (handler in handlers) {
            handler.destroyProcess()
        }

        // Poll until graceful timeout
        val allGraceful = withTimeoutOrNull(gracefulMs) {
            var remaining = handlers.filter { !it.isProcessTerminated }
            while (remaining.isNotEmpty()) {
                coroutineContext.ensureActive()
                delay(STOP_POLL_INTERVAL_MS)
                remaining = remaining.filter { !it.isProcessTerminated }
            }
            true
        } ?: false

        if (allGraceful) return StopOutcome.StoppedGracefully(pids)

        // Force kill
        for (handler in handlers.filter { !it.isProcessTerminated }) {
            try {
                @Suppress("DEPRECATION")
                handler.destroyProcess()
            } catch (_: Exception) {}
        }

        val allForced = withTimeoutOrNull(forceMs) {
            var remaining = handlers.filter { !it.isProcessTerminated }
            while (remaining.isNotEmpty()) {
                coroutineContext.ensureActive()
                delay(STOP_POLL_INTERVAL_MS)
                remaining = remaining.filter { !it.isProcessTerminated }
            }
            true
        } ?: false

        val stillAlive = handlers.filter { !it.isProcessTerminated }
        return if (allForced || stillAlive.isEmpty()) {
            StopOutcome.StoppedForced(pids)
        } else {
            StopOutcome.FailedToStop(
                pids,
                "Process '${configName}' could not be force-killed. ${stillAlive.size} process(es) still running."
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: stop_run_config — graceful (+ force) shutdown
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Stop a running process by config name. Graceful SIGTERM then optional force-kill.
     * Returns STOPPED with process count, or STOP_FAILED / PROCESS_NOT_RUNNING.
     * Delegates to [stopProcessesForConfig] for the stop state machine.
     */
    private suspend fun executeStopRunConfig(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Missing required parameter 'config_name'.",
                "Missing config_name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        val gracefulTimeoutSec = (params["graceful_timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 300)
        val forceOnTimeout = params["force_on_timeout"]?.jsonPrimitive?.booleanOrNull ?: true

        val handlers = ExecutionManager.getInstance(project).getRunningProcesses()
            .filter { h ->
                // Match by descriptor display name if available
                val displayName = try {
                    val mgr = RunContentManager.getInstance(project)
                    mgr.allDescriptors.firstOrNull { d -> d.processHandler === h }?.displayName
                } catch (_: Exception) { null }
                displayName?.contains(configName, ignoreCase = true) == true ||
                    h.toString().contains(configName, ignoreCase = true)
            }
            .filter { !it.isProcessTerminated }

        if (handlers.isEmpty()) {
            return ToolResult(
                "PROCESS_NOT_RUNNING: No running process matching '$configName'.",
                "PROCESS_NOT_RUNNING", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val invocation = project.service<AgentService>().newRunInvocation("stop-$configName")
        try {
            // Graceful destroy
            for (handler in handlers) {
                handler.destroyProcess()
            }

            // Poll until terminated or timeout
            val gracefulMs = gracefulTimeoutSec * 1000L
            val allTerminated = withTimeoutOrNull(gracefulMs) {
                var remaining = handlers.filter { !it.isProcessTerminated }
                while (remaining.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    delay(STOP_POLL_INTERVAL_MS)
                    remaining = remaining.filter { !it.isProcessTerminated }
                }
                true
            } ?: false

            if (!allTerminated) {
                if (!forceOnTimeout) {
                    return ToolResult(
                        "STOP_FAILED: Process '${configName}' is still running after ${gracefulTimeoutSec}s " +
                            "(force_on_timeout=false).",
                        "STOP_FAILED", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }

                // Force kill
                for (handler in handlers.filter { !it.isProcessTerminated }) {
                    try {
                        @Suppress("DEPRECATION")
                        handler.destroyProcess()
                    } catch (_: Exception) {}
                }

                withTimeoutOrNull(FORCE_KILL_TIMEOUT_MS) {
                    var remaining = handlers.filter { !it.isProcessTerminated }
                    while (remaining.isNotEmpty()) {
                        coroutineContext.ensureActive()
                        delay(STOP_POLL_INTERVAL_MS)
                        remaining = remaining.filter { !it.isProcessTerminated }
                    }
                    true
                }

                val stillAlive = handlers.filter { !it.isProcessTerminated }
                if (stillAlive.isNotEmpty()) {
                    return ToolResult(
                        "STOP_FAILED: Process '${configName}' could not be force-killed after ${gracefulTimeoutSec}s. " +
                            "${stillAlive.size} process(es) still running.",
                        "STOP_FAILED", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }
            }

            val count = handlers.size
            val content = "Stopped $count process(es) matching '$configName'."
            return ToolResult(content, "Stopped $count process(es)", content.length / 4)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult(
                "UNEXPECTED_ERROR stopping '$configName': ${e.message}",
                "UNEXPECTED_ERROR", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } finally {
            Disposer.dispose(invocation)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Port discovery helper
    // ══════════════════════════════════════════════════════════════════════

    // M2 fix: all Runtime.exec() + readLines() calls are blocking I/O — must run on
    // Dispatchers.IO. Function made suspend; callers are already in a suspend context.
    // S2 fix: on Linux, fall back to `ss -tlnp` when lsof returns nothing (Alpine-based
    // containers lack lsof). macOS does not have `ss`, so the fallback is Linux-only.
    private suspend fun discoverListeningPorts(pid: Long): Set<Int> = withContext(Dispatchers.IO) {
        val ports = mutableSetOf<Int>()
        val os = System.getProperty("os.name", "").lowercase()
        try {
            when {
                os.contains("mac") -> {
                    // macOS: lsof only (ss unavailable)
                    val lines = Runtime.getRuntime()
                        .exec(arrayOf("lsof", "-iTCP", "-sTCP:LISTEN", "-P", "-n", "-p", pid.toString()))
                        .inputStream.bufferedReader().readLines()
                    parseLsofPorts(lines, ports)
                }
                os.contains("linux") -> {
                    // Linux: try lsof first; fall back to ss -tlnp when lsof is absent (Alpine)
                    val lsofLines = try {
                        Runtime.getRuntime()
                            .exec(arrayOf("lsof", "-iTCP", "-sTCP:LISTEN", "-P", "-n", "-p", pid.toString()))
                            .inputStream.bufferedReader().readLines()
                    } catch (_: Exception) { emptyList() }

                    if (lsofLines.size > 1) {
                        parseLsofPorts(lsofLines, ports)
                    } else {
                        // S2: lsof absent or returned no rows — try ss -tlnp and grep by pid.
                        // ss output format: LISTEN 0 128 *:8080 *:*  users:(("java",pid=12345,fd=7))
                        // We filter lines that contain "pid=<pid>" so we only pick up ports
                        // owned by the target process.
                        val ssLines = try {
                            Runtime.getRuntime()
                                .exec(arrayOf("ss", "-tlnp"))
                                .inputStream.bufferedReader().readLines()
                        } catch (_: Exception) { emptyList() }
                        for (line in ssLines) {
                            if (!line.contains("pid=$pid")) continue
                            // Column 4 (0-based index 3) holds the local address: *:PORT or ADDR:PORT
                            val cols = line.trim().split(Regex("""\s+"""))
                            val localAddr = cols.getOrNull(3) ?: continue
                            val portStr = localAddr.substringAfterLast(':')
                            val port = portStr.toIntOrNull() ?: continue
                            if (port in 1..65535) ports.add(port)
                        }
                    }
                }
                os.contains("win") -> {
                    val lines = Runtime.getRuntime()
                        .exec(arrayOf("cmd", "/c", "netstat -ano | findstr LISTENING | findstr $pid"))
                        .inputStream.bufferedReader().readLines()
                    for (line in lines) {
                        val name = line.trim().split(Regex("""\s+""")).lastOrNull() ?: continue
                        val portStr = name.substringAfterLast(':')
                        val port = portStr.toIntOrNull() ?: continue
                        if (port in 1..65535) ports.add(port)
                    }
                }
            }
        } catch (_: Exception) {}
        ports
    }

    /** Parse lsof TCP-LISTEN output (skip header row, extract last column port). */
    private fun parseLsofPorts(lines: List<String>, ports: MutableSet<Int>) {
        for (line in lines.drop(1)) {
            val name = line.trim().split(Regex("""\s+""")).lastOrNull() ?: continue
            val portStr = name.substringAfterLast(':')
            val port = portStr.toIntOrNull() ?: continue
            if (port in 1..65535) ports.add(port)
        }
    }

    internal data class DescriptorSelection(
        val descriptor: RunContentDescriptor,
        val others: List<RunContentDescriptor>,
        val pickedLive: Boolean,
    )

    /**
     * Resolve a run/debug descriptor by name, preferring a live one over a terminated one.
     *
     * `RunContentManager.allDescriptors` retains closed tabs (terminated processes stay as
     * inert descriptors until the user closes the tab). A plain `find` returns the first
     * match in registration order, which is almost always an older terminated run — not the
     * live debug session the caller actually wants. This helper:
     *   1. collects all descriptors whose display name contains [configName] (case-insensitive);
     *   2. prefers the most recently registered *live* descriptor (not terminated / terminating);
     *   3. falls back to the most recently registered descriptor if none are live.
     *
     * The "others" list in the result lets callers surface disambiguation context to the LLM.
     */
    internal fun selectDescriptorByName(
        allDescriptors: List<RunContentDescriptor>,
        configName: String,
    ): DescriptorSelection? {
        val matches = allDescriptors.filter { desc ->
            desc.displayName?.contains(configName, ignoreCase = true) == true
        }
        if (matches.isEmpty()) return null

        val live = matches.lastOrNull { desc ->
            desc.processHandler?.let { !it.isProcessTerminated && !it.isProcessTerminating } == true
        }
        val chosen = live ?: matches.last()
        return DescriptorSelection(
            descriptor = chosen,
            others = matches.filter { it !== chosen },
            pickedLive = live != null,
        )
    }

    private fun describeProcessStatus(descriptor: RunContentDescriptor): String = when {
        descriptor.processHandler?.isProcessTerminated == true -> "Terminated"
        descriptor.processHandler?.isProcessTerminating == true -> "Terminating"
        else -> "Running"
    }

    /**
     * Best-effort detection of whether the descriptor was launched in Debug mode.
     *
     * A descriptor doesn't carry its executor directly; instead we check whether any active
     * `XDebugSession` has the same `RunContentDescriptor` reference. If so, it's Debug;
     * otherwise it's treated as Run. (Coverage uses the Run executor, so it'll show as "Run".)
     */
    private fun describeLaunchMode(descriptor: RunContentDescriptor, project: Project): String {
        return try {
            val isDebug = XDebuggerManager.getInstance(project).debugSessions.any { session ->
                session.runContentDescriptor === descriptor
            }
            if (isDebug) "Debug" else "Run"
        } catch (_: Exception) {
            "Run"
        }
    }

    companion object {
        // get_run_output constants
        private const val RUN_OUTPUT_DEFAULT_LINES = 200
        private const val RUN_OUTPUT_MAX_LINES = 1000
        // Console unwrap depth
        private const val MAX_UNWRAP_DEPTH = 5

        // get_test_results constants
        private const val MAX_PROCESS_WAIT_SECONDS = 600
        private const val TEST_TREE_FINALIZE_TIMEOUT_MS = 10_000L
        private const val PROGRESS_INTERVAL_MS = 10_000L

        // run_config readiness detection constants
        private const val IDLE_STDOUT_INITIAL_WAIT_MS = 300L
        private const val IDLE_STDOUT_POLL_MS = 200L

        // http_probe constants
        private const val HTTP_PROBE_GRACE_MS = 5000L
        private const val LOG_PATTERN_POLL_MS = 50L
        private const val READY_RACE_POLL_MS = 50L

        // OS port discovery poll interval when waiting for port to bind before HTTP probe
        private const val OS_PROBE_POLL_MS = 200L

        // stop_run_config / stopProcessesForConfig constants
        private const val STOP_POLL_INTERVAL_MS = 500L
        private const val FORCE_KILL_TIMEOUT_MS = 5_000L
    }
}
