package com.workflow.orchestrator.agent.tools.memory

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.memory.CoreMemory
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class CoreMemoryReplaceTool(private val coreMemory: CoreMemory) : AgentTool {
    override val name = "core_memory_replace"
    override val description = "Find and replace content within a core memory block. Requires an exact match of old_content (must appear exactly once). Use to update or correct existing memory entries."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "label" to ParameterProperty(type = "string", description = "The block label to modify."),
            "old_content" to ParameterProperty(type = "string", description = "The exact content to find (must appear exactly once in the block)."),
            "new_content" to ParameterProperty(type = "string", description = "The replacement content.")
        ),
        required = listOf("label", "old_content", "new_content")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val label = params["label"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'label' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val oldContent = params["old_content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'old_content' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val newContent = params["new_content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'new_content' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return try {
            val newValue = coreMemory.replace(label, oldContent, newContent)
            ToolResult(
                content = "Replaced in [$label]. New value:\n$newValue",
                summary = "Replaced content in core memory block '$label'",
                tokenEstimate = estimateTokens(newValue)
            )
        } catch (e: IllegalArgumentException) {
            ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: Exception) {
            ToolResult("Error replacing in core memory: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
