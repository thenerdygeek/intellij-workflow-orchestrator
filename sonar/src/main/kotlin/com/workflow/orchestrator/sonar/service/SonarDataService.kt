package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
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

    /** Testable core — accepts explicit dependencies. Fetches both overall + new code data. */
    internal suspend fun refreshWith(client: SonarApiClient, projectKey: String, branch: String) {
        // Fetch overall + new code issues + branches in parallel
        val overallIssuesDeferred = scope.async { client.getIssues(projectKey, branch) }
        val newCodeIssuesDeferred = scope.async { client.getIssues(projectKey, branch, inNewCodePeriod = true) }
        val branchesDeferred = scope.async { client.getBranches(projectKey) }

        val gateResult = client.getQualityGateStatus(projectKey, branch)
        val metricKeys = settings.state.sonarMetricKeys.orEmpty()
        val measuresResult = client.getMeasures(projectKey, branch, metricKeys)

        val overallIssuesResult = overallIssuesDeferred.await()
        val newCodeIssuesResult = newCodeIssuesDeferred.await()
        val branchesResult = branchesDeferred.await()

        val qualityGate = when (gateResult) {
            is ApiResult.Success -> mapQualityGate(gateResult.data)
            is ApiResult.Error -> QualityGateState(QualityGateStatus.NONE, emptyList())
        }

        val issues = when (overallIssuesResult) {
            is ApiResult.Success -> IssueMapper.mapIssues(overallIssuesResult.data, projectKey)
            is ApiResult.Error -> _stateFlow.value.issues
        }

        val newCodeIssues = when (newCodeIssuesResult) {
            is ApiResult.Success -> IssueMapper.mapIssues(newCodeIssuesResult.data, projectKey)
            is ApiResult.Error -> emptyList()
        }

        val fileCoverage = when (measuresResult) {
            is ApiResult.Success -> CoverageMapper.mapMeasures(measuresResult.data)
            is ApiResult.Error -> _stateFlow.value.fileCoverage
        }

        val overallCoverage = calculateOverallCoverage(fileCoverage)

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
            currentBranchAnalysisDate = currentBranchAnalysisDate
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
                passed = cond.status == "OK"
            )
        }
        return QualityGateState(status, conditions)
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

    companion object {
        fun getInstance(project: Project): SonarDataService {
            return project.getService(SonarDataService::class.java)
        }
    }
}
