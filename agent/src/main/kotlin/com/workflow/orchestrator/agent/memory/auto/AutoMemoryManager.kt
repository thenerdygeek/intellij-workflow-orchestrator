package com.workflow.orchestrator.agent.memory.auto

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.memory.ArchivalMemory
import com.workflow.orchestrator.agent.memory.CoreMemory
import com.workflow.orchestrator.core.ai.SourcegraphChatClient
import com.workflow.orchestrator.core.ai.dto.ChatMessage

/**
 * Orchestrates event-driven memory management.
 *
 * Two trigger points:
 * 1. Session end — cheap LLM call extracts insights from conversation -> core + archival memory.
 *    Single pass catches everything: corrections, confirmations, patterns, decisions,
 *    references, error resolutions.
 * 2. Session start — keyword search retrieves relevant archival entries for prompt injection.
 *    No LLM call, zero cost.
 *
 * All extraction is best-effort — failures are logged, never thrown.
 */
class AutoMemoryManager(
    private val coreMemory: CoreMemory,
    private val archivalMemory: ArchivalMemory,
    client: SourcegraphChatClient
) {
    private val log = Logger.getInstance(AutoMemoryManager::class.java)
    private val extractor = MemoryExtractor(client)
    private val retriever = RelevanceRetriever(archivalMemory)

    companion object {
        /** Minimum conversation messages to warrant extraction (skip trivial sessions). */
        private const val MIN_MESSAGES_FOR_EXTRACTION = 4
    }

    /**
     * Called after a session completes successfully.
     * Makes a cheap LLM call to extract insights into memory.
     */
    suspend fun onSessionComplete(sessionId: String, messages: List<ChatMessage>) {
        if (messages.size < MIN_MESSAGES_FOR_EXTRACTION) {
            log.info("[AutoMemory] Skipping extraction for short session $sessionId (${messages.size} messages)")
            return
        }

        try {
            val currentCore = buildCoreMemoryMap()
            val result = extractor.extractFromSession(messages, currentCore) ?: return

            applyExtractionResult(result, source = "session-end/$sessionId")
        } catch (e: Exception) {
            log.warn("[AutoMemory] Session-end extraction failed for $sessionId (non-fatal)", e)
        }
    }

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

    private fun applyExtractionResult(result: ExtractionResult, source: String) {
        var coreUpdates = 0
        var archivalInserts = 0

        for (update in result.coreMemoryUpdates) {
            try {
                when (update.action) {
                    UpdateAction.APPEND -> {
                        coreMemory.append(update.block, update.content)
                        coreUpdates++
                    }
                    UpdateAction.REPLACE -> {
                        val oldContent = update.oldContent
                        if (oldContent != null) {
                            coreMemory.replace(update.block, oldContent, update.content)
                            coreUpdates++
                        } else {
                            log.warn("[AutoMemory] Replace without old_content for block '${update.block}', skipping")
                        }
                    }
                }
            } catch (e: IllegalArgumentException) {
                log.info("[AutoMemory] Skipped core update for '${update.block}': ${e.message}")
            }
        }

        for (insert in result.archivalInserts) {
            try {
                archivalMemory.insert(insert.content, insert.tags)
                archivalInserts++
            } catch (e: Exception) {
                log.warn("[AutoMemory] Failed to insert archival entry: ${e.message}")
            }
        }

        if (coreUpdates > 0 || archivalInserts > 0) {
            log.info("[AutoMemory] Applied from $source: $coreUpdates core updates, $archivalInserts archival inserts")
        }
    }

    private fun buildCoreMemoryMap(): Map<String, String> {
        return coreMemory.readAll().mapValues { (_, block) -> block.value }
    }
}
