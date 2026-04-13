package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * pytest test framework actions for Python projects.
 *
 * Actions delegate to the `pytest` CLI for test discovery, execution,
 * and fixture listing. Falls back to `python -m pytest` if `pytest`
 * is not directly available.
 */

private const val CLI_TIMEOUT_SECONDS = 60L
private const val RUN_TIMEOUT_SECONDS = 300L

/** Safe characters for pytest -k and -m expressions (word chars, spaces, parens, boolean ops, dots, hyphens). */
private val SAFE_PYTEST_EXPR = Regex("""^[\w\s\-.,()\[\]]+$""")

/** Validate that a pytest path filter resolves within the project base directory. */
private fun validatePytestPath(path: String, basePath: String): String? {
    val canonical = File(basePath, path).canonicalPath
    if (!canonical.startsWith(File(basePath).canonicalPath)) return null
    return canonical
}

internal suspend fun executePytestDiscover(params: JsonObject, project: Project): ToolResult {
    val pathFilter = params["path"]?.jsonPrimitive?.content

    return try {
        withContext(Dispatchers.IO) {
            val basePath = project.basePath
                ?: return@withContext ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val args = mutableListOf("--collect-only", "-q", "--no-header")
            if (pathFilter != null) {
                val validatedPath = validatePytestPath(pathFilter, basePath)
                    ?: return@withContext ToolResult(
                        "Error: path '$pathFilter' resolves outside the project directory.",
                        "Error: invalid path",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                args.add(validatedPath)
            }

            val output = runPytestCommand(args, project)
                ?: return@withContext pytestNotFoundError()

            val tests = parsePytestCollectOutput(output)

            if (tests.isEmpty()) {
                val filterDesc = if (pathFilter != null) " in '$pathFilter'" else ""
                return@withContext ToolResult(
                    "No tests discovered$filterDesc.",
                    "No tests found",
                    5
                )
            }

            val byFile = tests.groupBy { it.file }

            val content = buildString {
                appendLine("Discovered pytest tests (${tests.size} total across ${byFile.size} file(s)):")
                appendLine()
                for ((file, fileTests) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (test in fileTests.sortedBy { it.name }) {
                        val paramStr = if (test.parametrized) " [parametrized]" else ""
                        appendLine("  ${test.name}$paramStr")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${tests.size} tests in ${byFile.size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error discovering tests: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

internal suspend fun executePytestRun(params: JsonObject, project: Project): ToolResult {
    val pathFilter = params["path"]?.jsonPrimitive?.content
    val pattern = params["pattern"]?.jsonPrimitive?.content
    val markers = params["markers"]?.jsonPrimitive?.content

    return try {
        withContext(Dispatchers.IO) {
            val basePath = project.basePath
                ?: return@withContext ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val args = mutableListOf("-v", "--tb=short", "--no-header")

            if (pathFilter != null) {
                val validatedPath = validatePytestPath(pathFilter, basePath)
                    ?: return@withContext ToolResult(
                        "Error: path '$pathFilter' resolves outside the project directory.",
                        "Error: invalid path",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                args.add(validatedPath)
            }
            if (pattern != null) {
                if (!SAFE_PYTEST_EXPR.matches(pattern) || pattern.contains("__")) {
                    return@withContext ToolResult(
                        "Error: pattern '$pattern' contains unsafe characters. Only word characters, spaces, dots, hyphens, parentheses, brackets, and commas are allowed.",
                        "Error: unsafe pattern",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                args.add("-k")
                args.add(pattern)
            }
            if (markers != null) {
                if (!SAFE_PYTEST_EXPR.matches(markers) || markers.contains("__")) {
                    return@withContext ToolResult(
                        "Error: markers '$markers' contains unsafe characters. Only word characters, spaces, dots, hyphens, parentheses, brackets, and commas are allowed.",
                        "Error: unsafe markers",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                args.add("-m")
                args.add(markers)
            }

            val output = runPytestCommand(args, project, timeout = RUN_TIMEOUT_SECONDS)
                ?: return@withContext pytestNotFoundError()

            val results = parsePytestRunOutput(output)

            val content = buildString {
                appendLine("pytest results:")
                appendLine()

                if (results.tests.isNotEmpty()) {
                    val passed = results.tests.count { it.status == "PASSED" }
                    val failed = results.tests.count { it.status == "FAILED" }
                    val skipped = results.tests.count { it.status == "SKIPPED" }
                    val errors = results.tests.count { it.status == "ERROR" }

                    appendLine("Summary: ${results.tests.size} total, $passed passed, $failed failed, $skipped skipped, $errors errors")
                    appendLine()

                    // Show failures first
                    val failures = results.tests.filter { it.status == "FAILED" || it.status == "ERROR" }
                    if (failures.isNotEmpty()) {
                        appendLine("FAILURES:")
                        for (test in failures) {
                            appendLine("  ${test.status} ${test.name}")
                        }
                        appendLine()
                    }

                    // Then passed
                    val passingTests = results.tests.filter { it.status == "PASSED" }
                    if (passingTests.isNotEmpty()) {
                        appendLine("PASSED:")
                        for (test in passingTests) {
                            appendLine("  ${test.name}")
                        }
                        appendLine()
                    }

                    // Show skipped
                    val skippedTests = results.tests.filter { it.status == "SKIPPED" }
                    if (skippedTests.isNotEmpty()) {
                        appendLine("SKIPPED:")
                        for (test in skippedTests) {
                            appendLine("  ${test.name}")
                        }
                        appendLine()
                    }
                }

                // Include raw summary line if available
                if (results.summaryLine.isNotBlank()) {
                    appendLine(results.summaryLine)
                }

                // Include any failure output
                if (results.failureOutput.isNotBlank()) {
                    appendLine()
                    appendLine("Failure details:")
                    append(results.failureOutput.take(5000)) // Cap failure output
                }
            }

            val passed = results.tests.count { it.status == "PASSED" }
            val failed = results.tests.count { it.status == "FAILED" }

            ToolResult(
                content = content.trimEnd(),
                summary = "${results.tests.size} tests: $passed passed, $failed failed",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error running tests: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

internal suspend fun executePytestFixtures(params: JsonObject, project: Project): ToolResult {
    val pathFilter = params["path"]?.jsonPrimitive?.content

    return try {
        withContext(Dispatchers.IO) {
            val basePath = project.basePath
                ?: return@withContext ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val args = mutableListOf("--fixtures", "-q", "--no-header")
            if (pathFilter != null) {
                val validatedPath = validatePytestPath(pathFilter, basePath)
                    ?: return@withContext ToolResult(
                        "Error: path '$pathFilter' resolves outside the project directory.",
                        "Error: invalid path",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                args.add(validatedPath)
            }

            val output = runPytestCommand(args, project)
                ?: return@withContext pytestNotFoundError()

            val fixtures = parsePytestFixtures(output)

            if (fixtures.isEmpty()) {
                return@withContext ToolResult(
                    "No fixtures found.",
                    "No fixtures",
                    5
                )
            }

            val content = buildString {
                appendLine("pytest fixtures (${fixtures.size} total):")
                appendLine()
                for (fixture in fixtures.sortedBy { it.name }) {
                    val scopeStr = if (fixture.scope != "function") " [scope: ${fixture.scope}]" else ""
                    appendLine("  ${fixture.name}$scopeStr")
                    if (fixture.docstring.isNotBlank()) {
                        appendLine("    ${fixture.docstring}")
                    }
                    if (fixture.location.isNotBlank()) {
                        appendLine("    defined in: ${fixture.location}")
                    }
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${fixtures.size} fixtures",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error listing fixtures: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

// ── Data classes ──────────────────────────────────────────────────────────

private data class DiscoveredTest(val file: String, val name: String, val parametrized: Boolean)

private data class TestRunResult(
    val tests: List<TestResult>,
    val summaryLine: String,
    val failureOutput: String
)

private data class TestResult(val name: String, val status: String)

private data class FixtureInfo(val name: String, val scope: String, val docstring: String, val location: String)

// ── CLI execution ────────────────────────────────────────────────────────

private fun runPytestCommand(args: List<String>, project: Project, timeout: Long = CLI_TIMEOUT_SECONDS): String? {
    val basePath = project.basePath ?: return null

    // Try pytest directly, then python -m pytest
    val commands = listOf(
        listOf("pytest") + args,
        listOf("python", "-m", "pytest") + args,
        listOf("python3", "-m", "pytest") + args
    )

    for (cmdArgs in commands) {
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val command = if (isWindows) {
                listOf("cmd.exe", "/c") + cmdArgs
            } else {
                cmdArgs
            }

            val process = ProcessBuilder(command)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()

            // Drain stdout concurrently to prevent pipe-buffer deadlock
            val outputFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().readText()
            }
            val completed = process.waitFor(timeout, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                continue
            }

            val output = try {
                outputFuture.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) { "" }
            // pytest returns 0 (all pass), 1 (some fail), 5 (no tests collected)
            // All are valid outputs to parse
            val exitCode = process.exitValue()
            if (exitCode in listOf(0, 1, 2, 5)) return output
        } catch (_: Exception) {
            continue
        }
    }
    return null
}

// ── Output parsers ───────────────────────────────────────────────────────

private fun parsePytestCollectOutput(output: String): List<DiscoveredTest> {
    // pytest --collect-only -q outputs lines like:
    //   tests/test_foo.py::test_bar
    //   tests/test_foo.py::TestClass::test_method
    //   tests/test_foo.py::test_parametrized[param1]
    val results = mutableListOf<DiscoveredTest>()
    for (line in output.lines()) {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("=") || trimmed.startsWith("-") ||
            trimmed.contains("warnings summary") || trimmed.contains("test selected") ||
            trimmed.contains("no tests ran") || trimmed.startsWith("<")) continue

        if ("::" in trimmed) {
            val parts = trimmed.split("::", limit = 2)
            val file = parts[0]
            val testId = parts[1]
            val parametrized = testId.contains("[")
            val name = if (parametrized) testId.substringBefore("[") else testId
            results.add(DiscoveredTest(file, name, parametrized))
        }
    }
    // Deduplicate parametrized tests (keep one entry with parametrized=true)
    return results.groupBy { "${it.file}::${it.name}" }.map { (_, group) ->
        if (group.any { it.parametrized }) {
            group.first().copy(parametrized = true)
        } else {
            group.first()
        }
    }
}

private fun parsePytestRunOutput(output: String): TestRunResult {
    val tests = mutableListOf<TestResult>()
    var summaryLine = ""
    val failureOutput = StringBuilder()
    var inFailure = false

    for (line in output.lines()) {
        val trimmed = line.trim()

        // Verbose output: tests/test_foo.py::test_bar PASSED
        val testPattern = Regex("""^(.+::\S+)\s+(PASSED|FAILED|SKIPPED|ERROR|XFAIL|XPASS)""")
        val match = testPattern.find(trimmed)
        if (match != null) {
            tests.add(TestResult(match.groupValues[1], match.groupValues[2]))
            continue
        }

        // Summary line: === N passed, M failed in X.XXs ===
        if (trimmed.startsWith("=") && (trimmed.contains("passed") || trimmed.contains("failed") || trimmed.contains("error"))) {
            summaryLine = trimmed.trim('=', ' ')
        }

        // Capture failure section
        if (trimmed.startsWith("FAILURES") || trimmed.startsWith("_ ")) {
            inFailure = true
        }
        if (inFailure && trimmed.startsWith("=") && trimmed.contains("short test summary")) {
            inFailure = false
        }
        if (inFailure) {
            failureOutput.appendLine(line)
        }
    }

    return TestRunResult(tests, summaryLine, failureOutput.toString().trimEnd())
}

private fun parsePytestFixtures(output: String): List<FixtureInfo> {
    // pytest --fixtures -q outputs:
    //   fixture_name [scope] -- docstring
    //       defined in: path/to/conftest.py
    // Or:
    //   fixture_name -- docstring
    //       path/to/conftest.py:10
    val fixtures = mutableListOf<FixtureInfo>()
    val lines = output.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i].trimEnd()

        // Skip empty, divider, and summary lines
        if (line.isBlank() || line.startsWith("=") || line.startsWith("-") ||
            line.contains("warnings summary") || line.startsWith("  ")) {
            // Check if this is an indented location line for the previous fixture
            if (line.startsWith("    ") && fixtures.isNotEmpty() && fixtures.last().location.isBlank()) {
                fixtures[fixtures.lastIndex] = fixtures.last().copy(location = line.trim())
            }
            i++
            continue
        }

        // Parse fixture line: name [scope] -- docstring
        // or: name -- docstring
        val fixturePattern = Regex("""^(\w+)\s*(?:\[(\w+)])?\s*(?:--\s*(.*))?$""")
        val match = fixturePattern.find(line)
        if (match != null) {
            val name = match.groupValues[1]
            val scope = match.groupValues[2].ifBlank { "function" }
            val docstring = match.groupValues[3].trim()

            // Skip built-in pytest fixtures that are verbose noise
            if (name in BUILTIN_FIXTURES) {
                i++
                continue
            }

            fixtures.add(FixtureInfo(name, scope, docstring, ""))
        }

        i++
    }
    return fixtures
}

private val BUILTIN_FIXTURES = setOf(
    "cache", "capsys", "capsysbinary", "capfd", "capfdbinary", "caplog",
    "doctest_namespace", "monkeypatch", "pytestconfig", "record_property",
    "record_testsuite_property", "recwarn", "request", "tmp_path",
    "tmp_path_factory", "tmpdir", "tmpdir_factory"
)

private fun pytestNotFoundError(): ToolResult = ToolResult(
    "pytest is not available. Ensure pytest is installed (pip install pytest) and on PATH.",
    "pytest not found",
    ToolResult.ERROR_TOKEN_ESTIMATE,
    isError = true
)
