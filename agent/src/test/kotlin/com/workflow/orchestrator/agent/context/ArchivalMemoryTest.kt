package com.workflow.orchestrator.agent.context

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ArchivalMemoryTest {

    @TempDir
    lateinit var tempDir: File
    private lateinit var memory: ArchivalMemory

    @BeforeEach
    fun setup() {
        memory = ArchivalMemory(File(tempDir, "store.json"), maxEntries = 100)
    }

    // --- Insert ---

    @Test
    fun `insert stores entry and returns id`() {
        val id = memory.insert("Fixed EDT freeze by replacing runBlocking", listOf("edt", "freeze", "runblocking"), "error_resolution")
        assertTrue(id.startsWith("arch-"))
        assertEquals(1, memory.size())
    }

    @Test
    fun `insert with session id`() {
        memory.insert("test content", listOf("tag1"), "agent_memory", sessionId = "session-123")
        val entries = memory.getAll()
        assertEquals("session-123", entries.first().sessionId)
    }

    @Test
    fun `insert caps content and tags`() {
        val longContent = "x".repeat(5000)
        memory.insert(longContent, (1..30).map { "tag$it" }, "agent_memory")
        val entry = memory.getAll().first()
        assertTrue(entry.content.length <= 2000)
        assertTrue(entry.tags.size <= 20)
    }

    @Test
    fun `insert evicts oldest when full`() {
        val smallMemory = ArchivalMemory(File(tempDir, "small.json"), maxEntries = 3)
        smallMemory.insert("first", listOf("a"), "agent_memory")
        smallMemory.insert("second", listOf("b"), "agent_memory")
        smallMemory.insert("third", listOf("c"), "agent_memory")
        smallMemory.insert("fourth", listOf("d"), "agent_memory")
        assertEquals(3, smallMemory.size())
        assertFalse(smallMemory.getAll().any { it.content == "first" })
    }

    // --- Search ---

    @Test
    fun `search by content keyword`() {
        memory.insert("Fixed EDT freeze in BambooService", listOf("edt", "freeze"), "error_resolution")
        memory.insert("Added unit tests for AuthService", listOf("test", "auth"), "code_pattern")

        val results = memory.search("freeze")
        assertEquals(1, results.size)
        assertTrue(results.first().first.content.contains("freeze"))
    }

    @Test
    fun `search by tag matches with boost`() {
        memory.insert("Some content about threading", listOf("edt", "freeze", "crash"), "error_resolution")
        memory.insert("Some content about crash handling", listOf("error", "exception"), "error_resolution")

        val results = memory.search("crash")
        assertEquals(2, results.size)
        // First result should be the one with "crash" in tags (boosted 3x)
        assertTrue(results.first().first.tags.contains("crash"))
    }

    @Test
    fun `search with multiple terms`() {
        memory.insert("Fixed EDT freeze in BambooService", listOf("edt", "freeze", "bamboo"), "error_resolution")
        memory.insert("Updated Jira integration", listOf("jira", "api"), "code_pattern")

        val results = memory.search("edt freeze bamboo")
        assertEquals(1, results.size)
    }

    @Test
    fun `search returns empty for no match`() {
        memory.insert("some content", listOf("tag"), "agent_memory")
        val results = memory.search("nonexistent")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search with type filter`() {
        memory.insert("error content", listOf("bug"), "error_resolution")
        memory.insert("pattern content", listOf("pattern"), "code_pattern")

        val results = memory.search("content", typeFilter = "error_resolution")
        assertEquals(1, results.size)
        assertEquals("error_resolution", results.first().first.type)
    }

    @Test
    fun `search respects topK limit`() {
        repeat(10) { i ->
            memory.insert("content about testing $i", listOf("test"), "agent_memory")
        }
        val results = memory.search("test", topK = 3)
        assertEquals(3, results.size)
    }

    @Test
    fun `search handles blank query`() {
        memory.insert("content", listOf("tag"), "agent_memory")
        assertTrue(memory.search("").isEmpty())
        assertTrue(memory.search("   ").isEmpty())
    }

    @Test
    fun `search with partial tag match`() {
        memory.insert("content", listOf("bambooservice", "build"), "code_pattern")
        val results = memory.search("bamboo")
        assertEquals(1, results.size) // "bamboo" is substring of "bambooservice"
    }

    // --- Delete ---

    @Test
    fun `delete by id`() {
        val id = memory.insert("to delete", listOf("tag"), "agent_memory")
        assertTrue(memory.delete(id))
        assertEquals(0, memory.size())
    }

    @Test
    fun `delete nonexistent returns false`() {
        assertFalse(memory.delete("nonexistent"))
    }

    // --- Type filtering ---

    @Test
    fun `getByType filters correctly`() {
        memory.insert("error", listOf("bug"), "error_resolution")
        memory.insert("pattern", listOf("code"), "code_pattern")
        memory.insert("error2", listOf("bug2"), "error_resolution")

        val errors = memory.getByType("error_resolution")
        assertEquals(2, errors.size)
    }

    // --- Persistence ---

    @Test
    fun `persists across instances`() {
        memory.insert("persistent entry", listOf("durable"), "agent_memory")
        val reloaded = ArchivalMemory(File(tempDir, "store.json"))
        assertEquals(1, reloaded.size())
        val results = reloaded.search("persistent")
        assertEquals(1, results.size)
    }

    @Test
    fun `handles corrupt file gracefully`() {
        File(tempDir, "corrupt.json").writeText("not valid json")
        val corrupted = ArchivalMemory(File(tempDir, "corrupt.json"))
        assertEquals(0, corrupted.size()) // Starts fresh
    }

    // --- Edge cases ---

    @Test
    fun `tags are lowercased`() {
        memory.insert("content", listOf("EDT", "Freeze", "CRASH"), "error_resolution")
        val entry = memory.getAll().first()
        assertTrue(entry.tags.all { it == it.lowercase() })
    }

    @Test
    fun `search is case insensitive`() {
        memory.insert("EDT Freeze in BambooService", listOf("edt", "freeze"), "error_resolution")
        val results = memory.search("edt FREEZE")
        assertEquals(1, results.size)
    }

    @Test
    fun `sequential ids are unique`() {
        val id1 = memory.insert("a", listOf("x"), "agent_memory")
        val id2 = memory.insert("b", listOf("y"), "agent_memory")
        assertNotEquals(id1, id2)
    }
}
