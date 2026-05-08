package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.model.bamboo.BuildChangeData
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
REMOTE CI ONLY: Bamboo build lifecycle — trigger, monitor, stop, inspect builds and test results.

Use for: 'show me the latest Bamboo build', 'why did CI fail', 'fetch artifact from build N', 'rerun failed CI jobs'.
Do NOT use for: local IDE Maven/Gradle reload errors, 'why did my IDE build fail', or anything in the IDE's Build tool window — use get_build_problems for those.

Actions and their parameters:
- build_status(plan_key, branch?, repo_name?) → Latest build status for plan
- get_build(build_key, include_commits?) → Detailed build info. Returns BuildResultData with stages[].jobs[].resultKey usable as the build_key parameter for get_build_log/get_test_results. Pass include_commits=true to also fetch the per-build commit list (SHA, message, author) and complete the Bamboo→Bitbucket→Jira triangle.
- trigger_build(plan_key, variables?) → Trigger new build (variables: JSON {"key":"value"})
- get_build_log(build_key) → Build log output. Accepts a build key (e.g. PROJ-PLAN138-4) for the whole-build log, OR a job-level resultKey from get_build's stages[].jobs[].resultKey (e.g. PROJ-PLAN138-UNIT-4) for just that job's log. Prefer per-job logs when triaging a single failing job.
- get_test_results(build_key) → Test results for build
- stop_build(build_key) → Stop running build
- cancel_build(build_key) → Cancel queued build
- get_artifacts(build_key) → List build artifacts
- download_artifact(artifact_url, target_path?) → Download build artifact to local file
- recent_builds(plan_key, branch?, repo_name?, max_results?) → Recent builds (default 10)
- get_running_builds(plan_key, repo_name?) → Currently running builds

build_key: Bamboo build result key (e.g. PROJ-PLAN-123) — used across all actions that operate on a single build.
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
                description = "Bamboo build result key e.g. PROJ-PLAN-123 — used across all actions that operate on a single build"
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
            ),
            "include_commits" to ParameterProperty(
                type = "boolean",
                description = "If true, also fetch the per-build commit list (SHA, message, author) via Bamboo's expand=changes.change. Default false to keep token cost low."
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
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getLatestBuild(planKey, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "get_build" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                val includeCommits = params["include_commits"]?.jsonPrimitive?.content?.lowercase() == "true"
                executeGetBuildForTest(buildKey, includeCommits, service)
            }

            "trigger_build" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val variables = when (val parsed = BambooToolUtils.parseVariables(params["variables"]?.jsonPrimitive?.content)) {
                    is BambooToolUtils.VariablesParseResult.Success -> parsed.variables
                    is BambooToolUtils.VariablesParseResult.Failure -> return parsed.error
                }
                service.triggerBuild(planKey, variables).toAgentToolResult()
            }

            "get_build_log" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.getBuildLog(buildKey).toAgentToolResult()
            }

            "get_test_results" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.getTestResults(buildKey).toAgentToolResult()
            }

            "stop_build" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content
                    ?: params["result_key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.stopBuild(buildKey).toAgentToolResult()
            }

            "cancel_build" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content
                    ?: params["result_key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.cancelBuild(buildKey).toAgentToolResult()
            }

            "get_artifacts" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content
                    ?: params["result_key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.getArtifacts(buildKey).toAgentToolResult()
            }

            "download_artifact" -> {
                val artifactUrl = params["artifact_url"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("artifact_url")
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
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                val maxResults = params["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getRecentBuilds(planKey, maxResults, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "get_running_builds" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
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

    internal suspend fun executeGetBuildForTest(
        buildKey: String,
        includeCommits: Boolean,
        service: com.workflow.orchestrator.core.services.BambooService
    ): ToolResult {
        if (!includeCommits) {
            return service.getBuild(buildKey).toAgentToolResult()
        }
        return coroutineScope {
            val buildDeferred = async { service.getBuild(buildKey) }
            val changesDeferred = async { service.getBuildChanges(buildKey) }
            val buildResult = buildDeferred.await()
            val changes = changesDeferred.await()
            if (buildResult.isError) return@coroutineScope buildResult.toAgentToolResult()
            val buildAgent = buildResult.toAgentToolResult()
            val commitsBlock = formatBuildCommits(changes.data ?: emptyList())
            val combined = buildAgent.content + "\n\n" + commitsBlock
            ToolResult(combined, "${buildAgent.summary} · ${changes.summary}", TokenEstimator.estimate(combined))
        }
    }

    private fun formatBuildCommits(commits: List<BuildChangeData>): String {
        if (commits.isEmpty()) return "Commits: (none)"
        val lines = commits.take(50).map { c ->
            "• ${c.changesetId.take(8)} · ${c.fullName.ifBlank { c.userName }.ifBlank { "—" }} · ${(c.comment.lineSequence().firstOrNull() ?: "").take(200)}"
        }
        val tail = if (commits.size > 50) "\n  …(${commits.size - 50} more commits)" else ""
        return "Commits (showing ${minOf(50, commits.size)} of ${commits.size}):\n" + lines.joinToString("\n") + tail
    }
}
