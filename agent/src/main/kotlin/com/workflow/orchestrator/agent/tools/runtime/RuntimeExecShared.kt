package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.states.TestStateInfo
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.truncateOutput
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
// RUN_TESTS_MAX_OUTPUT_CHARS and RUN_TESTS_TOKEN_CAP_CHARS deleted — replaced by spillOrFormat (Phase 7 Task 6.4)

// compile_module constants
internal const val COMPILE_MAX_ERROR_MESSAGES = 20

/** Truncate the leading error message in the summary so the LLM skim-read surface
 *  stays compact. 80 chars is enough for "file:line <compiler message gist>" without
 *  drowning the status-bar-sized summary line. */
private const val COMPILE_SUMMARY_MESSAGE_MAX = 80

/**
 * Format per-file compile errors from a [CompileContext] into a rich [ToolResult]
 * that pinpoints each failure at `file:line:col — message` granularity.
 *
 * Shared by both `run_tests` (where a failed pre-test build must surface the actual
 * typo/location, not just "1 errors, 0 warnings") and `compile_module` (the explicit
 * compile action). The [leadingLine] parameter lets each caller provide the
 * context-appropriate header:
 *
 * - `run_tests` passes `"BUILD FAILED — N compile error(s) prevented tests from starting:"`
 * - `compile_module` passes `"Compilation of {target} failed: N error(s), M warning(s)."`
 *
 * The `summary` field on the returned [ToolResult] leads with the first error
 * (`"COMPILE FAILED: MyTest.java:42 cannot find symbol: method asserT"`) so that an
 * LLM skim-reading the summary line cannot confuse a compile failure with a red test.
 *
 * Errors are capped at [COMPILE_MAX_ERROR_MESSAGES] (20). When the message's
 * navigatable is an [OpenFileDescriptor], the 1-based line/column is extracted;
 * otherwise only the filename is shown. Messages without a [com.intellij.openapi.vfs.VirtualFile]
 * are rendered with `<unknown>` as the filename — the helper never throws.
 */
internal fun formatCompileErrors(
    context: CompileContext,
    target: String,
    leadingLine: String,
    warnings: Int = context.getMessages(CompilerMessageCategory.WARNING).size
): ToolResult {
    val errorMessages = context.getMessages(CompilerMessageCategory.ERROR)
    val totalErrors = errorMessages.size
    val shown = errorMessages.take(COMPILE_MAX_ERROR_MESSAGES)

    val formattedLines = shown.map { msg ->
        val file = msg.virtualFile?.name ?: "<unknown>"
        val nav = msg.navigatable
        val location = if (nav is OpenFileDescriptor) {
            "$file:${nav.line + 1}:${nav.column + 1}"
        } else {
            file
        }
        "$location — ${msg.message}"
    }

    val sb = StringBuilder()
    sb.appendLine(leadingLine)
    sb.appendLine()
    for (line in formattedLines) {
        sb.appendLine(line)
    }
    if (totalErrors > COMPILE_MAX_ERROR_MESSAGES) {
        sb.appendLine()
        sb.appendLine("... and ${totalErrors - COMPILE_MAX_ERROR_MESSAGES} more error(s) (showing first $COMPILE_MAX_ERROR_MESSAGES).")
    }
    val content = sb.toString().trimEnd()

    // Lead the summary with the first error — "COMPILE FAILED: MyTest.java:42 msg..."
    // so an LLM scanning summary lines can't mistake this for a red test.
    val summary = if (shown.isNotEmpty()) {
        val firstMsg = shown.first()
        val file = firstMsg.virtualFile?.name ?: "<unknown>"
        val nav = firstMsg.navigatable
        // Summary uses `file:line` (no column) for brevity.
        val location = if (nav is OpenFileDescriptor) "$file:${nav.line + 1}" else file
        val rawMessage = firstMsg.message.orEmpty().lineSequence().firstOrNull().orEmpty()
        val combined = "$location $rawMessage".trim()
        val truncated = if (combined.length > COMPILE_SUMMARY_MESSAGE_MAX) {
            combined.take(COMPILE_SUMMARY_MESSAGE_MAX - 3) + "..."
        } else combined
        "COMPILE FAILED: $truncated"
    } else {
        "Compilation of $target failed: $totalErrors error(s), $warnings warning(s)"
    }

    return ToolResult(
        content = content,
        summary = summary,
        tokenEstimate = TokenEstimator.estimate(content),
        isError = true
    )
}

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
            url != null && (
                url.startsWith("java:test://") ||
                url.startsWith("java:suite://") ||
                url.startsWith("python://") ||
                url.startsWith("file://")
            )
        }
        .map { mapToTestResultEntry(it) }
}

