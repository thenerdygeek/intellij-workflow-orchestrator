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

class CoreMemoryAppendTool(private val coreMemory: CoreMemory) : AgentTool {
    override val name = "core_memory_append"
    override val description = "Append content to a core memory block. Creates the block if it doesn't exist. Use for storing facts, preferences, or project knowledge you want to remember across sessions."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "label" to ParameterProperty(type = "string", description = "The block label to append to (e.g., 'user_preferences', 'project_notes')."),
            "content" to ParameterProperty(type = "string", description = "The content to append to the block.")
        ),
        required = listOf("label", "content")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val label = params["label"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'label' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'content' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return try {
            val newValue = coreMemory.append(label, content)
            ToolResult(
                content = "Appended to [$label]. New value:\n$newValue",
                summary = "Appended to core memory block '$label'",
                tokenEstimate = estimateTokens(newValue)
            )
        } catch (e: IllegalArgumentException) {
            ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: Exception) {
            ToolResult("Error appending to core memory: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
