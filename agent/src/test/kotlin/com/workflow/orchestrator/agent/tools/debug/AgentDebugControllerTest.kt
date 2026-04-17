package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentDebugControllerTest {

    private lateinit var controller: AgentDebugController
    private lateinit var testParentDisposable: Disposable
    private val mockProject = mockk<Project>(relaxed = true)

    @BeforeEach
    fun setup() {
        // Phase 5 / Task 4.2: registerSession now requires a DebugInvocationFactory.
        // Tests use a throw-away parent Disposable so we don't have to stand up a
        // real AgentService (heavy init: memory, tool registration, hooks).
        testParentDisposable = Disposer.newDisposable("AgentDebugControllerTest-parent")
        controller = AgentDebugController(mockProject) { name ->
            DebugInvocation(testParentDisposable, name)
        }
    }

    @AfterEach
    fun tearDown() {
        controller.dispose()
        Disposer.dispose(testParentDisposable)
    }

    @Test
    fun `registerSession assigns unique IDs`() {
        val session1 = createMockSession()
        val session2 = createMockSession()

        val id1 = controller.registerSession(session1)
        val id2 = controller.registerSession(session2)

        assertNotEquals(id1, id2)
        assertTrue(id1.startsWith("debug-"))
        assertTrue(id2.startsWith("debug-"))
    }

    @Test
    fun `getSession returns registered session`() {
        val session = createMockSession()
        val id = controller.registerSession(session)

        val retrieved = controller.getSession(id)

        assertSame(session, retrieved)
    }

    @Test
    fun `getSession with null returns active session`() {
        val session = createMockSession()
        controller.registerSession(session)

        val retrieved = controller.getSession(null)

        assertSame(session, retrieved)
    }

    @Test
    fun `getSession returns null for unknown ID`() {
        assertNull(controller.getSession("nonexistent"))
    }

    @Test
    fun `getActiveSessionId returns latest registered session`() {
        val session1 = createMockSession()
        val session2 = createMockSession()

        controller.registerSession(session1)
        val id2 = controller.registerSession(session2)

        assertEquals(id2, controller.getActiveSessionId())
    }

    @Test
    fun `waitForPause returns immediately when already suspended`() = runTest {
        val session = createMockSession(isSuspended = true)
        val id = controller.registerSession(session)

        val event = controller.waitForPause(id, timeoutMs = 1000)

        assertNotNull(event)
        assertEquals(id, event!!.sessionId)
        assertEquals("/path/to/File.kt", event.file)
        assertEquals(11, event.line) // 0-based (10) + 1
        assertEquals("breakpoint", event.reason)
    }

    @Test
    fun `waitForPause returns null on timeout`() = runTest {
        val session = createMockSession(isSuspended = false)
        val id = controller.registerSession(session)

        val event = controller.waitForPause(id, timeoutMs = 100)

        assertNull(event)
    }

    @Test
    fun `waitForPause returns null for unknown session`() = runTest {
        val event = controller.waitForPause("nonexistent", timeoutMs = 100)
        assertNull(event)
    }

    @Test
    fun `stopAllSessions stops all tracked sessions`() {
        val session1 = createMockSession()
        val session2 = createMockSession()

        val id1 = controller.registerSession(session1)
        val id2 = controller.registerSession(session2)

        controller.stopAllSessions()

        verify { session1.stop() }
        verify { session2.stop() }
        assertNull(controller.getSession(id1))
        assertNull(controller.getSession(id2))
        assertNull(controller.getActiveSessionId())
    }

    @Test
    fun `evaluate returns error when no active frame`() = runTest {
        val session = mockk<XDebugSession>(relaxed = true) {
            every { currentStackFrame } returns null
            every { addSessionListener(any()) } just Runs
        }
        controller.registerSession(session)

        val result = controller.evaluate(session, "x + 1")

        assertTrue(result.isError)
        assertEquals("x + 1", result.expression)
        assertTrue(result.result.contains("No active stack frame"))
    }

    @Test
    fun `evaluate returns error when no evaluator available`() = runTest {
        val frame = mockk<XStackFrame>(relaxed = true) {
            every { evaluator } returns null
        }
        val session = mockk<XDebugSession>(relaxed = true) {
            every { currentStackFrame } returns frame
            every { addSessionListener(any()) } just Runs
        }
        controller.registerSession(session)

        val result = controller.evaluate(session, "y * 2")

        assertTrue(result.isError)
        assertTrue(result.result.contains("No evaluator"))
    }

    @Test
    fun `evaluate delegates to XDebuggerEvaluator`() = runTest {
        val evaluator = mockk<XDebuggerEvaluator>(relaxed = true)
        val frame = mockk<XStackFrame>(relaxed = true) {
            every { getEvaluator() } returns evaluator
        }
        val session = mockk<XDebugSession>(relaxed = true) {
            every { currentStackFrame } returns frame
            every { addSessionListener(any()) } just Runs
        }

        // Capture the callback and invoke it — evaluate(String, XEvaluationCallback, XSourcePosition?)
        every { evaluator.evaluate(any<String>(), any(), any()) } answers {
            val callback = secondArg<XDebuggerEvaluator.XEvaluationCallback>()
            callback.errorOccurred("Variable not found")
        }

        controller.registerSession(session)
        val result = controller.evaluate(session, "unknownVar")

        assertTrue(result.isError)
        assertEquals("Variable not found", result.result)
    }

    @Test
    fun `trackBreakpoint stores breakpoint for cleanup`() {
        val bp = mockk<XLineBreakpoint<*>>(relaxed = true)
        controller.trackBreakpoint(bp)
        // Verify no crash — cleanup happens on dispose
        controller.removeAgentBreakpoints()
    }

    @Test
    fun `dispose stops sessions and clears breakpoints`() {
        val session = createMockSession()
        controller.registerSession(session)
        val bp = mockk<XLineBreakpoint<*>>(relaxed = true)
        controller.trackBreakpoint(bp)

        controller.dispose()

        verify { session.stop() }
        assertNull(controller.getActiveSessionId())
    }

    @Test
    fun `session listener callback fires pause event`() = runTest {
        // Phase 5 / Task 4.2: DebugInvocation.attachListener uses the 2-arg
        // addSessionListener(listener, parentDisposable) form, so capture must
        // come from that overload, not the deprecated 1-arg form.
        val listenerSlot = slot<XDebugSessionListener>()
        val position = mockk<XSourcePosition> {
            every { file } returns mockk {
                every { path } returns "/src/Main.kt"
            }
            every { line } returns 24
        }
        val session = mockk<XDebugSession>(relaxed = true) {
            every {
                addSessionListener(capture(listenerSlot), any<Disposable>())
            } just Runs
            every { isSuspended } returns false andThen true
            every { currentPosition } returns position
        }

        val id = controller.registerSession(session)

        // Simulate the debugger pausing
        listenerSlot.captured.sessionPaused()

        // Now waitForPause should see the emitted event
        every { session.isSuspended } returns true
        val event = controller.waitForPause(id, timeoutMs = 1000)

        assertNotNull(event)
        assertEquals("/src/Main.kt", event!!.file)
        assertEquals(25, event.line) // 24 + 1
        assertEquals("breakpoint", event.reason)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 5 Task 4.7 — RED regression tests for callback-race fix
    //
    // These tests describe the FIXED behavior (Task 4.1). They are expected
    // to FAIL against the current (unfixed) AgentDebugController because:
    //
    //   (a) `evaluate()` has NO `withTimeoutOrNull` wrapper — a late/never-firing
    //       XDebuggerEvaluator callback hangs the coroutine indefinitely.
    //   (b) There is no `stopped` AtomicBoolean / `awaitCallback` helper — late
    //       callbacks racing a cancelled/consumed continuation cannot be silently
    //       dropped, risking `IllegalStateException: Already resumed` or leaked
    //       XValue references.
    //
    // Post-Task-4.1 these must pass.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `evaluate with never-firing callback times out with error result`() = runTest {
        // Given: a debug session whose evaluator captures the callback but
        // never invokes it (simulating a 20-second hang deep inside JDI).
        val evaluator = mockk<XDebuggerEvaluator>(relaxed = true)
        val frame = mockk<XStackFrame>(relaxed = true) {
            every { getEvaluator() } returns evaluator
        }
        val session = mockk<XDebugSession>(relaxed = true) {
            every { currentStackFrame } returns frame
            every { addSessionListener(any()) } just Runs
        }

        val callbackSlot = slot<XDebuggerEvaluator.XEvaluationCallback>()
        every { evaluator.evaluate(any<String>(), capture(callbackSlot), any()) } just Runs

        controller.registerSession(session)

        // When: we launch evaluate() and advance past any reasonable internal timeout.
        val deferred = async { controller.evaluate(session, "1 + 1") }
        advanceTimeBy(11_000)   // > expected 10s internal timeout
        runCurrent()

        // Then: the fix guarantees evaluate() returns an error EvaluationResult
        // via a `withTimeoutOrNull(10_000)` + `awaitCallback` guard.
        //
        // Pre-fix: this assertion FAILS because there is no outer timeout —
        // the coroutine hangs awaiting the evaluator callback.
        assertTrue(
            deferred.isCompleted,
            "EXPECTED TO FAIL pre-Task-4.1: evaluate() must complete within ~10s " +
                "even if the XDebuggerEvaluator callback never fires. Today there is " +
                "no `withTimeoutOrNull` wrapper around the outer `suspendCancellableCoroutine`, " +
                "so the coroutine hangs indefinitely — a leaked debugger evaluator " +
                "callback stuck waiting for a JDI response that will never arrive."
        )

        val result = deferred.await()
        assertTrue(
            result.isError,
            "Timed-out evaluate() must return an error EvaluationResult, not a success."
        )
        assertEquals("1 + 1", result.expression)

        // And: firing the late callback AFTER timeout must NOT corrupt state.
        // The fix installs a `stopped: AtomicBoolean` gate; pre-fix, the raw
        // `cont.resume(...)` call may throw `IllegalStateException: Already resumed`
        // (or silently leak the XValue reference) depending on continuation state.
        assertDoesNotThrow {
            val lateValue = mockk<XValue>(relaxed = true)
            callbackSlot.captured.evaluated(lateValue)
            runCurrent()
        }
    }

    @Test
    fun `resolvePresentation callback guards setPresentation against late firing with stopped flag`() {
        // Source-text regression: every `XValueNode.setPresentation` override
        // in AgentDebugController's `resolvePresentation` MUST guard its
        // `cont.resume(...)` behind an `AtomicBoolean` / `stopped` gate,
        // otherwise a late callback racing the `withTimeoutOrNull` window
        // leaks the XValue reference (the framework-facing callback never
        // learns we're done) and — depending on kotlinx version — may throw
        // `IllegalStateException: Already resumed`.
        //
        // Today's implementation calls `cont.resume(Pair(type ?: "unknown", value))`
        // unconditionally at roughly line 331 and 339. The fix routes through
        // the shared `awaitCallback(timeoutMs = 5_000) { stopped, resume, _ -> ... }`
        // helper, where the closure references `stopped` inside both
        // `setPresentation` overloads.
        //
        // This test pins the mitigation via source text — the same pattern
        // used by RunInvocationLeakTest — because the observable runtime
        // failure (silent leak vs late exception) is kotlinx-version-
        // dependent and therefore flaky as an assertion.
        val source = readControllerSource()

        // Locate the resolvePresentation function body.
        val resolveStart = source.indexOf("private suspend fun resolvePresentation(")
        assertTrue(
            resolveStart >= 0,
            "Could not locate `resolvePresentation` in AgentDebugController.kt — " +
                "file layout may have changed; update this test."
        )
        // Body runs until the companion object / next top-level `private suspend fun`
        // / `suspend fun` declaration, whichever comes first.
        val bodyEnd = sequenceOf(
            source.indexOf("suspend fun evaluate(", resolveStart + 1),
            source.indexOf("companion object", resolveStart + 1),
        ).filter { it > 0 }.min()
        val body = source.substring(resolveStart, bodyEnd)

        assertTrue(
            body.contains("stopped") || body.contains("awaitCallback"),
            "EXPECTED TO FAIL pre-Task-4.1: `resolvePresentation` must use a " +
                "`stopped: AtomicBoolean` gate (directly or via the `awaitCallback` " +
                "helper) so the XValueNode.setPresentation callback becomes a no-op " +
                "when fired after the 5s timeout. Today the body calls " +
                "`cont.resume(...)` unconditionally — source:\n\n$body"
        )
    }

    @Test
    fun `evaluate wraps outer callback in withTimeoutOrNull with stopped-flag guard`() {
        // Source-text regression: the `evaluate(session, expression, frameIndex)`
        // function must wrap its outer `XDebuggerEvaluator.evaluate` callback
        // with BOTH:
        //   (a) `withTimeoutOrNull(...)` — today missing entirely; without it,
        //       a JDI hang suspends the agent loop forever.
        //   (b) a `stopped` AtomicBoolean gate (directly or via `awaitCallback`)
        //       — today the `evaluated` / `errorOccurred` overrides call
        //       `cont.resume(Result.success(result))` unconditionally.
        //
        // The two are coupled: without (b), the timeout introduced by (a) would
        // still leak the evaluator reference and race a consumed continuation.
        // This test pins the exit criterion in the plan:
        //   "All `withTimeoutOrNull + suspendCancellableCoroutine` pairs in
        //    AgentDebugController.kt use the `awaitCallback` helper."
        val source = readControllerSource()

        val evaluateStart = source.indexOf("suspend fun evaluate(")
        assertTrue(
            evaluateStart >= 0,
            "Could not locate `evaluate(` in AgentDebugController.kt — file layout " +
                "may have changed; update this test."
        )
        // Extract from `suspend fun evaluate(` to the next top-level declaration.
        val bodyEnd = sequenceOf(
            source.indexOf("\n    fun stopAllSessions", evaluateStart + 1),
            source.indexOf("\n    fun ", evaluateStart + 1),
            source.indexOf("\n    private ", evaluateStart + 1),
        ).filter { it > 0 }.min()
        val body = source.substring(evaluateStart, bodyEnd)

        assertTrue(
            body.contains("withTimeoutOrNull") || body.contains("awaitCallback"),
            "EXPECTED TO FAIL pre-Task-4.1: `evaluate()` must bound its outer " +
                "XDebuggerEvaluator callback with `withTimeoutOrNull(...)` (or the " +
                "`awaitCallback` helper). Today the outer " +
                "`suspendCancellableCoroutine` has NO timeout — a hung JDI evaluator " +
                "suspends the agent loop indefinitely. Body:\n\n$body"
        )
        assertTrue(
            body.contains("stopped") || body.contains("awaitCallback"),
            "EXPECTED TO FAIL pre-Task-4.1: `evaluate()`'s XEvaluationCallback " +
                "overrides must guard `cont.resume(...)` behind a `stopped: AtomicBoolean` " +
                "(directly or via the `awaitCallback` helper). Today both `evaluated()` " +
                "and `errorOccurred()` call `cont.resume(...)` unconditionally — the " +
                "late-callback race this task was filed to fix. Body:\n\n$body"
        )
    }

    /**
     * Reads `AgentDebugController.kt` source from the module sources tree.
     * Same dual-user.dir resolution as `RunInvocationLeakTest.readSource` —
     * handles both `:agent:test` (user.dir = agent/) and IDE runners (user.dir = repo root).
     */
    private fun readControllerSource(): String {
        val userDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not set")
        val root = java.io.File(userDir)
        val relSubdir = "src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt"
        val moduleRooted = java.io.File(root, relSubdir)
        val repoRooted = java.io.File(root, "agent/$relSubdir")
        val path = when {
            moduleRooted.isFile -> moduleRooted
            repoRooted.isFile -> repoRooted
            else -> error(
                "AgentDebugController.kt not found at either expected path:\n" +
                    "  1. ${moduleRooted.absolutePath}\n" +
                    "  2. ${repoRooted.absolutePath}\n" +
                    "user.dir=$userDir"
            )
        }
        return path.readText()
    }

    // --- Helper ---

    private fun createMockSession(isSuspended: Boolean = false): XDebugSession {
        val position = mockk<XSourcePosition> {
            every { file } returns mockk {
                every { path } returns "/path/to/File.kt"
            }
            every { line } returns 10
        }
        return mockk<XDebugSession>(relaxed = true) {
            every { this@mockk.isSuspended } returns isSuspended
            every { currentPosition } returns position
            every { addSessionListener(any()) } just Runs
            every { stop() } just Runs
        }
    }
}
