package com.workflow.orchestrator.agent.context

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Fixed-size, always-in-prompt memory block (Letta pattern).
 *
 * Core memory is injected into every LLM call as part of the system prompt.
 * The agent can self-edit it via tools to maintain an up-to-date picture of:
 * - Project context (build system, key patterns, architecture notes)
 * - User preferences (coding style, review habits)
 * - Active constraints (things to remember across sessions)
 *
 * Size-capped at [maxSizeChars] to avoid bloating the system prompt.
 * Persisted to `{projectBasePath}/.workflow/agent/core-memory.json`.
 */
class CoreMemory(
    private val storePath: File,
    private val maxSizeChars: Int = 4000
) {
    companion object {
        private val LOG = Logger.getInstance(CoreMemory::class.java)
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        private val instanceCache = java.util.concurrent.ConcurrentHashMap<String, CoreMemory>()

        fun forProject(projectBasePath: String): CoreMemory {
            return instanceCache.getOrPut(projectBasePath) {
                val dir = ProjectIdentifier.agentDir(projectBasePath)
                dir.mkdirs()
                CoreMemory(File(dir, "core-memory.json"))
            }
        }
    }

    @Serializable
    data class CoreMemoryState(
        val entries: MutableMap<String, String> = mutableMapOf(),
        var totalChars: Int = 0
    )

    private var state = CoreMemoryState()

    init {
        load()
    }

    /** Read current core memory for prompt injection. Returns null if empty. */
    fun render(): String? {
        if (state.entries.isEmpty()) return null
        return buildString {
            for ((key, value) in state.entries) {
                appendLine("[$key]: $value")
            }
        }.trimEnd()
    }

    /** Append a new entry or update an existing key. Returns error message or null on success. */
    fun append(key: String, value: String): String? {
        val sanitizedKey = key.take(50).trim()
        if (sanitizedKey.isBlank()) return "Key cannot be blank"

        val sanitizedValue = value.take(500).trim()
        if (sanitizedValue.isBlank()) return "Value cannot be blank"

        val existingSize = state.entries[sanitizedKey]?.length ?: 0
        val newTotalSize = state.totalChars - existingSize + sanitizedKey.length + sanitizedValue.length + 5 // overhead

        if (newTotalSize > maxSizeChars) {
            return "Core memory full ($maxSizeChars chars). Remove an entry first with core_memory_replace or delete."
        }

        state.entries[sanitizedKey] = sanitizedValue
        state.totalChars = calculateTotalChars()
        save()
        return null
    }

    /** Replace an existing entry's value. Returns error message or null on success. */
    fun replace(key: String, newValue: String): String? {
        if (key !in state.entries) return "Key '$key' not found in core memory"
        return append(key, newValue)
    }

    /** Remove an entry by key. Returns true if removed. */
    fun remove(key: String): Boolean {
        val removed = state.entries.remove(key) != null
        if (removed) {
            state.totalChars = calculateTotalChars()
            save()
        }
        return removed
    }

    /** Get all entries as a map (for inspection/debugging). */
    fun getEntries(): Map<String, String> = state.entries.toMap()

    /** Get remaining capacity in characters. */
    fun remainingCapacity(): Int = maxSizeChars - state.totalChars

    /** Get total number of entries. */
    fun entryCount(): Int = state.entries.size

    private fun calculateTotalChars(): Int =
        state.entries.entries.sumOf { it.key.length + it.value.length + 5 }

    private fun load() {
        try {
            if (storePath.exists()) {
                state = json.decodeFromString<CoreMemoryState>(storePath.readText())
                state.totalChars = calculateTotalChars()
            }
        } catch (e: Exception) {
            LOG.warn("CoreMemory: failed to load from ${storePath.name}", e)
            state = CoreMemoryState()
        }
    }

    private fun save() {
        try {
            storePath.parentFile?.mkdirs()
            // Atomic write: write to temp file, then move atomically
            val tmp = File(storePath.parentFile, "${storePath.name}.tmp")
            tmp.writeText(json.encodeToString(state))
            java.nio.file.Files.move(
                tmp.toPath(), storePath.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            LOG.warn("CoreMemory: failed to save to ${storePath.name}", e)
        }
    }
}
