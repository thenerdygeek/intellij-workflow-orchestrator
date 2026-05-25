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

class ResearchIndexOnFileCreatedTest {

    private fun writeDump(file: Path, title: String, finding: String) {
        Files.writeString(file, """
            ---
            topic: example
            question: "Example question"
            researched-by: workflow-orchestrator research subagent
            session-id: 01HZTEST
            retrieved-at: 2026-05-24T15:00:00Z
            sources-consulted: 3
            sources-cited: 2
            ---

            # $title

            ## Research question
            Example question

            ## Method
            - 1 web_search query

            ## Sources
            | # | URL | Retrieved | Credibility |
            |---|-----|-----------|-------------|
            | 1 | https://example.com | 2026-05-24 | Primary |

            ## Findings
            $finding

            ## Limitations
            None.

            ## Open questions
            None.
        """.trimIndent())
    }

    @Test
    fun `onResearchFileCreated appends one entry to RESEARCH md`(@TempDir tmp: Path) {
        val researchDir = Files.createDirectory(tmp.resolve("research"))
        val dump = researchDir.resolve("2026-05-24-okhttp-pool-01HZTE.md")
        writeDump(dump, "OkHttp ConnectionPool Tuning", "Default pool of 5 connections is fine for IDE-plugin scale.")

        ResearchIndex.onResearchFileCreated(researchDir, dump)

        val index = Files.readString(researchDir.resolve("RESEARCH.md"))
        assertTrue(index.contains("OkHttp ConnectionPool Tuning"), "Title must appear in index: $index")
        assertTrue(index.contains("2026-05-24-okhttp-pool-01HZTE.md"), "Filename must appear in index: $index")
        assertTrue(index.contains("Default pool of 5 connections"), "Hook (first finding) must appear: $index")
    }

    @Test
    fun `onResearchFileCreated is a no-op when the created file IS the index itself`(@TempDir tmp: Path) {
        val researchDir = Files.createDirectory(tmp.resolve("research"))
        val indexFile = researchDir.resolve("RESEARCH.md")
        Files.writeString(indexFile, "# Research Index\n- existing entry\n")

        ResearchIndex.onResearchFileCreated(researchDir, indexFile)

        // Content unchanged — hook recognised self-edit and bailed.
        assertEquals("# Research Index\n- existing entry\n", Files.readString(indexFile))
    }

    @Test
    fun `onResearchFileCreated truncates the hook line to 80 chars`(@TempDir tmp: Path) {
        val researchDir = Files.createDirectory(tmp.resolve("research"))
        val dump = researchDir.resolve("2026-05-24-long-01HZTE.md")
        val longFinding = "A".repeat(200) + " end."
        writeDump(dump, "Long Finding", longFinding)

        ResearchIndex.onResearchFileCreated(researchDir, dump)

        val index = Files.readString(researchDir.resolve("RESEARCH.md"))
        val hookLine = index.lines().single { it.contains("Long Finding") }
        // "- [Long Finding](2026-...-md) — " prefix + ≤80 chars of hook + optional ellipsis.
        val afterDash = hookLine.substringAfter(" — ")
        assertTrue(afterDash.length <= 83, "Hook line tail too long: ${afterDash.length} chars")
    }

    @Test
    fun `onResearchFileCreated is idempotent on repeat calls for the same file`(@TempDir tmp: Path) {
        val researchDir = Files.createDirectory(tmp.resolve("research"))
        val dump = researchDir.resolve("2026-05-24-okhttp-pool-01HZTE.md")
        writeDump(dump, "OkHttp Pool", "Findings prose.")

        ResearchIndex.onResearchFileCreated(researchDir, dump)
        ResearchIndex.onResearchFileCreated(researchDir, dump)
        ResearchIndex.onResearchFileCreated(researchDir, dump)

        val index = Files.readString(researchDir.resolve("RESEARCH.md"))
        val occurrences = index.split("2026-05-24-okhttp-pool-01HZTE.md").size - 1
        assertEquals(1, occurrences, "Duplicate calls must NOT produce duplicate index entries")
    }

    @Test
    fun `concurrent onResearchFileCreated calls produce distinct entries with no data loss`(@TempDir tmp: Path) {
        val researchDir = Files.createDirectory(tmp.resolve("research"))
        val files = (1..10).map { i ->
            val dump = researchDir.resolve("2026-05-24-topic-$i-01HZT$i.md")
            writeDump(dump, "Topic $i", "Finding $i.")
            dump
        }

        // Fan out 10 concurrent calls.
        val threads = files.map { f -> Thread { ResearchIndex.onResearchFileCreated(researchDir, f) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val index = Files.readString(researchDir.resolve("RESEARCH.md"))
        for (i in 1..10) {
            assertTrue(
                index.contains("Topic $i"),
                "Concurrent appends must NOT drop entries; missing Topic $i. Full index:\n$index",
            )
        }
    }
}
