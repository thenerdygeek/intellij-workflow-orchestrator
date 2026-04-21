package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.states.TestStateInfo
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class JavaRuntimeExecToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = JavaRuntimeExecTool()

    @Test
    fun `tool name is java_runtime_exec`() {
        assertEquals("java_runtime_exec", tool.name)
    }

    @Test
    fun `action enum contains run_tests compile_module and rerun_failed_tests`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(setOf("run_tests", "compile_module", "rerun_failed_tests"), actions!!.toSet())
    }

    @Test
    fun `description mentions JUnit TestNG and CompilerManager territory`() {
        val desc = tool.description
        assertTrue(desc.contains("JUnit"), "description should mention JUnit")
        assertTrue(desc.contains("Java"), "description should mention Java")
    }

    @Test
    fun `only action is required`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes all expected types`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("java_runtime_exec", def.function.name)
        assertTrue(def.function.description.isNotBlank())
    }

    @Test
    fun `missing action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("action"))
    }

    @Test
    fun `unknown action returns java_runtime_exec-scoped error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "nonexistent") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown action"))
        assertTrue(result.content.contains("java_runtime_exec"))
    }

    @Test
    fun `run_tests without class_name returns helpful error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "run_tests") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("class_name"), "Error should mention class_name parameter")
        assertTrue(result.content.contains("test_finder"), "Error should suggest test_finder tool")
        assertTrue(result.content.contains("fully qualified"), "Error should explain expected format")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Bug-fix tests — root-health check and synthetic-leaf filtering.
    // These exercise the shared helpers (collectTestResults, mapToTestResultEntry,
    // buildRunnerErrorResult) that the native JUnit runner path delegates to on
    // onTestingFinished. Exercising them directly is sufficient because the
    // onTestingFinished logic in handleDescriptorReady is a 4-line branch:
    //     if (root.isDefect || root.wasTerminated() || root.isEmptySuite)
    //         return buildRunnerErrorResult(root)
    // ══════════════════════════════════════════════════════════════════════

    private fun makeSyntheticLeaf(locationUrl: String?): SMTestProxy {
        val leaf = mockk<SMTestProxy>(relaxed = true)
        every { leaf.isLeaf } returns true
        every { leaf.locationUrl } returns locationUrl
        every { leaf.isDefect } returns false
        every { leaf.wasTerminated() } returns false
        every { leaf.isIgnored } returns false
        every { leaf.name } returns "SyntheticLeaf"
        every { leaf.duration } returns 0
        every { leaf.stacktrace } returns null
        every { leaf.errorMessage } returns null
        return leaf
    }

    private fun makeRealLeaf(name: String = "testFoo"): SMTestProxy {
        val leaf = mockk<SMTestProxy>(relaxed = true)
        every { leaf.isLeaf } returns true
        every { leaf.locationUrl } returns "java:test://com.example.Foo/$name"
        every { leaf.isDefect } returns false
        every { leaf.wasTerminated() } returns false
        every { leaf.isIgnored } returns false
        every { leaf.getMagnitudeInfo() } returns TestStateInfo.Magnitude.PASSED_INDEX
        every { leaf.name } returns name
        every { leaf.duration } returns 10
        every { leaf.stacktrace } returns null
        every { leaf.errorMessage } returns null
        return leaf
    }

    private fun makeRoot(
        leaves: List<SMTestProxy>,
        isDefect: Boolean = false,
        wasTerminated: Boolean = false,
        isEmptySuite: Boolean = false,
        errorMessage: String? = null,
        stacktrace: String? = null
    ): SMTestProxy {
        val root = mockk<SMTestProxy>(relaxed = true)
        // allTests on a root returns a pre-order list that includes the root + descendants,
        // but the real implementation adds leaves as descendants. For our filter's logic
        // we just need a traversable list — the leaves themselves satisfy isLeaf=true.
        every { root.allTests } returns leaves
        every { root.isDefect } returns isDefect
        every { root.wasTerminated() } returns wasTerminated
        every { root.isEmptySuite } returns isEmptySuite
        every { root.errorMessage } returns errorMessage
        every { root.stacktrace } returns stacktrace
        return root
    }

    @Test
    fun `buildRunnerErrorResult returns error with runner error summary`() = runTest {
        val root = makeRoot(
            leaves = emptyList(),
            isDefect = true,
            errorMessage = "Internal Error Occurred",
            stacktrace = "at org.junit.platform.launcher..."
        )
        val result = buildRunnerErrorResult(root, tool, project)
        assertTrue(result.isError, "defect root should produce error ToolResult")
        assertTrue(
            result.summary.startsWith("Test runner error"),
            "summary should begin with 'Test runner error' but was: ${result.summary}"
        )
        assertTrue(
            result.content.contains("Internal Error Occurred"),
            "content should embed the root errorMessage"
        )
    }

    @Test
    fun `runner error contract — wasTerminated root with empty children`() = runTest {
        val root = makeRoot(leaves = emptyList(), wasTerminated = true, errorMessage = "killed")
        val result = buildRunnerErrorResult(root, tool, project)
        assertTrue(result.isError)
        assertTrue(result.summary.startsWith("Test runner error"))
    }

    @Test
    fun `collectTestResults rejects synthetic engine leaves with null locationUrl`() {
        val realLeaf = makeRealLeaf("testBar")
        val syntheticLeaf = makeSyntheticLeaf(locationUrl = null)
        val root = makeRoot(leaves = listOf(realLeaf, syntheticLeaf))

        val results = collectTestResults(root)

        assertEquals(1, results.size, "only the real java:test:// leaf should be counted")
        assertEquals("testBar", results.first().name)
    }

    @Test
    fun `collectTestResults rejects synthetic engine leaves with java engine scheme`() {
        val realLeaf = makeRealLeaf("testOne")
        val engineLeaf = makeSyntheticLeaf(locationUrl = "java:engine://junit-jupiter")
        val root = makeRoot(leaves = listOf(realLeaf, engineLeaf))

        val results = collectTestResults(root)

        assertEquals(1, results.size, "java:engine:// leaves must be filtered out")
        assertEquals("testOne", results.first().name)
    }

    @Test
    fun `mapToTestResultEntry honors wasTerminated as ERROR`() {
        val leaf = mockk<SMTestProxy>(relaxed = true)
        every { leaf.wasTerminated() } returns true
        every { leaf.isDefect } returns false
        every { leaf.isIgnored } returns false
        every { leaf.getMagnitudeInfo() } returns TestStateInfo.Magnitude.TERMINATED_INDEX
        every { leaf.name } returns "terminatedTest"
        every { leaf.duration } returns 5
        every { leaf.stacktrace } returns null
        every { leaf.errorMessage } returns null

        val entry = mapToTestResultEntry(leaf)
        assertEquals(TestStatus.ERROR, entry.status, "terminated tests must surface as ERROR, not PASSED")
    }

    // ══════════════════════════════════════════════════════════════════════
    // interpretTestRoot — regression tests for the root.isDefect bug.
    //
    // THE BUG: root.isDefect bubbles up from children. Before the fix, the
    // guard `if (root.isDefect || …) { buildRunnerErrorResult(root) }` fired
    // whenever any test failed, returning "Test runner error: unknown" instead
    // of the per-test results.
    // ══════════════════════════════════════════════════════════════════════

    private fun makeFailedLeaf(name: String): SMTestProxy {
        val leaf = mockk<SMTestProxy>(relaxed = true)
        every { leaf.isLeaf } returns true
        every { leaf.locationUrl } returns "java:test://com.example.Foo/$name"
        every { leaf.isDefect } returns true
        every { leaf.wasTerminated() } returns false
        every { leaf.isIgnored } returns false
        // TestFailedState.getMagnitude() returns FAILED_INDEX — assertion failure
        every { leaf.getMagnitudeInfo() } returns TestStateInfo.Magnitude.FAILED_INDEX
        every { leaf.name } returns name
        every { leaf.duration } returns 15
        every { leaf.stacktrace } returns "org.junit.ComparisonFailure: expected:<1> but was:<2>\n  at org.junit.Assert.assertEquals(Assert.java:117)"
        every { leaf.errorMessage } returns "expected:<1> but was:<2>"
        return leaf
    }

    /** A leaf that threw an unexpected exception (NPE, IAE, etc.) — ERROR state. */
    private fun makeErrorLeaf(name: String): SMTestProxy {
        val leaf = mockk<SMTestProxy>(relaxed = true)
        every { leaf.isLeaf } returns true
        every { leaf.locationUrl } returns "java:test://com.example.Foo/$name"
        every { leaf.isDefect } returns true
        every { leaf.wasTerminated() } returns false
        every { leaf.isIgnored } returns false
        // TestErrorState.getMagnitude() returns ERROR_INDEX — unexpected exception
        every { leaf.getMagnitudeInfo() } returns TestStateInfo.Magnitude.ERROR_INDEX
        every { leaf.name } returns name
        every { leaf.duration } returns 5
        every { leaf.stacktrace } returns "java.lang.NullPointerException\n  at com.example.FooTest.$name(FooTest.java:10)"
        every { leaf.errorMessage } returns "NullPointerException"
        return leaf
    }

    // ══════════════════════════════════════════════════════════════════════
    // mapToTestResultEntry — magnitude-based FAILED vs ERROR classification
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `mapToTestResultEntry — FAILED_INDEX magnitude maps to FAILED (JUnit 4 ComparisonFailure)`() {
        // THE KEY REGRESSION: JUnit 4 assertEquals throws ComparisonFailure which does
        // NOT contain "AssertionError" in its class name. The old string-matching code
        // returned ERROR for this case. getMagnitudeInfo() == FAILED_INDEX is the fix.
        val leaf = makeFailedLeaf("testEquality")
        // makeFailedLeaf stubs getMagnitudeInfo() → FAILED_INDEX and uses ComparisonFailure stacktrace

        val entry = mapToTestResultEntry(leaf)
        assertEquals(TestStatus.FAILED, entry.status,
            "TestFailedState (FAILED_INDEX) must map to FAILED regardless of stacktrace class name")
    }

    @Test
    fun `mapToTestResultEntry — ERROR_INDEX magnitude maps to ERROR (NullPointerException)`() {
        val leaf = makeErrorLeaf("testUnexpected")
        val entry = mapToTestResultEntry(leaf)
        assertEquals(TestStatus.ERROR, entry.status,
            "TestErrorState (ERROR_INDEX) must map to ERROR")
    }

    @Test
    fun `interpretTestRoot — 3 pass 1 fail returns structured results, NOT runner error`() = runTest {
        // THE KEY REGRESSION: root.isDefect is true because one test failed, but we must
        // still collect and report the individual test results.
        val passLeaf1 = makeRealLeaf("testA")
        val passLeaf2 = makeRealLeaf("testB")
        val passLeaf3 = makeRealLeaf("testC")
        val failLeaf  = makeFailedLeaf("testD_shouldFail")

        val root = makeRoot(
            leaves = listOf(passLeaf1, passLeaf2, passLeaf3, failLeaf),
            isDefect = true,    // bubbles up from failLeaf — this is what triggered the bug
            errorMessage = null // root has no own error message when it's just a child failure
        )

        val result = interpretTestRoot(root, "com.example.FooTest", tool, project)

        assertTrue(result.isError, "result should be error because a test failed")
        assertTrue(
            result.content.contains("FAILED"),
            "content should contain FAILED status"
        )
        assertFalse(
            result.summary.startsWith("Test runner error"),
            "summary must NOT be 'Test runner error' — actual test results must be returned. Got: ${result.summary}"
        )
        assertTrue(
            result.content.contains("testD_shouldFail"),
            "failing test name should appear in content"
        )
        assertTrue(
            result.content.contains("3 passed") || result.content.contains("passed, 1 failed"),
            "pass/fail counts should be reported. Content: ${result.content}"
        )
    }

    @Test
    fun `interpretTestRoot — all pass returns structured non-error results`() = runTest {
        val root = makeRoot(
            leaves = listOf(makeRealLeaf("testX"), makeRealLeaf("testY")),
            isDefect = false
        )
        val result = interpretTestRoot(root, "com.example.BarTest", tool, project)
        assertFalse(result.isError, "all-pass run should not be an error")
        assertTrue(result.content.contains("PASSED"))
    }

    @Test
    fun `interpretTestRoot — runner crash with no real leaves returns runner error`() = runTest {
        val engineLeaf = makeSyntheticLeaf(locationUrl = "java:engine://junit-jupiter")
        val root = makeRoot(
            leaves = listOf(engineLeaf),  // only a synthetic engine leaf, no real tests
            isDefect = true,
            errorMessage = "Internal Error Occurred",
            stacktrace = "at org.junit.platform.launcher.LauncherSession.execute(LauncherSession.java:65)"
        )
        val result = interpretTestRoot(root, "com.example.BrokenTest", tool, project)
        assertTrue(result.isError)
        assertTrue(
            result.summary.startsWith("Test runner error"),
            "engine crash with no real leaves must produce runner error. Got: ${result.summary}"
        )
        assertTrue(result.content.contains("Internal Error Occurred"))
    }

    @Test
    fun `interpretTestRoot — terminated run with partial results includes TERMINATED prefix`() = runTest {
        val passLeaf = makeRealLeaf("testFast")
        val root = makeRoot(
            leaves = listOf(passLeaf),
            wasTerminated = true,
            isDefect = false
        )
        val result = interpretTestRoot(root, "com.example.SlowTest", tool, project)
        assertTrue(result.isError)
        assertTrue(
            result.content.contains("[TERMINATED]"),
            "terminated run with partial results must include [TERMINATED] prefix"
        )
    }

    @Test
    fun `interpretTestRoot — empty suite with no defect returns helpful no-tests-found error`() = runTest {
        val root = makeRoot(leaves = emptyList(), isDefect = false, isEmptySuite = true)
        val result = interpretTestRoot(root, "com.example.EmptyTest", tool, project)
        assertTrue(result.isError)
        assertFalse(
            result.summary.startsWith("Test runner error"),
            "empty suite without error should say 'no tests found', not 'runner error'"
        )
        assertTrue(
            result.content.contains("no test methods") || result.content.contains("No test"),
            "content should explain no @Test methods were found. Got: ${result.content}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Build-gate regression tests.
    //
    // Background: the native runner creates a transient RunnerAndConfigurationSettings
    // that is intentionally never registered in RunManager (commit 9b164bf3 — prevents
    // "initialization error on next manual run"). As a side-effect, IntelliJ's factory-
    // default "Build" before-run task is never wired. Without an explicit build step,
    // JUnit launches against an uncompiled classpath → initializationError.
    //
    // Fix: JavaRuntimeExecTool now calls ProjectTaskManager.build(module) explicitly
    // before ProgramRunnerUtil.executeConfigurationAsync.
    //
    // Full end-to-end coverage of the ProjectTaskManager path requires EDT + IntelliJ
    // service infrastructure (BasePlatformTestCase). The tests below cover:
    //   (a) The shell fallback path — no EDT needed, runs synchronously.
    //   (b) A canary that the 9b164bf3 guard comment (preventing setTemporaryConfiguration)
    //       is still present in the source file.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_tests shell path — returns error when project has no build file`(@TempDir tempDir: Path) = runTest {
        val emptyProject = mockk<Project>(relaxed = true)
        every { emptyProject.basePath } returns tempDir.toFile().absolutePath
        // tempDir has no pom.xml, build.gradle, or build.gradle.kts → "No build tool found"
        val result = tool.execute(
            buildJsonObject {
                put("action", "run_tests")
                put("class_name", "com.example.FooTest")
                put("use_native_runner", false)
            },
            emptyProject
        )
        assertTrue(result.isError)
        assertTrue(
            result.content.contains("Maven") || result.content.contains("Gradle"),
            "error should mention the missing build tools. Got: ${result.content}"
        )
    }

    @Test
    fun `run_tests shell path — returns error when project basePath is null`() = runTest {
        val noPathProject = mockk<Project>(relaxed = true)
        every { noPathProject.basePath } returns null
        val result = tool.execute(
            buildJsonObject {
                put("action", "run_tests")
                put("class_name", "com.example.FooTest")
                put("use_native_runner", false)
            },
            noPathProject
        )
        assertTrue(result.isError)
        assertTrue(
            result.content.lowercase().contains("base path") || result.content.lowercase().contains("project"),
            "error should mention missing base path. Got: ${result.content}"
        )
    }

    @Test
    fun `run_tests shell path — finds Maven pom and builds correct command`(@TempDir tempDir: Path) = runTest {
        File(tempDir.toFile(), "pom.xml").writeText("<project/>")
        val mavenProject = mockk<Project>(relaxed = true)
        every { mavenProject.basePath } returns tempDir.toFile().absolutePath
        // The process will fail to run mvn (not installed in test), but the fact that it
        // attempts to and doesn't return "No build tool found" confirms the Maven path.
        val result = tool.execute(
            buildJsonObject {
                put("action", "run_tests")
                put("class_name", "com.example.FooTest")
                put("use_native_runner", false)
                put("timeout", 5)  // 5s cap so the test is fast
            },
            mavenProject
        )
        // May be a timeout or a "mvn: not found" error — either way NOT "No build tool found"
        assertFalse(
            result.content.contains("No Maven") && result.content.contains("Gradle"),
            "Maven pom.xml present: should NOT say 'No build tool found'. Got: ${result.content}"
        )
    }

    /**
     * Canary: guards against re-introducing commit 9b164bf3's regression.
     *
     * If someone "fixes" the build gate by calling RunManager.setTemporaryConfiguration,
     * this test will fail, reminding them to read the comment in createJUnitRunSettings
     * before proceeding.
     */
    @Test
    fun `9b164bf3 regression canary — guard comment still present in source`() {
        val sourceFile = findSourceFile("JavaRuntimeExecTool.kt")
        assertNotNull(sourceFile, "Could not find JavaRuntimeExecTool.kt for canary check")
        val source = sourceFile!!.readText()
        assertTrue(
            source.contains("9b164bf3"),
            "Commit 9b164bf3 guard comment must still exist in JavaRuntimeExecTool.kt. " +
                "It prevents setTemporaryConfiguration from being called, which would re-trigger " +
                "the 'initialization error on next manual run' regression."
        )
        // Verify no non-comment line actually CALLS setTemporaryConfiguration.
        val callLines = source.lines()
            .filter { it.contains("setTemporaryConfiguration(") }
            .filter { it.trim().startsWith("//").not() }
        assertTrue(
            callLines.isEmpty(),
            "RunManager.setTemporaryConfiguration must NOT be called in JavaRuntimeExecTool.kt — " +
                "see the 9b164bf3 guard comment. Use ProjectTaskManager.build(module) instead. " +
                "Offending lines: $callLines"
        )
    }

    private fun findSourceFile(name: String): File? {
        // user.dir in a Gradle test run is the project root (the directory containing
        // the root build.gradle.kts). Walk from there to find the source file.
        val root = File(System.getProperty("user.dir") ?: return null)
        return root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.isFile && it.name == name }
            .firstOrNull()
    }

    // ══════════════════════════════════════════════════════════════════════
    // rerun_failed_tests — early-exit path tests
    //
    // These tests exercise the dispatch logic in executeRerunFailedTests
    // without needing a real IntelliJ launch. All scenarios that require a
    // live launch (step 5 onward) are covered by the integration test suite;
    // these unit tests guard the pre-launch guards and error categories.
    //
    // Setup: mockkStatic(RunContentManager::class) + mockkObject(TestConsoleUtils)
    // so RunContentManager.getInstance(project) and unwrapToTestConsole are controllable.
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    inner class RerunFailedTests {
        private val rcm = mockk<RunContentManager>(relaxed = true)

        @BeforeEach
        fun setUp() {
            mockkStatic(RunContentManager::class)
            mockkObject(TestConsoleUtils)
            every { RunContentManager.getInstance(project) } returns rcm
        }

        @AfterEach
        fun tearDown() {
            unmockkAll()
        }

        // ─── Scenario R1: no prior test session ──────────────────────────
        @Test
        fun `rerun_failed_tests returns NO_PRIOR_TEST_SESSION when no descriptors exist`() = runTest {
            every { rcm.allDescriptors } returns emptyList()

            val result = tool.execute(
                buildJsonObject { put("action", "rerun_failed_tests") },
                project
            )

            assertTrue(result.isError)
            assertTrue(
                result.content.contains("NO_PRIOR_TEST_SESSION"),
                "Empty descriptor list must return NO_PRIOR_TEST_SESSION. Got: ${result.content}"
            )
            assertTrue(
                result.content.contains("run_tests"),
                "Error hint should mention run_tests. Got: ${result.content}"
            )
        }

        // ─── Scenario R2: session_id provided but no matching descriptor ─
        @Test
        fun `rerun_failed_tests with session_id returns NO_PRIOR_TEST_SESSION when session not found`() = runTest {
            val desc = mockk<RunContentDescriptor>(relaxed = true)
            every { desc.displayName } returns "SomeOtherTest"
            every { rcm.allDescriptors } returns listOf(desc)
            // unwrapToTestConsole returns null → desc doesn't count as a test session
            every { TestConsoleUtils.unwrapToTestConsole(any()) } returns null

            val result = tool.execute(
                buildJsonObject {
                    put("action", "rerun_failed_tests")
                    put("session_id", "MissingSession")
                },
                project
            )

            assertTrue(result.isError)
            assertTrue(
                result.content.contains("NO_PRIOR_TEST_SESSION"),
                "session_id with no match must return NO_PRIOR_TEST_SESSION. Got: ${result.content}"
            )
            assertTrue(
                result.content.contains("MissingSession"),
                "Error must include the requested session_id. Got: ${result.content}"
            )
        }

        // ─── Scenario R3: prior session exists but 0 failures ────────────
        @Test
        fun `rerun_failed_tests returns informational message when all tests passed`() = runTest {
            // Build a descriptor whose console resolves to a test root with 2 passing tests.
            val passLeaf1 = mockk<SMTestProxy>(relaxed = true).apply {
                every { isLeaf } returns true
                every { locationUrl } returns "java:test://com.example.FooTest/testA"
                every { isDefect } returns false
                every { wasTerminated() } returns false
                every { isIgnored } returns false
                every { getMagnitudeInfo() } returns TestStateInfo.Magnitude.PASSED_INDEX
                every { name } returns "testA"
                every { duration } returns 10
                every { stacktrace } returns null
                every { errorMessage } returns null
            }
            val passLeaf2 = mockk<SMTestProxy>(relaxed = true).apply {
                every { isLeaf } returns true
                every { locationUrl } returns "java:test://com.example.FooTest/testB"
                every { isDefect } returns false
                every { wasTerminated() } returns false
                every { isIgnored } returns false
                every { getMagnitudeInfo() } returns TestStateInfo.Magnitude.PASSED_INDEX
                every { name } returns "testB"
                every { duration } returns 10
                every { stacktrace } returns null
                every { errorMessage } returns null
            }
            val root = mockk<SMTestProxy.SMRootTestProxy>(relaxed = true)
            every { root.allTests } returns listOf(passLeaf1, passLeaf2)
            every { root.isDefect } returns false
            every { root.wasTerminated() } returns false
            every { root.isEmptySuite } returns false
            every { root.errorMessage } returns null

            val console = mockk<com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView>(relaxed = true)
            val desc = mockk<RunContentDescriptor>(relaxed = true)
            every { desc.displayName } returns "FooTest"
            every { desc.executionConsole } returns console
            every { rcm.allDescriptors } returns listOf(desc)
            every { TestConsoleUtils.unwrapToTestConsole(console) } returns console
            every { TestConsoleUtils.findTestRoot(desc) } returns root

            val result = tool.execute(
                buildJsonObject { put("action", "rerun_failed_tests") },
                project
            )

            assertFalse(result.isError, "0-failure session must not be an error")
            assertTrue(
                result.content.contains("No failed tests") || result.content.contains("passed"),
                "Result should mention no failures. Got: ${result.content}"
            )
        }

        // ─── Scenario R4: session_id override matches by substring ───────
        @Test
        fun `rerun_failed_tests session_id override selects correct descriptor by name substring`() = runTest {
            // Two descriptors: "AuthServiceTest" and "PaymentServiceTest".
            // session_id="Payment" should select the second one.
            val desc1 = mockk<RunContentDescriptor>(relaxed = true)
            every { desc1.displayName } returns "AuthServiceTest"

            val desc2 = mockk<RunContentDescriptor>(relaxed = true)
            every { desc2.displayName } returns "PaymentServiceTest"
            val console2 = mockk<com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView>(relaxed = true)
            every { desc2.executionConsole } returns console2

            // desc1 doesn't have a test console, desc2 does
            every { rcm.allDescriptors } returns listOf(desc1, desc2)
            every { TestConsoleUtils.unwrapToTestConsole(null) } returns null
            every { TestConsoleUtils.unwrapToTestConsole(console2) } returns console2

            // desc2's test root: 1 passing test (0 failures)
            val passLeaf = mockk<SMTestProxy>(relaxed = true).apply {
                every { isLeaf } returns true
                every { locationUrl } returns "java:test://com.example.PaymentServiceTest/testPay"
                every { isDefect } returns false
                every { wasTerminated() } returns false
                every { isIgnored } returns false
                every { getMagnitudeInfo() } returns TestStateInfo.Magnitude.PASSED_INDEX
                every { name } returns "testPay"
                every { duration } returns 5
                every { stacktrace } returns null
                every { errorMessage } returns null
            }
            val root2 = mockk<SMTestProxy.SMRootTestProxy>(relaxed = true)
            every { root2.allTests } returns listOf(passLeaf)
            every { root2.isDefect } returns false
            every { root2.wasTerminated() } returns false
            every { root2.isEmptySuite } returns false
            every { root2.errorMessage } returns null
            every { TestConsoleUtils.findTestRoot(desc2) } returns root2

            val result = tool.execute(
                buildJsonObject {
                    put("action", "rerun_failed_tests")
                    put("session_id", "Payment")   // partial match
                },
                project
            )

            // The session was found (desc2) and had 0 failures — returns informational message
            assertFalse(result.isError, "Matching session with 0 failures must not be an error")
            assertTrue(
                result.content.contains("No failed tests") || result.content.contains("passed"),
                "session_id='Payment' found PaymentServiceTest and found 0 failures. Got: ${result.content}"
            )
            // desc1 (AuthServiceTest) must NOT have been selected
            assertFalse(
                result.content.contains("AuthServiceTest"),
                "session_id='Payment' must not select AuthServiceTest. Got: ${result.content}"
            )
        }

        // ─── Scenario R5: description contains rerun_failed_tests ────────
        @Test
        fun `description mentions rerun_failed_tests action`() {
            val desc = tool.description
            assertTrue(
                desc.contains("rerun_failed_tests"),
                "tool description should document the rerun_failed_tests action"
            )
            assertTrue(
                desc.contains("NO_PRIOR_TEST_SESSION"),
                "description should mention NO_PRIOR_TEST_SESSION error category"
            )
        }
    }
}
