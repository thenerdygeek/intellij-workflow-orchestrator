package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex

/**
 * Project-level holder for the unified [WorkflowContext] state cell. See
 * `docs/architecture/workflow-context-design.md` (§3.2 + §4.5) and
 * `docs/architecture/phase5-workflow-context-plan.md` (Task 5).
 *
 * Phase 5 T5 — skeleton only:
 * - State cell with derived flows for [activeTicket] and [interactionMode].
 * - Synchronous boot anchor load from [PluginSettings] so first reads after
 *   service instantiation already see the persisted ticket.
 *
 * Mutators (`setActiveTicket`, `focusPr`) and listener wiring (editor / messageBus)
 * land in T6/T7. Mirror integration (`WorkflowEventMirror`) lands in T8 — it uses
 * [serviceCs] to launch event-driven cascades on the platform-managed scope.
 */
@Service(Service.Level.PROJECT)
class WorkflowContextService(
    private val project: Project,
    private val cs: CoroutineScope,
) : Disposable {
    private val log = Logger.getInstance(WorkflowContextService::class.java)
    private val cascadeMutex = Mutex()

    private val _state = MutableStateFlow(WorkflowContext())
    val state: StateFlow<WorkflowContext> = _state.asStateFlow()

    val activeTicketFlow: StateFlow<TicketRef?> = state
        .map { it.activeTicket }
        .distinctUntilChanged()
        .stateIn(cs, SharingStarted.Eagerly, null)

    val interactionModeFlow: StateFlow<InteractionMode> = state
        .map { it.interactionMode }
        .distinctUntilChanged()
        .stateIn(cs, SharingStarted.Eagerly, InteractionMode.Live)

    /** Package-private accessor for [WorkflowEventMirror] (T8). */
    internal val serviceCs: CoroutineScope get() = cs

    init {
        loadAnchorFromSettings()
    }

    private fun loadAnchorFromSettings() {
        val settings = project.getService(PluginSettings::class.java) ?: return
        val id = settings.state.activeTicketId?.takeIf { it.isNotBlank() } ?: return
        val summary = settings.state.activeTicketSummary.orEmpty()
        _state.value = WorkflowContext(activeTicket = TicketRef(id, summary))
        log.info("[Workflow:Context] Boot-loaded anchor: $id")
    }

    override fun dispose() {
        // Platform manages `cs` lifecycle. messageBus connections registered with
        // `connect(this)` are auto-disposed (added in T6).
    }

    companion object {
        fun getInstance(project: Project): WorkflowContextService = project.service()
    }
}
