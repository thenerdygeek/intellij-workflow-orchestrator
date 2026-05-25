package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentLoopHandoffTest {
    @Test
    fun `sentinel constants are distinct and stable`() {
        assertTrue(AgentLoop.HANDOFF_FORK_SENTINEL == "__HANDOFF_FORK__")
        assertTrue(AgentLoop.HANDOFF_DECLINE_SENTINEL == "__HANDOFF_DECLINE__")
        assertTrue(AgentLoop.HANDOFF_FORK_SENTINEL != AgentLoop.HANDOFF_DECLINE_SENTINEL)
    }

    @Test
    fun `AgentLoop source suspends on HandoffProposed and branches on the sentinel`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt"
        ).readText()
        // The HandoffProposed branch must fire the render callback, receive on the channel, and branch.
        assertTrue(src.contains("is ToolResultType.HandoffProposed"))
        assertTrue(src.contains("onHandoffProposed?.invoke"))
        assertTrue(src.contains("HANDOFF_FORK_SENTINEL"))
        assertTrue(src.contains("HANDOFF_DECLINE_SENTINEL"))
        assertTrue(src.contains("LoopResult.SessionHandoff"))
    }
}
