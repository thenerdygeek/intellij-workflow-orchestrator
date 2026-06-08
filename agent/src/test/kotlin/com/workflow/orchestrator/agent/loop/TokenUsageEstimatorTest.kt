package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.dto.UsageInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Behavioral characterization of [TokenUsageEstimator] — the per-turn (promptTokens,
 * completionTokens) the loop reports to the UI (context-usage bar + subagent token counts).
 *
 * BUG it fixes: `AgentLoop` previously updated token tracking ONLY inside `response.usage?.let{}`.
 * Sourcegraph streaming frequently returns `usage == null`, so the context bar froze at the last
 * usage-bearing value and subagent token counts stayed 0. When usage is absent we must fall back
 * to an estimate (prompt from the current context, completion from the response text) so both keep
 * updating.
 */
class TokenUsageEstimatorTest {

    @Test
    fun `passes through real API usage when present`() {
        val (prompt, completion) = TokenUsageEstimator.reportFor(
            usage = UsageInfo(promptTokens = 1234, completionTokens = 56, totalTokens = 1290),
            promptEstimate = 9999,
            responseText = "ignored when usage is present",
            estimate = { 9999 },
        )
        assertEquals(1234, prompt, "real promptTokens must win over the estimate")
        assertEquals(56, completion, "real completionTokens must win over the estimate")
    }

    @Test
    fun `falls back to estimates when usage is null`() {
        val (prompt, completion) = TokenUsageEstimator.reportFor(
            usage = null,
            promptEstimate = 4200,
            responseText = "the assistant response text",
            estimate = { text -> text.length },
        )
        assertEquals(4200, prompt, "prompt must use the context estimate when usage is null")
        assertEquals(
            "the assistant response text".length,
            completion,
            "completion must be estimated from the response text when usage is null",
        )
    }
}
