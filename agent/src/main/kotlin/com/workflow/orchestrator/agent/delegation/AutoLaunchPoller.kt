package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.core.delegation.DelegationMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

sealed class AutoLaunchOutcome {
    object Ready : AutoLaunchOutcome()
    object TimedOut : AutoLaunchOutcome()
}

/**
 * Polls [socketPath] every [pollIntervalMillis] for up to [timeoutMillis]. Returns
 * [AutoLaunchOutcome.Ready] on the first PONG, [AutoLaunchOutcome.TimedOut] on
 * elapsed deadline.
 *
 * Plan 3 spec §5.6.
 */
class AutoLaunchPoller(
    private val socketPath: Path,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 500L,
    private val timeoutMillis: Long = 90_000L,
    private val pingFn: suspend (Path) -> DelegationMessage.Pong?,
) {
    suspend fun awaitOrTimeout(): AutoLaunchOutcome {
        val outcome = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val pong = pingFn(socketPath)
                if (pong != null) return@withTimeoutOrNull AutoLaunchOutcome.Ready
                delay(pollIntervalMillis)
            }
            @Suppress("UNREACHABLE_CODE")
            AutoLaunchOutcome.Ready
        }
        return outcome ?: AutoLaunchOutcome.TimedOut
    }
}
