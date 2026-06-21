package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * B2/B3: [AgentService.releaseSessionState] (the session-end hook that already removes
 * perSessionStates) must also free the other per-session maps that otherwise grow for the whole
 * IDE-window lifetime — the unified message queue and the auto-wake guard state. AgentService is
 * not unit-instantiable (its init loads tools/memory/hooks), so this is a source-contract pin,
 * the same pattern MonitorToolTest uses for its onExit/onFloodStop lambdas.
 */
class AgentServiceSessionCleanupContractTest {

    @Test
    fun `releaseSessionState frees sessionQueues and resets autoWakeGuards`() {
        val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()
        val body = src.substringAfter("fun releaseSessionState(sessionId: String) {")
            .substringBefore("\n    }")
        assertTrue(
            body.contains("sessionQueues.remove(sessionId)"),
            "releaseSessionState must remove the per-session queue entry (B2 leak): $body",
        )
        assertTrue(
            body.contains("autoWakeGuards.resetSession(sessionId)"),
            "releaseSessionState must reset the auto-wake guard for the session (B3 leak): $body",
        )
    }
}
