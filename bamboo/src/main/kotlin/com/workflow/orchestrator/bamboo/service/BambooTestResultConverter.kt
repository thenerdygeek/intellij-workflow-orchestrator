package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.bamboo.api.dto.BambooTestCaseDto
import com.workflow.orchestrator.bamboo.api.dto.BambooTestResultsDto

/**
 * Converts Bamboo test results to TeamCity service messages format.
 * These messages are consumed by IntelliJ's SMTRunnerConsoleView to render
 * the native test runner UI (green/red tree, clickable test names, duration).
 *
 * Error details are extracted from the build log as a fallback when the
 * Bamboo API doesn't return error messages.
 */
object BambooTestResultConverter {

    private val log = Logger.getInstance(BambooTestResultConverter::class.java)

    /**
     * Convert Bamboo test results to TeamCity service messages.
     * @param testResults Bamboo test result data
     * @param buildLog Optional build log text — used to extract error messages for failed tests
     */
    fun toTeamCityMessages(testResults: BambooTestResultsDto, buildLog: String? = null): List<String> {
        val messages = mutableListOf<String>()

        val allTests = mutableListOf<BambooTestCaseDto>()
        allTests.addAll(testResults.failedTests.testResult)
        allTests.addAll(testResults.successfulTests.testResult)

        if (allTests.isEmpty()) return emptyList()

        // Pre-parse error details from build log for failed tests
        val errorDetails = if (buildLog != null) {
            extractErrorsFromLog(buildLog, testResults.failedTests.testResult)
        } else emptyMap()

        val grouped = allTests.groupBy { it.className }

        messages.add("##teamcity[testCount count='${allTests.size}']")

        for ((className, tests) in grouped) {
            messages.add("##teamcity[testSuiteStarted name='${escape(className)}']")

            for (test in tests) {
                val testName = escape(test.methodName)
                val durationMs = if (test.duration > 0) test.duration else test.durationInSeconds * 1000

                messages.add("##teamcity[testStarted name='${testName}' captureStandardOutput='true']")

                when (test.status.lowercase()) {
                    "failed" -> {
                        val errorKey = "${test.className}.${test.methodName}"
                        val errorInfo = errorDetails[errorKey]
                        val errorMessage = escape(errorInfo?.message ?: "Test failed on Bamboo build")
                        val errorTrace = errorInfo?.stackTrace?.let { escape(it) } ?: ""

                        if (errorTrace.isNotBlank()) {
                            messages.add("##teamcity[testFailed name='${testName}' message='${errorMessage}' details='${errorTrace}']")
                        } else {
                            messages.add("##teamcity[testFailed name='${testName}' message='${errorMessage}']")
                        }

                        // Also print the stack trace as test output (shows in the console tab)
                        if (errorInfo != null) {
                            messages.add("##teamcity[testStdOut name='${testName}' out='${escape(errorInfo.fullOutput)}']")
                        }
                    }
                    "skipped" -> {
                        messages.add("##teamcity[testIgnored name='${testName}' message='Test skipped']")
                    }
                }

                messages.add("##teamcity[testFinished name='${testName}' duration='${durationMs}']")
            }

            messages.add("##teamcity[testSuiteFinished name='${escape(className)}']")
        }

        return messages
    }

    /**
     * Extract error messages and stack traces from the build log for each failed test.
     * Matches patterns like:
     *   methodName(com.example.ClassName)  Time elapsed: 0.5 s  <<< FAILURE!
     *   java.lang.AssertionError: expected 200 but got 500
     *       at org.junit.Assert.fail(Assert.java:89)
     *       at com.example.ClassName.methodName(ClassName.java:42)
     */
    private fun extractErrorsFromLog(
        buildLog: String,
        failedTests: List<BambooTestCaseDto>
    ): Map<String, TestErrorInfo> {
        val result = mutableMapOf<String, TestErrorInfo>()

        for (test in failedTests) {
            val key = "${test.className}.${test.methodName}"
            val simpleClassName = test.className.substringAfterLast('.')

            // Try multiple patterns that Surefire/Failsafe use
            val errorInfo = extractTestError(buildLog, test.methodName, simpleClassName, test.className)
            if (errorInfo != null) {
                result[key] = errorInfo
                log.info("[Bamboo:TestConverter] Extracted error for $key: ${errorInfo.message.take(100)}")
            }
        }

        return result
    }

    private fun extractTestError(
        log: String,
        methodName: String,
        simpleClassName: String,
        fullClassName: String
    ): TestErrorInfo? {
        // Pattern 1: methodName(com.example.ClassName)  Time elapsed: X s  <<< FAILURE!
        val pattern1 = Regex(
            """${Regex.escape(methodName)}\(${Regex.escape(fullClassName)}\).*<<<\s*(FAILURE|ERROR)!\s*\n([\s\S]*?)(?=\n\S|\n\n|\Z)""",
            RegexOption.MULTILINE
        )

        // Pattern 2: methodName(ClassName)  Time elapsed: X s  <<< FAILURE!
        val pattern2 = Regex(
            """${Regex.escape(methodName)}\(${Regex.escape(simpleClassName)}\).*<<<\s*(FAILURE|ERROR)!\s*\n([\s\S]*?)(?=\n\S|\n\n|\Z)""",
            RegexOption.MULTILINE
        )

        // Pattern 3: [ERROR] Tests run: X, Failures: Y ... <<< FAILURE! - in className
        val pattern3 = Regex(
            """${Regex.escape(methodName)}.*<<<\s*(FAILURE|ERROR)!\s*\n([\s\S]*?)(?=\n[^\s]|\n\n|\Z)""",
            RegexOption.MULTILINE
        )

        for (pattern in listOf(pattern1, pattern2, pattern3)) {
            val match = pattern.find(log)
            if (match != null) {
                val errorBlock = match.groupValues[2].trim()
                val lines = errorBlock.lines()
                val message = lines.firstOrNull()?.trim() ?: "Test failed"
                val stackTrace = lines.drop(0).joinToString("\n")
                return TestErrorInfo(
                    message = message,
                    stackTrace = stackTrace,
                    fullOutput = errorBlock
                )
            }
        }

        return null
    }

    private fun escape(text: String): String {
        return text
            .replace("|", "||")
            .replace("'", "|'")
            .replace("\n", "|n")
            .replace("\r", "|r")
            .replace("[", "|[")
            .replace("]", "|]")
    }

    data class TestErrorInfo(
        val message: String,
        val stackTrace: String,
        val fullOutput: String
    )
}
