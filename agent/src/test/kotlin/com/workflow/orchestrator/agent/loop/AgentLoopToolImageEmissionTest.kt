package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.core.services.ToolResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Pins the shape of the api_conversation_history.json USER message that
 * AgentLoop writes after a tool returns image refs. Single message, two
 * blocks: ToolResult (text) + one ImageRef per attachment.
 */
class AgentLoopToolImageEmissionTest {

    @Test
    fun `tool result with imageRefs produces a single user message with toolResult plus imageRefs`() {
        val toolResult = ToolResult(
            data = "ok",
            summary = "downloaded screenshot.png",
            imageRefs = listOf(
                ToolResult.ImageRefData("sha-1", "image/png", 100, "a.png"),
                ToolResult.ImageRefData("sha-2", "image/jpeg", 200, "b.jpg"),
            )
        )

        val message = AgentLoopTestSupport.buildToolResultApiMessage(
            toolUseId = "call_xyz",
            toolResult = toolResult,
            truncatedContent = "downloaded screenshot.png"
        )

        assertEquals(ApiRole.USER, message.role)
        assertEquals(3, message.content.size)
        val tr = message.content[0] as ContentBlock.ToolResult
        assertEquals("call_xyz", tr.toolUseId)
        assertEquals("downloaded screenshot.png", tr.content)
        assertFalse(tr.isError)
        val img1 = message.content[1] as ContentBlock.ImageRef
        assertEquals("sha-1", img1.sha256)
        assertEquals("image/png", img1.mime)
        val img2 = message.content[2] as ContentBlock.ImageRef
        assertEquals("sha-2", img2.sha256)
    }

    @Test
    fun `tool result with no imageRefs emits the legacy single-block shape`() {
        val toolResult = ToolResult(data = "ok", summary = "no images")
        val message = AgentLoopTestSupport.buildToolResultApiMessage(
            toolUseId = "call_legacy", toolResult = toolResult, truncatedContent = "no images"
        )
        assertEquals(1, message.content.size)
        assertTrue(message.content[0] is ContentBlock.ToolResult)
    }
}
