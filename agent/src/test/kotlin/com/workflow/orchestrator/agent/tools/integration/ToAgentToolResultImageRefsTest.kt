package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.services.ToolResult as CoreToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToAgentToolResultImageRefsTest {

    @Test
    fun `core imageRefs propagate to agent ToolResult`() {
        val ref = CoreToolResult.ImageRefData(sha256 = "abc", mime = "image/png", size = 100, originalFilename = "x.png")
        val core = CoreToolResult(data = "ok", summary = "done", imageRefs = listOf(ref))
        val agent = core.toAgentToolResult()
        assertEquals(1, agent.imageRefs.size)
        assertEquals("abc", agent.imageRefs[0].sha256)
    }

    @Test
    fun `core with no imageRefs produces agent with empty imageRefs`() {
        val core = CoreToolResult(data = "ok", summary = "done")
        val agent = core.toAgentToolResult()
        assertTrue(agent.imageRefs.isEmpty())
    }
}
