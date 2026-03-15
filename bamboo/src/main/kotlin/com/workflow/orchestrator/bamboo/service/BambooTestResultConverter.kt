package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.dto.BambooTestCaseDto
import com.workflow.orchestrator.bamboo.api.dto.BambooTestResultsDto

/**
 * Converts Bamboo test results to TeamCity service messages format.
 * These messages are consumed by IntelliJ's SMTRunnerConsoleView to render
 * the native test runner UI (green/red tree, clickable test names, duration).
 */
object BambooTestResultConverter {

    /**
     * Convert Bamboo test results to TeamCity service messages.
     * Groups tests by className (test suite) and generates
     * testSuiteStarted/testStarted/testFailed/testFinished/testSuiteFinished messages.
     */
    fun toTeamCityMessages(testResults: BambooTestResultsDto): List<String> {
        val messages = mutableListOf<String>()

        // Combine failed and successful tests
        val allTests = mutableListOf<BambooTestCaseDto>()
        allTests.addAll(testResults.failedTests.testResult)
        allTests.addAll(testResults.successfulTests.testResult)

        if (allTests.isEmpty()) return emptyList()

        // Group by className (test suite)
        val grouped = allTests.groupBy { it.className }

        // Add test count message
        messages.add("##teamcity[testCount count='${allTests.size}']")

        for ((className, tests) in grouped) {
            messages.add("##teamcity[testSuiteStarted name='${escape(className)}']")

            for (test in tests) {
                val testName = escape(test.methodName)
                val durationMs = if (test.duration > 0) test.duration else test.durationInSeconds * 1000

                messages.add("##teamcity[testStarted name='${testName}' captureStandardOutput='true']")

                when (test.status.lowercase()) {
                    "failed" -> {
                        messages.add("##teamcity[testFailed name='${testName}' message='Test failed on Bamboo build']")
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

    /** Escape special characters for TeamCity service messages. */
    private fun escape(text: String): String {
        return text
            .replace("|", "||")
            .replace("'", "|'")
            .replace("\n", "|n")
            .replace("\r", "|r")
            .replace("[", "|[")
            .replace("]", "|]")
    }
}
