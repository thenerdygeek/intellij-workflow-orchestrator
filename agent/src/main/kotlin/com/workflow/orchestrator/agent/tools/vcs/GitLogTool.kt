package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.context.ToolOutputStore
import com.workflow.orchestrator.agent.runtime.WorkerType
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class GitLogTool : AgentTool {
    override val name = "git_log"
    override val description = "Show git commit history. Useful for understanding recent changes, finding when a file was modified, " +
        "or reviewing commit messages. Remote refs (origin/, upstream/) are blocked for safety."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "max_count" to ParameterProperty(type = "integer", description = "Maximum number of commits to show. Default: 20, max: 50."),
            "path" to ParameterProperty(type = "string", description = "Optional: file or directory path to show history for (relative to project root)."),
            "ref" to ParameterProperty(type = "string", description = "Optional: local ref (branch, tag, or commit SHA) to start from. Remote refs like 'origin/main' are rejected."),
            "oneline" to ParameterProperty(type = "boolean", description = "If true, show compact one-line format. Default: false (shows full format with author, date, message).")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    companion object {
        private const val MAX_OUTPUT_CHARS = 20_000
        private const val DEFAULT_MAX_COUNT = 20
        private const val ABSOLUTE_MAX_COUNT = 50
        private val REMOTE_REF_PATTERN = Regex("""(origin|upstream)/""")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val maxCount = (params["max_count"]?.jsonPrimitive?.int ?: DEFAULT_MAX_COUNT)
            .coerceIn(1, ABSOLUTE_MAX_COUNT)
        val path = params["path"]?.jsonPrimitive?.content
        val ref = params["ref"]?.jsonPrimitive?.content
        val oneline = params["oneline"]?.jsonPrimitive?.boolean ?: false

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

                val handler = GitLineHandler(project, repo.root, GitCommand.LOG)

                handler.addParameters("-n", maxCount.toString())

                if (oneline) {
                    handler.addParameters("--oneline")
                } else {
                    handler.addParameters("--format=%H%n%an <%ae>%n%ai%n%s%n%b%n---")
                }

                if (ref != null) {
                    handler.addParameters(ref)
                }

                if (path != null) {
                    handler.addParameters("--")
                    handler.addParameters(path)
                }

                val result = Git.getInstance().runCommand(handler)
                val output = result.getOutputOrThrow()

                if (output.isBlank()) {
                    val scope = buildString {
                        append("No commits found")
                        if (ref != null) append(" on $ref")
                        if (path != null) append(" for $path")
                    }
                    return@withContext ToolResult("$scope.", scope, 5)
                }

                val truncated = if (output.length > MAX_OUTPUT_CHARS) {
                    ToolOutputStore.middleTruncate(output, MAX_OUTPUT_CHARS)
                } else {
                    output
                }

                val summary = buildString {
                    append("Git log ($maxCount commits)")
                    if (ref != null) append(" on $ref")
                    if (path != null) append(" — $path")
                }

                ToolResult(
                    content = truncated,
                    summary = summary,
                    tokenEstimate = TokenEstimator.estimate(truncated)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error running git log: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
