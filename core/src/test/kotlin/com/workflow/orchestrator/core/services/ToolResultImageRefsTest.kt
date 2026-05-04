package com.workflow.orchestrator.core.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolResultImageRefsTest {

    @Test
    fun `default imageRefs is empty so existing tools are unchanged`() {
        val r = ToolResult.success(data = "ok", summary = "done")
        assertTrue(r.imageRefs.isEmpty())
    }

    @Test
    fun `imageRefs round-trips through copy`() {
        val ref = ToolResult.ImageRefData(
            sha256 = "abc123", mime = "image/png", size = 100, originalFilename = "x.png"
        )
        val r = ToolResult.success(data = "ok", summary = "done").copy(imageRefs = listOf(ref))
        assertEquals(1, r.imageRefs.size)
        assertEquals("abc123", r.imageRefs[0].sha256)
    }
}
