package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.tools.CompletionData

sealed class LoopResult {
    /** Files modified during the agent loop (collected from tool artifacts). */
    abstract val filesModified: List<String>
    /** Lines added during the agent loop (from edit/create diffs). */
    abstract val linesAdded: Int
    /** Lines removed during the agent loop (from edit diffs). */
    abstract val linesRemoved: Int

    data class Completed(
        val summary: String,
        val iterations: Int,
        val tokensUsed: Int = 0,
        val completionData: CompletionData? = null,
        /** Cumulative input (prompt) tokens across all API calls. Ported from Cline's tokensIn. */
        val inputTokens: Int = 0,
        /** Cumulative output (completion) tokens across all API calls. Ported from Cline's tokensOut. */
        val outputTokens: Int = 0,
        override val filesModified: List<String> = emptyList(),
        override val linesAdded: Int = 0,
        override val linesRemoved: Int = 0
    ) : LoopResult()

    data class Failed(
        val error: String,
        val iterations: Int = 0,
        val tokensUsed: Int = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        override val filesModified: List<String> = emptyList(),
        override val linesAdded: Int = 0,
        override val linesRemoved: Int = 0
    ) : LoopResult()

    data class Cancelled(
        val iterations: Int,
        val tokensUsed: Int = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        override val filesModified: List<String> = emptyList(),
        override val linesAdded: Int = 0,
        override val linesRemoved: Int = 0
    ) : LoopResult()

    /**
     * Session handoff — the LLM called `new_task` to escape context exhaustion.
     *
     * Ported from Cline's new_task tool: when context is too full for compaction
     * to save enough, the LLM creates a structured summary and hands off to a
     * fresh session. The caller (AgentService) saves the current session, creates
     * a new one, and injects the handoff context as the first user message.
     *
     * @param context structured summary (Current Work, Key Concepts, Files, Problems, Pending)
     */
    data class SessionHandoff(
        val context: String,
        val iterations: Int,
        val tokensUsed: Int = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        override val filesModified: List<String> = emptyList(),
        override val linesAdded: Int = 0,
        override val linesRemoved: Int = 0
    ) : LoopResult()
}

data class ToolCallProgress(
    val toolName: String,
    val args: String = "",
    val result: String = "",
    /** Full tool output content for the expanded UI view. Falls back to [result] (summary) if null. */
    val output: String? = null,
    val durationMs: Long = 0,
    val isError: Boolean = false,
    val toolCallId: String = "",
    /** Unified diff for file edits (edit_file, create_file). Sent to UI for diff display. */
    val editDiff: String? = null
)
