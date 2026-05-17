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
        assert("appendSubAgentThinking" in block) {
            "onSubagentProgress must route thinkingDelta via appendSubAgentThinking. Block:\n$block"
        }
        assert("endSubAgentThinking" in block) {
            "onSubagentProgress must route thinkingEnd via endSubAgentThinking. Block:\n$block"
        }
    }
}
