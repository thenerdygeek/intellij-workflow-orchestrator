package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.model.*
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SonarDataService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(SonarDataService::class.java)
    private val _stateFlow = MutableStateFlow(SonarState.EMPTY)
    val stateFlow: StateFlow<SonarState> = _stateFlow.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previousGateStatus: QualityGateStatus? = null

    private val settings get() = PluginSettings.getInstance(project)

    private val credentialStore = CredentialStore()
    @Volatile private var cachedApiClient: SonarApiClient? = null
    @Volatile private var cachedSonarUrl: String? = null

    private var refreshDebounceJob: Job? = null

    /**
     * Cache of per-file line coverage data, keyed by relative file path.
     * Populated on-demand when files are opened in the editor (via [getLineCoverage]).
     * Cleared on branch change or build completion to force re-fetch.
     */
    val lineCoverageCache = ConcurrentHashMap<String, Map<Int, LineCoverageStatus>>()

    private val apiClient: SonarApiClient? get() {
        val url = settings.connections.sonarUrl.orEmpty().trimEnd('/')
        if (url.isBlank()) return null
        if (url != cachedSonarUrl || cachedApiClient == null) {
            cachedSonarUrl = url
            cachedApiClient = SonarApiClient(
                baseUrl = url,
                tokenProvider = { credentialStore.getToken(ServiceType.SONARQUBE) },
                connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
                readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
            )
        }
        return cachedApiClient
    }

    private val currentBranch: String get() {
        val repos = GitRepositoryManager.getInstance(project).repositories
        return repos.firstOrNull()?.currentBranchName ?: (settings.state.defaultTargetBranch ?: "develop")
    }

    init {
        subscribeToEvents()
    }

    /**
     * Subscribe to cross-module events that affect the Quality tab's branch context:
     * - [WorkflowEvent.PrSelected]: refresh with the PR's source branch
     * - [WorkflowEvent.BranchChanged]: refresh with the newly checked-out branch
     * - [WorkflowEvent.BuildFinished]: refresh with current branch (build may trigger new Sonar analysis)
     */
    private fun subscribeToEvents() {
        val eventBus = project.getService(EventBus::class.java) ?: return
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is WorkflowEvent.PrSelected -> {
                        log.info("[Sonar:Events] PR selected (id=${event.prId}), refreshing for branch '${event.fromBranch}'")
                        refreshForBranch(event.fromBranch)
                    }
                    is WorkflowEvent.BranchChanged -> {
                        log.info("[Sonar:Events] Branch changed to '${event.branchName}', refreshing quality data")
                        clearLineCoverageCache()
                        refreshForBranch(event.branchName)
                    }
                    is WorkflowEvent.BuildFinished -> {
                        log.info("[Sonar:Events] Build finished (${event.planKey}#${event.buildNumber}), refreshing quality data for current branch")
                        clearLineCoverageCache()
                        refreshForBranch(currentBranch)
                    }
                    else -> { /* not relevant to sonar */ }
                }
            }
        }
    }

    /**
     * Refresh Sonar data for a specific branch. Called when cross-module events
     * indicate the branch context has changed (PR selected, git branch switch, build finished).
     */
    fun refreshForBranch(branch: String) {
        refreshDebounceJob?.cancel()
        refreshDebounceJob = scope.launch {
            delay(500) // Coalesce rapid events
            val client = apiClient ?: return@launch
            val projectKey = settings.state.sonarProjectKey.orEmpty()
            if (projectKey.isBlank()) return@launch
            refreshWith(client, projectKey, branch)
        }
    }

    fun refresh() {
        val client = apiClient ?: return
        val projectKey = settings.state.sonarProjectKey.orEmpty()
        if (projectKey.isBlank()) return
        scope.launch { refreshWith(client, projectKey, currentBranch) }
    }

    /**
     * Switch between new code and overall mode.
     * No API call — just flips the cached view. EDT-safe (updates state flow only).
     */
    fun setNewCodeMode(newCode: Boolean) {
        _stateFlow.value = _stateFlow.value.copy(newCodeMode = newCode)
    }

    /**
     * Fetch line-level coverage for a specific file from SonarQube's source lines API.
     * Converts the local file path to a SonarQube component key and calls getSourceLines().
     *
     * @param relativePath the file path relative to the project root (e.g., "src/main/kotlin/com/app/Service.kt")
     * @return map of line number to coverage status, or empty map on failure
     */
    suspend fun getLineCoverage(relativePath: String): Map<Int, LineCoverageStatus> {
        // Return from cache if available
        lineCoverageCache[relativePath]?.let { return it }

        val client = apiClient ?: return emptyMap()
        val projectKey = settings.state.sonarProjectKey.orEmpty()
        if (projectKey.isBlank()) return emptyMap()

        // SonarQube component key = projectKey:relativePath
        val componentKey = "$projectKey:$relativePath"
        val branch = currentBranch

        log.info("[Sonar:LineCoverage] Fetching line coverage for '$componentKey' branch='$branch'")

        return when (val result = client.getSourceLines(componentKey)) {
            is ApiResult.Success -> {
                val statuses = CoverageMapper.mapLineStatuses(result.data)
                lineCoverageCache[relativePath] = statuses
                log.info("[Sonar:LineCoverage] Cached ${statuses.size} line statuses for '$relativePath'")
                statuses
            }
            is ApiResult.Error -> {
                log.warn("[Sonar:LineCoverage] Failed to fetch line coverage for '$componentKey': ${result.message}")
                emptyMap()
            }
        }
    }

    /**
     * Fetch line coverage asynchronously and trigger a re-render when done.
     * Called from the EDT by [CoverageLineMarkerProvider] when line coverage is not yet cached.
     */
    fun fetchLineCoverageAsync(relativePath: String, onComplete: () -> Unit) {
        scope.launch {
            getLineCoverage(relativePath)
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
        // Fetch overall + new code issues + branches + CE tasks in parallel
        val overallIssuesDeferred = scope.async { client.getIssuesWithPaging(projectKey, branch) }
        val newCodeIssuesDeferred = scope.async { client.getIssuesWithPaging(projectKey, branch, inNewCodePeriod = true) }
        val branchesDeferred = scope.async { client.getBranches(projectKey) }
        val ceTasksDeferred = scope.async { client.getAnalysisTasks(projectKey) }
        val newCodePeriodDeferred = scope.async {
            try { client.getNewCodePeriod(projectKey, branch) }
            catch (e: Exception) {
                log.warn("[Sonar:CE] Failed to fetch new code period: ${e.message}")
                null
            }
        }
        val projectHealthDeferred = scope.async { client.getProjectMeasures(projectKey, branch) }
        val gateDeferred = scope.async { client.getQualityGateStatus(projectKey, branch) }
        val metricKeys = settings.state.sonarMetricKeys.orEmpty()
        val measuresDeferred = scope.async { client.getMeasures(projectKey, branch, metricKeys) }

        val overallIssuesResult = overallIssuesDeferred.await()
        val newCodeIssuesResult = newCodeIssuesDeferred.await()
        val branchesResult = branchesDeferred.await()
        val ceTasksResult = ceTasksDeferred.await()
        val newCodePeriodResult = newCodePeriodDeferred.await()
        val projectHealthResult = projectHealthDeferred.await()
        val gateResult = gateDeferred.await()
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
            is ApiResult.Success -> CoverageMapper.mapMeasures(measuresResult.data)
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

        // Map CE analysis tasks
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
                log.warn("[Sonar:CE] Failed to fetch analysis tasks: ${ceTasksResult.message}")
                emptyList()
            }
        }

        val lastAnalysisForBranch = recentAnalyses.firstOrNull { it.branch == branch }

        // Map new code period
        val newCodePeriod = when (newCodePeriodResult) {
            is ApiResult.Success -> {
                val dto = newCodePeriodResult.data
                NewCodePeriod(
                    type = dto.type,
                    value = dto.value.ifBlank { dto.effectiveValue },
                    inherited = dto.inherited
                ).also { ncp ->
                    log.info("[Sonar:CE] New code period: type=${ncp.type}, value=${ncp.value}, inherited=${ncp.inherited}")
                }
            }
            is ApiResult.Error -> {
                log.info("[Sonar:CE] New code period not available: ${newCodePeriodResult.message}")
                null
            }
            null -> null
        }

        val newState = SonarState(
            projectKey = projectKey,
            branch = branch,
            qualityGate = qualityGate,
            issues = issues,
            fileCoverage = fileCoverage,
            overallCoverage = overallCoverage,
            lastUpdated = Instant.now(),
            newCodeMode = _stateFlow.value.newCodeMode,
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
            projectHealth = projectHealth
        )

        _stateFlow.value = newState

        // Notify only on status CHANGES — skip the first load to avoid stale notifications on IDE startup
        if (qualityGate.status != QualityGateStatus.NONE) {
            if (previousGateStatus != null && previousGateStatus != qualityGate.status) {
                notifyGateTransition(qualityGate.status == QualityGateStatus.PASSED, projectKey)
            }
            previousGateStatus = qualityGate.status
        }
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
        return QualityGateState(status, conditions)
    }

    private fun mapProjectHealth(measures: List<com.workflow.orchestrator.sonar.api.dto.SonarMeasureDto>): ProjectHealthMetrics {
        val byMetric = measures.associateBy { it.metric }
        // SonarQube ratings are stored as 1.0=A, 2.0=B, 3.0=C, 4.0=D, 5.0=E
        fun ratingLetter(value: String?): String {
            val num = value?.toDoubleOrNull()?.toInt() ?: return ""
            return when (num) {
                1 -> "A"; 2 -> "B"; 3 -> "C"; 4 -> "D"; 5 -> "E"
                else -> ""
            }
        }
        return ProjectHealthMetrics(
            technicalDebtMinutes = byMetric["sqale_index"]?.value?.toDoubleOrNull()?.toInt() ?: 0,
            maintainabilityRating = ratingLetter(byMetric["sqale_rating"]?.value),
            reliabilityRating = ratingLetter(byMetric["reliability_rating"]?.value),
            securityRating = ratingLetter(byMetric["security_rating"]?.value),
            duplicatedLinesDensity = byMetric["duplicated_lines_density"]?.value?.toDoubleOrNull() ?: 0.0,
            cognitiveComplexity = byMetric["cognitive_complexity"]?.value?.toDoubleOrNull()?.toInt() ?: 0,
            lineCoverage = byMetric["coverage"]?.value?.toDoubleOrNull(),
            branchCoverage = byMetric["branch_coverage"]?.value?.toDoubleOrNull()
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
        scope.cancel()
    }

    /**
     * Exposes the lazily-created [SonarApiClient] so other services (e.g. [SonarServiceImpl])
     * can reuse the same client instance instead of duplicating credentials + caching logic.
     */
    fun getSharedApiClient(): SonarApiClient? = apiClient

    companion object {
        fun getInstance(project: Project): SonarDataService {
            return project.getService(SonarDataService::class.java)
        }
    }
}
