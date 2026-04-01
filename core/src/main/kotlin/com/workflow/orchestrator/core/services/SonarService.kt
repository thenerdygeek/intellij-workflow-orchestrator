package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.sonar.CoverageData
import com.workflow.orchestrator.core.model.sonar.PagedIssuesData
import com.workflow.orchestrator.core.model.sonar.ProjectMeasuresData
import com.workflow.orchestrator.core.model.sonar.QualityGateData
import com.workflow.orchestrator.core.model.sonar.ProjectHealthData
import com.workflow.orchestrator.core.model.sonar.SonarAnalysisTaskData
import com.workflow.orchestrator.core.model.sonar.SonarBranchData
import com.workflow.orchestrator.core.model.sonar.SonarIssueData
import com.workflow.orchestrator.core.model.sonar.SonarProjectData
import com.workflow.orchestrator.core.model.sonar.SourceLineData

/**
 * SonarQube operations used by both UI panels and AI agent.
 * Implementations registered as project-level services by :sonar module.
 */
interface SonarService {
    /** Get open issues for a project, optionally filtered by file and/or branch. */
    suspend fun getIssues(projectKey: String, filePath: String? = null, branch: String? = null, repoName: String? = null): ToolResult<List<SonarIssueData>>

    /** Get quality gate status, optionally for a specific branch. */
    suspend fun getQualityGateStatus(projectKey: String, branch: String? = null, repoName: String? = null): ToolResult<QualityGateData>

    /** Get coverage metrics, optionally for a specific branch. */
    suspend fun getCoverage(projectKey: String, branch: String? = null, repoName: String? = null): ToolResult<CoverageData>

    /** Search for SonarQube projects by name or key. */
    suspend fun searchProjects(query: String): ToolResult<List<SonarProjectData>>

    /** Get recent analysis tasks for a project. */
    suspend fun getAnalysisTasks(projectKey: String, repoName: String? = null): ToolResult<List<SonarAnalysisTaskData>>

    /** Get project-level health metrics: tech debt, ratings, duplication, complexity. */
    suspend fun getProjectHealth(projectKey: String): ToolResult<ProjectHealthData>

    /** Test the SonarQube connection. */
    suspend fun testConnection(): ToolResult<Unit>

    /** List branches for a project with their quality gate status. */
    suspend fun getBranches(projectKey: String, repoName: String? = null): ToolResult<List<SonarBranchData>>

    /** Get project-level aggregate measures (ratings, coverage, debt). */
    suspend fun getProjectMeasures(projectKey: String, branch: String? = null, repoName: String? = null): ToolResult<ProjectMeasuresData>

    /** Get source lines with per-line coverage status, optionally for a specific branch (internal API). */
    suspend fun getSourceLines(componentKey: String, from: Int? = null, to: Int? = null, branch: String? = null, repoName: String? = null): ToolResult<List<SourceLineData>>

    /** Get issues with paging metadata for pagination support, optionally for a specific branch. */
    suspend fun getIssuesPaged(projectKey: String, page: Int = 1, pageSize: Int = 100, branch: String? = null, repoName: String? = null): ToolResult<PagedIssuesData>
}
