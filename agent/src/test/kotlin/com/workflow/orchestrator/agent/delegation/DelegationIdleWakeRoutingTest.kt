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
 * an arriving delegation result/question must be durably queued and then AUTO-WOKEN — never
 * silently dropped.
 *
 * The routing decision is extracted into the pure [idleWakeRoute] so it can be unit
 * tested without constructing AgentService (whose init loads the full tool/memory/hook
 * subsystem). A source-text pin guards that `enqueueNudgeForSession` routes through
 * `enqueueToSession` with `kind=DELEGATION` (Task 2.3 migration): the unified queue's
 * durable persistence (pending_queue.json, DelegationQueuePolicy.durable=true) and its
 * idle auto-wake (DelegationQueuePolicy.autoWakesIdle=true) now own the BUG #2 / BUG #4
 * guarantee that previously lived in the hand-rolled persist-first-then-wake branch.
 *
 * BUG #4 added a `safeToResume` gate (a delegation result for an idle target must not
 * cancel/reset a DIFFERENT live session); the SKIP route is no longer the only "don't
 * wake now" outcome — DEFER_ACTIVE_SESSION joins it, and BOTH leave the nudge in the
 * durable queue until the target session is next resumed.
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
    fun `source pin - enqueueNudgeForSession routes through enqueueToSession with kind=DELEGATION`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
        )
        val fnStart = source.indexOf("fun enqueueNudgeForSession")
        assertTrue(fnStart >= 0, "enqueueNudgeForSession must exist")
        val fnBody = source.substring(fnStart, minOf(fnStart + 1000, source.length))

        assertTrue(
            fnBody.contains("enqueueToSession"),
            "enqueueNudgeForSession must delegate to enqueueToSession (Task 2.3: unified queue owns persist+wake)",
        )
        assertTrue(
            fnBody.contains("QueueSourceKind.DELEGATION") || fnBody.contains("DELEGATION"),
            "enqueueNudgeForSession must enqueue with kind=DELEGATION so DelegationQueuePolicy.durable=true " +
                "covers BUG #2 (guard-rejected wake replays on resume) and autoWakesIdle=true covers BUG #4",
        )
        assertTrue(
            !fnBody.contains("persistDelegationNudgeForLaterResume"),
            "the old hand-rolled persist helper must NOT appear inside enqueueNudgeForSession " +
                "(removed in Task 2.3 — queue persistence is the new carrier)",
        )
        assertTrue(
            !fnBody.contains("autoWakeIdleSession"),
            "enqueueNudgeForSession must NOT call autoWakeIdleSession directly " +
                "(enqueueToSession now owns idle auto-wake via DelegationQueuePolicy.autoWakesIdle)",
        )
    }
}
