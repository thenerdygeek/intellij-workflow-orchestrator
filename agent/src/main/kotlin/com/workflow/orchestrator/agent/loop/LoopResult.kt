package com.workflow.orchestrator.agent.loop

sealed class LoopResult {
    data class Completed(
        val summary: String,
        val iterations: Int,
        val tokensUsed: Int = 0,
        val verifyCommand: String? = null,
        /** Cumulative input (prompt) tokens across all API calls. Ported from Cline's tokensIn. */
        val inputTokens: Int = 0,
        /** Cumulative output (completion) tokens across all API calls. Ported from Cline's tokensOut. */
        val outputTokens: Int = 0
    ) : LoopResult()

    data class Failed(
        val error: String,
        val iterations: Int = 0,
        val tokensUsed: Int = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0
    ) : LoopResult()

    data class Cancelled(
        val iterations: Int,
        val tokensUsed: Int = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0
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
        val outputTokens: Int = 0
    ) : LoopResult()
}

data class ToolCallProgress(
    val toolName: String,
    val args: String = "",
    val result: String = "",
    val durationMs: Long = 0,
    val isError: Boolean = false,
    val toolCallId: String = ""
)
