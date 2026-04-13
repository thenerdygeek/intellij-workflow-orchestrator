package com.workflow.orchestrator.agent.memory

import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.agent.session.MessageStateHandler
import java.io.File

/**
 * Tier 3: Conversation Recall — search past session transcripts.
 *
 * Port of Letta's conversation_search (core_tool_executor.py:81-305)
 * simplified: keyword search over api_conversation_history.json files.
 *
 * Read-only — sessions are persisted by MessageStateHandler.
 * Searches across all api_conversation_history.json files in the sessions directory.
 *
 * Letta uses hybrid embedding + FTS search. We use keyword matching
 * (sufficient for <100 sessions, sub-second performance).
 */
class ConversationRecall(private val sessionsDir: File) {

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_CONTENT_LENGTH = 500

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

        // Scan all session directories for api_conversation_history.json files
        val sessionDirs = sessionsDir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name }
            ?: return emptyList()

        for (sessionDir in sessionDirs) {
            val history: List<ApiMessage> = MessageStateHandler.loadApiHistory(sessionDir)
            if (history.isEmpty()) continue

            val sessionId = sessionDir.name

            for (msg in history) {
                if (results.size >= limit) break

                val role = msg.role.name.lowercase()

                // Role filter (Letta: filter by assistant/user/tool)
                if (roles != null && role !in roles) continue

                // Skip tool results (Letta: removes ALL tool messages to prevent recursion)
                if (msg.content.any { it is ContentBlock.ToolResult }) continue

                // Extract text content from content blocks
                val textContent = msg.content.filterIsInstance<ContentBlock.Text>()
                    .joinToString("\n") { it.text }
                    .takeIf { it.isNotBlank() } ?: continue

                // Keyword match — OR logic with frequency-based scoring
                // (unified with ArchivalMemory via MemoryKeywordSearch helper)
                val score = keywords.sumOf { kw ->
                    MemoryKeywordSearch.countOccurrences(textContent, kw)
                }
                if (score == 0) continue

                results.add(RecallResult(
                    sessionId = sessionId,
                    role = role,
                    content = textContent.take(MAX_CONTENT_LENGTH),
                    score = score
                ))
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
