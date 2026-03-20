package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.sonar.CoverageData
import com.workflow.orchestrator.core.model.sonar.QualityGateData
import com.workflow.orchestrator.core.model.sonar.SonarAnalysisTaskData
import com.workflow.orchestrator.core.model.sonar.SonarIssueData
import com.workflow.orchestrator.core.model.sonar.SonarProjectData

/**
 * SonarQube operations used by both UI panels and AI agent.
 * Implementations registered as project-level services by :sonar module.
 */
interface SonarService {
    /** Get open issues for a project, optionally filtered by file. */
    suspend fun getIssues(projectKey: String, filePath: String? = null): ToolResult<List<SonarIssueData>>

    /** Get quality gate status. */
    suspend fun getQualityGateStatus(projectKey: String): ToolResult<QualityGateData>

    /** Get coverage metrics. */
    suspend fun getCoverage(projectKey: String): ToolResult<CoverageData>

    /** Search for SonarQube projects by name or key. */
    suspend fun searchProjects(query: String): ToolResult<List<SonarProjectData>>

    /** Get recent analysis tasks for a project. */
    suspend fun getAnalysisTasks(projectKey: String): ToolResult<List<SonarAnalysisTaskData>>

    /** Test the SonarQube connection. */
    suspend fun testConnection(): ToolResult<Unit>
}
