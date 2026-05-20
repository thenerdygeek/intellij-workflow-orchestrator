package com.workflow.orchestrator.automation.service

import kotlinx.coroutines.Job
import org.jetbrains.annotations.TestOnly

/**
 * Owns `QueueService.pollingJob` and guarantees the two operations on it —
 *   • startIfNeeded(launch): "launch a poller iff one isn't already running"
 *   • tryExit(self, hasLiveWork): "exit the loop iff no live work remains"
 * — are atomic against each other.
 *
 * Without this atomicity, the polling loop had a microsecond window between
 * its `_stateFlow.value.none { ... }` exit check and the `pollingJob = null`
 * assignment where `pollingJob.isActive` was still `true` while the loop had
 * already decided to break. An `enqueue` racing with that window would see
 * the still-active flag, skip launching a new poller, and silently orphan
 * its new entry in `WAITING_LOCAL`.
 *
 * The contract: `tryExit` and `startIfNeeded` use the same lock, and
 * `tryExit` clears the slot inside that lock so the very next
 * `startIfNeeded` reads `null` and proceeds to launch.
 */
internal class PollingLifecycle {

    private val lock = Any()

    /** Guarded by [lock]. Visible to tests via [currentJobForTest]. */
    private var currentJob: Job? = null

    /**
     * Atomically: if no poller is currently active, invoke [launch] to start one
     * and record its [Job]. Returns `true` if a new poller was launched.
     *
     * [launch] is invoked **inside** the lock, so it should be fast (it's just
     * `cs.launch { … }` returning a Job handle — the coroutine body runs later).
     */
    fun startIfNeeded(launch: () -> Job): Boolean = synchronized(lock) {
        if (currentJob?.isActive == true) return@synchronized false
        currentJob = launch()
        true
    }

    /**
     * Called by the polling loop after its `delay()`. Atomically:
     *   • If [self] is no longer the active job (we were replaced or cancelled),
     *     returns `true` so the loop exits cleanly. The slot is left alone.
     *   • Else if [hasLiveWork] returns `true`, returns `false` so the loop
     *     continues. The slot is unchanged.
     *   • Else clears the slot and returns `true` so the loop breaks. The very
     *     next [startIfNeeded] will launch a fresh poller.
     */
    fun tryExit(self: Job, hasLiveWork: () -> Boolean): Boolean = synchronized(lock) {
        if (currentJob !== self) return@synchronized true
        if (hasLiveWork()) return@synchronized false
        currentJob = null
        true
    }

    /**
     * Defensive cleanup, called from the polling coroutine's `finally` so that
     * cancellation or an unexpected exception cannot leave a dangling Job
     * reference in the slot. Only clears when [self] still matches — never
     * touches a slot owned by a newer poller.
     */
    fun clearIfStillOwnedBy(self: Job) = synchronized(lock) {
        if (currentJob === self) currentJob = null
    }

    @get:TestOnly
    val currentJobForTest: Job? get() = synchronized(lock) { currentJob }
}
