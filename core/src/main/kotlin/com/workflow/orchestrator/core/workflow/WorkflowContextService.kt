package com.workflow.orchestrator.core.workflow

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.model.workflow.ModuleRef
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.QualityScope
import com.workflow.orchestrator.core.model.workflow.RepoRef
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Project-level holder for the unified [WorkflowContext] state cell. See
 * `docs/architecture/workflow-context-design.md` (§3.2 + §4.5) and
 * `docs/architecture/phase5-workflow-context-plan.md` (Task 5/6/7).
 *
 * Phase 5 T7 — adds the `focusPr` cascade with mutex serialization and EP-driven build
 * lookup (spec §4.0, §4.1, §4.4). The [setActiveTicket] auto-seed (T6) routes through
 * [computeFocusForPr] so the build/quality cascade runs there too. Cascade serialization
 * is via the mutex alone (the cascade body runs inline under the lock); the spec's
 * launch-cancel example was simplified to inline-mutex during plan v2 because (a) the 5s
 * `withTimeoutOrNull` already bounds worst-case latency, (b) `runTest` virtual-time
 * interaction with launched HTTP cascades is fragile. Mirror integration
 * (`WorkflowEventMirror`) lands in T8 — it uses [serviceCs] to launch event-driven
 * cascades on the platform-managed scope.
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
        wireEditorListeners()
    }

    private fun loadAnchorFromSettings() {
        val settings = project.getService(PluginSettings::class.java) ?: return
        val id = settings.state.activeTicketId?.takeIf { it.isNotBlank() } ?: return
        val summary = settings.state.activeTicketSummary.orEmpty()
        _state.value = WorkflowContext(activeTicket = TicketRef(id, summary))
        log.info("[Workflow:Context] Boot-loaded anchor: $id")
    }

    /**
     * Subscribe to editor selection + VCS mapping changes; each fires a coroutine to
     * recompute the editor-derived slice (`activeRepo`, `activeBranch`, `activeModule`).
     *
     * NOTE: `GitRepositoryChangeListener` intentionally NOT subscribed — git4idea is an
     * optional dependency. `VCS_REPOSITORY_MAPPING_UPDATED` (in the `com.intellij.dvcs`
     * platform module) covers the branch-change signal we need without that risk.
     */
    private fun wireEditorListeners() {
        val bus = project.messageBus.connect(this)

        bus.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    cs.launch { recomputeFromEditor() }
                }
            }
        )

        bus.subscribe(
            VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED,
            VcsRepositoryMappingListener {
                cs.launch { recomputeFromEditor() }
            }
        )
    }

    /**
     * Recomputes the editor-derived slice. Mutex-serialized with all other mutators
     * (T7 `focusPr` cascade depends on this for serialization).
     *
     * `RepoContextResolver.resolveCurrentEditorRepoOrPrimary()` is already memoised via
     * `CachedValuesManager` + `SimpleModificationTracker`, so it does NOT need a
     * `readAction { }` wrap. Module computation (PSI access via `ModuleUtilCore` +
     * `ModuleRootManager`) DOES need `readAction { }`.
     */
    private suspend fun recomputeFromEditor() = cascadeMutex.withLock {
        val gitRepo = RepoContextResolver.getInstance(project).resolveCurrentEditorRepoOrPrimary()

        val repoConfig = gitRepo?.let { repo ->
            PluginSettings.getInstance(project).getRepos()
                .firstOrNull { it.localVcsRootPath == repo.root.path }
        }
        val repoRef = repoConfig?.let {
            RepoRef(
                name = it.name.orEmpty(),
                projectKey = it.bitbucketProjectKey.orEmpty(),
                repoSlug = it.bitbucketRepoSlug.orEmpty(),
                localVcsRootPath = it.localVcsRootPath.orEmpty(),
            )
        }
        val branch = gitRepo?.currentBranchName
        val module = readAction {
            val fem = FileEditorManager.getInstance(project)
            val file = fem.selectedEditor?.file ?: return@readAction null
            val mod = ModuleUtilCore.findModuleForFile(file, project)
                ?: return@readAction null
            ModuleRef(
                name = mod.name,
                rootPath = ModuleRootManager.getInstance(mod).contentRoots
                    .firstOrNull()?.path.orEmpty(),
            )
        }

        _state.value = _state.value.copy(
            activeRepo = repoRef,
            activeBranch = branch,
            activeModule = module,
        )
    }

    /**
     * Sets (or clears) the active ticket anchor and auto-seeds [WorkflowContext.focusPr]
     * if exactly one open PR matches the ticket key in its `fromBranch` (spec §4.2.2).
     *
     * Persistence to [PluginSettings] happens BEFORE any suspend point so a crash
     * mid-cascade never loses the anchor (spec §4.2 R-PERSIST).
     *
     * Auto-seed routes through [computeFocusForPr] so the matched PR also drives the
     * `focusBuild` + `focusQualityScope` cascade (T7).
     */
    suspend fun setActiveTicket(ticket: TicketRef?) = cascadeMutex.withLock {
        // 1. Persist BEFORE any suspend point.
        val settings = PluginSettings.getInstance(project)
        settings.state.activeTicketId = ticket?.key.orEmpty()
        settings.state.activeTicketSummary = ticket?.summary.orEmpty()

        // 2. Auto-seed focusPr (spec §4.2.2.a.ii) and run the full focus cascade so
        //    the auto-seeded PR's build + quality scope are populated in the same write.
        var next = _state.value.copy(activeTicket = ticket)
        if (ticket != null) {
            val matching = findOpenPrMatchingTicket(ticket.key)
            if (matching != null && matching != next.focusPr) {
                next = computeFocusForPr(next, matching)
            }
        }
        _state.value = next
        log.info(
            "[Workflow:Context] setActiveTicket: ${ticket?.key ?: "<cleared>"}, " +
                "focusPr=${next.focusPr?.prId}"
        )
    }

    /**
     * Sets (or clears) the focused PR and cascades to `focusBuild` + `focusQualityScope`
     * in a single observable state transition (spec §4.0, §4.1, §4.4).
     *
     * Mutex-serialized with all other mutators — concurrent calls run sequentially, so
     * a stale build lookup can never overwrite a newer cascade (each call's `_state.value`
     * write completes before the next call enters the lock).
     *
     * For `pr == null`, the focus chain is fully cleared. Per-spec §4.1, re-deriving
     * `focusBuild` from `activeBranch` is deferred to `latestBuildForBranchFlow`
     * (out of scope for Phase 5a) — null is correct here, and no [LatestBuildLookup]
     * call is made.
     */
    suspend fun focusPr(pr: PrRef?) = cascadeMutex.withLock {
        val newCtx = if (pr == null) {
            _state.value.copy(focusPr = null, focusBuild = null, focusQualityScope = null)
        } else {
            computeFocusForPr(_state.value, pr)
        }
        _state.value = newCtx
        log.info(
            "[Workflow:Context] focusPr → ${pr?.prId ?: "<cleared>"}, " +
                "focusBuild=${newCtx.focusBuild?.buildNumber}"
        )
    }

    /**
     * Pure-ish builder that composes the next [WorkflowContext] for a non-null PR focus:
     * looks up the latest build via the [LatestBuildLookup] EP (5s timeout, off-EDT) and
     * derives [QualityScope] from the PR's Sonar project key. Returns `base.copy(...)`
     * with all three focus fields populated; the caller writes `_state.value` exactly
     * once, preserving the single-merged-emission invariant (§4.4).
     */
    private suspend fun computeFocusForPr(
        base: WorkflowContext,
        pr: PrRef,
    ): WorkflowContext {
        // The [LatestBuildLookup] EP contract documents the implementation as "off-EDT"
        // (`:bamboo`'s `BambooApiClient.get()` already dispatches to `Dispatchers.IO`),
        // so we don't double-wrap with `withContext(Dispatchers.IO)` here. We do bound
        // it with [withTimeoutOrNull] (5s, per spec §4.1 R2) so a stalled HTTP call
        // never wedges the cascade — null on timeout is the documented degraded state.
        val build = pr.bambooPlanKey?.let { planKey ->
            withTimeoutOrNull(5_000) {
                LatestBuildLookup.getInstance()?.fetchLatestBuild(project, planKey, pr.fromBranch)
            }
        }
        val quality = pr.sonarProjectKey?.let {
            QualityScope(
                sonarProjectKey = it,
                branchName = pr.fromBranch,
                moduleKey = null,
            )
        }
        return base.copy(focusPr = pr, focusBuild = build, focusQualityScope = quality)
    }

    /**
     * Returns the deterministic open-PR match for the given ticket key, or null.
     * Highest `prId` wins so picks are stable across IDE restarts (spec §4.2.2).
     */
    private fun findOpenPrMatchingTicket(ticketKey: String): PrRef? {
        val lister = OpenPrLister.getInstance() ?: return null
        val matches = lister.listOpenPrs(project)
            .filter { it.fromBranch.contains(ticketKey, ignoreCase = false) }
        if (matches.isEmpty()) return null
        return matches.maxByOrNull { it.prId }
    }

    override fun dispose() {
        // Platform manages `cs` lifecycle. messageBus connections registered with
        // `connect(this)` are auto-disposed.
    }

    companion object {
        fun getInstance(project: Project): WorkflowContextService = project.service()
    }
}
