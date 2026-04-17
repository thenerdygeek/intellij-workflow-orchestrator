package com.workflow.orchestrator.agent.tools.framework

import com.workflow.orchestrator.agent.tools.framework.build.PytestSummary
import com.workflow.orchestrator.agent.tools.framework.build.parsePytestRunOutput
import com.workflow.orchestrator.agent.tools.framework.build.parsePytestSummaryLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for pytest output parsing helpers in [PytestActions].
 *
 * Covers (Task 1.4 — Pytest summary-line reconciliation):
 *  1. [parsePytestSummaryLine] correctly extracts counts across pytest summary variants.
 *  2. [parsePytestRunOutput] returns verbose `::`-style leaf results + raw summary line.
 *  3. End-to-end: when the verbose output undercounts vs the pytest summary, downstream
 *     formatting can flag a `[PARSE MISMATCH]` warning based on the comparison.
 */
class PytestActionsTest {

    // ────────────────────────────────────────────────────────────────────────
    // parsePytestSummaryLine
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class SummaryLineParser {

        @Test
        fun `single passed count`() {
            val result = parsePytestSummaryLine("1 passed in 0.42s")
            assertEquals(PytestSummary(passed = 1, failed = 0, skipped = 0, errors = 0, xfail = 0, xpass = 0), result)
        }

        @Test
        fun `mixed passed failed skipped error`() {
            val result = parsePytestSummaryLine("3 passed, 2 failed, 1 skipped, 1 error in 1.23s")
            assertEquals(PytestSummary(passed = 3, failed = 2, skipped = 1, errors = 1, xfail = 0, xpass = 0), result)
        }

        @Test
        fun `plural errors form handled like singular error`() {
            // pytest prints "1 error" for a single collection error and "2 errors" for multiple.
            val result = parsePytestSummaryLine("2 errors in 0.1s")
            assertEquals(PytestSummary(passed = 0, failed = 0, skipped = 0, errors = 2, xfail = 0, xpass = 0), result)
        }

        @Test
        fun `xfailed and xpassed counted separately`() {
            val result = parsePytestSummaryLine("1 xfailed, 1 xpassed in 0.1s")
            assertEquals(PytestSummary(passed = 0, failed = 0, skipped = 0, errors = 0, xfail = 1, xpass = 1), result)
        }

        @Test
        fun `blank summary returns null`() {
            assertNull(parsePytestSummaryLine(""))
            assertNull(parsePytestSummaryLine("   "))
        }

        @Test
        fun `no tests ran returns null`() {
            // "no tests ran in 0.01s" has no digit+keyword pair → no match, no summary to cross-check.
            assertNull(parsePytestSummaryLine("no tests ran in 0.01s"))
        }

        @Test
        fun `passed with all counts in one line`() {
            val result = parsePytestSummaryLine("5 passed, 1 failed, 2 skipped, 0 errors in 3.14s")
            assertEquals(PytestSummary(passed = 5, failed = 1, skipped = 2, errors = 0, xfail = 0, xpass = 0), result)
        }

        @Test
        fun `failed and errors both present`() {
            val result = parsePytestSummaryLine("1 failed, 3 errors in 0.5s")
            assertEquals(PytestSummary(passed = 0, failed = 1, skipped = 0, errors = 3, xfail = 0, xpass = 0), result)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // parsePytestRunOutput + cross-check against summary
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class VerboseOutputParser {

        @Test
        fun `parses verbose leaf lines and summary line`() {
            val output = """
                tests/test_math.py::test_add PASSED
                tests/test_math.py::test_sub PASSED
                tests/test_math.py::test_mul FAILED
                ============================ 2 passed, 1 failed in 0.42s ============================
            """.trimIndent()

            val result = parsePytestRunOutput(output)
            assertEquals(3, result.tests.size)
            assertEquals("tests/test_math.py::test_add", result.tests[0].name)
            assertEquals("PASSED", result.tests[0].status)
            assertEquals("FAILED", result.tests[2].status)
            assertTrue(
                result.summaryLine.contains("2 passed") && result.summaryLine.contains("1 failed"),
                "Expected summary line to round-trip, was: '${result.summaryLine}'"
            )
        }

        @Test
        fun `verbose output with trailing percentage still matches`() {
            // pytest with live progress (e.g. -v without --no-header) can append "[ 50%]" after status.
            // The existing regex is tolerant of trailing tokens, so this SHOULD match.
            val output = """
                tests/test_foo.py::test_bar PASSED [ 50%]
                tests/test_foo.py::test_baz PASSED [100%]
                ============================ 2 passed in 0.10s ============================
            """.trimIndent()

            val result = parsePytestRunOutput(output)
            assertEquals(2, result.tests.size)
        }

        @Test
        fun `verbose undercount vs summary triggers mismatch`() {
            // Scenario: only 3 `::` verbose lines survived parsing, but pytest's own summary
            // authoritatively reports 4 passed. Downstream formatter compares counts and emits
            // a [PARSE MISMATCH] warning.
            val output = """
                tests/test_a.py::test_one PASSED
                tests/test_a.py::test_two PASSED
                tests/test_a.py::test_three PASSED
                ============================ 4 passed in 0.42s ============================
            """.trimIndent()

            val result = parsePytestRunOutput(output)
            val parsedPassed = result.tests.count { it.status == "PASSED" }
            val summary = parsePytestSummaryLine(result.summaryLine)

            assertEquals(3, parsedPassed)
            assertNotNull(summary)
            assertEquals(4, summary!!.passed)
            assertTrue(parsedPassed != summary.passed, "Counts should disagree to trigger mismatch warning")
        }

        @Test
        fun `matching counts do not flag mismatch`() {
            val output = """
                tests/test_x.py::test_foo PASSED
                tests/test_x.py::test_bar FAILED
                ============================ 1 passed, 1 failed in 0.22s ============================
            """.trimIndent()

            val result = parsePytestRunOutput(output)
            val parsedPassed = result.tests.count { it.status == "PASSED" }
            val parsedFailed = result.tests.count { it.status == "FAILED" }
            val summary = parsePytestSummaryLine(result.summaryLine)

            assertNotNull(summary)
            assertEquals(summary!!.passed, parsedPassed)
            assertEquals(summary.failed, parsedFailed)
        }
    }
}
