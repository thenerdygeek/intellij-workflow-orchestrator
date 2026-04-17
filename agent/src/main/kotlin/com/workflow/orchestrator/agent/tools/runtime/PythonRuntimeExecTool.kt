package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.tools.framework.build.executePytestRun
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Python-specific runtime execution — runs pytest tests and compiles Python modules
 * via `python -m py_compile`. Registered only when the Python plugin is present
 * (see ToolRegistrationFilter.shouldRegisterPythonBuildTools).
 *
 * Action names (run_tests, compile_module) intentionally match JavaRuntimeExecTool
 * so the LLM's mental model stays simple: "to run tests, call the runtime_exec tool
 * my IDE provides."
 *
 * `run_tests` uses the IntelliJ [PytestNativeLauncher] → [PyTestConfigurationType]
 * execution pipeline when available (same RunInvocation/disposal pattern as
 * JavaRuntimeExecTool), and falls back to the shell-based [executePytestRun] when
 * PyTestConfigurationType is not registered (e.g. Python plugin absent or an older
 * edition).
 *
 * `compile_module` reuses `python -m py_compile` unchanged.
 */
class PythonRuntimeExecTool : AgentTool {

    override val name = "python_runtime_exec"

    override val description = """
Python runtime execution — pytest test running and module byte-compilation.

Actions and their parameters:
- run_tests(class_name?, method?, markers?, timeout?) → Run pytest tests.
    * class_name → pytest path or node id (e.g. `tests/test_api.py` or `tests/test_api.py::test_login`). Optional; omit to run all tests.
    * method → pytest -k pattern (keyword expression, e.g. `login and not flaky`). Not a Python method name.
    * markers → pytest -m expression (marker expression, e.g. `slow or integration`).
    * timeout default 300s, max 900s.
- compile_module(module?) → Byte-compile Python sources via `python -m py_compile`. `module` is a directory relative to project root; if omitted, compiles the entire project base path. Reports SyntaxError / compile errors via exit code and stderr.

description optional: shown to user in approval dialog on run_tests, compile_module.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("run_tests", "compile_module")
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Pytest path or node id (e.g. `tests/test_foo.py` or `tests/test_foo.py::test_bar`) — for run_tests. Optional."
            ),
            "method" to ParameterProperty(
                type = "string",
                description = "Pytest -k pattern (keyword expression, not a Python method name) — for run_tests"
            ),
            "markers" to ParameterProperty(
                type = "string",
                description = "Pytest -m expression (marker expression) — for run_tests"
            ),
            "timeout" to ParameterProperty(
                type = "integer",
                description = "Seconds before test process is killed (default: 300, max: 900) — for run_tests"
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Directory path relative to project root for compile_module (defaults to project root if omitted)"
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
            "run_tests" -> executeRunTests(params, project)
            "compile_module" -> executeCompileModule(params, project)
            else -> ToolResult(
                content = "Unknown action '$action' in python_runtime_exec. Valid actions: run_tests, compile_module",
                summary = "Unknown action '$action' in python_runtime_exec",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: run_tests
    //   1. Attempt native PyTestConfigurationType runner (via PytestNativeLauncher).
    //   2. Fall back to shell-based executePytestRun when native runner unavailable.
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeRunTests(params: JsonObject, project: Project): ToolResult {
        val rawTimeout = params["timeout"]?.jsonPrimitive?.intOrNull?.toLong()
        val timeoutSeconds = rawTimeout?.coerceIn(1, RUN_TESTS_MAX_TIMEOUT) ?: RUN_TESTS_DEFAULT_TIMEOUT

        // Resolve AgentService — may be null in unit-test context where the platform
        // service registry isn't initialised. Use getService (nullable) rather than
        // service() (throws) so tests can reach the shell fallback cleanly.
        val agentService: AgentService? = try {
            project.getService(AgentService::class.java)
        } catch (_: Exception) {
            null
        }

        // Check whether PyTestConfigurationType is registered AND whether the
        // ideContext has been populated (lateinit — may not be ready in tests).
        val hasPyTestConfigType: Boolean = try {
            agentService?.ideContext?.hasPyTestConfigType == true
        } catch (_: UninitializedPropertyAccessException) {
            false
        }

        if (agentService == null || !hasPyTestConfigType) {
            return executePytestShellFallback(params, project)
        }

        val launcher = PytestNativeLauncher(project)
        val settings = launcher.createSettings(
            pytestPath = params["class_name"]?.jsonPrimitive?.contentOrNull,
            keywordExpr = params["method"]?.jsonPrimitive?.contentOrNull,
            markerExpr = params["markers"]?.jsonPrimitive?.contentOrNull,
        ) ?: return executePytestShellFallback(params, project)

        return runPytestNative(settings, agentService, project, timeoutSeconds)
    }

    /**
     * Launch pytest via [PyTestConfigurationType] using the same RunInvocation /
     * disposal pattern as [JavaRuntimeExecTool.executeWithNativeRunner].
     *
     * Notable difference from the Java path: **no ProjectTaskManager build phase**.
     * pytest discovers and imports tests at run time; there is no ahead-of-time
     * compilation step required (or available) via IntelliJ's compiler API for Python.
     *
     * TODO: When Phase 2 BuildSystemValidator has a pytest equivalent (check interpreter
     *   has pytest installed, target file/dir exists), call it here before dispatch.
     */
    private suspend fun runPytestNative(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        agentService: AgentService,
        project: Project,
        timeoutSeconds: Long,
    ): ToolResult {
        coroutineContext.ensureActive()
        val invocation = agentService.newRunInvocation("pytest-${System.currentTimeMillis()}")
        try {
            val result = withTimeoutOrNull(timeoutSeconds * 1000) {
                suspendCancellableCoroutine { continuation ->
                    // Single cleanup path: dispose the invocation on cancellation.
                    // This destroys the process handler, disconnects MessageBusConnections,
                    // removes the RunContentDescriptor, and fires all onDispose callbacks.
                    continuation.invokeOnCancellation {
                        Disposer.dispose(invocation)
                    }

                    ApplicationManager.getApplication().invokeLater {
                        try {
                            val executor = DefaultRunExecutor.getRunExecutorInstance()
                            val env = ExecutionEnvironmentBuilder
                                .createOrNull(executor, settings)
                                ?.build()

                            if (env == null) {
                                if (continuation.isActive) continuation.resume(
                                    ToolResult(
                                        "Native pytest runner: ExecutionEnvironmentBuilder returned null. " +
                                            "Possible causes: no ProgramRunner registered for PyTestConfigurationType.",
                                        "Native runner unavailable", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                                    )
                                )
                                return@invokeLater
                            }

                            // Subscribe to processNotStarted BEFORE launching so we catch
                            // abort events from the execution framework (no ProgramRunner,
                            // executor disabled, interpreter resolution failure, etc.).
                            val runConnection = project.messageBus.connect()
                            invocation.subscribeTopic(runConnection)
                            runConnection.subscribe(
                                com.intellij.execution.ExecutionManager.EXECUTION_TOPIC,
                                object : com.intellij.execution.ExecutionListener {
                                    override fun processNotStarted(
                                        executorId: String,
                                        e: com.intellij.execution.runners.ExecutionEnvironment,
                                    ) {
                                        if (e == env && continuation.isActive) {
                                            continuation.resume(
                                                ToolResult(
                                                    "pytest execution did not start. Possible causes: no runner registered " +
                                                        "for PyTestConfigurationType, or interpreter not configured.",
                                                    "pytest run aborted", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                                                )
                                            )
                                        }
                                    }
                                }
                            )

                            val callback = object : ProgramRunner.Callback {
                                override fun processStarted(descriptor: RunContentDescriptor?) {
                                    if (descriptor == null) {
                                        if (continuation.isActive) continuation.resume(
                                            ToolResult(
                                                "pytest runner produced no RunContentDescriptor.",
                                                "No descriptor", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                                            )
                                        )
                                        return
                                    }

                                    invocation.descriptorRef.set(descriptor)
                                    invocation.processHandlerRef.set(descriptor.processHandler)

                                    // Register removeRunContent as an onDispose block so the
                                    // TestResultsViewer (and its EventsListener) are released
                                    // when the invocation disposes. The literal `removeRunContent`
                                    // call lives here in the tool file so source-text tests can anchor on it.
                                    invocation.onDispose {
                                        val desc = invocation.descriptorRef.get() ?: return@onDispose
                                        val runExecutor = DefaultRunExecutor.getRunExecutorInstance()
                                        ApplicationManager.getApplication().invokeLater {
                                            com.intellij.execution.ui.RunContentManager.getInstance(project)
                                                .removeRunContent(runExecutor, desc)
                                        }
                                    }

                                    val testConsole = TestConsoleUtils.unwrapToTestConsole(descriptor.executionConsole)
                                    if (testConsole != null) {
                                        val resultsViewer = testConsole.resultsViewer
                                        val eventsListener = object : TestResultsViewer.EventsListener {
                                            override fun onTestingFinished(sender: TestResultsViewer) {
                                                val root = sender.testsRootNode as? SMTestProxy.SMRootTestProxy
                                                if (root != null && continuation.isActive) {
                                                    continuation.resume(
                                                        interpretTestRoot(root, descriptor.displayName ?: "pytest")
                                                    )
                                                }
                                            }
                                        }
                                        invocation.attachListener(eventsListener, resultsViewer)
                                    } else {
                                        // Fallback: no test console — wait for process termination,
                                        // then extract whatever the test tree holds.
                                        val handler = descriptor.processHandler
                                        if (handler != null) {
                                            val terminationListener = object : com.intellij.execution.process.ProcessAdapter() {
                                                override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                                                    if (continuation.isActive) {
                                                        val root = TestConsoleUtils.findTestRoot(descriptor)
                                                        continuation.resume(
                                                            if (root != null) {
                                                                interpretTestRoot(root, descriptor.displayName ?: "pytest")
                                                            } else {
                                                                ToolResult(
                                                                    "pytest run completed but no test results available.",
                                                                    "No structured results", 20
                                                                )
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            invocation.attachProcessListener(handler, terminationListener)
                                        } else {
                                            if (continuation.isActive) continuation.resume(null)
                                        }
                                    }
                                }
                            }

                            if (!continuation.isActive) return@invokeLater
                            try {
                                ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
                            } catch (_: NoSuchMethodError) {
                                env.callback = callback
                                ProgramRunnerUtil.executeConfiguration(env, false, true)
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resume(
                                ToolResult(
                                    "pytest native launch threw ${e.javaClass.simpleName}: ${e.message}",
                                    "Launch error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                                )
                            )
                        }
                    }
                }
            }

            if (result == null) {
                // Timeout — capture refs immediately before invokeOnCancellation can dispose them.
                val capturedHandler = invocation.processHandlerRef.get()
                val capturedDescriptor = invocation.descriptorRef.get()
                capturedHandler?.destroyProcess()
                val partial = capturedDescriptor?.let {
                    val root = TestConsoleUtils.findTestRoot(it)
                    root?.let { r -> interpretTestRoot(r, "pytest") }
                }
                return if (partial != null) {
                    partial.copy(
                        content = "[TIMEOUT] pytest timed out after ${timeoutSeconds}s. Partial results:\n\n${partial.content}",
                        isError = true
                    )
                } else {
                    ToolResult(
                        "[TIMEOUT] pytest timed out after ${timeoutSeconds}s. No results captured.",
                        "pytest timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }
            }
            return result
        } finally {
            Disposer.dispose(invocation)
        }
    }

    /**
     * Shell fallback: translate agent-facing params (class_name, method) to the
     * pytest-facing params (path, pattern) that [executePytestRun] expects.
     */
    private suspend fun executePytestShellFallback(params: JsonObject, project: Project): ToolResult {
        val rawTimeout = params["timeout"]?.jsonPrimitive?.intOrNull?.toLong()
        val timeoutSeconds = rawTimeout?.coerceIn(1, RUN_TESTS_MAX_TIMEOUT)

        val pytestParams = buildJsonObject {
            params["class_name"]?.jsonPrimitive?.contentOrNull?.let { put("path", JsonPrimitive(it)) }
            params["method"]?.jsonPrimitive?.contentOrNull?.let { put("pattern", JsonPrimitive(it)) }
            params["markers"]?.jsonPrimitive?.contentOrNull?.let { put("markers", JsonPrimitive(it)) }
            if (timeoutSeconds != null) {
                put("timeout", JsonPrimitive(timeoutSeconds))
            }
        }
        return executePytestRun(pytestParams, project)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: compile_module (python -m py_compile)
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeCompileModule(params: JsonObject, project: Project): ToolResult {
        val moduleName = params["module"]?.jsonPrimitive?.contentOrNull

        return withContext(Dispatchers.IO) {
            val basePath = project.basePath
                ?: return@withContext ToolResult(
                    "Error: no project base path available",
                    "Error: no project",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            val baseDir = File(basePath)
            val targetDir = if (moduleName != null) {
                val resolved = File(baseDir, moduleName).canonicalFile
                if (!resolved.canonicalPath.startsWith(baseDir.canonicalPath)) {
                    return@withContext ToolResult(
                        "Error: module path '$moduleName' resolves outside the project directory.",
                        "Error: invalid module path",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                if (!resolved.exists()) {
                    return@withContext ToolResult(
                        "Error: module path '$moduleName' does not exist.",
                        "Module not found",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                resolved
            } else {
                baseDir
            }

            val pyFiles = if (targetDir.isDirectory) {
                targetDir.walkTopDown()
                    .onEnter { dir -> !dir.name.startsWith(".") && dir.name !in EXCLUDED_DIRS }
                    .filter { it.isFile && it.extension == "py" }
                    .toList()
            } else if (targetDir.isFile && targetDir.extension == "py") {
                listOf(targetDir)
            } else {
                return@withContext ToolResult(
                    "Error: module path '${moduleName ?: basePath}' contains no .py files.",
                    "No Python files",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            if (pyFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No Python files found under ${targetDir.relativeToOrSelf(baseDir)}.",
                    "No Python files",
                    10
                )
            }

            val target = moduleName ?: "project"
            val output = runPyCompile(pyFiles, baseDir) ?: return@withContext ToolResult(
                "Error: no Python interpreter found on PATH (tried python3, python). " +
                    "Install Python or ensure it is on PATH.",
                "Python not found",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

            val truncated = truncateOutput(output.stdErr, RUN_TESTS_MAX_OUTPUT_CHARS)
            if (output.exitCode == 0 && !truncated.contains("SyntaxError")) {
                ToolResult(
                    "Compilation of $target successful: ${pyFiles.size} file(s) byte-compiled, 0 errors.",
                    "Build OK",
                    20
                )
            } else {
                val content = "Compilation of $target failed (exit code ${output.exitCode}):\n\n$truncated"
                ToolResult(
                    content,
                    "py_compile errors",
                    TokenEstimator.estimate(content),
                    isError = true
                )
            }
        }
    }

    private data class PyCompileOutput(val exitCode: Int, val stdErr: String)

    private fun runPyCompile(pyFiles: List<File>, baseDir: File): PyCompileOutput? {
        val interpreters = listOf("python3", "python")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        val relPaths = pyFiles.map { it.relativeTo(baseDir).path }

        for (interpreter in interpreters) {
            try {
                val cmd = mutableListOf<String>()
                if (isWindows) {
                    cmd.add("cmd.exe")
                    cmd.add("/c")
                }
                cmd.add(interpreter)
                cmd.add("-m")
                cmd.add("py_compile")
                cmd.addAll(relPaths)

                val pb = ProcessBuilder(cmd)
                    .directory(baseDir)
                    .redirectErrorStream(true)

                val process = pb.start()
                val toolCallId = RunCommandTool.currentToolCallId.get()
                val streamCallback = RunCommandTool.streamCallback

                val outFuture = CompletableFuture.supplyAsync {
                    val sb = StringBuilder()
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            var line = reader.readLine()
                            while (line != null) {
                                sb.appendLine(line)
                                if (toolCallId != null) {
                                    streamCallback?.invoke(toolCallId, line + "\n")
                                }
                                line = reader.readLine()
                            }
                        }
                    } catch (_: Exception) { }
                    sb.toString()
                }

                val completed = process.waitFor(PY_COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    return PyCompileOutput(-1, "py_compile timed out after ${PY_COMPILE_TIMEOUT_SECONDS}s.")
                }

                val output = try {
                    outFuture.get(5, TimeUnit.SECONDS)
                } catch (_: Exception) { "" }

                return PyCompileOutput(process.exitValue(), output)
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    companion object {
        private const val PY_COMPILE_TIMEOUT_SECONDS = 120L
        private val EXCLUDED_DIRS = setOf(
            "__pycache__", "venv", ".venv", "env", ".env", "node_modules",
            ".git", ".tox", ".mypy_cache", ".pytest_cache", "dist", "build",
            ".eggs", "site-packages"
        )
    }
}
