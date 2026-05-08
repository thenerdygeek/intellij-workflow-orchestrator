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
 * ISO-8601 strings. This allows [com.workflow.orchestrator.core.settings.HandoverConfigurable]
 * (in `:core`) to read the 30-day count without creating a cross-module reference.
 *
 * Old entries (> 30 days) are pruned on every write and on every [count30d] call.
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

    /** Records [timestamp] and prunes entries older than 30 days. */
    private fun record(timestamp: Instant) {
        val log = settings.state.handoverOverrideLog
        log.add(DateTimeFormatter.ISO_INSTANT.format(timestamp))
        pruneOlderThan30Days(log)
    }

    /** Returns the number of override events recorded in the last 30 days. */
    fun count30d(): Int {
        val log = settings.state.handoverOverrideLog
        pruneOlderThan30Days(log)
        return log.size
    }

    /** Clears all recorded override timestamps. */
    fun clear() {
        settings.state.handoverOverrideLog.clear()
    }

    private fun pruneOlderThan30Days(log: MutableList<String>) {
        val cutoff = Instant.now().minus(30, ChronoUnit.DAYS)
        log.removeAll { entry ->
            runCatching { Instant.parse(entry).isBefore(cutoff) }.getOrElse { false }
        }
    }

    companion object {
        fun getInstance(project: Project): HandoverOverrideTracker =
            project.getService(HandoverOverrideTracker::class.java)
    }
}
