package com.workflow.orchestrator.agent.tools.background

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BUG #4 — an async auto-wake (background completion OR cross-IDE delegation result)
 * targeting an IDLE session S_old must NEVER tear down a DIFFERENT, actively-running
 * session S_new. The destructive delivery (`resumeSession` → `prepareForReplay` →
 * `cancelCurrentTask()` + `dashboard.reset()`) is only safe when the target session is
 * the one the user is currently on (or there is no active session at all).
 *
 * The safety decision is folded into the pure [idleWakeRoute] via a `safeToResume`
 * flag, and [IdleSessionWaker] gates the listener fire on it — when not safe it routes
 * to [IdleWakeRoute.DEFER_ACTIVE_SESSION] and leaves the nudge persisted for replay
 * (the persist happens at the AgentService call site, mirroring background completions).
 */
class SafeAutoWakeRouteTest {

    // ── pure routing seam ────────────────────────────────────────────────────

    @Test
    fun `passing guard plus listener plus safe-to-resume wakes`() {
        assertEquals(
            IdleWakeRoute.WAKE,
            idleWakeRoute(AutoWakeGuardState.Decision.PROCEED, listenerPresent = true, safeToResume = true),
        )
    }

    @Test
    fun `not safe to resume defers even when guard passes and listener present`() {
        assertEquals(
            IdleWakeRoute.DEFER_ACTIVE_SESSION,
            idleWakeRoute(AutoWakeGuardState.Decision.PROCEED, listenerPresent = true, safeToResume = false),
        )
    }

    @Test
    fun `guard rejection skips when safe to resume`() {
        AutoWakeGuardState.Decision.values()
            .filter { it != AutoWakeGuardState.Decision.PROCEED }
            .forEach { decision ->
                assertEquals(
                    IdleWakeRoute.SKIP_GUARD,
                    idleWakeRoute(decision, listenerPresent = true, safeToResume = true),
                    "decision $decision must skip the wake",
                )
            }
    }

    @Test
    fun `safety gate wins over the guard decision (no cap burned for a non-active target)`() {
        // When a different session is active, the route is DEFER_ACTIVE_SESSION even if the
        // guard would otherwise PROCEED — the safety gate is evaluated first so the per-session
        // cap/cooldown is left intact for the eventual resume of the target.
        assertEquals(
            IdleWakeRoute.DEFER_ACTIVE_SESSION,
            idleWakeRoute(AutoWakeGuardState.Decision.PROCEED, listenerPresent = true, safeToResume = false),
        )
    }

    @Test
    fun `no listener still defers as DEFER_NO_LISTENER when otherwise safe`() {
        assertEquals(
            IdleWakeRoute.DEFER_NO_LISTENER,
            idleWakeRoute(AutoWakeGuardState.Decision.PROCEED, listenerPresent = false, safeToResume = true),
        )
    }

    // ── IdleSessionWaker behaviour ───────────────────────────────────────────

    private fun waker(
        activeSessionId: () -> String?,
        listener: ((String, String) -> Unit)?,
        guards: AutoWakeGuardState = AutoWakeGuardState(),
    ) = IdleSessionWaker(
        guards = guards,
        settings = { AutoWakeSettings(enabled = true, cap = 10, cooldownMs = 0) },
        listener = { listener },
        activeSessionId = activeSessionId,
        invoker = { it() },
    )

    @Test
    fun `wake fires when target is the active session`() {
        val calls = mutableListOf<Pair<String, String>>()
        val w = waker(activeSessionId = { "S_old" }, listener = { sid, t -> calls.add(sid to t) })

        val route = w.wake("S_old", "result", "delegation")

        assertEquals(IdleWakeRoute.WAKE, route)
        assertEquals(listOf("S_old" to "result"), calls)
    }

    @Test
    fun `wake fires when there is no active session`() {
        val calls = mutableListOf<Pair<String, String>>()
        val w = waker(activeSessionId = { null }, listener = { sid, t -> calls.add(sid to t) })

        val route = w.wake("S_old", "result", "delegation")

        assertEquals(IdleWakeRoute.WAKE, route)
        assertEquals(listOf("S_old" to "result"), calls)
    }

    @Test
    fun `wake DEFERS and does NOT fire the listener when a DIFFERENT session is active`() {
        // S_new is the live session; the S_old delegation result must not hijack it.
        val calls = mutableListOf<Pair<String, String>>()
        val w = waker(activeSessionId = { "S_new" }, listener = { sid, t -> calls.add(sid to t) })

        val route = w.wake("S_old", "result", "delegation")

        assertEquals(IdleWakeRoute.DEFER_ACTIVE_SESSION, route)
        assertTrue(calls.isEmpty(), "the auto-wake listener (resumeSession) must NOT fire for a non-active target")
    }

    @Test
    fun `a deferred-active wake does NOT consume the per-session cap`() {
        // If a different session is active, the cap must be untouched so the nudge can
        // still wake S_old later when the user returns to it.
        val guards = AutoWakeGuardState()
        val w = waker(activeSessionId = { "S_new" }, listener = { _, _ -> }, guards = guards)

        w.wake("S_old", "result", "delegation")

        assertEquals(0, guards.attemptCount("S_old"), "DEFER_ACTIVE_SESSION must not burn a cap slot")
    }

    // ── shared-seam wiring (BUG #4 protects BOTH paths) ──────────────────────

    @Test
    fun `source pin - AgentService wires the live session id into the shared idleWaker`() {
        // The single shared IdleSessionWaker is used by BOTH cross-IDE delegation
        // (enqueueNudgeForSession) and background-process completion
        // (autoResumeForBackgroundCompletion), so wiring activeSessionId once protects both
        // async-completion paths from hijacking a different live session.
        val source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
        )
        assertTrue(
            source.contains("activeSessionId = { activeTask.get()?.sessionId }"),
            "AgentService must wire the live loop's session id into IdleSessionWaker so a " +
                "non-active target defers instead of cancelling the active session",
        )
    }

    @Test
    fun `source pin - AgentController auto-wake listener re-checks the active session before resuming`() {
        // Defense-in-depth: the waker decides at fire time but the resume runs later on the EDT
        // (invokeLater). wireAutoWakeListener must RE-CHECK at delivery time and bail if a
        // different session is now running, so it can't cancel/reset the live session.
        val source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt")
        )
        val fnStart = source.indexOf("private fun wireAutoWakeListener()")
        assertTrue(fnStart >= 0, "wireAutoWakeListener must exist")
        val fnBody = source.substring(fnStart, minOf(fnStart + 1600, source.length))
        val guardIdx = fnBody.indexOf("currentSessionId != sessionId")
        val resumeIdx = fnBody.indexOf("resumeSession(sessionId")
        assertTrue(guardIdx >= 0, "the listener must compare currentSessionId against the target sessionId")
        assertTrue(resumeIdx >= 0, "the listener must call resumeSession for the safe case")
        assertTrue(
            guardIdx < resumeIdx,
            "the different-active-session bail-out must come BEFORE the resumeSession call",
        )
    }
}
