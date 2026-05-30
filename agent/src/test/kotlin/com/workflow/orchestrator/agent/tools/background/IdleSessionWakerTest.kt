package com.workflow.orchestrator.agent.tools.background

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behavioral coverage for the auto-wake orchestration that delivers an async completion
 * (background process OR cross-IDE delegation result) to an idle session. Extracted from
 * AgentService precisely so this wiring — "registered listener + passing guard ⇒ the
 * listener is invoked with the synthetic message" — can be tested without constructing the
 * full service. Uses an inline [invoker] (no EDT) and a real [AutoWakeGuardState] so the
 * cap/cooldown behaviour is exercised end-to-end.
 */
class IdleSessionWakerTest {

    private fun waker(
        guards: AutoWakeGuardState = AutoWakeGuardState(),
        enabled: Boolean = true,
        cap: Int = 10,
        cooldownMs: Long = 0,
        listener: ((String, String) -> Unit)?,
        captureLog: MutableList<String> = mutableListOf(),
    ) = IdleSessionWaker(
        guards = guards,
        settings = { AutoWakeSettings(enabled, cap, cooldownMs) },
        listener = { listener },
        invoker = { it() },                  // inline — no EDT in tests
        onLog = { captureLog.add(it) },
    )

    @Test
    fun `wakes by invoking the listener with the synthetic message`() {
        val calls = mutableListOf<Pair<String, String>>()
        val w = waker(listener = { sid, text -> calls.add(sid to text) })

        val route = w.wake("sess-1", "the delegated result", "delegation")

        assertEquals(IdleWakeRoute.WAKE, route)
        assertEquals(listOf("sess-1" to "the delegated result"), calls)
    }

    @Test
    fun `with no listener it defers and does not invoke`() {
        var invokerRan = false
        val route = IdleSessionWaker(
            guards = AutoWakeGuardState(),
            settings = { AutoWakeSettings(true, 10, 0) },
            listener = { null },
            invoker = { invokerRan = true; it() },
        ).wake("s", "x", "src")

        assertEquals(IdleWakeRoute.DEFER_NO_LISTENER, route)
        assertTrue(!invokerRan, "the invoker must not run when no listener is registered")
    }

    @Test
    fun `disabled setting skips the wake without invoking`() {
        val calls = mutableListOf<Pair<String, String>>()
        val w = waker(enabled = false, listener = { sid, text -> calls.add(sid to text) })

        val route = w.wake("s", "x", "src")

        assertEquals(IdleWakeRoute.SKIP_GUARD, route)
        assertTrue(calls.isEmpty(), "a disabled toggle must not invoke the listener")
    }

    @Test
    fun `per-session cap is enforced across wakes (shared guard state)`() {
        val calls = mutableListOf<String>()
        val guards = AutoWakeGuardState()
        val w = waker(guards = guards, cap = 1, listener = { sid, _ -> calls.add(sid) })

        val first = w.wake("s", "a", "src")
        val second = w.wake("s", "b", "src")

        assertEquals(IdleWakeRoute.WAKE, first)
        assertEquals(IdleWakeRoute.SKIP_GUARD, second, "second wake must be capped")
        assertEquals(listOf("s"), calls, "only the first wake invokes the listener")
    }

    @Test
    fun `a throwing listener does not propagate out of wake`() {
        val w = waker(listener = { _, _ -> throw RuntimeException("boom") })
        val route = w.wake("s", "x", "src")   // must not throw
        assertEquals(IdleWakeRoute.WAKE, route)
    }
}
