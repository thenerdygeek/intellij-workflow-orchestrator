package com.workflow.orchestrator.agent.memory.auto

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.memory.ArchivalMemory

/**
 * Retrieval-only memory orchestrator.
 *
 * Single trigger point: at session start, keyword search retrieves relevant
 * archival entries for `<recalled_memory>` prompt injection. No LLM call,
 * zero cost.
 *
 * Extraction into memory is not automatic — users add entries manually via
 * the memory tools (`core_memory_append`, `archival_memory_insert`) or by
 * editing the Memory settings page directly.
 */
class AutoMemoryManager(
    archivalMemory: ArchivalMemory,
    pathExists: (String) -> Boolean = { true }
) {
    private val log = Logger.getInstance(AutoMemoryManager::class.java)
    private val retriever = RelevanceRetriever(archivalMemory, pathExists)

    /**
     * Called before the first LLM call in a new session.
     * Returns relevant archival memories for system prompt injection.
     */
    fun onSessionStart(firstMessage: String): String? {
        return try {
            retriever.retrieveForMessage(firstMessage)
        } catch (e: Exception) {
            log.warn("[AutoMemory] Session-start retrieval failed (non-fatal)", e)
            null
        }
    }
}
