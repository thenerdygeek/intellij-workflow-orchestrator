package com.workflow.orchestrator.agent.checkpoint

import kotlinx.serialization.Serializable

/**
 * Metadata for one user-message checkpoint. Stored as `meta.json` inside
 * `sessions/{sid}/checkpoints/msg-{ts}/`.
 *
 * @param messageTs ts of the user message that anchors this checkpoint
 * @param userText the user-typed text — restored to the chat input on revert
 * @param createdAt when this checkpoint dir was first written
 * @param createdPaths absolute paths the agent created from scratch during this turn
 *                    (revert means delete; no `files/` entry exists for these)
 * @param touchedPaths absolute paths whose pre-edit bytes were captured under `files/`
 */
@Serializable
data class CheckpointMeta(
    val messageTs: Long,
    val userText: String,
    val createdAt: Long,
    val createdPaths: List<String> = emptyList(),
    val touchedPaths: List<String> = emptyList(),
)

/** Per-file entry in the aggregate diff shown in the bottom bar. */
@Serializable
data class FileChange(
    val path: String,
    val added: Int,
    val removed: Int,
    val status: FileStatus,
)

@Serializable
enum class FileStatus { MODIFIED, CREATED, DELETED }

/** Session-wide aggregate from baseline to current file contents. */
@Serializable
data class AggregateDiff(
    val totalAdded: Int,
    val totalRemoved: Int,
    val files: List<FileChange>,
)

/** Returned by SessionCheckpointStore.revertToMessage. */
data class RevertResult(
    val userText: String,
    val restoredFiles: List<String>,
    val deletedFiles: List<String>,
    val truncatedAtTs: Long,
    /**
     * Paths that were skipped during revert because they failed the E3 path-safety check
     * (out-of-project-root, symlink, or un-canonicalizable). These should be surfaced to
     * the operator rather than silently swallowed.
     */
    val skippedPaths: List<String> = emptyList(),
)
