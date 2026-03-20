package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.AgentMemoryStore
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class SaveMemoryTool : AgentTool {
    override val name = "save_memory"
    override val description = "Save a project-specific learning for future sessions. Use when you discover something worth remembering: build quirks, API behaviors, project conventions, debugging insights, user preferences. Memories are loaded at the start of each new conversation."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "topic" to ParameterProperty(type = "string", description = "Short topic name (e.g., 'build-config', 'api-quirks', 'testing-patterns')"),
            "content" to ParameterProperty(type = "string", description = "The learning to remember. Be concise but specific.")
        ),
        required = listOf("topic", "content")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val topic = params["topic"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'topic' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'content' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        AgentMemoryStore(File(basePath)).saveMemory(topic, content)

        return ToolResult(
            content = "Memory saved: '$topic'. This will be available in future sessions.",
            summary = "Saved memory: $topic",
            tokenEstimate = 5
        )
    }
}
