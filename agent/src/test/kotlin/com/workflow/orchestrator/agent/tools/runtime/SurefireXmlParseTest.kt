package com.workflow.orchestrator.agent.tools.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Unit tests for [parseJUnitXmlReports] — the Surefire/Gradle XML report parser
 * used by the shell fallback path of run_tests to turn exit-code pass/fail into
 * structured per-test results.
 *
 * Test fixtures live in `src/test/resources/surefire-samples/` so the XML is
 * actually well-formed by a real Surefire/Failsafe shape. Each test copies the
 * chosen fixtures into a `@TempDir` under the expected `target/surefire-reports`
 * or `build/test-results/test` layout, then runs the parser.
 */
class SurefireXmlParseTest {

    // ══════════════════════════════════════════════════════════════════════
    // Fixture loading helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun loadFixture(name: String): String {
        val stream = this::class.java.classLoader.getResourceAsStream("surefire-samples/$name")
            ?: error("fixture '$name' not found on classpath — check src/test/resources/surefire-samples/")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun layoutMavenReport(moduleDir: File, fixtureName: String, outputFileName: String = "TEST-com.example.SampleTest.xml") {
        val reportsDir = File(moduleDir, "target/surefire-reports")
        reportsDir.mkdirs()
        File(reportsDir, outputFileName).writeText(loadFixture(fixtureName))
    }

    private fun layoutMavenFailsafeReport(moduleDir: File, fixtureName: String, outputFileName: String = "TEST-com.example.SampleIT.xml") {
        val reportsDir = File(moduleDir, "target/failsafe-reports")
        reportsDir.mkdirs()
        File(reportsDir, outputFileName).writeText(loadFixture(fixtureName))
    }

    private fun layoutGradleReport(
        moduleDir: File,
        fixtureName: String,
        taskDir: String = "test",
        outputFileName: String = "TEST-com.example.SampleTest.xml"
    ) {
        val reportsDir = File(moduleDir, "build/test-results/$taskDir")
        reportsDir.mkdirs()
        File(reportsDir, outputFileName).writeText(loadFixture(fixtureName))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Happy path — Maven: 3 tests, 2 pass + 1 fail
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `maven simple-pass-fail returns 3 entries with right statuses`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        layoutMavenReport(moduleDir, "simple-pass-fail.xml")

        val entries = parseJUnitXmlReports(moduleDir, "maven")
        assertNotNull(entries)
        assertEquals(3, entries!!.size)

        val byName = entries.associateBy { it.name }
        assertEquals(TestStatus.PASSED, byName["com.example.SimpleTest.testAdd"]?.status)
        assertEquals(TestStatus.PASSED, byName["com.example.SimpleTest.testSubtract"]?.status)

        val failed = byName["com.example.SimpleTest.testMultiply"]
        assertNotNull(failed)
        assertEquals(TestStatus.FAILED, failed!!.status)
        assertTrue(failed.errorMessage?.contains("expected:") == true, "errorMessage should carry <failure> message")
        assertTrue(failed.stackTrace.isNotEmpty(), "stackTrace must be populated from failure text")
        assertTrue(
            failed.stackTrace.size <= MAX_STACK_FRAMES,
            "stackTrace should be capped at MAX_STACK_FRAMES ($MAX_STACK_FRAMES) but was ${failed.stackTrace.size}"
        )
    }

    @Test
    fun `maven durationMs converted from seconds correctly`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        layoutMavenReport(moduleDir, "simple-pass-fail.xml")

        val entries = parseJUnitXmlReports(moduleDir, "maven")!!
        val testAdd = entries.first { it.name.endsWith("testAdd") }
        // fixture says time="0.101" → 101ms
        assertEquals(101L, testAdd.durationMs)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Empty suite — returns empty list (NOT null)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `maven empty suite returns empty list not null`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        layoutMavenReport(moduleDir, "empty-suite.xml")

        val entries = parseJUnitXmlReports(moduleDir, "maven")
        assertNotNull(entries, "reports dir exists → must return non-null (empty list) not null")
        assertTrue(entries!!.isEmpty(), "empty-suite fixture has tests=0 → entry list must be empty")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mixed pass/skip/error — including <testsuites> wrapper shape
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `maven mixed-pass-skip-error parses all 4 cases regardless of testsuites wrapper`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        layoutMavenReport(moduleDir, "mixed-pass-skip-error.xml")

        val entries = parseJUnitXmlReports(moduleDir, "maven")!!
        assertEquals(4, entries.size)

        val byName = entries.associateBy { it.name }
        assertEquals(TestStatus.PASSED, byName["com.example.MixedTest.testHappyPath"]?.status)
        assertEquals(TestStatus.SKIPPED, byName["com.example.MixedTest.testDisabled"]?.status)
        assertEquals("Disabled until bug X is fixed", byName["com.example.MixedTest.testDisabled"]?.errorMessage)
        assertEquals(TestStatus.SKIPPED, byName["com.example.MixedTest.testAlwaysSkipped"]?.status)
        assertEquals(TestStatus.ERROR, byName["com.example.MixedTest.testCrashes"]?.status)
        assertTrue(
            byName["com.example.MixedTest.testCrashes"]?.errorMessage?.contains("NullPointerException") == true,
            "<error> message attr should be surfaced"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Malformed XML — skipped, does not crash
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `malformed xml is skipped and returns null when it is the only file`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        layoutMavenReport(moduleDir, "malformed.xml")

        val entries = parseJUnitXmlReports(moduleDir, "maven")
        // Single malformed file: every file failed → caller treats as "no reports"
        assertNull(entries, "when every report file is malformed, parser returns null")
    }

