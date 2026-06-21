package com.workflow.orchestrator.agent.tools.background

import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.cancel.ToolCancellationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs eligible tools as detached jobs in a per-session-set, AgentService-owned SupervisorJob scope
 * (NOT a child of the loop turn). The loop normally awaits [BackgroundToolHandle.deferred]; "background"
 * = it stops awaiting. On job completion the result is delivered via [onDeliver] IFF the handle was
 * backgrounded (agent-initiated up-front or user-detached). Inline completions deliver nothing — the
 * awaiting loop owns that result. Errors are converted to an error [ToolResult] inside the job so the
 * awaiting `select`/`onAwait` never rethrows (single error site).
 */
class BackgroundToolExecutor(
    parentScope: CoroutineScope,
    private val registry: BackgroundToolRegistry,
    private val onDeliver: (BackgroundToolHandle, ToolResult) -> Unit,
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(handle: BackgroundToolHandle, block: suspend () -> ToolResult) {
        // LAZY so the body cannot run — and invokeOnCompletion cannot fire registry.remove — until the
        // handle is fully published (job assigned, registered, added). This closes two cross-thread races:
        // (a) cancelAllForSession on the EDT touching `handle.job` before it is assigned (lateinit crash),
        // and (b) a fast-completing body's remove() racing ahead of registry.add() (registry leak).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                block()
            } catch (e: CancellationException) {
                handle.deferred.complete(stoppedResult(handle.toolName))
                throw e   // genuine cancel: complete the await with a stopped result, then propagate
            } catch (e: Exception) {
                errorResult(handle.toolName, e.message)
            }
            handle.deferred.complete(result)
        }
        handle.job = job
        ToolCancellationRegistry.register(handle.toolCallId, job)
        job.invokeOnCompletion {
            ToolCancellationRegistry.unregister(handle.toolCallId)
            registry.remove(handle)
            // Deliver iff this completion (not the loop's inline consume) wins the single delivery claim —
            // closes the detach-vs-completion double-delivery TOCTOU.
            if (handle.backgrounded && handle.claimDelivery()) {
                val delivered = if (handle.deferred.isCompleted) handle.deferred.getCompleted()
                                else stoppedResult(handle.toolName)
                onDeliver(handle, delivered)
            }
        }
        registry.add(handle)
        job.start()
    }

    /** User "Move to background": detach the loop's inline await; the job runs on and delivers later. */
    fun detach(toolCallId: String): Boolean {
        val handle = registry.findByToolCallId(toolCallId) ?: return false
        handle.backgrounded = true
        return handle.detachSignal.complete(Unit)
    }

    /** Loop-cancel cleanup for an un-detached inline tool: cancel its job (no delivery — backgrounded stays false). */
    fun cancelOne(toolCallId: String) {
        registry.findByToolCallId(toolCallId)?.job?.cancel(CancellationException("inline tool cancelled with loop"))
    }

    /** New chat / global stop: cancel every in-flight background job for [sessionId]. */
    fun cancelAllForSession(sessionId: String) {
        registry.list(sessionId).forEach { it.job.cancel(CancellationException("session reset")) }
    }

    fun dispose() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun stoppedResult(toolName: String) = ToolResult(
        content = "Background tool '$toolName' was stopped by the user.",
        summary = "Stopped by user",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )

    private fun errorResult(toolName: String, message: String?) = ToolResult(
        content = "Error: background tool '$toolName' threw: ${message ?: "unknown error"}",
        summary = "Error: ${message ?: "unknown"}",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )
}
