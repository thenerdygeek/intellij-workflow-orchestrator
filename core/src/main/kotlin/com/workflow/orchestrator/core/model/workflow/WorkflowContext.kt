package com.workflow.orchestrator.core.model.workflow

/**
 * Immutable snapshot of workflow state. See docs/architecture/workflow-context-design.md.
 *
 * INVARIANT: every derived getter (today: [interactionMode]; future additions) MUST be
 * a pure function of declared fields. External-state reads inside derived getters break
 * the `state.map { it.<derived> }.distinctUntilChanged()` flow — the underlying state
 * doesn't change when external state changes, so the flow misses transitions. New
 * contributing factors MUST be added as declared fields. Enforced by InteractionModePurityTest.
 */
data class WorkflowContext(
    val activeTicket: TicketRef? = null,
    val activeRepo: RepoRef? = null,
    val activeBranch: String? = null,
    /**
     * The module containing the currently selected editor's file, or null when no
     * editor is open. Renamed from `activeModule` (which falsely implied IntelliJ
     * has a single "active" module — it doesn't; all modules are simultaneously
     * loaded). See [projectModules] for the full module list.
     */
    val editorModule: ModuleRef? = null,
    /**
     * All modules currently registered with this project's `ModuleManager`. Refreshed
     * on `ModuleListener` events. Empty list if the project has no modules (rare).
     */
    val projectModules: List<ModuleRef> = emptyList(),
    val focusPr: PrRef? = null,
    val focusBuild: BuildRef? = null,
    val focusQualityScope: QualityScope? = null,
) {
    val interactionMode: InteractionMode get() = when {
        focusPr == null -> InteractionMode.Live
        activeBranch != null && focusPr.fromBranch == activeBranch -> InteractionMode.Live
        else -> InteractionMode.ReadOnly
    }
}

enum class InteractionMode { Live, ReadOnly }
