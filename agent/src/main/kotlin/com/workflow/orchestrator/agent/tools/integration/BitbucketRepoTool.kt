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
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Repository-level operations — branches, users, files, build statuses, repo listing.
 *
 * 6 actions: get_branches, create_branch, search_users, get_file_content,
 * get_build_statuses, list_repos
 */
class BitbucketRepoTool : AgentTool {

    override val name = "bitbucket_repo"

    override val description = """
Repository-level operations — branches, users, files, build statuses, repo listing.

Actions and their parameters:
- get_branches(filter?) → List branches, optionally filtered by name
- create_branch(name, start_point) → Create a new branch from a ref
- search_users(filter) → Search for users by name/username
- get_file_content(file_path, at_ref) → Get file content at a specific git ref
- get_build_statuses(commit_id) → Get CI build statuses for a commit
- list_repos() → List all repositories in the project

Common optional: repo_name for multi-repo projects. description for approval dialog on write actions.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Operation to perform",
                enumValues = listOf(
                    "get_branches", "create_branch", "search_users",
                    "get_file_content", "get_build_statuses", "list_repos"
                )),
            "filter"      to ParameterProperty("string", "Name filter — for get_branches, search_users"),
            "name"        to ParameterProperty("string", "Branch name — for create_branch"),
            "start_point" to ParameterProperty("string", "Source ref to branch from — for create_branch"),
            "file_path"   to ParameterProperty("string", "File path — for get_file_content"),
            "at_ref"      to ParameterProperty("string", "Git ref (branch/tag/commit) — for get_file_content"),
            "commit_id"   to ParameterProperty("string", "Commit hash — for get_build_statuses"),
            "repo_name"   to ParameterProperty("string", "Repository name for multi-repo projects — omit for primary"),
            "description" to ParameterProperty("string", "Approval dialog description for write actions: create_branch")
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.TOOLER,
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
        WorkerType.REVIEWER,
        WorkerType.ANALYZER
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val service = ServiceLookup.bitbucket(project)
            ?: return ServiceLookup.notConfigured("Bitbucket")

        return when (action) {
            "get_branches" -> {
                val filter = params["filter"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getBranches(filter, repoName = repoName).toAgentToolResult()
            }

            "create_branch" -> {
                val name = params["name"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("name")
                val startPoint = params["start_point"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("start_point")
                ToolValidation.validateNotBlank(name, "name")?.let { return it }
                ToolValidation.validateNotBlank(startPoint, "start_point")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.createBranch(name, startPoint, repoName = repoName).toAgentToolResult()
            }

            "search_users" -> {
                val filter = params["filter"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("filter")
                ToolValidation.validateNotBlank(filter, "filter")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.searchUsers(filter, repoName = repoName).toAgentToolResult()
            }

            "get_file_content" -> {
                val filePath = params["file_path"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("file_path")
                val atRef = params["at_ref"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("at_ref")
                ToolValidation.validateNotBlank(filePath, "file_path")?.let { return it }
                ToolValidation.validateNotBlank(atRef, "at_ref")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getFileContent(filePath, atRef, repoName = repoName).toAgentToolResult()
            }

            "get_build_statuses" -> {
                val commitId = params["commit_id"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("commit_id")
                ToolValidation.validateNotBlank(commitId, "commit_id")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getBuildStatuses(commitId, repoName = repoName).toAgentToolResult()
            }

            "list_repos" -> {
                service.listRepos().toAgentToolResult()
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
