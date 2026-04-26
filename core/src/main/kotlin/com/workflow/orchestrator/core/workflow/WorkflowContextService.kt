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

/**
 * Project-level holder for the unified [WorkflowContext] state cell. See
 * `docs/architecture/workflow-context-design.md` (§3.2 + §4.5) and
 * `docs/architecture/phase5-workflow-context-plan.md` (Task 5/6).
 *
 * Phase 5 T6 — adds editor listener wiring + the `setActiveTicket` cascade with PR
 * auto-seed (spec §4.2). [focusPr] mutator (with build cascade) lands in T7. Mirror
 * integration (`WorkflowEventMirror`) lands in T8 — it uses [serviceCs] to launch
 * event-driven cascades on the platform-managed scope.
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
     * The build cascade triggered by an auto-seeded `focusPr` is intentionally NOT run
     * here — that lives in T7's `focusPr` mutator. T6 only sets the field.
     */
    suspend fun setActiveTicket(ticket: TicketRef?) = cascadeMutex.withLock {
        // 1. Persist BEFORE any suspend point.
        val settings = PluginSettings.getInstance(project)
        settings.state.activeTicketId = ticket?.key.orEmpty()
        settings.state.activeTicketSummary = ticket?.summary.orEmpty()

        // 2. Auto-seed focusPr (spec §4.2.2.a.ii).
        var next = _state.value.copy(activeTicket = ticket)
        if (ticket != null) {
            val matching = findOpenPrMatchingTicket(ticket.key)
            if (matching != null && matching != next.focusPr) {
                next = next.copy(focusPr = matching)  // T7 will add focusBuild + quality cascade
            }
        }
        _state.value = next
        log.info(
            "[Workflow:Context] setActiveTicket: ${ticket?.key ?: "<cleared>"}, " +
                "focusPr=${next.focusPr?.prId}"
        )
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
