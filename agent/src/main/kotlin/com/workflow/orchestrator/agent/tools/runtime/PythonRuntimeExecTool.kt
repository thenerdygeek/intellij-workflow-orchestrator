package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.tools.framework.build.executePytestRun
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Python-specific runtime execution — runs pytest tests and compiles Python modules
 * via `python -m py_compile`. Registered only when the Python plugin is present
 * (see ToolRegistrationFilter.shouldRegisterPythonBuildTools).
 *
 * Action names (run_tests, compile_module) intentionally match JavaRuntimeExecTool
 * so the LLM's mental model stays simple: "to run tests, call the runtime_exec tool
 * my IDE provides." `run_tests` reuses [executePytestRun] from the pytest actions
 * module and translates the shared param names (class_name -> pytest path, method ->
 * pytest -k pattern).
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
    // Action: run_tests (delegates to PytestActions.executePytestRun)
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeRunTests(params: JsonObject, project: Project): ToolResult {
        // Timeout is accepted for parity with JavaRuntimeExecTool but executePytestRun
        // internally uses its own RUN_TIMEOUT_SECONDS. Clamp here so we surface clearly
        // invalid values rather than silently ignoring them.
        val rawTimeout = params["timeout"]?.jsonPrimitive?.intOrNull?.toLong()
        val timeoutSeconds = rawTimeout?.coerceIn(1, RUN_TESTS_MAX_TIMEOUT)

        // Translate agent-facing params (class_name, method) to pytest-facing params
        // (path, pattern). markers pass through untouched.
        val pytestParams = buildJsonObject {
            params["class_name"]?.jsonPrimitive?.content?.let { put("path", JsonPrimitive(it)) }
            params["method"]?.jsonPrimitive?.content?.let { put("pattern", JsonPrimitive(it)) }
            params["markers"]?.jsonPrimitive?.content?.let { put("markers", JsonPrimitive(it)) }
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
        val moduleName = params["module"]?.jsonPrimitive?.content

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
