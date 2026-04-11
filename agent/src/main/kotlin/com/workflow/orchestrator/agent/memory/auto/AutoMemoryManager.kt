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
    client: SourcegraphChatClient,
    pathExists: (String) -> Boolean = { true },
    private val extractionLog: ExtractionLog? = null
) {
    private val log = Logger.getInstance(AutoMemoryManager::class.java)
    private val extractor = MemoryExtractor(client)
    private val retriever = RelevanceRetriever(archivalMemory, pathExists)

    companion object {
        /** Minimum substantive user turns to warrant extraction. */
        private const val MIN_USER_TURNS = 2

        /** Minimum character length for a user message to count as substantive. */
        private const val MIN_USER_TURN_LENGTH = 15

        /** Trivial greetings/acknowledgments to exclude from substantive count. */
        private val TRIVIAL_USER_MESSAGES = setOf(
            "hi", "hello", "hey", "ok", "okay", "thanks", "thank you", "bye", "goodbye",
            "cool", "nice", "yes", "no", "yep", "nope", "got it", "sure", "alright"
        )

        /** Valid core memory block names. Unknown blocks are ignored to prevent LLM hallucination. */
        private val ALLOWED_BLOCKS = setOf("user", "project", "patterns")
    }

    /**
     * Check if a session is worth extracting memory from.
     * Tighter than a simple message count — looks at substantive user turns only.
     * A session must contain at least [MIN_USER_TURNS] user messages whose content is
     * at least [MIN_USER_TURN_LENGTH] characters and is not a trivial greeting/ack.
     */
    private fun isWorthExtracting(messages: List<ChatMessage>): Boolean {
        val substantiveUserTurns = messages.count { msg ->
            val content = msg.content
            msg.role == "user"
                && content != null
                && content.length >= MIN_USER_TURN_LENGTH
                && content.lowercase().trim().trimEnd('.', '!', '?') !in TRIVIAL_USER_MESSAGES
        }
        return substantiveUserTurns >= MIN_USER_TURNS
    }

    /**
     * Called after a session completes successfully.
     * Makes a cheap LLM call to extract insights into memory.
     */
    suspend fun onSessionComplete(sessionId: String, messages: List<ChatMessage>) {
        if (!isWorthExtracting(messages)) {
            val userTurns = messages.count { it.role == "user" }
            log.info("[AutoMemory] Skipping extraction for trivial session $sessionId ($userTurns user turns, insufficient substance)")
            return
        }

        try {
            val currentCore = buildCoreMemoryMap()
            val result = extractor.extractFromSession(messages, currentCore) ?: return

            applyExtractionResult(result, source = "session-end/$sessionId", sessionId = sessionId)
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

    private fun applyExtractionResult(result: ExtractionResult, source: String, sessionId: String) {
        var coreUpdates = 0
        var archivalInserts = 0
        val appliedCoreUpdates = mutableListOf<CoreMemoryUpdate>()
        val appliedArchivalInserts = mutableListOf<ArchivalInsert>()

        for (update in result.coreMemoryUpdates) {
            // I2 fix: whitelist check prevents LLM hallucinated block names from
            // creating invisible orphan blocks that consume prompt tokens
            if (update.block !in ALLOWED_BLOCKS) {
                log.info("[AutoMemory] Ignoring update for unknown block '${update.block}' from $source")
                continue
            }
            try {
                when (update.action) {
                    UpdateAction.APPEND -> {
                        coreMemory.append(update.block, update.content)
                        coreUpdates++
                        appliedCoreUpdates.add(update)
                    }
                    UpdateAction.REPLACE -> {
                        val oldContent = update.oldContent
                        if (oldContent != null) {
                            // Use fuzzy match — Haiku cannot produce exact strings reliably
                            coreMemory.replaceFlexible(update.block, oldContent, update.content)
                            coreUpdates++
                            appliedCoreUpdates.add(update)
                        } else {
                            log.warn("[AutoMemory] Replace without old_content for block '${update.block}', skipping")
                        }
                    }
                }
            } catch (e: IllegalArgumentException) {
                // I7 fix: elevated from INFO to WARN. Block-limit rejections mean
                // auto-memory is silently capped — users should see this in the log.
                log.warn("[AutoMemory] Block '${update.block}' rejected update (likely at size limit): ${e.message}")
            }
        }

        for (insert in result.archivalInserts) {
            try {
                // M3 fix: ensure tagless entries remain searchable (tag boost requires tags).
                // LLM may return empty tags despite prompt asking for 2-4.
                val tags = insert.tags.ifEmpty { listOf("auto") }
                archivalMemory.insert(insert.content, tags)
                archivalInserts++
                appliedArchivalInserts.add(insert.copy(tags = tags))
            } catch (e: Exception) {
                log.warn("[AutoMemory] Failed to insert archival entry: ${e.message}")
            }
        }

        if (coreUpdates > 0 || archivalInserts > 0) {
            log.info("[AutoMemory] Applied from $source: $coreUpdates core updates, $archivalInserts archival inserts")
            // Record to audit log for settings-page visibility
            try {
                extractionLog?.record(sessionId, source, appliedCoreUpdates, appliedArchivalInserts)
            } catch (e: Exception) {
                log.warn("[AutoMemory] Failed to record extraction log: ${e.message}")
            }
        }
    }

    private fun buildCoreMemoryMap(): Map<String, String> {
        return coreMemory.readAll().mapValues { (_, block) -> block.value }
    }
}
