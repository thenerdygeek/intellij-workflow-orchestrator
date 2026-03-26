package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Checkpoint saved after each tool execution in a session.
 *
 * Enables two capabilities:
 * 1. **Interruption detection** — on IDE restart, sessions with phase="executing"
 *    are marked as "interrupted" and the user is offered a resume action.
 * 2. **Resume context** — when resuming, checkpoint data tells the agent what
 *    was happening (edited files, iteration count, plan status) so it can
 *    orient itself without re-discovering project state.
 *
 * Stored as checkpoint.json alongside the JSONL messages file in the session directory.
 * Messages are the authoritative conversation record (ConversationStore handles replay);
 * this provides supplementary loop state.
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
    val timestamp: Long = System.currentTimeMillis(),
    /** Files edited during this session — used by SelfCorrectionGate on resume. */
    val editedFiles: List<String> = emptyList(),
    /** Total persisted message count at checkpoint time — for verifying JSONL integrity. */
    val persistedMessageCount: Int = 0,
    /** Whether the session had an active plan at checkpoint time. */
    val hasPlan: Boolean = false,
    /** Brief description of what the agent was doing when checkpointed. */
    val lastActivity: String? = null
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun save(checkpoint: SessionCheckpoint, sessionDir: File) {
            sessionDir.mkdirs()
            // Atomic write: temp file then rename
            val tmp = File(sessionDir, "checkpoint.json.tmp")
            tmp.writeText(json.encodeToString(serializer(), checkpoint))
            tmp.renameTo(File(sessionDir, "checkpoint.json"))
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
