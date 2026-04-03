package com.workflow.orchestrator.agent.runtime

import com.intellij.history.LocalHistory
import com.intellij.history.Label
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.util.UUID

/**
 * Manages rollback of agent-made file changes using IntelliJ's LocalHistory API,
 * with git per-file fallback when LocalHistory fails.
 *
 * LocalHistory is superior to git stash because:
 * - Tracks ALL file changes (new files, modifications, deletions)
 * - No VCS dependency — works on any project
 * - Survives IDE restarts (limited time window)
 * - Supports per-file and per-directory rollback
 *
 * Rollback sequence: try LocalHistory first -> on exception, fall back to git per-file.
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
    private val createdFiles = mutableSetOf<String>()

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

    /** Track a file that the agent has created (for deletion on rollback). */
    fun trackFileCreation(path: String) {
        createdFiles.add(path)
        touchedFiles.add(path)
    }

    /** Get all files the agent has touched in this session. */
    fun getTouchedFiles(): Set<String> = touchedFiles.toSet()

    /** Get all files the agent has created in this session. */
    fun getCreatedFiles(): Set<String> = createdFiles.toSet()

    /**
     * Rollback ALL changes since the given checkpoint.
     * Tries LocalHistory first; falls back to git per-file revert on failure.
     *
     * Returns a [RollbackResult] with details about what happened.
     */
    fun rollbackToCheckpoint(checkpointId: String): RollbackResult {
        val label = checkpoints[checkpointId]
        if (label == null) {
            val available = checkpoints.keys.joinToString(", ").ifEmpty { "none" }
            LOG.warn("AgentRollbackManager: checkpoint '$checkpointId' not found. Available: $available")
            return RollbackResult(
                success = false,
                mechanism = RollbackMechanism.LOCAL_HISTORY,
                affectedFiles = emptyList(),
                error = "Checkpoint not found. Available checkpoints: $available"
            )
        }

        val baseDir = project.basePath?.let {
            LocalFileSystem.getInstance().findFileByPath(it)
        }
        if (baseDir == null) {
            LOG.warn("AgentRollbackManager: project base dir not found")
            return RollbackResult(
                success = false,
                mechanism = RollbackMechanism.LOCAL_HISTORY,
                affectedFiles = emptyList(),
                error = "Project base directory not found"
            )
        }

        // Try LocalHistory first
        return try {
            WriteCommandAction.runWriteCommandAction(project) {
                label.revert(project, baseDir)
            }
            val affected = touchedFiles.toList()
            LOG.info("AgentRollbackManager: rolled back to checkpoint $checkpointId via LocalHistory (${affected.size} files affected)")
            refreshVfs()
            RollbackResult(
                success = true,
                mechanism = RollbackMechanism.LOCAL_HISTORY,
                affectedFiles = affected
            )
        } catch (e: Exception) {
            LOG.warn("AgentRollbackManager: LocalHistory rollback failed for checkpoint $checkpointId, falling back to git", e)
            gitFallbackRevert()
        }
    }

    /**
     * Rollback a single file to its state at the given checkpoint (or HEAD if no checkpoint).
     * Uses git checkout for per-file revert. For created files, deletes instead.
     *
     * Returns a [RollbackResult] with details about what happened.
     */
    fun rollbackFile(filePath: String, checkpointId: String? = null): RollbackResult {
        val basePath = project.basePath
        if (basePath == null) {
            return RollbackResult(
                success = false,
                mechanism = RollbackMechanism.GIT_FALLBACK,
                affectedFiles = emptyList(),
                error = "Project base directory not found"
            )
        }

        // If the file was created by the agent, just delete it
        if (filePath in createdFiles) {
            return try {
                val file = File(filePath)
                if (file.exists()) {
                    file.delete()
                }
                createdFiles.remove(filePath)
                touchedFiles.remove(filePath)
                refreshVfs()
                RollbackResult(
                    success = true,
                    mechanism = RollbackMechanism.GIT_FALLBACK,
                    affectedFiles = listOf(filePath)
                )
            } catch (e: Exception) {
                LOG.warn("AgentRollbackManager: failed to delete created file $filePath", e)
                RollbackResult(
                    success = false,
                    mechanism = RollbackMechanism.GIT_FALLBACK,
                    affectedFiles = emptyList(),
                    failedFiles = listOf(filePath),
                    error = "Failed to delete created file: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }

        // For modified files, use git checkout
        return try {
            val ref = if (checkpointId != null) checkpointId else "HEAD"
            val process = ProcessBuilder("git", "checkout", ref, "--", filePath)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                touchedFiles.remove(filePath)
                refreshVfs()
                RollbackResult(
                    success = true,
                    mechanism = RollbackMechanism.GIT_FALLBACK,
                    affectedFiles = listOf(filePath)
                )
            } else {
                LOG.warn("AgentRollbackManager: git checkout failed for $filePath: $output")
                RollbackResult(
                    success = false,
                    mechanism = RollbackMechanism.GIT_FALLBACK,
                    affectedFiles = emptyList(),
                    failedFiles = listOf(filePath),
                    error = "git checkout failed (exit $exitCode): ${output.take(200)}"
                )
            }
        } catch (e: Exception) {
            LOG.warn("AgentRollbackManager: git checkout failed for $filePath", e)
            RollbackResult(
                success = false,
                mechanism = RollbackMechanism.GIT_FALLBACK,
                affectedFiles = emptyList(),
                failedFiles = listOf(filePath),
                error = "git checkout failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Git-based fallback revert: iterates all touched files, runs
     * `git checkout HEAD -- <file>` for modified files, deletes created files.
     */
    private fun gitFallbackRevert(): RollbackResult {
        val basePath = project.basePath
        if (basePath == null) {
            return RollbackResult(
                success = false,
                mechanism = RollbackMechanism.GIT_FALLBACK,
                affectedFiles = emptyList(),
                error = "Project base directory not found"
            )
        }

        val affected = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // Delete created files first
        for (path in createdFiles.toSet()) {
            try {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
                affected.add(path)
            } catch (e: Exception) {
                LOG.warn("AgentRollbackManager: failed to delete created file $path", e)
                failed.add(path)
                errors.add("$path: ${e.message}")
            }
        }

        // git checkout HEAD -- <file> for modified files
        val modifiedFiles = touchedFiles - createdFiles
        for (path in modifiedFiles) {
            try {
                val process = ProcessBuilder("git", "checkout", "HEAD", "--", path)
                    .directory(File(basePath))
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    affected.add(path)
                } else {
                    LOG.warn("AgentRollbackManager: git checkout failed for $path: $output")
                    failed.add(path)
                    errors.add("$path: git exit $exitCode")
                }
            } catch (e: Exception) {
                LOG.warn("AgentRollbackManager: git checkout failed for $path", e)
                failed.add(path)
                errors.add("$path: ${e.message}")
            }
        }

        refreshVfs()

        val success = failed.isEmpty()
        val errorMsg = if (errors.isEmpty()) null else errors.joinToString("; ")
        LOG.info("AgentRollbackManager: git fallback revert completed — ${affected.size} reverted, ${failed.size} failed")

        return RollbackResult(
            success = success,
            mechanism = RollbackMechanism.GIT_FALLBACK,
            affectedFiles = affected,
            failedFiles = failed,
            error = errorMsg
        )
    }

    /** Refresh IntelliJ VFS after revert so the IDE sees the restored file contents. */
    private fun refreshVfs() {
        try {
            val baseDir = project.basePath?.let {
                LocalFileSystem.getInstance().findFileByPath(it)
            }
            baseDir?.refresh(true, true)
        } catch (e: Exception) {
            LOG.warn("AgentRollbackManager: VFS refresh failed", e)
        }
    }

    /** Clear all checkpoints (call when session ends normally). */
    fun clearCheckpoints() {
        checkpoints.clear()
        touchedFiles.clear()
        createdFiles.clear()
    }

    /** Check if we have any checkpoints available for rollback. */
    fun hasCheckpoints(): Boolean = checkpoints.isNotEmpty()

    /** Get the most recent checkpoint ID. */
    fun latestCheckpointId(): String? = checkpoints.keys.lastOrNull()
}
