package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import git4idea.util.GitFileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitShowFileTool : AgentTool {
    override val name = "git_show_file"
    override val description = "Read file content at any commit, branch, or tag. " +
        "Useful for comparing file versions across branches or reviewing historical content. " +
        "Remote refs (origin/, upstream/) are blocked for safety."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path relative to project root."),
            "ref" to ParameterProperty(type = "string", description = "Git ref: branch name, tag, commit SHA, or HEAD~N."),
            "repo" to ParameterProperty(type = "string", description = "Optional: git root path (relative to project, absolute, or directory name) to target in multi-root projects. Auto-resolved from 'path' if omitted.")
        ),
        required = listOf("path", "ref")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    companion object {
        private const val MAX_LINES = 2000
        private const val MAX_CHARS = 50_000
        private val REMOTE_REF_PATTERN = Regex("""(origin|upstream)/""")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val path = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter is required.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val ref = params["ref"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'ref' parameter is required.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val repoParam = params["repo"]?.jsonPrimitive?.content

        // Safety: reject remote refs
        if (REMOTE_REF_PATTERN.containsMatchIn(ref)) {
            return ToolResult(
                "Error: Remote refs (origin/, upstream/) are not allowed. Use a local branch, tag, or commit SHA instead.",
                "Error: remote ref blocked",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                val repo = GitRepoResolver.resolve(project, repo = repoParam, path = path)
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val bytes = GitFileUtils.getFileContent(project, repo.root, ref, path)

                if (bytes.isEmpty()) {
                    return@withContext ToolResult("File '$path' is empty at ref '$ref'.", "Empty file at $ref", 5)
                }

                val fullContent = String(bytes, Charsets.UTF_8)
                val lines = fullContent.lines()

                val truncatedByLines = if (lines.size > MAX_LINES) {
                    lines.take(MAX_LINES).joinToString("\n") +
                        "\n\n[Truncated at $MAX_LINES lines. Total: ${lines.size} lines.]"
                } else {
                    fullContent
                }

                val truncated = if (truncatedByLines.length > MAX_CHARS) {
                    truncatedByLines.take(MAX_CHARS) +
                        "\n\n[Truncated at $MAX_CHARS characters. Total: ${fullContent.length} characters.]"
                } else {
                    truncatedByLines
                }

                val content = buildString {
                    appendLine("File: $path @ $ref (${lines.size} lines)")
                    appendLine()
                    append(truncated)
                }

                ToolResult(
                    content = content,
                    summary = "File $path at $ref (${lines.size} lines)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            val msg = e.message ?: "unknown error"
            if (msg.contains("does not exist") || msg.contains("not found") || msg.contains("bad revision")) {
                ToolResult("File '$path' not found at ref '$ref'. Check the path and ref are correct.", "Not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            } else {
                ToolResult("Error reading file at ref: $msg", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }
        }
    }
}
