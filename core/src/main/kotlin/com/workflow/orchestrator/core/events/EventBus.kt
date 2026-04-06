package com.workflow.orchestrator.core.events

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight snapshot of the last-selected PR for a given repo.
 * Queryable by Build/Quality tabs when the user manually switches their repo selector.
 */
data class PrContext(
    val prId: Int,
    val fromBranch: String,
    val toBranch: String,
    val repoName: String,
    val bambooPlanKey: String?,
    val sonarProjectKey: String?,
    /** Build status: SUCCESSFUL, FAILED, INPROGRESS, or null if not yet fetched */
    val buildStatus: String? = null,
    /** Quality gate: OK, ERROR, or null if not yet fetched */
    val qualityGateStatus: String? = null,
)

@Service(Service.Level.PROJECT)
class EventBus {
    private val log = Logger.getInstance(EventBus::class.java)

    private val _events = MutableSharedFlow<WorkflowEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<WorkflowEvent> = _events.asSharedFlow()

    /** Last-selected PR per repo. Key = repoName (RepoConfig.displayLabel). */
    val prContextMap: ConcurrentHashMap<String, PrContext> = ConcurrentHashMap()

    suspend fun emit(event: WorkflowEvent) {
        log.info("[Core:Events] Emitting event: ${event::class.simpleName}")
        // Auto-update PrContext map on PrSelected events
        if (event is WorkflowEvent.PrSelected) {
            prContextMap[event.repoName] = PrContext(
                prId = event.prId,
                fromBranch = event.fromBranch,
                toBranch = event.toBranch,
                repoName = event.repoName,
                bambooPlanKey = event.bambooPlanKey,
                sonarProjectKey = event.sonarProjectKey,
            )
        }
        if (!_events.tryEmit(event)) {
            log.warn("[Core:Events] Failed to emit event (buffer full): ${event::class.simpleName}")
        }
    }
}
