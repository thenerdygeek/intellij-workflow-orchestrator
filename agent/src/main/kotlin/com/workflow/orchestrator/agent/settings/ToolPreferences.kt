package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Persists user's tool enable/disable preferences per project.
 * All tools are enabled by default. Users disable tools via the Tools panel checkboxes.
 * Disabled tools are never sent to the LLM.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "AgentToolPreferences",
    storages = [Storage("workflowAgentToolPreferences.xml")]
)
class ToolPreferences : SimplePersistentStateComponent<ToolPreferences.State>(State()) {

    class State : BaseState() {
        var disabledTools by list<String>()
    }

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        val disabled = state.disabledTools.toMutableList()
        if (enabled) disabled.remove(toolName)
        else if (toolName !in disabled) disabled.add(toolName)
        state.disabledTools = disabled
    }

    companion object {
        fun getInstance(project: Project): ToolPreferences = project.service<ToolPreferences>()
    }
}
