package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.security.CredentialRedactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Structured event types for the agent audit trail.
 */
enum class AgentEventType {
    SESSION_STARTED, SESSION_COMPLETED, SESSION_FAILED,
    TOOL_CALLED, TOOL_SUCCEEDED, TOOL_FAILED,
    EDIT_APPLIED, EDIT_REJECTED_SYNTAX,
    COMPRESSION_TRIGGERED, ESCALATION_TRIGGERED,
    LOOP_DETECTED, APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_DENIED,
    RATE_LIMITED_RETRY, CONTEXT_EXCEEDED_RETRY, SNAPSHOT_CREATED,
    // Worker delegation events
    WORKER_SPAWNED, WORKER_COMPLETED, WORKER_FAILED, WORKER_TIMED_OUT, WORKER_ROLLED_BACK
}

/**
 * A single structured event in the agent audit trail.
 */
@Serializable
data class AgentEvent(
    val timestamp: Long,
    val sessionId: String,
    val type: AgentEventType,
    val detail: String
)

/**
 * Append-only structured event log for agent sessions.
 *
 * Each session gets a JSONL file at `{sessionDir}/events.jsonl`.
 * Events are appended one per line for efficient streaming reads.
 *
 * This provides the enterprise audit trail: what the agent did, which tools it called,
 * which edits were applied or rejected, and why the session ended.
 */
class AgentEventLog(
    private val sessionId: String,
    private val sessionDir: File
) {
    companion object {
        private val LOG = Logger.getInstance(AgentEventLog::class.java)
        private val json = Json { encodeDefaults = true }
    }

    private val events = mutableListOf<AgentEvent>()
    private val logFile: File by lazy {
        sessionDir.mkdirs()
        File(sessionDir, "events.jsonl")
    }

    /**
     * Log an event. Appends to both in-memory list and disk file.
     */
    fun log(type: AgentEventType, detail: String = "") {
        val sanitizedDetail = CredentialRedactor.redact(detail)
        val event = AgentEvent(
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            type = type,
            detail = sanitizedDetail
        )
        events.add(event)

        // Persist to JSONL (append-only, best-effort)
        try {
            logFile.appendText(json.encodeToString(event) + "\n")
        } catch (e: Exception) {
            LOG.warn("AgentEventLog: failed to persist event $type", e)
        }
    }

    /**
     * Get all events recorded in this session (in-memory).
     */
    fun getEvents(): List<AgentEvent> = events.toList()

    /**
     * Get events of a specific type.
     */
    fun getEvents(type: AgentEventType): List<AgentEvent> = events.filter { it.type == type }
}
