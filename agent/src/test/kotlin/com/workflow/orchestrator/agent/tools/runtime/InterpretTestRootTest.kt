package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [interpretTestRoot] exercised against REAL [SMTestProxy] trees
 * constructed via the public constructor + `addChild()` + `setStarted/Finished`
 * state transitions.
 *
 * These tests pin the failure-mode matrix documented in
 * `docs/research/2026-04-17-intellij-run-test-execution-contract.md` §6 so a
 * future refactor of [interpretTestRoot] (or the helpers it calls:
 * [collectTestResults], [mapToTestResultEntry], [buildRunnerErrorResult]) can
 * NOT silently regress either of:
 *
 *  - empty-suite → isError=true + "No test methods were found" message
 *  - engine-defect → isError=true + runner-error content
 *
 * Why real proxies and not MockK? `JavaRuntimeExecToolTest` uses MockK stubs
 * that return preset magnitudes/flags. Real proxies verify the interaction
 * between `setTestFailed(..., testError)` → magnitude → our `when` branches,
 * and guarantee our code still works against the actual IntelliJ state model
 * (not our recollection of it).
 *
 * ## Location URL convention
 *
 * `collectTestResults` filters leaves by `locationUrl.startsWith("java:test://")`
 * or `"java:suite://"`. Engine-level nodes (runner crash, initializationError,
 * etc.) carry null or `java:engine://` URLs and must NOT be counted as tests.
 * Scenarios 4 and 6 exercise this filter.
 */
class InterpretTestRootTest {

    // ══════════════════════════════════════════════════════════════════════
    // Helpers — real SMTestProxy tree construction
    // ══════════════════════════════════════════════════════════════════════

    /** Real test leaf with a proper `java:test://` locationUrl so
     *  [collectTestResults] accepts it. */
    private fun realLeaf(name: String, suiteFqn: String = "com.example.MyTest"): SMTestProxy =
        SMTestProxy(name, /* isSuite = */ false, "java:test://$suiteFqn/$name")

    /** A non-test engine node with a `java:engine://` URL — [collectTestResults]
     *  must reject these so the tool doesn't count them as "1 passed". */
    private fun engineLeaf(name: String = "junit-jupiter-engine"): SMTestProxy =
        SMTestProxy(name, /* isSuite = */ false, "java:engine://$name")

