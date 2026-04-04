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
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class GitBranchesTool : AgentTool {
    override val name = "git_branches"
    override val description = "List local and remote branches, and optionally tags. " +
        "Shows the current branch, tracking info, and remote branches. " +
        "Uses cached git repository data for branches (instant, no git process spawned)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "show_remote" to ParameterProperty(type = "boolean", description = "Show remote branches. Default: true."),
            "show_tags" to ParameterProperty(type = "boolean", description = "Show tags. Default: false.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    companion object {
        private const val MAX_BRANCHES = 100
        private const val MAX_TAGS = 50
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val showRemote = params["show_remote"]?.jsonPrimitive?.boolean ?: true
        val showTags = params["show_tags"]?.jsonPrimitive?.boolean ?: false

        return try {
            val repoManager = GitRepositoryManager.getInstance(project)
            val repo = repoManager.repositories.firstOrNull()
                ?: return ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val currentBranch = repo.currentBranch?.name ?: "DETACHED HEAD"
            val branches = repo.branches

            val content = buildString {
                appendLine("Current branch: $currentBranch")
                appendLine()

                // Local branches
                val localBranches = branches.localBranches.sortedBy { it.name }
                appendLine("Local branches (${localBranches.size}):")
                localBranches.take(MAX_BRANCHES).forEach { branch ->
                    val isCurrent = branch.name == currentBranch
                    val marker = if (isCurrent) "* " else "  "
                    val trackInfo = repo.getBranchTrackInfo(branch.name)
                    val tracking = if (trackInfo != null) {
                        " → ${trackInfo.remoteBranch.nameForRemoteOperations}"
                    } else ""
                    appendLine("$marker${branch.name}$tracking")
                }
                if (localBranches.size > MAX_BRANCHES) {
                    appendLine("  ... and ${localBranches.size - MAX_BRANCHES} more")
                }

                // Remote branches
                if (showRemote) {
                    val remoteBranches = branches.remoteBranches.sortedBy { it.name }
                    appendLine()
                    appendLine("Remote branches (${remoteBranches.size}):")
                    remoteBranches.take(MAX_BRANCHES).forEach { branch ->
                        appendLine("  ${branch.name}")
                    }
                    if (remoteBranches.size > MAX_BRANCHES) {
                        appendLine("  ... and ${remoteBranches.size - MAX_BRANCHES} more")
                    }
                }

                // Tags — use git tag command since GitRepository doesn't expose tags directly
                if (showTags) {
                    try {
                        val tagContent = withContext(Dispatchers.IO) {
                            val handler = GitLineHandler(project, repo.root, GitCommand.TAG)
                            handler.addParameters("--sort=-creatordate", "-l")
                            val result = Git.getInstance().runCommand(handler)
                            result.getOutputOrThrow()
                        }
                        val tagList = tagContent.lines().filter { it.isNotBlank() }
                        appendLine()
                        appendLine("Tags (${tagList.size}):")
                        tagList.take(MAX_TAGS).forEach { tag ->
                            appendLine("  ${tag.trim()}")
                        }
                        if (tagList.size > MAX_TAGS) {
                            appendLine("  ... and ${tagList.size - MAX_TAGS} more")
                        }
                    } catch (_: Exception) {
                        appendLine()
                        appendLine("Tags: (unable to list)")
                    }
                }
            }

            ToolResult(
                content = content,
                summary = "Branches: $currentBranch (${branches.localBranches.size} local)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error listing branches: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
