package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.model.BuildState
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.model.NewerBuild
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.workflow.BuildRef
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.polling.SmartPoller
import com.workflow.orchestrator.core.services.BuildLogCache
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Polls Bamboo for the focused build and emits `BuildFinished` / `BuildLogReady` events.
 *
 * ## Observer-gated polling contract (P0-5, 2026-06-10 perf audit)
 *
 * The focusBuild auto-seed at project startup (T-AutoSeed) starts polling without any UI
 * consumer, so polling is gated on an observer signal instead of running at full rate forever:
 *
 * - **No observer by default.** [tabVisible] starts false; a freshly created [SmartPoller] is
 *   immediately put into background mode (4× interval) until the Build tab attaches via
 *   [setVisible] `(true)`. Previously `SmartPoller.visible` defaulted true, so an unopened
 *   tab polled at full rate for the project lifetime.
 * - **Terminal + unobserved → stop.** After each poll, if the focused build is in a terminal
 *   state (SUCCESS/FAILED) and the tab is not visible, polling stops entirely. The ambient
 *   consumers (Automation tab docker tags, Handover build status — both EventBus-driven)
 *   already received `BuildFinished`/`BuildLogReady` for that build; continued polling would
 *   only serve the (invisible) newer-build banner.
 * - **Restart triggers.** An auto-stopped poller restarts when (a) the Build tab becomes
 *   visible again — [setVisible] `(true)` relaunches against [lastPollTarget] WITHOUT
 *   resetting the dedupe state, so no duplicate events and no log refetch — or (b) the
 *   focusBuild changes ([wireFocusBuildSubscription] → [startPolling], full reset as before).
 * - **Running build stays ambient.** A non-terminal build keeps background-rate polling even
 *   with no observer, so event consumers still see the eventual terminal transition.
 *
 * Pinned by `BuildMonitorObserverGatingTest`.
 */
@Service(Service.Level.PROJECT)
open class BuildMonitorService {

    private val log = Logger.getInstance(BuildMonitorService::class.java)

    private var _project: Project? = null
    private var _notificationServiceResolved = false

    // Backing fields — set directly by test constructor, or lazily resolved from project
    private var _apiClient: BambooApiClient? = null
    private var _eventBus: EventBus? = null
    private var _notificationService: WorkflowNotificationService? = null
    private var _buildLogCache: BuildLogCache? = null

    /**
     * Returns the URL-change-aware [BambooApiClient] for this project.
     *
     * - Test path: [_apiClient] is set directly by the test constructor and returned as-is.
     * - Production path: delegates to [BambooServiceImpl.getPollClient] so all Bamboo HTTP
     *   calls in the module share the single URL-change-aware client (which invalidates when
     *   the user changes the Bamboo URL in settings). No second cached client is created.
     */
    private val apiClient: BambooApiClient get() = _apiClient
        ?: _project!!.getService(BambooServiceImpl::class.java)?.getPollClient()
        ?: error("[Bamboo:Monitor] Bamboo not configured — no base URL set")

    private val eventBus: EventBus get() = _eventBus ?: _project!!.getService(EventBus::class.java).also { _eventBus = it }
    private val buildLogCache: BuildLogCache get() = _buildLogCache
        ?: BuildLogCache.getInstance(_project!!).also { _buildLogCache = it }
    private val cs: CoroutineScope
    private val notificationService: WorkflowNotificationService? get() {
        if (!_notificationServiceResolved) {
            _notificationService = _project?.let { WorkflowNotificationService.getInstance(it) }
            _notificationServiceResolved = true
        }
        return _notificationService
    }

    /** Project service constructor — used by IntelliJ DI. Deps are lazy-inited on first use. */
    constructor(project: Project, cs: CoroutineScope) {
        this._project = project
        this.cs = cs
        wireFocusBuildSubscription(
            WorkflowContextService.getInstance(project).state
                .map { it.focusBuild }
                .distinctUntilChanged()
        )
    }