    /** Build a suite that mimics what the IntelliJ JUnit runner creates: root
     *  → suite → leaves. */
    private fun buildTree(
        suiteName: String = "com.example.MyTest",
        suiteFqn: String = "com.example.MyTest",
        configureLeaves: (SMTestProxy) -> Unit = {}
    ): SMTestProxy.SMRootTestProxy {
        val root = SMTestProxy.SMRootTestProxy()
        val suite = SMTestProxy(suiteName, /* isSuite = */ true, "java:suite://$suiteFqn")
        root.addChild(suite)
        configureLeaves(suite)
        return root
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 1 — All tests pass
    //
    // 3 leaves, all with setStarted()+setFinished(). Expect PASSED overall,
    // isError=false, summary "PASSED: 3 passed, 0 failed".
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 1 — all tests pass returns PASSED with 3 passed`() {
        val root = buildTree { suite ->
            repeat(3) { i ->
                val leaf = realLeaf("test$i")
                leaf.setStarted()
                leaf.setFinished()
                suite.addChild(leaf)
            }
        }

        val result = interpretTestRoot(root, "MyTestSuite")

        assertFalse(result.isError, "all-pass run must not be an error. content=${result.content}")
        assertTrue(result.content.contains("PASSED"), "content must contain PASSED status. Got: ${result.content}")
        assertTrue(
            result.content.contains("3 passed"),
            "content must report 3 passed. Got: ${result.content}"
        )
        assertTrue(
            result.summary.startsWith("PASSED"),
            "summary must lead with PASSED. Got: ${result.summary}"
        )
        assertTrue(
            result.summary.contains("3 passed"),
            "summary must include count. Got: ${result.summary}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 2 — Some fail
    //
    // 2 pass + 1 setTestFailed(testError=false) → FAILED_INDEX → FAILED.
    // Expect isError=true, content has FAILED section, summary says
    // "FAILED: 2 passed, 1 failed".
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 2 — 2 pass 1 fail returns FAILED with correct counts`() {
        val root = buildTree { suite ->
            // Passing leaves
            listOf("testA", "testB").forEach { name ->
                val leaf = realLeaf(name)
                leaf.setStarted()
                leaf.setFinished()
                suite.addChild(leaf)
            }
            // Failing leaf — testError=false so magnitude is FAILED_INDEX (assertion), not ERROR_INDEX
            val failLeaf = realLeaf("testC_fails")
            failLeaf.setStarted()
            failLeaf.setTestFailed(
                "expected <1> but was <2>",
                "java.lang.AssertionError: expected <1> but was <2>\n\tat com.example.MyTest.testC_fails(MyTest.java:42)",
                /* testError = */ false
            )
            suite.addChild(failLeaf)
        }

        val result = interpretTestRoot(root, "MyTestSuite")

        assertTrue(result.isError, "run with a failure must be an error. summary=${result.summary}")
        assertTrue(result.content.contains("FAILED"), "content must contain FAILED status line. Got: ${result.content}")
        assertTrue(
            result.content.contains("testC_fails"),
            "failing test name must appear. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("2 passed, 1 failed"),
            "content must report 2 passed, 1 failed. Got: ${result.content}"
        )
        assertTrue(
            result.summary.startsWith("FAILED"),
            "summary must lead with FAILED. Got: ${result.summary}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 3 — Empty suite
    //
    // Root with 0 children (no suite, no leaves). interpretTestRoot must
    // return isError=true with content containing "no test methods" /
    // "No test" message — NOT "Test runner error".
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 3 — empty suite returns isError with no test methods message`() {
        val root = SMTestProxy.SMRootTestProxy()
        // No children at all.

        val result = interpretTestRoot(root, "EmptyTestSuite")

        assertTrue(result.isError, "empty suite must be isError=true")
        assertTrue(
            result.content.contains("no test methods", ignoreCase = true) ||
                result.content.contains("No test", ignoreCase = true),
            "content must say no test methods were found. Got: ${result.content}"
        )
        assertFalse(
            result.summary.startsWith("Test runner error"),
            "empty suite must NOT be reported as 'Test runner error'. Got: ${result.summary}"
        )
        assertTrue(
            result.summary.contains("No tests found", ignoreCase = true),
            "summary should say 'No tests found'. Got: ${result.summary}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 4 — Engine defect
    //
    // Root has 1 child with `java:engine://` URL (synthetic — collectTestResults
    // rejects it) and root itself is marked defective via setTestFailed(...,
    // testError=true). With 0 real leaves and root.isDefect=true → runner error.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 4 — engine defect with no real leaves returns Test runner error`() {
        val root = SMTestProxy.SMRootTestProxy()
        // Engine-scheme "leaf" — our filter must reject this.
        root.addChild(engineLeaf("junit-jupiter-engine"))
        // Mark the root itself as defective — simulates an engine-level failure
        // (class not found, JUnit 5 classpath mismatch, etc.) where nothing ever
        // got scheduled.
        root.setTestFailed(
            "Engine failed to start: ClassNotFoundException com.example.FooTest",
            "java.lang.ClassNotFoundException: com.example.FooTest\n\tat org.junit.platform.launcher.LauncherSession.execute(LauncherSession.java:65)",
            /* testError = */ true
        )

        val result = interpretTestRoot(root, "BrokenTestSuite")

        assertTrue(result.isError, "engine defect must be isError=true")
        assertTrue(
            result.content.contains("Test runner error:"),
            "content must lead with 'Test runner error:'. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("Engine failed to start"),
            "content must embed the root's errorMessage. Got: ${result.content}"
        )
        assertTrue(
            result.summary.startsWith("Test runner error"),
            "summary must start with 'Test runner error'. Got: ${result.summary}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 5 — Runner terminated
    //
    // 2 leaves have completed, then root.setTerminated() is called (simulates
    // watchdog kill / user cancellation mid-run). interpretTestRoot must
    // prepend [TERMINATED] and set isError=true, but still show partial
    // results so the LLM sees what DID run.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 5 — terminated with partial results prepends TERMINATED`() {
        val root = buildTree { suite ->
            listOf("testFast1", "testFast2").forEach { name ->
                val leaf = realLeaf(name)
                leaf.setStarted()
                leaf.setFinished()
                suite.addChild(leaf)
            }
        }
        root.setTerminated()

        val result = interpretTestRoot(root, "TimeoutTestSuite")

        assertTrue(result.isError, "terminated run must be isError=true")
        assertTrue(
            result.content.startsWith("[TERMINATED]"),
            "content must start with [TERMINATED] prefix. Got: ${result.content.lines().firstOrNull()}"
        )
        assertTrue(
            result.content.contains("Test run was killed"),
            "content must explain that the run was killed. Got: ${result.content}"
        )
        // Partial results still included so the LLM sees what DID run.
        assertTrue(
            result.content.contains("testFast1") || result.content.contains("2 passed"),
            "partial results must still be embedded. Got: ${result.content}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 6 — Initialization error
    //
    // Mimics IntelliJ's own behavior: JUnit 4's RunListener reports an
    // "initializationError" leaf when the test class can't be loaded. It is
    // NOT a real test — it has no `java:test://` URL (usually null) — so
    // collectTestResults rejects it. Then root.isDefect=true triggers the
    // runner-error path.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 6 — initializationError leaf falls through to runner error`() {
        val root = SMTestProxy.SMRootTestProxy()
        // Mimic a classic JUnit 4 initializationError leaf — null location URL.
        val initErrorLeaf = SMTestProxy("initializationError", /* isSuite = */ false, /* locationUrl = */ null)
        initErrorLeaf.setTestFailed(
            "java.lang.Exception: No runnable methods",
            "java.lang.Exception: No runnable methods\n\tat org.junit.runners.BlockJUnit4ClassRunner.validateInstanceMethods",
            /* testError = */ true
        )
        root.addChild(initErrorLeaf)
        // Root surfaces the defect so isDefect=true.
        root.setTestFailed(
            "initializationError: No runnable methods",
            "stack trace",
            /* testError = */ true
        )

        val result = interpretTestRoot(root, "BrokenTestSuite")

        assertTrue(result.isError, "initializationError must be isError=true")
        assertTrue(
            result.summary.startsWith("Test runner error"),
            "summary must be 'Test runner error'. Got: ${result.summary}"
        )
        assertTrue(
            result.content.contains("Test runner error:"),
            "content must carry the runner-error lead. Got: ${result.content}"
        )
        // The initializationError "leaf" must NOT be counted as a passing test.
        assertFalse(
            result.content.contains("1 passed"),
            "initializationError must NOT be surfaced as a passed test. Got: ${result.content}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 7 — Comparison failure
    //
    // `setTestComparisonFailed` is the JUnit/Surefire API call for
    // `assertEquals(expected, actual)` mismatches. It creates a
    // `TestComparisonFailedState` whose magnitude is FAILED_INDEX (extends
    // TestFailedState), so our helper maps it to TestStatus.FAILED.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 7 — setTestComparisonFailed produces FAILED status with message`() {
        val root = buildTree { suite ->
            val passLeaf = realLeaf("testOk")
            passLeaf.setStarted()
            passLeaf.setFinished()
            suite.addChild(passLeaf)

            val cmpLeaf = realLeaf("testCompare")
            cmpLeaf.setStarted()
            cmpLeaf.setTestComparisonFailed(
                /* localizedMessage = */ "expected:<hello> but was:<world>",
                /* stackTrace      = */ "org.junit.ComparisonFailure: expected:<hello> but was:<world>\n\tat org.junit.Assert.assertEquals(Assert.java:117)\n\tat com.example.MyTest.testCompare(MyTest.java:15)",
                /* actualText      = */ "world",
                /* expectedText    = */ "hello"
            )
            suite.addChild(cmpLeaf)
        }

        val result = interpretTestRoot(root, "CompareTestSuite")

        assertTrue(result.isError, "comparison failure must be isError=true")
        assertTrue(
            result.content.contains("FAILED"),
            "content must contain FAILED status line. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("testCompare"),
            "failing test name must appear. Got: ${result.content}"
        )
        // errorMessage/assertion text should appear — extracted from the comparison message.
        assertTrue(
            result.content.contains("expected:<hello>") ||
                result.content.contains("expected") && result.content.contains("hello"),
            "comparison message must be surfaced in content. Got: ${result.content}"
        )
        // Counts confirm the comparison failure is counted as FAILED, not ERROR/PASSED.
        assertTrue(
            result.content.contains("1 passed, 1 failed"),
            "must report 1 passed, 1 failed. Got: ${result.content}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 8 — Parameterized tests
    //
    // JUnit parameterized tests share a method name but each invocation
    // gets a unique `[param]` suffix in the node name (and a unique
    // locationUrl). All three must appear in the passed block and the
    // MAX_PASSED_SHOWN=20 cap must not kick in for 3 entries.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 8 — parameterized tests with same base name are all included`() {
        val paramSuffixes = listOf("[a]", "[b]", "[c]")
        val root = buildTree(suiteName = "ParamTest", suiteFqn = "com.example.ParamTest") { suite ->
            for ((i, suffix) in paramSuffixes.withIndex()) {
                val name = "test_param_${i + 1}$suffix"
                val leaf = SMTestProxy(
                    name,
                    /* isSuite = */ false,
                    "java:test://com.example.ParamTest/test_param_${i + 1}$suffix"
                )
                leaf.setStarted()
                leaf.setFinished()
                suite.addChild(leaf)
            }
        }

        val result = interpretTestRoot(root, "ParamTestSuite")

        assertFalse(result.isError, "all parameterized passes must not be an error. content=${result.content}")
        assertTrue(
            result.content.contains("3 passed"),
            "all 3 parameterized invocations must be counted as passed. Got: ${result.content}"
        )
        // Each unique suffixed name must appear in the passed block.
        for ((i, suffix) in paramSuffixes.withIndex()) {
            val name = "test_param_${i + 1}$suffix"
            assertTrue(
                result.content.contains(name),
                "parameterized entry '$name' must appear in passed block. Got: ${result.content}"
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Sanity: statusFilter PASSED against a run that had a failure should
    // still report the failure via isError + summary (filtering is a
    // presentation concern only). Pins the Task 1.2 contract.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `statusFilter PASSED against a failed run still reports failure in summary`() {
        val root = buildTree { suite ->
            val passLeaf = realLeaf("testA")
            passLeaf.setStarted()
            passLeaf.setFinished()
            suite.addChild(passLeaf)

            val failLeaf = realLeaf("testB")
            failLeaf.setStarted()
            failLeaf.setTestFailed(
                "boom",
                "java.lang.AssertionError: boom\n\tat com.example.MyTest.testB(MyTest.java:10)",
                /* testError = */ false
            )
            suite.addChild(failLeaf)
        }

        val result = interpretTestRoot(root, "MixedRun", statusFilter = "PASSED")

        assertTrue(result.isError, "failure-in-run still isError even when filtering PASSED")
        assertTrue(
            result.summary.startsWith("FAILED"),
            "summary must reflect TRUE overall status, not the filter. Got: ${result.summary}"
        )
        // Passed block should be present (filtered in)
        assertTrue(result.content.contains("testA"), "passed test must appear. Got: ${result.content}")
    }

    // Sanity: count of entries returned from collectTestResults when tree has
    // engine-scheme leaves mixed with real leaves — ensures our scenarios 4
    // and 6 are actually exercising the filter, not just the defect flag.
    @Test
    fun `collectTestResults rejects java_engine scheme leaves`() {
        val root = SMTestProxy.SMRootTestProxy()
        val suite = SMTestProxy("s", true, "java:suite://Fake")
        root.addChild(suite)
        val real = realLeaf("realTest")
        real.setStarted()
        real.setFinished()
        suite.addChild(real)
        suite.addChild(engineLeaf("junit-jupiter"))

        val results = collectTestResults(root)
        assertEquals(1, results.size, "engine leaves must be filtered out; only realTest should remain")
        assertEquals("realTest", results.first().name)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario P1 — pytest happy path
    //
    // 3 leaves with python:// URLs, all passed. Must FAIL now because
    // collectTestResults rejects python:// URLs → interpretTestRoot sees 0
    // leaves → returns NO_TESTS_FOUND / runner error.
    // Will PASS after Task 3.4 extends the URL filter to accept python://.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario P1 — pytest happy path python-scheme leaves are collected`() {
        val root = SMTestProxy.SMRootTestProxy()
        val suite = SMTestProxy("tests/test_sample.py", true, "file://tests/test_sample.py")
        root.addChild(suite)
        listOf("test_one", "test_two", "test_three").forEach { name ->
            val leaf = SMTestProxy(name, false, "python://tests/test_sample.py::$name")
            leaf.setStarted()
            leaf.setFinished()
            suite.addChild(leaf)
        }
        val result = interpretTestRoot(root, "pytest")
        assertFalse(result.isError, "pytest happy path must not be isError. content=${result.content}")
        assertTrue(result.content.contains("3 passed"), "must report 3 passed. Got: ${result.content}")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario P2 — pytest collection error (_collect suffix)
    //
    // 1 leaf whose name ends in _collect (teamcity-messages protocol for
    // collection errors). isDefect=true. Must surface as FAILED (or error)
    // not as runner-error/empty-suite. Must FAIL now because python:// URL
    // is rejected and the leaf is never counted.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario P2 — pytest collection error _collect leaf is collected as failure`() {
        val root = SMTestProxy.SMRootTestProxy()
        val suite = SMTestProxy("tests/test_foo.py", true, "file://tests/test_foo.py")
        root.addChild(suite)
        val collectLeaf = SMTestProxy(
            "tests/test_foo.py::test_bar_collect",
            false,
            "python://tests/test_foo.py::test_bar_collect"
        )
        collectLeaf.setStarted()
        collectLeaf.setTestFailed(
            "ImportError: cannot import name 'missing_dep' from 'conftest'",
            "ImportError: cannot import name 'missing_dep'\n\tat conftest.py:3",
            true
        )
        suite.addChild(collectLeaf)
        val result = interpretTestRoot(root, "pytest")
        assertTrue(result.isError, "collection error must be isError=true. content=${result.content}")
        assertTrue(
            result.content.contains("test_bar_collect") || result.content.contains("collection"),
            "collection error test name must appear or 'collection' referenced. Got: ${result.content}"
        )
        // Must NOT fall through to the runner-error / empty-suite path — the _collect leaf IS a real result.
        assertFalse(
            result.content.contains("Test runner error:") && !result.content.contains("test_bar_collect"),
            "must not surface as opaque runner error without the test name. Got: ${result.content}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario P3 — pytest fixture setup failure
    //
    // 1 leaf that fails due to a missing fixture. Must surface as ERROR
    // with the test name and "test setup failed" message visible. Must FAIL
    // now because python:// URL is rejected → empty leaf list → runner error
    // without the test name.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario P3 — pytest fixture setup failure surfaces as ERROR with setup failed message`() {
        val root = SMTestProxy.SMRootTestProxy()
        val suite = SMTestProxy("tests/test_conftest.py", true, "file://tests/test_conftest.py")
        root.addChild(suite)
        val fixtureLeaf = SMTestProxy(
            "test_uses_fixture",
            false,
            "python://tests/test_conftest.py::test_uses_fixture"
        )
        fixtureLeaf.setStarted()
        fixtureLeaf.setTestFailed(
            "test setup failed: fixture 'broken_fixture' not found",
            "fixture 'broken_fixture' not found\n\tat conftest.py:10",
            true
        )
        suite.addChild(fixtureLeaf)
        val result = interpretTestRoot(root, "pytest")
        assertTrue(result.isError, "fixture setup failure must be isError=true. content=${result.content}")
        assertTrue(
            result.content.contains("test_uses_fixture"),
            "fixture failure test name must appear. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("test setup failed") || result.content.contains("fixture"),
            "setup failure message must appear. Got: ${result.content}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario P4 — pytest empty root (exit code 5 equivalent)
    //
    // Root with no children — pytest --collect-only found nothing. Must
    // return isError=true with a "no tests found" message. This scenario
    // may already PASS because the empty-root path in interpretTestRoot is
    // not URL-scheme-dependent; that is acceptable for the RED phase.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `scenario P4 — pytest empty root returns no tests found`() {
        val root = SMTestProxy.SMRootTestProxy()
        // No children at all — pytest --collect-only found nothing (exit code 5).
        val result = interpretTestRoot(root, "pytest")
        assertTrue(result.isError, "empty pytest root must be isError=true")
        assertTrue(
            result.content.contains("no test methods", ignoreCase = true) ||
                result.content.contains("No test", ignoreCase = true),
            "content must say no tests were found. Got: ${result.content}"
        )
    }
}
