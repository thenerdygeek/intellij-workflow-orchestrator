package com.workflow.orchestrator.agent.session

import kotlinx.serialization.Serializable

/**
 * Session metadata — persisted as JSON alongside the message history.
 *
 * Ported from Cline's HistoryItem + TaskState persistence:
 * - Cline stores a HistoryItem (id, ts, task, tokensIn, tokensOut, totalCost, cwdOnInit,
 *   modelId) alongside per-task api_conversation_history.json and ui_messages.json.
 * - We collapse these into a single Session object stored at
 *   `{baseDir}/sessions/{sessionId}.json`, with a companion `messages.jsonl` file
 *   for conversation history (matching Cline's saveApiConversationHistory pattern).
 *
 * New fields for checkpoint/resume (matching Cline's TaskState persistence):
 * - [systemPrompt]: the system prompt used, needed to rebuild ContextManager on resume
 * - [planModeEnabled]: whether plan mode was active, affects tool schema on resume
 * - [lastToolCallId]: last completed tool call, the resume checkpoint marker
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
    // Checkpoint/resume fields (ported from Cline's TaskState + task settings persistence)
    val systemPrompt: String = "",
    val planModeEnabled: Boolean = false,
    val lastToolCallId: String? = null
)

@Serializable
enum class SessionStatus { ACTIVE, COMPLETED, FAILED, CANCELLED }
