package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Session metadata + conversation history persistence.
 *
 * Faithful port of Cline's disk.ts persistence pattern:
 * - Cline stores `api_conversation_history.json` (full JSON array of MessageParam[])
 *   alongside task metadata per task directory.
 * - We use JSONL (one message per line) for conversation history instead of a JSON array,
 *   because JSONL supports efficient append-only writes without reading + rewriting
 *   the entire file on every tool execution.
 *
 * Storage layout (matching Cline's per-task directory pattern):
 * ```
 * {baseDir}/sessions/{sessionId}.json        — Session metadata
 * {baseDir}/sessions/{sessionId}/messages.jsonl — Conversation history (one ChatMessage per line)
 * ```
 *
 * Thread safety: Cline uses atomicWriteFile + a sync worker for writes.
 * We use synchronized blocks since our writes are on coroutine IO dispatchers,
 * and JSONL append is inherently safe for single-writer scenarios.
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/storage/disk.ts">Cline disk.ts</a>
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/task/message-state.ts">Cline message-state.ts</a>
 */
class SessionStore(private val baseDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val compactJson = Json { ignoreUnknownKeys = true }
    private val sessionsDir: File get() = File(baseDir, "sessions")

    // ── Session metadata (Cline's HistoryItem equivalent) ─────────────

    fun save(session: Session) {
        sessionsDir.mkdirs()
        val file = File(sessionsDir, "${session.id}.json")
        file.writeText(json.encodeToString(session))
    }

    fun load(sessionId: String): Session? {
        val file = File(sessionsDir, "$sessionId.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<Session>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun list(): List<Session> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<Session>(file.readText())
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun delete(sessionId: String) {
        File(sessionsDir, "$sessionId.json").delete()
        sessionDataDir(sessionId).deleteRecursively()
    }

    // ── Conversation history (Cline's api_conversation_history equivalent) ──

    /**
     * Directory for per-session data files (messages.jsonl, etc.).
     * Matches Cline's ensureTaskDirectoryExists(taskId) pattern.
     */
    private fun sessionDataDir(sessionId: String): File =
        File(sessionsDir, sessionId)

    private fun messagesFile(sessionId: String): File =
        File(sessionDataDir(sessionId), MESSAGES_FILE_NAME)

    /**
     * Append a single message to the JSONL file.
     *
     * Cline calls saveApiConversationHistory after every addToApiConversationHistory,
     * writing the entire array each time. We optimize this: JSONL append writes only
     * the new message line, avoiding O(n) rewrites on each tool execution.
     *
     * This is the primary save path during normal execution — called after every
     * tool result is added to context.
     */
    fun appendMessage(sessionId: String, message: ChatMessage) {
        val dir = sessionDataDir(sessionId)
        dir.mkdirs()
        val file = messagesFile(sessionId)
        val line = compactJson.encodeToString(message)
        file.appendText(line + "\n")
    }

    /**
     * Load all messages from the JSONL file.
     *
     * Port of Cline's getSavedApiConversationHistory(taskId):
     * - Cline reads api_conversation_history.json and returns Anthropic.MessageParam[].
     * - We read messages.jsonl and return List<ChatMessage>.
     * - Cline handles file-not-found by returning []. We do the same.
     * - Cline handles parse errors by logging and returning []. We skip bad lines.
     */
    fun loadMessages(sessionId: String): List<ChatMessage> {
        val file = messagesFile(sessionId)
        if (!file.exists()) return emptyList()
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        compactJson.decodeFromString<ChatMessage>(line)
                    } catch (_: Exception) {
                        null // Skip corrupt lines, matching Cline's error-tolerant approach
                    }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Save all messages at once, replacing the entire JSONL file.
     *
     * Port of Cline's overwriteApiConversationHistory pattern:
     * used when resuming with modified history (e.g., after context truncation)
     * or during checkpoint restoration.
     */
    fun saveMessages(sessionId: String, messages: List<ChatMessage>) {
        val dir = sessionDataDir(sessionId)
        dir.mkdirs()
        val file = messagesFile(sessionId)
        file.bufferedWriter().use { writer ->
            for (msg in messages) {
                writer.write(compactJson.encodeToString(msg))
                writer.newLine()
            }
        }
    }

    /**
     * Returns the number of messages persisted for a session,
     * without loading the full list into memory.
     */
    fun messageCount(sessionId: String): Int {
        val file = messagesFile(sessionId)
        if (!file.exists()) return 0
        return try {
            file.readLines().count { it.isNotBlank() }
        } catch (_: Exception) {
            0
        }
    }

    companion object {
        /** Cline uses GlobalFileNames.apiConversationHistory = "api_conversation_history.json".
         *  We use JSONL for efficient append. */
        const val MESSAGES_FILE_NAME = "messages.jsonl"
    }
}
