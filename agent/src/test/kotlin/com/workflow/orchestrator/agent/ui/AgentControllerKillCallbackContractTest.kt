package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentControllerKillCallbackContractTest {

    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt").readText()

    @Test
    fun `kill callback routes through ToolStopCoordinator, not ProcessRegistry directly`() {
        assertTrue(
            src.contains("ToolStopCoordinator.requestStop"),
            "setCefKillCallback must route through ToolStopCoordinator.requestStop",
        )
        // The direct ProcessRegistry.kill in the kill callback is replaced by the coordinator.
        assertFalse(
            Regex("""setCefKillCallback\s*\{[^}]*ProcessRegistry\.kill""").containsMatchIn(src),
            "kill callback must not call ProcessRegistry.kill directly anymore",
        )
    }
}
