package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Bamboo build lifecycle tool — trigger, monitor, stop, and inspect builds.
 *
 * Split from the consolidated BambooTool. Covers 11 build-oriented actions:
 * build_status, get_build, trigger_build, get_build_log, get_test_results,
 * stop_build, cancel_build, get_artifacts, download_artifact, recent_builds,
 * get_running_builds.
 */
class BambooBuildsTool : AgentTool {

    override val name = "bamboo_builds"

    override val description = """
Bamboo build lifecycle — trigger, monitor, stop, inspect builds and test results.

Actions and their parameters:
- build_status(plan_key, branch?, repo_name?) → Latest build status for plan
- get_build(build_key) → Detailed build info
- trigger_build(plan_key, variables?) → Trigger new build (variables: JSON {"key":"value"})
- get_build_log(build_key) → Build log output
- get_test_results(build_key) → Test results for build
- stop_build(result_key) → Stop running build
- cancel_build(result_key) → Cancel queued build
- get_artifacts(result_key) → List build artifacts
- download_artifact(artifact_url, target_path?) → Download build artifact to local file
- recent_builds(plan_key, branch?, repo_name?, max_results?) → Recent builds (default 10)
- get_running_builds(plan_key, repo_name?) → Currently running builds

description optional: for approval dialog on trigger/stop/cancel.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "build_status", "get_build", "trigger_build", "get_build_log", "get_test_results",
                    "stop_build", "cancel_build", "get_artifacts", "download_artifact", "recent_builds",
                    "get_running_builds"
                )
            ),
            "plan_key" to ParameterProperty(
                type = "string",
                description = "Bamboo plan key e.g. PROJ-PLAN — for build_status, trigger_build, recent_builds, get_running_builds"
            ),
            "build_key" to ParameterProperty(
                type = "string",
                description = "Bamboo build result key e.g. PROJ-PLAN-123 — for get_build, get_build_log, get_test_results"
            ),
            "result_key" to ParameterProperty(
                type = "string",
                description = "Bamboo build result key e.g. PROJ-PLAN-123 — for stop_build, cancel_build, get_artifacts"
            ),
            "branch" to ParameterProperty(
                type = "string",
                description = "Optional branch name — for build_status, recent_builds. Use project_context tool to discover current branch."
            ),
            "repo_name" to ParameterProperty(
                type = "string",
                description = "Repository name for multi-repo projects — for build_status, recent_builds, get_running_builds"
            ),
            "variables" to ParameterProperty(
                type = "string",
                description = "JSON object of build variables e.g. '{\"key\":\"value\"}' — for trigger_build"
            ),
            "artifact_url" to ParameterProperty(
                type = "string",
                description = "Artifact download URL (from get_artifacts output) — for download_artifact"
            ),
            "target_path" to ParameterProperty(
                type = "string",
                description = "Optional local path to save artifact — for download_artifact (defaults to temp file)"
            ),
            "max_results" to ParameterProperty(
                type = "string",
                description = "Max results to return (default 10) — for recent_builds"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description shown in approval dialog — for write actions: trigger_build, stop_build, cancel_build"
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

            "download_artifact" -> {
                val artifactUrl = params["artifact_url"]?.jsonPrimitive?.content
                    ?: return missingParam("artifact_url")
                val targetPath = params["target_path"]?.jsonPrimitive?.content
                val targetFile = if (targetPath != null) {
                    java.io.File(targetPath)
                } else {
                    java.io.File.createTempFile("bamboo-artifact-", ".tmp")
                }
                val result = service.downloadArtifact(artifactUrl, targetFile)
                if (result.isError) {
                    result.toAgentToolResult()
                } else {
                    ToolResult(
                        content = "Artifact downloaded to: ${targetFile.absolutePath}\nSize: ${targetFile.length()} bytes",
                        summary = "Downloaded artifact to ${targetFile.name}",
                        tokenEstimate = TokenEstimator.estimate("Downloaded artifact to ${targetFile.absolutePath}"),
                        isError = false
                    )
                }
            }

            "recent_builds" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return missingParam("plan_key")
                val maxResults = params["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getRecentBuilds(planKey, maxResults, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "get_running_builds" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getRunningBuilds(planKey, repoName = repoName).toAgentToolResult()
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
