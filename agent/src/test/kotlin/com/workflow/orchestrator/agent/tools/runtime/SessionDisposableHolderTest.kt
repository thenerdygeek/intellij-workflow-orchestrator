package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [SessionDisposableHolder] — Phase 3 Task 2.2.
 *
 * Mirrors the pattern used in [RunInvocationTest]: a real
 * `Disposer.newDisposable` root disposed in `tearDown`, pure MockK-free
 * Disposer assertions. `SessionDisposableHolder` is a thin pure helper
 * that `AgentService` delegates to for the per-session Disposable
 * lifecycle, so these tests stand in for unit coverage that can't cover
 * `AgentService` directly (its `init` has heavy side effects — memory
 * system init, disk I/O, hook loading — which require an IntelliJ test
 * fixture).
 *
 * The observable behavior under test mirrors the `AgentService` surface:
 *  - `newRunInvocation(name)` returns a `RunInvocation` parented to the
 *     current session (so `Disposer.dispose(session)` cascades to it).
 *  - Repeated calls produce distinct `RunInvocation` instances.
 *  - `resetSession()` disposes outstanding invocations and installs a
 *     fresh parent so invocations created afterward are NOT affected by
 *     disposing the previous session.
 */
class SessionDisposableHolderTest {

    private lateinit var root: Disposable

    @BeforeEach
    fun setUp() {
        root = Disposer.newDisposable("SessionDisposableHolderTest-root")
    }

    @AfterEach
    fun tearDown() {
        Disposer.dispose(root)
    }

    @Test
    fun `newRunInvocation returns a RunInvocation tied to the current session`() {
        val holder = SessionDisposableHolder(root, "test-session")

        val invocation = holder.newRunInvocation("run-tests-foo")

        // Kotlin static typing already proves `invocation` is a RunInvocation
        // and therefore a Disposable — the return type IS RunInvocation.
        // The behavioural assertion below is what matters: the returned
        // instance must participate in the session Disposer chain so a
        // cascade on resetSession/parent-dispose actually invokes
        // RunInvocation.dispose().
        @Suppress("USELESS_IS_CHECK")
        run {
            assertTrue(
                invocation is RunInvocation,
                "newRunInvocation must return a RunInvocation instance (static-type " +
                    "assertion — mainly here to spell out the contract for future refactors)",
            )
        }

        // Proving the invocation is parented to the session: dispose the
        // holder (which disposes the session) and observe that the
        // invocation's cleanup runs.
        val calls = AtomicInteger(0)
        invocation.onDispose { calls.incrementAndGet() }

        Disposer.dispose(holder)

        assertEquals(
            1,
            calls.get(),
            "Disposing the holder must cascade to the invocation's onDispose callbacks",
        )
    }

    @Test
    fun `newRunInvocation with same name produces distinct instances`() {
        val holder = SessionDisposableHolder(root, "test-session")

        val first = holder.newRunInvocation("run-tests-foo")
        val second = holder.newRunInvocation("run-tests-foo")

        assertNotSame(
            first,
            second,
            "Each newRunInvocation call must return a fresh RunInvocation even with identical names",
        )

        // Both should still be under the same session — disposing the
        // session via Disposer.dispose(holder) cascades to both.
        val firstFired = AtomicInteger(0)
        val secondFired = AtomicInteger(0)
        first.onDispose { firstFired.incrementAndGet() }
        second.onDispose { secondFired.incrementAndGet() }

        Disposer.dispose(holder)

        assertEquals(1, firstFired.get(), "first invocation's onDispose must fire")
        assertEquals(1, secondFired.get(), "second invocation's onDispose must fire")
    }

    @Test
    fun `resetSession disposes outstanding invocations`() {
        val holder = SessionDisposableHolder(root, "test-session")

        val invocation = holder.newRunInvocation("run-tests-foo")
        val fired = AtomicInteger(0)
        invocation.onDispose { fired.incrementAndGet() }

        // Simulate the "new chat" boundary.
        holder.resetSession()

        assertEquals(
            1,
            fired.get(),
            "resetSession must cascade-dispose RunInvocations created under the prior session",
        )
    }

    @Test
    fun `newRunInvocation after resetSession has a fresh session parent`() {
        val holder = SessionDisposableHolder(root, "test-session")

        // Session A — one invocation.
        val invocationA = holder.newRunInvocation("run-tests-a")
        val firedA = AtomicInteger(0)
        invocationA.onDispose { firedA.incrementAndGet() }

        holder.resetSession()
        assertEquals(1, firedA.get(), "invocationA must fire once on first resetSession")

        // Session B — a new invocation created AFTER the reset should be
        // parented to the new session, NOT the disposed-A session.
        val invocationB = holder.newRunInvocation("run-tests-b")
        val firedB = AtomicInteger(0)
        invocationB.onDispose { firedB.incrementAndGet() }

        // A second reset disposes session B. Critically:
        //  - invocationA must NOT double-fire (it belongs to the already-
        //     disposed session A).
        //  - invocationB must fire exactly once (it belongs to session B).
        holder.resetSession()

        assertEquals(
            1,
            firedA.get(),
            "invocationA must NOT double-fire — it belonged to the first, already-disposed session",
        )
        assertEquals(
            1,
            firedB.get(),
            "invocationB must fire once — it belongs to the second session that was just disposed",
        )
    }

    @Test
    fun `disposing the parent disposes the holder's session chain`() {
        // Proves the holder is properly wired as a child of its parent
        // Disposable — i.e. if AgentService is disposed (project closed),
        // every RunInvocation under every past session goes down with it.
        val parent = Disposer.newDisposable("parent-scope")
        val holder = SessionDisposableHolder(parent, "test-session")

        val invocation = holder.newRunInvocation("run-tests-foo")
        val fired = AtomicInteger(0)
        invocation.onDispose { fired.incrementAndGet() }

        Disposer.dispose(parent)

        assertEquals(
            1,
            fired.get(),
            "Disposing the parent must cascade through the session Disposable to the RunInvocation",
        )
    }
}
