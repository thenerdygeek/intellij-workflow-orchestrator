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
 * Consolidated Bitbucket meta-tool replacing 26 individual bitbucket_* tools.
 *
 * Saves token budget per API call by collapsing all Bitbucket operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: create_pr, get_pr_commits, add_inline_comment, reply_to_comment,
 *          set_reviewer_status, get_file_content, add_reviewer, update_pr_title,
 *          get_branches, create_branch, search_users, get_my_prs, get_reviewing_prs,
 *          get_pr_detail, get_pr_activities, get_pr_changes, get_pr_diff,
 *          get_build_statuses, approve_pr, merge_pr, decline_pr,
 *          update_pr_description, add_pr_comment, check_merge_status,
 *          remove_reviewer, list_repos
 */
class BitbucketTool : AgentTool {

    override val name = "bitbucket"

    override val description =
        "Bitbucket pull request and repository integration — PRs, reviews, branches, files, build statuses.\n" +
        "Actions: create_pr, get_pr_commits, add_inline_comment, reply_to_comment, set_reviewer_status,\n" +
        "get_file_content, add_reviewer, update_pr_title, get_branches, create_branch, search_users,\n" +
        "get_my_prs, get_reviewing_prs, get_pr_detail, get_pr_activities, get_pr_changes, get_pr_diff,\n" +
        "get_build_statuses, approve_pr, merge_pr, decline_pr, update_pr_description, add_pr_comment,\n" +
        "check_merge_status, remove_reviewer, list_repos"

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Operation to perform",
                enumValues = listOf(
                    "create_pr", "get_pr_commits", "add_inline_comment", "reply_to_comment",
                    "set_reviewer_status", "get_file_content", "add_reviewer", "update_pr_title",
                    "get_branches", "create_branch", "search_users", "get_my_prs", "get_reviewing_prs",
                    "get_pr_detail", "get_pr_activities", "get_pr_changes", "get_pr_diff",
                    "get_build_statuses", "approve_pr", "merge_pr", "decline_pr",
                    "update_pr_description", "add_pr_comment", "check_merge_status",
                    "remove_reviewer", "list_repos"
                )),
            "pr_id"            to ParameterProperty("string", "Pull request ID (numeric) — for most PR actions"),
            "title"            to ParameterProperty("string", "PR title — for create_pr"),
            "pr_description"   to ParameterProperty("string", "PR body/description text — for create_pr, update_pr_description"),
            "from_branch"      to ParameterProperty("string", "Source branch — for create_pr"),
            "to_branch"        to ParameterProperty("string", "Target branch (default: master) — for create_pr"),
            "file_path"        to ParameterProperty("string", "File path — for add_inline_comment, get_file_content"),
            "at_ref"           to ParameterProperty("string", "Git ref (branch/tag/commit) — for get_file_content"),
            "line"             to ParameterProperty("string", "Line number — for add_inline_comment"),
            "line_type"        to ParameterProperty("string", "Line type: ADDED, REMOVED, CONTEXT — for add_inline_comment"),
            "text"             to ParameterProperty("string", "Comment/reply text — for add_inline_comment, reply_to_comment, add_pr_comment"),
            "parent_comment_id" to ParameterProperty("string", "Parent comment ID (integer) — for reply_to_comment"),
            "username"         to ParameterProperty("string", "Reviewer username — for add_reviewer, remove_reviewer, set_reviewer_status"),
            "status"           to ParameterProperty("string", "Reviewer status: APPROVED, NEEDS_WORK, UNAPPROVED — for set_reviewer_status"),
            "new_title"        to ParameterProperty("string", "New PR title — for update_pr_title"),
            "name"             to ParameterProperty("string", "Branch name — for create_branch"),
            "start_point"      to ParameterProperty("string", "Source ref to branch from — for create_branch"),
            "filter"           to ParameterProperty("string", "Name filter — for get_branches, search_users"),
            "state"            to ParameterProperty("string", "PR state: OPEN, MERGED, DECLINED (default OPEN) — for get_my_prs, get_reviewing_prs"),
            "commit_id"        to ParameterProperty("string", "Commit hash — for get_build_statuses"),
            "strategy"         to ParameterProperty("string", "Merge strategy: merge-commit, squash, ff-only — for merge_pr"),
            "delete_source_branch" to ParameterProperty("string", "Delete source branch after merge: true/false — for merge_pr"),
            "commit_message"   to ParameterProperty("string", "Custom merge commit message — for merge_pr"),
            "repo_name"        to ParameterProperty("string", "Repository name for multi-repo projects — omit for primary"),
            "description"      to ParameterProperty("string", "Approval dialog description for write actions: create_pr, approve_pr, merge_pr, decline_pr, add_pr_comment, create_branch, set_reviewer_status")
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
            "create_pr" -> {
                val title = params["title"]?.jsonPrimitive?.content ?: return missingParam("title")
                val prDescription = params["pr_description"]?.jsonPrimitive?.content ?: return missingParam("pr_description")
                val fromBranch = params["from_branch"]?.jsonPrimitive?.content ?: return missingParam("from_branch")
                val toBranch = params["to_branch"]?.jsonPrimitive?.content ?: "master"
                ToolValidation.validateNotBlank(title, "title")?.let { return it }
                ToolValidation.validateNotBlank(fromBranch, "from_branch")?.let { return it }
                if (fromBranch == toBranch) return ToolResult(
                    "Cannot create PR: source branch '$fromBranch' is the same as target branch '$toBranch'.",
                    "Same source and target branch",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.createPullRequest(title, prDescription, fromBranch, toBranch, repoName = repoName).toAgentToolResult()
            }

            "get_pr_commits" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestCommits(prId, repoName = repoName).toAgentToolResult()
            }

            "add_inline_comment" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val filePath = params["file_path"]?.jsonPrimitive?.content ?: return missingParam("file_path")
                val lineStr = params["line"]?.jsonPrimitive?.content ?: return missingParam("line")
                val line = lineStr.toIntOrNull() ?: return ToolResult(
                    "Error: 'line' must be an integer, got '$lineStr'",
                    "Error: invalid line",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val lineType = params["line_type"]?.jsonPrimitive?.content ?: return missingParam("line_type")
                val text = params["text"]?.jsonPrimitive?.content ?: return missingParam("text")
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.addInlineComment(prId, filePath, line, lineType, text, repoName = repoName).toAgentToolResult()
            }

            "reply_to_comment" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val parentIdStr = params["parent_comment_id"]?.jsonPrimitive?.content ?: return missingParam("parent_comment_id")
                val parentId = parentIdStr.toIntOrNull() ?: return ToolResult(
                    "Error: 'parent_comment_id' must be an integer, got '$parentIdStr'",
                    "Error: invalid parent_comment_id",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val text = params["text"]?.jsonPrimitive?.content ?: return missingParam("text")
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.replyToComment(prId, parentId, text, repoName = repoName).toAgentToolResult()
            }

            "set_reviewer_status" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val username = params["username"]?.jsonPrimitive?.content ?: return missingParam("username")
                val status = params["status"]?.jsonPrimitive?.content ?: return missingParam("status")
                if (status !in setOf("APPROVED", "NEEDS_WORK", "UNAPPROVED")) return ToolResult(
                    "Error: 'status' must be APPROVED, NEEDS_WORK, or UNAPPROVED",
                    "Error: invalid status",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.setReviewerStatus(prId, username, status, repoName = repoName).toAgentToolResult()
            }

            "get_file_content" -> {
                val filePath = params["file_path"]?.jsonPrimitive?.content ?: return missingParam("file_path")
                val atRef = params["at_ref"]?.jsonPrimitive?.content ?: return missingParam("at_ref")
                ToolValidation.validateNotBlank(filePath, "file_path")?.let { return it }
                ToolValidation.validateNotBlank(atRef, "at_ref")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getFileContent(filePath, atRef, repoName = repoName).toAgentToolResult()
            }

            "add_reviewer" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val username = params["username"]?.jsonPrimitive?.content ?: return missingParam("username")
                ToolValidation.validateNotBlank(username, "username")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.addReviewer(prId, username, repoName = repoName).toAgentToolResult()
            }

            "update_pr_title" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val newTitle = params["new_title"]?.jsonPrimitive?.content ?: return missingParam("new_title")
                ToolValidation.validateNotBlank(newTitle, "new_title")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.updatePrTitle(prId, newTitle, repoName = repoName).toAgentToolResult()
            }

            "get_branches" -> {
                val filter = params["filter"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getBranches(filter, repoName = repoName).toAgentToolResult()
            }

            "create_branch" -> {
                val name = params["name"]?.jsonPrimitive?.content ?: return missingParam("name")
                val startPoint = params["start_point"]?.jsonPrimitive?.content ?: return missingParam("start_point")
                ToolValidation.validateNotBlank(name, "name")?.let { return it }
                ToolValidation.validateNotBlank(startPoint, "start_point")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.createBranch(name, startPoint, repoName = repoName).toAgentToolResult()
            }

            "search_users" -> {
                val filter = params["filter"]?.jsonPrimitive?.content ?: return missingParam("filter")
                ToolValidation.validateNotBlank(filter, "filter")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.searchUsers(filter, repoName = repoName).toAgentToolResult()
            }

            "get_my_prs" -> {
                val state = params["state"]?.jsonPrimitive?.content ?: "OPEN"
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getMyPullRequests(state, repoName = repoName).toAgentToolResult()
            }

            "get_reviewing_prs" -> {
                val state = params["state"]?.jsonPrimitive?.content ?: "OPEN"
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getReviewingPullRequests(state, repoName = repoName).toAgentToolResult()
            }

            "get_pr_detail" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestDetail(prId, repoName = repoName).toAgentToolResult()
            }

            "get_pr_activities" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestActivities(prId, repoName = repoName).toAgentToolResult()
            }

            "get_pr_changes" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestChanges(prId, repoName = repoName).toAgentToolResult()
            }

            "get_pr_diff" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestDiff(prId, repoName = repoName).toAgentToolResult()
            }

            "get_build_statuses" -> {
                val commitId = params["commit_id"]?.jsonPrimitive?.content ?: return missingParam("commit_id")
                ToolValidation.validateNotBlank(commitId, "commit_id")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getBuildStatuses(commitId, repoName = repoName).toAgentToolResult()
            }

            "approve_pr" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.approvePullRequest(prId, repoName = repoName).toAgentToolResult()
            }

            "merge_pr" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val strategy = params["strategy"]?.jsonPrimitive?.content
                val deleteSourceBranch = params["delete_source_branch"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val commitMessage = params["commit_message"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.mergePullRequest(prId, strategy, deleteSourceBranch, commitMessage, repoName = repoName).toAgentToolResult()
            }

            "decline_pr" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.declinePullRequest(prId, repoName = repoName).toAgentToolResult()
            }

            "update_pr_description" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val newDescription = params["pr_description"]?.jsonPrimitive?.content ?: return missingParam("pr_description")
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.updatePrDescription(prId, newDescription, repoName = repoName).toAgentToolResult()
            }

            "add_pr_comment" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val text = params["text"]?.jsonPrimitive?.content ?: return missingParam("text")
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.addPrComment(prId, text, repoName = repoName).toAgentToolResult()
            }

            "check_merge_status" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.checkMergeStatus(prId, repoName = repoName).toAgentToolResult()
            }

            "remove_reviewer" -> {
                val prId = parsePrId(params) ?: return invalidPrId()
                val username = params["username"]?.jsonPrimitive?.content ?: return missingParam("username")
                ToolValidation.validateNotBlank(username, "username")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.removeReviewer(prId, username, repoName = repoName).toAgentToolResult()
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

    private fun parsePrId(params: JsonObject): Int? =
        params["pr_id"]?.jsonPrimitive?.content?.toIntOrNull()

    private fun invalidPrId(): ToolResult = ToolResult(
        "Error: 'pr_id' must be a valid integer",
        "Error: invalid pr_id",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

    private fun missingParam(name: String): ToolResult = ToolResult(
        content = "Error: '$name' parameter required",
        summary = "Error: missing $name",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
}
