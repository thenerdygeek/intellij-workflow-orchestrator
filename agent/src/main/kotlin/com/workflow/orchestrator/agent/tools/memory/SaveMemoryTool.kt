package com.workflow.orchestrator.agent.tools.memory

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SaveMemoryTool(private val agentDir: File) : AgentTool {
    override val name = "save_memory"
    override val description = "DEPRECATED — use archival_memory_insert instead, which provides structured storage with tags and search. This tool only saves raw markdown files without indexing."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "content" to ParameterProperty(type = "string", description = "The markdown content to save."),
            "filename" to ParameterProperty(type = "string", description = "Optional filename (without extension). Defaults to a timestamp-based name.")
        ),
        required = listOf("content")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'content' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val filename = params["filename"]?.jsonPrimitive?.content
            ?: "memory-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))}"

        return try {
            val memoryDir = File(agentDir, "memory")
            memoryDir.mkdirs()
            val file = File(memoryDir, "$filename.md")
            file.writeText(content)
            ToolResult(
                content = "Memory saved to: ${file.absolutePath}",
                summary = "Saved memory file '$filename.md'",
                tokenEstimate = 10
            )
        } catch (e: Exception) {
            ToolResult("Error saving memory file: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
