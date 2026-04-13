package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class GitShowCommitTool : AgentTool {
    override val name = "git_show_commit"
    override val description = "Show full details for a specific commit: hash, author, date, message, and diff stat. " +
        "Optionally include the full diff. Remote refs (origin/, upstream/) are blocked for safety."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "commit" to ParameterProperty(type = "string", description = "Commit reference: SHA, HEAD, HEAD~N, or local branch name."),
            "include_diff" to ParameterProperty(type = "boolean", description = "If true, include the full diff (capped at 30K chars). Default: false (shows stat only)."),
            "repo" to ParameterProperty(type = "string", description = "Optional: git root path (relative to project, absolute, or directory name) to target in multi-root projects.")
        ),
        required = listOf("commit")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    companion object {
        private const val MAX_OUTPUT_CHARS = 30_000
        private val REMOTE_REF_PATTERN = Regex("""(origin|upstream)/""")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val commit = params["commit"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'commit' parameter is required.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val includeDiff = params["include_diff"]?.jsonPrimitive?.boolean ?: false
        val repoParam = params["repo"]?.jsonPrimitive?.content

        // Safety: reject remote refs
        if (REMOTE_REF_PATTERN.containsMatchIn(commit)) {
            return ToolResult(
                "Error: Remote refs (origin/, upstream/) are not allowed. Use a local branch, tag, or commit SHA instead.",
                "Error: remote ref blocked",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                val repo = GitRepoResolver.resolve(project, repo = repoParam)
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val handler = GitLineHandler(project, repo.root, GitCommand.SHOW)

                if (includeDiff) {
                    handler.addParameters("--stat", "--patch", commit)
                } else {
                    handler.addParameters("--stat", "--no-patch",
                        "--format=%H%n%an <%ae>%n%ai%n%s%n%n%b", commit)
                }

                val result = Git.getInstance().runCommand(handler)
                val output = result.getOutputOrThrow()

                if (output.isBlank()) {
                    return@withContext ToolResult("No commit found for '$commit'.", "Not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                }

                val truncated = if (output.length > MAX_OUTPUT_CHARS) {
                    truncateOutput(output, MAX_OUTPUT_CHARS)
                } else {
                    output
                }

                val summary = buildString {
                    append("Commit $commit")
                    if (includeDiff) append(" (with diff)")
                }

                ToolResult(
                    content = truncated,
                    summary = summary,
                    tokenEstimate = TokenEstimator.estimate(truncated)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error showing commit: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