    @Test
    fun `malformed xml is skipped but good files still parse`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        val reportsDir = File(moduleDir, "target/surefire-reports").apply { mkdirs() }
        File(reportsDir, "TEST-com.example.Good.xml").writeText(loadFixture("simple-pass-fail.xml"))
        File(reportsDir, "TEST-com.example.Broken.xml").writeText(loadFixture("malformed.xml"))

        val entries = parseJUnitXmlReports(moduleDir, "maven")
        assertNotNull(entries)
        assertEquals(3, entries!!.size, "good file's 3 testcases parsed; malformed file skipped")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Missing directory — null
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `maven with no target directory at all returns null`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        // no target/ dir created

        val entries = parseJUnitXmlReports(moduleDir, "maven")
        assertNull(entries)
    }

    @Test
    fun `gradle with no build directory at all returns null`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        // no build/ dir created

        val entries = parseJUnitXmlReports(moduleDir, "gradle")
        assertNull(entries)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Failsafe (maven IT tests) — included alongside surefire reports
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `maven failsafe reports are combined with surefire`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        layoutMavenReport(moduleDir, "simple-pass-fail.xml")       // 3 tests
        layoutMavenFailsafeReport(moduleDir, "mixed-pass-skip-error.xml") // 4 tests

        val entries = parseJUnitXmlReports(moduleDir, "maven")
        assertNotNull(entries)
        assertEquals(7, entries!!.size, "maven parser should combine surefire + failsafe reports")
    }

    @Test
    fun `maven with only failsafe dir (no surefire) still returns results`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        layoutMavenFailsafeReport(moduleDir, "simple-pass-fail.xml")

        val entries = parseJUnitXmlReports(moduleDir, "maven")
        assertNotNull(entries)
        assertEquals(3, entries!!.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Gradle — build/test-results/{task}/TEST-*.xml
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `gradle scans all subdirectories under test-results`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        layoutGradleReport(moduleDir, "simple-pass-fail.xml", taskDir = "test")
        layoutGradleReport(moduleDir, "mixed-pass-skip-error.xml", taskDir = "integrationTest")

        val entries = parseJUnitXmlReports(moduleDir, "gradle")
        assertNotNull(entries)
        assertEquals(7, entries!!.size, "gradle parser should walk every test-results subdir")
    }

    @Test
    fun `gradle empty suite returns empty list`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        layoutGradleReport(moduleDir, "empty-suite.xml")

        val entries = parseJUnitXmlReports(moduleDir, "gradle")
        assertNotNull(entries)
        assertTrue(entries!!.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    // XXE hardening — DOCTYPE declaration must be rejected (or at least
    // not load remote entities). We use an XXE-bomb-lite that declares a
    // local file entity; a hardened parser should throw (SAX error) rather
    // than silently resolve it. We assert the parser returns null (all files
    // rejected), NOT crash the test VM.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `xxe doctype declaration is rejected`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        val reportsDir = File(moduleDir, "target/surefire-reports").apply { mkdirs() }
        val xxePayload = """<?xml version="1.0"?>
            |<!DOCTYPE foo [
            |  <!ENTITY xxe SYSTEM "file:///etc/passwd">
            |]>
            |<testsuite name="x" tests="1" failures="0" errors="0" skipped="0" time="0.1">
            |  <testcase name="t" classname="x" time="0.1">&xxe;</testcase>
            |</testsuite>
            |""".trimMargin()
        File(reportsDir, "TEST-xxe.xml").writeText(xxePayload)

        val entries = parseJUnitXmlReports(moduleDir, "maven")
        // Disallow-DOCTYPE feature should make the parser throw on this file;
        // since it is the only file, parser returns null.
        assertNull(entries, "XML files with DOCTYPE must be rejected to prevent XXE")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Non-matching file names are ignored
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `non-TEST-prefixed files under reports dir are ignored`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        val reportsDir = File(moduleDir, "target/surefire-reports").apply { mkdirs() }
        // Surefire writes .txt summaries alongside TEST-*.xml — those must not be parsed
        File(reportsDir, "com.example.Good.txt").writeText("Some text, not XML")
        File(reportsDir, "TEST-com.example.Good.xml").writeText(loadFixture("simple-pass-fail.xml"))

        val entries = parseJUnitXmlReports(moduleDir, "maven")
        assertNotNull(entries)
        assertEquals(3, entries!!.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Empty reports directory — null (no reports to parse)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `maven reports dir exists but has no TEST files returns null`(@TempDir temp: Path) {
        val moduleDir = temp.toFile()
        File(moduleDir, "target/surefire-reports").mkdirs()

        val entries = parseJUnitXmlReports(moduleDir, "maven")
        assertNull(entries, "dir exists but no TEST-*.xml files → null (treated as 'no reports')")
        assertFalse(false) // sanity
    }
}
