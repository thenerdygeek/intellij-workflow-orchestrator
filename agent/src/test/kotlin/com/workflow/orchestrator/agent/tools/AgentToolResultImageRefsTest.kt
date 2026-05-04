package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.core.services.ToolResult as CoreToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentToolResultImageRefsTest {

    @Test
    fun `default imageRefs is empty so existing tools are unchanged`() {
        val r = ToolResult(content = "ok", summary = "done", tokenEstimate = 10)
        assertTrue(r.imageRefs.isEmpty())
    }

    @Test
    fun `imageRefs round-trips through copy`() {
        val ref = CoreToolResult.ImageRefData(sha256 = "abc", mime = "image/png", size = 100, originalFilename = "x.png")
        val r = ToolResult(content = "ok", summary = "done", tokenEstimate = 10).copy(imageRefs = listOf(ref))
        assertEquals(1, r.imageRefs.size)
        assertEquals("abc", r.imageRefs[0].sha256)
    }

    @Test
    fun `error factory produces empty imageRefs`() {
        val r = ToolResult.error("boom")
        assertTrue(r.imageRefs.isEmpty())
    }
}
