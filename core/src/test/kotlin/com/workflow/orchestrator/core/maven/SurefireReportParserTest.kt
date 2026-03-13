package com.workflow.orchestrator.core.maven

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SurefireReportParserTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `parses successful test suite`() {
        val reportDir = File(tempDir, "target/surefire-reports").apply { mkdirs() }
        File(reportDir, "TEST-com.example.FooTest.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.FooTest" tests="3" failures="0" errors="0" skipped="0" time="1.234">
              <testcase name="testAdd" classname="com.example.FooTest" time="0.1"/>
              <testcase name="testSubtract" classname="com.example.FooTest" time="0.2"/>
              <testcase name="testMultiply" classname="com.example.FooTest" time="0.3"/>
            </testsuite>
        """.trimIndent())

        val result = SurefireReportParser.parseProjectReports(tempDir.absolutePath)

        assertEquals(3, result.totalTests)
        assertEquals(3, result.passed)
        assertEquals(0, result.failures)
        assertEquals(0, result.errors)
        assertTrue(result.failedTests.isEmpty())
    }

    @Test
    fun `parses test suite with failures`() {
        val reportDir = File(tempDir, "target/surefire-reports").apply { mkdirs() }
        File(reportDir, "TEST-com.example.BarTest.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.BarTest" tests="2" failures="1" errors="0" skipped="0" time="0.5">
              <testcase name="testPass" classname="com.example.BarTest" time="0.1"/>
              <testcase name="testFail" classname="com.example.BarTest" time="0.4">
                <failure message="expected 5 but was 3" type="org.junit.AssertionError">stack trace here</failure>
              </testcase>
            </testsuite>
        """.trimIndent())

        val result = SurefireReportParser.parseProjectReports(tempDir.absolutePath)

        assertEquals(2, result.totalTests)
        assertEquals(1, result.passed)
        assertEquals(1, result.failures)
        assertEquals(1, result.failedTests.size)
        assertEquals("testFail", result.failedTests[0].testName)
        assertEquals("com.example.BarTest", result.failedTests[0].className)
        assertEquals("expected 5 but was 3", result.failedTests[0].message)
    }

    @Test
    fun `parses test suite with errors and skipped`() {
        val reportDir = File(tempDir, "target/surefire-reports").apply { mkdirs() }
        File(reportDir, "TEST-com.example.ErrTest.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.ErrTest" tests="4" failures="0" errors="1" skipped="1" time="0.8">
              <testcase name="testOk" classname="com.example.ErrTest" time="0.1"/>
              <testcase name="testOk2" classname="com.example.ErrTest" time="0.1"/>
              <testcase name="testError" classname="com.example.ErrTest" time="0.5">
                <error message="NullPointerException" type="java.lang.NullPointerException">npe trace</error>
              </testcase>
              <testcase name="testSkipped" classname="com.example.ErrTest" time="0.0">
                <skipped/>
              </testcase>
            </testsuite>
        """.trimIndent())

        val result = SurefireReportParser.parseProjectReports(tempDir.absolutePath)

        assertEquals(4, result.totalTests)
        assertEquals(2, result.passed)
        assertEquals(0, result.failures)
        assertEquals(1, result.errors)
        assertEquals(1, result.skipped)
        assertEquals(1, result.failedTests.size)
        assertEquals("testError", result.failedTests[0].testName)
    }

    @Test
    fun `aggregates across multiple report files`() {
        val reportDir = File(tempDir, "target/surefire-reports").apply { mkdirs() }
        File(reportDir, "TEST-com.example.ATest.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite tests="5" failures="0" errors="0" skipped="0"/>
        """.trimIndent())
        File(reportDir, "TEST-com.example.BTest.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite tests="3" failures="1" errors="0" skipped="0">
              <testcase name="failMe" classname="com.example.BTest">
                <failure message="oops"/>
              </testcase>
            </testsuite>
        """.trimIndent())

        val result = SurefireReportParser.parseProjectReports(tempDir.absolutePath)

        assertEquals(8, result.totalTests)
        assertEquals(7, result.passed)
        assertEquals(1, result.failures)
    }

    @Test
    fun `handles multi-module project`() {
        val module1Dir = File(tempDir, "module-a/target/surefire-reports").apply { mkdirs() }
        File(module1Dir, "TEST-ModA.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite tests="2" failures="0" errors="0" skipped="0"/>
        """.trimIndent())

        val module2Dir = File(tempDir, "module-b/target/surefire-reports").apply { mkdirs() }
        File(module2Dir, "TEST-ModB.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite tests="3" failures="1" errors="0" skipped="0">
              <testcase name="fail" classname="ModB">
                <failure message="bad"/>
              </testcase>
            </testsuite>
        """.trimIndent())

        val result = SurefireReportParser.parseProjectReports(tempDir.absolutePath)

        assertEquals(5, result.totalTests)
        assertEquals(4, result.passed)
        assertEquals(1, result.failures)
    }

    @Test
    fun `returns empty when no reports exist`() {
        val result = SurefireReportParser.parseProjectReports(tempDir.absolutePath)

        assertEquals(0, result.totalTests)
        assertEquals(0, result.passed)
        assertTrue(result.failedTests.isEmpty())
    }

    @Test
    fun `parseDetailedResults returns test case info grouped by suite`() {
        val reportDir = File(tempDir, "target/surefire-reports").apply { mkdirs() }
        File(reportDir, "TEST-com.example.FooTest.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.FooTest" tests="2" failures="1" errors="0" skipped="0" time="0.3">
              <testcase name="testPass" classname="com.example.FooTest" time="0.1"/>
              <testcase name="testFail" classname="com.example.FooTest" time="0.2">
                <failure message="oops" type="AssertionError">stack</failure>
              </testcase>
            </testsuite>
        """.trimIndent())

        val (summary, casesBySuite) = SurefireReportParser.parseDetailedReports(tempDir.absolutePath)

        assertEquals(2, summary.totalTests)
        val cases = casesBySuite["com.example.FooTest"]
        assertNotNull(cases)
        assertEquals(2, cases!!.size)
        assertEquals(TestCaseStatus.PASSED, cases[0].status)
        assertEquals(TestCaseStatus.FAILED, cases[1].status)
        assertEquals("oops", cases[1].failureMessage)
    }
}
