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
import kotlinx.serialization.json.jsonPrimitive

class GitMergeBaseTool : AgentTool {
    override val name = "git_merge_base"
    override val description = "Find the common ancestor (merge base) of two refs. " +
        "Useful for understanding where branches diverged and how many commits each has since. " +
        "Remote refs (origin/, upstream/) are blocked for safety."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "ref1" to ParameterProperty(type = "string", description = "First ref: local branch name, tag, or commit SHA."),
            "ref2" to ParameterProperty(type = "string", description = "Second ref: local branch name, tag, or commit SHA.")
        ),
        required = listOf("ref1", "ref2")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    companion object {
        private val REMOTE_REF_PATTERN = Regex("""(origin|upstream)/""")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val ref1 = params["ref1"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'ref1' parameter is required.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val ref2 = params["ref2"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'ref2' parameter is required.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        // Safety: reject remote refs
        if (REMOTE_REF_PATTERN.containsMatchIn(ref1) || REMOTE_REF_PATTERN.containsMatchIn(ref2)) {
            return ToolResult(
                "Error: Remote refs (origin/, upstream/) are not allowed. Use local branch names, tags, or commit SHAs instead.",
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

                // Find merge base
                val mbHandler = GitLineHandler(project, repo.root, GitCommand.MERGE_BASE)
                mbHandler.addParameters(ref1, ref2)
                val mbResult = Git.getInstance().runCommand(mbHandler)
                val mergeBase = mbResult.getOutputOrThrow().trim()

                if (mergeBase.isBlank()) {
                    return@withContext ToolResult(
                        "No common ancestor found between '$ref1' and '$ref2'. They may be unrelated histories.",
                        "No merge base",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                // Count commits since divergence for each ref
                val content = buildString {
                    appendLine("Merge base of '$ref1' and '$ref2':")
                    appendLine("  Common ancestor: $mergeBase")
                    appendLine()

                    // Count commits ref1 has since merge base
                    try {
                        val count1Handler = GitLineHandler(project, repo.root, GitCommand.REV_LIST)
                        count1Handler.addParameters("--count", "$mergeBase..$ref1")
                        val count1 = Git.getInstance().runCommand(count1Handler).getOutputOrThrow().trim()
                        appendLine("  $ref1: $count1 commits since divergence")
                    } catch (_: Exception) {
                        appendLine("  $ref1: (unable to count commits)")
                    }

                    // Count commits ref2 has since merge base
                    try {
                        val count2Handler = GitLineHandler(project, repo.root, GitCommand.REV_LIST)
                        count2Handler.addParameters("--count", "$mergeBase..$ref2")
                        val count2 = Git.getInstance().runCommand(count2Handler).getOutputOrThrow().trim()
                        appendLine("  $ref2: $count2 commits since divergence")
                    } catch (_: Exception) {
                        appendLine("  $ref2: (unable to count commits)")
                    }
                }

                ToolResult(
                    content = content,
                    summary = "Merge base: ${mergeBase.take(8)} ($ref1 ∩ $ref2)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error finding merge base: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
