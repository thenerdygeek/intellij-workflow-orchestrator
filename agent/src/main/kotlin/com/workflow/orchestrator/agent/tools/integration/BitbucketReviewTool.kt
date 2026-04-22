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
 * PR review actions — comments, inline comments, replies, reviewer management.
 *
 * 7 actions: add_pr_comment, add_inline_comment, reply_to_comment,
 * add_reviewer, remove_reviewer, set_reviewer_status, list_comments
 */
class BitbucketReviewTool : AgentTool {

    override val name = "bitbucket_review"

    override val description = """
PR review actions — comments, inline comments, replies, reviewer management.

Actions and their parameters:
- add_pr_comment(pr_id, text) → Add a general comment to a PR
- add_inline_comment(pr_id, file_path, line, line_type, text) → Add inline comment on a specific line
- reply_to_comment(pr_id, parent_comment_id, text) → Reply to an existing comment thread
- add_reviewer(pr_id, username) → Add a reviewer to a PR
- remove_reviewer(pr_id, username) → Remove a reviewer from a PR
- set_reviewer_status(pr_id, username, status) → Set reviewer status: APPROVED, NEEDS_WORK, UNAPPROVED
- list_comments(project_key, repo_slug, pr_id, only_open?, only_inline?) → List all comments on a PR (filter by open/inline)

Common optional: repo_name for multi-repo projects. description for approval dialog on write actions.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Operation to perform",
                enumValues = listOf(
                    "add_pr_comment", "add_inline_comment", "reply_to_comment",
                    "add_reviewer", "remove_reviewer", "set_reviewer_status",
                    "list_comments"
                )),
            "pr_id"             to ParameterProperty("string", "Pull request ID (numeric) — required for all actions"),
            "text"              to ParameterProperty("string", "Comment/reply text — for add_pr_comment, add_inline_comment, reply_to_comment"),
            "file_path"         to ParameterProperty("string", "File path — for add_inline_comment"),
            "line"              to ParameterProperty("string", "Line number — for add_inline_comment"),
            "line_type"         to ParameterProperty("string", "Line type: ADDED, REMOVED, CONTEXT — for add_inline_comment"),
            "parent_comment_id" to ParameterProperty("string", "Parent comment ID (integer) — for reply_to_comment"),
            "username"          to ParameterProperty("string", "Reviewer username — for add_reviewer, remove_reviewer, set_reviewer_status"),
            "status"            to ParameterProperty("string", "Reviewer status: APPROVED, NEEDS_WORK, UNAPPROVED — for set_reviewer_status"),
            "repo_name"         to ParameterProperty("string", "Repository name for multi-repo projects — omit for primary"),
            "description"       to ParameterProperty("string", "Approval dialog description for write actions: add_pr_comment, set_reviewer_status"),
            "project_key"       to ParameterProperty("string", "Bitbucket project key (e.g. PROJ) — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment"),
            "repo_slug"         to ParameterProperty("string", "Repository slug — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment"),
            "only_open"         to ParameterProperty("string", "Filter to open comments only: true/false — for list_comments"),
            "only_inline"       to ParameterProperty("string", "Filter to inline comments only: true/false — for list_comments")
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
            "add_pr_comment" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val text = params["text"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("text")
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.addPrComment(prId, text, repoName = repoName).toAgentToolResult()
            }

            "add_inline_comment" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val filePath = params["file_path"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("file_path")
                val lineStr = params["line"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("line")
                val line = lineStr.toIntOrNull() ?: return ToolResult(
                    "Error: 'line' must be an integer, got '$lineStr'",
                    "Error: invalid line",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val lineType = params["line_type"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("line_type")
                val text = params["text"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("text")
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.addInlineComment(prId, filePath, line, lineType, text, repoName = repoName).toAgentToolResult()
            }

            "reply_to_comment" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val parentIdStr = params["parent_comment_id"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("parent_comment_id")
                val parentId = parentIdStr.toIntOrNull() ?: return ToolResult(
                    "Error: 'parent_comment_id' must be an integer, got '$parentIdStr'",
                    "Error: invalid parent_comment_id",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val text = params["text"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("text")
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.replyToComment(prId, parentId, text, repoName = repoName).toAgentToolResult()
            }

            "add_reviewer" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val username = params["username"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("username")
                ToolValidation.validateNotBlank(username, "username")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.addReviewer(prId, username, repoName = repoName).toAgentToolResult()
            }

            "remove_reviewer" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val username = params["username"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("username")
                ToolValidation.validateNotBlank(username, "username")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.removeReviewer(prId, username, repoName = repoName).toAgentToolResult()
            }

            "set_reviewer_status" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val username = params["username"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("username")
                val status = params["status"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("status")
                if (status !in setOf("APPROVED", "NEEDS_WORK", "UNAPPROVED")) return ToolResult(
                    "Error: 'status' must be APPROVED, NEEDS_WORK, or UNAPPROVED",
                    "Error: invalid status",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.setReviewerStatus(prId, username, status, repoName = repoName).toAgentToolResult()
            }

            "list_comments" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("project_key")
                val repoSlug = params["repo_slug"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("repo_slug")
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val onlyOpen = params["only_open"]?.jsonPrimitive?.content?.toBoolean() ?: false
                val onlyInline = params["only_inline"]?.jsonPrimitive?.content?.toBoolean() ?: false
                service.listPrComments(projectKey, repoSlug, prId, onlyOpen, onlyInline).toAgentToolResult()
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
