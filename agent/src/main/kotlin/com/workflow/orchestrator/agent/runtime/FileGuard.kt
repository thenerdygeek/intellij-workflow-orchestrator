package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Prevents concurrent edit conflicts by tracking which files the agent is editing.
 * Provides snapshot/rollback using git stash for safe editing.
 */
class FileGuard {
    private val lockedFiles = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private val LOG = Logger.getInstance(FileGuard::class.java)
    }

    /**
     * Lock a file path, indicating the agent is editing it.
     * @return true if the lock was acquired, false if already locked
     */
    fun lockFile(path: String): Boolean {
        val normalized = normalizePath(path)
        val acquired = lockedFiles.add(normalized)
        if (acquired) {
            LOG.info("FileGuard: locked $normalized")
        } else {
            LOG.warn("FileGuard: attempted to lock already-locked file $normalized")
        }
        return acquired
    }

    /**
     * Unlock a file path, indicating the agent is done editing it.
     * @return true if the file was locked and is now unlocked, false if it wasn't locked
     */
    fun unlockFile(path: String): Boolean {
        val normalized = normalizePath(path)
        val released = lockedFiles.remove(normalized)
        if (released) {
            LOG.info("FileGuard: unlocked $normalized")
        }
        return released
    }

    /**
     * Check if a file is currently locked for editing.
     */
    fun isLocked(path: String): Boolean {
        return normalizePath(path) in lockedFiles
    }

    /**
     * Get all currently locked file paths.
     */
    fun getLockedFiles(): Set<String> = lockedFiles.toSet()

    /**
     * Create a git stash snapshot of the specified files before editing.
     * Uses `git stash create` which creates a stash entry without modifying the working tree.
     *
     * @param project The IntelliJ project (used to determine project base path)
     * @param paths The file paths to include in the snapshot
     * @return The stash reference (SHA), or null if snapshot failed or no changes to stash
     */
    fun snapshotFiles(project: Project, paths: List<String>): String? {
        val basePath = project.basePath ?: return null
        return try {
            val process = ProcessBuilder("git", "stash", "create")
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.isNotEmpty()) {
                LOG.info("FileGuard: created snapshot $output for ${paths.size} files")
                output
            } else {
                LOG.info("FileGuard: no changes to snapshot (exit=$exitCode)")
                null
            }
        } catch (e: Exception) {
            LOG.warn("FileGuard: failed to create snapshot", e)
            null
        }
    }

    /**
     * Rollback files to a previous stash snapshot.
     * Applies the stash reference to restore files.
     *
     * @param project The IntelliJ project
     * @param stashRef The stash reference (SHA) from snapshotFiles
     * @return true if rollback succeeded
     */
    fun rollback(project: Project, stashRef: String): Boolean {
        val basePath = project.basePath ?: return false
        return try {
            val process = ProcessBuilder("git", "stash", "apply", stashRef)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                LOG.info("FileGuard: rolled back to snapshot $stashRef")
                true
            } else {
                LOG.warn("FileGuard: rollback failed (exit=$exitCode): $output")
                false
            }
        } catch (e: Exception) {
            LOG.warn("FileGuard: failed to rollback to $stashRef", e)
            false
        }
    }

    /**
     * Clear all locks. Used during cleanup/shutdown.
     */
    fun clearAll() {
        lockedFiles.clear()
        LOG.info("FileGuard: cleared all locks")
    }

    private fun normalizePath(path: String): String {
        return File(path).canonicalPath
    }
}
