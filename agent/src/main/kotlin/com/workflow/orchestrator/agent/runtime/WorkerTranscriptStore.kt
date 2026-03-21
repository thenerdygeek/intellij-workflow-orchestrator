package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Persists worker (subagent) transcripts to JSONL for resume capability.
 *
 * Storage layout:
 *   {sessionDir}/subagents/agent-{agentId}.jsonl   — conversation transcript
 *   {sessionDir}/subagents/agent-{agentId}.meta.json — metadata (status, type, timestamps)
 *
 * Follows the same JSONL append-only pattern as ConversationStore.
 */
class WorkerTranscriptStore(private val sessionDir: File) {

    companion object {
        private val LOG = Logger.getInstance(WorkerTranscriptStore::class.java)
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

        fun generateAgentId(): String = UUID.randomUUID().toString().take(12)
    }

    @Serializable
    data class WorkerMetadata(
        val agentId: String,
        val subagentType: String,
        val description: String,
        val status: String = "running",  // running, completed, failed, killed
        val createdAt: Long = System.currentTimeMillis(),
        var completedAt: Long? = null,
        var tokensUsed: Int = 0,
        var summary: String? = null
    )

    @Serializable
    data class TranscriptMessage(
        val role: String,
        val content: String? = null,
        val toolCallId: String? = null,
        val toolCalls: String? = null,  // JSON string of tool calls
        val timestamp: Long = System.currentTimeMillis()
    )

    private val subagentsDir: File get() = File(sessionDir, "subagents").also { it.mkdirs() }

    private fun transcriptFile(agentId: String) = File(subagentsDir, "agent-$agentId.jsonl")
    private fun metadataFile(agentId: String) = File(subagentsDir, "agent-$agentId.meta.json")

    /**
     * Save metadata for a worker.
     */
    fun saveMetadata(metadata: WorkerMetadata) {
        try {
            metadataFile(metadata.agentId).writeText(json.encodeToString(metadata))
        } catch (e: Exception) {
            LOG.warn("WorkerTranscriptStore: failed to save metadata for ${metadata.agentId}", e)
        }
    }

    /**
     * Load metadata for a worker.
     */
    fun loadMetadata(agentId: String): WorkerMetadata? {
        val file = metadataFile(agentId)
        if (!file.isFile) return null
        return try {
            json.decodeFromString<WorkerMetadata>(file.readText())
        } catch (e: Exception) {
            LOG.warn("WorkerTranscriptStore: failed to load metadata for $agentId", e)
            null
        }
    }

    /**
     * Append a message to the worker's transcript (append-only JSONL).
     */
    fun appendMessage(agentId: String, message: TranscriptMessage) {
        try {
            val line = json.encodeToString(message)
            transcriptFile(agentId).appendText(line + "\n")
        } catch (e: Exception) {
            LOG.debug("WorkerTranscriptStore: failed to append message for $agentId: ${e.message}")
        }
    }

    /**
     * Load all messages from a worker's transcript.
     */
    fun loadTranscript(agentId: String): List<TranscriptMessage> {
        val file = transcriptFile(agentId)
        if (!file.isFile) return emptyList()
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try { json.decodeFromString<TranscriptMessage>(line) }
                    catch (_: Exception) { null }
                }
        } catch (e: Exception) {
            LOG.warn("WorkerTranscriptStore: failed to load transcript for $agentId", e)
            emptyList()
        }
    }

    /**
     * Convert transcript messages to ChatMessages for ContextManager replay.
     */
    fun toChatMessages(transcript: List<TranscriptMessage>): List<ChatMessage> {
        return transcript.map { msg ->
            ChatMessage(
                role = msg.role,
                content = msg.content,
                toolCallId = msg.toolCallId
            )
        }
    }

    /**
     * Update worker status in metadata.
     */
    fun updateStatus(agentId: String, status: String, summary: String? = null, tokensUsed: Int? = null) {
        val meta = loadMetadata(agentId) ?: return
        meta.completedAt = if (status != "running") System.currentTimeMillis() else null
        meta.summary = summary ?: meta.summary
        meta.tokensUsed = tokensUsed ?: meta.tokensUsed
        saveMetadata(meta.copy(status = status))
    }

    /**
     * List all worker transcripts in this session.
     */
    fun listWorkers(): List<WorkerMetadata> {
        if (!subagentsDir.isDirectory) return emptyList()
        return subagentsDir.listFiles()
            ?.filter { it.name.endsWith(".meta.json") }
            ?.mapNotNull { file ->
                try { json.decodeFromString<WorkerMetadata>(file.readText()) }
                catch (_: Exception) { null }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }
}
