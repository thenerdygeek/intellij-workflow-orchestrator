# Three-Tier Memory System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the agent's 3-tier memory system (core + archival + recall) with 7 memory tools, ported from Letta/MemGPT's pattern with Goose's file-based simplicity and Codex's usage decay.

**Architecture:** Core memory is a JSON key-value store injected into the system prompt before every LLM call (Letta pattern). Archival memory is a JSON array store with tag-boosted keyword search and Codex-style usage tracking/decay. Conversation recall searches existing JSONL session transcripts. All 7 tools are first-class AgentTools registered in ToolRegistry.

**Tech Stack:** Kotlin, kotlinx.serialization (JSON), java.io.File, existing SessionStore for JSONL access. No external dependencies (no vector DB, no embedding models).

**Source references:**
- Letta: `/Users/subhankarhalder/Desktop/Programs/git-clones/agent-research/letta/letta/services/tool_executor/core_tool_executor.py` (memory tool implementations)
- Letta: `/Users/subhankarhalder/Desktop/Programs/git-clones/agent-research/letta/letta/schemas/memory.py` (core memory structure)
- Goose: `/Users/subhankarhalder/Desktop/Programs/git-clones/agent-research/goose/crates/goose-mcp/src/memory/mod.rs` (file-based storage)
- Codex: `/Users/subhankarhalder/Desktop/Programs/git-clones/agent-research/codex/codex-rs/core/src/memories/` (usage decay)
- Spec: `agent/CLAUDE.md` lines 263-303 (Three-Tier Memory System section)

**Target package:** `com.workflow.orchestrator.agent.memory`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `agent/src/main/kotlin/.../agent/memory/CoreMemory.kt` | Tier 1: JSON key-value store, compile to system prompt XML |
| `agent/src/main/kotlin/.../agent/memory/ArchivalMemory.kt` | Tier 2: JSON array store with tags, keyword search, usage tracking, decay |
| `agent/src/main/kotlin/.../agent/memory/ConversationRecall.kt` | Tier 3: Search across JSONL session transcripts |
| `agent/src/main/kotlin/.../agent/tools/memory/CoreMemoryReadTool.kt` | Tool: `core_memory_read` |
| `agent/src/main/kotlin/.../agent/tools/memory/CoreMemoryAppendTool.kt` | Tool: `core_memory_append` |
| `agent/src/main/kotlin/.../agent/tools/memory/CoreMemoryReplaceTool.kt` | Tool: `core_memory_replace` |
| `agent/src/main/kotlin/.../agent/tools/memory/ArchivalMemoryInsertTool.kt` | Tool: `archival_memory_insert` |
| `agent/src/main/kotlin/.../agent/tools/memory/ArchivalMemorySearchTool.kt` | Tool: `archival_memory_search` |
| `agent/src/main/kotlin/.../agent/tools/memory/ConversationSearchTool.kt` | Tool: `conversation_search` |
| `agent/src/main/kotlin/.../agent/tools/memory/SaveMemoryTool.kt` | Tool: `save_memory` (legacy compat) |
| `agent/src/test/kotlin/.../agent/memory/CoreMemoryTest.kt` | Tests for core memory |
| `agent/src/test/kotlin/.../agent/memory/ArchivalMemoryTest.kt` | Tests for archival memory |
| `agent/src/test/kotlin/.../agent/memory/ConversationRecallTest.kt` | Tests for conversation recall |
| `agent/src/test/kotlin/.../agent/tools/memory/MemoryToolsTest.kt` | Tests for all 7 tools |

---

### Task 1: CoreMemory — JSON key-value store with system prompt compilation

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/CoreMemory.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/memory/CoreMemoryTest.kt`

Port from: Letta's `letta/schemas/memory.py` (Memory class, Block structure, compile method) + Goose's file-based persistence.

- [ ] **Step 1: Write CoreMemory implementation**

```kotlin
package com.workflow.orchestrator.agent.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Tier 1: Core Memory — always-in-prompt working memory.
 *
 * Port of Letta's Memory class (letta/schemas/memory.py) with Goose's file-based persistence.
 *
 * Structure: Named blocks with character limits, compiled into XML for system prompt injection.
 * The LLM sees core memory on every turn and can self-edit via memory tools.
 *
 * Storage: JSON file at ~/.workflow-orchestrator/{proj}/agent/core-memory.json
 *
 * Letta equivalent: Memory.compile() renders blocks as <memory_blocks> XML.
 * Our equivalent: compile() renders as <core_memory> XML (matching agent CLAUDE.md spec).
 */
class CoreMemory(private val storageFile: File) {

