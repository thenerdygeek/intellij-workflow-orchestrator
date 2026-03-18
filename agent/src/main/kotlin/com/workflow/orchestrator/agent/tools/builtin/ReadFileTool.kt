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
            "limit" to ParameterProperty(type = "integer", description = "Max lines to read. Optional, defaults to 200.")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", 5, isError = true)

        val path = if (rawPath.startsWith("/")) rawPath
            else "${project.basePath}/$rawPath"

        val file = java.io.File(path)
        if (!file.exists() || !file.isFile) {
            return ToolResult("Error: File not found: $path", "Error: file not found", 5, isError = true)
        }

        val lines = file.readText(Charsets.UTF_8).lines()
        val offset = (params["offset"]?.jsonPrimitive?.int ?: 1).coerceAtLeast(1) - 1
        val limit = params["limit"]?.jsonPrimitive?.int ?: 200

        val selectedLines = lines.drop(offset).take(limit)
        val content = selectedLines.mapIndexed { idx, line ->
            "${offset + idx + 1}\t$line"
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
