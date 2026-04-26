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
    val activeModule: ModuleRef? = null,
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
