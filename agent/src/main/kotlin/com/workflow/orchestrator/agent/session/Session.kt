package com.workflow.orchestrator.agent.session

import kotlinx.serialization.Serializable

/**
 * Metadata attached to a session that was initiated by an incoming cross-IDE delegation.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §9.1.
 */
@Serializable
data class DelegationMetadata(
    val delegatorIde: String,
    val delegatorRepo: String,
    val delegatorSessionId: String,
    val startedAt: Long,
    val closedAt: Long? = null,
    val closeReason: String? = null,
)

/**
 * Session metadata — in-memory representation used during task execution.
 *
 * Ported from Cline's HistoryItem + TaskState persistence:
 * - Cline stores a HistoryItem (id, ts, task, tokensIn, tokensOut, totalCost, cwdOnInit,
 *   modelId) alongside per-task api_conversation_history.json and ui_messages.json.
 * - Persistence is handled by MessageStateHandler which maintains sessions.json (global
 *   index) and per-session api_conversation_history.json + ui_messages.json files.
 *
 * Resume-related fields (matching Cline's TaskState persistence):
 * - [systemPrompt]: the system prompt used, needed to rebuild ContextManager on resume
 * - [planModeEnabled]: whether plan mode was active, affects tool schema on resume
 * - [lastToolCallId]: last completed tool call, the resume marker
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/shared/HistoryItem.ts">Cline HistoryItem</a>
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/task/TaskState.ts">Cline TaskState</a>
 */
@Serializable
data class Session(
    val id: String,
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastMessageAt: Long = createdAt,
    val messageCount: Int = 0,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val totalTokens: Int = 0,
    /**
     * Cumulative input (prompt) tokens for this session.
     * Ported from Cline's HistoryItem.tokensIn — tracks total prompt tokens
     * across all API calls in the task.
     */
    val inputTokens: Int = 0,
    /**
     * Cumulative output (completion) tokens for this session.
     * Ported from Cline's HistoryItem.tokensOut — tracks total completion tokens
     * across all API calls in the task.
     */
    val outputTokens: Int = 0,
    // Resume fields (ported from Cline's TaskState + task settings persistence)
    val systemPrompt: String = "",
    val planModeEnabled: Boolean = false,
    val lastToolCallId: String? = null,
    /** Per-session execution metrics. Null for pre-v2 sessions (backward compatible). */
    val metrics: com.workflow.orchestrator.agent.observability.SessionMetrics.MetricsSnapshot? = null,
    /**
     * Non-null when this session was started by an incoming cross-IDE delegation.
     * Nullable default preserves backward compatibility with existing on-disk sessions.
     *
     * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §9.1.
     */
    val delegated: DelegationMetadata? = null,
)

@Serializable
enum class SessionStatus { ACTIVE, COMPLETED, FAILED, CANCELLED }
