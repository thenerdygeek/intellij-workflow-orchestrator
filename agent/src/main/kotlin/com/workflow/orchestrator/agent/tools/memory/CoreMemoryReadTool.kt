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

class CoreMemoryReadTool(private val coreMemory: CoreMemory) : AgentTool {
    override val name = "core_memory_read"
    override val description = "Read your persistent core memory. Returns all memory blocks or a specific block by label. Use this to check what you currently remember before making updates."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "label" to ParameterProperty(type = "string", description = "Optional block label to read. If omitted, returns all blocks.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val label = params["label"]?.jsonPrimitive?.content

            if (label != null) {
                val value = coreMemory.read(label)
                if (value != null) {
                    ToolResult(
                        content = "[$label]\n$value",
                        summary = "Read core memory block '$label'",
                        tokenEstimate = estimateTokens(value)
                    )
                } else {
                    ToolResult(
                        content = "No core memory block named '$label' exists.",
                        summary = "Block '$label' not found",
                        tokenEstimate = 10
                    )
                }
            } else {
                val blocks = coreMemory.readAll()
                val nonEmpty = blocks.filter { it.value.value.isNotBlank() }
                if (nonEmpty.isEmpty()) {
                    ToolResult(
                        content = "Core memory is empty. Use core_memory_append to store information.\nAvailable blocks: ${blocks.keys.joinToString(", ")}",
                        summary = "Core memory empty",
                        tokenEstimate = 10
                    )
                } else {
                    val formatted = nonEmpty.entries.joinToString("\n\n") { (label, block) ->
                        "[$label] (${block.value.length}/${block.limit} chars)\n${block.value}"
                    }
                    ToolResult(
                        content = formatted,
                        summary = "Read ${blocks.size} core memory blocks",
                        tokenEstimate = estimateTokens(formatted)
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult("Error reading core memory: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
