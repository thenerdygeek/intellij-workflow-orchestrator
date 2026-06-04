package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.polling.SmartPoller
import kotlinx.coroutines.CoroutineScope

/**
 * [MonitorSource] base that polls a `:core` service on a [SmartPoller] and diffs successive
 * snapshots. Subclasses implement [fetch] (one async read; null = treat as no-change) and
 * [diff] (pure; empty list = no change).
 *
 * The poll cycle is extracted into [pollOnce] so tests can drive it directly without
 * constructing a live [SmartPoller].
 */
abstract class PollingSource<T>(
    final override val monitorId: String,
    final override val description: String,
    private val cs: CoroutineScope,
    private val baseIntervalMs: Long = 30_000,
) : MonitorSource {

    @Volatile private var poller: SmartPoller? = null
    @Volatile private var snapshot: T? = null

    /**
     * Fetch current state from the domain service.
     * Return null on transient error — [pollOnce] treats null as no-change so the
     * [SmartPoller] backs off rather than emitting spurious events.
     */
    protected abstract suspend fun fetch(): T?

    /**
     * Pure: compare [previous] snapshot (null on the very first poll) to [current] and return
     * the events to emit. An empty list signals no notable change (SmartPoller backs off 1.5×).
     */
    protected abstract fun diff(previous: T?, current: T): List<MonitorEvent>

    /**
     * One poll cycle. Returns true if any event was emitted (SmartPoller "data changed" signal,
     * which resets backoff). Extracted so unit tests can drive it without a live SmartPoller.
     */
    internal suspend fun pollOnce(emit: (MonitorEvent) -> Unit): Boolean {
        val current = fetch() ?: return false
        val events = diff(snapshot, current)
        snapshot = current
        events.forEach(emit)
        return events.isNotEmpty()
    }

    override fun start(emit: (MonitorEvent) -> Unit) {
        poller = SmartPoller(name = "monitor-$monitorId", baseIntervalMs = baseIntervalMs, scope = cs) {
            pollOnce(emit)
        }.also { it.start() }
    }

    override fun stop() {
        poller?.stop()
        poller = null
    }
}
