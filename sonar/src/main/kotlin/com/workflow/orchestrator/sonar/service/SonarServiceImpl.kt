package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.sonar.CoverageData
import com.workflow.orchestrator.core.model.sonar.PagedIssuesData
import com.workflow.orchestrator.core.model.sonar.ProjectMeasuresData
import com.workflow.orchestrator.core.model.sonar.QualityCondition
import com.workflow.orchestrator.core.model.sonar.QualityGateData
import com.workflow.orchestrator.core.model.sonar.SonarAnalysisTaskData
import com.workflow.orchestrator.core.model.sonar.SonarBranchData
import com.workflow.orchestrator.core.model.sonar.SonarIssueData
import com.workflow.orchestrator.core.model.sonar.SonarProjectData
import com.workflow.orchestrator.core.model.sonar.SecurityHotspotData
import com.workflow.orchestrator.core.model.sonar.DuplicationData
import com.workflow.orchestrator.core.model.sonar.DuplicationBlock
import com.workflow.orchestrator.core.model.sonar.DuplicationFragment
import com.workflow.orchestrator.core.model.sonar.SourceLineData
import com.workflow.orchestrator.core.model.sonar.SonarRuleData
import com.workflow.orchestrator.core.model.sonar.BranchQualityReportData
import com.workflow.orchestrator.core.model.sonar.HotspotDetailData
import com.workflow.orchestrator.core.model.sonar.IssueFacet
import com.workflow.orchestrator.core.model.sonar.IssueFacetValue
import com.workflow.orchestrator.core.model.sonar.IssueFacetsData
import com.workflow.orchestrator.core.model.sonar.SonarCurrentUserData
import com.workflow.orchestrator.core.model.sonar.SonarQualityGateEntry
import com.workflow.orchestrator.core.model.sonar.SonarQualityGateListData
import com.workflow.orchestrator.core.model.sonar.IssueSummary
import com.workflow.orchestrator.core.model.sonar.NewCodeCoverageSummary
import com.workflow.orchestrator.core.model.sonar.FileQualityReport
import com.workflow.orchestrator.core.model.sonar.LineRange
import com.workflow.orchestrator.core.model.sonar.SonarFileComponent
import com.workflow.orchestrator.core.services.SonarService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.SonarMetricKey
import com.workflow.orchestrator.sonar.util.SonarRatingUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

    /** Test-only seam: when set, used instead of the shared client so unit tests need no DI. */
    internal var testClient: SonarApiClient? = null

    private val client: SonarApiClient?
        get() = testClient ?: SonarDataService.getInstance(project).getSharedApiClient()

    // Session cache for the issues-action preflight (`/api/components/show`). Keyed on
    // (sonarUrl, projectKey) so a mid-session URL change in settings doesn't return
    // stale "exists" verdicts for the new server. Only successful Sonar responses are
    // cached — transient errors (NETWORK_ERROR / 5xx) are not, so a flaky probe can
    // recover on the next call without waiting out the TTL.
    private val componentExistsCache = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Pair<Long, Boolean>>()

    private suspend fun componentExistsCached(api: SonarApiClient, projectKey: String): ApiResult<Boolean> {
        val baseUrl = ConnectionSettings.getInstance().state.sonarUrl
        val key = baseUrl to projectKey
        val now = System.currentTimeMillis()
        componentExistsCache[key]?.let { (expiryMs, exists) ->
            if (now < expiryMs) return ApiResult.Success(exists)
        }
        return when (val result = api.componentExists(projectKey)) {
            is ApiResult.Success -> {
                componentExistsCache[key] = (now + COMPONENT_EXISTS_TTL_MS) to result.data
                result
            }
            is ApiResult.Error -> result
        }
    }

    override suspend fun getIssues(
        projectKey: String,
        filePath: String?,
        branch: String?,
        repoName: String?,
        inNewCodePeriod: Boolean
    ): ToolResult<List<SonarIssueData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "SonarQube not configured. Cannot fetch issues.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        // SonarQube's /api/issues/search returns 200 with an empty array when the
        // project key doesn't exist, which is indistinguishable from "no open
        // issues". Preflight via /api/components/show so a typo surfaces as an
        // explicit error instead of silently masquerading as a clean project.
        // Transient preflight errors (network / 5xx) fall through to the main
        // call so a bad probe doesn't block a healthy fetch.
        when (val precheck = componentExistsCached(api, projectKey)) {
            is ApiResult.Success -> if (!precheck.data) return ToolResult(
                data = emptyList(),
                summary = "SonarQube project '$projectKey' not found.",
                isError = true,
                hint = "Verify the project key. Maven artifact keys like 'group:artifact' often map to plain 'artifact' in SonarQube — try the short form or search via the Quality dashboard."
            )
            is ApiResult.Error -> log.info("[SonarService] componentExists preflight failed (${precheck.type}: ${precheck.message}); proceeding to issues search — a flaky preflight will not block the main fetch")
        }

        return when (val result = api.getIssues(projectKey, branch = branch, filePath = filePath, inNewCodePeriod = inNewCodePeriod)) {
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
                        type = dto.type,
                        cleanCodeAttribute = dto.cleanCodeAttribute,
                        cleanCodeAttributeCategory = dto.cleanCodeAttributeCategory,
                        impacts = dto.impacts.map { com.workflow.orchestrator.core.model.sonar.SonarImpact(it.softwareQuality, it.severity) },
                        issueStatus = dto.issueStatus,
                    )
                }

                val bySeverity = issues.groupBy { it.severity }
                val summary = buildString {
                    append("${issues.size} open issues for $projectKey")
                    if (inNewCodePeriod) append(" (new code only)")
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

    override suspend fun getQualityGateStatus(projectKey: String, branch: String?, repoName: String?): ToolResult<QualityGateData> {
        val api = client ?: return ToolResult(
            data = QualityGateData(status = "ERROR"),
            summary = "SonarQube not configured. Cannot fetch quality gate status.",
            isError = true,
            hint = "Set up SonarQube connection in Settings."
        )

        return when (val result = api.getQualityGateStatus(projectKey, branch = branch)) {
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

    override suspend fun getCoverage(projectKey: String, branch: String?, repoName: String?): ToolResult<CoverageData> {
        val api = client ?: return ToolResult(
            data = CoverageData(
                lineCoverage = 0.0, branchCoverage = 0.0,
                totalLines = 0, coveredLines = 0
            ),
            summary = "SonarQube not configured. Cannot fetch coverage.",
            isError = true,
            hint = "Set up SonarQube connection in Settings."
        )

        // Use project-level measures API (not component_tree) for accurate aggregate coverage
        val metricKeys = SonarMetricKey.csv(
            SonarMetricKey.COVERAGE, SonarMetricKey.BRANCH_COVERAGE,
            SonarMetricKey.LINES_TO_COVER, SonarMetricKey.UNCOVERED_LINES,
        )
        return when (val result = api.getProjectMeasures(projectKey, branch = branch, metricKeys = metricKeys)) {
            is ApiResult.Success -> {
                val measures = result.data.associate { it.metric to it.effectiveValue() }

                val lineCoverage = measures[SonarMetricKey.COVERAGE]?.toDoubleOrNull() ?: 0.0
                val branchCoverage = measures[SonarMetricKey.BRANCH_COVERAGE]?.toDoubleOrNull() ?: 0.0
                val totalLinesToCover = measures[SonarMetricKey.LINES_TO_COVER]?.toIntOrNull() ?: 0
                val uncoveredLines = measures[SonarMetricKey.UNCOVERED_LINES]?.toIntOrNull() ?: 0
                val coveredLines = totalLinesToCover - uncoveredLines

                val data = CoverageData(
                    lineCoverage = lineCoverage,
                    branchCoverage = branchCoverage,
                    totalLines = totalLinesToCover,
                    coveredLines = coveredLines
                )

                val branchLabel = branch?.let { " ($it)" } ?: ""
                val summary = buildString {
                    append("Coverage for $projectKey$branchLabel")
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

    override suspend fun getAnalysisTasks(projectKey: String, repoName: String?): ToolResult<List<SonarAnalysisTaskData>> {
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

    override suspend fun getCeTaskStatus(taskId: String): ToolResult<String> {
        val api = client ?: return ToolResult(
            data = "UNKNOWN",
            summary = "SonarQube not configured. Cannot poll CE task.",
            isError = true
        )
        return when (val result = api.getCeTask(taskId)) {
            is ApiResult.Success -> ToolResult(
                data = result.data.status,
                summary = "CE task $taskId: ${result.data.status}" +
                    (result.data.errorMessage?.let { " — $it" } ?: "")
            )
            is ApiResult.Error -> ToolResult(
                data = "UNKNOWN",
                summary = "Error polling CE task $taskId: ${result.message}",
                isError = true
            )
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

    override suspend fun getBranches(projectKey: String, repoName: String?): ToolResult<List<SonarBranchData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "SonarQube not configured. Cannot fetch branches.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getBranches(projectKey)) {
            is ApiResult.Success -> {
                val branches = result.data.map { dto ->
                    SonarBranchData(
                        name = dto.name,
                        isMain = dto.isMain,
                        type = dto.type,
                        qualityGateStatus = dto.status?.qualityGateStatus?.ifBlank { null }
                    )
                }

                val mainBranch = branches.find { it.isMain }
                val summary = buildString {
                    append("${branches.size} branch(es) for $projectKey")
                    mainBranch?.let { b ->
                        append("\nMain: ${b.name}")
                        b.qualityGateStatus?.let { append(" (QG: $it)") }
                    }
                    val nonMain = branches.filter { !it.isMain }
                    if (nonMain.isNotEmpty()) {
                        append("\nOther: ${nonMain.joinToString(", ") { it.name }}")
                    }
                }

                ToolResult.success(data = branches, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch branches for $projectKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching branches for $projectKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and project key."
                )
            }
        }
    }

    override suspend fun getProjectMeasures(
        projectKey: String,
        branch: String?,
        repoName: String?
    ): ToolResult<ProjectMeasuresData> {
        val api = client ?: return ToolResult(
            data = ProjectMeasuresData(null, null, null, null, null, null, null),
            summary = "SonarQube not configured. Cannot fetch project measures.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        val metricKeys = SonarMetricKey.csv(
            SonarMetricKey.RELIABILITY_RATING, SonarMetricKey.SECURITY_RATING, SonarMetricKey.SQALE_RATING,
            SonarMetricKey.COVERAGE, SonarMetricKey.DUPLICATED_LINES_DENSITY,
            SonarMetricKey.SQALE_DEBT_RATIO, SonarMetricKey.NCLOC,
        )
        return when (val result = api.getProjectMeasures(projectKey, branch, metricKeys)) {
            is ApiResult.Success -> {
                val measures = result.data.associate { it.metric to it.value }

                // SonarQube ratings: 1=A, 2=B, 3=C, 4=D, 5=E. Fall back to the
                // raw value when SonarQube returns something we don't recognise.
                fun ratingToGrade(value: String?): String? =
                    SonarRatingUtils.ratingLetter(value, unknown = value.orEmpty())
                        .ifEmpty { value }

                val data = ProjectMeasuresData(
                    reliability = ratingToGrade(measures[SonarMetricKey.RELIABILITY_RATING]),
                    security = ratingToGrade(measures[SonarMetricKey.SECURITY_RATING]),
                    maintainability = ratingToGrade(measures[SonarMetricKey.SQALE_RATING]),
                    coverage = measures[SonarMetricKey.COVERAGE]?.toDoubleOrNull(),
                    duplications = measures[SonarMetricKey.DUPLICATED_LINES_DENSITY]?.toDoubleOrNull(),
                    technicalDebt = measures[SonarMetricKey.SQALE_DEBT_RATIO],
                    linesOfCode = measures[SonarMetricKey.NCLOC]?.toLongOrNull()
                )

                val branchLabel = branch?.let { " ($it)" } ?: ""
                val summary = buildString {
                    append("Project measures for $projectKey$branchLabel")
                    data.reliability?.let { append("\nReliability: $it") }
                    data.security?.let { append(" | Security: $it") }
                    data.maintainability?.let { append(" | Maintainability: $it") }
                    data.coverage?.let { append("\nCoverage: ${"%.1f".format(it)}%") }
                    data.duplications?.let { append(" | Duplications: ${"%.1f".format(it)}%") }
                    data.linesOfCode?.let { append(" | LoC: $it") }
                }

                ToolResult.success(data = data, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch project measures for $projectKey: ${result.message}")
                ToolResult(
                    data = ProjectMeasuresData(null, null, null, null, null, null, null),
                    summary = "Error fetching project measures for $projectKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and project key."
                )
            }
        }
    }

    override suspend fun getSourceLines(
        componentKey: String,
        from: Int?,
        to: Int?,
        branch: String?,
        repoName: String?
    ): ToolResult<List<SourceLineData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "SonarQube not configured. Cannot fetch source lines.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getSourceLines(componentKey, from, to, branch)) {
            is ApiResult.Success -> {
                val lines = result.data.map { dto ->
                    // Coverage state: a line whose statement ran (lineHits>0) but
                    // has at least one untaken branch is "partially-covered" —
                    // the agent's "write a test for the missing branch" signal.
                    // Plain covered = lineHits>0 with no branches OR all branches taken.
                    SourceLineData(
                        line = dto.line,
                        code = dto.code,
                        coverageStatus = when {
                            dto.lineHits == null -> null
                            dto.lineHits == 0 -> "uncovered"
                            dto.conditions != null && dto.conditions > 0 &&
                                (dto.coveredConditions ?: 0) < dto.conditions -> "partially-covered"
                            else -> "covered"
                        },
                        conditions = dto.conditions,
                        coveredConditions = dto.coveredConditions
                    )
                }

                val covered = lines.count { it.coverageStatus == "covered" }
                val partial = lines.count { it.coverageStatus == "partially-covered" }
                val uncovered = lines.count { it.coverageStatus == "uncovered" }
                val rangeLabel = if (from != null || to != null) " (L${from ?: 1}-${to ?: "end"})" else ""
                val summary = buildString {
                    append("${lines.size} source lines for $componentKey$rangeLabel")
                    if (covered + partial + uncovered > 0) {
                        append("\nCovered: $covered | Partial: $partial | Uncovered: $uncovered")
                    }
                }

                ToolResult.success(data = lines, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch source lines for $componentKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching source lines for $componentKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and component key."
                )
            }
        }
    }

    override suspend fun getIssuesPaged(
        projectKey: String,
        page: Int,
        pageSize: Int,
        branch: String?,
        repoName: String?,
        inNewCodePeriod: Boolean
    ): ToolResult<PagedIssuesData> {
        val api = client ?: return ToolResult(
            data = PagedIssuesData(emptyList(), 0, page, pageSize),
            summary = "SonarQube not configured. Cannot fetch issues.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        // F-15: fetch a single server-side page (one request with &p=&ps=) instead of
        // looping getIssuesWithPaging up to 10 000 issues and slicing one page client-side.
        // paging.total in the response remains the server-authoritative count.
        return when (
            val result = api.getIssuesSinglePage(
                projectKey,
                page = page,
                pageSize = pageSize,
                branch = branch,
                inNewCodePeriod = inNewCodePeriod,
            )
        ) {
            is ApiResult.Success -> {
                val pagedIssues = result.data.issues.map { dto ->
                    SonarIssueData(
                        key = dto.key,
                        rule = dto.rule,
                        severity = dto.severity,
                        message = dto.message,
                        component = dto.component,
                        line = dto.textRange?.startLine,
                        status = dto.status,
                        type = dto.type,
                        cleanCodeAttribute = dto.cleanCodeAttribute,
                        cleanCodeAttributeCategory = dto.cleanCodeAttributeCategory,
                        impacts = dto.impacts.map { com.workflow.orchestrator.core.model.sonar.SonarImpact(it.softwareQuality, it.severity) },
                        issueStatus = dto.issueStatus,
                    )
                }

                val total = result.data.paging.total

                val data = PagedIssuesData(
                    issues = pagedIssues,
                    total = total,
                    page = page,
                    pageSize = pageSize
                )

                val bySeverity = pagedIssues.groupBy { it.severity }
                val summary = buildString {
                    append("$total total issues for $projectKey")
                    if (inNewCodePeriod) append(" (new code only)")
                    append(" (page $page, showing ${pagedIssues.size})")
                    if (bySeverity.isNotEmpty()) {
                        append("\n")
                        val counts = listOf("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO")
                            .mapNotNull { sev ->
                                bySeverity[sev]?.let { "$sev: ${it.size}" }
                            }
                        append(counts.joinToString(" | "))
                    }
                }

                ToolResult.success(data = data, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch paged issues for $projectKey: ${result.message}")
                ToolResult(
                    data = PagedIssuesData(emptyList(), 0, page, pageSize),
                    summary = "Error fetching issues for $projectKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and project key."
                )
            }
        }
    }

    override suspend fun getSecurityHotspots(
        projectKey: String,
        branch: String?,
        repoName: String?
    ): ToolResult<List<SecurityHotspotData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "SonarQube not configured. Cannot fetch security hotspots.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getSecurityHotspots(projectKey, branch)) {
            is ApiResult.Success -> {
                val hotspots = result.data.hotspots.map { dto ->
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

                val byProb = hotspots.groupBy { it.probability }
                val summary = buildString {
                    append("${hotspots.size} security hotspot(s) for $projectKey")
                    if (branch != null) append(" on branch '$branch'")
                    if (byProb.isNotEmpty()) {
                        append("\n")
                        val counts = listOf("HIGH", "MEDIUM", "LOW")
                            .mapNotNull { p -> byProb[p]?.let { "$p: ${it.size}" } }
                        append(counts.joinToString(" | "))
                    }
                    if (hotspots.isNotEmpty()) {
                        append("\n\n")
                        hotspots.take(20).forEach { append("$it\n") }
                        if (hotspots.size > 20) append("... and ${hotspots.size - 20} more")
                    }
                }

                ToolResult.success(data = hotspots, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch security hotspots for $projectKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching security hotspots for $projectKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and project key."
                )
            }
        }
    }

    override suspend fun getDuplications(
        componentKey: String,
        branch: String?,
        repoName: String?
    ): ToolResult<DuplicationData> {
        val api = client ?: return ToolResult(
            data = DuplicationData(emptyList()),
            summary = "SonarQube not configured. Cannot fetch duplications.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getDuplications(componentKey, branch)) {
            is ApiResult.Success -> {
                val fileMap = result.data.files
                val blocks = result.data.duplications.map { dup ->
                    DuplicationBlock(
                        fragments = dup.blocks.map { block ->
                            val fileInfo = fileMap[block.ref]
                            DuplicationFragment(
                                file = fileInfo?.name ?: fileInfo?.key ?: block.ref,
                                startLine = block.from,
                                endLine = block.from + block.size - 1
                            )
                        }
                    )
                }

                val data = DuplicationData(blocks)
                val summary = buildString {
                    append("${blocks.size} duplication group(s) in $componentKey")
                    if (branch != null) append(" on branch '$branch'")
                    if (blocks.isNotEmpty()) {
                        append("\n\n")
                        append(data.toString())
                    }
                }

                ToolResult.success(data = data, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch duplications for $componentKey: ${result.message}")
                ToolResult(
                    data = DuplicationData(emptyList()),
                    summary = "Error fetching duplications for $componentKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and component key."
                )
            }
        }
    }

    override suspend fun getRule(ruleKey: String, repoName: String?): ToolResult<SonarRuleData> {
        val api = client ?: return ToolResult(
            data = SonarRuleData(ruleKey = ruleKey, name = "", description = "", remediation = null),
            summary = "SonarQube not configured. Cannot fetch rule details.",
            isError = true,
            hint = "Configure SonarQube URL and token in Settings > CI/CD."
        )

        return when (val result = api.getRule(ruleKey)) {
            is ApiResult.Success -> {
                val dto = result.data
                // Description fallback chain. Sonar 9.x ships htmlDesc/mdDesc; Sonar
                // 25.x replaced both with descriptionSections[{key, content}] and
                // omits the legacy fields. Order: Markdown (richer) → legacy HTML →
                // 25.x sections joined. Preserves pre-25.x behavior unchanged.
                val description = dto.mdDesc
                    ?: dto.htmlDesc
                    ?: dto.descriptionSections
                        .filter { it.content.isNotBlank() }
                        .joinToString("\n\n") { it.content }
                        .ifBlank { null }
                    ?: ""
                val data = SonarRuleData(
                    ruleKey = dto.key,
                    name = dto.name,
                    description = description,
                    remediation = dto.remFnBaseEffort,
                    tags = dto.tags
                )
                ToolResult.success(data = data, summary = "Rule ${dto.key}: ${dto.name}")
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch rule $ruleKey: ${result.message}")
                ToolResult(
                    data = SonarRuleData(ruleKey = ruleKey, name = "", description = "", remediation = null),
                    summary = "Error fetching rule $ruleKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and token."
                )
            }
        }
    }

    override suspend fun listFileComponents(
        projectKey: String,
        branch: String?,
        repoName: String?
    ): ToolResult<List<SonarFileComponent>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "SonarQube not configured. Cannot list file components.",
            isError = true,
            hint = "Configure SonarQube URL and token in Settings > CI/CD."
        )

        // Cheapest metric — we only care about the component tree itself, not the values.
        return when (val result = api.getMeasures(projectKey, branch, metricKeys = SonarMetricKey.NCLOC)) {
            is ApiResult.Success -> {
                val components = result.data
                    .filter { it.qualifier == "FIL" }
                    .mapNotNull { comp ->
                        val path = comp.path ?: return@mapNotNull null
                        SonarFileComponent(key = comp.key, path = path, name = comp.name)
                    }
                ToolResult.success(
                    data = components,
                    summary = "Resolved ${components.size} file component(s) for project '$projectKey'" +
                        (branch?.let { " on branch '$it'" } ?: "")
                )
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to list file components for $projectKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error listing file components for $projectKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection, project key, and branch name."
                )
            }
        }
    }

    override suspend fun getBranchQualityReport(
        projectKey: String,
        branch: String,
        maxFiles: Int,
        repoName: String?
    ): ToolResult<BranchQualityReportData> {
        val api = client ?: return ToolResult(
            data = BranchQualityReportData(
                branch = branch,
                qualityGate = QualityGateData(status = "ERROR"),
                issueSummary = IssueSummary(0, 0, 0, 0),
                issues = emptyList(),
                securityHotspots = emptyList(),
                coverageSummary = NewCodeCoverageSummary(null, null, 0, 0, null),
                fileReports = emptyList()
            ),
            summary = "SonarQube not configured. Cannot generate branch quality report.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        val newCodeMetrics = SonarMetricKey.csv(
            SonarMetricKey.NEW_UNCOVERED_LINES, SonarMetricKey.NEW_LINE_COVERAGE, SonarMetricKey.NEW_BRANCH_COVERAGE,
            SonarMetricKey.NEW_UNCOVERED_CONDITIONS, SonarMetricKey.NEW_DUPLICATED_LINES,
            SonarMetricKey.NEW_DUPLICATED_LINES_DENSITY,
        )

        val gate: ApiResult<com.workflow.orchestrator.sonar.api.dto.SonarQualityGateDto>
        val issues: ApiResult<List<com.workflow.orchestrator.sonar.api.dto.SonarIssueDto>>
        val hotspots: ApiResult<com.workflow.orchestrator.sonar.api.dto.SonarHotspotSearchResult>
        val fileMeasures: ApiResult<List<com.workflow.orchestrator.sonar.api.dto.SonarMeasureComponentDto>>
        val projectMeasures: ApiResult<List<com.workflow.orchestrator.sonar.api.dto.SonarMeasureDto>>

        // ── Phase 1: parallel project-level fetches (SONAR-ARC-6: projectMeasures moved inside) ─
        val projectNewCodeMetrics = SonarMetricKey.csv(
            SonarMetricKey.NEW_LINE_COVERAGE, SonarMetricKey.NEW_BRANCH_COVERAGE,
            SonarMetricKey.NEW_DUPLICATED_LINES_DENSITY,
            SonarMetricKey.NEW_UNCOVERED_LINES, SonarMetricKey.NEW_UNCOVERED_CONDITIONS,
        )

        coroutineScope {
            val gateDeferred = async { api.getQualityGateStatus(projectKey, branch) }
            val issuesDeferred = async { api.getIssues(projectKey, branch = branch, inNewCodePeriod = true) }
            val hotspotsDeferred = async { api.getSecurityHotspots(projectKey, branch) }
            val measuresDeferred = async { api.getMeasures(projectKey, branch, newCodeMetrics) }
            val projectMeasuresDeferred = async { api.getProjectMeasures(projectKey, branch, projectNewCodeMetrics) }
            gate = gateDeferred.await()
            issues = issuesDeferred.await()
            hotspots = hotspotsDeferred.await()
            fileMeasures = measuresDeferred.await()
            projectMeasures = projectMeasuresDeferred.await()
        }

        // Quality gate: trust the server's authoritative status (for a branch this already IS
        // the new-code gate result). The displayed conditions are filtered to new-code for the
        // report, but recomputing the STATUS from that subset could downgrade a real failure —
        // e.g. a WARN new-code condition would mask an ERROR on a non-new_ condition.
        val qualityGate = when (gate) {
            is ApiResult.Success -> QualityGateData(
                status = gate.data.status,
                conditions = gate.data.conditions
                    .filter { c -> c.metricKey.startsWith("new_") }
                    .map { c ->
                        QualityCondition(metric = c.metricKey, operator = c.comparator, value = c.actualValue, status = c.status)
                    }
            )
            is ApiResult.Error -> QualityGateData(status = "ERROR")
        }

        // Map issues
        val mappedIssues = when (issues) {
            is ApiResult.Success -> issues.data.map { dto ->
                SonarIssueData(
                    key = dto.key, rule = dto.rule, severity = dto.severity,
                    message = dto.message, component = dto.component,
                    line = dto.textRange?.startLine, status = "OPEN", type = dto.type,
                    cleanCodeAttribute = dto.cleanCodeAttribute,
                    cleanCodeAttributeCategory = dto.cleanCodeAttributeCategory,
                    impacts = dto.impacts.map { com.workflow.orchestrator.core.model.sonar.SonarImpact(it.softwareQuality, it.severity) },
                    issueStatus = dto.issueStatus,
                )
            }
            is ApiResult.Error -> emptyList()
        }

        val issueSummary = IssueSummary(
            bugs = mappedIssues.count { it.type == "BUG" },
            vulnerabilities = mappedIssues.count { it.type == "VULNERABILITY" },
            codeSmells = mappedIssues.count { it.type == "CODE_SMELL" },
            total = mappedIssues.size
        )

        // Map security hotspots
        val mappedHotspots = when (hotspots) {
            is ApiResult.Success -> hotspots.data.hotspots.map { dto ->
                SecurityHotspotData(
                    key = dto.key, message = dto.message, component = dto.component,
                    line = dto.line, securityCategory = dto.securityCategory,
                    probability = dto.vulnerabilityProbability, status = dto.status,
                    resolution = dto.resolution
                )
            }
            is ApiResult.Error -> emptyList()
        }

        // ── Phase 2: identify files needing drill-down ──────────────────────
        data class FileMeasureInfo(
            val componentKey: String,
            val filePath: String,
            val newUncoveredLines: Int,
            val newUncoveredConditions: Int,
            val newLineCoverage: Double?,
            val newBranchCoverage: Double?,
            val newDuplicatedLines: Int
        )

        val fileInfos = when (fileMeasures) {
            is ApiResult.Success -> fileMeasures.data.mapNotNull { comp ->
                val m = comp.measures.associate { it.metric to it.effectiveValue() }
                val uncoveredLines = m[SonarMetricKey.NEW_UNCOVERED_LINES]?.toIntOrNull() ?: 0
                val uncoveredConds = m[SonarMetricKey.NEW_UNCOVERED_CONDITIONS]?.toIntOrNull() ?: 0
                val dupLines = m[SonarMetricKey.NEW_DUPLICATED_LINES]?.toIntOrNull() ?: 0
                // Only include files that have something to report
                if (uncoveredLines > 0 || uncoveredConds > 0 || dupLines > 0) {
                    FileMeasureInfo(
                        componentKey = comp.key,
                        filePath = comp.path ?: comp.name,
                        newUncoveredLines = uncoveredLines,
                        newUncoveredConditions = uncoveredConds,
                        newLineCoverage = m[SonarMetricKey.NEW_LINE_COVERAGE]?.toDoubleOrNull(),
                        newBranchCoverage = m[SonarMetricKey.NEW_BRANCH_COVERAGE]?.toDoubleOrNull(),
                        newDuplicatedLines = dupLines
                    )
                } else null
            }.sortedByDescending { it.newUncoveredLines + it.newDuplicatedLines }
            is ApiResult.Error -> emptyList()
        }

        val truncated = fileInfos.size > maxFiles
        val filesToDrill = fileInfos.take(maxFiles)

        // projectMeasures was fetched in Phase 1 (now part of the parallel coroutineScope block).
        // Fetch uncovered counts from project-level API (not file sums) to avoid undercounting
        // when component_tree is paginated (ps=500)
        val projM = when (projectMeasures) {
            is ApiResult.Success -> projectMeasures.data.associate { it.metric to it.effectiveValue() }
            is ApiResult.Error -> emptyMap()
        }

        val coverageSummary = NewCodeCoverageSummary(
            lineCoverage = projM[SonarMetricKey.NEW_LINE_COVERAGE]?.toDoubleOrNull(),
            branchCoverage = projM[SonarMetricKey.NEW_BRANCH_COVERAGE]?.toDoubleOrNull(),
            newUncoveredLines = projM[SonarMetricKey.NEW_UNCOVERED_LINES]?.toIntOrNull() ?: 0,
            newUncoveredConditions = projM[SonarMetricKey.NEW_UNCOVERED_CONDITIONS]?.toIntOrNull() ?: 0,
            duplicatedLinesDensity = projM[SonarMetricKey.NEW_DUPLICATED_LINES_DENSITY]?.toDoubleOrNull()
        )

        // ── Phase 3: per-file drill-down for exact line numbers ─────────────
        val fileReports = if (filesToDrill.isNotEmpty()) {
            coroutineScope {
                filesToDrill.map { info ->
                    async {
                        buildFileReport(
                            api, info.componentKey, info.filePath, branch,
                            FileQualityReportInfo(
                                newUncoveredLines = info.newUncoveredLines,
                                newUncoveredConditions = info.newUncoveredConditions,
                                newLineCoverage = info.newLineCoverage,
                                newBranchCoverage = info.newBranchCoverage,
                                newDuplicatedLines = info.newDuplicatedLines
                            )
                        )
                    }
                }.awaitAll()
            }
        } else {
            emptyList()
        }

        val data = BranchQualityReportData(
            branch = branch,
            qualityGate = qualityGate,
            issueSummary = issueSummary,
            issues = mappedIssues,
            securityHotspots = mappedHotspots,
            coverageSummary = coverageSummary,
            fileReports = fileReports,
            truncatedFiles = truncated
        )

        // ── Build LLM summary ───────────────────────────────────────────────
        val summary = buildBranchQualityReportSummary(data, projectKey, maxFiles)
        return ToolResult.success(data = data, summary = summary)
    }

    private suspend fun buildFileReport(
        api: SonarApiClient,
        componentKey: String,
        filePath: String,
        branch: String,
        fileInfo: FileQualityReportInfo
    ): FileQualityReport {

        // Parallel: source lines (for coverage) + duplications
        val (sourceResult, dupResult) = coroutineScope {
            val src = async { api.getSourceLines(componentKey, branch = branch) }
            val dup = if (fileInfo.newDuplicatedLines > 0) {
                async { api.getDuplications(componentKey, branch) }
            } else null
            Pair(src.await(), dup?.await())
        }

        // Extract uncovered line numbers and uncovered branch line numbers.
        // Filter to new code lines only (isNew=true) when available (SonarQube 9.x+).
        // Falls back to all uncovered lines if isNew is not present in the API response.
        val uncoveredLines = mutableListOf<Int>()
        val uncoveredBranchLines = mutableListOf<Int>()
        when (sourceResult) {
            is ApiResult.Success -> {
                val hasNewCodeInfo = sourceResult.data.any { it.isNew != null }
                for (line in sourceResult.data) {
                    // Skip lines that aren't part of new code (when the field is available)
                    if (hasNewCodeInfo && line.isNew != true) continue

                    if (line.lineHits != null && line.lineHits <= 0) {
                        uncoveredLines.add(line.line)
                    }
                    if (line.conditions != null && line.conditions > 0) {
                        val covered = line.coveredConditions ?: 0
                        if (covered < line.conditions) {
                            uncoveredBranchLines.add(line.line)
                        }
                    }
                }
            }
            is ApiResult.Error -> { /* best-effort — skip this file's line details */ }
        }

        // Extract duplication ranges
        val dupRanges = mutableListOf<LineRange>()
        if (dupResult != null) {
            when (dupResult) {
                is ApiResult.Success -> {
                    val fileMap = dupResult.data.files
                    // Identify THIS file's ref by exact component key only. The previous basename
                    // fallback (name == file.kt) collided across modules, and treating an
                    // unresolved ref as "matches everything" attributed every duplication block in
                    // the response to this file — inflating its ranges. If we can't identify the
                    // file's ref, emit no ranges rather than all.
                    val selfRef = fileMap.entries.find { it.value.key == componentKey }?.key
                    if (selfRef != null) {
                        for (dup in dupResult.data.duplications) {
                            for (block in dup.blocks) {
                                if (block.ref == selfRef) {
                                    dupRanges.add(LineRange(block.from, block.from + block.size - 1))
                                }
                            }
                        }
                    }
                }
                is ApiResult.Error -> { /* best-effort */ }
            }
        }

        return FileQualityReport(
            filePath = filePath,
            lineCoverage = fileInfo.newLineCoverage,
            branchCoverage = fileInfo.newBranchCoverage,
            uncoveredLineNumbers = uncoveredLines.sorted(),
            uncoveredBranchLineNumbers = uncoveredBranchLines.sorted(),
            duplicatedLineRanges = dupRanges.sortedBy { it.startLine }
        )
    }

    /** Type-safe wrapper to pass file info through buildFileReport. */
    private data class FileQualityReportInfo(
        val newUncoveredLines: Int,
        val newUncoveredConditions: Int,
        val newLineCoverage: Double?,
        val newBranchCoverage: Double?,
        val newDuplicatedLines: Int
    )

    private fun buildBranchQualityReportSummary(data: BranchQualityReportData, projectKey: String, maxFiles: Int): String = buildString {
        append("Branch Quality Report: $projectKey (${data.branch}) — New Code\n")
        append("═══════════════════════════════════════════════════\n\n")

        // Quality gate
        append("Quality Gate: ${data.qualityGate.status}")
        val failedConds = data.qualityGate.conditions.filter { it.status == "ERROR" }
        if (failedConds.isNotEmpty()) {
            append(" (${failedConds.size} failed)")
            failedConds.forEach { c -> append("\n  FAILED: ${c.metric} = ${c.value}") }
        }
        append("\n\n")

        // Issue summary
        val s = data.issueSummary
        append("Issues: ${s.total} total")
        if (s.total > 0) {
            val parts = mutableListOf<String>()
            if (s.bugs > 0) parts.add("${s.bugs} bugs")
            if (s.vulnerabilities > 0) parts.add("${s.vulnerabilities} vulnerabilities")
            if (s.codeSmells > 0) parts.add("${s.codeSmells} code smells")
            append(" (${parts.joinToString(", ")})")
        }
        append("\n")

        // Top issues
        if (data.issues.isNotEmpty()) {
            data.issues.take(10).forEach { issue ->
                val file = issue.component.substringAfterLast(':').substringAfterLast('/')
                val loc = issue.line?.let { "$file:$it" } ?: file
                append("  [${issue.severity}/${issue.type}] $loc — ${issue.message.take(100)}\n")
            }
            if (data.issues.size > 10) append("  ... and ${data.issues.size - 10} more\n")
        }
        append("\n")

        // Security hotspots
        if (data.securityHotspots.isNotEmpty()) {
            append("Security Hotspots: ${data.securityHotspots.size}\n")
            data.securityHotspots.take(5).forEach { h ->
                val file = h.component.substringAfterLast(':').substringAfterLast('/')
                val loc = h.line?.let { "$file:$it" } ?: file
                append("  [${h.probability}] $loc — ${h.message.take(100)} (${h.status})\n")
            }
            if (data.securityHotspots.size > 5) append("  ... and ${data.securityHotspots.size - 5} more\n")
            append("\n")
        }

        // Coverage summary
        val cov = data.coverageSummary
        append("Coverage (new code):")
        cov.lineCoverage?.let { append(" Line=${"%.1f".format(it)}%") }
        cov.branchCoverage?.let { append(" | Branch=${"%.1f".format(it)}%") }
        append("\n")
        append("  Uncovered lines: ${cov.newUncoveredLines} | Uncovered conditions: ${cov.newUncoveredConditions}")
        cov.duplicatedLinesDensity?.let { append(" | Duplication: ${"%.1f".format(it)}%") }
        append("\n\n")

        // Per-file details
        if (data.fileReports.isNotEmpty()) {
            append("File Details (${data.fileReports.size} files")
            if (data.truncatedFiles) append(", showing top $maxFiles")
            append("):\n")
            append("───────────────────────────────────────────────────\n")

            for (fr in data.fileReports) {
                val fileName = fr.filePath.substringAfterLast('/')
                append("\n$fileName")
                fr.lineCoverage?.let { append("  Line=${"%.1f".format(it)}%") }
                fr.branchCoverage?.let { append("  Branch=${"%.1f".format(it)}%") }
                append("\n")

                if (fr.uncoveredLineNumbers.isNotEmpty()) {
                    append("  Uncovered lines: ${compactLineNumbers(fr.uncoveredLineNumbers)}\n")
                }
                if (fr.uncoveredBranchLineNumbers.isNotEmpty()) {
                    append("  Uncovered branches: ${compactLineNumbers(fr.uncoveredBranchLineNumbers)}\n")
                }
                if (fr.duplicatedLineRanges.isNotEmpty()) {
                    append("  Duplicated: ${fr.duplicatedLineRanges.joinToString(", ")}\n")
                }
            }
        }

        // Conditional legend: explain the apparent contradiction between a gate ERROR at 0.0
        // and coverage percentage metrics showing 100% when the branch has no new lines.
        // Emitted only when both signals are simultaneously present (empty-new-code case).
        val hasGateZeroCoverageError = data.qualityGate.conditions.any { c ->
            (c.metric.contains(SonarMetricKey.NEW_COVERAGE)) &&
                c.status.equals("ERROR", ignoreCase = true) &&
                (c.value.toDoubleOrNull() ?: 1.0) <= 0.01
        }
        val hasCoveragePctNearHundred = listOf(SonarMetricKey.NEW_LINE_COVERAGE, SonarMetricKey.NEW_BRANCH_COVERAGE).any { key ->
            val v = when (key) {
                SonarMetricKey.NEW_LINE_COVERAGE   -> cov.lineCoverage
                SonarMetricKey.NEW_BRANCH_COVERAGE -> cov.branchCoverage
                else                               -> null
            }
            v != null && v >= 99.0
        }
        if (hasGateZeroCoverageError && hasCoveragePctNearHundred) {
            appendLine()
            appendLine(
                "_Note: Quality Gate evaluates ratio conditions (e.g., new_coverage ≥ 80%) and " +
                "returns 0.0 / ERROR when the branch has no new lines. The coverage percentage " +
                "metrics (new_line_coverage, new_branch_coverage) return 100% in the same " +
                "empty-new-code case (0 of 0 lines covered). Both reflect the same state._"
            )
        }
    }

    /**
     * Compact consecutive line numbers into ranges for readability.
     * e.g. [1,2,3,5,7,8,9] → "1-3, 5, 7-9"
     */
    private fun compactLineNumbers(lines: List<Int>): String {
        if (lines.isEmpty()) return ""
        val sorted = lines.sorted()
        val ranges = mutableListOf<String>()
        var start = sorted[0]
        var end = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] == end + 1) {
                end = sorted[i]
            } else {
                ranges.add(if (start == end) "$start" else "$start-$end")
                start = sorted[i]
                end = sorted[i]
            }
        }
        ranges.add(if (start == end) "$start" else "$start-$end")
        return ranges.joinToString(", ")
    }

    override suspend fun getHotspotDetail(hotspotKey: String, repoName: String?): ToolResult<HotspotDetailData> {
        val api = client ?: return ToolResult(
            data = HotspotDetailData(
                key = hotspotKey, componentKey = "", componentPath = "", projectKey = "",
                ruleKey = "", ruleName = "", securityCategory = "", vulnerabilityProbability = "",
                riskDescription = "", vulnerabilityDescription = "", fixRecommendations = "",
                status = "", resolution = null, line = null, message = "",
                assignee = null, author = null, canChangeStatus = false
            ),
            summary = "SonarQube not configured. Cannot fetch hotspot detail.",
            isError = true,
            hint = "Configure SonarQube URL and token in Settings."
        )

        return when (val result = api.getHotspotDetail(hotspotKey)) {
            is ApiResult.Success -> {
                val dto = result.data
                val data = HotspotDetailData(
                    key = dto.key,
                    componentKey = dto.component.key,
                    componentPath = dto.component.path,
                    projectKey = dto.project.key,
                    ruleKey = dto.rule.key,
                    ruleName = dto.rule.name,
                    securityCategory = dto.rule.securityCategory,
                    vulnerabilityProbability = dto.rule.vulnerabilityProbability,
                    riskDescription = dto.rule.riskDescription,
                    vulnerabilityDescription = dto.rule.vulnerabilityDescription,
                    fixRecommendations = dto.rule.fixRecommendations,
                    status = dto.status,
                    resolution = dto.resolution,
                    line = dto.line,
                    message = dto.message,
                    assignee = dto.assignee,
                    author = dto.author,
                    canChangeStatus = dto.canChangeStatus
                )
                val summary = buildString {
                    append("Hotspot ${data.key}: ${data.ruleKey}")
                    append(" [${data.vulnerabilityProbability}/${data.status}]")
                    if (!data.canChangeStatus) {
                        append(" — token cannot mark hotspot fixed/safe; remediation requires code edit + re-analysis")
                    }
                }
                ToolResult.success(data = data, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch hotspot detail $hotspotKey: ${result.message}")
                ToolResult(
                    data = HotspotDetailData(
                        key = hotspotKey, componentKey = "", componentPath = "", projectKey = "",
                        ruleKey = "", ruleName = "", securityCategory = "", vulnerabilityProbability = "",
                        riskDescription = "", vulnerabilityDescription = "", fixRecommendations = "",
                        status = "", resolution = null, line = null, message = "",
                        assignee = null, author = null, canChangeStatus = false
                    ),
                    summary = "Error fetching hotspot $hotspotKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and the hotspot key."
                )
            }
        }
    }

    override suspend fun getIssueFacets(
        projectKey: String,
        branch: String?,
        inNewCodePeriod: Boolean,
        facets: String,
        repoName: String?
    ): ToolResult<IssueFacetsData> {
        val api = client ?: return ToolResult(
            data = IssueFacetsData(total = 0, facets = emptyList()),
            summary = "SonarQube not configured. Cannot fetch issue facets.",
            isError = true,
            hint = "Configure SonarQube in Settings."
        )

        return when (val result = api.getIssueFacets(projectKey, branch, inNewCodePeriod, facets)) {
            is ApiResult.Success -> {
                val mapped = IssueFacetsData(
                    total = result.data.paging.total,
                    facets = result.data.facets.map { f ->
                        IssueFacet(
                            property = f.property,
                            values = f.values.map { v -> IssueFacetValue(v.value, v.count) }
                        )
                    }
                )
                val summary = buildString {
                    append("${mapped.total} ${if (inNewCodePeriod) "new-code " else ""}issues for $projectKey")
                    if (mapped.facets.isNotEmpty()) {
                        append(" — facets: ")
                        append(mapped.facets.joinToString(", ") { f ->
                            val nonZero = f.values.filter { it.count > 0 }
                            if (nonZero.isEmpty()) "${f.property}:0"
                            else "${f.property}:${nonZero.joinToString("/") { "${it.value}=${it.count}" }}"
                        })
                    }
                }
                ToolResult.success(data = mapped, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch issue facets for $projectKey: ${result.message}")
                ToolResult(
                    data = IssueFacetsData(total = 0, facets = emptyList()),
                    summary = "Error fetching issue facets for $projectKey: ${result.message}",
                    isError = true,
                    hint = "Check the facet names — valid 25.x values include severities, types, impactSoftwareQualities, files, rules."
                )
            }
        }
    }

    override suspend fun getCurrentUser(): ToolResult<SonarCurrentUserData> {
        val api = client ?: return ToolResult(
            data = SonarCurrentUserData(
                login = "", name = "", email = null, groups = emptyList(),
                globalPermissions = emptyList(), externalProvider = null, isLoggedIn = false
            ),
            summary = "SonarQube not configured. Cannot fetch user identity.",
            isError = true,
            hint = "Configure SonarQube URL and token in Settings."
        )

        return when (val result = api.getCurrentUser()) {
            is ApiResult.Success -> {
                val dto = result.data
                val data = SonarCurrentUserData(
                    login = dto.login,
                    name = dto.name,
                    email = dto.email,
                    groups = dto.groups,
                    globalPermissions = dto.permissions?.global ?: emptyList(),
                    externalProvider = dto.externalProvider,
                    isLoggedIn = dto.isLoggedIn
                )
                val summary = buildString {
                    append("Connected as ${data.name} (${data.login})")
                    if (data.isAdmin) append(" — admin scope")
                    if (data.email != null) append(" · ${data.email}")
                }
                ToolResult.success(data = data, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch current user: ${result.message}")
                ToolResult(
                    data = SonarCurrentUserData(
                        login = "", name = "", email = null, groups = emptyList(),
                        globalPermissions = emptyList(), externalProvider = null, isLoggedIn = false
                    ),
                    summary = "Error fetching current user: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube URL and token."
                )
            }
        }
    }

    override suspend fun listQualityGates(): ToolResult<SonarQualityGateListData> {
        val api = client ?: return ToolResult(
            data = SonarQualityGateListData(emptyList()),
            summary = "SonarQube not configured. Cannot list quality gates.",
            isError = true,
            hint = "Configure SonarQube in Settings."
        )

        return when (val result = api.listQualityGates()) {
            is ApiResult.Success -> {
                val gates = result.data.qualitygates.map { dto ->
                    SonarQualityGateEntry(
                        name = dto.name,
                        isDefault = dto.isDefault,
                        isBuiltIn = dto.isBuiltIn,
                        caycStatus = dto.caycStatus,
                        hasStandardConditions = dto.hasStandardConditions,
                        hasMQRConditions = dto.hasMQRConditions,
                        isAiCodeSupported = dto.isAiCodeSupported
                    )
                }
                val summary = buildString {
                    append("${gates.size} quality gates configured")
                    gates.firstOrNull { it.isDefault }?.let { append(" — default: ${it.name} (${it.caycStatus})") }
                }
                ToolResult.success(data = SonarQualityGateListData(gates), summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to list quality gates: ${result.message}")
                ToolResult(
                    data = SonarQualityGateListData(emptyList()),
                    summary = "Error listing quality gates: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection."
                )
            }
        }
    }

    companion object {
        private const val COMPONENT_EXISTS_TTL_MS = 5L * 60 * 1000  // matches the 5-min cache convention used elsewhere

        @JvmStatic
        fun getInstance(project: Project): SonarService =
            project.getService(SonarService::class.java)
    }
}
