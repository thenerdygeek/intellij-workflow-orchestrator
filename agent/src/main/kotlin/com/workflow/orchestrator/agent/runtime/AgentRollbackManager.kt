package com.workflow.orchestrator.agent.runtime

import com.intellij.history.LocalHistory
import com.intellij.history.Label
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.util.UUID

/**
 * Manages rollback of agent-made file changes using IntelliJ's LocalHistory API.
 *
 * LocalHistory is superior to git stash because:
 * - Tracks ALL file changes (new files, modifications, deletions)
 * - No VCS dependency — works on any project
 * - Survives IDE restarts (limited time window)
 * - Supports per-file and per-directory rollback
 *
 * Usage:
 *   val manager = AgentRollbackManager(project)
 *   val checkpointId = manager.createCheckpoint("Fix NPE in UserService")
 *   // ... agent modifies files ...
 *   manager.rollbackToCheckpoint(checkpointId)  // reverts all changes
 */
class AgentRollbackManager(private val project: Project) {
    companion object {
        private val LOG = Logger.getInstance(AgentRollbackManager::class.java)
    }

    private val checkpoints = mutableMapOf<String, Label>()
    private val touchedFiles = mutableSetOf<String>()

    /**
     * Create a LocalHistory checkpoint before the agent starts modifying files.
     * Returns a checkpoint ID that can be used to rollback later.
     */
    fun createCheckpoint(description: String): String {
        val id = UUID.randomUUID().toString().take(12)
        try {
            val label = LocalHistory.getInstance().putSystemLabel(
                project, "Agent: $description"
            )
            checkpoints[id] = label
            LOG.info("AgentRollbackManager: created checkpoint $id: $description")
        } catch (e: Exception) {
            LOG.warn("AgentRollbackManager: failed to create checkpoint", e)
        }
        return id
    }

    /** Track a file that the agent has modified (for selective rollback). */
    fun trackFileChange(path: String) {
        touchedFiles.add(path)
    }

    /** Get all files the agent has touched in this session. */
    fun getTouchedFiles(): Set<String> = touchedFiles.toSet()

    /**
     * Rollback ALL changes since the given checkpoint.
     * Reverts the entire project directory to the state at checkpoint time.
     */
    /**
     * Rollback ALL changes since the given checkpoint.
     * Returns null on success, or an error message string on failure.
     */
    fun rollbackToCheckpoint(checkpointId: String): String? {
        val label = checkpoints[checkpointId]
        if (label == null) {
            val available = checkpoints.keys.joinToString(", ").ifEmpty { "none" }
            LOG.warn("AgentRollbackManager: checkpoint '$checkpointId' not found. Available: $available")
            return "Checkpoint not found. Available checkpoints: $available"
        }

        val baseDir = project.basePath?.let {
            LocalFileSystem.getInstance().findFileByPath(it)
        }
        if (baseDir == null) {
            LOG.warn("AgentRollbackManager: project base dir not found")
            return "Project base directory not found"
        }

        return try {
            WriteCommandAction.runWriteCommandAction(project) {
                label.revert(project, baseDir)
            }
            LOG.info("AgentRollbackManager: rolled back to checkpoint $checkpointId (${touchedFiles.size} files affected)")
            null // success
        } catch (e: Exception) {
            LOG.warn("AgentRollbackManager: rollback failed for checkpoint $checkpointId", e)
            "Rollback failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Clear all checkpoints (call when session ends normally). */
    fun clearCheckpoints() {
        checkpoints.clear()
        touchedFiles.clear()
    }

    /** Check if we have any checkpoints available for rollback. */
    fun hasCheckpoints(): Boolean = checkpoints.isNotEmpty()

    /** Get the most recent checkpoint ID. */
    fun latestCheckpointId(): String? = checkpoints.keys.lastOrNull()
}
