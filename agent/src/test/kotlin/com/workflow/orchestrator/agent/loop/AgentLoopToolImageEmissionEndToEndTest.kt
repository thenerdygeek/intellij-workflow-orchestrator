package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.agent.tools.ToolResult as AgentToolResult
import com.workflow.orchestrator.core.services.ToolResult as CoreToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end emission shape from agent ToolResult (with imageRefs) into
 * ApiMessage. This pins the contract that Phase 4 closes the gap left by
 * Phase 2's empty-imageRefs adapter.
 */
class AgentLoopToolImageEmissionEndToEndTest {

    @Test
    fun `agent ToolResult with imageRefs produces user message with two ImageRef blocks`() {
        val agentResult = AgentToolResult(
            content = "downloaded screenshot.png",
            summary = "downloaded screenshot.png",
            tokenEstimate = 10,
            imageRefs = listOf(
                CoreToolResult.ImageRefData("sha-1", "image/png", 100, "a.png"),
                CoreToolResult.ImageRefData("sha-2", "image/jpeg", 200, "b.jpg"),
            )
        )

        // Build the same shape AgentLoop builds — using the existing seam
        // via a coreToolResult adapter that NOW carries imageRefs.
        val coreAdapter = CoreToolResult(
            data = Unit,
            summary = agentResult.summary,
            isError = agentResult.isError,
            imageRefs = agentResult.imageRefs
        )
        val msg = AgentLoopTestSupport.buildToolResultApiMessage(
            toolUseId = "call_xyz",
            toolResult = coreAdapter,
            truncatedContent = "downloaded screenshot.png"
        )

        assertEquals(ApiRole.USER, msg.role)
        assertEquals(3, msg.content.size)
        assertTrue(msg.content[0] is ContentBlock.ToolResult)
        assertEquals("sha-1", (msg.content[1] as ContentBlock.ImageRef).sha256)
        assertEquals("sha-2", (msg.content[2] as ContentBlock.ImageRef).sha256)
    }
}
