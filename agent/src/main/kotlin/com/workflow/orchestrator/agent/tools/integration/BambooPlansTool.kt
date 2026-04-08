package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Bamboo plan management tool — discover, search, and configure build plans.
 *
 * Split from the consolidated BambooTool. Covers 8 plan-oriented actions:
 * get_plans, get_project_plans, search_plans, get_plan_branches,
 * get_build_variables, get_plan_variables, rerun_failed_jobs, trigger_stage.
 */
class BambooPlansTool : AgentTool {

    override val name = "bamboo_plans"

    override val description = """
Bamboo plan management — discover, search, and configure build plans and variables.

Actions and their parameters:
- get_plans() → List all build plans
- get_project_plans(project_key) → Plans in a project
- search_plans(query) → Search plans by name
- get_plan_branches(plan_key, repo_name?) → Plan branch list
- get_build_variables(result_key) → Variables for a specific build
- get_plan_variables(plan_key) → Default plan variables
- rerun_failed_jobs(plan_key, build_number) → Rerun failed jobs in build
- trigger_stage(plan_key, stage?, variables?) → Trigger specific stage (variables: JSON {"key":"value"})
- auto_detect_plan(git_remote_url) → Auto-detect the Bamboo plan key from a Git remote URL

description optional: for approval dialog on rerun_failed_jobs/trigger_stage.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_plans", "get_project_plans", "search_plans", "get_plan_branches",
                    "get_build_variables", "get_plan_variables", "rerun_failed_jobs", "trigger_stage",
                    "auto_detect_plan"
                )
            ),
            "plan_key" to ParameterProperty(
                type = "string",
                description = "Bamboo plan key e.g. PROJ-PLAN — for get_plan_branches, get_plan_variables, rerun_failed_jobs, trigger_stage"
            ),
            "project_key" to ParameterProperty(
                type = "string",
                description = "Bamboo project key e.g. PROJ — for get_project_plans"
            ),
            "query" to ParameterProperty(
                type = "string",
                description = "Search query — for search_plans"
            ),
            "repo_name" to ParameterProperty(
                type = "string",
                description = "Repository name for multi-repo projects — for get_plan_branches"
            ),
            "result_key" to ParameterProperty(
                type = "string",
                description = "Bamboo build result key e.g. PROJ-PLAN-123 — for get_build_variables"
            ),
            "build_number" to ParameterProperty(
                type = "string",
                description = "Build number integer — for rerun_failed_jobs"
            ),
            "stage" to ParameterProperty(
                type = "string",
                description = "Stage name to trigger (optional) — for trigger_stage"
            ),
            "variables" to ParameterProperty(
                type = "string",
                description = "JSON object of build variables e.g. '{\"key\":\"value\"}' — for trigger_stage"
            ),
            "git_remote_url" to ParameterProperty(
                type = "string",
                description = "Git remote URL (e.g. git@github.com:org/repo.git or https://github.com/org/repo) — for auto_detect_plan"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description shown in approval dialog — for write actions: rerun_failed_jobs, trigger_stage"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val service = ServiceLookup.bamboo(project)
            ?: return ServiceLookup.notConfigured("Bamboo")

        return when (action) {
            "get_plans" -> {
                service.getPlans().toAgentToolResult()
            }

            "get_project_plans" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                service.getProjectPlans(projectKey).toAgentToolResult()
            }

            "search_plans" -> {
                val query = params["query"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("query")
                ToolValidation.validateNotBlank(query, "query")?.let { return it }
                service.searchPlans(query).toAgentToolResult()
            }

            "get_plan_branches" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPlanBranches(planKey, repoName = repoName).toAgentToolResult()
            }

            "get_build_variables" -> {
                val resultKey = params["result_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("result_key")
                ToolValidation.validateBambooBuildKey(resultKey)?.let { return it }
                service.getBuildVariables(resultKey).toAgentToolResult()
            }

            "get_plan_variables" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                service.getPlanVariables(planKey).toAgentToolResult()
            }

            "rerun_failed_jobs" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                val buildNumberStr = params["build_number"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("build_number")
                val buildNumber = buildNumberStr.toIntOrNull()
                    ?: return ToolResult(
                        "Error: 'build_number' must be an integer, got '$buildNumberStr'",
                        "Error: invalid build_number",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                service.rerunFailedJobs(planKey, buildNumber).toAgentToolResult()
            }

            "trigger_stage" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                val stage = params["stage"]?.jsonPrimitive?.content
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val variables = when (val parsed = BambooToolUtils.parseVariables(params["variables"]?.jsonPrimitive?.content)) {
                    is BambooToolUtils.VariablesParseResult.Success -> parsed.variables
                    is BambooToolUtils.VariablesParseResult.Failure -> return parsed.error
                }
                service.triggerStage(planKey, variables, stage).toAgentToolResult()
            }

            "auto_detect_plan" -> {
                val gitRemoteUrl = params["git_remote_url"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("git_remote_url")
                ToolValidation.validateNotBlank(gitRemoteUrl, "git_remote_url")?.let { return it }
                service.autoDetectPlan(gitRemoteUrl).toAgentToolResult()
            }

            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
