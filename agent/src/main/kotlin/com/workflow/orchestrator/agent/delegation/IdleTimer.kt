package com.workflow.orchestrator.agent.delegation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-channel coroutine on the outbound side that periodically checks
 * `lastSeenAt[handleId]` and invokes [onTimeout] when the gap exceeds the
 * timeout returned by [timeoutMillisProvider]. The provider is re-read on
 * every tick so PluginSettings changes take effect for the next check on
 * already-open channels (spec §3.3). A provider return of <= 0 disables
 * the check for that tick.
 *
 * Plan 3 spec §5.4.
 */
class IdleTimer(
    private val handleId: String,
    private val scope: CoroutineScope,
    private val checkIntervalMillis: Long,
    private val timeoutMillisProvider: () -> Long,
    private val clock: Clock,
    private val lastSeenAtProvider: () -> Long?,
    private val onTimeout: suspend () -> Unit,
) {
    private val jobRef = AtomicReference<Job?>(null)

    fun start() {
        if (jobRef.get() != null) return
        val job = scope.launch {
            while (isActive) {
                delay(checkIntervalMillis)
                val timeoutMillis = timeoutMillisProvider()
                if (timeoutMillis <= 0L) continue
                val seen = lastSeenAtProvider() ?: continue
                val gap = clock.nowMillis() - seen
                if (gap > timeoutMillis) {
                    onTimeout()
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
}
