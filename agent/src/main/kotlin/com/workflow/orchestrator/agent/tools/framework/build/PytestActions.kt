package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.truncateOutput
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

/**
 * Matches a pytest verbose-mode leaf line such as `tests/test_foo.py::test_bar PASSED`
 * or `tests/test_foo.py::test_bar PASSED [ 50%]`. Lifted from [parsePytestRunOutput] so
 * the zero-output heuristic ([shouldWarnZeroOutput] / [computeStdoutVolumeBytes]) can
 * filter the same lines the parser already consumes.
 */
internal val PYTEST_VERBOSE_STATUS = Regex("""^(.+::\S+)\s+(PASSED|FAILED|SKIPPED|ERROR|XFAIL|XPASS)""")

/**
 * Soft-warning note prepended to pytest results when passed tests complete in ~zero time
 * with minimal stdout. Not an error — just a nudge for the LLM to verify the tests are
 * actually exercising code (not empty bodies, over-mocked, wrong assertion target, etc.).
 */
internal const val PYTEST_ZERO_OUTPUT_NOTE =
    "[NOTE] All tests passed in near-zero time with minimal stdout. " +
        "Consider verifying tests actually exercise the code under test " +
        "(overmocked / empty body / wrong assertion target all produce this pattern)."

/**
 * Decide whether to emit [PYTEST_ZERO_OUTPUT_NOTE] alongside pytest results.
 *
 * Triggers when:
 *  - at least one test passed (so there's something to be suspicious about);
 *  - average wall time per passed test is < 1 ms (integer division); and
 *  - non-status stdout volume is < 1 KB (tests printed nothing of substance).
 *
 * Pure function so it can be tested in isolation.
 */
internal fun shouldWarnZeroOutput(passed: Int, wallTimeMs: Long, stdoutVolumeKB: Double): Boolean {
    if (passed <= 0) return false
    return (wallTimeMs / passed) < 1L && stdoutVolumeKB < 1.0
}

/**
 * Compute the byte-size of pytest stdout minus the lines the parser already consumes
 * (verbose status lines like `file.py::test PASSED` and the `=== N passed ===` summary
 * line). Blank lines and the summary bar are also excluded.
 *
 * Bytes are counted with UTF-8 encoding, plus 1 per line for the implicit newline
 * (lost by [String.lines]). Used by the zero-output heuristic to decide whether the
 * run produced any meaningful output beyond the test-status bookkeeping.
 */
