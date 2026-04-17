package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 5 / Task 4.2 — leak regression test for [AgentDebugController].
 *
 * Mirrors [com.workflow.orchestrator.agent.tools.runtime.RunInvocationLeakTest]
 * in shape. Pins the new listener/flow ownership contract:
 *
 *   1. [AgentDebugController.registerSession] MUST route its session listener
 *      through [DebugInvocation.attachListener] (which uses the 2-arg
 *      `addSessionListener(listener, parentDisposable)` form — auto-removed
 *      via `Disposer`), NOT the 1-arg `session.addSessionListener(listener)`
 *      form (which has NO remove path and leaks across session cycles).
 *
 *   2. The pause flow MUST live on the per-session [DebugInvocation.pauseFlow]
 *      (so it's garbage-collected alongside the invocation on dispose), NOT
 *      on a controller-wide `ConcurrentHashMap<String, MutableSharedFlow>`
 *      (which grew indefinitely pre-fix — `sessionStopped` cleanup only
 *      fired if the session actually reported `sessionStopped`, so cancel
 *      and error paths accumulated orphan flows).
 *
 *   3. [AgentDebugController.stopAllSessions] MUST dispose every session's
 *      invocation (cascades listener removal, flow reset, `onDispose` hooks).
 *
 * Validation command:
 *     ./gradlew :agent:test --tests "*AgentDebugControllerLeak*"
 */
class AgentDebugControllerLeakTest {

    private val project = mockk<Project>(relaxed = true)
    private lateinit var parent: Disposable

    // Track DebugInvocation disposals via a spy wrapper so behavioural tests
    // can count them without coupling to a specific static/object harness.
    private val invocationsCreated = mutableListOf<DebugInvocation>()
    private val invocationsDisposed = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        parent = Disposer.newDisposable("AgentDebugControllerLeakTest-parent")
        invocationsCreated.clear()
        invocationsDisposed.set(0)
    }

    @AfterEach
    fun tearDown() {
        Disposer.dispose(parent)
    }

    /**
     * Factory that hands out real [DebugInvocation]s (so production code
     * paths exercise real Disposer wiring) but records them so tests can
     * track creation order and count disposals via `Disposer.isDisposed`.
     */
    private fun recordingFactory(): (String) -> DebugInvocation = { name ->
        val inv = DebugInvocation(parent, name)
        invocationsCreated.add(inv)
        // Chain an onDispose counter so we can verify dispose was called.
        inv.onDispose { invocationsDisposed.incrementAndGet() }
        inv
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SOURCE-TEXT pins (structural assertions against AgentDebugController.kt)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `registerSession must NOT use the 1-arg addSessionListener leak form`() {
        val body = extractRegisterSessionBody()
        // The 1-arg form is the leak site — no `removeSessionListener` counterpart
        // is ever called, so every registerSession cycle stacked another listener
        // on the same XDebugSession pre-Task-4.2. Post-fix MUST NOT appear.
        // Regex matches `session.addSessionListener(` only when followed by
        // a bare `object :` (1-arg form), not `(..., any<Disposable>())`.
        val oneArgForm = Regex("""session\.addSessionListener\s*\(\s*object\s*:""")
        assertFalse(
            oneArgForm.containsMatchIn(body),
            "Leak: registerSession body still contains the 1-arg " +
                "`session.addSessionListener(object : ...)` form. Every such call " +
                "stacks a listener that is never removed — the XDebugSessionListener " +
                "API exposes no `removeSessionListener` path. Route through " +
                "`invocation.attachListener(session, listener)` (2-arg form, " +
                "auto-removed via Disposer) instead. Body:\n\n$body"
        )
    }

    @Test
    fun `registerSession must route listener attach through invocation attachListener`() {
        val body = extractRegisterSessionBody()
        assertTrue(
            body.contains("invocation.attachListener("),
            "registerSession must call `invocation.attachListener(session, listener)` " +
                "— the 2-arg form that parents the listener to the invocation's " +
                "Disposable chain. Without this pin, a future refactor could " +
                "accidentally reintroduce the 1-arg leak without tripping the " +
                "negative assertion above. Body:\n\n$body"
        )
    }

    @Test
    fun `registerSession must obtain a session-scoped DebugInvocation via the factory`() {
        val body = extractRegisterSessionBody()
        // The default factory calls `project.service<AgentService>().newDebugInvocation(...)`.
        // Either spelling proves the session parent-chain is in place; any
        // free-standing `DebugInvocation(...)` construction inside this body
        // would bypass the session scope and reintroduce the cross-chat leak.
        assertTrue(
            body.contains("newDebugInvocation(") || body.contains("debugInvocationFactory("),
            "registerSession must allocate its DebugInvocation via the session-scoped " +
                "factory (`newDebugInvocation(\"session-\$sessionId\")` or the injected " +
                "`debugInvocationFactory(...)`), NOT by direct `DebugInvocation(...)` " +
                "construction — otherwise the invocation is not parented to the session " +
                "Disposable and a new-chat cascade won't tear it down. Body:\n\n$body"
        )
    }

    @Test
    fun `registerSession must emit pause events through invocation pauseFlow`() {
        val body = extractRegisterSessionBody()
        assertTrue(
            body.contains("invocation.pauseFlow"),
            "registerSession must route pause-event emission / resetReplayCache through " +
                "`invocation.pauseFlow` — the flow now lives on the per-session " +
                "invocation (GC'd on dispose), not on a controller-wide map. Body:\n\n$body"
        )
    }

    @Test
    fun `AgentDebugController source must NOT contain the old pauseFlows map field`() {
        val source = readControllerSource()
        // The pre-fix field:
        //   private val pauseFlows = ConcurrentHashMap<String, MutableSharedFlow<DebugPauseEvent>>()
        // was the accumulation site for orphan flows (only cleaned up on
        // sessionStopped — missed on cancel / error). Its absence post-fix is
        // the structural proof the map is gone.
        assertFalse(
            source.contains("private val pauseFlows"),
            "Leak: AgentDebugController still declares `private val pauseFlows = ...`. " +
                "The pauseFlow now lives on DebugInvocation — the controller-wide map " +
                "must be removed so cancel / error paths can't accumulate orphan flows."
        )
    }

    @Test
    fun `stopAllSessions must dispose each session's invocation`() {
        val body = extractStopAllSessionsBody()
        assertTrue(
            body.contains("Disposer.dispose("),
            "Leak: stopAllSessions must call `Disposer.dispose(entry.invocation)` " +
                "(or `Disposer.dispose(invocation)`) for every tracked session — this " +
                "cascades listener removal, pauseFlow replay-cache reset, and onDispose " +
                "callbacks. Without this call, `stopAllSessions` leaves the invocation " +
                "(and its 2-arg session listener) alive. Body:\n\n$body"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BEHAVIOURAL — registerSession + stopAllSessions disposes invocation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `registerSession followed by stopAllSessions disposes the invocation`() {
        val controller = AgentDebugController(project, recordingFactory())
        try {
            val session = mockSession()
            controller.registerSession(session)

            assertEquals(
                1,
                invocationsCreated.size,
                "Precondition: registerSession must allocate exactly one invocation " +
                    "(got ${invocationsCreated.size})"
            )
            assertEquals(
                0,
                invocationsDisposed.get(),
                "Precondition: invocation must not be disposed before stopAllSessions"
            )

            controller.stopAllSessions()

            assertEquals(
                1,
                invocationsDisposed.get(),
                "Leak: stopAllSessions did NOT trigger DebugInvocation.dispose — the " +
                    "onDispose counter observed ${invocationsDisposed.get()} calls " +
                    "instead of 1. The session listener is still wired to the " +
                    "XDebugSession after stopAllSessions returns."
            )
            assertTrue(
                controller.sessionInvocations.isEmpty(),
                "stopAllSessions must clear the sessionInvocations map — state leak."
            )
            assertNull(
                controller.getActiveSessionId(),
                "stopAllSessions must null out activeSessionId."
            )
        } finally {
            controller.dispose()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BEHAVIOURAL — sessionStopped callback disposes the invocation + removes entry
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `sessionStopped listener callback disposes the invocation and removes map entry`() {
        val controller = AgentDebugController(project, recordingFactory())
        try {
            // Capture the proxy XDebugSessionListener registered via the 2-arg form.
            val listenerSlot = slot<XDebugSessionListener>()
            val session = mockk<XDebugSession>(relaxed = true) {
                every {
                    addSessionListener(capture(listenerSlot), any<Disposable>())
                } just Runs
                every { isSuspended } returns false
            }

            val sessionId = controller.registerSession(session)
            assertTrue(
                controller.sessionInvocations.containsKey(sessionId),
                "Precondition: registerSession must install the entry keyed by sessionId"
            )
            assertEquals(
                0,
                invocationsDisposed.get(),
                "Precondition: invocation must not be disposed before sessionStopped fires"
            )

            // Fire the sessionStopped callback the same way IntelliJ would.
            listenerSlot.captured.sessionStopped()

            assertFalse(
                controller.sessionInvocations.containsKey(sessionId),
                "Leak: sessionStopped callback did NOT remove the entry from " +
                    "sessionInvocations — the map would grow unboundedly across " +
                    "debug cycles."
            )
            assertEquals(
                1,
                invocationsDisposed.get(),
                "Leak: sessionStopped callback did NOT dispose the invocation " +
                    "(onDispose counter = ${invocationsDisposed.get()}). The 2-arg " +
                    "session listener is still wired to the XDebugSession after " +
                    "sessionStopped."
            )
        } finally {
            controller.dispose()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun mockSession(): XDebugSession = mockk(relaxed = true) {
        every { addSessionListener(any(), any<Disposable>()) } just Runs
        every { isSuspended } returns false
        every { stop() } just Runs
    }

    /**
     * Extract the body of `fun registerSession(session: XDebugSession): String {`
     * up to the matching closing brace by counting unbalanced braces. The
     * function is not nested so simple counting suffices — we start at the
     * opening `{` after the signature and walk until depth returns to zero.
     */
    private fun extractRegisterSessionBody(): String =
        extractFunctionBody("fun registerSession(session: XDebugSession): String")

    private fun extractStopAllSessionsBody(): String =
        extractFunctionBody("fun stopAllSessions()")

    private fun extractFunctionBody(signature: String): String {
        val source = readControllerSource()
        val start = source.indexOf(signature)
        check(start >= 0) {
            "Could not locate `$signature` in AgentDebugController.kt — file layout " +
                "may have changed; update this test."
        }
        // Find the `{` after the signature.
        val openBrace = source.indexOf('{', start)
        check(openBrace >= 0) { "No opening brace after signature `$signature`" }
        var depth = 1
        var i = openBrace + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        check(depth == 0) {
            "Unbalanced braces walking function body `$signature` — file layout " +
                "may have changed; update this test."
        }
        return source.substring(openBrace, i)
    }

    /**
     * Reads AgentDebugController.kt from the module sources tree. Dual
     * user.dir resolution matches the convention in RunInvocationLeakTest —
     * handles `:agent:test` (user.dir = agent/) and IDE runners
     * (user.dir = repo root).
     */
    private fun readControllerSource(): String {
        val userDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not set")
        val root = File(userDir)
        val relSubdir =
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt"
        val moduleRooted = File(root, relSubdir)
        val repoRooted = File(root, "agent/$relSubdir")
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
}
