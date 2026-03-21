package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description = "Read the contents of a file. Use offset and limit for large files."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path"),
            "offset" to ParameterProperty(type = "integer", description = "Starting line number (1-based). Optional."),
            "limit" to ParameterProperty(type = "integer", description = "Max lines to read. Optional, defaults to $DEFAULT_LIMIT.")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    companion object {
        private const val DEFAULT_LIMIT = 200
        private const val MAX_LINE_CHARS = 2000
        private const val MAX_FILE_SIZE = 10_000_000L // 10MB
        private val BINARY_EXTENSIONS = setOf(
            "jar", "class", "png", "jpg", "jpeg", "gif", "ico", "svg",
            "zip", "tar", "gz", "war", "ear", "so", "dll", "exe",
            "pdf", "woff", "woff2", "ttf", "eot", "bin", "dat"
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        val resolvedPath = path!!
        val file = java.io.File(resolvedPath)
        if (!file.exists() || !file.isFile) {
            return ToolResult("Error: File not found: $resolvedPath", "Error: file not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Binary file detection
        if (file.extension.lowercase() in BINARY_EXTENSIONS) {
            return ToolResult(
                "Error: '${file.name}' is a binary file and cannot be read as text. Use search_code to find specific content.",
                "Binary file: ${file.name}",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // File size check
        if (file.length() > MAX_FILE_SIZE) {
            return ToolResult(
                "Error: '${file.name}' is ${file.length() / 1_000_000}MB — too large to read. Use search_code to find specific content, or use offset/limit to read a section.",
                "File too large: ${file.name}",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Try to read from Document (sees unsaved editor changes) or fall back to file I/O
        val allLines: List<String> = try {
            val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(resolvedPath)
            if (vFile != null) {
                val text = com.intellij.openapi.application.readAction {
                    val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(vFile)
                    doc?.text ?: String(vFile.contentsToByteArray(), vFile.charset)
                }
                text.lines()
            } else {
                file.readLines(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            file.readLines(Charsets.UTF_8) // fallback
        }

        val lines = allLines.ifEmpty { listOf("") }
        val offset = (params["offset"]?.jsonPrimitive?.int ?: 1).coerceAtLeast(1) - 1
        val limit = params["limit"]?.jsonPrimitive?.int ?: DEFAULT_LIMIT

        val selectedLines = lines.drop(offset).take(limit)
        val content = selectedLines.mapIndexed { idx, line ->
            val truncatedLine = if (line.length > MAX_LINE_CHARS) {
                line.take(MAX_LINE_CHARS) + " ... [line truncated at $MAX_LINE_CHARS chars]"
            } else line
            "${offset + idx + 1}\t$truncatedLine"
        }.joinToString("\n")

        val truncated = if (offset + limit < lines.size) "\n... (${lines.size - offset - limit} more lines)" else ""
        val fullContent = content + truncated

        return ToolResult(
            content = fullContent,
            summary = "Read ${selectedLines.size} lines from $rawPath (${lines.size} total)",
            tokenEstimate = TokenEstimator.estimate(fullContent)
        )
    }
}
