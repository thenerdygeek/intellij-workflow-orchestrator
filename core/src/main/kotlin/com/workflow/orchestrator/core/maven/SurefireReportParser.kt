package com.workflow.orchestrator.core.maven

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses Maven Surefire XML test reports from target/surefire-reports/TEST-*.xml.
 *
 * Each XML file has the structure:
 * ```xml
 * <testsuite name="com.example.FooTest" tests="5" failures="1" errors="0" skipped="1" time="1.234">
 *   <testcase name="testMethod" classname="com.example.FooTest" time="0.123">
 *     <failure message="expected X but was Y" type="AssertionError">stacktrace</failure>
 *   </testcase>
 * </testsuite>
 * ```
 */
object SurefireReportParser {

    private val log = Logger.getInstance(SurefireReportParser::class.java)

    /**
     * Scan the project for all surefire report directories and parse them.
     * Handles both single-module (target/surefire-reports) and multi-module layouts.
     */
    fun parseProjectReports(projectBasePath: String): TestResultSummary {
        val baseDir = File(projectBasePath)
        val reportDirs = mutableListOf<File>()

        // Single-module project
        val singleModuleDir = File(baseDir, "target/surefire-reports")
        if (singleModuleDir.isDirectory) {
            reportDirs.add(singleModuleDir)
        }

        // Multi-module: scan first-level subdirectories
        baseDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
            val surefireDir = File(subDir, "target/surefire-reports")
            if (surefireDir.isDirectory) {
                reportDirs.add(surefireDir)
            }
        }

        if (reportDirs.isEmpty()) {
            return TestResultSummary(0, 0, 0, 0, 0, emptyList())
        }

        var totalTests = 0
        var totalFailures = 0
        var totalErrors = 0
        var totalSkipped = 0
        val failedTests = mutableListOf<TestFailure>()

        for (dir in reportDirs) {
            val xmlFiles = dir.listFiles { _, name -> name.startsWith("TEST-") && name.endsWith(".xml") }
                ?: continue

            for (xmlFile in xmlFiles) {
                try {
                    val result = parseReportFile(xmlFile)
                    totalTests += result.tests
                    totalFailures += result.failures
                    totalErrors += result.errors
                    totalSkipped += result.skipped
                    failedTests.addAll(result.failedTests)
                } catch (e: Exception) {
                    log.warn("[Maven:Surefire] Failed to parse ${xmlFile.name}: ${e.message}")
                }
            }
        }

        val passed = totalTests - totalFailures - totalErrors - totalSkipped
        return TestResultSummary(totalTests, passed, totalFailures, totalErrors, totalSkipped, failedTests)
    }

    private fun parseReportFile(file: File): SuiteResult {
        val factory = DocumentBuilderFactory.newInstance()
        // Disable external entities for security
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)

        val root = doc.documentElement
        val tests = root.getAttribute("tests").toIntOrNull() ?: 0
        val failures = root.getAttribute("failures").toIntOrNull() ?: 0
        val errors = root.getAttribute("errors").toIntOrNull() ?: 0
        val skipped = root.getAttribute("skipped").toIntOrNull() ?: 0

        val failedTests = mutableListOf<TestFailure>()

        val testcases = root.getElementsByTagName("testcase")
        for (i in 0 until testcases.length) {
            val testcase = testcases.item(i)
            val testName = testcase.attributes.getNamedItem("name")?.nodeValue ?: "unknown"
            val className = testcase.attributes.getNamedItem("classname")?.nodeValue ?: "unknown"

            // Check for failure or error child elements
            val children = testcase.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j)
                if (child.nodeName == "failure" || child.nodeName == "error") {
                    val message = child.attributes?.getNamedItem("message")?.nodeValue ?: ""
                    val type = child.attributes?.getNamedItem("type")?.nodeValue ?: ""
                    val stacktrace = child.textContent ?: ""
                    failedTests.add(TestFailure(className, testName, message, type, stacktrace))
                }
            }
        }

        return SuiteResult(tests, failures, errors, skipped, failedTests)
    }

    private data class SuiteResult(
        val tests: Int,
        val failures: Int,
        val errors: Int,
        val skipped: Int,
        val failedTests: List<TestFailure>
    )
}

data class TestResultSummary(
    val totalTests: Int,
    val passed: Int,
    val failures: Int,
    val errors: Int,
    val skipped: Int,
    val failedTests: List<TestFailure>
)

data class TestFailure(
    val className: String,
    val testName: String,
    val message: String,
    val type: String = "",
    val stacktrace: String = ""
)
