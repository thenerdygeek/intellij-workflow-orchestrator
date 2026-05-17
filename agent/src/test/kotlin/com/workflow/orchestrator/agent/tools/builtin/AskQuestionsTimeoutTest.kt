package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Coverage for feedback.md §13 — the agent-loop per-tool `withTimeoutOrNull(120s)` used
 * to fire before AskQuestionsTool's own 5-minute QUESTION_TIMEOUT_MS, masking the clean
 * "User did not respond within 5 minutes." message with a generic
 * "Tool 'ask_followup_question' timed out after 120s".
 *
 * The fix is `override val timeoutMs = 5 * 60_000L + 30_000L` so the outer guard is
 * longer than the inner wait. We pin that invariant here.
 */
class AskQuestionsTimeoutTest {

    @Test
    @DisplayName("§13 — tool timeout overrides default and beats the inner 5-min wait")
    fun `timeout override is longer than inner wait`() {
        val tool = AskQuestionsTool()
        val innerTimeoutMs = 5L * 60_000L  // matches QUESTION_TIMEOUT_MS in source
        assertTrue(
            tool.timeoutMs > innerTimeoutMs,
            "Outer per-tool timeout must exceed the inner 5-min question wait; " +
                "otherwise users get a generic 120s timeout instead of the specific " +
                "'User did not respond within 5 minutes' message. Got ${tool.timeoutMs}ms."
        )
    }

    @Test
    @DisplayName("§13 — timeout has enough grace for the JCEF bridge confirmation")
    fun `timeout has at least 15s of grace beyond the inner wait`() {
        val tool = AskQuestionsTool()
        val innerTimeoutMs = 5L * 60_000L
        val grace = tool.timeoutMs - innerTimeoutMs
        assertTrue(
            grace >= 15_000L,
            "Grace period should be ≥ 15s to cover JCEF bridge round-trip after Submit; " +
                "got ${grace}ms."
        )
    }
}
