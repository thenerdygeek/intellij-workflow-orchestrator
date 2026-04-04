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
