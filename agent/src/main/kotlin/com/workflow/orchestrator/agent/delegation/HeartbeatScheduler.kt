package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.core.delegation.DelegationMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-session coroutine that emits [DelegationMessage.Heartbeat] every
 * [intervalMillis] (default 60 s) while the session is in a non-terminal state.
 * Started by [DelegationInboundService] when a session channel is registered;
 * stopped via [stop] when the session transitions to a terminal Result.
 *
 * Plan 3 spec §5.4.
 */
class HeartbeatScheduler(
    private val sessionId: String,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val sendMessage: suspend (DelegationMessage) -> Unit,
) {
    private val jobRef = AtomicReference<Job?>(null)

    fun start() {
        if (jobRef.get() != null) return
        val job = scope.launch {
            while (isActive) {
                delay(intervalMillis)
                try {
                    sendMessage(DelegationMessage.Heartbeat(sessionId = sessionId))
                } catch (e: Exception) {
                    break
                }
            }
        }
        if (!jobRef.compareAndSet(null, job)) {
            job.cancel()
        }
    }

    fun stop() {
        jobRef.getAndSet(null)?.cancel()
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS: Long = 60_000L
    }
}
