package com.workflow.orchestrator.core.services

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ToolResultTest {

    @Test
    fun `error factory leaves data null without type-system bypass`() {
        val r = ToolResult.error<String>(summary = "boom")
        assertTrue(r.isError)
        assertNull(r.data)
        assertEquals("boom", r.summary)
    }

    @Test
    fun `success factory carries data through`() {
        val r = ToolResult.success(data = "ok", summary = "x")
        assertFalse(r.isError)
        assertEquals("ok", r.data)
    }
}
