package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.MessageBusConnection
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RunInvocation] — Phase 3 Task 2.1.
 *
 * Uses a real [Disposer] parent for each test (disposed in [tearDown]) so we
 * don't have to mock the static `Disposer.newDisposable` factory. Everything
 * else is MockK — no IntelliJ test fixture required.
 */
class RunInvocationTest {

    private lateinit var parent: Disposable

    @BeforeEach
    fun setUp() {
        parent = Disposer.newDisposable("RunInvocationTest-parent")
    }

    @AfterEach
    fun tearDown() {
        // Even if the test forgot, tear down the whole subtree so we don't
        // leak Disposables between tests.
        Disposer.dispose(parent)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Process handler destruction
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispose destroys the process handler if not terminated`() {
        val invocation = RunInvocation(parent, "handler-alive")
        val handler = mockk<ProcessHandler>(relaxed = true)
        every { handler.isProcessTerminated } returns false
        every { handler.destroyProcess() } just Runs

        invocation.processHandlerRef.set(handler)

        Disposer.dispose(invocation)

        verify(exactly = 1) { handler.destroyProcess() }
    }

    @Test
    fun `dispose skips process destruction if already terminated`() {
        val invocation = RunInvocation(parent, "handler-terminated")
        val handler = mockk<ProcessHandler>(relaxed = true)
        every { handler.isProcessTerminated } returns true

        invocation.processHandlerRef.set(handler)

        Disposer.dispose(invocation)

        verify(exactly = 0) { handler.destroyProcess() }
    }

    @Test
    fun `dispose handles null process handler without throwing`() {
        val invocation = RunInvocation(parent, "handler-null")
        // processHandlerRef is null by default — no set() call.

        assertDoesNotThrow { Disposer.dispose(invocation) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. onDispose ordering
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispose fires all onDispose callbacks in LIFO order`() {
        val invocation = RunInvocation(parent, "callback-order")
        val log = mutableListOf<String>()

        invocation.onDispose { log.add("first-registered") }
        invocation.onDispose { log.add("second-registered") }
        invocation.onDispose { log.add("third-registered") }

        Disposer.dispose(invocation)

        assertEquals(
            listOf("third-registered", "second-registered", "first-registered"),
            log,
            "onDispose callbacks must fire in LIFO order — last registered runs first " +
                "so outermost resources (e.g. removeRunContent) are released last, mirroring " +
                "natural construction unwind semantics."
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. attachListener defense-in-depth proxy
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `attachListener wraps the listener so post-dispose events are no-ops`() {
        val invocation = RunInvocation(parent, "listener-proxy")
        val viewer = mockk<TestResultsViewer>(relaxed = true)
        val proxySlot = slot<TestResultsViewer.EventsListener>()
        every { viewer.addEventsListener(capture(proxySlot)) } just Runs

        var finishedCount = 0
        val rawListener = object : TestResultsViewer.EventsListener {
            override fun onTestingStarted(sender: TestResultsViewer) {}
            override fun onTestingFinished(sender: TestResultsViewer) { finishedCount++ }
            override fun onTestNodeAdded(sender: TestResultsViewer, test: SMTestProxy) {}
        }

        invocation.attachListener(rawListener, viewer)

        // Pre-dispose: the proxy delegates to the raw listener.
        val proxy = proxySlot.captured
        proxy.onTestingFinished(viewer)
        assertEquals(1, finishedCount, "Pre-dispose event must reach the raw listener")

        // Dispose flips the internal `disposed` flag.
        Disposer.dispose(invocation)

        // Post-dispose: the proxy swallows the event — the raw listener is not invoked.
        proxy.onTestingFinished(viewer)
        assertEquals(
            1,
            finishedCount,
            "Post-dispose event must be silently dropped by the RunInvocation proxy — " +
                "TestResultsViewer has no removeEventsListener API, so this defense-in-depth " +
                "gate is the only thing preventing a late framework callback from racing " +
                "a consumed continuation."
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. MessageBusConnection disposal
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `subscribeTopic disconnects the MessageBusConnection on dispose`() {
        val invocation = RunInvocation(parent, "mbc-dispose")
        val connection = mockk<MessageBusConnection>(relaxed = true)
        every { connection.dispose() } just Runs

        invocation.subscribeTopic(connection)

        Disposer.dispose(invocation)

        // MessageBusConnection extends Disposable; `Disposer.register(disposable, connection)`
        // means the framework calls .dispose() on the connection when the parent disposable
        // is disposed. Verifying that the mock .dispose() was invoked proves the chain works.
        verify(exactly = 1) { connection.dispose() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. ProcessListener 2-arg form uses invocation's Disposable
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `attachProcessListener with 2-arg form uses the invocation's Disposable as parent`() {
        val invocation = RunInvocation(parent, "proc-listener-2arg")
        val handler = mockk<ProcessHandler>(relaxed = true)
        val parentSlot = slot<Disposable>()
        val listenerSlot = slot<ProcessListener>()
        every { handler.addProcessListener(capture(listenerSlot), capture(parentSlot)) } just Runs

        val listener = object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {}
            override fun processTerminated(event: ProcessEvent) {}
        }

        invocation.attachProcessListener(handler, listener)

        // Crucial: the parent Disposable passed to the 2-arg addProcessListener
        // MUST be the invocation's internal Disposable — otherwise Disposer.dispose(invocation)
        // won't trigger auto-cleanup of the listener.
        verify(exactly = 1) { handler.addProcessListener(any(), any<Disposable>()) }
        verify(exactly = 0) { handler.addProcessListener(any()) }
        assertSame(
            invocation.disposable,
            parentSlot.captured,
            "2-arg addProcessListener must use RunInvocation.disposable as parent so " +
                "the framework auto-removes the listener when the invocation disposes."
        )
        assertSame(listener, listenerSlot.captured)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. Idempotency
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispose is idempotent — calling twice does not throw or double-cleanup`() {
        val invocation = RunInvocation(parent, "idempotent")
        val handler = mockk<ProcessHandler>(relaxed = true)
        every { handler.isProcessTerminated } returns false
        every { handler.destroyProcess() } just Runs

        var callbackInvocations = 0
        invocation.processHandlerRef.set(handler)
        invocation.onDispose { callbackInvocations++ }

        // First dispose — runs cleanup.
        Disposer.dispose(invocation)
        assertEquals(1, callbackInvocations, "First dispose must run the callback")
        verify(exactly = 1) { handler.destroyProcess() }

        // Second dispose — no-op (guarded by internal AtomicBoolean).
        // We call invocation.dispose() directly because Disposer itself
        // already removed the disposable chain on the first call.
        assertDoesNotThrow { invocation.dispose() }

        assertEquals(
            1,
            callbackInvocations,
            "Second dispose must be a no-op — the internal disposed flag must prevent " +
                "the callback from firing again"
        )
        verify(exactly = 1) { handler.destroyProcess() }
        assertTrue(
            true,
            "Idempotency verified: dispose() called twice, cleanup ran once, no exceptions."
        )
    }
}
