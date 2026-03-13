package com.workflow.orchestrator.core.maven

enum class TestCaseStatus { PASSED, FAILED, ERROR, SKIPPED }

data class TestCaseInfo(
    val name: String,
    val className: String,
    val durationMs: Long,
    val status: TestCaseStatus,
    val failureMessage: String = "",
    val stackTrace: String = ""
)

/**
 * Converts Surefire test results into TeamCity service message format.
 * These messages are understood by IntelliJ's SMTRunnerConsoleView.
 */
object TeamCityMessageConverter {

    fun convert(testCasesBySuite: Map<String, List<TestCaseInfo>>): List<String> {
        val messages = mutableListOf<String>()
        val totalTests = testCasesBySuite.values.sumOf { it.size }
        messages.add("##teamcity[testCount count='$totalTests']")

        for ((suiteName, testCases) in testCasesBySuite) {
            messages.add("##teamcity[testSuiteStarted name='${escapeValue(suiteName)}']")
            for (tc in testCases) {
                val testId = "${tc.className}.${tc.name}"
                messages.add("##teamcity[testStarted name='${escapeValue(tc.name)}' locationHint='java:test://${escapeValue(testId)}']")
                when (tc.status) {
                    TestCaseStatus.PASSED -> {
                        messages.add("##teamcity[testFinished name='${escapeValue(tc.name)}' duration='${tc.durationMs}']")
                    }
                    TestCaseStatus.FAILED, TestCaseStatus.ERROR -> {
                        messages.add("##teamcity[testFailed name='${escapeValue(tc.name)}' message='${escapeValue(tc.failureMessage)}' details='${escapeValue(tc.stackTrace)}']")
                        messages.add("##teamcity[testFinished name='${escapeValue(tc.name)}' duration='${tc.durationMs}']")
                    }
                    TestCaseStatus.SKIPPED -> {
                        messages.add("##teamcity[testIgnored name='${escapeValue(tc.name)}' message='Skipped']")
                    }
                }
            }
            messages.add("##teamcity[testSuiteFinished name='${escapeValue(suiteName)}']")
        }
        return messages
    }

    fun escapeValue(value: String): String {
        return value
            .replace("|", "||")
            .replace("'", "|'")
            .replace("\n", "|n")
            .replace("\r", "|r")
            .replace("[", "|[")
            .replace("]", "|]")
    }
}
