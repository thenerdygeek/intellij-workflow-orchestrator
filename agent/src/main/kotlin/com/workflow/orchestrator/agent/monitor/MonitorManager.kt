package com.workflow.orchestrator.agent.monitor

import java.util.concurrent.ConcurrentHashMap

/** Outcome the injected idle-waker reports back, mirroring IdleWakeRoute semantics. */
enum class WakeOutcome { WOKE, SKIPPED, DEFERRED }

/** Maps an [com.workflow.orchestrator.agent.tools.background.IdleWakeRoute] onto the manager's [WakeOutcome]. */
fun wakeOutcomeFor(route: com.workflow.orchestrator.agent.tools.background.IdleWakeRoute): WakeOutcome =
    when (route) {
        com.workflow.orchestrator.agent.tools.background.IdleWakeRoute.WAKE -> WakeOutcome.WOKE
        com.workflow.orchestrator.agent.tools.background.IdleWakeRoute.SKIP_GUARD -> WakeOutcome.SKIPPED
        else -> WakeOutcome.DEFERRED
    }

data class MonitorConfig(
    val coalesceWindowMs: Long = 2_000,
    val wakeBudgetPerMonitor: Int = 3,
    val floodThresholdPerMin: Int = 20,
)

/**
 * Pure coordinator for monitor event delivery (design spec §4). All side effects are
 * injected so this class is unit-testable without the IDE:
 *  - [clock] supplies the current epoch millis.
 *  - [isLoopLive] reports whether the agent loop is currently running.
 *  - [deliverToLoop] hands a coalesced notification to the live loop (steering).
 *  - [wakeIdle] hands a notification to the idle-wake path; returns the [WakeOutcome].
 *
 * Threading: [onEvent] may be called from any source thread; the whole instance is the
 * lock (methods are @Synchronized). [flushDue] is driven by a single timer (wired in the
 * AgentService task) and performs the actual routing.
 */
class MonitorManager(
    private val config: MonitorConfig,
    private val clock: () -> Long,
    private val isLoopLive: () -> Boolean,
    private val deliverToLoop: (String) -> Unit,
    private val wakeIdle: (String) -> WakeOutcome,
) {
    private data class Pending(val lines: MutableList<MonitorEvent> = mutableListOf(), var firstAt: Long = 0)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val wakeBudget = ConcurrentHashMap<String, Int>()
    private val dormant = ConcurrentHashMap.newKeySet<String>()
    private val autoStopped = ConcurrentHashMap.newKeySet<String>()
    private val recentTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

    fun isDormant(id: String) = id in dormant
    fun isAutoStopped(id: String) = id in autoStopped

    /** Buffer an event; flood-detect; arm the coalesce window. */
    @Synchronized
    fun onEvent(event: MonitorEvent) {
        val id = event.monitorId
        if (id in autoStopped) return
        if (registerAndCheckFlood(id)) { autoStopped += id; return }
        val p = pending.getOrPut(id) { Pending() }
        if (p.lines.isEmpty()) p.firstAt = clock()
        p.lines += event
    }

    /**
     * Route every monitor whose coalesce window has elapsed. Driven by the AgentService timer.
     * Batches for monitors that have gone dormant are dropped each cycle (delivery stops once dormant).
     */
    @Synchronized
    fun flushDue() {
        val nowMs = clock()
        val ready = pending.entries.filter { nowMs - it.value.firstAt >= config.coalesceWindowMs && it.value.lines.isNotEmpty() }
        for ((id, p) in ready) {
            val batch = p.lines.toList(); p.lines.clear()
            val text = batch.joinToString("\n") { it.formatLine() }
            if (isLoopLive()) {
                deliverToLoop(text)
            } else if (batch.any { it.wakeEligible } && id !in dormant) {
                when (wakeIdle(text)) {
                    WakeOutcome.WOKE -> {
                        val left = (wakeBudget.getOrPut(id) { config.wakeBudgetPerMonitor }) - 1
                        wakeBudget[id] = left
                        if (left <= 0) dormant += id
                    }
                    WakeOutcome.SKIPPED, WakeOutcome.DEFERRED -> { /* budget not spent; batch dropped here — passive surfacing is MonitorHandle's job, not the manager's */ }
                }
            }
        }
    }

    /** @return true if [id] has exceeded the flood threshold within the trailing minute. */
    private fun registerAndCheckFlood(id: String): Boolean {
        val nowMs = clock()
        val ts = recentTimestamps.getOrPut(id) { mutableListOf() }
        ts += nowMs
        ts.removeAll { nowMs - it > 60_000 }
        // Threshold is EXCLUSIVE: returns true only when ts.size > floodThresholdPerMin, i.e.
        // auto-stop fires on the (threshold + 1)th event within the trailing minute.
        return ts.size > config.floodThresholdPerMin
    }

    /** Drop all per-monitor state for [id] (called on stop/kill). */
    @Synchronized
    fun forget(id: String) {
        pending.remove(id); wakeBudget.remove(id)
        dormant.remove(id); autoStopped.remove(id); recentTimestamps.remove(id)
    }
}
