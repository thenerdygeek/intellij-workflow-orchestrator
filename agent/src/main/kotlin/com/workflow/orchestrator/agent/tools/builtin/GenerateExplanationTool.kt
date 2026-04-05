package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.core.ai.TokenEstimator
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AI-powered explanation of code diffs between two git references.
 *
 * Faithful port of Cline's generate_explanation tool from:
 * src/core/prompts/system-prompt/tools/generate_explanation.ts
 *
 * Cline opens a multi-file diff view with inline comments.
 * In our IntelliJ context, we return the diff content as a tool result
 * and let the LLM generate the explanation in its response text.
 *
 * Parameters (from Cline):
 * - title (required): descriptive label for the comparison
 * - from_ref (required): starting git reference (commit SHA, branch, tag, HEAD~1)
 * - to_ref (optional): ending reference; if omitted, compares to working directory
 */
class GenerateExplanationTool : AgentTool {
    override val name = "generate_explanation"
    override val description =
        "Generate an AI-powered explanation of code changes between two git references. " +
        "Retrieves the diff between from_ref and to_ref (or working directory if to_ref omitted) " +
        "and returns it for explanation. Use to help understand commits, PRs, or branch differences."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "title" to ParameterProperty(
                type = "string",
                description = "A descriptive title for the diff view (e.g., 'Changes in commit abc123', " +
                    "'PR #42: Add authentication', 'Changes between main and feature-branch')"
            ),
            "from_ref" to ParameterProperty(
                type = "string",
                description = "The git reference for the 'before' state. Can be a commit hash, branch name, " +
                    "tag, or relative reference like HEAD~1, HEAD^, origin/main, etc."
            ),
            "to_ref" to ParameterProperty(
                type = "string",
                description = "The git reference for the 'after' state. Can be a commit hash, branch name, " +
                    "tag, or relative reference. If not provided, compares to the current working directory " +
                    "(including uncommitted changes)."
            )
        ),
        required = listOf("title", "from_ref")
    )
    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER
    )

    companion object {
        private const val MAX_DIFF_CHARS = 50_000
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val title = params["title"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'title' parameter is required.",
                "Error: missing title",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val fromRef = params["from_ref"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'from_ref' parameter is required.",
                "Error: missing from_ref",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val toRef = params["to_ref"]?.jsonPrimitive?.content

        return try {
            withContext(Dispatchers.IO) {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull()
                    ?: return@withContext ToolResult(
                        "No git repository found in project.",
                        "No git repo",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                val handler = GitLineHandler(project, repo.root, GitCommand.DIFF)
                handler.addParameters("--stat")
                handler.addParameters("--patch")

                if (toRef != null) {
                    // git diff from_ref..to_ref
                    handler.addParameters("$fromRef..$toRef")
                } else {
                    // git diff from_ref (compares to working directory)
                    handler.addParameters(fromRef)
                }

                val result = Git.getInstance().runCommand(handler)
                val output = result.getOutputOrThrow()

                if (output.isBlank()) {
                    val comparison = if (toRef != null) "$fromRef..$toRef" else "$fromRef..working directory"
                    return@withContext ToolResult(
                        "No differences found between $comparison.",
                        "No diff for: $title",
                        5
                    )
                }

                val truncated = truncateOutput(output, MAX_DIFF_CHARS)
                val comparison = if (toRef != null) "$fromRef..$toRef" else "$fromRef vs working directory"

                val content = buildString {
                    appendLine("# $title")
                    appendLine("Comparing: $comparison")
                    appendLine()
                    appendLine("Please explain the following changes:")
                    appendLine()
                    append(truncated)
                }

                ToolResult(
                    content = content,
                    summary = "Diff for '$title': $comparison",
                    tokenEstimate = TokenEstimator.estimate(content),
                    diff = truncated  // raw unified diff — pushed to UI as DiffHtml
                )
            }
        } catch (e: Exception) {
            ToolResult(
                "Error generating diff: ${e.message}",
                "Error: ${e.message}",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
