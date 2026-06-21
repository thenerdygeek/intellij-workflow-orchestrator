package com.workflow.orchestrator.agent.tools.background

import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One in-flight background tool execution. The job lives in the BackgroundToolExecutor's scope (NOT the
 * loop turn). [deferred] carries the PROCESSED result (grep/spill/truncate already applied) so both the
 * inline-await path and the background-delivery path see identical content. [detachSignal] is completed by
 * the UI "Move to background" button to release the loop's inline await; [backgrounded] flips true when the
 * agent launched it backgrounded up-front OR the user detached it.
 */
class BackgroundToolHandle(
    val toolCallId: String,
    val sessionId: String,
    val toolName: String,
    val params: JsonObject,
    val tool: AgentTool,
    val startedAt: Long,
) {
    lateinit var job: Job
    val deferred = CompletableDeferred<ToolResult>()
    val detachSignal = CompletableDeferred<Unit>()
    @Volatile var backgrounded: Boolean = false

    /**
     * Single-owner delivery guard. Exactly one of {loop consumes inline, executor delivers to queue} may
     * "claim" this result — closes the detach-vs-completion TOCTOU where both fire at once. The loop's
     * inline `select` and the executor's `invokeOnCompletion` both CAS this; the loser backs off.
     */
    private val delivered = AtomicBoolean(false)
    fun claimDelivery(): Boolean = delivered.compareAndSet(false, true)
}
