package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.model.sonar.CoverageData
import com.workflow.orchestrator.core.model.sonar.QualityCondition
import com.workflow.orchestrator.core.model.sonar.QualityGateData
import com.workflow.orchestrator.core.model.sonar.SonarAnalysisTaskData
import com.workflow.orchestrator.core.model.sonar.SonarIssueData
import com.workflow.orchestrator.core.model.sonar.SonarProjectData
import com.workflow.orchestrator.core.services.SonarService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.sonar.api.SonarApiClient

/**
 * Unified SonarQube service implementation used by both UI panels and AI agent.
 *
 * Wraps the existing [SonarApiClient] and maps its responses to shared
 * domain models ([SonarIssueData], [QualityGateData], [CoverageData])
 * with LLM-optimized text summaries.
 */
@Service(Service.Level.PROJECT)
class SonarServiceImpl(private val project: Project) : SonarService {

    private val log = Logger.getInstance(SonarServiceImpl::class.java)
    private val credentialStore = CredentialStore()
    private val settings get() = PluginSettings.getInstance(project)

    @Volatile private var cachedClient: SonarApiClient? = null
    @Volatile private var cachedBaseUrl: String? = null

    private val client: SonarApiClient?
        get() {
            val url = settings.connections.sonarUrl.orEmpty().trimEnd('/')
            if (url.isBlank()) return null
            if (url != cachedBaseUrl || cachedClient == null) {
                cachedBaseUrl = url
                cachedClient = SonarApiClient(
                    baseUrl = url,
                    tokenProvider = { credentialStore.getToken(ServiceType.SONARQUBE) },
                    connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
                    readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
                )
            }
            return cachedClient
        }