    /**
     * Test constructor — allows injecting mocks.
     *
     * @param focusBuildFlow when non-null, wires the same ambient focus-driven lifecycle
     *   used by the IntelliJ-DI constructor against the supplied flow. Tests pass a
     *   [MutableStateFlow<BuildRef?>] to drive polling lifecycle scenarios without
     *   needing IntelliJ platform services. When null, no subscription is wired
     *   (caller drives [startPolling]/[stopPolling] directly, matching the legacy
     *   test style used by [BuildMonitorServiceTest]).
     */
    constructor(
        apiClient: BambooApiClient,
        eventBus: EventBus,
        scope: CoroutineScope,
        notificationService: WorkflowNotificationService? = null,
        buildLogCache: BuildLogCache = BuildLogCache(),
        focusBuildFlow: StateFlow<BuildRef?>? = null,
    ) {
        this._apiClient = apiClient
        this._eventBus = eventBus
        this.cs = scope
        this._notificationService = notificationService
        this._notificationServiceResolved = true
        this._buildLogCache = buildLogCache
        if (focusBuildFlow != null) {
            wireFocusBuildSubscription(focusBuildFlow)
        }
    }

    /**
     * Wires a [collectLatest] subscription against [focusFlow] that drives
     * [startPolling]/[stopPolling] as the focused build changes.
     *
     * - Non-null [BuildRef]: calls [startPolling] with the chain key (preferred) or
     *   plan key as the plan target, using the current [PluginSettings] poll interval
     *   when a project is available, or the default 30s when running under tests.
     * - Null [BuildRef]: calls [stopPolling].
     *
     * [collectLatest] is essential here: it cancels the in-flight coroutine when a new
     * value arrives, so a rapid A→B→C sequence delivers only C's [startPolling] target
     * and never lets a stale A or B result win a race.
     *
     * Coexists safely with direct [startPolling]/[stopPolling] calls from
     * [com.workflow.orchestrator.bamboo.ui.BuildDashboardPanel] — the last caller wins.
     * The panel-side calls will be removed in T-B2/B3-b.
     */
    private fun wireFocusBuildSubscription(focusFlow: kotlinx.coroutines.flow.Flow<BuildRef?>) {
        cs.launch {
            focusFlow.collectLatest { focusBuild ->
                if (focusBuild == null) {
                    stopPolling()
                } else {
                    val pollKey = focusBuild.chainKey ?: focusBuild.planKey
                    val intervalMs = _project?.let { p ->
                        PluginSettings.getInstance(p).state.buildPollIntervalSeconds.toLong() * 1_000L
                    } ?: 30_000L
                    startPolling(pollKey, focusBuild.branch, intervalMs)
                }
            }
        }
    }

    private val _stateFlow = MutableStateFlow<BuildState?>(null)
    val stateFlow: StateFlow<BuildState?> = _stateFlow.asStateFlow()

    private var previousBuildNumber: Int? = null
    private var previousStatus: BuildStatus? = null
    private var lastLogFetchedForBuild: Int? = null

    @Volatile private var poller: SmartPoller? = null

    /** Whether the Build tab (the only direct UI observer) is currently showing. */
    private val tabVisible = AtomicBoolean(false)

    /** True when polling was auto-stopped (terminal build + no observer) — see class KDoc. */
    @Volatile private var autoStoppedTerminal = false

    /** Last [startPolling] target, kept so an observer attach can restart an auto-stopped poller. */
    @Volatile private var lastPollTarget: Triple<String, String, Long>? = null

    /** Guards poller create/stop + the auto-stop/restart handshake between poll loop and EDT. */
    private val pollerLock = Any()

    /**
     * Guards the entire body of [pollOnce] so that the SmartPoller coroutine and
     * a concurrent user-initiated Refresh call cannot execute [pollOnce] at the same
     * time. Without this lock, two concurrent invocations could both observe
     * `previousBuildNumber == null` and emit a double BuildFinished event, or
     * `lastLogFetchedForBuild` could be set by one before the other has fetched
     * all logs, silently skipping the retry path.
     *
     * Contention is negligible: SmartPoller fires every 30s and Refresh is user-initiated.
     */
    private val pollMutex = Mutex()

