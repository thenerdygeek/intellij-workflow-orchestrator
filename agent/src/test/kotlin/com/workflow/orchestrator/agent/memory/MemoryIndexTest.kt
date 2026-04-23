package com.workflow.orchestrator.agent.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MemoryIndexTest {

    @Test
    fun `load returns null when MEMORY_md is missing`(@TempDir tmp: Path) {
        val result = MemoryIndex.load(tmp)
        assertNull(result)
    }

    @Test
    fun `load returns null when memory dir does not exist`(@TempDir tmp: Path) {
        val nonexistent = tmp.resolve("does-not-exist")
        val result = MemoryIndex.load(nonexistent)
        assertNull(result)
    }

    @Test
    fun `load returns file content when MEMORY_md exists`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("MEMORY.md"), "# Index\n- [A](a.md) — hook")
        val result = MemoryIndex.load(tmp)
        assertEquals("# Index\n- [A](a.md) — hook", result)
    }

    @Test
    fun `load truncates content past line 200 with marker`(@TempDir tmp: Path) {
        val lines = (1..300).map { "- [Entry $it](e$it.md) — hook" }
        Files.writeString(tmp.resolve("MEMORY.md"), lines.joinToString("\n"))

        val result = MemoryIndex.load(tmp)!!
        val resultLines = result.lines()

        assertEquals(201, resultLines.size) // 200 kept + 1 marker
        assertEquals("- [Entry 200](e200.md) — hook", resultLines[199])
        assertTrue(resultLines[200].startsWith("<!-- MEMORY.md truncated"))
    }

    @Test
    fun `load returns content unchanged when at the 200 line cap`(@TempDir tmp: Path) {
        val lines = (1..200).map { "line $it" }
        Files.writeString(tmp.resolve("MEMORY.md"), lines.joinToString("\n"))

        val result = MemoryIndex.load(tmp)!!
        assertEquals(200, result.lines().size)
    }
}