    override suspend fun getIssues(
        projectKey: String,
        filePath: String?
    ): ToolResult<List<SonarIssueData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "SonarQube not configured. Cannot fetch issues.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getIssues(projectKey, filePath = filePath)) {
            is ApiResult.Success -> {
                val issues = result.data.map { dto ->
                    SonarIssueData(
                        key = dto.key,
                        rule = dto.rule,
                        severity = dto.severity,
                        message = dto.message,
                        component = dto.component,
                        line = dto.textRange?.startLine,
                        status = "OPEN",
                        type = dto.type
                    )
                }

                val bySeverity = issues.groupBy { it.severity }
                val summary = buildString {
                    append("${issues.size} open issues for $projectKey")
                    if (filePath != null) append(" (file: ${filePath.substringAfterLast("/")})")
                    if (bySeverity.isNotEmpty()) {
                        append("\n")
                        val counts = listOf("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO")
                            .mapNotNull { sev ->
                                bySeverity[sev]?.let { "$sev: ${it.size}" }
                            }
                        append(counts.joinToString(" | "))
                    }
                    // Show first 3 issues in summary for LLM context
                    if (issues.isNotEmpty()) {
                        append("\nTop issues:")
                        issues.take(3).forEach { issue ->
                            val lineInfo = issue.line?.let { ":$it" } ?: ""
                            val file = issue.component.substringAfterLast(":")
                            append("\n  [${issue.severity}] ${issue.message} ($file$lineInfo)")
                        }
                        if (issues.size > 3) append("\n  ... and ${issues.size - 3} more")
                    }
                }

                ToolResult.success(data = issues, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch issues for $projectKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching SonarQube issues for $projectKey: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED ->
                            "Check your SonarQube token in Settings."
                        com.workflow.orchestrator.core.model.ErrorType.NOT_FOUND ->
                            "Verify the project key is correct."
                        else -> "Check SonarQube connection in Settings."
                    }
                )
            }
        }
    }

    override suspend fun getQualityGateStatus(projectKey: String): ToolResult<QualityGateData> {
        val api = client ?: return ToolResult(
            data = QualityGateData(status = "ERROR"),
            summary = "SonarQube not configured. Cannot fetch quality gate status.",
            isError = true,
            hint = "Set up SonarQube connection in Settings."
        )

        return when (val result = api.getQualityGateStatus(projectKey)) {
            is ApiResult.Success -> {
                val gate = result.data
                val conditions = gate.conditions.map { c ->
                    QualityCondition(
                        metric = c.metricKey,
                        operator = c.comparator,
                        value = c.actualValue,
                        status = c.status
                    )
                }
                val data = QualityGateData(
                    status = gate.status,
                    conditions = conditions
                )

                val failedConditions = conditions.filter { it.status == "ERROR" }
                val summary = buildString {
                    append("Quality Gate: ${data.status}")
                    if (failedConditions.isNotEmpty()) {
                        append(" (${failedConditions.size} failed)")
                        failedConditions.forEach { c ->
                            append("\n  FAILED: ${c.metric} = ${c.value}")
                        }
                    }
                }

                ToolResult.success(data = data, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to get quality gate for $projectKey: ${result.message}")
                ToolResult(
                    data = QualityGateData(status = "ERROR"),
                    summary = "Error fetching quality gate for $projectKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and project key."
                )
            }
        }
    }

    override suspend fun getCoverage(projectKey: String): ToolResult<CoverageData> {
        val api = client ?: return ToolResult(
            data = CoverageData(
                lineCoverage = 0.0, branchCoverage = 0.0,
                totalLines = 0, coveredLines = 0
            ),
            summary = "SonarQube not configured. Cannot fetch coverage.",
            isError = true,
            hint = "Set up SonarQube connection in Settings."
        )

        // Fetch project-level measures for coverage metrics
        val metricKeys = "coverage,branch_coverage,lines_to_cover,uncovered_lines"
        return when (val result = api.getMeasures(projectKey, metricKeys = metricKeys)) {
            is ApiResult.Success -> {
                // Aggregate measures across all components
                var totalLinesToCover = 0
                var totalUncoveredLines = 0
                var lineCoverage = 0.0
                var branchCoverage = 0.0

                // For project-level, we look at the first component or aggregate
                for (comp in result.data) {
                    for (measure in comp.measures) {
                        when (measure.metric) {
                            "coverage" -> lineCoverage = measure.value.toDoubleOrNull() ?: 0.0
                            "branch_coverage" -> branchCoverage = measure.value.toDoubleOrNull() ?: 0.0
                            "lines_to_cover" -> totalLinesToCover += measure.value.toIntOrNull() ?: 0
                            "uncovered_lines" -> totalUncoveredLines += measure.value.toIntOrNull() ?: 0
                        }
                    }
                }

                val coveredLines = totalLinesToCover - totalUncoveredLines
                val data = CoverageData(
                    lineCoverage = lineCoverage,
                    branchCoverage = branchCoverage,
                    totalLines = totalLinesToCover,
                    coveredLines = coveredLines
                )

                val summary = buildString {
                    append("Coverage for $projectKey")
                    append("\nLine: ${"%.1f".format(lineCoverage)}% | Branch: ${"%.1f".format(branchCoverage)}%")
                    append("\nCovered: $coveredLines / $totalLinesToCover lines")
                }

                ToolResult.success(data = data, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to get coverage for $projectKey: ${result.message}")
                ToolResult(
                    data = CoverageData(
                        lineCoverage = 0.0, branchCoverage = 0.0,
                        totalLines = 0, coveredLines = 0
                    ),
                    summary = "Error fetching coverage for $projectKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and project key."
                )
            }
        }
    }

    override suspend fun searchProjects(query: String): ToolResult<List<SonarProjectData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "SonarQube not configured. Cannot search projects.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.searchProjects(query)) {
            is ApiResult.Success -> {
                val projects = result.data.map { dto ->
                    SonarProjectData(key = dto.key, name = dto.name)
                }
                ToolResult.success(
                    data = projects,
                    summary = "${projects.size} project(s) found for '$query'"
                )
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to search projects for '$query': ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error searching SonarQube projects: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and token."
                )
            }
        }
    }

    override suspend fun getAnalysisTasks(projectKey: String): ToolResult<List<SonarAnalysisTaskData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "SonarQube not configured. Cannot fetch analysis tasks.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getAnalysisTasks(projectKey)) {
            is ApiResult.Success -> {
                val tasks = result.data.map { dto ->
                    SonarAnalysisTaskData(
                        id = dto.id,
                        status = dto.status,
                        branch = dto.branch,
                        errorMessage = dto.errorMessage,
                        executionTimeMs = dto.executionTimeMs
                    )
                }

                val summary = buildString {
                    append("${tasks.size} recent analysis tasks for $projectKey")
                    val byStatus = tasks.groupBy { it.status }
                    if (byStatus.isNotEmpty()) {
                        append("\n")
                        append(byStatus.entries.joinToString(" | ") { "${it.key}: ${it.value.size}" })
                    }
                    val failed = tasks.filter { it.status == "FAILED" }
                    if (failed.isNotEmpty()) {
                        append("\nFailed analyses:")
                        failed.take(3).forEach { t ->
                            val branchInfo = t.branch?.let { " ($it)" } ?: ""
                            append("\n  ${t.id}$branchInfo: ${t.errorMessage ?: "No error message"}")
                        }
                    }
                }

                ToolResult.success(data = tasks, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch analysis tasks for $projectKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching analysis tasks for $projectKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and project key."
                )
            }
        }
    }

    override suspend fun testConnection(): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "SonarQube not configured.",
            isError = true,
            hint = "Set SonarQube URL and token in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.validateConnection()) {
            is ApiResult.Success -> {
                if (result.data) {
                    ToolResult.success(Unit, "SonarQube connection successful.")
                } else {
                    ToolResult(
                        data = Unit,
                        summary = "SonarQube authentication failed.",
                        isError = true,
                        hint = "Check your SonarQube token."
                    )
                }
            }
            is ApiResult.Error -> {
                ToolResult(
                    data = Unit,
                    summary = "SonarQube connection failed: ${result.message}",
                    isError = true,
                    hint = "Check URL and token in Settings."
                )
            }
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): SonarServiceImpl =
            project.getService(SonarService::class.java) as SonarServiceImpl
    }
}