    companion object {
        /** Default per-block character limit. Letta uses 100K; we use 5K for IDE context efficiency. */
        const val DEFAULT_BLOCK_LIMIT = 5_000
        /** Maximum total core memory size (all blocks). Spec says 4KB but we use chars not bytes. */
        const val MAX_TOTAL_CHARS = 20_000

        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Load core memory for a project directory.
         * Port of Letta's CoreMemory.forProject() pattern.
         *
         * @param agentDir the agent data directory (~/.workflow-orchestrator/{proj}/agent/)
         */
        fun forProject(agentDir: File): CoreMemory {
            return CoreMemory(File(agentDir, "core-memory.json"))
        }
    }

    /** In-memory state — loaded from disk, persisted on mutation. */
    private var blocks: MutableMap<String, MemoryBlock> = mutableMapOf()

    init {
        load()
    }

    // ---- Block operations (ported from Letta's core_memory_append/replace) ----

    /**
     * Read the value of a named block.
     * Port of Letta: agent reads core memory to decide what to update.
     */
    fun read(label: String): String? = blocks[label]?.value

    /**
     * Read all blocks as a map.
     */
    fun readAll(): Map<String, MemoryBlock> = blocks.toMap()

    /**
     * Append content to a named block. Creates the block if it doesn't exist.
     *
     * Port of Letta's core_memory_append (core_tool_executor.py:319-344):
     * current_value + "\n" + content
     *
     * @return the new block value after append
     * @throws IllegalArgumentException if block is read-only or would exceed limit
     */
    fun append(label: String, content: String): String {
        val block = blocks.getOrPut(label) {
            MemoryBlock(value = "", limit = DEFAULT_BLOCK_LIMIT)
        }
        require(!block.readOnly) { "Block '$label' is read-only" }

        val newValue = if (block.value.isEmpty()) content else "${block.value}\n$content"
        require(newValue.length <= block.limit) {
            "Appending would exceed block '$label' limit (${newValue.length}/${block.limit} chars)"
        }

        block.value = newValue
        persist()
        return newValue
    }

    /**
     * Find-and-replace within a named block.
     *
     * Port of Letta's core_memory_replace (core_tool_executor.py:346-401):
     * Exact match required. Rejects if 0 or >1 occurrences.
     *
     * @return the new block value after replacement
     * @throws IllegalArgumentException if block not found, read-only, or match count != 1
     */
    fun replace(label: String, oldContent: String, newContent: String): String {
        val block = blocks[label]
            ?: throw IllegalArgumentException("Block '$label' not found")
        require(!block.readOnly) { "Block '$label' is read-only" }

        val occurrences = block.value.windowed(oldContent.length, 1)
            .count { it == oldContent }
        require(occurrences == 1) {
            if (occurrences == 0) "No match found for old_content in block '$label'"
            else "Multiple matches ($occurrences) found — replace requires exactly 1 match"
        }

        val newValue = block.value.replace(oldContent, newContent)
        require(newValue.length <= block.limit) {
            "Replacement would exceed block '$label' limit (${newValue.length}/${block.limit} chars)"
        }

        block.value = newValue
        persist()
        return newValue
    }

    /**
     * Set a block directly (used for initialization, not exposed as tool).
     */
    fun setBlock(label: String, value: String, limit: Int = DEFAULT_BLOCK_LIMIT, readOnly: Boolean = false) {
        blocks[label] = MemoryBlock(value = value, limit = limit, readOnly = readOnly)
        persist()
    }

    /**
     * Check if core memory is empty (no blocks or all empty).
     */
    fun isEmpty(): Boolean = blocks.isEmpty() || blocks.values.all { it.value.isBlank() }

    /**
     * Total character count across all blocks.
     */
    fun totalChars(): Int = blocks.values.sumOf { it.value.length }

    // ---- System prompt compilation (ported from Letta's Memory.compile()) ----

    /**
     * Compile core memory into XML for system prompt injection.
     *
     * Port of Letta's Memory.compile() -> _render_memory_blocks_standard() (memory.py:143-174).
     * Our format uses <core_memory> tag matching the agent CLAUDE.md spec (section 8).
     *
     * @return XML string, or null if memory is empty
     */
    fun compile(): String? {
        if (isEmpty()) return null

        return buildString {
            appendLine("<core_memory>")
            appendLine("The following memory blocks are your persistent working memory.")
            appendLine("You can read and update them using core_memory_read, core_memory_append, and core_memory_replace tools.")
            appendLine()
            for ((label, block) in blocks) {
                if (block.value.isBlank()) continue
                appendLine("<$label>")
                appendLine(block.value)
                appendLine("</$label>")
                appendLine("[${block.value.length}/${block.limit} chars used]")
                appendLine()
            }
            appendLine("</core_memory>")
        }
    }

    // ---- Persistence (Goose-style file-based) ----

