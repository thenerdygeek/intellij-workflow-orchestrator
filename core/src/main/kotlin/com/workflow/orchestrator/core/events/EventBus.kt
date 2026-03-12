package com.workflow.orchestrator.core.events

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Service(Service.Level.PROJECT)
class EventBus {
    private val log = Logger.getInstance(EventBus::class.java)

    private val _events = MutableSharedFlow<WorkflowEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<WorkflowEvent> = _events.asSharedFlow()

    suspend fun emit(event: WorkflowEvent) {
        log.info("[Core:Events] Emitting event: ${event::class.simpleName}")
        _events.emit(event)
    }
}
