package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated SonarQube meta-tool (12 actions).
 *
 * Saves token budget per API call by collapsing all SonarQube operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: issues, quality_gate, coverage, search_projects, analysis_tasks,
 *          branches, project_measures, source_lines, issues_paged,
 *          security_hotspots, duplications, branch_quality_report
 */
class SonarTool : AgentTool {

    override val name = "sonar"

    override val description = """
SonarQube code quality — issues, coverage, quality gates, analysis, security hotspots.

Actions and their parameters:
- issues(project_key, file?, branch?, new_code_only?) → Code issues (optionally filter by file path; set new_code_only=true to see only issues in new code period)
- quality_gate(project_key, branch?) → Quality gate status
- coverage(project_key, branch?) → Code coverage metrics
- search_projects(query) → Search SonarQube projects
- analysis_tasks(project_key) → Recent analysis task status
- branches(project_key) → Analyzed branches
- project_measures(project_key, branch?) → All project metrics
- source_lines(component_key, from?, to?, branch?) → Source code with metrics (from/to are line numbers)
- issues_paged(project_key, page?, page_size?, branch?, new_code_only?) → Paginated issues (default page 1, 100/page, max 500; set new_code_only=true for new code only)
- security_hotspots(project_key, branch?) → Security hotspots
- duplications(component_key, branch?) → Code duplications
- branch_quality_report(project_key, branch, max_files?) → **Consolidated new-code quality report** — one call gets: quality gate, all issues (bugs/smells/vulnerabilities), security hotspots, coverage summary, plus exact uncovered line numbers, uncovered branch line numbers, and duplicated line ranges per file. Default max_files=20. Use this instead of calling issues+quality_gate+coverage+hotspots separately.

Common optional: repo_name for multi-repo projects.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "issues", "quality_gate", "coverage", "search_projects",
                    "analysis_tasks", "branches", "project_measures", "source_lines", "issues_paged",
                    "security_hotspots", "duplications", "branch_quality_report"
                )
            ),
            "project_key" to ParameterProperty(
                type = "string",
                description = "SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged"
            ),
            "component_key" to ParameterProperty(
                type = "string",
                description = "SonarQube component key e.g. 'com.example:my-service:src/main/java/MyClass.java' — for source_lines, duplications"
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
                description = "Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch."
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
            "new_code_only" to ParameterProperty(
                type = "boolean",
                description = "When true, return only issues introduced in the new code period (since branch point or configured baseline) — for issues, issues_paged"
            ),
            "max_files" to ParameterProperty(
                type = "string",
                description = "Max files to drill down into for line-level details (default 20) — for branch_quality_report"
            ),
            "repo_name" to ParameterProperty(
                type = "string",
                description = "Repository name for multi-repo projects"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
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
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                val newCodeOnly = try { params["new_code_only"]?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false
                service.getIssues(projectKey, file, branch = branch, repoName = repoName, inNewCodePeriod = newCodeOnly).toAgentToolResult()
            }

            "quality_gate" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getQualityGateStatus(projectKey, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "coverage" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getCoverage(projectKey, branch = branch, repoName = repoName).toAgentToolResult()
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
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getSourceLines(componentKey, from, to, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "issues_paged" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                val page = params["page"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                val pageSize = params["page_size"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                val newCodeOnly = try { params["new_code_only"]?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false
                service.getIssuesPaged(projectKey, page, pageSize, branch = branch, repoName = repoName, inNewCodePeriod = newCodeOnly).toAgentToolResult()
            }

            "security_hotspots" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getSecurityHotspots(projectKey, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "duplications" -> {
                val componentKey = params["component_key"]?.jsonPrimitive?.content ?: return missingParam("component_key")
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getDuplications(componentKey, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "branch_quality_report" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                val branch = params["branch"]?.jsonPrimitive?.content ?: return missingParam("branch")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                ToolValidation.validateNotBlank(branch, "branch")?.let { return it }
                val maxFiles = params["max_files"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getBranchQualityReport(projectKey, branch, maxFiles, repoName).toAgentToolResult()
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
