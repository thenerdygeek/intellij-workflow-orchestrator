package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OutputFilteringIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `grep then truncate reduces context size`() {
        // Large output with mixed content
        val lines = (1..1000).map { i ->
            if (i % 10 == 0) "ERROR: failure at line $i" else "INFO: normal operation at line $i"
        }.joinToString("\n")

        // Apply grep
        val filtered = ToolOutputConfig.applyGrep(lines, "^ERROR:")

        // Should be ~100 lines, not 1000
        val filteredLines = filtered.lines().size
        assertEquals(100, filteredLines)
        assertTrue(filtered.lines().all { it.startsWith("ERROR:") })
    }

    @Test
    fun `grep reduces below spill threshold avoids disk write`() {
        val spiller = ToolOutputSpiller(tempDir)
        // 50K content, but grep will reduce to ~5K
        val lines = (1..5000).map { i ->
            if (i % 100 == 0) "MATCH: item $i" else "noise " + "x".repeat(8)
        }.joinToString("\n")

        val filtered = ToolOutputConfig.applyGrep(lines, "^MATCH:")
        val spillResult = spiller.spill("test", filtered)

        assertNull(spillResult.spilledToFile)  // Grep reduced size below threshold
    }

    @Test
    fun `automatic spill for large content`() {
        val spiller = ToolOutputSpiller(tempDir)
        // 35K content spread across many lines so head-20 preview is far shorter than full content
        val largeContent = (1..500).joinToString("\n") { "line $it: " + "x".repeat(60) }

        val spillResult = spiller.spill("run_command", largeContent)
        assertNotNull(spillResult.spilledToFile)
        assertTrue(spillResult.preview.contains("Output saved to:"))
        assertTrue(spillResult.preview.length < largeContent.length)
    }

    @Test
    fun `grep then spill — grep runs first reducing before spill decision`() {
        val spiller = ToolOutputSpiller(tempDir)
        // 40K of content but only small portion matches
        val lines = (1..4000).map { i ->
            if (i % 10 == 0) "ERROR: issue $i" else "INFO: " + "padding".repeat(5)
        }.joinToString("\n")

        // Grep first
        val filtered = ToolOutputConfig.applyGrep(lines, "^ERROR:")
        // Filtered content should be well under 30K
        assertTrue(filtered.length < ToolOutputConfig.SPILL_THRESHOLD_CHARS)

        // So spill should NOT trigger
        val spillResult = spiller.spill("test", filtered)
        assertNull(spillResult.spilledToFile)
    }

    @Test
    fun `full pipeline — grep, spill, truncate`() {
        val spiller = ToolOutputSpiller(tempDir)
        // Huge output: 100K chars
        val lines = (1..10000).map { "line $it: data " + "x".repeat(5) }.joinToString("\n")

        // Step 1: Grep (keep lines matching "line 1\d{3}:" — 4-digit numbers starting with 1: 1000-1999)
        var content = ToolOutputConfig.applyGrep(lines, "line 1\\d{3}:")

        // Step 2: If still large, spill
        if (content.length > ToolOutputConfig.SPILL_THRESHOLD_CHARS) {
            val spillResult = spiller.spill("test", content)
            content = spillResult.preview
        }

        // Step 3: Truncate
        content = truncateOutput(content, ToolOutputConfig.DEFAULT_MAX_CHARS)

        // Final content should be manageable
        assertTrue(content.length < ToolOutputConfig.DEFAULT_MAX_CHARS)
    }

    @Test
    fun `per-tool output config respected in truncation`() {
        // 110K chars — exceeds COMMAND (100K) but also exceeds DEFAULT (50K).
        // Use a separate smaller input to verify DEFAULT pass-through.
        val longOutput = "x".repeat(110_000)

        // Default config (50K) — truncated (110K > 50K)
        val defaultResult = truncateOutput(longOutput, ToolOutputConfig.DEFAULT_MAX_CHARS)
        assertTrue(defaultResult.length < longOutput.length)
        assertTrue(defaultResult.contains("[..."))

        // Command config (100K) — also truncated (110K > 100K)
        val commandResult = truncateOutput(longOutput, ToolOutputConfig.COMMAND_MAX_CHARS)
        assertTrue(commandResult.length < longOutput.length)
        assertTrue(commandResult.contains("[..."))

        // Verify that content under COMMAND threshold (40K) is NOT truncated
        val shortOutput = "x".repeat(40_000)
        val underCapCommandResult = truncateOutput(shortOutput, ToolOutputConfig.COMMAND_MAX_CHARS)
        assertEquals(shortOutput, underCapCommandResult)
    }

    @Test
    fun `head tail works as manual alternative to grep`() {
        val lines = (1..500).joinToString("\n") { "line $it" }
        val result = ToolOutputConfig.applyHeadTail(lines, head = 10, tail = 5)

        assertTrue(result.contains("line 1"))
        assertTrue(result.contains("line 10"))
        assertTrue(result.contains("[... 485 lines omitted ...]"))
        assertTrue(result.contains("line 500"))
    }
}
