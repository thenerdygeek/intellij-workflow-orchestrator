package com.workflow.orchestrator.agent.tools.memory

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.memory.ArchivalMemory
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ArchivalMemoryInsertTool(private val archivalMemory: ArchivalMemory) : AgentTool {
    override val name = "archival_memory_insert"
    override val description = "Store knowledge in long-term archival memory with tags for searchability. Use for error resolutions, code patterns, API behaviors, decisions, and conventions you might need later."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "content" to ParameterProperty(type = "string", description = "The knowledge to store in archival memory."),
            "tags" to ParameterProperty(type = "string", description = "Comma-separated tags for categorization and search (e.g., 'error_resolution,spring,cors').")
        ),
        required = listOf("content", "tags")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'content' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val tagsStr = params["tags"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'tags' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return try {
            val tags = tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val id = archivalMemory.insert(content, tags)
            val totalEntries = archivalMemory.size()
            ToolResult(
                content = "Stored in archival memory.\nID: $id\nTags: ${tags.joinToString(", ")}\nTotal entries: $totalEntries",
                summary = "Inserted archival memory entry $id with tags: ${tags.joinToString(", ")}",
                tokenEstimate = 15
            )
        } catch (e: Exception) {
            ToolResult("Error inserting to archival memory: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