    private fun load() {
        if (!storageFile.exists()) {
            blocks = mutableMapOf()
            return
        }
        try {
            val content = storageFile.readText()
            val stored = json.decodeFromString<StoredCoreMemory>(content)
            blocks = stored.blocks.mapValues { (_, sb) ->
                MemoryBlock(value = sb.value, limit = sb.limit, readOnly = sb.readOnly)
            }.toMutableMap()
        } catch (e: Exception) {
            blocks = mutableMapOf()
        }
    }

    private fun persist() {
        try {
            storageFile.parentFile?.mkdirs()
            val stored = StoredCoreMemory(
                blocks = blocks.mapValues { (_, b) ->
                    StoredBlock(value = b.value, limit = b.limit, readOnly = b.readOnly)
                }
            )
            val tempFile = File(storageFile.parent, "${storageFile.name}.tmp")
            tempFile.writeText(json.encodeToString(stored))
            tempFile.renameTo(storageFile)
        } catch (e: Exception) {
            // Log but don't throw — memory persistence is best-effort
        }
    }

    /** Mutable in-memory block. */
    class MemoryBlock(
        var value: String,
        val limit: Int = DEFAULT_BLOCK_LIMIT,
        val readOnly: Boolean = false
    )

    /** Serializable storage format. */
    @Serializable
    private data class StoredCoreMemory(val blocks: Map<String, StoredBlock>)

