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
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class GitDiffTool : AgentTool {
    override val name = "git_diff"
    override val description = "Show git diff: working tree changes, staged changes, or diff against a local ref. " +
        "Use to review uncommitted work, staged changes, or compare with a local branch/tag. " +
        "Remote refs (origin/, upstream/) are blocked for safety."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Optional: file or directory path (relative to project root) to limit diff scope."),
            "staged" to ParameterProperty(type = "boolean", description = "If true, show only staged (cached) changes. Default: false (shows unstaged working tree diff)."),
            "ref" to ParameterProperty(type = "string", description = "Optional: local ref (branch, tag, or commit SHA) to diff against. Example: 'main', 'HEAD~3'. Remote refs like 'origin/main' are rejected.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    companion object {
        private const val MAX_OUTPUT_CHARS = 30_000
        private val REMOTE_REF_PATTERN = Regex("""(origin|upstream)/""")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val path = params["path"]?.jsonPrimitive?.content
        val staged = params["staged"]?.jsonPrimitive?.boolean ?: false
        val ref = params["ref"]?.jsonPrimitive?.content

        // Safety: reject remote refs
        if (ref != null && REMOTE_REF_PATTERN.containsMatchIn(ref)) {
            return ToolResult(
                "Error: Remote refs (origin/, upstream/) are not allowed. Use a local branch, tag, or commit SHA instead.",
                "Error: remote ref blocked",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull()
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val handler = GitLineHandler(project, repo.root, GitCommand.DIFF)

                if (staged) {
                    handler.addParameters("--cached")
                }

                if (ref != null) {
                    handler.addParameters(ref)
                }

                handler.addParameters("--stat-width=120")

                if (path != null) {
                    handler.addParameters("--")
                    handler.addParameters(path)
                }

                val result = Git.getInstance().runCommand(handler)
                val output = result.getOutputOrThrow()

                if (output.isBlank()) {
                    val scope = buildString {
                        if (staged) append("staged ")
                        append("diff")
                        if (ref != null) append(" against $ref")
                        if (path != null) append(" for $path")
                    }
                    return@withContext ToolResult("No differences found ($scope).", "No diff", 5)
                }

                val truncated = if (output.length > MAX_OUTPUT_CHARS) {
                    truncateOutput(output, MAX_OUTPUT_CHARS)
                } else {
                    output
                }

                val summary = buildString {
                    append("Git diff")
                    if (staged) append(" (staged)")
                    if (ref != null) append(" vs $ref")
                    if (path != null) append(" — $path")
                }

                ToolResult(
                    content = truncated,
                    summary = summary,
                    tokenEstimate = TokenEstimator.estimate(truncated)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error running git diff: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
