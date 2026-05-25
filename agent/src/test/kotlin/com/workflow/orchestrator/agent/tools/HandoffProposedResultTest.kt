package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HandoffProposedResultTest {
    @Test
    fun `handoffProposed factory produces HandoffProposed type carrying the context`() {
        val result = ToolResult.handoffProposed(
            content = "summary body",
            summary = "Proposing handoff",
            tokenEstimate = 10,
            context = "## Current Work\nRefactor auth"
        )
        val type = result.type
        assertTrue(type is ToolResultType.HandoffProposed)
        assertEquals("## Current Work\nRefactor auth", (type as ToolResultType.HandoffProposed).context)
    }
}