    // Canonical per-stage job order (stageName -> ordered job shortNames) from the plan
    // DEFINITION, cached per plan key. The result endpoint returns jobs in an unstable order;
    // this restores the plan-defined (website) order for the Build-tab job list. Cached only
    // on success, so a transient fetch failure simply retries on the next poll (and the mapper
    // falls back to the API order in the meantime).
    private var jobOrderCache: Pair<String, Map<String, List<String>>>? = null

    private suspend fun jobOrderFor(planKey: String): Map<String, List<String>> {
        jobOrderCache?.let { (cachedKey, order) -> if (cachedKey == planKey) return order }
        return when (val r = apiClient.getPlanStructure(planKey)) {
            is ApiResult.Success -> {
                val order = com.workflow.orchestrator.bamboo.service.BambooPlanJobOrder.fromConfig(r.data)
                jobOrderCache = planKey to order
                order
            }
            is ApiResult.Error -> {
                log.warn("[Bamboo:Monitor] getPlanStructure($planKey) failed: ${r.message} — keeping API job order")
                emptyMap()
            }
        }
    }

    open fun startPolling(planKey: String, branch: String, intervalMs: Long = 30_000) {
        log.info("[Bamboo:Monitor] Starting polling for planKey=$planKey, branch=$branch, intervalMs=$intervalMs")
        synchronized(pollerLock) {
            stopPollerLocked()
            autoStoppedTerminal = false
            lastPollTarget = Triple(planKey, branch, intervalMs)
            previousBuildNumber = null
            previousStatus = null
            lastLogFetchedForBuild = null
            launchPollerLocked(planKey, branch, intervalMs)
        }
    }

    open fun stopPolling() {
        log.info("[Bamboo:Monitor] Stopping polling")
        synchronized(pollerLock) {
            autoStoppedTerminal = false
            lastPollTarget = null
            stopPollerLocked()
        }
    }

    /**
     * Observer signal from the Build tab (P0-5). Forwards visibility to the SmartPoller
     * (hidden tab → 4× background interval) and restarts an auto-stopped poller when the
     * tab re-attaches — preserving the dedupe state ([previousBuildNumber] /
     * [lastLogFetchedForBuild]) so the restart emits no duplicate events.
     */
    fun setVisible(isVisible: Boolean) {
        tabVisible.set(isVisible)
        synchronized(pollerLock) {
            val target = lastPollTarget
            if (isVisible && autoStoppedTerminal && target != null) {
                autoStoppedTerminal = false
                log.info("[Bamboo:Monitor] Observer attached — restarting auto-stopped polling for ${target.first}")
                launchPollerLocked(target.first, target.second, target.third)
            } else {
                poller?.setVisible(isVisible)
            }
        }
    }

    /** Creates and starts the SmartPoller. Caller must hold [pollerLock]. */
    private fun launchPollerLocked(planKey: String, branch: String, intervalMs: Long) {
        poller = SmartPoller(
            name = "BuildMonitor",
            baseIntervalMs = intervalMs,
            scope = cs
        ) {
            val prevNum = previousBuildNumber
            val prevStat = previousStatus
            pollOnce(planKey, branch)
            val changed = previousBuildNumber != prevNum || previousStatus != prevStat
            maybeAutoStopAfterPoll()
            changed
        }.also {
            // P0-5: observer-gated — a poller created with no visible Build tab starts in
            // background mode instead of SmartPoller's visible-by-default full rate.
            it.setVisible(tabVisible.get())
            it.start()
        }
    }

    /** Stops the current poller without touching restart state. Caller must hold [pollerLock]. */
    private fun stopPollerLocked() {
        poller?.stop()
        poller = null
    }

