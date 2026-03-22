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

class SonarIssuesTool : AgentTool {
    override val name = "sonar_issues"
    override val description = "Get open SonarQube issues for a project, optionally filtered by file."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "project_key" to ParameterProperty(type = "string", description = "SonarQube project key (e.g., 'com.example:my-service')"),
            "file" to ParameterProperty(type = "string", description = "Optional: filter by relative file path (e.g., 'src/main/java/com/example/MyService.java')"),
            "repo_name" to ParameterProperty(type = "string", description = "Repository name (e.g., 'backend', 'frontend'). Required for multi-repo projects to target a specific repo. Omit to use the primary repository. Call bitbucket_list_repos to discover available names.")
        ),
        required = listOf("project_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val projectKey = params["project_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'project_key' parameter required", "Error: missing project_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val file = params["file"]?.jsonPrimitive?.content

        val service = ServiceLookup.sonar(project)
            ?: return ServiceLookup.notConfigured("SonarQube")

        val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
        val result = service.getIssues(projectKey, file, repoName = repoName)
        return result.toAgentToolResult()
    }
}