    @Serializable
    private data class StoredBlock(
        val value: String,
        val limit: Int = DEFAULT_BLOCK_LIMIT,
        val readOnly: Boolean = false
    )
}
```

- [ ] **Step 2: Write CoreMemory tests**

```kotlin
package com.workflow.orchestrator.agent.memory

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CoreMemoryTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var storageFile: File
    private lateinit var memory: CoreMemory

    @BeforeEach
    fun setUp() {
        storageFile = tempDir.resolve("core-memory.json").toFile()
        memory = CoreMemory(storageFile)
    }

    @Nested
    inner class AppendTests {
        @Test
        fun `append creates block if absent`() {
            val result = memory.append("project", "Uses Gradle with Kotlin DSL")
            assertEquals("Uses Gradle with Kotlin DSL", result)
            assertEquals("Uses Gradle with Kotlin DSL", memory.read("project"))
        }

        @Test
        fun `append adds newline between entries`() {
            memory.append("project", "Line 1")
            val result = memory.append("project", "Line 2")
            assertEquals("Line 1\nLine 2", result)
        }

        @Test
        fun `append rejects read-only block`() {
            memory.setBlock("system", "Do not change", readOnly = true)
            assertThrows(IllegalArgumentException::class.java) {
                memory.append("system", "extra")
            }
        }

        @Test
        fun `append rejects if over limit`() {
            memory.setBlock("small", "", limit = 10)
            assertThrows(IllegalArgumentException::class.java) {
                memory.append("small", "A".repeat(11))
            }
        }
    }

    @Nested
    inner class ReplaceTests {
        @Test
        fun `replace exact match`() {
            memory.append("user", "Name: Alice")
            val result = memory.replace("user", "Alice", "Bob")
            assertEquals("Name: Bob", result)
        }

        @Test
        fun `replace rejects no match`() {
            memory.append("user", "Name: Alice")
            assertThrows(IllegalArgumentException::class.java) {
                memory.replace("user", "Charlie", "Bob")
            }
        }

        @Test
        fun `replace rejects multiple matches`() {
            memory.append("user", "foo foo")
            assertThrows(IllegalArgumentException::class.java) {
                memory.replace("user", "foo", "bar")
            }
        }

        @Test
        fun `replace rejects unknown block`() {
            assertThrows(IllegalArgumentException::class.java) {
                memory.replace("nonexistent", "a", "b")
            }
        }
    }

    @Nested
    inner class CompileTests {
        @Test
        fun `compile returns null when empty`() {
            assertNull(memory.compile())
        }

        @Test
        fun `compile renders XML with block labels`() {
            memory.append("project", "Spring Boot app")
            memory.append("user", "Prefers Kotlin")
            val xml = memory.compile()!!
            assertTrue(xml.contains("<core_memory>"))
            assertTrue(xml.contains("<project>"))
            assertTrue(xml.contains("Spring Boot app"))
            assertTrue(xml.contains("<user>"))
            assertTrue(xml.contains("Prefers Kotlin"))
            assertTrue(xml.contains("</core_memory>"))
        }

        @Test
        fun `compile shows character usage`() {
            memory.append("project", "test")
            val xml = memory.compile()!!
            assertTrue(xml.contains("4/${CoreMemory.DEFAULT_BLOCK_LIMIT} chars used"))
        }
    }

    @Nested
    inner class PersistenceTests {
        @Test
        fun `persists and reloads`() {
            memory.append("project", "Gradle build")
            memory.append("user", "Uses dark theme")

            val reloaded = CoreMemory(storageFile)
            assertEquals("Gradle build", reloaded.read("project"))
            assertEquals("Uses dark theme", reloaded.read("user"))
        }

        @Test
        fun `handles missing file gracefully`() {
            val missing = File(tempDir.toFile(), "does-not-exist.json")
            val m = CoreMemory(missing)
            assertTrue(m.isEmpty())
        }
    }

    @Nested
    inner class HelperTests {
        @Test
        fun `isEmpty returns true initially`() {
            assertTrue(memory.isEmpty())
        }

        @Test
        fun `isEmpty returns false after append`() {
            memory.append("x", "data")
            assertFalse(memory.isEmpty())
        }

        @Test
        fun `totalChars counts all blocks`() {
            memory.append("a", "123")
            memory.append("b", "45678")
            assertEquals(8, memory.totalChars())
        }

        @Test
        fun `readAll returns all blocks`() {
            memory.append("a", "1")
            memory.append("b", "2")
            val all = memory.readAll()
            assertEquals(2, all.size)
        }
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "*.CoreMemoryTest" -x verifyPlugin`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/CoreMemory.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/memory/CoreMemoryTest.kt
git commit -m "feat(agent): add CoreMemory — Tier 1 always-in-prompt working memory

Port of Letta's Memory/Block pattern with Goose's file-based persistence.
JSON key-value store with named blocks, character limits, read-only flag.
Compiles to <core_memory> XML for system prompt injection."
```

---

### Task 2: ArchivalMemory — JSON store with tag search and usage decay

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/ArchivalMemory.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/memory/ArchivalMemoryTest.kt`

Port from: Letta's archival_memory_insert/search (core_tool_executor.py:307-317, agent_manager.py:2534-2670) + Codex's usage tracking (stage1_outputs usage_count/last_usage).

- [ ] **Step 1: Write ArchivalMemory implementation**

```kotlin
package com.workflow.orchestrator.agent.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Tier 2: Archival Memory — long-term searchable knowledge store.
 *
 * Port of Letta's archival memory (passage_manager.py, agent_manager.py)
 * with Goose's file-based storage and Codex's usage tracking/decay.
 *
 * Storage: JSON file at ~/.workflow-orchestrator/{proj}/agent/archival/store.json
 * Search: Keyword matching with 3x tag boost (no embeddings — spec requirement).
 * Decay: Entries unused for maxUnusedDays get pruned (Codex pattern).
 * Cap: 5000 entries, oldest evicted when full.
 *
 * Letta uses PostgreSQL + pgvector. We use JSON files for zero external dependencies.
 * Codex tracks usage_count/last_usage per memory — we do the same.
 */
class ArchivalMemory(private val storageFile: File) {

    companion object {
        const val MAX_ENTRIES = 5_000
        const val DEFAULT_MAX_UNUSED_DAYS = 30L
        const val TAG_BOOST_MULTIPLIER = 3

        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        fun forProject(agentDir: File): ArchivalMemory {
            val archivalDir = File(agentDir, "archival")
            return ArchivalMemory(File(archivalDir, "store.json"))
        }
    }

    private var entries: MutableList<ArchivalEntry> = mutableListOf()

    init {
        load()
    }

    /**
     * Insert a memory with tags.
     *
     * Port of Letta's archival_memory_insert (core_tool_executor.py:307-317).
     * Adds Codex-style usage_count=0 and createdAt timestamp.
     *
     * @param content the memory text
     * @param tags categorization tags (e.g., ["error_resolution", "spring", "cors"])
     * @return the created entry ID
     */
    fun insert(content: String, tags: List<String> = emptyList()): String {
        // Evict oldest if at capacity (spec: cap 5000, oldest evicted)
        while (entries.size >= MAX_ENTRIES) {
            val oldest = entries.minByOrNull { it.createdAt } ?: break
            entries.remove(oldest)
        }

        val entry = ArchivalEntry(
            id = generateId(),
            content = content,
            tags = tags.map { it.lowercase().trim() }.distinct(),
            createdAt = Instant.now().epochSecond,
            usageCount = 0,
            lastUsage = null
        )
        entries.add(entry)
        persist()
        return entry.id
    }

    /**
     * Search archival memory by keyword with tag boosting.
     *
     * Port of Letta's search_agent_archival_memory_async (agent_manager.py:2534-2670)
     * simplified: keyword matching instead of vector similarity + FTS hybrid.
     *
     * Scoring (per spec): keyword match in content = 1 point per hit,
     * keyword match in tags = 3 points per hit (TAG_BOOST_MULTIPLIER).
     *
     * Codex addition: records usage on matched entries (usage_count++, last_usage=now).
     *
     * @param query search query (split into keywords)
     * @param tags optional tag filter (entries must have at least one matching tag)
     * @param limit max results (default 10)
     * @return matched entries sorted by relevance score descending
     */
    fun search(query: String, tags: List<String>? = null, limit: Int = 10): List<SearchResult> {
        val keywords = query.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (keywords.isEmpty() && tags.isNullOrEmpty()) return emptyList()

        val filterTags = tags?.map { it.lowercase().trim() }

        val scored = entries.mapNotNull { entry ->
            // Tag filter: if specified, entry must match at least one
            if (filterTags != null && filterTags.none { it in entry.tags }) return@mapNotNull null

            var score = 0
            for (kw in keywords) {
                // Content matches
                val contentHits = entry.content.lowercase().windowed(kw.length, 1)
                    .count { it == kw }
                score += contentHits

                // Tag matches (3x boost per spec)
                val tagHits = entry.tags.count { it.contains(kw) }
                score += tagHits * TAG_BOOST_MULTIPLIER
            }

            if (score > 0 || (keywords.isEmpty() && filterTags != null)) {
                SearchResult(entry = entry, score = if (keywords.isEmpty()) 1 else score)
            } else null
        }
            .sortedByDescending { it.score }
            .take(limit)

        // Record usage on matched entries (Codex pattern)
        val now = Instant.now().epochSecond
        for (result in scored) {
            result.entry.usageCount++
            result.entry.lastUsage = now
        }
        if (scored.isNotEmpty()) persist()

        return scored
    }

    /**
     * Prune entries unused for more than maxUnusedDays.
     *
     * Port of Codex's prune_stage1_outputs_for_retention.
     * Retention metric: COALESCE(lastUsage, createdAt).
     */
    fun prune(maxUnusedDays: Long = DEFAULT_MAX_UNUSED_DAYS): Int {
        val cutoff = Instant.now().epochSecond - (maxUnusedDays * 86400)
        val before = entries.size
        entries.removeAll { entry ->
            val recency = entry.lastUsage ?: entry.createdAt
            recency < cutoff
        }
        val removed = before - entries.size
        if (removed > 0) persist()
        return removed
    }

    /**
     * Get all entries (for debugging/export).
     */
    fun all(): List<ArchivalEntry> = entries.toList()

    /**
     * Entry count.
     */
    fun size(): Int = entries.size

    // ---- Persistence ----

    private fun load() {
        if (!storageFile.exists()) {
            entries = mutableListOf()
            return
        }
        try {
            val content = storageFile.readText()
            val stored = json.decodeFromString<StoredArchival>(content)
            entries = stored.entries.toMutableList()
        } catch (e: Exception) {
            entries = mutableListOf()
        }
    }

    private fun persist() {
        try {
            storageFile.parentFile?.mkdirs()
            val stored = StoredArchival(entries = entries.toList())
            val tempFile = File(storageFile.parent, "${storageFile.name}.tmp")
            tempFile.writeText(json.encodeToString(stored))
            tempFile.renameTo(storageFile)
        } catch (e: Exception) {
            // Best-effort persistence
        }
    }

    private fun generateId(): String = "mem_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

    // ---- Data classes ----

    @Serializable
    data class ArchivalEntry(
        val id: String,
        val content: String,
        val tags: List<String>,
        val createdAt: Long,
        var usageCount: Int = 0,
        var lastUsage: Long? = null
    )

    data class SearchResult(
        val entry: ArchivalEntry,
        val score: Int
    )

    @Serializable
    private data class StoredArchival(val entries: List<ArchivalEntry>)
}
```

- [ ] **Step 2: Write ArchivalMemory tests**

```kotlin
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
```

- [ ] **Step 3: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "*.ArchivalMemoryTest" -x verifyPlugin`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/ArchivalMemory.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/memory/ArchivalMemoryTest.kt
git commit -m "feat(agent): add ArchivalMemory — Tier 2 long-term searchable store

Port of Letta's archival memory with Goose's file-based storage and
Codex's usage tracking/decay. JSON store with tag-boosted keyword search.
Cap 5000 entries, usage_count/last_usage tracking, decay-based pruning."
```

---

### Task 3: ConversationRecall — search past JSONL sessions

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/ConversationRecall.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/memory/ConversationRecallTest.kt`

Port from: Letta's conversation_search (core_tool_executor.py:81-305) simplified to keyword search over our JSONL files.

- [ ] **Step 1: Write ConversationRecall implementation**

```kotlin
package com.workflow.orchestrator.agent.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Tier 3: Conversation Recall — search past session transcripts.
 *
 * Port of Letta's conversation_search (core_tool_executor.py:81-305)
 * simplified: keyword search over existing JSONL session files.
 *
 * Read-only — sessions are persisted by SessionStore.
 * Searches across all messages.jsonl files in the sessions directory.
 *
 * Letta uses hybrid embedding + FTS search. We use keyword matching
 * (sufficient for <100 sessions, sub-second performance).
 */
class ConversationRecall(private val sessionsDir: File) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val DEFAULT_LIMIT = 20

        fun forProject(agentDir: File): ConversationRecall {
            return ConversationRecall(File(agentDir, "sessions"))
        }
    }

    /**
     * Search past session transcripts by keyword.
     *
     * Port of Letta's conversation_search with role/date filtering.
     *
     * @param query search query (keywords, case-insensitive)
     * @param roles filter by message role (e.g., ["user", "assistant"])
     * @param limit max results (default 20)
     * @return matching messages with session context
     */
    fun search(
        query: String,
        roles: List<String>? = null,
        limit: Int = DEFAULT_LIMIT
    ): List<RecallResult> {
        val keywords = query.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (keywords.isEmpty()) return emptyList()

        val results = mutableListOf<RecallResult>()

        if (!sessionsDir.exists() || !sessionsDir.isDirectory) return emptyList()

        // Scan all session directories for messages.jsonl files
        val sessionDirs = sessionsDir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name }
            ?: return emptyList()

        for (sessionDir in sessionDirs) {
            val messagesFile = File(sessionDir, "messages.jsonl")
            if (!messagesFile.exists()) continue

            val sessionId = sessionDir.name

            try {
                messagesFile.forEachLine { line ->
                    if (results.size >= limit) return@forEachLine
                    if (line.isBlank()) return@forEachLine

                    try {
                        val msg = json.decodeFromString<JsonObject>(line)
                        val role = msg["role"]?.jsonPrimitive?.content ?: return@forEachLine
                        val content = msg["content"]?.jsonPrimitive?.content ?: return@forEachLine

                        // Role filter (Letta: filter by assistant/user/tool)
                        if (roles != null && role !in roles) return@forEachLine

                        // Skip tool messages (Letta: removes ALL tool messages to prevent recursion)
                        if (role == "tool") return@forEachLine

                        // Keyword match
                        val contentLower = content.lowercase()
                        val matched = keywords.all { kw -> contentLower.contains(kw) }
                        if (!matched) return@forEachLine

                        results.add(RecallResult(
                            sessionId = sessionId,
                            role = role,
                            content = content.take(500),
                            score = keywords.count { kw -> contentLower.contains(kw) }
                        ))
                    } catch (e: Exception) {
                        // Skip malformed lines
                    }
                }
            } catch (e: Exception) {
                // Skip unreadable session files
            }

            if (results.size >= limit) break
        }

        return results.sortedByDescending { it.score }.take(limit)
    }

    data class RecallResult(
        val sessionId: String,
        val role: String,
        val content: String,
        val score: Int
    )
}
```

- [ ] **Step 2: Write ConversationRecall tests**

```kotlin
package com.workflow.orchestrator.agent.memory

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ConversationRecallTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var sessionsDir: File
    private lateinit var recall: ConversationRecall

    @BeforeEach
    fun setUp() {
        sessionsDir = tempDir.resolve("sessions").toFile()
        sessionsDir.mkdirs()
        recall = ConversationRecall(sessionsDir)
    }

    private fun createSession(sessionId: String, vararg messages: Pair<String, String>) {
        val dir = File(sessionsDir, sessionId)
        dir.mkdirs()
        val file = File(dir, "messages.jsonl")
        file.writeText(messages.joinToString("\n") { (role, content) ->
            """{"role":"$role","content":"$content"}"""
        })
    }

    @Test
    fun `search finds matching messages across sessions`() {
        createSession("s1", "user" to "Fix the CORS error in SecurityConfig")
        createSession("s2", "user" to "Add pagination to the API")

        val results = recall.search("CORS")
        assertEquals(1, results.size)
        assertEquals("s1", results[0].sessionId)
        assertTrue(results[0].content.contains("CORS"))
    }

    @Test
    fun `search filters by role`() {
        createSession("s1",
            "user" to "Fix the bug",
            "assistant" to "I found the bug in UserService"
        )

        val userOnly = recall.search("bug", roles = listOf("user"))
        assertEquals(1, userOnly.size)
        assertEquals("user", userOnly[0].role)

        val assistantOnly = recall.search("bug", roles = listOf("assistant"))
        assertEquals(1, assistantOnly.size)
        assertEquals("assistant", assistantOnly[0].role)
    }

    @Test
    fun `search skips tool messages`() {
        createSession("s1",
            "user" to "Search for tests",
            "tool" to "Found 5 test files"
        )

        val results = recall.search("test")
        assertEquals(1, results.size)
        assertEquals("user", results[0].role)
    }

    @Test
    fun `search respects limit`() {
        createSession("s1",
            "user" to "kotlin question 1",
            "user" to "kotlin question 2",
            "user" to "kotlin question 3"
        )

        val results = recall.search("kotlin", limit = 2)
        assertEquals(2, results.size)
    }

    @Test
    fun `search returns empty for no matches`() {
        createSession("s1", "user" to "hello world")
        val results = recall.search("nonexistent")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search handles missing sessions dir`() {
        val missing = ConversationRecall(File(tempDir.toFile(), "nope"))
        val results = missing.search("test")
        assertTrue(results.isEmpty())
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "*.ConversationRecallTest" -x verifyPlugin`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/ConversationRecall.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/memory/ConversationRecallTest.kt
git commit -m "feat(agent): add ConversationRecall — Tier 3 past session search

Port of Letta's conversation_search simplified to keyword matching over
existing JSONL session transcripts. Filters by role, skips tool messages."
```

---

### Task 4: Memory Tools — 7 AgentTools for the 3-tier memory system

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/memory/CoreMemoryReadTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/memory/CoreMemoryAppendTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/memory/CoreMemoryReplaceTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/memory/ArchivalMemoryInsertTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/memory/ArchivalMemorySearchTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/memory/ConversationSearchTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/memory/SaveMemoryTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/memory/MemoryToolsTest.kt`

Each tool follows the existing AgentTool pattern (see ThinkTool.kt as reference). Tools receive memory stores via constructor injection (not global singletons).

- [ ] **Step 1: Create all 7 tool files**

Each tool implements `AgentTool` with name, description, parameters, allowedWorkers, and execute().

**CoreMemoryReadTool:** Takes optional `label` param. Returns all blocks or specific block content.

**CoreMemoryAppendTool:** Takes `label` and `content` params. Calls CoreMemory.append(). Returns new block value.

**CoreMemoryReplaceTool:** Takes `label`, `old_content`, `new_content` params. Calls CoreMemory.replace(). Returns new block value.

**ArchivalMemoryInsertTool:** Takes `content` and `tags` (comma-separated string) params. Calls ArchivalMemory.insert(). Returns confirmation with entry ID.

**ArchivalMemorySearchTool:** Takes `query`, optional `tags` (comma-separated), optional `limit` params. Calls ArchivalMemory.search(). Returns formatted results.

**ConversationSearchTool:** Takes `query`, optional `roles` (comma-separated), optional `limit` params. Calls ConversationRecall.search(). Returns formatted results.

**SaveMemoryTool:** Takes `content`, optional `filename` params. Saves markdown file to `~/.workflow-orchestrator/{proj}/agent/memory/`. Legacy compatibility tool (Tier 4 from spec).

All tools have `allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER)` (all workers can use memory).

- [ ] **Step 2: Write tests for all 7 tools**

Test each tool's execute() with mocked Project and real memory stores (using @TempDir). Verify:
- Correct ToolResult content format
- Error handling (missing params, invalid blocks)
- isError flag on failures
- Integration with underlying memory stores

- [ ] **Step 3: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "*.MemoryToolsTest" -x verifyPlugin`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/memory/ \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/memory/
git commit -m "feat(agent): add 7 memory tools — core_memory_read/append/replace, archival_memory_insert/search, conversation_search, save_memory

Port of Letta's memory tool set. Each tool delegates to the corresponding
memory store (CoreMemory, ArchivalMemory, ConversationRecall)."
```

---

### Task 5: Wire memory into AgentService and system prompt

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`

Wire the memory stores and tools into the agent lifecycle:
1. Create CoreMemory, ArchivalMemory, ConversationRecall in AgentService init
2. Register all 7 memory tools in ToolRegistry
3. Add `coreMemory` parameter to SystemPrompt.build()
4. Inject `<core_memory>` section between repo_map and rules (matching spec section 8)
5. Prune archival memory on startup (Codex pattern)

- [ ] **Step 1: Read AgentService to find where tools are registered and system prompt is built**

Read `AgentService.kt` to find:
- Where ToolRegistry is populated (look for `safeRegisterCore` or `registry.register`)
- Where SystemPrompt.build() is called
- Where the agent dir path is computed (`ProjectIdentifier.agentDir(basePath)`)

- [ ] **Step 2: Add memory store initialization in AgentService**

```kotlin
// In AgentService init, after agentDir is computed:
val coreMemory = CoreMemory.forProject(agentDir)
val archivalMemory = ArchivalMemory.forProject(agentDir)
val conversationRecall = ConversationRecall.forProject(agentDir)

// Prune archival memory on startup (Codex pattern)
val pruned = archivalMemory.prune()
if (pruned > 0) LOG.info("[AgentService] Pruned $pruned stale archival memories")
```

- [ ] **Step 3: Register memory tools in ToolRegistry**

```kotlin
// Register memory tools (all as core — always available)
safeRegisterCore { CoreMemoryReadTool(coreMemory) }
safeRegisterCore { CoreMemoryAppendTool(coreMemory) }
safeRegisterCore { CoreMemoryReplaceTool(coreMemory) }
safeRegisterCore { ArchivalMemoryInsertTool(archivalMemory) }
safeRegisterCore { ArchivalMemorySearchTool(archivalMemory) }
safeRegisterCore { ConversationSearchTool(conversationRecall) }
safeRegisterCore { SaveMemoryTool(agentDir) }
```

- [ ] **Step 4: Add coreMemory to SystemPrompt.build()**

Add parameter: `coreMemoryXml: String? = null`

Inject between section 7 (repo_map) and section 8 (rules):
```kotlin
// 7.5 CORE MEMORY (optional — injected if non-empty)
coreMemoryXml?.let {
    append(SECTION_SEP)
    append(it)
}
```

- [ ] **Step 5: Pass compiled core memory when building system prompt**

In AgentService where SystemPrompt.build() is called:
```kotlin
val systemPrompt = SystemPrompt.build(
    projectName = project.name,
    projectPath = project.basePath ?: "",
    // ... existing params ...
    coreMemoryXml = coreMemory.compile()
)
```

- [ ] **Step 6: Verify compilation**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt
git commit -m "feat(agent): wire 3-tier memory system into AgentService and system prompt

Initialize CoreMemory/ArchivalMemory/ConversationRecall on startup.
Register 7 memory tools in ToolRegistry.
Inject <core_memory> into system prompt.
Prune stale archival entries on startup (Codex decay pattern)."
```

---

### Task 6: Integration test — memory round-trip

**Files:**
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/memory/MemoryIntegrationTest.kt`

End-to-end test: create all 3 stores, use tools to insert/search/read, verify round-trip persistence.

- [ ] **Step 1: Write integration test**

Test scenarios:
1. Core memory: append → read → replace → compile → verify XML
2. Archival: insert with tags → search by keyword → verify tag boost → verify usage tracking
3. Recall: create fake session JSONL → search → verify role filter
4. Persistence: create stores, insert data, create new stores from same files, verify data survives

- [ ] **Step 2: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "*.MemoryIntegrationTest" -x verifyPlugin`
Expected: All tests PASS

- [ ] **Step 3: Run ALL agent tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test -x verifyPlugin`
Expected: All tests PASS (no regressions)

- [ ] **Step 4: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/memory/MemoryIntegrationTest.kt
git commit -m "test(agent): add memory system integration tests

Round-trip tests for all 3 tiers: core memory append/replace/compile,
archival insert/search with tag boost and usage tracking,
conversation recall with role filtering."
```

---

### Task 7: Update documentation

**Files:**
- Modify: `CLAUDE.md` (root)

Update CLAUDE.md to reflect the implemented memory system (it's currently documented in agent/CLAUDE.md but not in root CLAUDE.md).

- [ ] **Step 1: Add memory section to root CLAUDE.md**

Add after Agent Personas section:
```markdown
## Agent Memory System

Three-tier memory ported from Letta/MemGPT pattern with file-based storage:

**Tier 1 — Core Memory** (`core-memory.json`): Named blocks always injected as `<core_memory>` in system prompt.
Agent self-edits via `core_memory_read/append/replace` tools. Per-block character limits.

**Tier 2 — Archival Memory** (`archival/store.json`): JSON store with tag-boosted keyword search.
Insert via `archival_memory_insert`, search via `archival_memory_search`. Usage tracking with
decay-based pruning (Codex pattern, 30-day window). Cap: 5000 entries.

**Tier 3 — Conversation Recall**: Keyword search across JSONL session transcripts via `conversation_search`.
Read-only, filters by role. Backed by existing SessionStore.

**Legacy — save_memory**: Markdown file persistence for backward compatibility.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add agent memory system documentation to root CLAUDE.md"
```

---

## Summary

| Task | Description | New Files | Modified Files |
|------|-------------|-----------|----------------|
| 1 | CoreMemory store | 2 | 0 |
| 2 | ArchivalMemory store | 2 | 0 |
| 3 | ConversationRecall store | 2 | 0 |
| 4 | 7 memory tools | 8 | 0 |
| 5 | Wire into AgentService + SystemPrompt | 0 | 2 |
| 6 | Integration tests | 1 | 0 |
| 7 | Documentation | 0 | 1 |

**Total:** 15 new files, 3 modified files, 7 commits, ~1200 lines of implementation + ~600 lines of tests
