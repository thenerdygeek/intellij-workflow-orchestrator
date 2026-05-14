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
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.tools.framework.build.executePytestRun
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
    // run_tests manages its own pytest process timeout (up to RUN_TESTS_MAX_TIMEOUT = 900 s).
    override val timeoutMs: Long get() = Long.MAX_VALUE

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

    // TODO: rerun_failed_tests for pytest — delegate via pytest --lf flag. See docs/research/2026-04-21-followups-research.md Section C2.
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
                description = "Pytest -k pattern (keyword expression, not a Python method name) — for run_tests. " +
                    "Single method: 'test_foo'. Multiple methods in one launch (pytest -k boolean): " +
                    "'test_foo or test_bar or test_baz'. Also supports exclusion: 'test_foo and not slow'. " +
                    "Pytest runs everything matched in a single process and returns an aggregated result."
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

    override fun documentation(): ToolDocumentation = toolDoc("python_runtime_exec") {
        summary {
            technical("Python-specific runtime execution with two actions: run_tests dispatches pytest via PytestNativeLauncher → PyTestConfigurationType (when available) with shell-based executePytestRun as fallback; compile_module byte-compiles .py files under the project root via `python -m py_compile`. Registered only when the Python plugin is present (ToolRegistrationFilter.shouldRegisterPythonBuildTools).")
            plain("The Python counterpart to java_runtime_exec — runs pytest tests and syntax-checks Python sources. Mirrors java_runtime_exec's action names so the LLM picks the right tool for the IDE without mental gymnastics.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.PROCESS_SPAWN)
        verdict {
            keep(
                "Irreplaceable on Python projects: integrates pytest with IntelliJ's TestResultsViewer so the agent " +
                    "and the user see pass/fail counts in the IDE's standard test panel — not buried in shell stdout. " +
                    "compile_module provides a fast, interpreter-portable syntax sweep that catches SyntaxErrors " +
                    "before slow test invocations. Registered only when the Python plugin is present, so it never " +
                    "pollutes Java-only IDEs.",
                VerdictSeverity.STRONG,
            )
        }
        counterfactual(
            "Without this tool, the LLM falls back to run_command with `pytest` — which works, but loses the " +
                "native PyTestConfigurationType integration (no TestResultsViewer, no IDE test history, no " +
                "per-test re-run button). compile_module has no run_command equivalent; the LLM would have to " +
                "synthesise `python -m py_compile` itself, walk the file tree manually, and handle interpreter " +
                "resolution — fragile and verbose."
        )
        llmMistake(
            "Using a comma separator in `method` (e.g. `test_foo,test_bar`) as if it were java_runtime_exec. " +
                "The `method` param is a pytest -k keyword expression, not a comma-separated list. " +
                "Correct: `test_foo or test_bar`. The comma form is silently passed as a literal -k expression " +
                "and pytest will match nothing (or mis-match) without raising an obvious error."
        )
        llmMistake(
            "Passing a bare Python method name (e.g. `test_login`) to `class_name` instead of a pytest path " +
                "(e.g. `tests/test_api.py::test_login`). `class_name` is a pytest node id or file path, not a " +
                "Python qualified name. Using a method name there causes pytest to interpret it as a file path " +
                "and fail with 'file not found'."
        )
        llmMistake(
            "Passing a markers expression to `method` or a keyword expression to `markers`. " +
                "`method` feeds pytest `-k`; `markers` feeds pytest `-m`. Swapping them silently changes " +
                "which tests run (or skips all tests if the wrong expression is used with the wrong flag)."
        )
        llmMistake(
            "Calling run_tests before the Python plugin is confirmed present, or on an IntelliJ IDEA project " +
                "with no Python facet. The tool is not registered in that environment and will return an " +
                "unknown-tool error. Use tool_search to verify `python_runtime_exec` is available before calling."
        )
        downside(
            "pytest output reconciliation is heuristic: verbose PASSED/FAILED/ERROR counts are reconciled " +
                "against the summary line, and a warning is emitted when the suite reports passes with " +
                "near-zero duration and no stdout (a common sign of a mis-configured test collection or " +
                "import error). The heuristic may misfire on heavily parametrised suites."
        )
        downside(
            "compile_module uses the first interpreter found on PATH (python3 → python) and does not consult " +
                "IntelliJ's configured Python SDK. If the project's venv interpreter differs from PATH, " +
                "py_compile may succeed against a different standard-library version than the IDE expects, " +
                "masking f-string or walrus-operator compatibility issues."
        )
        downside(
            "run_tests falls back to the shell-based executePytestRun silently when PyTestConfigurationType is " +
                "not registered (e.g. IntelliJ IDEA Community with Python plugin but no PyCharm). The fallback " +
                "does not integrate with the TestResultsViewer, so IDE test history is not updated."
        )
        downside(
            "No `rerun_failed_tests` action (unlike java_runtime_exec). The LLM must re-invoke run_tests " +
                "with the failed test node ids or a -k expression. A pytest --lf-based rerun action is " +
                "tracked in TODO at the top of the tool file."
        )
        observation(
            "The `method` param name mirrors java_runtime_exec (where it means a Java method name), but its " +
                "semantics are entirely different here (a pytest -k keyword expression). This naming " +
                "inconsistency is the root cause of the most frequent LLM mistake with this tool. A future " +
                "rename to `keyword` or `filter` would reduce confusion, but would break any persisted " +
                "prompts that already use `method`."
        )
        actions {
            action("run_tests") {
                description {
                    technical("Runs pytest. Prefers IntelliJ's PyTestConfigurationType native runner (integrates with the test results pane) and falls back to shell-based executePytestRun when the native config type is unregistered or the AgentService is unavailable. Waits for smart mode (indexing) before dispatch; returns DUMB_MODE if indexing doesn't finish within 60s.")
                    plain("Runs your pytest tests — either the whole suite, a file/node, or a -k keyword expression to slice across the suite.")
                }
                whenLLMUses("To run pytest tests after editing Python sources or to verify a fix against a specific test or marker.")
                params {
                    optional("class_name", "string") {
                        llmSeesIt("Pytest path or node id (e.g. `tests/test_foo.py` or `tests/test_foo.py::test_bar`) — for run_tests. Optional.")
                        humanReadable("Path or node id pytest should run. Omit to run all tests.")
                        whenPresent("Passed to pytest as the test target (file path or path::nodeid).")
                        whenAbsent("Pytest runs the full discovered test suite.")
                        example("tests/test_api.py")
                        example("tests/test_api.py::test_login")
                    }
                    optional("method", "string") {
                        llmSeesIt("Pytest -k pattern (keyword expression, not a Python method name) — for run_tests. Single method: 'test_foo'. Multiple methods in one launch (pytest -k boolean): 'test_foo or test_bar or test_baz'. Also supports exclusion: 'test_foo and not slow'. Pytest runs everything matched in a single process and returns an aggregated result.")
                        humanReadable("Pytest -k keyword expression. Boolean operators (and, or, not) compose multiple tests into one launch.")
                        whenPresent("Forwarded to pytest as `-k <expression>`; matching tests run in a single process with aggregated results.")
                        whenAbsent("No keyword filter is applied.")
                        example("test_login")
                        example("test_foo or test_bar")
                    }
                    optional("markers", "string") {
                        llmSeesIt("Pytest -m expression (marker expression) — for run_tests")
                        humanReadable("Pytest -m marker expression to select tests by their @pytest.mark.* tags.")
                        whenPresent("Forwarded to pytest as `-m <expression>`.")
                        whenAbsent("No marker filter is applied.")
                        example("slow or integration")
                    }
                    optional("timeout", "integer") {
                        llmSeesIt("Seconds before test process is killed (default: 300, max: 900) — for run_tests")
                        humanReadable("How long to let pytest run before killing the process.")
                        whenPresent("Caps the test run at this many seconds; on timeout, partial test results (if any) are returned with isError=true.")
                        whenAbsent("Defaults to 300s.")
                        constraint("clamped to [1, 900]")
                        example("600")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description of what this action does and why (shown to user in approval dialog) — for run_tests, compile_module")
                        humanReadable("Short explanation surfaced to the user in the approval dialog.")
                        whenPresent("Displayed verbatim in the approval gate.")
                        whenAbsent("Approval dialog shows only the action and parameters.")
                        example("Run login regression tests after auth refactor")
                    }
                }
                onSuccess("Returns aggregated pytest results (pass/fail/error/skip counts plus per-test detail) via interpretTestRoot. Empty suites return NO_TESTS_FOUND; failed runs propagate isError=true.")
                onFailure("indexing didn't finish in 60s", "Returns DUMB_MODE — retry shortly.")
                onFailure("timeout", "Returns '[TIMEOUT] pytest timed out after Ns' with partial results when available, isError=true.")
                onFailure("native runner unavailable", "Falls back transparently to the shell-based executePytestRun.")
            }
            action("compile_module") {
                description {
                    technical("Walks the target directory (or project base path), filters .py files (excluding venv/.venv/__pycache__/.git/etc.), and invokes `python -m py_compile` via the first available interpreter on PATH (python3, then python). Output >30K is auto-spilled to disk via ToolOutputSpiller.")
                    plain("Byte-compiles every .py file under a directory to catch SyntaxError without running the code — like a fast syntax sweep before committing.")
                }
                whenLLMUses("After bulk edits to Python sources, to check for syntax errors before invoking tests or running the app.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Directory path relative to project root for compile_module (defaults to project root if omitted)")
                        humanReadable("Directory (relative to project root) to byte-compile. Omit to compile the entire project.")
                        whenPresent("Resolved against the project base path and validated to stay inside it; all .py files under that directory are compiled.")
                        whenAbsent("Compiles every .py file under the project base path.")
                        constraint("must resolve inside the project base path")
                        example("src/app")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description of what this action does and why (shown to user in approval dialog) — for run_tests, compile_module")
                        humanReadable("Short explanation surfaced to the user in the approval dialog.")
                        whenPresent("Displayed verbatim in the approval gate.")
                        whenAbsent("Approval dialog shows only the action and parameters.")
                        example("Syntax-check api module after import refactor")
                    }
                }
                onSuccess("Returns 'Compilation of <target> successful: N file(s) byte-compiled, 0 errors.' when exit code is 0 and no SyntaxError appears in stderr.")
                onFailure("syntax / compile error", "Returns 'Compilation of <target> failed (exit code N):' followed by py_compile stderr, isError=true.")
                onFailure("path escapes project", "Returns 'Error: module path \"<m>\" resolves outside the project directory.' with isError=true.")
                onFailure("path missing", "Returns 'Error: module path \"<m>\" does not exist.' with isError=true.")
                onFailure("no .py files", "Returns 'No Python files found under <dir>.' (informational).")
                onFailure("no interpreter on PATH", "Returns 'Error: no Python interpreter found on PATH (tried python3, python).' with isError=true.")
            }
        }
        related("java_runtime_exec", Relationship.ALTERNATIVE, "Java/Kotlin counterpart — same action names (run_tests, compile_module) for the Java side. Registered only when the Java plugin is present.")
        related("runtime_exec", Relationship.COMPLEMENT, "Universal observation/launch (get_running_processes, get_run_output, get_test_results, run_config, stop_run_config). Use runtime_exec.get_test_results to re-read results from a previous pytest run.")
        related("coverage", Relationship.COMPOSE_WITH, "For coverage-instrumented runs, use coverage.run_with_coverage instead of run_tests.")
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
        // Bug 4 — Layer C: indexing barrier (consistent with java_runtime_exec / coverage).
        if (!com.workflow.orchestrator.core.vfs.waitForSmartModeOrTimeout(project)) {
            return ToolResult(
                content = "DUMB_MODE: indexing did not complete within 60s. " +
                    "A recent file mutation triggered reindexing. Retry shortly.",
                summary = "DUMB_MODE: timeout waiting for indexing",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }

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
                                                    val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                                                    invocation.onDispose { pollScope.cancel() }
                                                    pollScope.launch {
                                                        if (continuation.isActive) {
                                                            continuation.resume(
                                                                interpretTestRoot(root, descriptor.displayName ?: "pytest", this@PythonRuntimeExecTool, project)
                                                            )
                                                        }
                                                    }
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
                                                        if (root != null) {
                                                            val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                                                            invocation.onDispose { pollScope.cancel() }
                                                            pollScope.launch {
                                                                if (continuation.isActive) {
                                                                    continuation.resume(
                                                                        interpretTestRoot(root, descriptor.displayName ?: "pytest", this@PythonRuntimeExecTool, project)
                                                                    )
                                                                }
                                                            }
                                                        } else {
                                                            continuation.resume(
                                                                ToolResult(
                                                                    "pytest run completed but no test results available.",
                                                                    "No structured results", 20
                                                                )
                                                            )
                                                        }
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
                    root?.let { r -> interpretTestRoot(r, "pytest", this@PythonRuntimeExecTool, project) }
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

            val spilled = this@PythonRuntimeExecTool.spillOrFormat(output.stdErr, project)
            if (output.exitCode == 0 && !spilled.preview.contains("SyntaxError")) {
                ToolResult(
                    "Compilation of $target successful: ${pyFiles.size} file(s) byte-compiled, 0 errors.",
                    "Build OK",
                    20
                )
            } else {
                val content = "Compilation of $target failed (exit code ${output.exitCode}):\n\n${spilled.preview}"
                ToolResult(
                    content = content,
                    summary = "py_compile errors",
                    tokenEstimate = TokenEstimator.estimate(content),
                    isError = true,
                    spillPath = spilled.spilledToFile,
                )
            }
        }
    }

    private data class PyCompileOutput(val exitCode: Int, val stdErr: String)

    private fun runPyCompile(pyFiles: List<File>, baseDir: File): PyCompileOutput? {
        val interpreters = listOf("python3", "python")
        val relPaths = pyFiles.map { it.relativeTo(baseDir).path }

        for (interpreter in interpreters) {
            try {
                val cmd = com.workflow.orchestrator.agent.tools.process.PlatformCommandWrapper
                    .cmdWrap(listOf(interpreter, "-m", "py_compile") + relPaths)

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
