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
    /**
     * `Live` when the user is positioned to act on the focused PR — that means the editor's
     * current repo matches the PR's repo AND the local checkout is on the PR's source branch.
     *
     * Repo identity is part of the check (not just branch name) because two submodules in a
     * multi-module project commonly carry the same branch name (e.g. both branched from the
     * same Jira ticket as `feature/ABC-123-foo`). Without the repo check, focusing PR-A in
     * repo A while the editor sits in repo B — both on `feature/ABC-123-foo` — would falsely
     * report `Live` and let action handlers (build triggers, branch switches) target the
     * wrong submodule. That's the same bug class the 2026-04-27 repo-resolution sweep set
     * out to eliminate; gating UI on this getter must stay honest about repo identity.
     */
    val interactionMode: InteractionMode get() = when {
        focusPr == null -> InteractionMode.Live
        activeBranch != null
            && focusPr.fromBranch == activeBranch
            && focusPr.repoName == activeRepo?.name -> InteractionMode.Live
        else -> InteractionMode.ReadOnly
    }
}

enum class InteractionMode { Live, ReadOnly }
