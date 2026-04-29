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
    /**
     * Editor-derived: the repo containing the currently selected editor's file.
     * AGENT-CONTEXT ONLY. Never use this for action targeting, branch comparison, or
     * `interactionMode` decisions — opening a random `.txt` file in the wrong submodule
     * would silently target/compare against the wrong repo. Use [focusPr] + VCS lookup
     * instead.
     */
    val activeRepo: RepoRef? = null,
    /**
     * Editor-derived: `currentBranchName` of [activeRepo]. AGENT-CONTEXT ONLY (same
     * caveat as [activeRepo]). [interactionMode] reads [prRepoBranch] (the focused
     * PR's repo's actual branch), not this field.
     */
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
    /**
     * `currentBranchName` of the [focusPr]'s OWN repo, looked up by `focusPr.repoName`
     * via `GitRepositoryManager` — not the editor's repo. Null when [focusPr] is null
     * or the PR's repo cannot be resolved. Maintained by `WorkflowContextService` on
     * focus changes AND on `BranchChangeListener` events. This is the single field
     * [interactionMode] consults; the editor's open file is irrelevant.
     */
    val prRepoBranch: String? = null,
) {
    /**
     * `Live` when the [focusPr]'s source branch is currently checked out on the PR's
     * own git repo. Read off [prRepoBranch] which the service populates from the same
     * `GitRepositoryManager.repositories` list IntelliJ's top-bar branch widget reads
     * — so what the user sees in the IDE chrome and what this getter reports stay in
     * lockstep, regardless of which file is open in the editor.
     *
     * Multi-module / multi-repo: each `.git` is a distinct `GitRepository`; the lookup
     * key is `focusPr.repoName` resolved through `PluginSettings.getRepos()` to a
     * `localVcsRootPath`. Two submodules sharing a branch name don't collide because
     * the lookup is repo-specific.
     */
    val interactionMode: InteractionMode get() = when {
        focusPr == null -> InteractionMode.Live
        prRepoBranch != null && focusPr.fromBranch == prRepoBranch -> InteractionMode.Live
        else -> InteractionMode.ReadOnly
    }
}

enum class InteractionMode { Live, ReadOnly }
