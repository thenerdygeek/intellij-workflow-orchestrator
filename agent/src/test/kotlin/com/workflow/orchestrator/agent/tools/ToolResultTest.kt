package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolResultTest {

    @Test
    fun `error factory creates proper error result`() {
        val result = ToolResult.error("File not found", "not found")
        assertTrue(result.isError)
        assertEquals(ToolResult.ERROR_TOKEN_ESTIMATE, result.tokenEstimate)
        assertEquals("File not found", result.content)
        assertEquals("not found", result.summary)
    }

    @Test
    fun `error factory uses message as summary when summary is omitted`() {
        val result = ToolResult.error("Something went wrong")
        assertTrue(result.isError)
        assertEquals("Something went wrong", result.content)
        assertEquals("Something went wrong", result.summary)
        assertEquals(ToolResult.ERROR_TOKEN_ESTIMATE, result.tokenEstimate)
    }
}
