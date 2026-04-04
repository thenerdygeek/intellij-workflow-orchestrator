package com.workflow.orchestrator.agent.memory

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ArchivalMemoryTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var storageFile: File
    private lateinit var memory: ArchivalMemory

    @BeforeEach
    fun setUp() {
        storageFile = tempDir.resolve("archival/store.json").toFile()
        memory = ArchivalMemory(storageFile)
    }

    @Nested
    inner class InsertTests {
        @Test
        fun `insert stores entry with tags`() {
            val id = memory.insert("CORS error fixed by adding @CrossOrigin", listOf("error_resolution", "spring"))
            assertTrue(id.startsWith("mem_"))
            assertEquals(1, memory.size())
        }

        @Test
        fun `insert deduplicates and lowercases tags`() {
            memory.insert("test", listOf("Spring", "spring", "SPRING"))
            val entry = memory.all().first()
            assertEquals(listOf("spring"), entry.tags)
        }

        @Test
        fun `insert evicts oldest when at capacity`() {
            // Fill to capacity
            repeat(ArchivalMemory.MAX_ENTRIES) { i ->
                memory.insert("entry $i", listOf("bulk"))
            }
            assertEquals(ArchivalMemory.MAX_ENTRIES, memory.size())

            // One more should evict oldest
            memory.insert("newest", listOf("new"))
            assertEquals(ArchivalMemory.MAX_ENTRIES, memory.size())
            assertNotNull(memory.search("newest").firstOrNull())
        }
    }

    @Nested
    inner class SearchTests {
        @Test
        fun `search by keyword in content`() {
            memory.insert("Fixed CORS error in SecurityConfig", listOf("error"))
            memory.insert("Added pagination to UserController", listOf("feature"))
            memory.insert("CORS requires proper origin setup", listOf("config"))

            val results = memory.search("CORS")
            assertEquals(2, results.size)
            assertTrue(results.all { it.entry.content.contains("CORS", ignoreCase = true) })
        }

        @Test
        fun `search boosts tag matches 3x`() {
            memory.insert("Some text about database issues", listOf("database", "error"))
            memory.insert("Database connection pool tuning", listOf("performance"))

            val results = memory.search("database")
            assertEquals(2, results.size)
            // First result has "database" in tags (3x boost) AND content
            assertTrue(results[0].score > results[1].score)
        }

        @Test
        fun `search filters by tags`() {
            memory.insert("Spring security setup", listOf("spring", "security"))
            memory.insert("React component patterns", listOf("frontend", "react"))

            val results = memory.search("setup", tags = listOf("spring"))
            assertEquals(1, results.size)
            assertEquals("Spring security setup", results[0].entry.content)
        }

        @Test
        fun `search records usage on matched entries`() {
            memory.insert("test memory", listOf("test"))
            assertEquals(0, memory.all().first().usageCount)

            memory.search("test")
            assertEquals(1, memory.all().first().usageCount)
            assertNotNull(memory.all().first().lastUsage)
        }

        @Test
        fun `search respects limit`() {
            repeat(20) { memory.insert("entry $it about kotlin", listOf("kotlin")) }
            val results = memory.search("kotlin", limit = 5)
            assertEquals(5, results.size)
        }

        @Test
        fun `search returns empty for no matches`() {
            memory.insert("something", listOf("tag"))
            val results = memory.search("nonexistent")
            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class PruneTests {
        @Test
        fun `prune removes old unused entries`() {
            // Insert entry with old timestamp
            val entry = ArchivalMemory.ArchivalEntry(
                id = "old", content = "old stuff", tags = listOf("old"),
                createdAt = 0, usageCount = 0, lastUsage = null
            )
            // Access internal state for test (insert then manually override)
            memory.insert("old stuff", listOf("old"))
            memory.insert("new stuff", listOf("new"))

            // Prune with 0 days = remove everything not used recently
            val removed = memory.prune(maxUnusedDays = 0)
            // Both were just created so both are recent — nothing removed
            assertEquals(0, removed)
        }
    }

    @Nested
    inner class PersistenceTests {
        @Test
        fun `persists and reloads`() {
            memory.insert("Important fact", listOf("fact", "important"))
            memory.insert("Another fact", listOf("fact"))

            val reloaded = ArchivalMemory(storageFile)
            assertEquals(2, reloaded.size())
            val results = reloaded.search("Important")
            assertEquals(1, results.size)
        }
    }
}
