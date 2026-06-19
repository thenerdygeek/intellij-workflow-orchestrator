package com.workflow.orchestrator.core.events

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Service(Service.Level.PROJECT)
class EventBus {
    private val log = Logger.getInstance(EventBus::class.java)

    private val _events = MutableSharedFlow<WorkflowEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<WorkflowEvent> = _events.asSharedFlow()

    fun emit(event: WorkflowEvent) {
        // P2-24: per-event logging stays at DEBUG — emit() is on hot paths (polling,
        // task/monitor updates) and INFO-per-event floods idea.log.
        log.debug("[Core:Events] Emitting event: ${event::class.simpleName}")
        if (!_events.tryEmit(event)) {
            log.warn("[Core:Events] Failed to emit event (buffer full): ${event::class.simpleName}")
        }
    }
}
