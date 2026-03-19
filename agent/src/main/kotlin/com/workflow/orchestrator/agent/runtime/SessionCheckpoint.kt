package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Lightweight checkpoint saved after each tool execution in a session.
 *
 * Allows detection of interrupted sessions on IDE restart. Stored as
 * checkpoint.json alongside the JSONL messages file in the session directory.
 *
 * Not used for full replay (ConversationStore handles that) — this is purely
 * for detecting interruptions and providing resume metadata.
 */
@Serializable
data class SessionCheckpoint(
    val sessionId: String,
    val phase: String,              // "executing", "completed", "failed", "interrupted"
    val iteration: Int = 0,
    val tokensUsed: Int = 0,
    val lastToolCall: String? = null,
    val touchedFiles: List<String> = emptyList(),
    val rollbackCheckpointId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun save(checkpoint: SessionCheckpoint, sessionDir: File) {
            sessionDir.mkdirs()
            val file = File(sessionDir, "checkpoint.json")
            file.writeText(json.encodeToString(serializer(), checkpoint))
        }

        fun load(sessionDir: File): SessionCheckpoint? {
            val file = File(sessionDir, "checkpoint.json")
            if (!file.exists()) return null
            return try {
                json.decodeFromString(serializer(), file.readText())
            } catch (_: Exception) { null }
        }

        fun delete(sessionDir: File) {
            File(sessionDir, "checkpoint.json").delete()
        }
    }
}
