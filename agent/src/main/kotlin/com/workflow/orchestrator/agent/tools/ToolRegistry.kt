package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.runtime.WorkerType

class ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): AgentTool? = tools[name]

    fun getToolsForWorker(workerType: WorkerType): List<AgentTool> {
        return tools.values.filter { workerType in it.allowedWorkers }
    }

    fun getToolDefinitionsForWorker(workerType: WorkerType): List<ToolDefinition> {
        return getToolsForWorker(workerType).map { it.toToolDefinition() }
    }

    fun allTools(): Collection<AgentTool> = tools.values.toList()
}
