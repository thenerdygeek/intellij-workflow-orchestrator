package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.util.AgentStringUtils
import com.workflow.orchestrator.core.util.ProjectIdentifier
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
    var status: SessionStatus,
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
 *   ~/.workflow-orchestrator/{ProjectName-hash}/agent/sessions/{sessionId}/messages.jsonl
 *   ~/.workflow-orchestrator/{ProjectName-hash}/agent/sessions/{sessionId}/metadata.json
 *
 * Messages are appended one-per-line (JSONL) for crash-safety — a crash mid-write
 * loses at most one message. Metadata is overwritten after each turn.
 *
 * All I/O runs on the caller's thread. Callers (AgentController, ConversationSession)
 * are already on Dispatchers.IO so no additional threading is needed here.
 */
class ConversationStore(
    private val sessionId: String,
    /** Override for testing — when null, uses ProjectIdentifier-based path. */
    private val baseDir: File? = null,
    /** Project base path — required when baseDir is null. */
    private val projectBasePath: String? = null
) {
    private val sessionDir: File by lazy {
        val parent = baseDir ?: run {
            require(projectBasePath != null) { "ConversationStore requires projectBasePath when baseDir is not provided" }
            ProjectIdentifier.sessionsDir(projectBasePath)
        }
        File(parent, sessionId).also { it.mkdirs() }
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
        /** Maximum recent messages to keep verbatim when recovering a session with many messages. */
        private const val RECOVERY_RECENT_MESSAGE_LIMIT = 20

        /**
         * Get the root sessions directory.
         * Uses [baseDir] override for testing, otherwise ProjectIdentifier.
         */
        fun getSessionsDir(baseDir: File? = null, projectBasePath: String? = null): File {
            return baseDir ?: run {
                require(projectBasePath != null) { "getSessionsDir requires projectBasePath when baseDir is not provided" }
                ProjectIdentifier.sessionsDir(projectBasePath)
            }
        }

        /**
         * List all session IDs (subdirectory names under sessions/).
         */
        fun listSessionIds(baseDir: File? = null, projectBasePath: String? = null): List<String> {
            val dir = getSessionsDir(baseDir, projectBasePath)
            if (!dir.exists()) return emptyList()
            return dir.listFiles()
                ?.filter { it.isDirectory && File(it, "metadata.json").exists() }
                ?.map { it.name }
                ?: emptyList()
        }

        /**
         * Delete an entire session directory.
         */
        fun deleteSession(sessionId: String, baseDir: File? = null, projectBasePath: String? = null) {
            val dir = File(getSessionsDir(baseDir, projectBasePath), sessionId)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }

        /**
         * Load a session from disk for recovery after IDE restart or crash.
         *
         * Reads messages from `messages.jsonl` and metadata from `metadata.json`
         * in the session directory. If more than [RECOVERY_RECENT_MESSAGE_LIMIT] messages
         * exist, older messages are compressed into a summary to stay within context limits.
         *
         * A recovery injection message is appended to orient the LLM about the
         * recovered session state.
         *
         * @param sessionDir The session directory containing messages.jsonl and metadata.json
         * @return [RecoveredSession] containing messages, metadata, and optional plan, or null
         *         if the session directory is missing or unreadable
         */
        fun loadSession(sessionDir: File): RecoveredSession? {
            val messagesFile = File(sessionDir, "messages.jsonl")
            if (!messagesFile.exists()) return null

            val allMessages = messagesFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        json.decodeFromString<PersistedMessage>(line)
                    } catch (_: Exception) {
                        null // Skip corrupted lines
                    }
                }

            if (allMessages.isEmpty()) return null

            val metadataFile = File(sessionDir, "metadata.json")
            val metadata = if (metadataFile.exists()) {
                try {
                    json.decodeFromString<SessionMetadata>(metadataFile.readText())
                } catch (_: Exception) {
                    null
                }
            } else null

            // Load persisted plan if available
            val plan = PlanPersistence.load(sessionDir)

            // Compress old messages if there are too many
            val recoveredMessages: List<PersistedMessage>
            val compressionSummary: String?

            if (allMessages.size > RECOVERY_RECENT_MESSAGE_LIMIT) {
                val oldMessages = allMessages.dropLast(RECOVERY_RECENT_MESSAGE_LIMIT)
                compressionSummary = summarizeOldMessages(oldMessages)
                recoveredMessages = allMessages.takeLast(RECOVERY_RECENT_MESSAGE_LIMIT)
            } else {
                compressionSummary = null
                recoveredMessages = allMessages
            }

            // Build recovery injection message
            val statusText = metadata?.status?.value ?: "unknown"
            val recoveryMessage = buildString {
                append("Session recovered from previous run. Last status: $statusText.")
                if (plan != null) {
                    val completedSteps = plan.steps.count { it.status == "done" }
                    val totalSteps = plan.steps.size
                    append(" Plan progress: $completedSteps/$totalSteps steps completed.")
                    val lastDone = plan.steps.lastOrNull { it.status == "done" }
                    if (lastDone != null) {
                        append(" Last completed step: ${lastDone.title}.")
                    }
                }
                append(" Resume from where you left off.")
            }

            return RecoveredSession(
                messages = recoveredMessages,
                metadata = metadata,
                plan = plan,
                compressionSummary = compressionSummary,
                recoveryMessage = recoveryMessage,
                totalMessageCount = allMessages.size
            )
        }

        /**
         * List all available sessions with their metadata, sorted by last message time (newest first).
         *
         * Unlike [listSessionIds] which returns only IDs, this method returns full metadata
         * for each session, enabling UI display of session history.
         *
         * @param baseDir Override for testing; when null, uses ProjectIdentifier
         * @param projectBasePath Required when baseDir is null
         * @return List of [SessionSummary] entries sorted by last message time descending
         */
        fun listSessions(baseDir: File? = null, projectBasePath: String? = null): List<SessionSummary> {
            val dir = getSessionsDir(baseDir, projectBasePath)
            if (!dir.exists()) return emptyList()

            return dir.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { sessionDir ->
                    val metadataFile = File(sessionDir, "metadata.json")
                    if (!metadataFile.exists()) return@mapNotNull null

                    val metadata = try {
                        json.decodeFromString<SessionMetadata>(metadataFile.readText())
                    } catch (_: Exception) {
                        return@mapNotNull null
                    }

                    val messagesFile = File(sessionDir, "messages.jsonl")
                    val hasPlan = File(sessionDir, "plan.json").exists()

                    SessionSummary(
                        sessionId = metadata.sessionId,
                        title = metadata.title,
                        projectName = metadata.projectName,
                        projectPath = metadata.projectPath,
                        model = metadata.model,
                        status = metadata.status,
                        createdAt = metadata.createdAt,
                        lastMessageAt = metadata.lastMessageAt,
                        messageCount = metadata.messageCount,
                        totalTokens = metadata.totalTokens,
                        hasPlan = hasPlan,
                        hasMessages = messagesFile.exists() && messagesFile.length() > 0,
                        sessionDir = sessionDir
                    )
                }
                ?.sortedByDescending { it.lastMessageAt }
                ?: emptyList()
        }

        /**
         * Summarize old messages into a compressed text summary.
         *
         * Extracts key information: user requests, assistant responses (truncated),
         * tool calls (name + arguments preview), and file paths referenced.
         * This is a heuristic summarizer — no LLM call required.
         */
        internal fun summarizeOldMessages(messages: List<PersistedMessage>): String {
            val sb = StringBuilder()
            sb.appendLine("## Recovered Session Summary (${messages.size} older messages compressed)")
            sb.appendLine()

            val filePaths = mutableSetOf<String>()
            val filePathRegex = AgentStringUtils.FILE_PATH_REGEX
            val toolsUsed = mutableSetOf<String>()

            for (msg in messages) {
                when (msg.role) {
                    "user" -> {
                        val preview = msg.content?.take(300) ?: ""
                        sb.appendLine("- User: $preview")
                    }
                    "assistant" -> {
                        if (msg.toolCalls != null) {
                            for (tc in msg.toolCalls) {
                                toolsUsed.add(tc.name)
                                val argsPreview = tc.arguments.take(150)
                                sb.appendLine("- Tool call: ${tc.name}($argsPreview)")
                            }
                        } else if (!msg.content.isNullOrBlank() && msg.content.length > 5) {
                            sb.appendLine("- Agent: ${msg.content.take(300)}")
                        }
                    }
                    "tool" -> {
                        // Extract file paths from tool results
                        msg.content?.let { content ->
                            filePathRegex.findAll(content).forEach { match ->
                                val path = match.value
                                if (path.contains('/') || path.contains('\\')) {
                                    filePaths.add(path)
                                }
                            }
                        }
                    }
                }
                // Limit summary size
                if (sb.length > 4000) {
                    sb.appendLine("... (${messages.size - messages.indexOf(msg)} more messages omitted)")
                    break
                }
            }

            if (toolsUsed.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("### Tools Used")
                sb.appendLine(toolsUsed.joinToString(", "))
            }

            if (filePaths.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("### Referenced Files")
                filePaths.take(20).forEach { sb.appendLine("- $it") }
            }

            return sb.toString().take(6000)
        }
    }
}

