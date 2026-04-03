package com.workflow.orchestrator.agent.runtime

import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class OwnershipRecord(
    val agentId: String,
    val workerType: WorkerType,
    val claimedAt: Long = System.currentTimeMillis()
)

enum class ClaimResult {
    GRANTED,
    DENIED
}

data class ClaimResponse(
    val result: ClaimResult,
    val ownerAgentId: String? = null
)

/**
 * Per-session registry tracking which worker agent owns (is actively editing) each file.
 *
 * Write operations (`edit_file`, `create_file`) must acquire ownership before proceeding.
 * Read operations (`read_file`) warn if the file is owned by another worker.
 *
 * Thread-safe via [ConcurrentHashMap]. Uses canonical paths to prevent aliasing.
 * Ownership is whole-file granularity — no line-level locking.
 */
class FileOwnershipRegistry {
    private val fileOwners = ConcurrentHashMap<String, OwnershipRecord>()

    /**
     * Attempt to claim ownership of a file. Idempotent — claiming a file you already own returns GRANTED.
     * Returns DENIED with the owning agentId if the file is owned by another agent.
     *
     * Uses [ConcurrentHashMap.compute] to eliminate the TOCTOU race between reading
     * the current owner and writing the new record.
     */
    fun claim(filePath: String, agentId: String, workerType: WorkerType): ClaimResponse {
        val canonical = canonicalize(filePath)
        val newRecord = OwnershipRecord(agentId, workerType)
        var denied: String? = null
        fileOwners.compute(canonical) { _, existing ->
            when {
                existing == null -> newRecord
                existing.agentId == agentId -> newRecord
                else -> { denied = existing.agentId; existing }
            }
        }
        return if (denied != null) ClaimResponse(ClaimResult.DENIED, denied)
        else ClaimResponse(ClaimResult.GRANTED)
    }

    /**
     * Release ownership of a file. Only the owning agent can release.
     * Returns false if the file is not owned by this agent.
     */
    fun release(filePath: String, agentId: String): Boolean {
        val canonical = canonicalize(filePath)
        var released = false
        fileOwners.computeIfPresent(canonical) { _, existing ->
            if (existing.agentId == agentId) { released = true; null }
            else existing
        }
        return released
    }

    /**
     * Release all files owned by the given agent. Called on worker completion/failure/kill.
     * Returns the number of files released.
     */
    fun releaseAll(agentId: String): Int {
        val toRemove = fileOwners.entries.filter { it.value.agentId == agentId }.map { it.key }
        toRemove.forEach { key -> fileOwners.computeIfPresent(key) { _, v -> if (v.agentId == agentId) null else v } }
        return toRemove.size
    }

    /** Get the ownership record for a file, or null if unclaimed. */
    fun getOwner(filePath: String): OwnershipRecord? {
        return fileOwners[canonicalize(filePath)]
    }

    /** Check if a file is owned by a different agent than the given one. */
    fun isOwnedByOther(filePath: String, agentId: String): Boolean {
        val owner = getOwner(filePath) ?: return false
        return owner.agentId != agentId
    }

    /** List all files owned by the given agent. */
    fun listOwnedFiles(agentId: String): List<String> {
        return fileOwners.entries.filter { it.value.agentId == agentId }.map { it.key }
    }

    private fun canonicalize(filePath: String): String {
        return try {
            File(filePath).canonicalPath
        } catch (_: Exception) {
            filePath
        }
    }
}
