package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ToolOutputSpillerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `content under threshold is not spilled`() {
        val spiller = ToolOutputSpiller(tempDir)
        val result = spiller.spill("run_command", "small output")
        assertNull(result.spilledToFile)
        assertEquals("small output", result.preview)
    }

    @Test
    fun `content over threshold is spilled to file`() {
        val spiller = ToolOutputSpiller(tempDir)
        val content = "x".repeat(35_000)
        val result = spiller.spill("run_command", content, threshold = 30_000)
        assertNotNull(result.spilledToFile)
        assertTrue(result.preview.contains("Output saved to:"))
        assertTrue(result.preview.contains("35000 chars"))
        // Verify file was actually written
        assertEquals(content, File(result.spilledToFile!!).readText())
    }

    @Test
    fun `preview contains head lines and tail lines`() {
        val spiller = ToolOutputSpiller(tempDir)
        val lines = (1..100).joinToString("\n") { "line $it" }
        val result = spiller.spill("test_tool", lines, threshold = 100)
        assertNotNull(result.spilledToFile)
        assertTrue(result.preview.contains("line 1"))   // head
        assertTrue(result.preview.contains("line 20"))  // last of head
        assertTrue(result.preview.contains("line 100")) // tail
        assertTrue(result.preview.contains("read_file or search_code"))
    }

    @Test
    fun `spill creates directory if needed`() {
        val subDir = tempDir.resolve("nested/dir")
        val spiller = ToolOutputSpiller(subDir)
        val result = spiller.spill("tool", "x".repeat(200), threshold = 100)
        assertNotNull(result.spilledToFile)
        assertTrue(File(result.spilledToFile!!).exists())
    }

    @Test
    fun `content at exact threshold is not spilled`() {
        val spiller = ToolOutputSpiller(tempDir)
        val content = "x".repeat(100)
        val result = spiller.spill("tool", content, threshold = 100)
        assertNull(result.spilledToFile)
        assertEquals(content, result.preview)
    }

    @Test
    fun `spill file name contains tool name`() {
        val spiller = ToolOutputSpiller(tempDir)
        val content = "x".repeat(200)
        val result = spiller.spill("search_code", content, threshold = 100)
        assertNotNull(result.spilledToFile)
        assertTrue(result.spilledToFile!!.contains("search_code"))
        assertTrue(result.spilledToFile!!.endsWith("-output.txt"))
    }

    @Test
    fun `preview shows line count`() {
        val spiller = ToolOutputSpiller(tempDir)
        val content = (1..50).joinToString("\n") { "line $it content here" }
        val result = spiller.spill("git", content, threshold = 100)
        assertNotNull(result.spilledToFile)
        assertTrue(result.preview.contains("50 lines"))
    }

    @Test
    fun `short content with many lines below threshold is not spilled`() {
        val spiller = ToolOutputSpiller(tempDir)
        val content = (1..5).joinToString("\n") { "l$it" }
        val result = spiller.spill("tool", content, threshold = 1000)
        assertNull(result.spilledToFile)
        assertEquals(content, result.preview)
    }

    @Test
    fun `two same-second spills of the same tool get distinct files with their own content`() {
        // B6: epoch-second filenames collide when the same tool spills twice within one
        // second — the second write silently overwrote the first, so the earlier result's
        // spillPath pointed at the later content.
        val spiller = ToolOutputSpiller(tempDir)
        val contentA = "A".repeat(200)
        val contentB = "B".repeat(200)
        val resultA = spiller.spill("run_command", contentA, threshold = 100)
        val resultB = spiller.spill("run_command", contentB, threshold = 100)
        assertNotNull(resultA.spilledToFile)
        assertNotNull(resultB.spilledToFile)
        assertNotEquals(resultA.spilledToFile, resultB.spilledToFile)
        assertTrue(File(resultA.spilledToFile!!).exists())
        assertTrue(File(resultB.spilledToFile!!).exists())
        assertEquals(contentA, File(resultA.spilledToFile!!).readText())
        assertEquals(contentB, File(resultB.spilledToFile!!).readText())
    }

    @Test
    fun `preview shape for 100-line input — head 20, separator, tail 10, footer`() {
        // P2-4 regression pin: the single-pass tail-ring rewrite must preserve the exact
        // preview shape of the old content.lines() implementation.
        val spiller = ToolOutputSpiller(tempDir)
        val content = (1..100).joinToString("\n") { "line $it" }
        val result = spiller.spill("test_tool", content, threshold = 100)
        assertNotNull(result.spilledToFile)
        val expectedHead = (1..20).joinToString("\n") { "line $it" }
        val expectedTail = (91..100).joinToString("\n") { "line $it" }
        assertTrue(result.preview.startsWith("$expectedHead\n...\n$expectedTail"))
        assertTrue(result.preview.contains("(${content.length} chars, 100 lines)"))
        assertFalse(result.preview.contains("line 21\n"))
        assertFalse(result.preview.contains("line 90\n"))
    }

    @Test
    fun `preview for 25-line input has no separator — 30-line rule`() {
        val spiller = ToolOutputSpiller(tempDir)
        val content = (1..25).joinToString("\n") { "line $it padded to exceed the threshold" }
        val result = spiller.spill("test_tool", content, threshold = 100)
        assertNotNull(result.spilledToFile)
        assertFalse(result.preview.contains("\n...\n"))
        assertTrue(result.preview.contains("(${content.length} chars, 25 lines)"))
        // head is still capped at 20 lines even when the separator/tail is omitted
        assertTrue(result.preview.contains("line 20 "))
        assertFalse(result.preview.contains("line 21 "))
    }
}
