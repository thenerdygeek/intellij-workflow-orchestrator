package com.workflow.orchestrator.sonar.service

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.SonarMetricKey
import com.workflow.orchestrator.core.model.sonar.SecurityHotspotData
import com.workflow.orchestrator.sonar.model.*
import com.workflow.orchestrator.sonar.util.SonarRatingUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SonarDataService(
    private val project: Project,
    // F-14: platform-injected scope (2024.1+ @Service pattern). The IntelliJ
    // Platform owns this scope's lifecycle and cancels it on service teardown,
    // so we no longer allocate (or cancel) a CoroutineScope ourselves. Mirrors
    // the precedent in core HealthCheckService.
    private val cs: CoroutineScope,
) : Disposable {

    private val log = Logger.getInstance(SonarDataService::class.java)
    private val _stateFlow = MutableStateFlow(SonarState.EMPTY)
    val stateFlow: StateFlow<SonarState> = _stateFlow.asStateFlow()

    @Volatile private var previousGateStatus: QualityGateStatus? = null

    private val settings get() = PluginSettings.getInstance(project)

    private val credentialStore = CredentialStore()

    // F-4: guard the check-then-act on cachedApiClient with a Mutex.
    // The getter is called from suspend context (getLineCoverage, refreshForBranch),
    // so a coroutine Mutex is correct (non-blocking for other coroutines).
    private val apiClientMutex = Mutex()
    @Volatile private var cachedApiClient: SonarApiClient? = null
    @Volatile private var cachedSonarUrl: String? = null

    // F-10: top-level refresh job tracked so a newer refresh can cancel a prior one's
    // in-flight HTTP children (which run under structured coroutineScope inside refreshWith).
    // SONAR-CLE-2: refreshDebounceJob was a redundant alias always assigned the same Job —
    // removed; activeRefreshJob covers both the debounce delay and in-flight HTTP children.
    @Volatile private var activeRefreshJob: Job? = null

    /**
     * Cache of per-file line coverage data, keyed by `"projectKey\u0000relativePath"`.
     * The projectKey prefix prevents cross-module collisions on multi-repo projects
     * where the same relative path (e.g. `src/main/java/App.java`) can exist in two
     * different Sonar projects and return different coverage results. Pre-fix: keyed
     * only by relativePath, so repo_2's coverage data would leak into repo_1's display
     * if the same file path existed in both.
     */
    val lineCoverageCache = ConcurrentHashMap<String, Map<Int, LineCoverageStatus>>()

    /**
     * SONAR-COR-6: Single-flight guard for getLineCoverage. Keyed by the same composite key as
     * lineCoverageCache. A concurrent caller that arrives while a fetch is in-flight awaits the
     * same Deferred rather than firing a duplicate HTTP request.
     */
    private val lineCoverageInFlight = ConcurrentHashMap<String, Deferred<Map<Int, LineCoverageStatus>>>()

    private fun cacheKey(projectKey: String, relativePath: String): String =
        "$projectKey\u0000$relativePath"

    /**
     * F-4: Returns the cached [SonarApiClient], creating a new one when the configured URL
     * has changed. The check-then-act is serialized via [apiClientMutex] so two concurrent
     * callers cannot each create a new client and abandon one of the OkHttp connection pools.
     * When the URL changes, the old client is closed (dispatcher + pool evicted) before the
     * new one is stored.
     */
    private suspend fun resolveApiClient(): SonarApiClient? {
        val url = settings.connections.sonarUrl.orEmpty().trimEnd('/')
        if (url.isBlank()) return null
        return apiClientMutex.withLock {
            if (url != cachedSonarUrl || cachedApiClient == null) {
                // Close the old client's OkHttp resources before replacing it.
                cachedApiClient?.close()
                cachedSonarUrl = url
                val timeouts = com.workflow.orchestrator.core.http.HttpClientFactory.timeoutsFromSettings(project)
                cachedApiClient = SonarApiClient(
                    baseUrl = url,
                    tokenProvider = { credentialStore.getToken(ServiceType.SONARQUBE) },
                    connectTimeoutSeconds = timeouts.connectSeconds,
                    readTimeoutSeconds = timeouts.readSeconds
                )
            }
            cachedApiClient
        }
    }

    init {
        subscribeToQualityScopeFlow()
    }

    /**
     * Subscribe to [WorkflowContextService.state] and refresh Sonar data whenever
     * [focusQualityScope] changes. This replaces the former legacy [EventBus] subscription
     * that listened for [WorkflowEvent.PrSelected], [WorkflowEvent.BranchChanged], and
     * [WorkflowEvent.BuildFinished].
     *
     * Migration rationale:
     * - [WorkflowContextService] is the canonical cross-tab state store (Phase 5). Listening
     *   to the flow is the correct pattern — the panel already does this at lines 326-345.
     * - The [WorkflowEvent.PrSelected] and [WorkflowEvent.BranchChanged] handlers were
     *   parallel-and-redundant: [WorkflowContextService] already updates [focusQualityScope]
     *   in response to the same events via its own listeners.
     * - The [WorkflowEvent.BuildFinished] handler has been intentionally removed. Reasoning:
     *   (1) [WorkflowContextService] does not update [focusQualityScope] on BuildFinished,
     *   so adding a targeted event subscription would re-introduce the legacy EventBus
     *   dependency just for a transient cache-clear; (2) Sonar analysis pipelines are async —
     *   Sonar data is not yet updated at the moment BuildFinished fires, so an immediate
     *   refresh returns stale data anyway; (3) per project directive "better empty state than
     *   wrong data", faulty transient refreshes are actively harmful. The user can click
     *   Refresh manually or wait for the next focusQualityScope change.
     */
    private fun subscribeToQualityScopeFlow() {
        cs.launch {
            WorkflowContextService.getInstance(project).state
                .map { it.focusQualityScope }
                .distinctUntilChanged()
                .collect { qualityScope ->
                    if (qualityScope != null) {
                        val branch = qualityScope.branchName?.takeIf { it.isNotBlank() }
                        if (branch != null) {
                            log.info(
                                "[Sonar:DataService] focusQualityScope changed — " +
                                "refreshing for projectKey=${qualityScope.sonarProjectKey}, branchName=$branch"
                            )
                            clearLineCoverageCache()
                            refreshForBranch(branch, qualityScope.sonarProjectKey)
                        } else {
                            log.info(
                                "[Sonar:DataService] focusQualityScope has blank branchName — clearing state"
                            )
                            clearLineCoverageCache()
                            _stateFlow.value = SonarState.EMPTY
                        }
                    } else {
                        // No focused PR or no Sonar key configured — clear stale state.
                        // Better to show empty than to keep showing data for a previous scope.
                        log.info("[Sonar:DataService] focusQualityScope cleared — resetting to empty state")
                        clearLineCoverageCache()
                        _stateFlow.value = SonarState.EMPTY
                    }
                }
        }
    }

    /**
     * Refresh Sonar data for a specific branch AND project key. Called by the
     * [focusQualityScope] flow subscription when a new scope is emitted, and by
     * [QualityDashboardPanel] for user-initiated refreshes (repo selector, manual Refresh).
     *
     * The [projectKey] is required — callers always have the Sonar project key from the
     * [com.workflow.orchestrator.core.model.workflow.QualityScope]. The former single-arg
     * overload that fell back to [PluginSettings.state.sonarProjectKey] has been removed:
     * that path was only reachable from the legacy EventBus BranchChanged handler which has
     * been deleted. Requiring the key avoids silent fallback to the wrong project on
     * multi-repo setups.
     */
    fun refreshForBranch(branch: String, projectKey: String) {
        if (projectKey.isBlank()) return
        // F-10: cancel any in-flight HTTP children from a prior refresh.
        // activeRefreshJob wraps both the debounce delay and the coroutineScope inside
        // refreshWith, so cancelling it propagates to all in-flight children (F-5 fix).
        activeRefreshJob?.cancel()
        val job = cs.launch {
            delay(500)
            val client = resolveApiClient() ?: return@launch
            refreshWith(client, projectKey, branch)
        }
        activeRefreshJob = job
    }

    /**
     * Switch between new code and overall mode.
     * No API call — just flips the cached view. EDT-safe (updates state flow only).
     * Persists the choice to PluginSettings so it survives IDE restarts.
     */
    fun setNewCodeMode(newCode: Boolean) {
        _stateFlow.value = _stateFlow.value.copy(newCodeMode = newCode)
        // 1 = Overall, 2 = NewCode. 0 means "unset, follow branch heuristic".
        settings.state.sonarPreferredCodeMode = if (newCode) 2 else 1
        // Editor surfaces (annotator, line markers) cache state per daemon
        // pass. Without an explicit restart, squiggles + gutter marks lag
        // the dashboard toggle until the user types or scrolls — split-brain
        // between the dashboard's filtered count and the editor's stale one.
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    /**
     * Fetch line-level coverage for a specific file from SonarQube's source lines API.
     * Converts the local file path to a SonarQube component key and calls getSourceLines().
     *
     * @param relativePath the file path relative to the OWNING REPO's root (NOT the aggregator
     *   project root) — e.g. for a file at `repo_2/src/main/kotlin/App.kt`, pass
     *   `src/main/kotlin/App.kt`. Caller is expected to compute this with the owning repo's
     *   localVcsRootPath as the base, not `project.basePath`.
     * @param projectKey the owning repo's Sonar project key. When null, falls back to the
     *   scalar `settings.state.sonarProjectKey` for single-repo projects. Required explicitly
     *   on multi-repo setups so repo_2's gutter markers don't query repo_1's Sonar project.
     * @param branch the file's owning repo's git branch. When null (e.g. the file's
     *   owning [git4idea.repo.GitRepository] couldn't be resolved), returns an empty
     *   map (no coverage) rather than guessing a default branch. Callers that have per-action
     *   context (the file's owning repo) MUST pass branch explicitly — querying
     *   Sonar for the wrong branch returns inconsistent coverage data on multi-repo
     *   projects where the editor and the inspected file can sit on different repos.
     * @return map of line number to coverage status, or empty map on failure
     */
    suspend fun getLineCoverage(
        relativePath: String,
        projectKey: String? = null,
        branch: String? = null,
    ): Map<Int, LineCoverageStatus> {
        val effectiveKey = projectKey?.takeIf { it.isNotBlank() }
            ?: settings.state.sonarProjectKey.orEmpty()
        if (effectiveKey.isBlank()) return emptyMap()

        val key = cacheKey(effectiveKey, relativePath)
        // Return from cache if available
        lineCoverageCache[key]?.let { return it }

        val client = resolveApiClient() ?: return emptyMap()

        // SonarQube component key = projectKey:relativePath
        val componentKey = "$effectiveKey:$relativePath"
        val effectiveBranch = branch?.takeIf { it.isNotBlank() } ?: run {
            // SONAR-COR-3: do NOT guess "develop" -- the wrong default branch (most
            // repos use main/master) returns 404/empty and blanks all gutter markers.
            log.debug("[Sonar:LineCoverage] No branch resolved for '$componentKey' -- skipping coverage lookup")
            return emptyMap()
        }

        // SONAR-COR-6: single-flight deduplication — register a Deferred in the in-flight map
        // BEFORE starting the HTTP request. A concurrent caller that arrives while the fetch is
        // running will find the existing Deferred and await it instead of issuing a duplicate call.
        // Use cs.async so the Deferred is created in the service scope (not a child coroutineScope
        // that would wait for it to complete before returning the reference).
        log.info("[Sonar:LineCoverage] Fetching line coverage for '$componentKey' branch='$effectiveBranch'")

        val deferred = cs.async {
            when (val result = client.getSourceLines(componentKey, branch = effectiveBranch)) {
                is ApiResult.Success -> {
                    val statuses = CoverageMapper.mapLineStatuses(result.data)
                    lineCoverageCache[key] = statuses
                    log.info("[Sonar:LineCoverage] Cached ${statuses.size} line statuses for '$relativePath' (projectKey='$effectiveKey')")
                    statuses
                }
                is ApiResult.Error -> {
                    log.warn("[Sonar:LineCoverage] Failed to fetch line coverage for '$componentKey': ${result.message}")
                    emptyMap<Int, LineCoverageStatus>()
                }
            }
        }
        // Only register if no concurrent caller beat us to it; if one did, await its Deferred.
        val winner = lineCoverageInFlight.putIfAbsent(key, deferred) ?: deferred
        if (winner !== deferred) {
            deferred.cancel()
        }
        return try {
            winner.await()
        } catch (_: Exception) {
            emptyMap()
        } finally {
            lineCoverageInFlight.remove(key, winner)
        }
    }

    /** Expose the composite cache key so the marker provider can look up without re-deriving. */
    fun lineCoverageCacheLookup(relativePath: String, projectKey: String): Map<Int, LineCoverageStatus>? {
        if (projectKey.isBlank()) return null
        return lineCoverageCache[cacheKey(projectKey, relativePath)]
    }

    /**
     * Fetch line coverage asynchronously and trigger a re-render when done.
     * Called from the EDT by [CoverageLineMarkerProvider] when line coverage is not yet cached.
     */
    fun fetchLineCoverageAsync(
        relativePath: String,
        projectKey: String? = null,
        branch: String? = null,
        onComplete: () -> Unit,
    ) {
        cs.launch {
            getLineCoverage(relativePath, projectKey, branch)
            onComplete()
        }
    }

    /**
     * Clear the line coverage cache. Called when branch changes or build finishes
     * to ensure stale coverage data is not displayed.
     */
    fun clearLineCoverageCache() {
        val size = lineCoverageCache.size
        lineCoverageCache.clear()
        if (size > 0) {
            log.info("[Sonar:LineCoverage] Cleared line coverage cache ($size entries)")
        }
    }

    /** Testable core — accepts explicit dependencies. Fetches both overall + new code data. */
    internal suspend fun refreshWith(client: SonarApiClient, projectKey: String, branch: String) {
        // F-5: use coroutineScope {} instead of scope.async {} so all HTTP children are
        // structured-concurrency-scoped to this suspend call. When the caller (refreshForBranch's
        // tracked job) is cancelled, coroutineScope propagates cancellation to every child
        // immediately — preventing stale-branch writes to _stateFlow.
        coroutineScope {
        // Fetch overall + new code issues + branches + CE tasks in parallel.
        // /api/new_code_periods/show is intentionally NOT called — it requires
        // Administer Project permission and 403s on most tokens. The new-code
        // period mode + parameter we need is already on the gate response's
        // `period` block (carried by SonarGatePeriodDto), which any token with
        // Browse permission can read.
        val overallIssuesDeferred = async { client.getIssuesWithPaging(projectKey, branch) }
        val newCodeIssuesDeferred = async { client.getIssuesWithPaging(projectKey, branch, inNewCodePeriod = true) }
        val branchesDeferred = async { client.getBranches(projectKey) }
        val ceTasksDeferred = async {
            try { client.getAnalysisTasks(projectKey) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                log.info("[Sonar:CE] CE activity not available (may require admin permission)")
                null
            }
        }
        val projectHealthDeferred = async { client.getProjectMeasures(projectKey, branch) }
        val gateDeferred = async { client.getQualityGateStatus(projectKey, branch) }
        val hotspotsDeferred = async {
            try { client.getSecurityHotspots(projectKey, branch) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                log.info("[Sonar:Hotspots] Security hotspots fetch failed")
                null
            }
        }
        val measuresDeferred = async { client.getMeasures(projectKey, branch) }

        val overallIssuesResult = overallIssuesDeferred.await()
        val newCodeIssuesResult = newCodeIssuesDeferred.await()
        val branchesResult = branchesDeferred.await()
        val ceTasksResult = ceTasksDeferred.await()
        val projectHealthResult = projectHealthDeferred.await()
        val gateResult = gateDeferred.await()
        val hotspotsResult = hotspotsDeferred.await()
        val measuresResult = measuresDeferred.await()

        val qualityGate = when (gateResult) {
            is ApiResult.Success -> mapQualityGate(gateResult.data)
            is ApiResult.Error -> QualityGateState(QualityGateStatus.NONE, emptyList())
        }

        val issues = when (overallIssuesResult) {
            is ApiResult.Success -> IssueMapper.mapIssues(overallIssuesResult.data.issues, projectKey)
            is ApiResult.Error -> _stateFlow.value.issues
        }
        val totalIssueCount = when (overallIssuesResult) {
            is ApiResult.Success -> overallIssuesResult.data.paging.total
            is ApiResult.Error -> null
        }

        val newCodeIssues = when (newCodeIssuesResult) {
            is ApiResult.Success -> IssueMapper.mapIssues(newCodeIssuesResult.data.issues, projectKey)
            is ApiResult.Error -> emptyList()
        }
        val totalNewCodeIssueCount = when (newCodeIssuesResult) {
            is ApiResult.Success -> newCodeIssuesResult.data.paging.total
            is ApiResult.Error -> null
        }

        val fileCoverage = when (measuresResult) {
            is ApiResult.Success -> CoverageMapper.mapMeasures(measuresResult.data, projectKey)
            is ApiResult.Error -> _stateFlow.value.fileCoverage
        }

        // Prefer project-level coverage from getProjectMeasures() (weighted by SonarQube)
        // over unweighted file-level average
        val projectHealth = when (projectHealthResult) {
            is ApiResult.Success -> mapProjectHealth(projectHealthResult.data)
            is ApiResult.Error -> {
                log.warn("[Sonar:Health] Failed to fetch project health metrics: ${projectHealthResult.message}")
                ProjectHealthMetrics()
            }
        }
        val overallCoverage = if (projectHealth.lineCoverage != null) {
            CoverageMetrics(
                projectHealth.lineCoverage,
                projectHealth.branchCoverage ?: 0.0
            )
        } else {
            calculateOverallCoverage(fileCoverage)
        }

        // Build new-code coverage from the same measures response (new_* fields)
        val newCodeFileCoverage = fileCoverage
            .filter { (_, data) -> data.newLinesToCover != null && data.newLinesToCover > 0 }
        val newCodeOverallCoverage = calculateNewCodeCoverage(newCodeFileCoverage)

        // Count issues by type
        val overallCounts = countIssues(issues)
        val newCodeCounts = countIssues(newCodeIssues)

        // Map branches
        val mappedBranches = when (branchesResult) {
            is ApiResult.Success -> branchesResult.data.map { dto ->
                SonarBranch(
                    name = dto.name,
                    isMain = dto.isMain,
                    type = dto.type,
                    qualityGateStatus = dto.status?.qualityGateStatus,
                    bugs = dto.status?.bugs,
                    vulnerabilities = dto.status?.vulnerabilities,
                    codeSmells = dto.status?.codeSmells,
                    analysisDate = dto.analysisDate
                )
            }.also { branches ->
                log.info("[Sonar:Branches] ${branches.size} branches analyzed:")
                branches.forEach { b ->
                    log.info("[Sonar:Branches]   ${b.name} (main=${b.isMain}) — gate=${b.qualityGateStatus ?: "N/A"}, analyzed=${b.analysisDate ?: "never"}")
                }
            }
            is ApiResult.Error -> {
                log.warn("[Sonar:Branches] Failed to fetch branches: ${branchesResult.message}")
                emptyList()
            }
        }

        val currentBranchInfo = mappedBranches.find { it.name == branch }
        val currentBranchAnalyzed = currentBranchInfo != null && currentBranchInfo.analysisDate != null
        val currentBranchAnalysisDate = currentBranchInfo?.analysisDate

        // Map CE analysis tasks (optional — requires Administer Project permission).
        // FORBIDDEN is captured separately so the UI can surface a "permission
        // required" hint instead of a silent empty "Last analysis" indicator.
        var analysisHistoryForbidden = false
        val recentAnalyses = when (ceTasksResult) {
            is ApiResult.Success -> ceTasksResult.data.map { dto ->
                SonarAnalysisTask(
                    id = dto.id,
                    status = dto.status,
                    branch = dto.branch,
                    submittedAt = dto.submittedAt,
                    executedAt = dto.executedAt,
                    executionTimeMs = dto.executionTimeMs,
                    errorMessage = dto.errorMessage
                )
            }.also { tasks ->
                log.info("[Sonar:CE] ${tasks.size} recent analysis tasks fetched")
                tasks.firstOrNull()?.let { t ->
                    log.info("[Sonar:CE] Latest: status=${t.status}, branch=${t.branch ?: "N/A"}, time=${t.executionTimeMs ?: 0}ms")
                }
            }
            is ApiResult.Error -> {
                if (ceTasksResult.type == ErrorType.FORBIDDEN) {
                    analysisHistoryForbidden = true
                    log.info("[Sonar:CE] /api/ce/activity 403 — token lacks Administer Project permission")
                } else {
                    log.warn("[Sonar:CE] Failed to fetch analysis tasks: ${ceTasksResult.message}")
                }
                emptyList()
            }
            null -> emptyList()
        }

        val lastAnalysisForBranch = recentAnalyses.firstOrNull { it.branch == branch }

        // New code period: read from the gate response's `period` block. The
        // dedicated /api/new_code_periods/show endpoint requires admin and
        // 403s on most tokens; the gate response carries the same mode +
        // parameter and is fetched with Browse permission.
        val newCodePeriod = (gateResult as? ApiResult.Success)?.data?.period
            ?.takeIf { it.mode.isNotBlank() }
            ?.let { gatePeriod ->
                NewCodePeriod(
                    type = gatePeriod.mode,
                    value = gatePeriod.parameter,
                    inherited = false  // Gate response doesn't expose this
                ).also { ncp ->
                    log.info("[Sonar:CE] New code period from gate: type=${ncp.type}, value=${ncp.value}")
                }
            }

        // Map security hotspots. Sonar 25.x+ ships hotspots at all editions
        // including Community Build; pre-25.x Community returned 404 here.
        val securityHotspots = when (hotspotsResult) {
            is ApiResult.Success -> hotspotsResult.data.hotspots.map { dto ->
                SecurityHotspotData(
                    key = dto.key,
                    message = dto.message,
                    component = dto.component,
                    line = dto.line,
                    securityCategory = dto.securityCategory,
                    probability = dto.vulnerabilityProbability,
                    status = dto.status,
                    resolution = dto.resolution
                )
            }
            is ApiResult.Error -> {
                log.warn("[Sonar:Hotspots] Failed to fetch security hotspots: ${hotspotsResult.message}")
                emptyList()
            }
            null -> emptyList()
        }

        val newState = SonarState(
            projectKey = projectKey,
            branch = branch,
            qualityGate = qualityGate,
            issues = issues,
            fileCoverage = fileCoverage,
            overallCoverage = overallCoverage,
            lastUpdated = Instant.now(),
            // Mode resolution priority:
            //  1. Explicit user choice persisted in settings (1=Overall, 2=NewCode)
            //  2. Branch-isMain heuristic: main → Overall, non-main → NewCode
            //     (per Sonar's Web UI: New Code tab is the default user view on
            //     feature branches because that's where their PR delta lives)
            //  3. In-session toggle preserved (when settings is unset and we're
            //     in a refresh after the user already toggled this session)
            newCodeMode = run {
                val prevWasInitial = _stateFlow.value.projectKey != projectKey
                    || _stateFlow.value.branch != branch
                val persisted = when (settings.state.sonarPreferredCodeMode) {
                    1 -> false
                    2 -> true
                    else -> null
                }
                when {
                    persisted != null -> persisted
                    prevWasInitial -> {
                        val isMainBranch = mappedBranches.find { it.name == branch }?.isMain == true
                        !isMainBranch
                    }
                    else -> _stateFlow.value.newCodeMode
                }
            },
            newCodeIssues = newCodeIssues,
            newCodeFileCoverage = newCodeFileCoverage,
            newCodeOverallCoverage = newCodeOverallCoverage,
            newCodeIssueCounts = newCodeCounts,
            overallIssueCounts = overallCounts,
            branches = mappedBranches,
            currentBranchAnalyzed = currentBranchAnalyzed,
            currentBranchAnalysisDate = currentBranchAnalysisDate,
            recentAnalyses = recentAnalyses,
            newCodePeriod = newCodePeriod,
            lastAnalysisForBranch = lastAnalysisForBranch,
            totalIssueCount = totalIssueCount,
            totalNewCodeIssueCount = totalNewCodeIssueCount,
            totalCoverageFileCount = fileCoverage.size,
            projectHealth = projectHealth,
            securityHotspots = securityHotspots,
            analysisHistoryForbidden = analysisHistoryForbidden,
            sonarBaseUrl = settings.connections.sonarUrl.orEmpty().trimEnd('/')
        )

        _stateFlow.value = newState

        // Notify and emit only on terminal status changes.
        // IN_PROGRESS / PENDING / NONE are excluded by the QualityGateStatus.NONE guard.
        if (qualityGate.status != QualityGateStatus.NONE) {
            val passed = qualityGate.status == QualityGateStatus.PASSED

            // UI notification: skip the first load to avoid stale alerts on IDE startup.
            if (previousGateStatus != null && previousGateStatus != qualityGate.status) {
                notifyGateTransition(passed, projectKey)
            }

            // T-B1: emit WorkflowEvent.QualityGateResult so HandoverStateService and
            // HealthCheckService.SonarGateCheck receive real data.
            // Deduplication: emit on first terminal result OR on status transition.
            // Identical repeated results (panel refresh with no gate change) are suppressed.
            val isFirstTerminal = previousGateStatus == null
            val hasChanged = previousGateStatus != qualityGate.status
            if (isFirstTerminal || hasChanged) {
                log.info(
                    "[Sonar:DataService] Emitting QualityGateResult for $projectKey: passed=$passed" +
                    " (previous=${previousGateStatus})"
                )
                project.getService(EventBus::class.java).emit(
                    WorkflowEvent.QualityGateResult(
                        projectKey = projectKey,
                        passed = passed,
                    )
                )
            }

            previousGateStatus = qualityGate.status
        }
        } // end coroutineScope (F-5: structured concurrency for parallel HTTP children)
    }

    private fun notifyGateTransition(passed: Boolean, projectKey: String) {
        val notificationService = WorkflowNotificationService.getInstance(project)
        if (passed) {
            notificationService.notifyInfo(
                WorkflowNotificationService.GROUP_QUALITY,
                "Quality Gate Passed",
                "\u2713 All conditions met for $projectKey"
            )
        } else {
            notificationService.notifyError(
                WorkflowNotificationService.GROUP_QUALITY,
                "Quality Gate Failed",
                "\u2717 Quality gate failed for $projectKey"
            )
        }
    }

    private fun mapQualityGate(dto: com.workflow.orchestrator.sonar.api.dto.SonarQualityGateDto): QualityGateState {
        val status = when (dto.status) {
            "OK" -> QualityGateStatus.PASSED
            "ERROR" -> QualityGateStatus.FAILED
            else -> QualityGateStatus.NONE
        }
        val conditions = dto.conditions.map { cond ->
            GateCondition(
                metric = cond.metricKey,
                comparator = cond.comparator,
                threshold = cond.errorThreshold,
                actualValue = cond.actualValue,
                passed = cond.status == "OK",
                warningThreshold = cond.warningThreshold
            )
        }
        return QualityGateState(status, conditions, caycStatus = dto.caycStatus)
    }

    private fun mapProjectHealth(measures: List<com.workflow.orchestrator.sonar.api.dto.SonarMeasureDto>): ProjectHealthMetrics {
        val byMetric = measures.associateBy { it.metric }
        return ProjectHealthMetrics(
            technicalDebtMinutes = byMetric[SonarMetricKey.SQALE_INDEX]?.value?.toDoubleOrNull()?.toInt() ?: 0,
            maintainabilityRating = SonarRatingUtils.ratingLetter(byMetric[SonarMetricKey.SQALE_RATING]?.value),
            reliabilityRating = SonarRatingUtils.ratingLetter(byMetric[SonarMetricKey.RELIABILITY_RATING]?.value),
            securityRating = SonarRatingUtils.ratingLetter(byMetric[SonarMetricKey.SECURITY_RATING]?.value),
            duplicatedLinesDensity = byMetric[SonarMetricKey.DUPLICATED_LINES_DENSITY]?.value?.toDoubleOrNull() ?: 0.0,
            cognitiveComplexity = byMetric[SonarMetricKey.COGNITIVE_COMPLEXITY]?.value?.toDoubleOrNull()?.toInt() ?: 0,
            lineCoverage = byMetric[SonarMetricKey.COVERAGE]?.value?.toDoubleOrNull(),
            branchCoverage = byMetric[SonarMetricKey.BRANCH_COVERAGE]?.value?.toDoubleOrNull()
        ).also { h ->
            log.info("[Sonar:Health] Maintainability=${h.maintainabilityRating}, Debt=${h.formattedDebt}, " +
                "Reliability=${h.reliabilityRating}, Security=${h.securityRating}, " +
                "Duplication=${h.duplicatedLinesDensity}%, Complexity=${h.cognitiveComplexity}")
        }
    }

    private fun calculateOverallCoverage(fileCoverage: Map<String, FileCoverageData>): CoverageMetrics {
        if (fileCoverage.isEmpty()) return CoverageMetrics(0.0, 0.0)
        val avgLine = fileCoverage.values.map { it.lineCoverage }.average()
        val avgBranch = fileCoverage.values.map { it.branchCoverage }.average()
        return CoverageMetrics(avgLine, avgBranch)
    }

    private fun calculateNewCodeCoverage(newCodeFiles: Map<String, FileCoverageData>): CoverageMetrics {
        if (newCodeFiles.isEmpty()) return CoverageMetrics(0.0, 0.0)
        val avgLine = newCodeFiles.values.mapNotNull { it.newCoverage }.let {
            if (it.isEmpty()) 0.0 else it.average()
        }
        val avgBranch = newCodeFiles.values.mapNotNull { it.newBranchCoverage }.let {
            if (it.isEmpty()) 0.0 else it.average()
        }
        return CoverageMetrics(avgLine, avgBranch)
    }

    private fun countIssues(issues: List<MappedIssue>): IssueCounts {
        return IssueCounts(
            bugs = issues.count { it.type == IssueType.BUG },
            vulnerabilities = issues.count { it.type == IssueType.VULNERABILITY },
            codeSmells = issues.count { it.type == IssueType.CODE_SMELL },
            securityHotspots = issues.count { it.type == IssueType.SECURITY_HOTSPOT }
        )
    }

    override fun dispose() {
        // F-14: the platform-injected `cs` scope is cancelled by the platform on
        // service teardown — we must NOT cancel it ourselves here.
        // Close cached OkHttp resources so threads/sockets are released promptly.
        cachedApiClient?.close()
        cachedApiClient = null
        lineCoverageCache.clear()
        // Clean up per-project state in CoverageLineMarkerProvider (pendingFetches map)
        com.workflow.orchestrator.sonar.ui.CoverageLineMarkerProvider.clearProjectState(project)
    }

    /**
     * Returns the already-cached [SonarApiClient] without creating a new one.
     * Callers that need a guarantee the client is initialized (e.g. agent tool
     * paths) should call [resolveApiClientForSharedUse] instead.
     *
     * F-4 note: [resolveApiClient] is the canonical entry point for all internal
     * code. This non-suspend accessor is kept for [SonarServiceImpl] which cannot
     * be in a suspend context at the property getter level; it gets null when the
     * service has not yet been asked to refresh (safe — callers handle null).
     */
    fun getSharedApiClient(): SonarApiClient? = cachedApiClient

    /**
     * Suspend variant for external callers that need a freshly-resolved client
     * (e.g., agent tools invoked before the first dashboard refresh).
     */
    suspend fun resolveApiClientForSharedUse(): SonarApiClient? = resolveApiClient()

    companion object {
        fun getInstance(project: Project): SonarDataService {
            return project.getService(SonarDataService::class.java)
        }
    }
}
