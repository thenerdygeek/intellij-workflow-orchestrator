package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.events.WorkflowEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * [MonitorSource] base that subscribes to the `:core` [com.workflow.orchestrator.core.events.EventBus]
 * `SharedFlow<WorkflowEvent>` and maps incoming events to [MonitorEvent]s.
 *
 * Because [com.workflow.orchestrator.core.events.EventBus] is `replay = 0`, any event fired before
 * subscription is permanently lost. To seed an initial state, [hydrate] is called once before
 * the flow collector starts — subclasses override it to fetch current state from a `:core` service.
 *
 * Subclasses only need to implement:
 * - [map] — pure; return a [MonitorEvent] for an interesting event, or null to ignore it.
 * - [hydrate] — optional; one-shot fetch of current state on start (default: no-op).
 *
 * The injected [flow] constructor parameter keeps this class decoupled from the real `EventBus`,
 * so it is directly testable with a [kotlinx.coroutines.flow.MutableSharedFlow].
 *
 * @param monitorId  Stable identifier; stamped onto every emitted [MonitorEvent].
 * @param description  Short human-readable label shown in monitor list/status output.
 * @param cs  Scope in which the collection coroutine and hydration suspend.
 * @param flow  The `SharedFlow<WorkflowEvent>` to subscribe to; concrete subclasses pass
 *              `EventBus.events` (the real bus); tests inject a [kotlinx.coroutines.flow.MutableSharedFlow].
 */
abstract class EventBusSource(
    final override val monitorId: String,
    final override val description: String,
    private val cs: CoroutineScope,
    private val flow: SharedFlow<WorkflowEvent>,
) : MonitorSource {

    // protected so subclasses (e.g. Phase-3 PR-state) can inspect liveness via `job?.isActive`.
    // start-after-stop race: stop() nulls `job` before cancellation is observed by the launched
    // coroutine, so a hydrate/emit already in flight may still deliver one event after stop() returns.
    @Volatile protected var job: Job? = null

    /**
     * Pure mapping function. Return a [MonitorEvent] when this event is interesting, or null to
     * ignore it. Must not throw — any exception silently drops the event.
     */
    protected abstract fun map(event: WorkflowEvent): MonitorEvent?

    /**
     * One-shot start-hydration invoked BEFORE the flow collector begins. Subclasses override this
     * to fetch current state from a `:core` service so that a state that was reached before
     * subscription is not silently missed. Default returns null (no hydration event).
     */
    protected open suspend fun hydrate(): MonitorEvent? = null

    override fun start(emit: (MonitorEvent) -> Unit) {
        job = cs.launch {
            runCatching { hydrate() }.getOrNull()?.let(emit)   // start-hydration BEFORE collecting
            flow.collect { ev -> map(ev)?.let(emit) }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}
