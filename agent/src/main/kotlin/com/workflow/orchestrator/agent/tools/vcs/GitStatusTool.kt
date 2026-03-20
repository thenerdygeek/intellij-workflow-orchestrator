package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import git4idea.repo.GitRepositoryManager
import kotlinx.serialization.json.JsonObject

class GitStatusTool : AgentTool {
    override val name = "git_status"
    override val description = "Get git status: current branch, remote tracking, changed files (staged/unstaged), and untracked files."
    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val repoManager = GitRepositoryManager.getInstance(project)
            val repo = repoManager.repositories.firstOrNull()
                ?: return ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val branch = repo.currentBranch?.name ?: "DETACHED HEAD"
            val tracking = repo.currentBranch?.let {
                repo.getBranchTrackInfo(it.name)?.remoteBranch?.nameForRemoteOperations
            } ?: "none"

            val content = ReadAction.compute<String, Exception> {
                val clm = ChangeListManager.getInstance(project)
                val changes = clm.allChanges
                val untracked = clm.modifiedWithoutEditing

                buildString {
                    appendLine("Branch: $branch")
                    appendLine("Tracking: $tracking")
                    appendLine()

                    if (changes.isEmpty() && untracked.isEmpty()) {
                        appendLine("Working tree clean — no changes.")
                        return@buildString
                    }

                    if (changes.isNotEmpty()) {
                        appendLine("Changed files (${changes.size}):")
                        changes.take(50).forEach { change ->
                            val type = change.type.name.lowercase()
                            val filePath = change.virtualFile?.path
                                ?: change.afterRevision?.file?.path
                                ?: change.beforeRevision?.file?.path
                                ?: "unknown"
                            // Make path relative to project
                            val relativePath = project.basePath?.let { base ->
                                if (filePath.startsWith(base)) filePath.removePrefix("$base/") else filePath
                            } ?: filePath
                            appendLine("  [$type] $relativePath")
                        }
                        if (changes.size > 50) {
                            appendLine("  ... and ${changes.size - 50} more")
                        }
                    }

                    if (untracked.isNotEmpty()) {
                        appendLine()
                        appendLine("Untracked/externally modified files (${untracked.size}):")
                        untracked.take(30).forEach { vf ->
                            val relativePath = project.basePath?.let { base ->
                                if (vf.path.startsWith(base)) vf.path.removePrefix("$base/") else vf.path
                            } ?: vf.path
                            appendLine("  $relativePath")
                        }
                        if (untracked.size > 30) {
                            appendLine("  ... and ${untracked.size - 30} more")
                        }
                    }
                }
            }

            ToolResult(
                content = content,
                summary = "Git status: $branch",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error getting git status: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
