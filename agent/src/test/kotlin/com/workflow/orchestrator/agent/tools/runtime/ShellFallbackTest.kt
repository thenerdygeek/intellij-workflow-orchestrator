package com.workflow.orchestrator.agent.tools.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the shell-fallback helpers in [JavaRuntimeExecTool] —
 * specifically the Maven "Tests run: N" total-line parser and the Gradle
 * `> Task :...:test` progress-line regex.
 *
 * These helpers live on the tool because they only matter for the shell
 * execution path; the in-IDE JUnit runner path uses SMTestProxy tree results.
 * They are marked `internal` for test access — same-package test file can
 * reach them without changing visibility semantics for production consumers.
 */
class ShellFallbackTest {

    // ══════════════════════════════════════════════════════════════════════
    // Fix 2 — extractMavenTestsRunCount returns LAST occurrence
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `extractMavenTestsRunCount returns last occurrence when multiple suites run`() {
        // Simulates a multi-suite Surefire run: per-suite line + final aggregate.
        val rawOutput = """
            [INFO] -------------------------------------------------------
            [INFO]  T E S T S
            [INFO] -------------------------------------------------------
            [INFO] Running com.example.FirstTest
            [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.123 s
            [INFO] Running com.example.SecondTest
            [INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.456 s
            [INFO] Results:
            [INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
        """.trimIndent()

        val count = JavaRuntimeExecTool().extractMavenTestsRunCount(rawOutput)
        assertEquals(15, count, "should return the final aggregate (15), not the first suite (3)")
    }

    @Test
    fun `extractMavenTestsRunCount returns single value when only one suite ran`() {
        val rawOutput = """
            [INFO] Running com.example.OnlyTest
            [INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
        """.trimIndent()

        assertEquals(7, JavaRuntimeExecTool().extractMavenTestsRunCount(rawOutput))
    }

    @Test
    fun `extractMavenTestsRunCount returns null when no Tests run line is present`() {
        assertNull(JavaRuntimeExecTool().extractMavenTestsRunCount("BUILD SUCCESS"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Fix 1 — GRADLE_TEST_TASK_REGEX
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `gradleTestTaskRegex ignores compileTestJava`() {
        val rawOutput = "> Task :compileTestJava\n> Task :compileJava"
        assertFalse(
            GRADLE_TEST_TASK_REGEX.containsMatchIn(rawOutput),
            "compileTestJava is a compile phase, not a test-run task"
        )
    }

    @Test
    fun `gradleTestTaskRegex ignores testClasses and other test-prefixed tasks`() {
        // `testClasses` is the Gradle task that assembles test sources — tests
        // haven't actually run yet. The `\btest\b` word boundary must reject it.
        assertFalse(GRADLE_TEST_TASK_REGEX.containsMatchIn("> Task :testClasses"))
        assertFalse(GRADLE_TEST_TASK_REGEX.containsMatchIn("> Task :testIntegration"))
    }

    @Test
    fun `gradleTestTaskRegex matches nested project test task`() {
        assertTrue(
            GRADLE_TEST_TASK_REGEX.containsMatchIn("> Task :services:auth:test"),
            "multi-module Gradle builds emit fully-qualified task paths"
        )
    }

    @Test
    fun `gradleTestTaskRegex matches simple test task`() {
        assertTrue(GRADLE_TEST_TASK_REGEX.containsMatchIn("> Task :test"))
        assertTrue(GRADLE_TEST_TASK_REGEX.containsMatchIn("> Task :test UP-TO-DATE"))
    }

    @Test
    fun `gradleTestTaskRegex does not match arbitrary log lines containing colon test`() {
        // Previous implementation ORed two substring checks that both matched
        // this kind of line (`> Task :` somewhere + `:test ` somewhere else).
        val rawOutput = "> Task :compileTestJava\nStarting :test phase of the build"
        assertFalse(
            GRADLE_TEST_TASK_REGEX.containsMatchIn(rawOutput),
            "a random log line containing ':test ' must not be treated as a Gradle progress line"
        )
    }
}
