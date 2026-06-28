package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Project-scoped service that subscribes to [WorkflowEvent.HandoverOverride] events
 * and persists their timestamps in [PluginSettings.State.handoverOverrideLog] as
 * ISO-8601 strings. This allows [com.workflow.orchestrator.handover.settings.HandoverConfigurable]
 * to read the 30-day count without creating a cross-module reference to `:core`.
 *
 * All mutations and reads of [PluginSettings.State.handoverOverrideLog] are guarded by
 * `synchronized` on the list instance to prevent [java.util.ConcurrentModificationException]
 * between the coroutine write path ([record]) and any EDT read path
 * ([count30d] / [clear] / [HandoverConfigurable.count30d]).
 *
 * Pruning of entries older than 30 days happens exclusively inside [record], so read-only
 * callers (e.g., [HandoverConfigurable]) never need to mutate the list.
 *
 * The log is hard-capped at [MAX_LOG_SIZE] entries; oldest entries are evicted first when
 * the cap is reached.
 *
 * Registered as a `<projectService>` in `plugin.xml`; the platform injects
 * [CoroutineScope] so the service does not allocate its own scope.
 */
@Service(Service.Level.PROJECT)
class HandoverOverrideTracker {

    private val eventBus: EventBus
    private val settings: PluginSettings
    private val cs: CoroutineScope

    /** IntelliJ platform DI constructor. */
    constructor(project: Project, cs: CoroutineScope) {
        this.eventBus = project.getService(EventBus::class.java)
        this.settings = PluginSettings.getInstance(project)
        this.cs = cs
        init()
    }

    /** Test constructor — allows injecting mocks without a live project. */
    constructor(eventBus: EventBus, settings: PluginSettings, cs: CoroutineScope) {
        this.eventBus = eventBus
        this.settings = settings
        this.cs = cs
        init()
    }

    private fun init() {
        cs.launch {
            eventBus.events.collect { ev ->
                if (ev is WorkflowEvent.HandoverOverride) {
                    record(ev.timestamp)
                }
            }
        }
    }

    /**
     * Appends [timestamp] to the log, then prunes entries older than 30 days and enforces
     * [MAX_LOG_SIZE]. All mutations are performed under `synchronized` on the log list.
     */
    private fun record(timestamp: Instant) {
        val log = settings.state.handoverOverrideLog
        synchronized(log) {
            log.add(DateTimeFormatter.ISO_INSTANT.format(timestamp))
            pruneInPlace(log, timestamp)
        }
    }

    /**
     * Returns the number of override events recorded in the last 30 days.
     * Read-only — pruning is handled by [record]; this method never mutates the list.
     */
    fun count30d(): Int {
        val log = settings.state.handoverOverrideLog
        val cutoff = Instant.now().minus(30, ChronoUnit.DAYS)
        return synchronized(log) {
            log.count { entry ->
                runCatching { !Instant.parse(entry).isBefore(cutoff) }.getOrElse { false }
            }
        }
    }

    /** Clears all recorded override timestamps. */
    fun clear() {
        val log = settings.state.handoverOverrideLog
        synchronized(log) {
            log.clear()
        }
    }

    /**
     * Prunes entries older than 30 days relative to [nowInstant] and evicts the oldest
     * entries if the list exceeds [MAX_LOG_SIZE]. Caller must hold the lock on [log].
     */
    private fun pruneInPlace(log: MutableList<String>, nowInstant: Instant) {
        val cutoff = nowInstant.minus(30, ChronoUnit.DAYS)
        log.removeAll { entry ->
            runCatching { Instant.parse(entry).isBefore(cutoff) }.getOrElse { false }
        }
        while (log.size > MAX_LOG_SIZE) log.removeAt(0)
    }

    companion object {
        /** Maximum number of entries kept in [PluginSettings.State.handoverOverrideLog]. */
        const val MAX_LOG_SIZE = 1000

        fun getInstance(project: Project): HandoverOverrideTracker =
            project.getService(HandoverOverrideTracker::class.java)
    }
}
