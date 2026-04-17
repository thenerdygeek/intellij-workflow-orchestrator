package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.MessageBusConnection
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Unit tests for [DebugInvocation] — Phase 5 Task 4.3.
 *
 * Mirrors `RunInvocationTest` in shape. Uses a real [Disposer] parent for
 * each test (disposed in [tearDown]) so we don't have to mock the static
 * `Disposer.newDisposable` factory. Everything else is MockK — no
 * IntelliJ test fixture required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugInvocationTest {

    private lateinit var parent: Disposable

    @BeforeEach
    fun setUp() {
        parent = Disposer.newDisposable("DebugInvocationTest-parent")
    }

    @AfterEach
    fun tearDown() {
        // Even if the test forgot, tear down the whole subtree so we don't
        // leak Disposables between tests.
        Disposer.dispose(parent)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. attachListener wires up to XDebugSession via the 2-arg form
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `attachListener uses the 2-arg addSessionListener form so Disposer auto-removes on dispose`() {
        val invocation = DebugInvocation(parent, "listener-2arg")
        val session = mockk<XDebugSession>(relaxed = true)
        val parentSlot = slot<Disposable>()
        val listenerSlot = slot<XDebugSessionListener>()
        every { session.addSessionListener(capture(listenerSlot), capture(parentSlot)) } just Runs

        val rawListener = object : XDebugSessionListener {}

        invocation.attachListener(session, rawListener)

        // Crucial: the parent Disposable passed to the 2-arg addSessionListener
        // MUST be the invocation's internal Disposable — otherwise Disposer.dispose(invocation)
        // won't trigger auto-cleanup of the listener via the XDebugSession chain.
        verify(exactly = 1) { session.addSessionListener(any(), any<Disposable>()) }
        verify(exactly = 0) { session.addSessionListener(any()) }
        assertSame(
            invocation.disposable,
            parentSlot.captured,
            "2-arg addSessionListener must use DebugInvocation.disposable as parent so " +
                "the framework auto-removes the listener when the invocation disposes."
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. attachListener defense-in-depth proxy — post-dispose events are no-ops
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `attachListener proxy gates all 7 session events after dispose`() {
        val invocation = DebugInvocation(parent, "proxy-gate")
        val session = mockk<XDebugSession>(relaxed = true)
        val proxySlot = slot<XDebugSessionListener>()
        every { session.addSessionListener(capture(proxySlot), any<Disposable>()) } just Runs

        // Counters for every XDebugSessionListener method declared on the
        // target platform (2025.1.x — 7 methods).
        var paused = 0
        var resumed = 0
        var stopped = 0
        var frameChanged = 0
        var beforeResume = 0
        var settingsChanged = 0
        var mutedChanged = 0

        val rawListener = object : XDebugSessionListener {
            override fun sessionPaused() { paused++ }
            override fun sessionResumed() { resumed++ }
            override fun sessionStopped() { stopped++ }
            override fun stackFrameChanged() { frameChanged++ }
            override fun beforeSessionResume() { beforeResume++ }
            override fun settingsChanged() { settingsChanged++ }
            override fun breakpointsMuted(muted: Boolean) { mutedChanged++ }
        }

        invocation.attachListener(session, rawListener)
        val proxy = proxySlot.captured

        // Pre-dispose: every method forwards.
        proxy.sessionPaused()
        proxy.sessionResumed()
        proxy.sessionStopped()
        proxy.stackFrameChanged()
        proxy.beforeSessionResume()
        proxy.settingsChanged()
        proxy.breakpointsMuted(true)

        assertEquals(1, paused, "sessionPaused must forward pre-dispose")
        assertEquals(1, resumed, "sessionResumed must forward pre-dispose")
        assertEquals(1, stopped, "sessionStopped must forward pre-dispose")
        assertEquals(1, frameChanged, "stackFrameChanged() must forward pre-dispose")
        assertEquals(1, beforeResume, "beforeSessionResume must forward pre-dispose")
        assertEquals(1, settingsChanged, "settingsChanged must forward pre-dispose")
        assertEquals(1, mutedChanged, "breakpointsMuted must forward pre-dispose")

        // Dispose flips the internal `disposed` flag.
        Disposer.dispose(invocation)

        // Post-dispose: every method becomes a no-op — the raw listener is not invoked again.
        proxy.sessionPaused()
        proxy.sessionResumed()
        proxy.sessionStopped()
        proxy.stackFrameChanged()
        proxy.beforeSessionResume()
        proxy.settingsChanged()
        proxy.breakpointsMuted(false)

        assertEquals(1, paused, "sessionPaused must be gated post-dispose")
        assertEquals(1, resumed, "sessionResumed must be gated post-dispose")
        assertEquals(1, stopped, "sessionStopped must be gated post-dispose")
        assertEquals(1, frameChanged, "stackFrameChanged() must be gated post-dispose")
        assertEquals(1, beforeResume, "beforeSessionResume must be gated post-dispose")
        assertEquals(1, settingsChanged, "settingsChanged must be gated post-dispose")
        assertEquals(
            1,
            mutedChanged,
            "breakpointsMuted must be gated post-dispose — the disposed flag is the only " +
                "thing preventing a late framework callback from racing a consumed continuation."
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. dispose releases the session listener via Disposer
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispose releases the underlying child Disposable`() {
        val invocation = DebugInvocation(parent, "cascade-dispose")
        val connection = mockk<MessageBusConnection>(relaxed = true)
        every { connection.dispose() } just Runs

        // Hang a MessageBusConnection off the invocation's internal disposable
        // — this proves the child Disposable chain actually fires on dispose,
        // which is the same mechanism that auto-removes the 2-arg session
        // listener registered via attachListener.
        invocation.subscribeTopic(connection)

        Disposer.dispose(invocation)

        verify(exactly = 1) { connection.dispose() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. dispose is idempotent across multiple calls
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispose is idempotent across multiple calls`() {
        val invocation = DebugInvocation(parent, "idempotent")
        var callbackInvocations = 0
        invocation.onDispose { callbackInvocations++ }

        Disposer.dispose(invocation)
        assertEquals(1, callbackInvocations, "First dispose must run the callback")

        // Second dispose — no-op (guarded by internal AtomicBoolean). We call
        // invocation.dispose() directly because Disposer itself already
        // removed the disposable chain on the first call.
        assertDoesNotThrow { invocation.dispose() }

        assertEquals(
            1,
            callbackInvocations,
            "Second dispose must be a no-op — the internal disposed flag must prevent " +
                "the callback from firing again"
        )
        assertTrue(
            true,
            "Idempotency verified: dispose() called twice, cleanup ran once, no exceptions."
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. onDispose callbacks fire in LIFO order
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `onDispose callbacks fire in LIFO order`() {
        val invocation = DebugInvocation(parent, "lifo-order")
        val log = mutableListOf<String>()

        invocation.onDispose { log.add("first-registered") }
        invocation.onDispose { log.add("second-registered") }
        invocation.onDispose { log.add("third-registered") }

        Disposer.dispose(invocation)

        assertEquals(
            listOf("third-registered", "second-registered", "first-registered"),
            log,
            "onDispose callbacks must fire in LIFO order — last registered runs first " +
                "so outermost resources are released last, mirroring natural construction " +
                "unwind semantics."
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. onDispose callback exception does not prevent subsequent callbacks
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `onDispose callback exception does not prevent subsequent callbacks`() {
        val invocation = DebugInvocation(parent, "exception-swallow")
        val log = mutableListOf<String>()

        invocation.onDispose { log.add("first-registered") }
        invocation.onDispose { throw IllegalStateException("boom from middle callback") }
        invocation.onDispose { log.add("third-registered") }

        // LIFO order: third → second (throws) → first. Best-effort semantics:
        // the exception is logged and swallowed; the earlier-registered
        // blocks still run.
        assertDoesNotThrow { Disposer.dispose(invocation) }

        assertEquals(
            listOf("third-registered", "first-registered"),
            log,
            "Exception in middle callback must not prevent subsequent (earlier-registered) " +
                "callbacks — best-effort dispose semantics, mirrors RunInvocation."
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. pauseFlow resetReplayCache is called on dispose
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `pauseFlow resetReplayCache is called on dispose — late subscribers see no stale event`() =
        runTest {
            val invocation = DebugInvocation(parent, "replay-cache-reset")

            // Emit a pause event. With replay=1, a subscriber that collects
            // AFTER the emission still receives it — which is the whole point
            // of the replay cache (so waitForPause can use .first()).
            val emitted = invocation.pauseFlow.tryEmit(
                DebugPauseEvent(
                    sessionId = "debug-1",
                    file = "/tmp/Foo.kt",
                    line = 42,
                    reason = "breakpoint",
                ),
            )
            assertTrue(emitted, "tryEmit into replay-cache flow must succeed")

            // Pre-dispose: a late subscriber observes the buffered event.
            val preDispose = withTimeoutOrNull(500) { invocation.pauseFlow.first() }
            assertNotNull(
                preDispose,
                "Pre-dispose a late subscriber must observe the replay-cached event — " +
                    "this is the invariant that waitForPause relies on.",
            )

            Disposer.dispose(invocation)

            // Post-dispose: a late subscriber must NOT observe a stale event
            // from before the session teardown. The 500ms withTimeoutOrNull
            // fires with null because resetReplayCache() evicted the buffered
            // emission — no new emission arrives (the session is gone) and
            // the timeout expires with no value.
            val postDispose = withTimeoutOrNull(500) { invocation.pauseFlow.first() }
            assertNull(
                postDispose,
                "Post-dispose resetReplayCache must evict the buffered event so a " +
                    "late-arriving subscriber doesn't observe stale pause state from a " +
                    "torn-down session.",
            )
        }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. Cascade dispose of parent invokes DebugInvocation.dispose
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `cascade dispose of parent invokes DebugInvocation dispose when registered as child`() {
        // Mirror the SessionDisposableHolder double-registration pattern:
        // register the invocation itself as a child of its parent so that
        // when the parent is disposed, the invocation's dispose() (and its
        // onDispose callbacks) actually fire — not just its internal
        // `disposable` child.
        val invocation = DebugInvocation(parent, "cascade-root")
        Disposer.register(parent, invocation)

        var callbackFired = false
        invocation.onDispose { callbackFired = true }

        // Dispose the PARENT, not the invocation directly.
        Disposer.dispose(parent)

        assertTrue(
            callbackFired,
            "Cascade dispose of parent must invoke DebugInvocation.dispose() — which is " +
                "what fires the user-registered onDispose { … } hooks. This invariant is " +
                "why SessionDisposableHolder.newDebugInvocation explicitly Disposer.registers " +
                "the invocation itself, not only its internal `disposable` child.",
        )

        // Re-establish parent for AfterEach so teardown doesn't double-dispose.
        parent = Disposer.newDisposable("DebugInvocationTest-parent-rebuilt")
    }
}
