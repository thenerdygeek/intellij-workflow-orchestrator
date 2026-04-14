package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.states.TestStateInfo
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JavaRuntimeExecToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = JavaRuntimeExecTool()

    @Test
    fun `tool name is java_runtime_exec`() {
        assertEquals("java_runtime_exec", tool.name)
    }

    @Test
    fun `action enum contains only run_tests and compile_module`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(setOf("run_tests", "compile_module"), actions!!.toSet())
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
    fun `buildRunnerErrorResult returns error with runner error summary`() {
        val root = makeRoot(
            leaves = emptyList(),
            isDefect = true,
            errorMessage = "Internal Error Occurred",
            stacktrace = "at org.junit.platform.launcher..."
        )
        val result = buildRunnerErrorResult(root)
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
    fun `runner error contract — wasTerminated root with empty children`() {
        val root = makeRoot(leaves = emptyList(), wasTerminated = true, errorMessage = "killed")
        val result = buildRunnerErrorResult(root)
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
    fun `interpretTestRoot — 3 pass 1 fail returns structured results, NOT runner error`() {
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

        val result = interpretTestRoot(root, "com.example.FooTest")

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
    fun `interpretTestRoot — all pass returns structured non-error results`() {
        val root = makeRoot(
            leaves = listOf(makeRealLeaf("testX"), makeRealLeaf("testY")),
            isDefect = false
        )
        val result = interpretTestRoot(root, "com.example.BarTest")
        assertFalse(result.isError, "all-pass run should not be an error")
        assertTrue(result.content.contains("PASSED"))
    }

    @Test
    fun `interpretTestRoot — runner crash with no real leaves returns runner error`() {
        val engineLeaf = makeSyntheticLeaf(locationUrl = "java:engine://junit-jupiter")
        val root = makeRoot(
            leaves = listOf(engineLeaf),  // only a synthetic engine leaf, no real tests
            isDefect = true,
            errorMessage = "Internal Error Occurred",
            stacktrace = "at org.junit.platform.launcher.LauncherSession.execute(LauncherSession.java:65)"
        )
        val result = interpretTestRoot(root, "com.example.BrokenTest")
        assertTrue(result.isError)
        assertTrue(
            result.summary.startsWith("Test runner error"),
            "engine crash with no real leaves must produce runner error. Got: ${result.summary}"
        )
        assertTrue(result.content.contains("Internal Error Occurred"))
    }

    @Test
    fun `interpretTestRoot — terminated run with partial results includes TERMINATED prefix`() {
        val passLeaf = makeRealLeaf("testFast")
        val root = makeRoot(
            leaves = listOf(passLeaf),
            wasTerminated = true,
            isDefect = false
        )
        val result = interpretTestRoot(root, "com.example.SlowTest")
        assertTrue(result.isError)
        assertTrue(
            result.content.contains("[TERMINATED]"),
            "terminated run with partial results must include [TERMINATED] prefix"
        )
    }

    @Test
    fun `interpretTestRoot — empty suite with no defect returns helpful no-tests-found error`() {
        val root = makeRoot(leaves = emptyList(), isDefect = false, isEmptySuite = true)
        val result = interpretTestRoot(root, "com.example.EmptyTest")
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
}
