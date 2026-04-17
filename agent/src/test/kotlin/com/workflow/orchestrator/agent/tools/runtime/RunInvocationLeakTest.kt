package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellableContinuation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Phase 3 / Task 2.7 — RED regression test for the IDE-state-leak fix plan.
 *
 * Every call to `run_tests` / `run_with_coverage` currently attaches three things
 * that are never detached:
 *
 *   1. `TestResultsViewer.EventsListener` — added in
 *      [JavaRuntimeExecTool.handleDescriptorReady] (≈L569) and in
 *      [CoverageTool.executeRunWithCoverage] (≈L203). `TestResultsViewer` is
 *      `Disposable`; there is NO `removeEventsListener` API. The documented
 *      release mechanism is `Disposer.register(resultsViewer, childDisposable)`
 *      so the listener becomes unreachable when the child Disposable is
 *      disposed. Neither tool does this today — hence the leak.
 *
 *   2. `ProcessListener` (streaming `ProcessAdapter`) — added via the 1-arg
 *      `handler.addProcessListener(ProcessListener)` (≈L556 & L598 in
 *      `JavaRuntimeExecTool`, ≈L212 in `CoverageTool`). `ProcessHandler`
 *      exposes a 2-arg `addProcessListener(ProcessListener, Disposable)` AND an
 *      explicit `removeProcessListener(ProcessListener)` — neither is used.
 *
 *   3. `RunContentDescriptor` — created by the JUnit/coverage runner, pushed
 *      into `RunContentManager`, captured in `descriptorRef`, but never removed
 *      via `ExecutionManager.getInstance(project).contentManager
 *           .removeRunContent(executor, descriptor)` at run completion.
 *
 * Plus two raw `Thread { … }` spawns whose closures retain strong references to
 * `continuation`, `descriptor`, and `MessageBusConnection`:
 * `build-watchdog-timeout` (≈L437) and `test-tree-finalize` (≈L583).
 *
 * ### TDD: this test FAILS on the current HEAD
 *
 * Tasks 2.1–2.6 will introduce a `RunInvocation` abstraction whose
 * `Disposer.dispose()` is responsible for detaching every listener and
 * releasing the descriptor. This test locks in that contract.
 *
 * Validation command (run by the caller after the refactor):
 *     ./gradlew :agent:test --tests "*RunInvocation*" --tests "*Leak*"
 *
 * If these assertions pass before the refactor, the assertions aren't actually
 * catching the leak — investigate before weakening them.
 */
class RunInvocationLeakTest {

    private val project = mockk<Project>(relaxed = true)

    // Counters for the ProcessListener runtime test ———————————————————————
    private val processListenerAddCount = AtomicInteger(0)
    private val processListenerRemoveCount = AtomicInteger(0)
    private val capturedProcessListener = AtomicReference<ProcessListener?>(null)

