package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat

class GitBlameTool : AgentTool {
    override val name = "git_blame"
    override val description = "Get git blame for a file: who changed each line, when, in which commit. Useful for understanding code ownership and change history."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path (relative to project root or absolute)"),
            "start_line" to ParameterProperty(type = "integer", description = "Optional: start line (1-based). Defaults to 1."),
            "end_line" to ParameterProperty(type = "integer", description = "Optional: end line (1-based). Defaults to last line.")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        val startLine = params["start_line"]?.jsonPrimitive?.int
        val endLine = params["end_line"]?.jsonPrimitive?.int

        val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path!!))
            ?: return ToolResult("File not found: $path", "Not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return try {
            withContext(Dispatchers.IO) {
                val vcsManager = ProjectLevelVcsManager.getInstance(project)
                val vcs = vcsManager.getVcsFor(vf)
                    ?: return@withContext ToolResult("File is not under version control.", "No VCS", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                val annotationProvider = vcs.annotationProvider
                    ?: return@withContext ToolResult("No annotation/blame provider available.", "No blame", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val annotation = annotationProvider.annotate(vf)
                try {
                    val lineCount = annotation.lineCount
                    if (lineCount == 0) {
                        return@withContext ToolResult("File has no content to blame.", "Empty file", 5)
                    }

                    val start = ((startLine ?: 1) - 1).coerceIn(0, lineCount - 1)
                    val end = ((endLine ?: lineCount) - 1).coerceIn(start, lineCount - 1)
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd")

                    val content = buildString {
                        val relativePath = project.basePath?.let { base ->
                            if (path.startsWith(base)) path.removePrefix("$base/") else path
                        } ?: path
                        appendLine("Blame for $relativePath (lines ${start + 1}..${end + 1}):")
                        appendLine()

                        for (line in start..end) {
                            val rev = annotation.getLineRevisionNumber(line)?.asString()?.take(8) ?: "????????"
                            val tooltip = annotation.getToolTip(line)
                            // Tooltip typically contains "Author: <name>\nDate: <date>\nCommit: <hash>\n<message>"
                            val author = extractAuthorFromTooltip(tooltip).take(20).padEnd(20)
                            val date = annotation.getLineDate(line)?.let { dateFormat.format(it) } ?: "          "
                            appendLine("${(line + 1).toString().padStart(5)}  $rev  $author  $date")
                        }
                    }

                    ToolResult(
                        content = content,
                        summary = "Blame for ${vf.name} (${end - start + 1} lines)",
                        tokenEstimate = TokenEstimator.estimate(content)
                    )
                } finally {
                    @Suppress("DEPRECATION")
                    annotation.dispose()
                }
            }
        } catch (e: Exception) {
            ToolResult("Error running git blame: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun extractAuthorFromTooltip(tooltip: String?): String {
        if (tooltip.isNullOrBlank()) return "unknown"
        // Git blame tooltips typically have author info in various formats
        for (line in tooltip.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Author:", ignoreCase = true)) {
                return trimmed.removePrefix("Author:").removePrefix("author:").trim()
            }
        }
        // Fallback: return first non-blank line (often the author in compact format)
        return tooltip.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "unknown"
    }
}
