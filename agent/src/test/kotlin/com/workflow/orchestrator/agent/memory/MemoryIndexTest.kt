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
    fun `load truncates content past line 200 keeping the LAST 200 lines with marker at top`(@TempDir tmp: Path) {
        val lines = (1..300).map { "- [Entry $it](e$it.md) — hook" }
        Files.writeString(tmp.resolve("MEMORY.md"), lines.joinToString("\n"))

        val result = MemoryIndex.load(tmp)!!
        val resultLines = result.lines()

        // 1 marker at top + last 200 lines (entries 101..300)
        assertEquals(201, resultLines.size)
        assertTrue(resultLines[0].startsWith("<!-- MEMORY.md truncated"))
        assertEquals("- [Entry 101](e101.md) — hook", resultLines[1])
        assertEquals("- [Entry 300](e300.md) — hook", resultLines[200])
    }

    @Test
    fun `load returns content unchanged when at the 200 line cap`(@TempDir tmp: Path) {
        val lines = (1..200).map { "line $it" }
        Files.writeString(tmp.resolve("MEMORY.md"), lines.joinToString("\n"))

        val result = MemoryIndex.load(tmp)!!
        assertEquals(200, result.lines().size)
    }

    @Test
    fun `seedIfMissing creates a stub MEMORY_md when missing`(@TempDir tmp: Path) {
        val file = tmp.resolve("MEMORY.md")
        assertTrue(!Files.exists(file))

        MemoryIndex.seedIfMissing(tmp)

        assertTrue(Files.exists(file))
        assertEquals("# Memory Index\n", Files.readString(file))
        // After seeding, load() must return non-null so the prompt's Section 10b fires.
        assertEquals("# Memory Index\n", MemoryIndex.load(tmp))
    }

    @Test
    fun `seedIfMissing does not overwrite existing MEMORY_md`(@TempDir tmp: Path) {
        val existing = "# Memory Index\n- [Existing](e.md) — hook"
        Files.writeString(tmp.resolve("MEMORY.md"), existing)

        MemoryIndex.seedIfMissing(tmp)

        assertEquals(existing, Files.readString(tmp.resolve("MEMORY.md")))
    }
}
