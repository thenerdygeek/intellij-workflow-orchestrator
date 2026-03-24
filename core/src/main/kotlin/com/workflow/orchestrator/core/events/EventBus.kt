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

    suspend fun emit(event: WorkflowEvent) {
        log.info("[Core:Events] Emitting event: ${event::class.simpleName}")
        // With DROP_OLDEST, tryEmit() always succeeds and never suspends,
        // preventing slow subscribers from blocking emitters
        if (!_events.tryEmit(event)) {
            log.warn("[Core:Events] Failed to emit event (buffer full): ${event::class.simpleName}")
        }
    }
}
