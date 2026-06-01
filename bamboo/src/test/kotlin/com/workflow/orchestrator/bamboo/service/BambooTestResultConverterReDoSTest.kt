package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.dto.BambooTestCaseDto
import com.workflow.orchestrator.bamboo.api.dto.BambooTestCaseCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooTestResultsDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Regression tests for audit finding bamboo:F-5 — ReDoS-prone regex in
 * BambooTestResultConverter.extractTestError.
 *
 * The three regexes previously used unbounded [\s\S]*? with alternation-lookahead
 * against the full (multi-MB) build log. A pathological log whose chars almost-but-don't
 * match the trailing lookahead could cause catastrophic backtracking, hanging log rendering.
 *
 * Fix: (a) capture group bounded to 4 000 chars, (b) input sliced to the last 64 KB.
 */
class BambooTestResultConverterReDoSTest {

    // ────────────────────────────────────────────────────────────────
    //  Helper to build a minimal BambooTestResultsDto with one failed test
    // ────────────────────────────────────────────────────────────────

    private fun dtoWithOneFailure(className: String, methodName: String): BambooTestResultsDto {
        val dto = BambooTestCaseDto(className = className, methodName = methodName, status = "failed")
        return BambooTestResultsDto(
            all = 1,
            successful = 0,
            failed = 1,
            skipped = 0,
            failedTests = BambooTestCaseCollection(testResult = listOf(dto)),
            successfulTests = BambooTestCaseCollection()
        )
    }

    // ────────────────────────────────────────────────────────────────
    //  ReDoS safety: pathological input must NOT hang for more than 2s
    // ────────────────────────────────────────────────────────────────

    /**
     * Builds a string that triggers worst-case backtracking on the OLD unbounded regex:
     * a long run of spaces (which are not \S and are not \n\n) so the alternation
     * lookahead (?=\n\S|\n\n|\Z) has to re-try at every position.
     *
     * With the bounded capture {0,4000} the regex gives up quickly once the limit is hit.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `extractTestError completes within 2s on pathological input`() {
        val methodName = "myTest"
        val className = "com.example.MyTest"

        // Craft a log that starts with the Surefire failure header then a 100KB run
        // of characters that never satisfy the lookahead — worst-case for the old regex.
        val evilBody = " ".repeat(100_000)   // spaces: no \S, no \n\n, not \Z
        val log = buildString {
            append("$methodName($className)  Time elapsed: 0.5 s  <<< FAILURE!\n")
            append(evilBody)
        }

        val dto = dtoWithOneFailure(className, methodName)
        // Must not hang. The @Timeout will interrupt and fail if it does.
        val messages = BambooTestResultConverter.toTeamCityMessages(dto, buildLog = log)
        assertNotNull(messages)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `extractTestError completes within 2s on multi-MB log with no match`() {
        val methodName = "myTest"
        val className = "com.example.MyTest"

        // 4 MB log with NO Surefire failure header — every regex must scan the slice and give up.
        val bigLog = "x".repeat(4 * 1024 * 1024)

        val dto = dtoWithOneFailure(className, methodName)
        val messages = BambooTestResultConverter.toTeamCityMessages(dto, buildLog = bigLog)
        // No match expected — testFailed message uses default "Test failed on Bamboo build"
        assertTrue(messages.any { it.contains("testFailed") })
        // Elapsed time is asserted implicitly by @Timeout(2s) above
    }

    // ────────────────────────────────────────────────────────────────
    //  Positive: normal Surefire failure block extracts correctly
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `extracts error message from a normal Surefire failure block`() {
        val methodName = "testCreateUser"
        val className = "com.example.UserServiceTest"

        val log = """
            Running com.example.UserServiceTest
            testCreateUser(com.example.UserServiceTest)  Time elapsed: 0.312 s  <<< FAILURE!
            java.lang.AssertionError: expected:<200> but was:<500>
                at org.junit.Assert.fail(Assert.java:89)
                at com.example.UserServiceTest.testCreateUser(UserServiceTest.java:42)

            Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
        """.trimIndent()

        val dto = dtoWithOneFailure(className, methodName)
        val messages = BambooTestResultConverter.toTeamCityMessages(dto, buildLog = log)

        // Must produce a testFailed line with the extracted message
        val failedLine = messages.firstOrNull { it.contains("testFailed") }
        assertNotNull(failedLine, "Expected a testFailed TeamCity message")
        assertTrue(
            failedLine!!.contains("expected:<200>") || failedLine.contains("AssertionError"),
            "testFailed message should contain extracted error text, got: $failedLine"
        )
    }

    @Test
    fun `extracts error message using simple class name pattern`() {
        val methodName = "testDelete"
        val className = "com.example.OrderServiceTest"

        // Pattern 2: simple class name (without package)
        val log = """
            testDelete(OrderServiceTest)  Time elapsed: 0.1 s  <<< ERROR!
            java.lang.NullPointerException: entity was null
                at com.example.OrderServiceTest.testDelete(OrderServiceTest.java:88)

        """.trimIndent()

        val dto = dtoWithOneFailure(className, methodName)
        val messages = BambooTestResultConverter.toTeamCityMessages(dto, buildLog = log)

        val failedLine = messages.firstOrNull { it.contains("testFailed") }
        assertNotNull(failedLine, "Expected a testFailed TeamCity message for pattern 2")
        assertTrue(
            failedLine!!.contains("NullPointerException") || failedLine.contains("entity was null"),
            "testFailed message should contain extracted error text, got: $failedLine"
        )
    }
}
