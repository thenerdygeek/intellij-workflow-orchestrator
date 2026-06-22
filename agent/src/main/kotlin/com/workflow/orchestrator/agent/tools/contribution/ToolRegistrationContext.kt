package com.workflow.orchestrator.agent.tools.contribution

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.core.api.InternalApi

@InternalApi
class ToolRegistrationContext(
    val project: Project,
    private val registry: ToolRegistry,
) {
    fun registerCore(tool: AgentTool) = registry.registerCore(tool)
    fun registerDeferred(tool: AgentTool, category: String = "Other") =
        registry.registerDeferred(tool, category)
}