internal fun computeStdoutVolumeBytes(output: String): Long {
    val nonStatusLines = output.lines().filter { line ->
        val trimmed = line.trim()
        trimmed.isNotBlank() &&
            !PYTEST_VERBOSE_STATUS.containsMatchIn(trimmed) &&
            !(trimmed.startsWith("=") && (trimmed.contains("passed") || trimmed.contains("failed") || trimmed.contains("error")))
    }
    return nonStatusLines.sumOf { (it.toByteArray(Charsets.UTF_8).size + 1).toLong() /* +1 for newline */ }
}

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

            // Wall-time measurement is intentionally localized here (Option 2 from the plan):
            // recording start/end around runPytestCommand under-measures by the tiny wrapper
            // overhead but avoids widening the shared helper signature or touching the other
            // call sites. The duration feeds into the zero-output heuristic below.
            val startMs = System.currentTimeMillis()
            val output = runPytestCommand(args, project, timeout = RUN_TIMEOUT_SECONDS)
                ?: return@withContext pytestNotFoundError()
            val wallTimeMs = System.currentTimeMillis() - startMs

            val results = parsePytestRunOutput(output)

            val parsedPassed = results.tests.count { it.status == "PASSED" }
            val parsedFailed = results.tests.count { it.status == "FAILED" }
            val parsedSkipped = results.tests.count { it.status == "SKIPPED" }
            val parsedErrors = results.tests.count { it.status == "ERROR" }

            val parsedSummary = parsePytestSummaryLine(results.summaryLine)
            val mismatch = parsedSummary != null && (
                parsedPassed != parsedSummary.passed ||
                    parsedFailed != parsedSummary.failed ||
                    parsedSkipped != parsedSummary.skipped ||
                    parsedErrors != parsedSummary.errors
                )

            // Zero-output heuristic (Incident #3): pytest happily reports PASSED for
            // no-op tests (`def test_nothing(): pass`). Flag runs where passed tests
            // completed in ~zero time with nothing meaningful written to stdout.
            val stdoutVolumeKB = computeStdoutVolumeBytes(output) / 1024.0
            val zeroOutputWarning = shouldWarnZeroOutput(parsedPassed, wallTimeMs, stdoutVolumeKB)

            val content = buildString {
                if (mismatch) {
                    // `mismatch` is only true when parsedSummary is non-null (see computation above).
                    val s = parsedSummary!!
                    appendLine(
                        "[PARSE MISMATCH] Verbose output parsed as ${results.tests.size} tests " +
                            "($parsedPassed passed, $parsedFailed failed, $parsedSkipped skipped, $parsedErrors errors) " +
                            "but pytest summary reports " +
                            "(${s.passed} passed, ${s.failed} failed, ${s.skipped} skipped, ${s.errors} errors). " +
                            "Raw output included below for verification."
                    )
                    appendLine()
                }

                if (zeroOutputWarning) {
                    appendLine(PYTEST_ZERO_OUTPUT_NOTE)
                    appendLine()
                }

                appendLine("pytest results:")
                appendLine()

                if (results.tests.isNotEmpty()) {
                    appendLine("Summary: ${results.tests.size} total, $parsedPassed passed, $parsedFailed failed, $parsedSkipped skipped, $parsedErrors errors")
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
                    append(truncateOutput(results.failureOutput, 5000))
                }
            }

            val toolSummary = if (results.summaryLine.isNotBlank()) {
                "pytest: ${results.summaryLine}"
            } else {
                "${results.tests.size} tests: $parsedPassed passed, $parsedFailed failed"
            }

            ToolResult(
                content = content.trimEnd(),
                summary = toolSummary,
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

internal data class TestRunResult(
    val tests: List<TestResult>,
    val summaryLine: String,
    val failureOutput: String
)

internal data class TestResult(val name: String, val status: String)

/**
 * Parsed counts from a pytest summary line (e.g. `=== 3 passed, 2 failed, 1 skipped in 0.42s ===`).
 * Internal so tests in the same module can construct/compare instances.
 */
internal data class PytestSummary(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val errors: Int,
    val xfail: Int,
    val xpass: Int,
)

private val PYTEST_SUMMARY_PATTERN =
    Regex("""(\d+)\s+(passed|failed|skipped|error|errors|xfailed|xpassed)""")

/**
 * Parse a pytest summary line into counts. Input is the already-`=`-stripped summary
 * (see [parsePytestRunOutput]). Returns null if the input is blank or contains no
 * recognizable count/token pairs (e.g. "no tests ran in 0.01s").
 *
 * Handles both singular (`1 error`) and plural (`2 errors`) forms that pytest prints.
 */
internal fun parsePytestSummaryLine(summaryLine: String): PytestSummary? {
    if (summaryLine.isBlank()) return null
    var passed = 0
    var failed = 0
    var skipped = 0
    var errors = 0
    var xfail = 0
    var xpass = 0
    var anyMatch = false
    PYTEST_SUMMARY_PATTERN.findAll(summaryLine).forEach { m ->
        anyMatch = true
        val n = m.groupValues[1].toInt()
        when (m.groupValues[2]) {
            "passed" -> passed = n
            "failed" -> failed = n
            "skipped" -> skipped = n
            "error", "errors" -> errors = n
            "xfailed" -> xfail = n
            "xpassed" -> xpass = n
        }
    }
    return if (anyMatch) PytestSummary(passed, failed, skipped, errors, xfail, xpass) else null
}

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

internal fun parsePytestRunOutput(output: String): TestRunResult {
    val tests = mutableListOf<TestResult>()
    var summaryLine = ""
    val failureOutput = StringBuilder()
    var inFailure = false

    for (line in output.lines()) {
        val trimmed = line.trim()

        // Verbose output: tests/test_foo.py::test_bar PASSED
        // Pattern lifted to file-level PYTEST_VERBOSE_STATUS so the zero-output
        // heuristic can filter the same lines we consume here.
        val match = PYTEST_VERBOSE_STATUS.find(trimmed)
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
