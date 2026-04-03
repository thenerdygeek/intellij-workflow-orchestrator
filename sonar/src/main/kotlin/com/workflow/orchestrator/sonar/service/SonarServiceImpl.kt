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
import com.workflow.orchestrator.core.model.sonar.ProjectHealthData
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
import com.workflow.orchestrator.core.services.SonarService
import com.workflow.orchestrator.core.services.ToolResult
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

    private val client: SonarApiClient?
        get() = SonarDataService.getInstance(project).getSharedApiClient()

    private fun resolveProjectKey(projectKey: String?, repoName: String?): String? {
        if (!projectKey.isNullOrBlank()) return projectKey
        if (repoName != null) {
            val repo = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).getRepoByName(repoName)
            if (repo != null && !repo.sonarProjectKey.isNullOrBlank()) return repo.sonarProjectKey
        }
        // Fallback to existing data service project key resolution
        return null
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
                        type = dto.type
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

        // Fetch project-level measures for coverage metrics
        val metricKeys = "coverage,branch_coverage,lines_to_cover,uncovered_lines"
        return when (val result = api.getMeasures(projectKey, branch = branch, metricKeys = metricKeys)) {
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

    override suspend fun getProjectHealth(projectKey: String): ToolResult<ProjectHealthData> {
        val api = client ?: return ToolResult(
            data = ProjectHealthData(
                technicalDebtMinutes = 0, technicalDebtFormatted = "0min",
                maintainabilityRating = "?", reliabilityRating = "?", securityRating = "?",
                duplicatedLinesDensity = 0.0, cognitiveComplexity = 0,
                lineCoverage = 0.0, branchCoverage = 0.0
            ),
            summary = "SonarQube not configured. Cannot fetch project health.",
            isError = true,
            hint = "Set up SonarQube connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getProjectMeasures(projectKey)) {
            is ApiResult.Success -> {
                val measures = result.data.associateBy { it.metric }

                val debtMinutes = measures["sqale_index"]?.value?.toDoubleOrNull()?.toLong() ?: 0L
                val maintainability = measures["sqale_rating"]?.value?.toDoubleOrNull()?.let { ratingLetter(it) } ?: "?"
                val reliability = measures["reliability_rating"]?.value?.toDoubleOrNull()?.let { ratingLetter(it) } ?: "?"
                val security = measures["security_rating"]?.value?.toDoubleOrNull()?.let { ratingLetter(it) } ?: "?"
                val duplication = measures["duplicated_lines_density"]?.value?.toDoubleOrNull() ?: 0.0
                val complexity = measures["cognitive_complexity"]?.value?.toDoubleOrNull()?.toLong() ?: 0L
                val lineCov = measures["coverage"]?.value?.toDoubleOrNull() ?: 0.0
                val branchCov = measures["branch_coverage"]?.value?.toDoubleOrNull() ?: 0.0

                val data = ProjectHealthData(
                    technicalDebtMinutes = debtMinutes,
                    technicalDebtFormatted = formatDebt(debtMinutes),
                    maintainabilityRating = maintainability,
                    reliabilityRating = reliability,
                    securityRating = security,
                    duplicatedLinesDensity = duplication,
                    cognitiveComplexity = complexity,
                    lineCoverage = lineCov,
                    branchCoverage = branchCov
                )

                val summary = buildString {
                    append("Project Health for $projectKey")
                    append("\nRatings: Maintainability=$maintainability | Reliability=$reliability | Security=$security")
                    append("\nTechnical Debt: ${formatDebt(debtMinutes)}")
                    append("\nDuplication: ${"%.1f".format(duplication)}%")
                    append("\nCoverage: Line=${"%.1f".format(lineCov)}% | Branch=${"%.1f".format(branchCov)}%")
                    append("\nCognitive Complexity: $complexity")
                }

                ToolResult.success(data = data, summary = summary)
            }
            is ApiResult.Error -> {
                log.warn("[SonarService] Failed to fetch project health for $projectKey: ${result.message}")
                ToolResult(
                    data = ProjectHealthData(
                        technicalDebtMinutes = 0, technicalDebtFormatted = "0min",
                        maintainabilityRating = "?", reliabilityRating = "?", securityRating = "?",
                        duplicatedLinesDensity = 0.0, cognitiveComplexity = 0,
                        lineCoverage = 0.0, branchCoverage = 0.0
                    ),
                    summary = "Error fetching project health for $projectKey: ${result.message}",
                    isError = true,
                    hint = "Check SonarQube connection and project key."
                )
            }
        }
    }

    private fun ratingLetter(value: Double): String = when {
        value <= 1.0 -> "A"
        value <= 2.0 -> "B"
        value <= 3.0 -> "C"
        value <= 4.0 -> "D"
        else -> "E"
    }

    private fun formatDebt(minutes: Long): String {
        if (minutes < 60) return "${minutes}min"
        val hours = minutes / 60
        val mins = minutes % 60
        if (hours < 24) return if (mins > 0) "${hours}h ${mins}min" else "${hours}h"
        val days = hours / 24
        val remainingHours = hours % 24
        return if (remainingHours > 0) "${days}d ${remainingHours}h" else "${days}d"
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

        val metricKeys = "reliability_rating,security_rating,sqale_rating,coverage,duplicated_lines_density,sqale_debt_ratio,ncloc"
        return when (val result = api.getProjectMeasures(projectKey, branch, metricKeys)) {
            is ApiResult.Success -> {
                val measures = result.data.associate { it.metric to it.value }

                // SonarQube ratings: 1=A, 2=B, 3=C, 4=D, 5=E
                fun ratingToGrade(value: String?): String? = when (value?.toDoubleOrNull()?.toInt()) {
                    1 -> "A"; 2 -> "B"; 3 -> "C"; 4 -> "D"; 5 -> "E"; else -> value
                }

                val data = ProjectMeasuresData(
                    reliability = ratingToGrade(measures["reliability_rating"]),
                    security = ratingToGrade(measures["security_rating"]),
                    maintainability = ratingToGrade(measures["sqale_rating"]),
                    coverage = measures["coverage"]?.toDoubleOrNull(),
                    duplications = measures["duplicated_lines_density"]?.toDoubleOrNull(),
                    technicalDebt = measures["sqale_debt_ratio"],
                    linesOfCode = measures["ncloc"]?.toLongOrNull()
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
                    SourceLineData(
                        line = dto.line,
                        code = dto.code,
                        coverageStatus = when {
                            dto.lineHits == null -> null
                            dto.lineHits > 0 -> "covered"
                            else -> "uncovered"
                        },
                        conditions = dto.conditions,
                        coveredConditions = dto.coveredConditions
                    )
                }

                val covered = lines.count { it.coverageStatus == "covered" }
                val uncovered = lines.count { it.coverageStatus == "uncovered" }
                val rangeLabel = if (from != null || to != null) " (L${from ?: 1}-${to ?: "end"})" else ""
                val summary = buildString {
                    append("${lines.size} source lines for $componentKey$rangeLabel")
                    if (covered + uncovered > 0) {
                        append("\nCovered: $covered | Uncovered: $uncovered")
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

        return when (val result = api.getIssuesWithPaging(projectKey, branch = branch, inNewCodePeriod = inNewCodePeriod)) {
            is ApiResult.Success -> {
                val allIssues = result.data.issues.map { dto ->
                    SonarIssueData(
                        key = dto.key,
                        rule = dto.rule,
                        severity = dto.severity,
                        message = dto.message,
                        component = dto.component,
                        line = dto.textRange?.startLine,
                        status = dto.status,
                        type = dto.type
                    )
                }

                // Apply client-side paging over the fetched results
                val startIndex = (page - 1) * pageSize
                val pagedIssues = allIssues.drop(startIndex).take(pageSize)
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
                    hint = "Check SonarQube connection and project key. Security hotspots require Developer Edition+."
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
                val data = SonarRuleData(
                    ruleKey = dto.key,
                    name = dto.name,
                    description = dto.mdDesc ?: dto.htmlDesc ?: "",
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

    companion object {
        @JvmStatic
        fun getInstance(project: Project): SonarServiceImpl =
            project.getService(SonarService::class.java) as SonarServiceImpl
    }
}
