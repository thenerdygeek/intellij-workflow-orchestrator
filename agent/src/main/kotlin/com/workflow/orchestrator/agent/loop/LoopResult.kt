package com.workflow.orchestrator.agent.loop

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
        val verifyCommand: String? = null,
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
     * Plan presented to user for review — loop pauses until user approves or gives feedback.
     *
     * Ported from Cline's plan mode flow:
     * - When plan_mode_respond is called with needs_more_exploration=false, the loop
     *   returns this result so the UI can show the plan and collect feedback.
     * - The plan text is displayed in the chat UI.
     * - On approval, the caller switches to act mode and re-runs the loop.
     * - On feedback, the caller re-runs the loop in plan mode with the feedback.
     */
    data class PlanPresented(
        val plan: String,
        val needsMoreExploration: Boolean = false,
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
    val durationMs: Long = 0,
    val isError: Boolean = false,
    val toolCallId: String = "",
    /** Unified diff for file edits (edit_file, create_file). Sent to UI for diff display. */
    val editDiff: String? = null
)