/**
 * Result of loading a session from disk for recovery.
 *
 * Contains the messages to replay (possibly compressed), metadata,
 * optional plan state, and a recovery injection message for the LLM.
 */
data class RecoveredSession(
    /** Messages to replay into context (last [ConversationStore.RECOVERY_RECENT_MESSAGE_LIMIT] if compressed). */
    val messages: List<PersistedMessage>,
    /** Session metadata, or null if metadata.json was missing/corrupt. */
    val metadata: SessionMetadata?,
    /** Persisted plan, or null if no plan was active. */
    val plan: AgentPlan?,
    /** Summary of compressed older messages, or null if no compression was needed. */
    val compressionSummary: String?,
    /** Recovery injection message to orient the LLM about the recovered state. */
    val recoveryMessage: String,
    /** Total number of messages in the original JSONL (before compression). */
    val totalMessageCount: Int
)

/**
 * Summary of a session for listing in the UI or programmatic access.
 * Includes metadata and basic file-existence checks without reading full JSONL.
 */
data class SessionSummary(
    val sessionId: String,
    val title: String,
    val projectName: String,
    val projectPath: String,
    val model: String,
    val status: SessionStatus,
    val createdAt: Long,
    val lastMessageAt: Long,
    val messageCount: Int,
    val totalTokens: Int,
    val hasPlan: Boolean,
    val hasMessages: Boolean,
    val sessionDir: File
)
