package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolOutputProcessorTest {
    @Test
    fun `no grep, no spill, no truncate returns original content and original token estimate`() {
        val r = ToolOutputProcessor.process(
            toolName = "read_file", rawContent = "line1\nline2", rawTokenEstimate = 7,
            grepPattern = null, requestedOutputFile = false, maxChars = 1000, spiller = null,
            truncate = { c, _ -> c }, estimateTokens = { it.length },
        )
        assertEquals("line1\nline2", r.content)
        assertEquals(7, r.tokenEstimate)   // untouched -> keeps original estimate
        assertFalse(r.wasProcessed)
    }

    @Test
    fun `grep filters lines and re-estimates tokens`() {
        val r = ToolOutputProcessor.process(
            toolName = "search_code", rawContent = "apple\nbanana\napricot", rawTokenEstimate = 99,
            grepPattern = "ap", requestedOutputFile = false, maxChars = 1000, spiller = null,
            truncate = { c, _ -> c }, estimateTokens = { it.length },
        )
        assertEquals("apple\napricot", r.content)
        assertEquals("apple\napricot".length, r.tokenEstimate)  // re-estimated after filter
        assertTrue(r.wasProcessed)
    }

    @Test
    fun `truncate shortens and re-estimates`() {
        val r = ToolOutputProcessor.process(
            toolName = "run_command", rawContent = "0123456789", rawTokenEstimate = 50,
            grepPattern = null, requestedOutputFile = false, maxChars = 4, spiller = null,
            truncate = { c, max -> c.take(max) }, estimateTokens = { it.length },
        )
        assertEquals("0123", r.content)
        assertEquals(4, r.tokenEstimate)
        assertTrue(r.wasProcessed)
    }
}
