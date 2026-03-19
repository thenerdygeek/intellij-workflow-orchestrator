package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.application.PathManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persisted representation of a ChatMessage for JSONL storage.
 */
@Serializable
data class PersistedMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<PersistedToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Persisted representation of a tool call.
 */
@Serializable
data class PersistedToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * Session metadata stored as a separate JSON file.
 * Updated after each turn for quick listing without reading JSONL.
 */
@Serializable
data class SessionMetadata(
    val sessionId: String,
    val projectName: String,
    val projectPath: String,
    val title: String,
    val model: String,
    val createdAt: Long,
    var lastMessageAt: Long,
    var messageCount: Int,
    var status: String, // "active", "completed", "interrupted", "failed"
    var totalTokens: Int = 0
)

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

private val prettyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/**
 * Persists conversation history to JSONL files so conversations survive IDE restarts.
 *
 * Storage layout per session:
 *   {systemPath}/workflow-agent/sessions/{sessionId}/messages.jsonl
 *   {systemPath}/workflow-agent/sessions/{sessionId}/metadata.json
 *
 * Messages are appended one-per-line (JSONL) for crash-safety — a crash mid-write
 * loses at most one message. Metadata is overwritten after each turn.
 *
 * All I/O runs on the caller's thread. Callers (AgentController, ConversationSession)
 * are already on Dispatchers.IO so no additional threading is needed here.
 */
class ConversationStore(
    private val sessionId: String,
    /** Override for testing — when null, uses PathManager.getSystemPath(). */
    private val baseDir: File? = null
) {
    private val sessionDir: File by lazy {
        val parent = baseDir ?: File(PathManager.getSystemPath(), "workflow-agent/sessions")
        File(parent, sessionId)
    }

    /** Expose the session directory for checkpoint storage. */
    val sessionDirectory: File get() = sessionDir

    private val messagesFile: File get() = File(sessionDir, "messages.jsonl")
    private val metadataFile: File get() = File(sessionDir, "metadata.json")

    /**
     * Append a single message to messages.jsonl.
     * Creates the session directory on first write.
     */
    fun saveMessage(message: PersistedMessage) {
        sessionDir.mkdirs()
        val line = json.encodeToString(message)
        messagesFile.appendText(line + "\n")
    }

    /**
     * Read all messages from messages.jsonl.
     * Returns empty list if file doesn't exist or is empty.
     */
    fun loadMessages(): List<PersistedMessage> {
        if (!messagesFile.exists()) return emptyList()
        return messagesFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    json.decodeFromString<PersistedMessage>(line)
                } catch (_: Exception) {
                    null // Skip corrupted lines
                }
            }
    }

    /**
     * Write metadata.json (overwrites existing).
     */
    fun saveMetadata(metadata: SessionMetadata) {
        sessionDir.mkdirs()
        val content = prettyJson.encodeToString(metadata)
        metadataFile.writeText(content)
    }

    /**
     * Read metadata.json. Returns null if file doesn't exist.
     */
    fun loadMetadata(): SessionMetadata? {
        if (!metadataFile.exists()) return null
        return try {
            json.decodeFromString<SessionMetadata>(metadataFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * Get the root sessions directory.
         * Uses [baseDir] override for testing, otherwise PathManager.
         */
        fun getSessionsDir(baseDir: File? = null): File {
            return baseDir ?: File(PathManager.getSystemPath(), "workflow-agent/sessions")
        }

        /**
         * List all session IDs (subdirectory names under sessions/).
         */
        fun listSessionIds(baseDir: File? = null): List<String> {
            val dir = getSessionsDir(baseDir)
            if (!dir.exists()) return emptyList()
            return dir.listFiles()
                ?.filter { it.isDirectory && File(it, "metadata.json").exists() }
                ?.map { it.name }
                ?: emptyList()
        }

        /**
         * Delete an entire session directory.
         */
        fun deleteSession(sessionId: String, baseDir: File? = null) {
            val dir = File(getSessionsDir(baseDir), sessionId)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }
}
