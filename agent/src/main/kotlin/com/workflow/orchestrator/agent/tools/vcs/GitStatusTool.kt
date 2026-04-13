package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitStatusTool : AgentTool {
    override val name = "git_status"
    override val description = "Get the current git working tree status including branch name, remote tracking info, staged and unstaged changed files, and untracked files. Use this before making commits to see what has changed, to understand the current workspace state, or to check for uncommitted changes before switching branches. Do NOT use this for viewing actual content of changes (use git_diff instead) or for commit history (use git_log instead). Output is truncated to 50 changed files and 30 untracked files for large projects, so some entries may be omitted in very active repositories."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "repo" to ParameterProperty(type = "string", description = "Optional: git root path (relative to project, absolute, or directory name) to target in multi-root projects. Omit for single-repo projects.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val repoParam = params["repo"]?.jsonPrimitive?.content
            val repo = GitRepoResolver.resolve(project, repo = repoParam)
                ?: return ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val branch = repo.currentBranch?.name ?: "DETACHED HEAD"
            val tracking = repo.currentBranch?.let {
                repo.getBranchTrackInfo(it.name)?.remoteBranch?.nameForRemoteOperations
            } ?: "none"

            val repoRootPath = repo.root.path

            val content = ReadAction.compute<String, Exception> {
                val clm = ChangeListManager.getInstance(project)
                // Filter changes to this repo root in multi-root projects
                val changes = clm.allChanges.filter { change ->
                    val filePath = change.virtualFile?.path
                        ?: change.afterRevision?.file?.path
                        ?: change.beforeRevision?.file?.path
                    filePath != null && filePath.startsWith(repoRootPath)
                }
                val untracked = clm.modifiedWithoutEditing.filter { it.path.startsWith(repoRootPath) }

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
