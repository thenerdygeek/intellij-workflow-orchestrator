package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.agent.tools.background.AutoWakeGuardState
import com.workflow.orchestrator.agent.tools.background.IdleWakeRoute
import com.workflow.orchestrator.agent.tools.background.idleWakeRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression coverage for the dropped-delegation-result bug: when IDE-A's orchestrator
 * loop has already ended (the normal state after delegating and completing the turn),
 * an arriving delegation result/question must be PERSISTED and then AUTO-WOKEN — never
 * silently dropped (the old `enqueueNudgeForSession` else-branch).
 *
 * The routing decision is extracted into the pure [idleWakeRoute] so it can be unit
 * tested without constructing AgentService (whose init loads the full tool/memory/hook
 * subsystem). A source-text pin guards that `enqueueNudgeForSession`:
 *   (a) PERSISTS the nudge BEFORE auto-wake (BUG #2 — so a guard-rejected / active-session-
 *       deferred nudge replays on the next resume rather than vanishing), and
 *   (b) still routes through the auto-wake path rather than logging-and-dropping.
 *
 * BUG #4 added a `safeToResume` gate (a delegation result for an idle target must not
 * cancel/reset a DIFFERENT live session); the SKIP route is no longer the only "don't
 * wake now" outcome — DEFER_ACTIVE_SESSION joins it, and BOTH leave the nudge persisted.
 */
class DelegationIdleWakeRoutingTest {

    @Test
    fun `idle session with a registered listener and passing guard and safe-to-resume wakes`() {
        // This is the case the bug missed entirely — IDE-A loop ended, result arrives.
        assertEquals(
            IdleWakeRoute.WAKE,
            idleWakeRoute(AutoWakeGuardState.Decision.PROCEED, listenerPresent = true, safeToResume = true),
        )
    }

    @Test
    fun `no listener defers rather than waking`() {
        assertEquals(
            IdleWakeRoute.DEFER_NO_LISTENER,
            idleWakeRoute(AutoWakeGuardState.Decision.PROCEED, listenerPresent = false, safeToResume = true),
        )
    }

    @Test
    fun `a different active session defers rather than hijacking it`() {
        assertEquals(
            IdleWakeRoute.DEFER_ACTIVE_SESSION,
            idleWakeRoute(AutoWakeGuardState.Decision.PROCEED, listenerPresent = true, safeToResume = false),
        )
    }

    @Test
    fun `a non-PROCEED guard decision skips the wake (still persisted for replay)`() {
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
    fun `source pin - enqueueNudgeForSession persists the idle nudge BEFORE auto-wake, never drops`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
        )
        val fnStart = source.indexOf("fun enqueueNudgeForSession")
        assertTrue(fnStart >= 0, "enqueueNudgeForSession must exist")
        val fnBody = source.substring(fnStart, minOf(fnStart + 1400, source.length))

        val persistIdx = fnBody.indexOf("persistDelegationNudgeForLaterResume")
        val wakeIdx = fnBody.indexOf("autoWakeIdleSession")
        assertTrue(
            persistIdx >= 0,
            "enqueueNudgeForSession must PERSIST the idle nudge (BUG #2) so a guard-rejected wake replays on resume",
        )
        assertTrue(
            wakeIdx >= 0,
            "enqueueNudgeForSession must route idle sessions through autoWakeIdleSession (was: silently dropped)",
        )
        assertTrue(
            persistIdx < wakeIdx,
            "the persist must happen BEFORE the auto-wake (mirrors onBackgroundCompletion's persist-first contract)",
        )
    }
}
