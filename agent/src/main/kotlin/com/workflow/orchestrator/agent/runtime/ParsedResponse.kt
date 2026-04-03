package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.ToolCall

/**
 * C5: Classification of an LLM response for structured handling in [SingleAgentSession].
 *
 * Instead of branching on multiple nullable fields inline within processLlmSuccess,
 * this sealed class makes the response shape explicit and enables exhaustive when-matching.
 */
sealed class ParsedResponse {
    abstract val content: String?

    /** LLM returned no content and no tool calls. */
    data class Empty(override val content: String?) : ParsedResponse()

    /**
     * LLM indicated tool_calls (finishReason=tool_calls) but the parsed tool calls
     * were empty or invalid — the arguments were malformed/filtered out.
     */
    data class Malformed(
        override val content: String?,
        val rawToolCalls: List<ToolCall>,
        val finishReason: String?
    ) : ParsedResponse()

    /** LLM returned only text content with no tool calls. */
    data class TextOnly(override val content: String) : ParsedResponse()

    /**
     * LLM returned valid tool calls (possibly with accompanying text content).
     * [completionCall] is non-null when one of the tool calls is attempt_completion.
     */
    data class WithToolCalls(
        override val content: String?,
        val toolCalls: List<ToolCall>,
        val completionCall: ToolCall?
    ) : ParsedResponse()
}

/**
 * Outcome of processing a single iteration — used to signal the ReAct loop
 * whether to continue, complete, or take other action.
 */
enum class LoopAction {
    /** Continue to the next ReAct iteration. */
    CONTINUE,
    /** Task completed normally (via attempt_completion or implicit). */
    COMPLETED,
    /** Task force-completed by gatekeeper after max attempts. */
    FORCE_COMPLETED,
    /** Task cancelled by user. */
    CANCELLED,
    /** Context budget exhausted. */
    BUDGET_EXHAUSTED
}
