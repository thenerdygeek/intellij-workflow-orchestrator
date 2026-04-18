package com.workflow.orchestrator.agent.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Describes what kind of completion signal the LLM is emitting. */
@Serializable
enum class CompletionKind {
    @SerialName("done") DONE,
    @SerialName("review") REVIEW,
    @SerialName("heads_up") HEADS_UP,
}

/**
 * Structured payload carried by [ToolResult] when [ToolResultType.Completion] is signalled.
 * Travels end-to-end from [AttemptCompletionTool] through AgentLoop → LoopResult → UiMessage →
 * JCEF bridge → React, giving every downstream layer a stable, typed shape.
 *
 * @param kind       Classification of this completion (done / review / heads_up).
 * @param result     Short summary card text shown to the user.
 * @param verifyHow  Optional CLI command to demonstrate the result to the user.
 * @param discovery  Optional notes surfaced only when [kind] is [CompletionKind.HEADS_UP].
 */
@Serializable
data class CompletionData(
    val kind: CompletionKind,
    val result: String,
    val verifyHow: String? = null,
    val discovery: String? = null,
)
