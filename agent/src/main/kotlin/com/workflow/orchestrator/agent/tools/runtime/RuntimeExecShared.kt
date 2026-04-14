package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.truncateOutput

/**
 * Shared helpers for [RuntimeExecTool], [JavaRuntimeExecTool], and [PythonRuntimeExecTool].
 *
 * Contains the JUnit/TestNG test result collection, mapping, and formatting primitives
 * — plus the test status enum and entry data class — extracted from the pre-split
 * RuntimeExecTool so each IDE-specific variant can share them without duplication.
 */

internal enum class TestStatus { PASSED, FAILED, ERROR, SKIPPED }

internal data class TestResultEntry(
    val name: String,
    val status: TestStatus,
    val durationMs: Long,
    val errorMessage: String?,
    val stackTrace: List<String>
)

// Shared test result constants
internal const val MAX_STACK_FRAMES = 5
internal const val MAX_PASSED_SHOWN = 20

// run_tests constants
internal const val RUN_TESTS_DEFAULT_TIMEOUT = 300L
internal const val RUN_TESTS_MAX_TIMEOUT = 900L
/** Test tree finalization retry — when no TestResultsViewer is available,
 *  poll for the test root to be populated after process termination. */
internal const val TEST_TREE_RETRY_ATTEMPTS = 10
internal const val TEST_TREE_RETRY_INTERVAL_MS = 500L  // 10 * 500ms = 5s max wait

/** Build watchdog timeout — how long to wait for CompilationStatusListener callback. */
internal const val BUILD_WATCHDOG_MAX_MS = 300_000L     // Hard cap at 5 min (matches test timeout)
internal const val RUN_TESTS_MAX_OUTPUT_CHARS = 12000
internal const val RUN_TESTS_TOKEN_CAP_CHARS = 12000

// compile_module constants
internal const val COMPILE_MAX_ERROR_MESSAGES = 20

/**
 * Collect real test leaves from the SMTestProxy tree, rejecting synthetic engine-level
 * nodes that surface under root.allTests when JUnit reports a runner failure.
 *
 * Real test leaves carry a non-null locationUrl starting with `java:test://` or
 * `java:suite://`. Engine-level failure nodes (e.g. "Internal Error Occurred") either
 * have null locationUrl or use the `java:engine://` scheme; these must NOT be treated
 * as tests — mapping them as PASSED yields the misleading "1 passed, 0 failed"
 * contract bug that this fix addresses.
 */
internal fun collectTestResults(root: SMTestProxy): List<TestResultEntry> {
    return root.allTests
        .filterIsInstance<SMTestProxy>()
        .filter { it.isLeaf }
        .filter { proxy ->
            val url = proxy.locationUrl
            url != null && (url.startsWith("java:test://") || url.startsWith("java:suite://"))
        }
        .map { mapToTestResultEntry(it) }
}

/**
 * Map a single test leaf to a [TestResultEntry].
 *
 * `proxy.wasTerminated()` takes precedence over `isDefect`: a terminated test was
 * killed (e.g. by watchdog timeout) and should surface as ERROR even if JUnit itself
 * did not mark it defect.
 */
internal fun mapToTestResultEntry(proxy: SMTestProxy): TestResultEntry {
    val status = when {
        proxy.wasTerminated() -> TestStatus.ERROR
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

internal fun formatStructuredResults(allTests: List<TestResultEntry>, runName: String): ToolResult {
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
            val reason = test.errorMessage
            if (reason != null) sb.appendLine("${test.name} — $reason") else sb.appendLine(test.name)
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
    val capped = truncateOutput(content, RUN_TESTS_TOKEN_CAP_CHARS)

    return ToolResult(
        capped,
        "$overallStatus: $passed passed, $failed failed",
        capped.length / 4,
        isError = overallStatus == "FAILED"
    )
}

internal fun formatDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        else -> "${"%.1f".format(ms / 1000.0)}s"
    }
}

/**
 * Build a "test runner error" failure result for a root that surfaced a defect,
 * termination, or empty suite without producing real leaves. Content combines the
 * error message and stacktrace (if any) so the LLM has enough signal to diagnose
 * the runner failure instead of silently seeing "0 tests".
 */
internal fun buildRunnerErrorResult(root: SMTestProxy): ToolResult {
    val errorMessage = root.errorMessage ?: "unknown"
    val stacktrace = root.stacktrace ?: ""
    val content = "Test runner error: $errorMessage\n\n$stacktrace".trimEnd()
    val capped = truncateOutput(content, RUN_TESTS_TOKEN_CAP_CHARS)
    return ToolResult(
        content = capped,
        summary = "Test runner error (no tests executed)",
        tokenEstimate = capped.length / 4,
        isError = true
    )
}

/**
 * Interpret an [SMTestProxy] root after a test run completes, distinguishing
 * between normal test results (some may have failed), runner-level errors, and
 * empty suites.
 *
 * **Key invariant**: `root.isDefect` bubbles up from children — it is `true`
 * whenever any test method failed, even when the run itself completed normally.
 * It MUST NOT be used alone as a runner-failure signal; doing so causes runs
 * where some tests fail to be reported as "Test runner error: unknown" instead
 * of the actual per-test results.
 *
 * Decision tree:
 * 1. Collect real test leaves first via [collectTestResults].
 * 2. If leaves exist → format them (passes + failures) regardless of `root.isDefect`.
 * 3. If no leaves + `root.wasTerminated()` → runner was killed externally.
 * 4. If no leaves + `root.isDefect` → engine-level failure (wrong class, JUnit 5 crash).
 * 5. If no leaves + no defect → empty suite (no @Test methods, wrong class name).
 */
internal fun interpretTestRoot(root: SMTestProxy, runName: String): ToolResult {
    val allTests = collectTestResults(root)

    if (allTests.isNotEmpty()) {
        // Real tests ran — format pass/fail/error/skip regardless of root defect state.
        val result = formatStructuredResults(allTests, runName)
        // If the process was also terminated (e.g. timeout), prepend a warning.
        return if (root.wasTerminated()) {
            result.copy(
                content = "[TERMINATED] Test run was killed before all tests could complete. Partial results:\n\n${result.content}",
                isError = true
            )
        } else result
    }

    // No real test leaves — distinguish the failure cause.
    return when {
        root.wasTerminated() ->
            buildRunnerErrorResult(root)   // killed before any test reported
        root.isDefect ->
            buildRunnerErrorResult(root)   // engine-level failure (JUnit 5 crash, class not found, etc.)
        else -> ToolResult(
            content = "Test run completed for '$runName' but no test methods were found.\n\n" +
                "Possible causes:\n" +
                "  • Class name is not a test class or has no @Test methods\n" +
                "  • Tests are annotated with a framework the runner doesn't support\n" +
                "  • Module not compiled — run compile_module first",
            summary = "No tests found in '$runName'",
            tokenEstimate = 20,
            isError = true
        )
    }
}