    /**
     * P0-5 terminal auto-stop: once the focused build reached a terminal state and no observer
     * is attached, polling stops entirely. Restarts via [setVisible] `(true)` or a focusBuild
     * change (see class KDoc).
     */
    private fun maybeAutoStopAfterPoll() {
        val status = previousStatus
        val terminal = status == BuildStatus.SUCCESS || status == BuildStatus.FAILED
        if (!terminal || tabVisible.get()) return
        synchronized(pollerLock) {
            if (poller == null) return
            log.info("[Bamboo:Monitor] Focused build terminal and Build tab hidden — auto-stopping polling (P0-5)")
            autoStoppedTerminal = true
            stopPollerLocked()
        }
    }

    suspend fun pollOnce(planKey: String, branch: String) = pollMutex.withLock {
        log.info("[Bamboo:Monitor] pollOnce planKey=$planKey, branch=$branch")
        val result = apiClient.getLatestResult(planKey)
        log.info("[Bamboo:Monitor] pollOnce result: ${if (result is ApiResult.Success) "SUCCESS" else "FAILED: $result"}")
        if (result is ApiResult.Success) {
            val dto = result.data
            var buildState = BambooBuildStructureMapper.toBuildState(dto, planKey, branch, jobOrderFor(planKey))

            // Only check for newer builds when the current build is in a terminal state.
            // While the build is still running, there's no point querying for newer builds —
            // it wastes an API call on every 30-second poll cycle.
            val isTerminalState = dto.lifeCycleState == "Finished" ||
                dto.state == "Successful" || dto.state == "Failed"
            if (isTerminalState) {
                val newerBuild = checkForNewerBuild(planKey, dto.buildNumber)
                if (newerBuild != null) {
                    buildState = buildState.copy(newerBuild = newerBuild)
                }
            }

            _stateFlow.value = buildState

            // Only emit event and notify on terminal state changes
            // Skip notifications on first poll (previousBuildNumber == null) to avoid
            // stale "Build Failed" notifications on IDE startup
            val isFirstPoll = previousBuildNumber == null
            val isTerminal = buildState.overallStatus == BuildStatus.SUCCESS ||
                buildState.overallStatus == BuildStatus.FAILED
            val statusChanged = dto.buildNumber != previousBuildNumber ||
                buildState.overallStatus != previousStatus

            if (statusChanged) {
                log.info("[Bamboo:Monitor] Build $planKey-${dto.buildNumber} changed from $previousStatus to ${buildState.overallStatus}")
            }

            if (isTerminal && statusChanged && !isFirstPoll) {
                val eventStatus = when (buildState.overallStatus) {
                    BuildStatus.SUCCESS -> WorkflowEvent.BuildEventStatus.SUCCESS
                    else -> WorkflowEvent.BuildEventStatus.FAILED
                }
                eventBus.emit(
                    WorkflowEvent.BuildFinished(
                        planKey = planKey,
                        buildNumber = dto.buildNumber,
                        status = eventStatus
                    )
                )

                sendBuildNotification(planKey, dto.buildNumber, buildState.overallStatus)
            }

            // Fetch build log and emit BuildLogReady for terminal builds.
            // Emitted on first poll too (unlike BuildFinished) so consumers like
            // Automation tab get the current state when they subscribe.
            if (isTerminal && dto.buildNumber != lastLogFetchedForBuild) {
                // Fetch logs from ALL jobs — the docker tag line can appear in any
                // job's output, not necessarily the first. Plan-level logs are useless
                // (404 or ~101 bytes), so we use job-level result keys.
                val jobResultKeys = buildState.stages
                    .map { it.resultKey }
                    .filter { it.isNotBlank() }
                val resultKey = jobResultKeys.firstOrNull() ?: run {
                    log.debug("[Bamboo:Monitor] No job-level resultKeys in stages, falling back to plan-level: $planKey-${dto.buildNumber}")
                    "${planKey}-${dto.buildNumber}"
                }
                val eventStatus = when (buildState.overallStatus) {
                    BuildStatus.SUCCESS -> WorkflowEvent.BuildEventStatus.SUCCESS
                    else -> WorkflowEvent.BuildEventStatus.FAILED
                }
                val keysToFetch = jobResultKeys.ifEmpty { listOf(resultKey) }
                val logParts = mutableListOf<String>()
                var allFetchesSucceeded = true
                for (key in keysToFetch) {
                    try {
                        when (val logResult = apiClient.getBuildLog(key)) {
                            is ApiResult.Success -> {
                                if (logResult.data.isNotBlank()) logParts.add(logResult.data)
                            }
                            is ApiResult.Error -> {
                                log.warn("[Bamboo:Monitor] Failed to fetch log for $key: ${logResult.message}")
                                allFetchesSucceeded = false
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("[Bamboo:Monitor] Exception fetching log for $key: ${e.message}")
                        allFetchesSucceeded = false
                    }
                }
                // Only mark this build as fetched when every job log returned Success.
                // A partial fetch may be missing the publish job's "Unique Docker Tag"
                // line (Bamboo flips a build to Successful slightly before all logs are
                // flushed to disk) — the next poll must retry so the cache is overwritten
                // with the complete log.
                if (allFetchesSucceeded) {
                    lastLogFetchedForBuild = dto.buildNumber
                }
                log.info("[Bamboo:Monitor] Fetched ${logParts.size}/${keysToFetch.size} job logs for build $planKey-${dto.buildNumber} (allSucceeded=$allFetchesSucceeded)")
                // planKey passed to startPolling is already the chain key (the resolved
                // branch-plan key after autoDetectPlan) — set chainKey = planKey so the
                // BuildLogCache is keyed by chain, not by the master plan key.
                val logEvent = WorkflowEvent.BuildLogReady(
                    planKey = planKey,
                    buildNumber = dto.buildNumber,
                    resultKey = resultKey,
                    status = eventStatus,
                    logText = logParts.joinToString("\n"),
                    chainKey = planKey,
                )
                // Cache before emit so a subscriber that mounts mid-emit and immediately
                // queries the cache still sees a value.
                buildLogCache.put(logEvent)
                eventBus.emit(logEvent)
            }

            previousBuildNumber = dto.buildNumber
            previousStatus = buildState.overallStatus
        } else {
            log.warn("[Bamboo:Monitor] Poll failed for planKey=$planKey, branch=$branch: $result")
        }
    }

    private fun sendBuildNotification(planKey: String, buildNumber: Int, status: BuildStatus) {
        val ns = notificationService ?: return
        when (status) {
            BuildStatus.SUCCESS -> ns.notifyInfo(
                WorkflowNotificationService.GROUP_BUILD,
                "Build Passed",
                "$planKey #$buildNumber completed successfully"
            )
            BuildStatus.FAILED -> ns.notifyError(
                WorkflowNotificationService.GROUP_BUILD,
                "Build Failed",
                "$planKey #$buildNumber failed. Click to view details."
            )
            else -> {} // No notification for non-terminal states
        }
    }

    /**
     * Check if there's a build with a higher build number that is running or queued.
     * Returns null if no newer build exists.
     */
    private suspend fun checkForNewerBuild(planKey: String, currentBuildNumber: Int): NewerBuild? {
        return try {
            val result = apiClient.getRunningAndQueuedBuilds(planKey)
            if (result is ApiResult.Success) {
                val newer = result.data
                    .filter { it.buildNumber > currentBuildNumber }
                    .maxByOrNull { it.buildNumber }
                if (newer != null) {
                    log.info("[Bamboo:Monitor] Newer build detected: #${newer.buildNumber} (${newer.lifeCycleState})")
                    NewerBuild(
                        buildNumber = newer.buildNumber,
                        status = BuildStatus.fromBambooState(newer.state, newer.lifeCycleState)
                    )
                } else null
            } else null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.debug("[Bamboo:Monitor] Failed to check for newer builds: ${e.message}")
            null
        }
    }

    companion object {
        fun getInstance(project: Project): BuildMonitorService {
            return project.getService(BuildMonitorService::class.java)
        }
    }
}
