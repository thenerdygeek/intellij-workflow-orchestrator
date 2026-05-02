package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.util.StringUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import java.io.File

/**
 * Persisted wrapper around the api_conversation_history.json messages list.
 *
 * The pre-Phase-4 on-disk shape was a bare JSON array `[ApiMessage, ...]`.
 * Phase 4 introduces this wrapper with a [schemaVersion] field so future
 * schema changes can be detected at read time.
 *
 * The reader tries this wrapper first, then falls back to the bare array
 * for legacy v1 sessions. The writer always emits this wrapper at
 * `schemaVersion = 2`.
 *
 * Phase 4 of multimodal-agent plan.
 */
@Serializable
data class ApiHistoryFile(
    val schemaVersion: Int = 1,
    val messages: List<ApiMessage>,
)

class MessageStateHandler(
    private val baseDir: File,
    val sessionId: String,
    val taskText: String,
) {
    private val mutex = Mutex()
    // Delegate to the shared, polymorphic-fallback-enabled Json instance in the
    // companion object so reads and writes share the same defensive deserialization
    // contract. See `configuredJson` below for the full configuration.
    private val json = configuredJson
    private val prettyJson = configuredPrettyJson

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
        if (isEmptyAssistant(message)) {
            // Provider-error turns (empty content + no tool use) carry no information
            // and, persisted, accumulate across retries — training the model to mimic
            // the empty pattern. Dropped on the write path; paired nudge (if any) is
            // already in-memory only via ContextManager.
            return@withLock
        }
        apiHistory.add(message)
        saveApiHistoryInternal()
    }

    /**
     * Strip trailing assistant messages that have neither text content nor tool use.
     * Mirror of `ContextManager.pruneTrailingEmptyAssistants` for the disk side.
     * Called by the retry and resume paths in `AgentService` to clean any pollution
     * that landed before the write-time guard was introduced.
     *
     * @return number of empty assistant entries removed
     */
    suspend fun pruneTrailingEmptyAssistants(): Int = mutex.withLock {
        var removed = 0
        while (apiHistory.isNotEmpty() && isEmptyAssistant(apiHistory.last())) {
            apiHistory.removeAt(apiHistory.size - 1)
            removed++
        }
        if (removed > 0) {
            saveApiHistoryInternal()
        }
        removed
    }

    @Suppress("DEPRECATION")  // legacy ContentBlock.Image still has to exhaust the when
    private fun isEmptyAssistant(message: ApiMessage): Boolean {
        if (message.role != ApiRole.ASSISTANT) return false
        if (message.content.isEmpty()) return true
        return message.content.all { block ->
            when (block) {
                // isEffectivelyBlank covers `""`, `"   "`, AND U+200B-only echoes
                // (the LLM mirroring our own placeholder back). See StringUtils.
                is ContentBlock.Text -> StringUtils.isEffectivelyBlank(block.text)
                is ContentBlock.ToolUse -> false
                is ContentBlock.ToolResult -> false
                is ContentBlock.Image -> false
                is ContentBlock.ImageRef -> false
                else -> false
            }
        }
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

    /**
     * Rewrites the content of the most recent tool-result message for a given tool name.
     * Used by the plan discard flow to prevent the LLM from re-surfacing a discarded plan.
     *
     * Finds the most recent ASSISTANT message with a ToolUse of [toolName], then finds
     * the corresponding USER message with a matching ToolResult, and replaces its content
     * with [newContent]. Uses the existing mutex + atomic-write mechanism.
     *
     * @return true if a matching tool result was found and rewritten, false otherwise.
     */
    suspend fun rewriteMostRecentToolResult(toolName: String, newContent: String): Boolean = mutex.withLock {
        // Find most recent assistant message with a ToolUse of this name
        val toolUseId = apiHistory.lastOrNull { msg ->
            msg.role == ApiRole.ASSISTANT && msg.content.any { it is ContentBlock.ToolUse && it.name == toolName }
        }?.content?.filterIsInstance<ContentBlock.ToolUse>()?.lastOrNull { it.name == toolName }?.id
            ?: return@withLock false

        // Find the most recent user message containing the matching ToolResult
        val idx = apiHistory.indexOfLast { msg ->
            msg.role == ApiRole.USER && msg.content.any { it is ContentBlock.ToolResult && it.toolUseId == toolUseId }
        }
        if (idx < 0) return@withLock false

        val msg = apiHistory[idx]
        apiHistory[idx] = msg.copy(
            content = msg.content.map { block ->
                if (block is ContentBlock.ToolResult && block.toolUseId == toolUseId) block.copy(content = newContent)
                else block
            }
        )
        saveApiHistoryInternal()
        true
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
        // Phase 4: emit v2 wrapper { schemaVersion: 2, messages: [...] }.
        // Reader (loadApiHistory) tries this shape first and falls back to the
        // bare-array v1 shape for legacy sessions.
        val payload = ApiHistoryFile(schemaVersion = SCHEMA_VERSION_CURRENT, messages = apiHistory.toList())
        AtomicFileWriter.write(apiHistoryFile, json.encodeToString(ApiHistoryFile.serializer(), payload))
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
        /**
         * Current schema version emitted by the writer. v1 (the pre-Phase-4
         * bare-array shape) is detected by [loadApiHistory]'s fallback and
         * normalized into the in-memory model with no version field.
         *
         * Phase 4 of multimodal-agent plan.
         */
        const val SCHEMA_VERSION_CURRENT: Int = 2

        /** Separate mutex for sessions.json to prevent races between concurrent sessions (I2 fix). */
        private val globalIndexMutex = Mutex()

        /**
         * Shared serializers module with a polymorphic fallback for unknown
         * [ContentBlock] discriminators. Phase 1 of multimodal-agent plan.
         *
         * `kotlinx-serialization`'s `ignoreUnknownKeys = true` covers unknown FIELDS
         * within known classes — it does NOT cover unknown polymorphic discriminators.
         * A v1 plugin loading a v2 session file (e.g. with `type: "image_url_ref"` from
         * Phase 4) would otherwise crash with `SerializationException`.
         */
        private val contentBlockModule = SerializersModule {
            polymorphic(ContentBlock::class) {
                // Existing subclasses (Text/ToolUse/ToolResult/Image) are auto-registered
                // via their `@SerialName` annotations. Unknown discriminators fall through
                // to UnsupportedContentBlock so v1 readers degrade gracefully.
                defaultDeserializer { UnsupportedContentBlockSerializer }
            }
        }

        private val configuredJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
            serializersModule = contentBlockModule
        }
        private val configuredPrettyJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            serializersModule = contentBlockModule
        }
        private val compactJson = Json {
            ignoreUnknownKeys = true
            serializersModule = contentBlockModule
        }
        private val prettyJsonStatic = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            serializersModule = contentBlockModule
        }

        /**
         * Test-only accessor for the configured [Json] instance. Used by
         * `UnknownContentBlockTest` to verify the polymorphic fallback is wired.
         * Returns the same instance the production read/write paths use.
         */
        internal fun jsonForTesting(): Json = configuredJson

        fun loadUiMessages(sessionDir: File): List<UiMessage> {
            val file = File(sessionDir, "ui_messages.json")
            if (!file.exists()) return emptyList()
            return try {
                compactJson.decodeFromString<List<UiMessage>>(file.readText())
            } catch (_: Exception) { emptyList() }
        }

        /**
         * Loads `api_conversation_history.json` with backward-compat for the
         * pre-Phase-4 bare-array shape.
         *
         * Order:
         *   1. Try v2 wrapper [ApiHistoryFile] — `{schemaVersion, messages}`.
         *   2. On failure, fall back to v1 bare array `List<ApiMessage>` and
         *      synthesize an implicit `schemaVersion = 1`.
         *   3. On both failing, return [emptyList] (corrupted file — better
         *      than crashing the session UI).
         *
         * Phase 4 of multimodal-agent plan.
         */
        fun loadApiHistory(sessionDir: File): List<ApiMessage> {
            val file = File(sessionDir, "api_conversation_history.json")
            if (!file.exists()) return emptyList()
            val text = file.readText()
            // Try v2 wrapper first
            return runCatching {
                compactJson.decodeFromString(ApiHistoryFile.serializer(), text).messages
            }.recoverCatching {
                // v1 fallback: bare JSON array
                compactJson.decodeFromString(ListSerializer(ApiMessage.serializer()), text)
            }.getOrElse {
                emptyList()
            }
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
