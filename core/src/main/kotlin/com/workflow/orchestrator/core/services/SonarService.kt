package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.sonar.CoverageData
import com.workflow.orchestrator.core.model.sonar.PagedIssuesData
import com.workflow.orchestrator.core.model.sonar.ProjectMeasuresData
import com.workflow.orchestrator.core.model.sonar.QualityGateData
import com.workflow.orchestrator.core.model.sonar.SonarAnalysisTaskData
import com.workflow.orchestrator.core.model.sonar.SonarBranchData
import com.workflow.orchestrator.core.model.sonar.SonarIssueData
import com.workflow.orchestrator.core.model.sonar.SonarProjectData
import com.workflow.orchestrator.core.model.sonar.SecurityHotspotData
import com.workflow.orchestrator.core.model.sonar.DuplicationData
import com.workflow.orchestrator.core.model.sonar.SourceLineData
import com.workflow.orchestrator.core.model.sonar.SonarRuleData
import com.workflow.orchestrator.core.model.sonar.BranchQualityReportData

/**
 * SonarQube operations used by both UI panels and AI agent.
 * Implementations registered as project-level services by :sonar module.
 */
interface SonarService {
    /** Get open issues for a project, optionally filtered by file, branch, and/or new code period. */
    suspend fun getIssues(projectKey: String, filePath: String? = null, branch: String? = null, repoName: String? = null, inNewCodePeriod: Boolean = false): ToolResult<List<SonarIssueData>>

    /** Get quality gate status, optionally for a specific branch. */
    suspend fun getQualityGateStatus(projectKey: String, branch: String? = null, repoName: String? = null): ToolResult<QualityGateData>

    /** Get coverage metrics, optionally for a specific branch. */
    suspend fun getCoverage(projectKey: String, branch: String? = null, repoName: String? = null): ToolResult<CoverageData>

    /** Search for SonarQube projects by name or key. */
    suspend fun searchProjects(query: String): ToolResult<List<SonarProjectData>>

    /** Get recent analysis tasks for a project. */
    suspend fun getAnalysisTasks(projectKey: String, repoName: String? = null): ToolResult<List<SonarAnalysisTaskData>>

    /** Test the SonarQube connection. */
    suspend fun testConnection(): ToolResult<Unit>

    /** List branches for a project with their quality gate status. */
    suspend fun getBranches(projectKey: String, repoName: String? = null): ToolResult<List<SonarBranchData>>

    /** Get project-level aggregate measures (ratings, coverage, debt). */
    suspend fun getProjectMeasures(projectKey: String, branch: String? = null, repoName: String? = null): ToolResult<ProjectMeasuresData>

    /** Get source lines with per-line coverage status, optionally for a specific branch (internal API). */
    suspend fun getSourceLines(componentKey: String, from: Int? = null, to: Int? = null, branch: String? = null, repoName: String? = null): ToolResult<List<SourceLineData>>

    /** Get security hotspots (separate from issues — requires dedicated API), optionally for a specific branch. */
    suspend fun getSecurityHotspots(projectKey: String, branch: String? = null, repoName: String? = null): ToolResult<List<SecurityHotspotData>>

    /** Get code duplication details for a file — duplicate block locations across files. */
    suspend fun getDuplications(componentKey: String, branch: String? = null, repoName: String? = null): ToolResult<DuplicationData>

    /** Get issues with paging metadata for pagination support, optionally for a specific branch and/or new code period. */
    suspend fun getIssuesPaged(projectKey: String, page: Int = 1, pageSize: Int = 100, branch: String? = null, repoName: String? = null, inNewCodePeriod: Boolean = false): ToolResult<PagedIssuesData>

    /** Get rule details (name, description, remediation) for a specific rule key. */
    suspend fun getRule(ruleKey: String, repoName: String? = null): ToolResult<SonarRuleData>

    /**
     * Consolidated branch quality report for new code.
     *
     * Fetches quality gate, issues, security hotspots, coverage, and duplications
     * in parallel, then drills down into the top [maxFiles] files with coverage gaps
     * or duplications to extract exact uncovered/duplicated line numbers.
     */
    suspend fun getBranchQualityReport(
        projectKey: String,
        branch: String,
        maxFiles: Int = 20,
        repoName: String? = null
    ): ToolResult<BranchQualityReportData>
}
