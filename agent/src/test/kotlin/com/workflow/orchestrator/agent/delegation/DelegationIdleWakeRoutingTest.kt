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
 * an arriving delegation result/question must AUTO-WAKE the session, not be silently
 * dropped (the old `enqueueNudgeForSession` else-branch).
 *
 * The routing decision is extracted into the pure [idleWakeRoute] so it can be unit
 * tested without constructing AgentService (whose init loads the full tool/memory/hook
 * subsystem). A source-text pin guards that `enqueueNudgeForSession` actually routes
 * through the auto-wake path rather than logging-and-dropping.
 */
class DelegationIdleWakeRoutingTest {

    @Test
    fun `idle session with a registered listener and passing guard wakes`() {
        // This is the case the bug missed entirely — IDE-A loop ended, result arrives.
        assertEquals(
            IdleWakeRoute.WAKE,
            idleWakeRoute(AutoWakeGuardState.Decision.PROCEED, listenerPresent = true),
        )
    }

    @Test
    fun `no listener defers rather than waking`() {
        assertEquals(
            IdleWakeRoute.DEFER_NO_LISTENER,
            idleWakeRoute(AutoWakeGuardState.Decision.PROCEED, listenerPresent = false),
        )
    }

    @Test
    fun `a non-PROCEED guard decision skips the wake`() {
        AutoWakeGuardState.Decision.values()
            .filter { it != AutoWakeGuardState.Decision.PROCEED }
            .forEach { decision ->
                assertEquals(
                    IdleWakeRoute.SKIP_GUARD,
                    idleWakeRoute(decision, listenerPresent = true),
                    "decision $decision must skip the wake",
                )
            }
    }

    @Test
    fun `source pin - enqueueNudgeForSession routes idle nudges to auto-wake, not drop`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
        )
        val fnStart = source.indexOf("fun enqueueNudgeForSession")
        assertTrue(fnStart >= 0, "enqueueNudgeForSession must exist")
        val fnBody = source.substring(fnStart, minOf(fnStart + 1200, source.length))
        assertTrue(
            fnBody.contains("autoWakeIdleSession"),
            "enqueueNudgeForSession must route idle sessions through autoWakeIdleSession (was: silently dropped)",
        )
    }
}
