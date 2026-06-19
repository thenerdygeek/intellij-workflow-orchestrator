package com.workflow.orchestrator.agent.tools.cancel

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sentinel cancellation cause marking a per-tool-call coroutine that the USER
 * explicitly stopped (Stop button), as opposed to the whole agent turn being
 * cancelled. The funnel in AgentLoop discriminates on this cause to decide whether
 * to return a "Stopped by user" result (and continue the loop) or propagate.
 */
class UserStopCancellationException(toolCallId: String) :
    CancellationException("Tool call $toolCallId stopped by user")

/**
 * Global registry of in-flight tool-call coroutine jobs, keyed by toolCallId.
 * Sibling to ProcessRegistry. The UI Stop button cancels a specific running tool's
 * coroutine from the EDT/JCEF bridge thread while register/unregister run on
 * Dispatchers.IO — hence ConcurrentHashMap.
 */
object ToolCancellationRegistry {
    private val active = ConcurrentHashMap<String, Job>()

    fun register(toolCallId: String, job: Job) {
        active[toolCallId] = job
    }

    fun unregister(toolCallId: String) {
        active.remove(toolCallId)
    }

    /**
     * Cancels the per-call job with a [UserStopCancellationException].
     * @return true iff a job was registered for [toolCallId] and was cancelled.
     */
    fun cancel(toolCallId: String): Boolean {
        val job = active.remove(toolCallId) ?: return false
        job.cancel(UserStopCancellationException(toolCallId))
        return true
    }

    fun isActive(toolCallId: String): Boolean = active.containsKey(toolCallId)
}
