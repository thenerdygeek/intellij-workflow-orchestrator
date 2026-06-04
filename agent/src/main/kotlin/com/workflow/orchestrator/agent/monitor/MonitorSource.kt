package com.workflow.orchestrator.agent.monitor

/**
 * Produces [MonitorEvent]s for one watched thing (a shell command, a Bamboo build, …).
 *
 * Lifecycle: [start] is called once with an `emit` sink the source invokes (from any
 * thread) whenever a notable change is detected. [stop] cancels the underlying work
 * (coroutine job / process / EventBus subscription) and must be idempotent.
 */
interface MonitorSource {
    /** Stable identifier; also stamped onto every emitted [MonitorEvent.monitorId]. */
    val monitorId: String

    /** Short human-readable description, shown in `monitor` list output and notifications. */
    val description: String

    fun start(emit: (MonitorEvent) -> Unit)

    fun stop()
}
