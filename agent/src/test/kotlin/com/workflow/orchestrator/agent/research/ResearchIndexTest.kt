package com.workflow.orchestrator.agent.research

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ResearchIndexLoadTest {

    @Test
    fun `load returns null when research dir does not exist`(@TempDir tmp: Path) {
        val researchDir = tmp.resolve("research")
        assertNull(ResearchIndex.load(researchDir))
    }

    @Test
    fun `load returns null when RESEARCH md does not exist in research dir`(@TempDir tmp: Path) {
        val researchDir = Files.createDirectory(tmp.resolve("research"))
        assertNull(ResearchIndex.load(researchDir))
    }

    @Test
    fun `load returns null when RESEARCH md is empty`(@TempDir tmp: Path) {
        val researchDir = Files.createDirectory(tmp.resolve("research"))
        Files.writeString(researchDir.resolve("RESEARCH.md"), "")
        assertNull(ResearchIndex.load(researchDir))
    }

    @Test
    fun `load returns null when RESEARCH md is blank whitespace`(@TempDir tmp: Path) {
        val researchDir = Files.createDirectory(tmp.resolve("research"))
        Files.writeString(researchDir.resolve("RESEARCH.md"), "   \n\n  \t  \n")
        assertNull(ResearchIndex.load(researchDir))
    }

    @Test
    fun `load returns full file when at or under 200 lines`(@TempDir tmp: Path) {
        val researchDir = Files.createDirectory(tmp.resolve("research"))
        val content = (1..150).joinToString("\n") { "- [Topic $it](2026-05-24-topic-$it.md) — hook $it." }
        Files.writeString(researchDir.resolve("RESEARCH.md"), content)
        val loaded = ResearchIndex.load(researchDir)
        assertNotNull(loaded)
        assertEquals(150, loaded!!.lines().size)
        assertTrue(loaded.contains("Topic 1"))
        assertTrue(loaded.contains("Topic 150"))
    }

    @Test
    fun `load truncates to first 200 lines when larger than 200 lines`(@TempDir tmp: Path) {
        val researchDir = Files.createDirectory(tmp.resolve("research"))
        val content = (1..500).joinToString("\n") { "- [Topic $it](file-$it.md) — hook $it." }
        Files.writeString(researchDir.resolve("RESEARCH.md"), content)
        val loaded = ResearchIndex.load(researchDir)
        assertNotNull(loaded)
        val lines = loaded!!.lines()
        // Allow the truncation marker line to make it 200 OR 201 — pin both bounds.
        assertTrue(lines.size >= 200 && lines.size <= 201, "Expected ≤201 lines after truncation, got ${lines.size}")
        assertTrue(loaded.contains("Topic 1"), "First entry must survive")
        assertTrue(loaded.contains("Topic 200"), "Entry 200 must survive")
        assertTrue(!loaded.contains("Topic 201"), "Entry 201 must NOT survive truncation")
    }
}
