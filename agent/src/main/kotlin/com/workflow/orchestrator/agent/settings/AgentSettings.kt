package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "AgentSettings",
    storages = [Storage("workflowAgent.xml")]
)
class AgentSettings : SimplePersistentStateComponent<AgentSettings.State>(State()) {

    class State : BaseState() {
        var agentEnabled by property(false)
        var sourcegraphChatModel by string("anthropic/claude-sonnet-4")
        var maxInputTokens by property(150000)
        var maxOutputTokens by property(64000)
        var enableFastPath by property(true)
        var approvalRequiredForEdits by property(true)
        var tokenBudgetWarningPercent by property(80)
    }

    companion object {
        fun getInstance(project: Project): AgentSettings {
            return project.service<AgentSettings>()
        }
    }
}
