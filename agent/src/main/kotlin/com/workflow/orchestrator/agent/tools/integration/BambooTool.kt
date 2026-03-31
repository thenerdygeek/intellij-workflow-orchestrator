package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Consolidated Bamboo meta-tool replacing 18 individual bamboo_* tools.
 *
 * Saves ~17,280 tokens per API call by collapsing all Bamboo CI/CD operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: build_status, get_build, trigger_build, get_build_log, get_test_results,
 *          stop_build, cancel_build, get_artifacts, recent_builds, get_plans,
 *          get_project_plans, search_plans, get_plan_branches, get_running_builds,
 *          get_build_variables, get_plan_variables, rerun_failed_jobs, trigger_stage
 */
class BambooTool : AgentTool {

    override val name = "bamboo"

    override val description = """Bamboo CI/CD integration — build status, logs, test results, artifacts, plans, branches, variables.
Actions: build_status, get_build, trigger_build, get_build_log, get_test_results, stop_build,
cancel_build, get_artifacts, recent_builds, get_plans, get_project_plans, search_plans,
get_plan_branches, get_running_builds, get_build_variables, get_plan_variables, rerun_failed_jobs, trigger_stage""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "build_status", "get_build", "trigger_build", "get_build_log", "get_test_results",
                    "stop_build", "cancel_build", "get_artifacts", "recent_builds", "get_plans",
                    "get_project_plans", "search_plans", "get_plan_branches", "get_running_builds",
                    "get_build_variables", "get_plan_variables", "rerun_failed_jobs", "trigger_stage"
                )
            ),
            "plan_key" to ParameterProperty(
                type = "string",
                description = "Bamboo plan key e.g. PROJ-PLAN — for build_status, trigger_build, recent_builds, get_plan_branches, get_running_builds, get_plan_variables, rerun_failed_jobs, trigger_stage"
            ),
            "build_key" to ParameterProperty(
                type = "string",
                description = "Bamboo build result key e.g. PROJ-PLAN-123 — for get_build, get_build_log, get_test_results"
            ),
            "result_key" to ParameterProperty(
                type = "string",
                description = "Bamboo build result key e.g. PROJ-PLAN-123 — for stop_build, cancel_build, get_artifacts, get_build_variables"
            ),
            "project_key" to ParameterProperty(
                type = "string",
                description = "Bamboo project key e.g. PROJ — for get_project_plans"
            ),
            "build_number" to ParameterProperty(
                type = "string",
                description = "Build number integer — for rerun_failed_jobs"
            ),
            "stage" to ParameterProperty(
                type = "string",
                description = "Stage name to trigger (optional) — for trigger_stage"
            ),
            "query" to ParameterProperty(
                type = "string",
                description = "Search query — for search_plans"
            ),
            "variables" to ParameterProperty(
                type = "string",
                description = "JSON object of build variables e.g. '{\"key\":\"value\"}' — for trigger_build, trigger_stage"
            ),
            "max_results" to ParameterProperty(
                type = "string",
                description = "Max results to return (default 10) — for recent_builds"
            ),
            "branch" to ParameterProperty(
                type = "string",
                description = "Optional branch name — for build_status, recent_builds. Use project_context tool to discover current branch."
            ),
            "repo_name" to ParameterProperty(
                type = "string",
                description = "Repository name for multi-repo projects — for build_status, recent_builds, get_plan_branches, get_running_builds"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description shown in approval dialog — for write actions: trigger_build, stop_build, cancel_build, rerun_failed_jobs, trigger_stage"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
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
            "build_status" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getLatestBuild(planKey, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "get_build" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content ?: return missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.getBuild(buildKey).toAgentToolResult()
            }

            "trigger_build" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val variablesStr = params["variables"]?.jsonPrimitive?.content
                val variables = if (!variablesStr.isNullOrBlank()) {
                    try {
                        val obj = kotlinx.serialization.json.Json.parseToJsonElement(variablesStr).jsonObject
                        obj.mapValues { it.value.jsonPrimitive.content }
                    } catch (_: Exception) {
                        return ToolResult(
                            "Invalid variables JSON: '$variablesStr'. Expected format: {\"key\":\"value\"}",
                            "Invalid variables",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                    }
                } else emptyMap()
                service.triggerBuild(planKey, variables).toAgentToolResult()
            }

            "get_build_log" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content ?: return missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.getBuildLog(buildKey).toAgentToolResult()
            }

            "get_test_results" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content ?: return missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.getTestResults(buildKey).toAgentToolResult()
            }

            "stop_build" -> {
                val resultKey = params["result_key"]?.jsonPrimitive?.content ?: return missingParam("result_key")
                ToolValidation.validateBambooBuildKey(resultKey)?.let { return it }
                service.stopBuild(resultKey).toAgentToolResult()
            }

            "cancel_build" -> {
                val resultKey = params["result_key"]?.jsonPrimitive?.content ?: return missingParam("result_key")
                ToolValidation.validateBambooBuildKey(resultKey)?.let { return it }
                service.cancelBuild(resultKey).toAgentToolResult()
            }

            "get_artifacts" -> {
                val resultKey = params["result_key"]?.jsonPrimitive?.content ?: return missingParam("result_key")
                ToolValidation.validateBambooBuildKey(resultKey)?.let { return it }
                service.getArtifacts(resultKey).toAgentToolResult()
            }

            "recent_builds" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return missingParam("plan_key")
                val maxResults = params["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getRecentBuilds(planKey, maxResults, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "get_plans" -> {
                service.getPlans().toAgentToolResult()
            }

            "get_project_plans" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return missingParam("project_key")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                service.getProjectPlans(projectKey).toAgentToolResult()
            }

            "search_plans" -> {
                val query = params["query"]?.jsonPrimitive?.content ?: return missingParam("query")
                ToolValidation.validateNotBlank(query, "query")?.let { return it }
                service.searchPlans(query).toAgentToolResult()
            }

            "get_plan_branches" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPlanBranches(planKey, repoName = repoName).toAgentToolResult()
            }

            "get_running_builds" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getRunningBuilds(planKey, repoName = repoName).toAgentToolResult()
            }

            "get_build_variables" -> {
                val resultKey = params["result_key"]?.jsonPrimitive?.content ?: return missingParam("result_key")
                ToolValidation.validateBambooBuildKey(resultKey)?.let { return it }
                service.getBuildVariables(resultKey).toAgentToolResult()
            }

            "get_plan_variables" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                service.getPlanVariables(planKey).toAgentToolResult()
            }

            "rerun_failed_jobs" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return missingParam("plan_key")
                val buildNumberStr = params["build_number"]?.jsonPrimitive?.content ?: return missingParam("build_number")
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
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return missingParam("plan_key")
                val stage = params["stage"]?.jsonPrimitive?.content
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val variablesStr = params["variables"]?.jsonPrimitive?.content
                val variables = if (!variablesStr.isNullOrBlank()) {
                    try {
                        val obj = kotlinx.serialization.json.Json.parseToJsonElement(variablesStr).jsonObject
                        obj.mapValues { it.value.jsonPrimitive.content }
                    } catch (_: Exception) {
                        return ToolResult(
                            "Invalid variables JSON: '$variablesStr'. Expected format: {\"key\":\"value\"}",
                            "Invalid variables",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                    }
                } else emptyMap()
                service.triggerStage(planKey, variables, stage).toAgentToolResult()
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
