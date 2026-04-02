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
 * Pull request lifecycle — create, inspect, approve, merge, decline, update PRs.
 *
 * 14 actions: create_pr, get_pr_detail, get_pr_commits, get_pr_activities,
 * get_pr_changes, get_pr_diff, check_merge_status, approve_pr, merge_pr,
 * decline_pr, update_pr_title, update_pr_description, get_my_prs, get_reviewing_prs
 */
class BitbucketPrTool : AgentTool {

    override val name = "bitbucket_pr"

    override val description = """
Pull request lifecycle — create, inspect, approve, merge, decline, update PRs.

Actions and their parameters:
- create_pr(title, pr_description, from_branch, to_branch?) → Create pull request
- get_pr_detail(pr_id) → Full PR info with reviewers and status
- get_pr_commits(pr_id) → List commits in PR
- get_pr_activities(pr_id) → Activity feed (comments, approvals, merges)
- get_pr_changes(pr_id) → List changed files in PR
- get_pr_diff(pr_id) → Raw diff of PR changes
- check_merge_status(pr_id) → Check if PR can be merged (vetoes, conflicts)
- approve_pr(pr_id) → Approve the pull request
- merge_pr(pr_id, strategy?, delete_source_branch?, commit_message?) → Merge the PR
- decline_pr(pr_id) → Decline the pull request
- update_pr_title(pr_id, new_title) → Change PR title
- update_pr_description(pr_id, pr_description) → Change PR description
- get_my_prs(state?) → List PRs authored by current user
- get_reviewing_prs(state?) → List PRs where current user is reviewer

Common optional: repo_name for multi-repo projects. description for approval dialog on write actions.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Operation to perform",
                enumValues = listOf(
                    "create_pr", "get_pr_detail", "get_pr_commits", "get_pr_activities",
                    "get_pr_changes", "get_pr_diff", "check_merge_status", "approve_pr",
                    "merge_pr", "decline_pr", "update_pr_title", "update_pr_description",
                    "get_my_prs", "get_reviewing_prs"
                )),
            "pr_id"                to ParameterProperty("string", "Pull request ID (numeric) — for most PR actions"),
            "title"                to ParameterProperty("string", "PR title — for create_pr"),
            "pr_description"       to ParameterProperty("string", "PR body/description text — for create_pr, update_pr_description"),
            "from_branch"          to ParameterProperty("string", "Source branch — for create_pr"),
            "to_branch"            to ParameterProperty("string", "Target branch (default: master) — for create_pr"),
            "new_title"            to ParameterProperty("string", "New PR title — for update_pr_title"),
            "state"                to ParameterProperty("string", "PR state: OPEN, MERGED, DECLINED (default OPEN) — for get_my_prs, get_reviewing_prs"),
            "strategy"             to ParameterProperty("string", "Merge strategy: merge-commit, squash, ff-only — for merge_pr"),
            "delete_source_branch" to ParameterProperty("string", "Delete source branch after merge: true/false — for merge_pr"),
            "commit_message"       to ParameterProperty("string", "Custom merge commit message — for merge_pr"),
            "repo_name"            to ParameterProperty("string", "Repository name for multi-repo projects — omit for primary"),
            "description"          to ParameterProperty("string", "Approval dialog description for write actions: create_pr, approve_pr, merge_pr, decline_pr")
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
            "create_pr" -> {
                val title = params["title"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("title")
                val prDescription = params["pr_description"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("pr_description")
                val fromBranch = params["from_branch"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("from_branch")
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

            "get_pr_detail" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestDetail(prId, repoName = repoName).toAgentToolResult()
            }

            "get_pr_commits" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestCommits(prId, repoName = repoName).toAgentToolResult()
            }

            "get_pr_activities" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestActivities(prId, repoName = repoName).toAgentToolResult()
            }

            "get_pr_changes" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestChanges(prId, repoName = repoName).toAgentToolResult()
            }

            "get_pr_diff" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestDiff(prId, repoName = repoName).toAgentToolResult()
            }

            "check_merge_status" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.checkMergeStatus(prId, repoName = repoName).toAgentToolResult()
            }

            "approve_pr" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.approvePullRequest(prId, repoName = repoName).toAgentToolResult()
            }

            "merge_pr" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val strategy = params["strategy"]?.jsonPrimitive?.content
                val deleteSourceBranch = params["delete_source_branch"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val commitMessage = params["commit_message"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.mergePullRequest(prId, strategy, deleteSourceBranch, commitMessage, repoName = repoName).toAgentToolResult()
            }

            "decline_pr" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.declinePullRequest(prId, repoName = repoName).toAgentToolResult()
            }

            "update_pr_title" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val newTitle = params["new_title"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("new_title")
                ToolValidation.validateNotBlank(newTitle, "new_title")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.updatePrTitle(prId, newTitle, repoName = repoName).toAgentToolResult()
            }

            "update_pr_description" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val newDescription = params["pr_description"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("pr_description")
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.updatePrDescription(prId, newDescription, repoName = repoName).toAgentToolResult()
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

            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
