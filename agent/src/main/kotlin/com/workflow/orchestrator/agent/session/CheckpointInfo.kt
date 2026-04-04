package com.workflow.orchestrator.agent.session

import kotlinx.serialization.Serializable

/**
 * Metadata for a conversation checkpoint.
 *
 * Ported from Cline's checkpoint-based conversation reversion:
 * checkpoints are snapshots of the conversation at meaningful points
 * (after write operations, at regular intervals) that allow reverting
 * the conversation to an earlier state.
 *
 * @param id unique checkpoint identifier
 * @param createdAt timestamp when the checkpoint was created
 * @param messageCount number of messages in the conversation at this point
 * @param description human-readable description (e.g., "After editing UserService.kt")
 */
@Serializable
data class CheckpointInfo(
    val id: String,
    val createdAt: Long,
    val messageCount: Int,
    val description: String = ""
)