/**
 * Map a single test leaf to a [TestResultEntry].
 *
 * Uses `getMagnitudeInfo()` — IntelliJ's own state model — to distinguish test
 * failures (assertion errors) from test errors (unexpected exceptions):
 *
 *   TestFailedState       → FAILED_INDEX → TestStatus.FAILED
 *   TestComparisonFailedState (extends TestFailedState) → FAILED_INDEX → FAILED
 *   TestErrorState        → ERROR_INDEX  → TestStatus.ERROR
 *
 * String matching on the stacktrace is NOT used: it breaks for JUnit 4's
 * `ComparisonFailure` (from `assertEquals`) because the class name does not
 * contain "AssertionError" even though IntelliJ correctly marks it as FAILED_INDEX.
 * `proxy.wasTerminated()` takes precedence over `isDefect`.
 */
internal fun mapToTestResultEntry(proxy: SMTestProxy): TestResultEntry {
    val status = when {
        proxy.wasTerminated() -> TestStatus.ERROR
        proxy.isDefect -> {
            when (proxy.getMagnitudeInfo()) {
                TestStateInfo.Magnitude.FAILED_INDEX -> TestStatus.FAILED
                TestStateInfo.Magnitude.ERROR_INDEX  -> TestStatus.ERROR
                else                                 -> TestStatus.ERROR
            }
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

/**
 * Format a list of [TestResultEntry] into a rich [ToolResult] with a header line
 * (overall status + passed/failed/errors/skipped counts), a duration, then
 * FAILED / SKIPPED / PASSED sections.
 *
 * The optional [statusFilter] (uppercase `PASSED`/`FAILED`/`ERROR`/`SKIPPED`) is
 * a **presentation** concern only: the header counts and the `isError` flag are
 * always derived from the FULL [allTests] list, so a "PASSED" filter against a
 * run that had failures still reports the failure in the summary line and
 * `isError=true`. When the filter is set, only sections matching the filter are
 * rendered, and for `statusFilter == "PASSED"` every passed test is shown (the
 * `MAX_PASSED_SHOWN` cap is only applied when no filter is active).
 */
internal suspend fun formatStructuredResults(
    allTests: List<TestResultEntry>,
    runName: String,
    tool: AgentTool,
    project: Project?,
    statusFilter: String? = null
): ToolResult {
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

    // FAILED/ERROR block: shown when no filter is set, or when the filter selects FAILED/ERROR.
    val showFailed = statusFilter == null || statusFilter == "FAILED" || statusFilter == "ERROR"
    val failedTests = if (statusFilter == null) {
        allTests.filter { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
    } else if (statusFilter == "FAILED") {
        allTests.filter { it.status == TestStatus.FAILED }
    } else if (statusFilter == "ERROR") {
        allTests.filter { it.status == TestStatus.ERROR }
    } else emptyList()
    if (showFailed && failedTests.isNotEmpty()) {
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

    // SKIPPED block: shown when no filter is set, or when the filter selects SKIPPED.
    val showSkipped = statusFilter == null || statusFilter == "SKIPPED"
    val skippedTests = allTests.filter { it.status == TestStatus.SKIPPED }
    if (showSkipped && skippedTests.isNotEmpty()) {
        sb.appendLine("--- SKIPPED ---")
        for (test in skippedTests) {
            val reason = test.errorMessage
            if (reason != null) sb.appendLine("${test.name} — $reason") else sb.appendLine(test.name)
        }
        sb.appendLine()
    }

    // PASSED block: shown when no filter is set (capped at MAX_PASSED_SHOWN), or when
    // the filter explicitly selects PASSED (uncapped — user asked for all passed tests).
    val showPassed = statusFilter == null || statusFilter == "PASSED"
    val passedTests = allTests.filter { it.status == TestStatus.PASSED }
    if (showPassed && passedTests.isNotEmpty()) {
        sb.appendLine("--- PASSED ($passed tests) ---")
        val shown = if (statusFilter == "PASSED") passedTests else passedTests.take(MAX_PASSED_SHOWN)
        for (test in shown) {
            sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
        }
        if (statusFilter == null && passedTests.size > MAX_PASSED_SHOWN) {
            sb.appendLine("... and ${passedTests.size - MAX_PASSED_SHOWN} more passed tests")
        }
    }

    val content = sb.toString().trimEnd()
    return if (project != null) {
        val spilled = tool.spillOrFormat(content, project)
        ToolResult(
            content = spilled.preview,
            summary = "$overallStatus: $passed passed, $failed failed",
            tokenEstimate = spilled.preview.length / 4,
            isError = overallStatus == "FAILED",
            spillPath = spilled.spilledToFile,
        )
    } else {
        ToolResult(
            content = content,
            summary = "$overallStatus: $passed passed, $failed failed",
            tokenEstimate = content.length / 4,
            isError = overallStatus == "FAILED",
        )
    }
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
internal suspend fun buildRunnerErrorResult(
    root: SMTestProxy,
    tool: AgentTool,
    project: Project?,
): ToolResult {
    val errorMessage = root.errorMessage ?: "unknown"
    val stacktrace = root.stacktrace ?: ""
    val content = "Test runner error: $errorMessage\n\n$stacktrace".trimEnd()
    return if (project != null) {
        val spilled = tool.spillOrFormat(content, project)
        ToolResult(
            content = spilled.preview,
            summary = "Test runner error (no tests executed)",
            tokenEstimate = spilled.preview.length / 4,
            isError = true,
            spillPath = spilled.spilledToFile,
        )
    } else {
        ToolResult(
            content = content,
            summary = "Test runner error (no tests executed)",
            tokenEstimate = content.length / 4,
            isError = true,
        )
    }
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
internal suspend fun interpretTestRoot(
    root: SMTestProxy,
    runName: String,
    tool: AgentTool,
    project: Project?,
    statusFilter: String? = null,
): ToolResult {
    val allTests = collectTestResults(root)

    if (allTests.isNotEmpty()) {
        // Real tests ran — format pass/fail/error/skip regardless of root defect state.
        val result = formatStructuredResults(allTests, runName, tool, project, statusFilter)
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
            buildRunnerErrorResult(root, tool, project)   // killed before any test reported
        root.isDefect ->
            buildRunnerErrorResult(root, tool, project)   // engine-level failure (JUnit 5 crash, class not found, etc.)
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

/**
 * Parse JUnit/Surefire-format test report XML files under a module directory.
 *
 * Shell fallback for `run_tests` shells out to `mvn test` or `./gradlew test` and
 * previously inferred pass/fail from exit code alone. That breaks when the target
 * class is not actually a test class — Surefire exits 0 with 0 tests and the agent
 * reports "Tests PASSED" even though nothing ran. Parsing the XML reports gives us
 * per-test status so we can tell the LLM what really happened.
 *
 * **Return contract:**
 * - `null` → no report files were found at all (directory missing, or every file
 *   was malformed and rejected). Caller should fall through to stdout-marker checks.
 * - empty `List` → reports found but every `<testsuite>` had `tests="0"` or no
 *   `<testcase>` children. Caller should return NO_TESTS_FOUND (not PASSED).
 * - non-empty `List` → parsed testcases in the standard [TestResultEntry] shape,
 *   suitable for [formatStructuredResults].
 *
 * **Search paths:**
 * - `tool == "maven"` → `{moduleDir}/target/surefire-reports/TEST-*.xml` PLUS
 *   `{moduleDir}/target/failsafe-reports/TEST-*.xml` (failsafe = Maven IT tests).
 * - `tool == "gradle"` → `{moduleDir}/build/test-results/STAR/TEST-*.xml`
 *   (walks every subdir — tasks include `test`, `integrationTest`, custom tasks).
 *
 * **XXE hardening:** DOCTYPE declarations are disabled via the
 * `http://apache.org/xml/features/disallow-doctype-decl` feature and XInclude is
 * turned off. Malformed XML, IO errors, and files rejected by the hardened parser
 * are silently skipped so one bad file doesn't kill the whole parse.
 */
internal fun parseJUnitXmlReports(moduleDir: File, tool: String): List<TestResultEntry>? {
    val reportDirs = when (tool) {
        "maven" -> listOf(
            File(moduleDir, "target/surefire-reports"),
            File(moduleDir, "target/failsafe-reports")
        )
        "gradle" -> {
            val testResults = File(moduleDir, "build/test-results")
            // test-results/{task}/ — each task is its own subdir with TEST-*.xml
            if (testResults.isDirectory) {
                testResults.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
            } else emptyList()
        }
        else -> emptyList()
    }

    val xmlFiles = reportDirs
        .filter { it.isDirectory }
        .flatMap { dir ->
            dir.listFiles { f ->
                f.isFile && f.name.startsWith("TEST-") && f.name.endsWith(".xml")
            }?.toList() ?: emptyList()
        }

    if (xmlFiles.isEmpty()) return null

    val entries = mutableListOf<TestResultEntry>()
    var parseAttempted = 0
    var parseSucceeded = 0

    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isXIncludeAware = false
        // XXE hardening — disable external entity resolution entirely. Report XML
        // should never legitimately contain a DOCTYPE.
        try {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) { /* older parsers may not support this feature */ }
        try {
            setFeature("http://xml.org/sax/features/external-general-entities", false)
        } catch (_: Exception) { }
        try {
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (_: Exception) { }
    }

    for (file in xmlFiles) {
        parseAttempted++
        try {
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(file)
            doc.documentElement?.let { root ->
                entries += parseTestsuiteElements(root)
            }
            parseSucceeded++
        } catch (_: Exception) {
            // Malformed XML, XXE-rejected DOCTYPE, or IO error — skip this file.
        }
    }

    // If we found files but EVERY one failed to parse, treat as "no reports" so
    // the caller can escalate to the stdout-marker / NO_TESTS_FOUND path instead
    // of treating an empty entry list as "0 tests ran successfully".
    if (parseAttempted > 0 && parseSucceeded == 0) return null

    return entries
}

/**
 * Parse `<testsuite>` elements under [root], which may itself be a `<testsuite>`
 * (the common Surefire single-suite shape) or a `<testsuites>` wrapper (Gradle +
 * some Surefire configurations). Returns every `<testcase>` as a [TestResultEntry].
 */
private fun parseTestsuiteElements(root: Element): List<TestResultEntry> {
    val suites: List<Element> = when (root.nodeName) {
        "testsuite" -> listOf(root)
        "testsuites" -> root.childNodes.elements().filter { it.nodeName == "testsuite" }.toList()
        else -> emptyList()
    }

    return suites.flatMap { suite ->
        suite.childNodes.elements()
            .filter { it.nodeName == "testcase" }
            .map { parseTestcaseElement(it) }
            .toList()
    }
}

/**
 * Parse a single `<testcase>` element into a [TestResultEntry]. Status is
 * determined by the first child status element found: `<failure>`, `<error>`,
 * `<skipped>`, or (absent) PASSED.
 */
private fun parseTestcaseElement(testcase: Element): TestResultEntry {
    val classname = testcase.getAttribute("classname").orEmpty()
    val method = testcase.getAttribute("name").orEmpty()
    val name = if (classname.isNotBlank()) "$classname.$method" else method

    val durationMs = (testcase.getAttribute("time").toDoubleOrNull() ?: 0.0).let {
        (it * 1000).toLong()
    }

    val children = testcase.childNodes.elements().toList()
    val failure = children.firstOrNull { it.nodeName == "failure" }
    val error = children.firstOrNull { it.nodeName == "error" }
    val skipped = children.firstOrNull { it.nodeName == "skipped" }

    val (status, statusElement) = when {
        failure != null -> TestStatus.FAILED to failure
        error != null -> TestStatus.ERROR to error
        skipped != null -> TestStatus.SKIPPED to skipped
        else -> TestStatus.PASSED to null
    }

    val errorMessage = statusElement?.let { el ->
        el.getAttribute("message").takeIf { it.isNotBlank() }
            ?: el.textContent?.trim()?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    val stackTrace = if (statusElement != null && status != TestStatus.SKIPPED) {
        extractStackLines(statusElement.textContent.orEmpty())
    } else emptyList()

    return TestResultEntry(
        name = name,
        status = status,
        durationMs = durationMs,
        errorMessage = errorMessage,
        stackTrace = stackTrace
    )
}

/**
 * Extract up to [MAX_STACK_FRAMES] stack-relevant lines from a failure/error
 * element's text content. Mirrors [mapToTestResultEntry]'s filter so both paths
 * produce the same shape.
 */
private fun extractStackLines(text: String): List<String> {
    return text.lines()
        .filter { it.trimStart().startsWith("at ") || it.contains("Exception") || it.contains("Error") }
        .take(MAX_STACK_FRAMES)
}

/** Extension: iterate child [Element] nodes only (skips Text, Comment, etc.). */
private fun org.w3c.dom.NodeList.elements(): Sequence<Element> = sequence {
    for (i in 0 until length) {
        val node = item(i)
        if (node.nodeType == Node.ELEMENT_NODE && node is Element) yield(node)
    }
}
