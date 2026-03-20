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
        /**
         * Model name in Sourcegraph format: provider::apiVersion::modelId
         * Default is Sonnet 4, but auto-upgraded to latest Opus when models are loaded
         * (if user hasn't manually changed the model).
         */
        var sourcegraphChatModel by string("anthropic::2024-10-22::claude-sonnet-4-20250514")
        /** Tracks whether the user has manually selected a model (prevents auto-upgrade). */
        var userManuallySelectedModel by property(false)
        var maxInputTokens by property(190000)
        /** Max output tokens per LLM response. Sourcegraph API caps at 4000. */
        var maxOutputTokens by property(4000)
        var enableFastPath by property(true)
        var approvalRequiredForEdits by property(true)
        var tokenBudgetWarningPercent by property(80)
        var maxSessionTokens by property(500_000)
    }

    companion object {
        /** Default values — used as fallback when settings aren't available. */
        val DEFAULTS = State()

        fun getInstance(project: Project): AgentSettings {
            return project.service<AgentSettings>()
        }
    }
}
