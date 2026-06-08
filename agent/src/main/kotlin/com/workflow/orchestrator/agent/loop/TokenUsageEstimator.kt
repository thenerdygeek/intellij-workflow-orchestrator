package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.dto.UsageInfo

/**
 * Decides the (promptTokens, completionTokens) the loop reports to the UI for one turn — the
 * context-usage bar's "used" value and the subagent card's token count.
 *
 * `AgentLoop` previously updated token tracking ONLY inside `response.usage?.let { }`. Sourcegraph
 * streaming frequently returns `usage == null`, so the context bar froze at the last usage-bearing
 * value (its `lastPromptTokens` cache never refreshed) and short-lived subagents that never got a
 * usage-bearing turn showed 0 tokens. When usage is absent we fall back to an estimate: prompt
 * tokens from the current context size, completion tokens from the response text.
 */
object TokenUsageEstimator {

    /**
     * @param usage the API-reported usage for the turn, or null when the provider omitted it.
     * @param promptEstimate estimate of the current context size (e.g. `ContextManager.tokenEstimate()`).
     * @param responseText the assistant's response text for this turn.
     * @param estimate token-estimation function applied to [responseText] (e.g. `estimateTokens`).
     * @return (promptTokens, completionTokens) to report — real usage when present, else estimates.
     */
    fun reportFor(
        usage: UsageInfo?,
        promptEstimate: Int,
        responseText: String,
        estimate: (String) -> Int,
    ): Pair<Int, Int> =
        if (usage != null) {
            usage.promptTokens to usage.completionTokens
        } else {
            promptEstimate to estimate(responseText)
        }
}
