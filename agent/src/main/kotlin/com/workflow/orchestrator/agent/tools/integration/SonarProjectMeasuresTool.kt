package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SonarProjectMeasuresTool : AgentTool {
    override val name = "sonar_project_measures"
    override val description = "Get project-level aggregate measures from SonarQube: ratings, coverage, debt, duplications. Optionally for a specific branch."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "project_key" to ParameterProperty(type = "string", description = "SonarQube project key (e.g., 'com.example:my-service')"),
            "branch" to ParameterProperty(type = "string", description = "Branch name to analyze (optional, defaults to main branch)"),
            "repo_name" to ParameterProperty(type = "string", description = "Repository name for multi-repo projects. Omit for single-repo or to use the primary repository.")
        ),
        required = listOf("project_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val projectKey = params["project_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'project_key' parameter required", "Error: missing project_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val branch = params["branch"]?.jsonPrimitive?.content

        val service = ServiceLookup.sonar(project) ?: return ServiceLookup.notConfigured("SonarQube")

        val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
        return service.getProjectMeasures(projectKey, branch, repoName = repoName).toAgentToolResult()
    }
}
