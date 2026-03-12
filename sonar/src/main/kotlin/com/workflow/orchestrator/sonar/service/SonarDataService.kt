package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
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

    private val _stateFlow = MutableStateFlow(SonarState.EMPTY)
    val stateFlow: StateFlow<SonarState> = _stateFlow.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previousGateStatus: QualityGateStatus? = null

    private val settings get() = PluginSettings.getInstance(project)

    private val apiClient: SonarApiClient? get() {
        val url = settings.state.sonarUrl.orEmpty().trimEnd('/')
        if (url.isBlank()) return null
        val credentialStore = CredentialStore()
        return SonarApiClient(
            baseUrl = url,
            tokenProvider = { credentialStore.getToken(ServiceType.SONARQUBE) },
            connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
            readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
        )
    }

    private val currentBranch: String get() {
        val repos = GitRepositoryManager.getInstance(project).repositories
        return repos.firstOrNull()?.currentBranchName ?: "main"
    }

    fun refresh() {
        val client = apiClient ?: return
        val projectKey = settings.state.sonarProjectKey.orEmpty()
        if (projectKey.isBlank()) return
        scope.launch { refreshWith(client, projectKey, currentBranch) }
    }

    /** Testable core — accepts explicit dependencies. */
    internal suspend fun refreshWith(client: SonarApiClient, projectKey: String, branch: String) {
        val gateResult = client.getQualityGateStatus(projectKey, branch)
        val issuesResult = client.getIssues(projectKey, branch)
        val measuresResult = client.getMeasures(projectKey, branch)

        val qualityGate = when (gateResult) {
            is ApiResult.Success -> mapQualityGate(gateResult.data)
            is ApiResult.Error -> QualityGateState(QualityGateStatus.NONE, emptyList())
        }

        val issues = when (issuesResult) {
            is ApiResult.Success -> IssueMapper.mapIssues(issuesResult.data, projectKey)
            is ApiResult.Error -> _stateFlow.value.issues
        }

        val fileCoverage = when (measuresResult) {
            is ApiResult.Success -> CoverageMapper.mapMeasures(measuresResult.data)
            is ApiResult.Error -> _stateFlow.value.fileCoverage
        }

        val overallCoverage = calculateOverallCoverage(fileCoverage)

        val newState = SonarState(
            projectKey = projectKey,
            branch = branch,
            qualityGate = qualityGate,
            issues = issues,
            fileCoverage = fileCoverage,
            overallCoverage = overallCoverage,
            lastUpdated = Instant.now()
        )

        _stateFlow.value = newState

        // Fire notification if quality gate status changed
        if (qualityGate.status != QualityGateStatus.NONE) {
            if (previousGateStatus != null && previousGateStatus != qualityGate.status) {
                notifyGateTransition(qualityGate.status == QualityGateStatus.PASSED, projectKey)
            }
            if (previousGateStatus == null) {
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

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): SonarDataService {
            return project.getService(SonarDataService::class.java)
        }
    }
}
