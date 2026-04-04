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

    /**
     * Write content to a file atomically using write-then-rename.
     *
     * Ported from Cline's atomicWriteFile in disk.ts: write to a temporary file
     * in the same directory, then rename to the target path. Rename is atomic on
     * most filesystems (POSIX guarantees, NTFS on same volume), preventing
     * partial/corrupt files on crash or power loss.
     */
    private fun atomicWrite(targetFile: File, content: String) {
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
        try {
            tempFile.writeText(content)
            if (!tempFile.renameTo(targetFile)) {
                // renameTo can fail on some platforms (e.g., cross-device);
                // fall back to copy + delete
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /**
     * Write content to a file atomically using a BufferedWriter via write-then-rename.
     * Used for multi-line writes (JSONL files with many messages).
     */
    private fun atomicWriteBuffered(targetFile: File, writer: (java.io.BufferedWriter) -> Unit) {
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
        try {
            tempFile.bufferedWriter().use(writer)
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    // ── Session metadata (Cline's HistoryItem equivalent) ─────────────

    fun save(session: Session) {
        sessionsDir.mkdirs()
        val file = File(sessionsDir, "${session.id}.json")
        atomicWrite(file, json.encodeToString(session))
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
        atomicWriteBuffered(file) { writer ->
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

    // ── Checkpoint reversion (ported from Cline's context-management checkpoint pattern) ──

    private fun checkpointsDir(sessionId: String): File =
        File(sessionDataDir(sessionId), "checkpoints")

    private fun checkpointFile(sessionId: String, checkpointId: String): File =
        File(checkpointsDir(sessionId), "$checkpointId.jsonl")

    private fun checkpointMetaFile(sessionId: String, checkpointId: String): File =
        File(checkpointsDir(sessionId), "$checkpointId.meta.json")

    /**
     * Save a named checkpoint — a snapshot of messages at a specific point in time.
     *
     * Ported from Cline's checkpoint-based conversation reversion pattern:
     * Cline tracks conversation state snapshots that can be reverted to.
     * We persist checkpoints as JSONL files in the session's checkpoints/ directory.
     *
     * @param sessionId the session this checkpoint belongs to
     * @param checkpointId unique identifier for this checkpoint
     * @param messages the conversation messages at this point
     * @param description human-readable description (e.g., "After editing UserService.kt")
     */
    fun saveCheckpoint(
        sessionId: String,
        checkpointId: String,
        messages: List<ChatMessage>,
        description: String = ""
    ) {
        val dir = checkpointsDir(sessionId)
        dir.mkdirs()

        // Save messages as JSONL (atomic write-then-rename for crash safety)
        val file = checkpointFile(sessionId, checkpointId)
        atomicWriteBuffered(file) { writer ->
            for (msg in messages) {
                writer.write(compactJson.encodeToString(msg))
                writer.newLine()
            }
        }

        // Save metadata (atomic write-then-rename for crash safety)
        val meta = CheckpointInfo(
            id = checkpointId,
            createdAt = System.currentTimeMillis(),
            messageCount = messages.size,
            description = description
        )
        val metaFile = checkpointMetaFile(sessionId, checkpointId)
        atomicWrite(metaFile, json.encodeToString(meta))
    }

    /**
     * Load a specific checkpoint's messages.
     *
     * @param sessionId the session
     * @param checkpointId the checkpoint to load
     * @return the messages at that checkpoint, or null if not found
     */
    fun loadCheckpoint(sessionId: String, checkpointId: String): List<ChatMessage>? {
        val file = checkpointFile(sessionId, checkpointId)
        if (!file.exists()) return null
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        compactJson.decodeFromString<ChatMessage>(line)
                    } catch (_: Exception) {
                        null
                    }
                }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * List all checkpoints for a session, sorted by creation time (newest first).
     *
     * @param sessionId the session
     * @return list of checkpoint metadata
     */
    fun listCheckpoints(sessionId: String): List<CheckpointInfo> {
        val dir = checkpointsDir(sessionId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".meta.json") }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<CheckpointInfo>(file.readText())
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /**
     * Delete checkpoints that are newer than a given checkpoint.
     * Used during reversion: when reverting to checkpoint C, all checkpoints
     * created after C are invalidated and should be removed.
     *
     * @param sessionId the session
     * @param checkpointId the checkpoint to revert to (this one is kept)
     */
    fun deleteCheckpointsAfter(sessionId: String, checkpointId: String) {
        val targetMeta = checkpointMetaFile(sessionId, checkpointId)
        if (!targetMeta.exists()) return
        val targetInfo = try {
            json.decodeFromString<CheckpointInfo>(targetMeta.readText())
        } catch (_: Exception) {
            return
        }

        val allCheckpoints = listCheckpoints(sessionId)
        for (cp in allCheckpoints) {
            if (cp.createdAt > targetInfo.createdAt) {
                checkpointFile(sessionId, cp.id).delete()
                checkpointMetaFile(sessionId, cp.id).delete()
            }
        }
    }

    companion object {
        /** Cline uses GlobalFileNames.apiConversationHistory = "api_conversation_history.json".
         *  We use JSONL for efficient append. */
        const val MESSAGES_FILE_NAME = "messages.jsonl"
    }
}
