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

/**
 * Consolidated SonarQube meta-tool replacing 9 individual sonar_* tools.
 *
 * Saves token budget per API call by collapsing all SonarQube operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: issues, quality_gate, coverage, search_projects, analysis_tasks,
 *          branches, project_measures, source_lines, issues_paged
 */
class SonarTool : AgentTool {

    override val name = "sonar"

    override val description =
        "SonarQube code quality integration — issues, quality gate, coverage, measures, branches, source lines.\n" +
        "Actions: issues, quality_gate, coverage, search_projects, analysis_tasks, branches, project_measures, source_lines, issues_paged"

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "issues", "quality_gate", "coverage", "search_projects",
                    "analysis_tasks", "branches", "project_measures", "source_lines", "issues_paged"
                )
            ),
            "project_key" to ParameterProperty(
                type = "string",
                description = "SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged"
            ),
            "component_key" to ParameterProperty(
                type = "string",
                description = "SonarQube component key e.g. 'com.example:my-service:src/main/java/MyClass.java' — for source_lines"
            ),
            "query" to ParameterProperty(
                type = "string",
                description = "Search query — for search_projects"
            ),
            "file" to ParameterProperty(
                type = "string",
                description = "Optional relative file path filter — for issues"
            ),
            "branch" to ParameterProperty(
                type = "string",
                description = "Optional branch name — for project_measures"
            ),
            "from" to ParameterProperty(
                type = "string",
                description = "Start line number — for source_lines"
            ),
            "to" to ParameterProperty(
                type = "string",
                description = "End line number — for source_lines"
            ),
            "page" to ParameterProperty(
                type = "string",
                description = "Page number (default 1) — for issues_paged"
            ),
            "page_size" to ParameterProperty(
                type = "string",
                description = "Results per page max 500 (default 100) — for issues_paged"
            ),
            "repo_name" to ParameterProperty(
                type = "string",
                description = "Repository name for multi-repo projects"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val service = ServiceLookup.sonar(project)
            ?: return ServiceLookup.notConfigured("SonarQube")

        return when (action) {
            "issues" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                val file = params["file"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getIssues(projectKey, file, repoName = repoName).toAgentToolResult()
            }

            "quality_gate" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getQualityGateStatus(projectKey, repoName = repoName).toAgentToolResult()
            }

            "coverage" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getCoverage(projectKey, repoName = repoName).toAgentToolResult()
            }

            "search_projects" -> {
                val query = params["query"]?.jsonPrimitive?.content ?: return missingParam("query")
                ToolValidation.validateNotBlank(query, "query")?.let { return it }
                service.searchProjects(query).toAgentToolResult()
            }

            "analysis_tasks" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getAnalysisTasks(projectKey, repoName = repoName).toAgentToolResult()
            }

            "branches" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getBranches(projectKey, repoName = repoName).toAgentToolResult()
            }

            "project_measures" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getProjectMeasures(projectKey, branch, repoName = repoName).toAgentToolResult()
            }

            "source_lines" -> {
                val componentKey = params["component_key"]?.jsonPrimitive?.content ?: return missingParam("component_key")
                val from = params["from"]?.jsonPrimitive?.content?.toIntOrNull()
                val to = params["to"]?.jsonPrimitive?.content?.toIntOrNull()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getSourceLines(componentKey, from, to, repoName = repoName).toAgentToolResult()
            }

            "issues_paged" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                val page = params["page"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                val pageSize = params["page_size"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getIssuesPaged(projectKey, page, pageSize, repoName = repoName).toAgentToolResult()
            }

            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    private fun missingParam(name: String): ToolResult = ToolResult(
        content = "Error: '$name' parameter required",
        summary = "Error: missing $name",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
}
