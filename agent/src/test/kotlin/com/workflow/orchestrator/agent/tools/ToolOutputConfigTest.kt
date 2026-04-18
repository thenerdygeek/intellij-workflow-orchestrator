package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ToolOutputConfigTest {

    @Test
    fun `default config has 50K max chars`() {
        assertEquals(50_000, ToolOutputConfig.DEFAULT.maxChars)
    }

    @Test
    fun `command config has 100K max chars`() {
        assertEquals(100_000, ToolOutputConfig.COMMAND.maxChars)
    }

    @Test
    fun `grep filter keeps only matching lines`() {
        val input = "line 1 error\nline 2 ok\nline 3 error found\nline 4"
        val result = ToolOutputConfig.applyGrep(input, "error")
        assertEquals("line 1 error\nline 3 error found", result)
    }

    @Test
    fun `grep filter with regex pattern`() {
        val input = "ERROR: something\nWARN: something\nERROR: other"
        val result = ToolOutputConfig.applyGrep(input, "^ERROR:")
        assertEquals("ERROR: something\nERROR: other", result)
    }

    @Test
    fun `grep with invalid regex returns original content`() {
        val input = "some content"
        val result = ToolOutputConfig.applyGrep(input, "[invalid")
        assertEquals(input, result)
    }

    @Test
    fun `grep with no matches returns empty string`() {
        val input = "line 1\nline 2\nline 3"
        val result = ToolOutputConfig.applyGrep(input, "NONEXISTENT")
        assertEquals("", result)
    }

    @Test
    fun `head tail keeps first and last lines`() {
        val lines = (1..100).joinToString("\n") { "line $it" }
        val result = ToolOutputConfig.applyHeadTail(lines, head = 5, tail = 3)
        assertTrue(result.startsWith("line 1\n"))
        assertTrue(result.contains("line 5\n"))
        assertTrue(result.contains("[... 92 lines omitted ...]"))
        assertTrue(result.contains("line 98\n"))
        assertTrue(result.endsWith("line 100"))
    }

    @Test
    fun `head tail with short content returns unchanged`() {
        val input = "line 1\nline 2\nline 3"
        val result = ToolOutputConfig.applyHeadTail(input, head = 5, tail = 5)
        assertEquals(input, result)
    }

    @Test
    fun `head tail with exact boundary returns unchanged`() {
        val lines = (1..8).joinToString("\n") { "line $it" }
        val result = ToolOutputConfig.applyHeadTail(lines, head = 5, tail = 3)
        assertEquals(lines, result)  // 8 lines = 5 + 3, no omission needed
    }
}
