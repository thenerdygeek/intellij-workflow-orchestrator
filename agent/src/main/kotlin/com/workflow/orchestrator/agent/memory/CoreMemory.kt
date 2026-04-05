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
         * Default block labels pre-seeded on first initialization.
         * Created empty so they appear as labeled sections in compile() output,
         * guiding the LLM on what to store. The MEMORY prompt section explains each block.
         */
        private val DEFAULT_BLOCK_LABELS = listOf("user", "project", "patterns")

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
        seedDefaultBlocks()
    }

    /**
     * Pre-seed empty labeled blocks on first initialization so the LLM
     * sees the structure in <core_memory> and knows what to fill in.
     * Only creates blocks that don't already exist (safe to call on every load).
     * Blocks start empty — the MEMORY prompt section tells the LLM what each is for.
     */
    private fun seedDefaultBlocks() {
        var changed = false
        for (label in DEFAULT_BLOCK_LABELS) {
            if (label !in blocks) {
                blocks[label] = MemoryBlock(value = "", limit = DEFAULT_BLOCK_LIMIT)
                changed = true
            }
        }
        if (changed) persist()
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

        // Non-overlapping count — matches Python's str.count() used by Letta
        val occurrences = block.value.split(oldContent).size - 1
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