    @BeforeEach
    fun setUp() {
        processListenerAddCount.set(0)
        processListenerRemoveCount.set(0)
        capturedProcessListener.set(null)

        // Stub TestConsoleUtils.unwrapToTestConsole so the tool's code path reaches
        // our mock SMTRunnerConsoleView instead of wrestling real unwrap reflection.
        mockkObject(TestConsoleUtils)

        // The 1-arg addProcessListener branch only fires when currentToolCallId is
        // set (see handleDescriptorReady ≈L555). Prime the ThreadLocal.
        RunCommandTool.currentToolCallId.set("leak-test-tool-call-id")
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(TestConsoleUtils)
        RunCommandTool.currentToolCallId.remove()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ProcessListener disposal — RUNTIME test.
    // Drives handleDescriptorReady on a mock descriptor. Counts addProcessListener
    // calls, then counts removeProcessListener calls after the terminal event
    // (onTestingFinished) that RunInvocation.dispose() should trigger.
    // Pre-fix: added >= 1, removed = 0 → FAIL.
    // Post-fix: added == removed → PASS.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_tests — every attached ProcessListener must be removed by terminal notification`() {
        val tool = JavaRuntimeExecTool()
        val descriptor = buildMockDescriptor()
        val continuation = stubContinuation()

        tool.handleDescriptorReady(
            descriptor,
            continuation,
            "com.example.FooTest",
            AtomicReference(null),
            AtomicReference(null),
            project
        )

        assertTrue(
            processListenerAddCount.get() >= 1,
            "Precondition: handleDescriptorReady must attach at least one streaming " +
                "ProcessListener when currentToolCallId is set (got ${processListenerAddCount.get()})"
        )

        // Simulate the terminal condition that should trigger RunInvocation.dispose().
        // The captured ProcessListener is notified that the process has terminated.
        // In a properly-disposed implementation, that triggers removeProcessListener.
        val terminalEvent = mockk<com.intellij.execution.process.ProcessEvent>(relaxed = true)
        every { terminalEvent.exitCode } returns 0
        capturedProcessListener.get()?.processTerminated(terminalEvent)

        assertEquals(
            processListenerAddCount.get(),
            processListenerRemoveCount.get(),
            "ProcessListener leak: added=${processListenerAddCount.get()}, " +
                "removed=${processListenerRemoveCount.get()}. " +
                "Every addProcessListener MUST be matched by a removeProcessListener " +
                "(or use the 2-arg Disposable form) via RunInvocation.dispose() — " +
                "see Phase 3 plan Task 2.1–2.4."
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EventsListener disposal — SOURCE-TEXT test.
    //
    // TestResultsViewer.addEventsListener has no symmetric removeEventsListener
    // API (TestResultsViewer IS Disposable). The documented release contract is
    // Disposer.register(resultsViewer, childDisposable). We assert structural
    // evidence that the tool wires the EventsListener into a Disposer chain or
    // the forthcoming RunInvocation abstraction.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `JavaRuntimeExecTool — TestResultsViewer EventsListener must be tied to a Disposer or RunInvocation`() {
        val text = readSource("JavaRuntimeExecTool.kt")
        assertTrue(
            text.contains("addEventsListener"),
            "Precondition: JavaRuntimeExecTool must attach a TestResultsViewer.EventsListener " +
                "(the leak site — see handleDescriptorReady ≈L569)."
        )
        assertTrue(
            text.contains("Disposer.register") || text.contains("RunInvocation"),
            "Leak: JavaRuntimeExecTool calls addEventsListener but the source contains neither " +
                "`Disposer.register(...)` nor a `RunInvocation` reference. Since TestResultsViewer " +
                "has no removeEventsListener API, the listener MUST be routed through a Disposer " +
                "chain tied to RunInvocation.dispose() — Phase 3 Tasks 2.3–2.4."
        )
    }

    @Test
    fun `CoverageTool — TestResultsViewer EventsListener must be tied to a Disposer or RunInvocation`() {
        val text = readSource("CoverageTool.kt")
        assertTrue(
            text.contains("addEventsListener"),
            "Precondition: CoverageTool must attach a TestResultsViewer.EventsListener " +
                "(the leak site — see executeRunWithCoverage ≈L203)."
        )
        assertTrue(
            text.contains("Disposer.register") || text.contains("RunInvocation"),
            "Leak: CoverageTool calls addEventsListener but the source contains neither " +
                "`Disposer.register(...)` nor a `RunInvocation` reference. Since TestResultsViewer " +
                "has no removeEventsListener API, the listener MUST be routed through a Disposer " +
                "chain tied to RunInvocation.dispose() — Phase 3 Task 2.5."
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ProcessListener disposal — SOURCE-TEXT test (covers CoverageTool).
    //
    // Asserts symmetric count: addProcessListener(1-arg) vs removeProcessListener,
    // OR every addProcessListener uses the 2-arg form with a Disposable.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `CoverageTool — every addProcessListener must be paired with remove or 2-arg Disposable`() {
        val text = readSource("CoverageTool.kt")
        assertLeaklessProcessListener(text, "CoverageTool")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RunContentDescriptor disposal — SOURCE-TEXT test for both tools.
    //
    // Descriptor must be released via
    //   ExecutionManager.getInstance(project).contentManager
    //       .removeRunContent(executor, descriptor)
    // when the run reaches a terminal state. Pre-fix: nobody calls it.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `JavaRuntimeExecTool — RunContentDescriptor must be released via removeRunContent`() {
        val text = readSource("JavaRuntimeExecTool.kt")
        assertTrue(
            text.contains("removeRunContent"),
            "Leak: JavaRuntimeExecTool never calls RunContentManager.removeRunContent. " +
                "The descriptor captured in descriptorRef is tracked indefinitely. " +
                "Tasks 2.1–2.4 must release it via " +
                "ExecutionManager.getInstance(project).contentManager.removeRunContent(executor, descriptor)."
        )
    }

    @Test
    fun `CoverageTool — RunContentDescriptor must be released via removeRunContent`() {
        val text = readSource("CoverageTool.kt")
        assertTrue(
            text.contains("removeRunContent"),
            "Leak: CoverageTool never calls RunContentManager.removeRunContent. " +
                "The descriptor returned by ProgramRunner.Callback is tracked indefinitely. " +
                "Task 2.5 must release it via " +
                "ExecutionManager.getInstance(project).contentManager.removeRunContent(executor, descriptor)."
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Leak-target Thread spawns — SOURCE-TEXT test, identified by behaviour.
    //
    // JavaRuntimeExecTool.kt spawns THREE raw threads on the current HEAD:
    //   ✗ build-watchdog-timeout  (≈L449) — sleeps BUILD_WATCHDOG_MAX_MS, then
    //                                       disconnects a MessageBusConnection.
    //                                       Leak target — Tasks 2.2 / 2.3.
    //   ✗ test-tree-finalize      (≈L595) — polls TEST_TREE_RETRY_INTERVAL_MS
    //                                       TEST_TREE_RETRY_ATTEMPTS times.
    //                                       Leak target — Task 2.4.
    //   ✓ RunTests-Output-{id}    (≈L871) — shell-fallback stdout reader,
    //                                       bounded by `readerThread.join(1000)`
    //                                       inside executeWithShell. NOT in
    //                                       Phase 3 scope.
    //
    // Fingerprint chosen: the pairing of `Thread {` with either of the two
    // WATCHDOG-NAMED CONSTANTS (`BUILD_WATCHDOG_MAX_MS`, `TEST_TREE_RETRY_`).
    // That's resilient to a refactor that extracts the thread's `name = …`
    // literal to a constant — the constants themselves are the behavioural
    // signature of the leak (sleep-based watchdog loops). Post-fix they will
    // not appear inside a `Thread { … }` body anywhere in the file. The
    // shell reader uses neither constant, so it's naturally excluded.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `JavaRuntimeExecTool — watchdog constants must not appear inside raw Thread blocks`() {
        val text = readSource("JavaRuntimeExecTool.kt")

        // Match a `Thread {` followed (within ~400 chars, newlines included) by
        // either watchdog constant name. Two separate regexes so the failure
        // message can break down the count per leak target.
        val buildWatchdog = Regex(
            """Thread\s*\{[^}]{0,400}BUILD_WATCHDOG_MAX_MS""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(text).count()
        val testTreeFinalize = Regex(
            """Thread\s*\{[^}]{0,400}TEST_TREE_RETRY_""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(text).count()
        val leakThreads = buildWatchdog + testTreeFinalize

        assertEquals(
            0,
            leakThreads,
            "Leak: JavaRuntimeExecTool still spawns the two watchdog Threads " +
                "[build-watchdog-timeout (BUILD_WATCHDOG_MAX_MS inside Thread{})=$buildWatchdog, " +
                "test-tree-finalize (TEST_TREE_RETRY_* inside Thread{})=$testTreeFinalize]. " +
                "Their closures retain the continuation, descriptor, and MessageBusConnection " +
                "until their sleep expires. Tasks 2.2–2.3 (build-watchdog-timeout) and Task 2.4 " +
                "(test-tree-finalize) must replace these with cancellable scheduling registered " +
                "on the RunInvocation's Disposable chain. The bounded shell-fallback reader " +
                "'RunTests-Output-{toolCallId}' in executeWithShell uses neither constant and " +
                "is intentionally out of scope."
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Dispose-on-all-paths — SOURCE-TEXT tests.
    //
    // Disposal must run on every exit path: success, processNotStarted,
    // timeout, exception, coroutine cancellation. Only [handleDescriptorReady]
    // is directly driveable from MockK here — the outer exit paths live in
    // [executeWithNativeRunner] / [executeRunWithCoverage] where the `finally`
    // block owns disposal. We encode that invariant structurally: the outer
    // functions must contain `finally { … Disposer.dispose(invocation) … }`
    // (or an equivalent `.use { … }` on a Closeable RunInvocation).
    //
    // Pre-fix: no RunInvocation exists, no `finally { Disposer.dispose }`
    // pattern exists → FAIL.
    // Post-fix: Tasks 2.1–2.5 must wrap each outer function in a try/finally
    // that disposes the RunInvocation on every exit → PASS.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `JavaRuntimeExecTool — executeWithNativeRunner must dispose invocation in a finally block`() {
        val text = readSource("JavaRuntimeExecTool.kt")
        assertTrue(
            hasFinallyDisposePattern(text),
            "Leak: JavaRuntimeExecTool has no `finally { … Disposer.dispose(invocation) … }` " +
                "pattern. Disposal MUST run on ALL exit paths from executeWithNativeRunner " +
                "(success / processNotStarted / timeout / exception / coroutine cancel). " +
                "Tasks 2.1–2.4 must introduce a RunInvocation and wrap the run body in " +
                "`try { … } finally { Disposer.dispose(invocation) }` (or an equivalent " +
                "`invocation.use { … }` on a Closeable) so the listener/descriptor cleanup " +
                "is structurally guaranteed regardless of which exit path fires."
        )
    }

    @Test
    fun `CoverageTool — executeRunWithCoverage must dispose invocation in a finally block`() {
        val text = readSource("CoverageTool.kt")
        assertTrue(
            hasFinallyDisposePattern(text),
            "Leak: CoverageTool has no `finally { … Disposer.dispose(invocation) … }` " +
                "pattern. Disposal MUST run on ALL exit paths from executeRunWithCoverage " +
                "(success / processNotStarted / timeout / exception / coroutine cancel). " +
                "Task 2.5 must introduce a RunInvocation and wrap the run body in " +
                "`try { … } finally { Disposer.dispose(invocation) }` (or an equivalent " +
                "`invocation.use { … }` on a Closeable)."
        )
    }

    /**
     * True when the source contains a `finally` block that disposes a
     * RunInvocation, via either:
     *   (a) `finally { … Disposer.dispose(invocation) … }`, or
     *   (b) `invocation.use { … }` on a `Closeable` RunInvocation.
     *
     * This is conservative — the `(?s)` flag lets `.` match newlines so a
     * multi-line finally block is detected. Post-fix, at least one of these
     * patterns must be present in the file.
     */
    private fun hasFinallyDisposePattern(source: String): Boolean {
        val finallyDispose = Regex(
            """finally\s*\{[^}]*Disposer\.dispose\s*\(\s*invocation""",
            RegexOption.DOT_MATCHES_ALL
        ).containsMatchIn(source)
        val invocationUse = Regex(
            """\binvocation\.use\s*\{""",
            RegexOption.DOT_MATCHES_ALL
        ).containsMatchIn(source)
        return finallyDispose || invocationUse
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Build a mock [RunContentDescriptor] whose executionConsole unwraps (via
     * stubbed [TestConsoleUtils]) to a mock [SMTRunnerConsoleView] with a mock
     * [SMTestRunnerResultsForm] as its resultsViewer, and whose processHandler
     * instruments addProcessListener / removeProcessListener counters.
     */
    private fun buildMockDescriptor(): RunContentDescriptor {
        val descriptor = mockk<RunContentDescriptor>(relaxed = true)
        val console = mockk<SMTRunnerConsoleView>(relaxed = true)
        // resultsViewer returns SMTestRunnerResultsForm (the concrete class),
        // not TestResultsViewer. addEventsListener is inherited from the interface.
        val resultsViewer = mockk<SMTestRunnerResultsForm>(relaxed = true)
        val handler = mockk<ProcessHandler>(relaxed = true)

        every { console.resultsViewer } returns resultsViewer

        val processListenerSlot = slot<ProcessListener>()
        every { handler.addProcessListener(capture(processListenerSlot)) } answers {
            processListenerAddCount.incrementAndGet()
            capturedProcessListener.set(processListenerSlot.captured)
        }
        every { handler.removeProcessListener(any()) } answers {
            processListenerRemoveCount.incrementAndGet()
        }
        every { handler.isProcessTerminated } returns false

        every { descriptor.processHandler } returns handler
        every { descriptor.displayName } returns "com.example.FooTest"
        every { descriptor.executionConsole } returns console

        every { TestConsoleUtils.unwrapToTestConsole(any()) } returns console
        return descriptor
    }

    /** Minimal real-enough [CancellableContinuation] so the tool can call `resume(...)`. */
    private fun stubContinuation(): CancellableContinuation<ToolResult?> {
        val cc = mockk<CancellableContinuation<ToolResult?>>(relaxed = true)
        every { cc.isActive } returns true
        every { cc.context } returns EmptyCoroutineContext
        return cc
    }

    /**
     * Disposal contract for ProcessListeners:
     *   Every 1-arg `addProcessListener(listener)` must be matched by a
     *   `removeProcessListener(listener)` call. 2-arg
     *   `addProcessListener(listener, parentDisposable)` calls carry their own
     *   cleanup via the parent Disposable and do NOT need a matching remove.
     *
     * Invariant (checked explicitly, no OR predicate hole):
     *     oneArgAddCount == removeCount
     *
     * Where:
     *     oneArgAddCount = totalAddCount - twoArgAddCount
     *
     * A stray 1-arg add with no remove can no longer slip through because
     * some other `addProcessListener` call in the file happens to be 2-arg.
     */
    private fun assertLeaklessProcessListener(source: String, toolName: String) {
        val totalAddCount = Regex("""\baddProcessListener\b""").findAll(source).count()
        val removeCount = Regex("""\bremoveProcessListener\b""").findAll(source).count()
        // Two-argument call: addProcessListener(listener, parentDisposable) —
        // detected by a top-level comma inside a balanced parenthesis group.
        val twoArgAddCount = Regex("""addProcessListener\s*\([^()]*,[^()]*\)""")
            .findAll(source)
            .count()
        val oneArgAddCount = totalAddCount - twoArgAddCount

        assertTrue(
            totalAddCount >= 1,
            "Precondition: $toolName must call addProcessListener at least once " +
                "(totalAddCount=$totalAddCount)"
        )
        assertEquals(
            oneArgAddCount,
            removeCount,
            "Leak: $toolName has $oneArgAddCount 1-arg addProcessListener call(s) " +
                "(totalAddCount=$totalAddCount, twoArgAddCount=$twoArgAddCount) but only " +
                "$removeCount removeProcessListener call(s). Every 1-arg addProcessListener " +
                "MUST be matched by a removeProcessListener — or switch to the 2-arg form " +
                "`addProcessListener(listener, parentDisposable)` which is auto-cleaned. " +
                "Route disposal through RunInvocation.dispose()."
        )
    }

    /**
     * Reads the canonical runtime-tool source file directly from the known
     * module layout. No tree walk — if the layout changes, the test fails
     * loudly with the exact missing path instead of silently matching a peer
     * worktree or a fixture file of the same name.
     *
     * Gradle's `:agent:test` task runs with `user.dir = <repoRoot>/agent` (the
     * submodule dir). If a different runner sets `user.dir = <repoRoot>` (e.g.
     * IntelliJ's test runner with `Working directory = $MODULE_WORKING_DIR$`)
     * the first candidate will miss and we fall back to the repo-root layout.
     * Anything else is a layout change we want to fail on loudly.
     */
    private fun readSource(name: String): String {
        val userDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not set")
        val root = File(userDir)
        val relSubdir = "src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/$name"
        val moduleRootedPath = File(root, relSubdir)                      // user.dir == <repo>/agent
        val repoRootedPath = File(root, "agent/$relSubdir")               // user.dir == <repo>
        val path = when {
            moduleRootedPath.isFile -> moduleRootedPath
            repoRootedPath.isFile -> repoRootedPath
            else -> error(
                "Source file '$name' not found at either expected path:\n" +
                    "  1. ${moduleRootedPath.absolutePath}\n" +
                    "  2. ${repoRootedPath.absolutePath}\n" +
                    "user.dir=$userDir — module layout may have changed."
            )
        }
        return path.readText()
    }
}
