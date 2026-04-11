package com.workflow.orchestrator.agent.memory.auto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ExtractionLogTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var logFile: File
    private lateinit var log: ExtractionLog

    @BeforeEach
    fun setUp() {
        logFile = tempDir.resolve("extraction-log.jsonl").toFile()
        log = ExtractionLog(logFile)
    }

    @Test
    fun `record appends an entry`() {
        log.record(
            sessionId = "session-1",
            source = "session-end/session-1",
            coreUpdates = listOf(
                CoreMemoryUpdate(block = "user", action = UpdateAction.APPEND, content = "Backend dev")
            ),
            archivalInserts = emptyList()
        )

        val entries = log.loadRecent(100)
        assertEquals(1, entries.size)
        assertEquals("session-1", entries[0].sessionId)
        assertEquals(1, entries[0].coreUpdates.size)
    }

    @Test
    fun `loadRecent returns newest first`() {
        log.record("session-1", "source-1", emptyList(), emptyList())
        log.record("session-2", "source-2", emptyList(), emptyList())
        log.record("session-3", "source-3", emptyList(), emptyList())

        val entries = log.loadRecent(10)
        assertEquals(3, entries.size)
        assertEquals("session-3", entries[0].sessionId)
        assertEquals("session-2", entries[1].sessionId)
        assertEquals("session-1", entries[2].sessionId)
    }

    @Test
    fun `loadRecent respects limit`() {
        repeat(10) { i ->
            log.record("session-$i", "source-$i", emptyList(), emptyList())
        }

        val entries = log.loadRecent(5)
        assertEquals(5, entries.size)
        assertEquals("session-9", entries[0].sessionId)
    }

    @Test
    fun `cap trims oldest entries on overflow`() {
        repeat(505) { i ->
            log.record("session-$i", "source-$i", emptyList(), emptyList())
        }

        val entries = log.loadRecent(1000)
        assertEquals(500, entries.size)
        // Oldest 5 (0..4) should be gone
        assertFalse(entries.any { it.sessionId == "session-0" })
        assertTrue(entries.any { it.sessionId == "session-504" })
    }

    @Test
    fun `clear empties the log`() {
        log.record("session-1", "source", emptyList(), emptyList())
        log.record("session-2", "source", emptyList(), emptyList())

        log.clear()

        assertTrue(log.loadRecent(10).isEmpty())
    }

    @Test
    fun `persists across reloads`() {
        log.record(
            sessionId = "session-1",
            source = "session-end/session-1",
            coreUpdates = listOf(
                CoreMemoryUpdate(block = "patterns", action = UpdateAction.APPEND, content = "Use TDD")
            ),
            archivalInserts = listOf(
                ArchivalInsert(content = "CORS fix", tags = listOf("error", "cors"))
            )
        )

        val reloaded = ExtractionLog(logFile)
        val entries = reloaded.loadRecent(10)
        assertEquals(1, entries.size)
        assertEquals("session-1", entries[0].sessionId)
        assertEquals("Use TDD", entries[0].coreUpdates[0].content)
        assertEquals(1, entries[0].archivalInserts.size)
    }

    @Test
    fun `forProject uses extraction-log jsonl path`() {
        val agentDir = tempDir.resolve("agent-dir").toFile()
        val log = ExtractionLog.forProject(agentDir)

        log.record("test", "test", emptyList(), emptyList())

        val expectedFile = File(agentDir, "extraction-log.jsonl")
        assertTrue(expectedFile.exists())
    }
}
