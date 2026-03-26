package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Checkpoint data for persisting agent task state.
 */
@Serializable
data class AgentCheckpoint(
    val taskId: String,
    val taskGraphState: List<AgentTask>,
    val completedSummaries: Map<String, String>,
    val timestamp: Long
)

/**
 * Persists agent task state to .workflow/agent/ directory.
 * Uses kotlinx.serialization for JSON encoding/decoding.
 */
class CheckpointStore(
    private val baseDir: File
) {
    companion object {
        private val LOG = Logger.getInstance(CheckpointStore::class.java)
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Create a CheckpointStore for a project, storing checkpoints in
         * {projectDir}/.workflow/agent/
         */
        fun forProject(projectBasePath: String): CheckpointStore {
            return CheckpointStore(ProjectIdentifier.agentDir(projectBasePath))
        }
    }

    /**
     * Save a checkpoint for the given task ID.
     * Writes to .workflow/agent/checkpoint-{taskId}.json
     */
    fun save(taskId: String, checkpoint: AgentCheckpoint) {
        try {
            ensureDir()
            val file = checkpointFile(taskId)
            file.writeText(json.encodeToString(checkpoint))
            LOG.info("CheckpointStore: saved checkpoint for task '$taskId'")
        } catch (e: Exception) {
            LOG.warn("CheckpointStore: failed to save checkpoint for task '$taskId'", e)
            throw e
        }
    }

    /**
     * Load a checkpoint for the given task ID.
     * @return The checkpoint, or null if not found or corrupt
     */
    fun load(taskId: String): AgentCheckpoint? {
        val file = checkpointFile(taskId)
        if (!file.exists()) return null

        return try {
            val content = file.readText()
            json.decodeFromString<AgentCheckpoint>(content)
        } catch (e: Exception) {
            LOG.warn("CheckpointStore: failed to load checkpoint for task '$taskId'", e)
            null
        }
    }

    /**
     * Delete a checkpoint for the given task ID.
     * @return true if the checkpoint existed and was deleted
     */
    fun delete(taskId: String): Boolean {
        val file = checkpointFile(taskId)
        return if (file.exists()) {
            val deleted = file.delete()
            if (deleted) {
                LOG.info("CheckpointStore: deleted checkpoint for task '$taskId'")
            }
            deleted
        } else {
            false
        }
    }

    /**
     * Check if a checkpoint exists for the given task ID.
     */
    fun exists(taskId: String): Boolean {
        return checkpointFile(taskId).exists()
    }

    /**
     * List all checkpoint task IDs.
     */
    fun listCheckpoints(): List<String> {
        if (!baseDir.exists()) return emptyList()
        return baseDir.listFiles()
            ?.filter { it.name.startsWith("checkpoint-") && it.name.endsWith(".json") }
            ?.map { it.name.removePrefix("checkpoint-").removeSuffix(".json") }
            ?: emptyList()
    }

    private fun checkpointFile(taskId: String): File {
        return File(baseDir, "checkpoint-$taskId.json")
    }

    private fun ensureDir() {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }
}
