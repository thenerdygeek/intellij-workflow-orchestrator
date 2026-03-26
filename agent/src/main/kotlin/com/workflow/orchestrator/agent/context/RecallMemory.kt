package com.workflow.orchestrator.agent.context

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.runtime.ConversationStore
import com.workflow.orchestrator.agent.runtime.SessionMetadata
import java.io.File

/**
 * Searchable conversation history across all past sessions (Letta pattern).
 *
 * Unlike ArchivalMemory (curated knowledge), RecallMemory provides raw access
 * to past conversation transcripts. The agent can search for what was discussed,
 * what tools were called, and what decisions were made.
 *
 * Read-only — conversations are persisted by ConversationStore.
 * Search is keyword-based over the JSONL message content.
 */
class RecallMemory {
    companion object {
        private val LOG = Logger.getInstance(RecallMemory::class.java)
        private const val MAX_RESULT_CHARS = 500
    }

    data class RecallResult(
        val sessionId: String,
        val sessionTitle: String,
        val matchingMessages: List<RecallMessage>,
        val score: Int
    )

    data class RecallMessage(
        val role: String,
        val contentPreview: String,
        val timestamp: Long
    )

    /**
     * Search past conversation sessions for messages matching the query.
     *
     * @param query Space-separated search terms
     * @param projectPath Filter to sessions from this project (null = all projects)
     * @param maxSessions Maximum number of sessions to return
     * @param maxMessagesPerSession Maximum matching messages per session
     * @return Ranked list of sessions with matching messages
     */
    fun search(
        query: String,
        projectPath: String? = null,
        maxSessions: Int = 5,
        maxMessagesPerSession: Int = 3
    ): List<RecallResult> {
        if (query.isBlank()) return emptyList()

        val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (queryTerms.isEmpty()) return emptyList()

        return try {
            val sessions = ConversationStore.listSessions(projectBasePath = projectPath)
                .filter { s ->
                    s.status in setOf("completed", "interrupted") &&
                    s.hasMessages
                }
                .take(50) // Limit scanning to last 50 sessions for performance

            sessions.mapNotNull { summary ->
                searchSession(summary.sessionId, summary.title, summary.sessionDir, queryTerms, maxMessagesPerSession)
            }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(maxSessions)
        } catch (e: Exception) {
            LOG.warn("RecallMemory: search failed for query '$query'", e)
            emptyList()
        }
    }

    private fun searchSession(
        sessionId: String,
        title: String,
        sessionDir: File?,
        queryTerms: List<String>,
        maxMessages: Int
    ): RecallResult? {
        return try {
            val store = ConversationStore(sessionId, baseDir = sessionDir?.parentFile)
            val messages = store.loadMessages()

            val matchingMessages = messages
                .filter { msg ->
                    val contentLower = (msg.content ?: "").lowercase()
                    queryTerms.any { term -> contentLower.contains(term) }
                }
                .take(maxMessages)
                .map { msg ->
                    val content = msg.content ?: ""
                    RecallMessage(
                        role = msg.role,
                        contentPreview = content.take(MAX_RESULT_CHARS) + if (content.length > MAX_RESULT_CHARS) "..." else "",
                        timestamp = msg.timestamp
                    )
                }

            if (matchingMessages.isEmpty()) return null

            // Score: number of matching messages * number of query terms found
            val allContent = messages.mapNotNull { it.content }.joinToString(" ").lowercase()
            val termsFound = queryTerms.count { allContent.contains(it) }
            val score = matchingMessages.size * termsFound

            RecallResult(
                sessionId = sessionId,
                sessionTitle = title,
                matchingMessages = matchingMessages,
                score = score
            )
        } catch (e: Exception) {
            LOG.debug("RecallMemory: failed to search session $sessionId", e)
            null
        }
    }
}
