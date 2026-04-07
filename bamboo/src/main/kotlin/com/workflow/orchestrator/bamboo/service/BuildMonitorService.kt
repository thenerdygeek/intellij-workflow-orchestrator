package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.model.BuildState
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.model.NewerBuild
import com.workflow.orchestrator.bamboo.model.StageState
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.polling.SmartPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

@Service(Service.Level.PROJECT)
class BuildMonitorService : Disposable {

    private val log = Logger.getInstance(BuildMonitorService::class.java)

    private var _project: Project? = null
    private var _notificationServiceResolved = false

    // Backing fields — set directly by test constructor, or lazily resolved from project
    private var _apiClient: BambooApiClient? = null
    private var _eventBus: EventBus? = null
    private var _notificationService: WorkflowNotificationService? = null

    private val apiClient: BambooApiClient get() = _apiClient ?: run {
        val p = _project!!
        val settings = PluginSettings.getInstance(p)
        val credentialStore = CredentialStore()
        val timeouts = com.workflow.orchestrator.core.http.HttpClientFactory.timeoutsFromSettings(p)
        BambooApiClient(
            baseUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
            connectTimeoutSeconds = timeouts.connectSeconds,
            readTimeoutSeconds = timeouts.readSeconds
        ).also { _apiClient = it }
    }

    private val eventBus: EventBus get() = _eventBus ?: _project!!.getService(EventBus::class.java).also { _eventBus = it }
    private val scope: CoroutineScope
    private val notificationService: WorkflowNotificationService? get() {
        if (!_notificationServiceResolved) {
            _notificationService = _project?.let { WorkflowNotificationService.getInstance(it) }
            _notificationServiceResolved = true
        }
        return _notificationService
    }

    /** Project service constructor — used by IntelliJ DI. Deps are lazy-inited on first use. */
    constructor(project: Project) {
        this._project = project
        this.scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    /** Test constructor — allows injecting mocks. */
    constructor(
        apiClient: BambooApiClient,
        eventBus: EventBus,
        scope: CoroutineScope,
        notificationService: WorkflowNotificationService? = null
    ) {
        this._apiClient = apiClient
        this._eventBus = eventBus
        this.scope = scope
        this._notificationService = notificationService
        this._notificationServiceResolved = true
    }

    private val _stateFlow = MutableStateFlow<BuildState?>(null)
    val stateFlow: StateFlow<BuildState?> = _stateFlow.asStateFlow()

    private var previousBuildNumber: Int? = null
    private var previousStatus: BuildStatus? = null
    private var poller: SmartPoller? = null

    fun startPolling(planKey: String, branch: String, intervalMs: Long = 30_000) {
        log.info("[Bamboo:Monitor] Starting polling for planKey=$planKey, branch=$branch, intervalMs=$intervalMs")
        stopPolling()
        previousBuildNumber = null
        previousStatus = null
        poller = SmartPoller(
            name = "BuildMonitor",
            baseIntervalMs = intervalMs,
            scope = scope
        ) {
            val prevNum = previousBuildNumber
            val prevStat = previousStatus
            pollOnce(planKey, branch)
            previousBuildNumber != prevNum || previousStatus != prevStat
        }.also { it.start() }
    }

    fun stopPolling() {
        log.info("[Bamboo:Monitor] Stopping polling")
        poller?.stop()
        poller = null
    }

    fun setVisible(isVisible: Boolean) {
        poller?.setVisible(isVisible)
    }

    fun switchBranch(planKey: String, newBranch: String, intervalMs: Long = 30_000) {
        log.info("[Bamboo:Monitor] Switching branch to '$newBranch' for planKey=$planKey")
        _stateFlow.value = null
        startPolling(planKey, newBranch, intervalMs)
    }

    override fun dispose() {
        stopPolling()
        scope.cancel()
    }

    suspend fun pollOnce(planKey: String, branch: String) {
        log.info("[Bamboo:Monitor] pollOnce planKey=$planKey, branch=$branch")
        val result = apiClient.getLatestResult(planKey, branch)
        log.info("[Bamboo:Monitor] pollOnce result: ${if (result is ApiResult.Success) "SUCCESS" else "FAILED: $result"}")
        if (result is ApiResult.Success) {
            val dto = result.data
            var buildState = mapToBuildState(dto, planKey, branch)

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

    private fun mapToBuildState(dto: BambooResultDto, planKey: String, branch: String): BuildState {
        // Flatten stages into jobs — each job is a StageState with its parent stage name
        val jobs = mutableListOf<StageState>()
        for (stage in dto.stages.stage) {
            val jobResults = stage.results.result
            if (jobResults.isNotEmpty()) {
                // Stage has jobs — show individual jobs
                for (job in jobResults) {
                    val jobName = job.plan?.shortName ?: job.buildResultKey
                    jobs.add(StageState(
                        name = jobName,
                        status = BuildStatus.fromBambooState(job.state, job.lifeCycleState),
                        manual = stage.manual,
                        durationMs = job.buildDurationInSeconds * 1000,
                        stageName = stage.name,
                        resultKey = job.buildResultKey
                    ))
                }
            } else {
                // Stage has no expanded jobs — show stage itself
                jobs.add(StageState(
                    name = stage.name,
                    status = BuildStatus.fromBambooState(stage.state, stage.lifeCycleState),
                    manual = stage.manual,
                    durationMs = stage.buildDurationInSeconds * 1000,
                    stageName = stage.name,
                    resultKey = ""
                ))
            }
        }
        return BuildState(
            planKey = planKey,
            branch = branch,
            buildNumber = dto.buildNumber,
            stages = jobs,
            overallStatus = BuildStatus.fromBambooState(dto.state, dto.lifeCycleState),
            lastUpdated = Instant.now()
        )
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
            if (e is kotlinx.coroutines.CancellationException) throw e
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
