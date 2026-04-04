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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class GitFileHistoryTool : AgentTool {
    override val name = "git_file_history"
    override val description = "Show commit history for a specific file with rename tracking (--follow). " +
        "Useful for understanding how a file evolved over time, who made changes, and when renames occurred."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path relative to project root."),
            "max_count" to ParameterProperty(type = "integer", description = "Maximum number of commits to show. Default: 15, max: 30.")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    companion object {
        private const val MAX_OUTPUT_CHARS = 15_000
        private const val DEFAULT_MAX_COUNT = 15
        private const val ABSOLUTE_MAX_COUNT = 30
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val path = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter is required.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val maxCount = (params["max_count"]?.jsonPrimitive?.int ?: DEFAULT_MAX_COUNT)
            .coerceIn(1, ABSOLUTE_MAX_COUNT)

        return try {
            withContext(Dispatchers.IO) {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull()
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
                handler.addParameters(
                    "--follow",
                    "-n", maxCount.toString(),
                    "--format=%H%n%an <%ae>%n%ai%n%s%n---",
                    "--"
                )
                handler.addParameters(path)

                val result = Git.getInstance().runCommand(handler)
                val output = result.getOutputOrThrow()

                if (output.isBlank()) {
                    return@withContext ToolResult("No history found for '$path'. Check the file path is correct.", "No history", 5)
                }

                val header = "History for $path ($maxCount max):\n\n"

                val truncated = if (output.length + header.length > MAX_OUTPUT_CHARS) {
                    truncateOutput(output, MAX_OUTPUT_CHARS - header.length)
                } else {
                    output
                }

                val content = header + truncated
                val commitCount = output.split("---").count { it.isNotBlank() }

                ToolResult(
                    content = content,
                    summary = "File history: $path ($commitCount commits)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error getting file history: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
