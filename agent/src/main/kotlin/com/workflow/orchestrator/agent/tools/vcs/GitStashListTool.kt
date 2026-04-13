package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitStashListTool : AgentTool {
    override val name = "git_stash_list"
    override val description = "List all git stashes with index, message, branch, and date. " +
        "Useful for finding saved work-in-progress changes."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "repo" to ParameterProperty(type = "string", description = "Optional: git root path (relative to project, absolute, or directory name) to target in multi-root projects.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val repoParam = params["repo"]?.jsonPrimitive?.content

        return try {
            withContext(Dispatchers.IO) {
                val repo = GitRepoResolver.resolve(project, repo = repoParam)
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val handler = GitLineHandler(project, repo.root, GitCommand.STASH)
                handler.addParameters("list", "--format=%gd|%ai|%gs")

                val result = Git.getInstance().runCommand(handler)
                val output = result.getOutputOrThrow()

                if (output.isBlank()) {
                    return@withContext ToolResult("No stashes found.", "No stashes", 5)
                }

                val content = buildString {
                    appendLine("Stashes:")
                    appendLine()
                    output.lines().filter { it.isNotBlank() }.forEach { line ->
                        val parts = line.split("|", limit = 3)
                        if (parts.size == 3) {
                            val index = parts[0]   // stash@{N}
                            val date = parts[1]     // author date
                            val message = parts[2]  // stash message
                            appendLine("  $index  $date  $message")
                        } else {
                            appendLine("  $line")
                        }
                    }
                }

                val stashCount = output.lines().count { it.isNotBlank() }

                ToolResult(
                    content = content,
                    summary = "Stash list ($stashCount stashes)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error listing stashes: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
