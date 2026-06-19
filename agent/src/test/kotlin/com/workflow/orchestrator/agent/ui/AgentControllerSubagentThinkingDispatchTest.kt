package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Test

class AgentControllerSubagentThinkingDispatchTest {

    @Test
    fun `onSubagentProgress dispatches thinkingDelta and thinkingEnd to dashboard`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
        ).readText()
        val block = src.substringAfter("private fun onSubagentProgress(")
            .substringBefore("\n    private fun ")
        // P1-12: thinking deltas no longer hit the bridge per SSE chunk — they coalesce
        // through the agentId-keyed subAgentThinkingBatcher, whose onFlush delivers via
        // dashboard.appendSubAgentThinking (see the field declaration + the batching pins
        // in AgentControllerThinkingBatchingTest).
        assert("subAgentThinkingBatcher.append(" in block) {
            "onSubagentProgress must route thinkingDelta into the keyed batcher. Block:\n$block"
        }
        assert("endSubAgentThinking" in block) {
            "onSubagentProgress must route thinkingEnd via endSubAgentThinking. Block:\n$block"
        }
        assert("appendSubAgentThinking" in src.substringAfter("private val subAgentThinkingBatcher")) {
            "the batcher's onFlush must deliver via dashboard.appendSubAgentThinking"
        }
    }
}
