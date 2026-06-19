package com.workflow.orchestrator.agent.tools.cancel

import com.workflow.orchestrator.agent.tools.ToolResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * True iff [e] (or any link in its cause chain) is a [UserStopCancellationException] —
 * i.e. the user clicked Stop on this specific tool call, as opposed to the whole agent
 * turn being cancelled. Carry intent in the cancel cause; never infer it from coroutine
 * liveness (after coroutineScope unwinds, isActive reports only the loop's liveness).
 */
fun isUserStop(e: CancellationException): Boolean =
    generateSequence(e as Throwable?) { it.cause }.any { it is UserStopCancellationException }

/**
 * The tool result fed back to the LLM when a tool is stopped by the user.
 * isError = false: a deliberate user action, not a failure. The loop continues.
 */
fun stoppedByUserResult(toolName: String): ToolResult = ToolResult(
    content = "[Tool '$toolName' was stopped by the user before it finished. " +
        "No result was produced. You may continue with a different approach.]",
    summary = "Stopped by user",
    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
    isError = false,
)
