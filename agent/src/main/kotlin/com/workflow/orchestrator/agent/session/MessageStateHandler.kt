package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class MessageStateHandler(
    private val baseDir: File,
    val sessionId: String,
    private val taskText: String,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val prettyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val uiMessages: MutableList<UiMessage> = mutableListOf()
    private val apiHistory: MutableList<ApiMessage> = mutableListOf()

    /** Throttle global index updates to at most once per second during streaming. */
    @Volatile private var lastGlobalIndexUpdateMs = 0L
    @Volatile private var globalIndexDirty = false
    private val globalIndexThrottleMs = 1000L

    private val sessionDir: File get() = File(baseDir, "sessions/$sessionId")
    private val uiMessagesFile: File get() = File(sessionDir, "ui_messages.json")
    private val apiHistoryFile: File get() = File(sessionDir, "api_conversation_history.json")
    private val globalIndexFile: File get() = File(baseDir, "sessions.json")

    fun getClineMessages(): List<UiMessage> = uiMessages.toList()
    fun getApiConversationHistory(): List<ApiMessage> = apiHistory.toList()

    suspend fun addToClineMessages(message: UiMessage) = mutex.withLock {
        val histIdx = if (apiHistory.isEmpty()) null else apiHistory.size - 1
        val indexed = message.copy(conversationHistoryIndex = histIdx)
        uiMessages.add(indexed)
        saveInternal()
    }

    suspend fun updateClineMessage(index: Int, updated: UiMessage) = mutex.withLock {
        if (index in uiMessages.indices) {
            uiMessages[index] = updated
            saveInternal()
        }
    }

    suspend fun deleteClineMessage(index: Int) = mutex.withLock {
        if (index in uiMessages.indices) {
            uiMessages.removeAt(index)
            saveInternal()
        }
    }

    suspend fun addToApiConversationHistory(message: ApiMessage) = mutex.withLock {
        apiHistory.add(message)
        saveApiHistoryInternal()
    }

    suspend fun overwriteApiConversationHistory(messages: List<ApiMessage>) = mutex.withLock {
        apiHistory.clear()
        apiHistory.addAll(messages)
        saveApiHistoryInternal()
    }

    suspend fun overwriteClineMessages(messages: List<UiMessage>) = mutex.withLock {
        uiMessages.clear()
        uiMessages.addAll(messages)
        saveInternal()
    }

    /** Call ONLY during initialization, before any concurrent access begins. */
    fun setClineMessages(messages: List<UiMessage>) {
        check(!mutex.isLocked) { "setClineMessages must only be called during init, before concurrent access" }
        uiMessages.clear()
        uiMessages.addAll(messages)
    }

    /** Call ONLY during initialization, before any concurrent access begins. */
    fun setApiConversationHistory(messages: List<ApiMessage>) {
        check(!mutex.isLocked) { "setApiConversationHistory must only be called during init, before concurrent access" }
        apiHistory.clear()
        apiHistory.addAll(messages)
    }

    private suspend fun saveInternal() {
        sessionDir.mkdirs()
        AtomicFileWriter.write(uiMessagesFile, json.encodeToString(uiMessages))
        val now = System.currentTimeMillis()
        if (now - lastGlobalIndexUpdateMs >= globalIndexThrottleMs) {
            updateGlobalIndex()
            lastGlobalIndexUpdateMs = now
            globalIndexDirty = false
        } else {
            globalIndexDirty = true
        }
    }

    private fun saveApiHistoryInternal() {
        sessionDir.mkdirs()
        AtomicFileWriter.write(apiHistoryFile, json.encodeToString(apiHistory))
    }

    suspend fun saveBoth() = mutex.withLock {
        saveInternal()
        saveApiHistoryInternal()
        // Flush any throttled global index update
        if (globalIndexDirty) {
            updateGlobalIndex()
            lastGlobalIndexUpdateMs = System.currentTimeMillis()
            globalIndexDirty = false
        }
    }

    private suspend fun updateGlobalIndex() = globalIndexMutex.withLock {
        val totalTokensIn = apiHistory.sumOf { it.metrics?.inputTokens ?: 0 }
        val totalTokensOut = apiHistory.sumOf { it.metrics?.outputTokens ?: 0 }
        val totalCost = apiHistory.sumOf { it.metrics?.cost ?: 0.0 }
        val lastModel = apiHistory.lastOrNull { it.modelInfo != null }?.modelInfo?.modelId

        val item = HistoryItem(
            id = sessionId,
            ts = uiMessages.lastOrNull()?.ts ?: System.currentTimeMillis(),
            task = taskText.take(200),
            tokensIn = totalTokensIn.toLong(),
            tokensOut = totalTokensOut.toLong(),
            totalCost = totalCost,
            modelId = lastModel
        )

        val existingItems: MutableList<HistoryItem> = try {
            if (globalIndexFile.exists()) {
                prettyJson.decodeFromString<MutableList<HistoryItem>>(globalIndexFile.readText())
            } else mutableListOf()
        } catch (_: Exception) { mutableListOf() }

        val idx = existingItems.indexOfFirst { it.id == sessionId }
        if (idx >= 0) {
            val preserved = item.copy(isFavorited = existingItems[idx].isFavorited)
            existingItems[idx] = preserved
        } else {
            existingItems.add(0, item)
        }

        baseDir.mkdirs()
        AtomicFileWriter.write(globalIndexFile, prettyJson.encodeToString(existingItems))
    }

    companion object {
        /** Separate mutex for sessions.json to prevent races between concurrent sessions (I2 fix). */
        private val globalIndexMutex = Mutex()

        private val compactJson = Json { ignoreUnknownKeys = true }
        private val prettyJsonStatic = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        fun loadUiMessages(sessionDir: File): List<UiMessage> {
            val file = File(sessionDir, "ui_messages.json")
            if (!file.exists()) return emptyList()
            return try {
                compactJson.decodeFromString<List<UiMessage>>(file.readText())
            } catch (_: Exception) { emptyList() }
        }

        fun loadApiHistory(sessionDir: File): List<ApiMessage> {
            val file = File(sessionDir, "api_conversation_history.json")
            if (!file.exists()) return emptyList()
            return try {
                compactJson.decodeFromString<List<ApiMessage>>(file.readText())
            } catch (_: Exception) { emptyList() }
        }

        fun loadGlobalIndex(baseDir: File): List<HistoryItem> {
            val file = File(baseDir, "sessions.json")
            if (!file.exists()) return emptyList()
            return try {
                compactJson.decodeFromString<List<HistoryItem>>(file.readText())
            } catch (_: Exception) { emptyList() }
        }

        private val SAFE_SESSION_ID = Regex("^[a-zA-Z0-9_-]+$")

        fun deleteSession(baseDir: File, sessionId: String) {
            if (!sessionId.matches(SAFE_SESSION_ID)) return
            val indexFile = File(baseDir, "sessions.json")
            if (indexFile.exists()) {
                try {
                    val items = compactJson.decodeFromString<List<HistoryItem>>(indexFile.readText())
                    val filtered = items.filter { it.id != sessionId }
                    AtomicFileWriter.write(indexFile, prettyJsonStatic.encodeToString(filtered))
                } catch (_: Exception) { /* corrupted index, skip */ }
            }
            val sessionDir = File(baseDir, "sessions/$sessionId")
            if (sessionDir.exists()) {
                sessionDir.deleteRecursively()
            }
        }

        fun toggleFavorite(baseDir: File, sessionId: String) {
            if (!sessionId.matches(SAFE_SESSION_ID)) return
            val indexFile = File(baseDir, "sessions.json")
            if (!indexFile.exists()) return
            try {
                val items = compactJson.decodeFromString<List<HistoryItem>>(indexFile.readText())
                val updated = items.map { item ->
                    if (item.id == sessionId) item.copy(isFavorited = !item.isFavorited) else item
                }
                if (updated != items) {
                    AtomicFileWriter.write(indexFile, prettyJsonStatic.encodeToString(updated))
                }
            } catch (_: Exception) { /* corrupted index, skip */ }
        }

        // ── Checkpoint operations (moved from SessionStore) ──────────────

        private fun checkpointsDir(baseDir: File, sessionId: String): File =
            File(baseDir, "sessions/$sessionId/checkpoints")

        private fun checkpointFile(baseDir: File, sessionId: String, checkpointId: String): File =
            File(checkpointsDir(baseDir, sessionId), "$checkpointId.jsonl")

        private fun checkpointMetaFile(baseDir: File, sessionId: String, checkpointId: String): File =
            File(checkpointsDir(baseDir, sessionId), "$checkpointId.meta.json")

        /**
         * Save a named checkpoint — a snapshot of messages at a specific point in time.
         */
        fun saveCheckpoint(
            baseDir: File,
            sessionId: String,
            checkpointId: String,
            messages: List<ChatMessage>,
            description: String = ""
        ) {
            val dir = checkpointsDir(baseDir, sessionId)
            dir.mkdirs()

            val file = checkpointFile(baseDir, sessionId, checkpointId)
            val content = buildString {
                for (msg in messages) {
                    appendLine(compactJson.encodeToString(msg))
                }
            }
            AtomicFileWriter.write(file, content)

            val meta = CheckpointInfo(
                id = checkpointId,
                createdAt = System.currentTimeMillis(),
                messageCount = messages.size,
                description = description
            )
            val metaFile = checkpointMetaFile(baseDir, sessionId, checkpointId)
            AtomicFileWriter.write(metaFile, prettyJsonStatic.encodeToString(meta))
        }

        /**
         * Load a specific checkpoint's messages.
         */
        fun loadCheckpoint(baseDir: File, sessionId: String, checkpointId: String): List<ChatMessage>? {
            val file = checkpointFile(baseDir, sessionId, checkpointId)
            if (!file.exists()) return null
            return try {
                file.readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            compactJson.decodeFromString<ChatMessage>(line)
                        } catch (_: Exception) { null }
                    }
            } catch (_: Exception) { null }
        }

        /**
         * List all checkpoints for a session, sorted by creation time (newest first).
         */
        fun listCheckpoints(baseDir: File, sessionId: String): List<CheckpointInfo> {
            val dir = checkpointsDir(baseDir, sessionId)
            if (!dir.exists()) return emptyList()
            return dir.listFiles { f -> f.name.endsWith(".meta.json") }
                ?.mapNotNull { file ->
                    try {
                        compactJson.decodeFromString<CheckpointInfo>(file.readText())
                    } catch (_: Exception) { null }
                }
                ?.sortedByDescending { it.createdAt }
                ?: emptyList()
        }

        /**
         * Delete checkpoints that are newer than a given checkpoint.
         */
        fun deleteCheckpointsAfter(baseDir: File, sessionId: String, checkpointId: String) {
            val targetMeta = checkpointMetaFile(baseDir, sessionId, checkpointId)
            if (!targetMeta.exists()) return
            val targetInfo = try {
                compactJson.decodeFromString<CheckpointInfo>(targetMeta.readText())
            } catch (_: Exception) { return }

            val allCheckpoints = listCheckpoints(baseDir, sessionId)
            for (cp in allCheckpoints) {
                if (cp.createdAt > targetInfo.createdAt) {
                    checkpointFile(baseDir, sessionId, cp.id).delete()
                    checkpointMetaFile(baseDir, sessionId, cp.id).delete()
                }
            }
        }
    }
}
