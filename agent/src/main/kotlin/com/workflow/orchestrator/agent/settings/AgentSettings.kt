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
         * Auto-resolved from API on first use. User can override in settings.
         */
        var sourcegraphChatModel by string(null)
        /** Tracks whether the user has manually selected a model (prevents auto-upgrade). */
        var userManuallySelectedModel by property(false)
        var maxInputTokens by property(190000)
        /** Max output tokens per LLM response. Limit varies per model — no hardcoded cap. */
        var maxOutputTokens by property(64000)
        var approvalRequiredForEdits by property(true)
        var maxSessionTokens by property(500_000)
        var showDebugLog by property(false)
        var commandIdleThresholdSeconds by property(15)
        var buildCommandIdleThresholdSeconds by property(60)
        var maxStdinPerProcess by property(10)
        var askUserInputTimeoutMinutes by property(5)
        var powershellEnabled by property(true)

        // Ralph Loop defaults
        var ralphMaxIterations by property(10)
        var ralphMaxCostUsd by string("10.0")
        var ralphReviewerEnabled by property(true)
    }

    companion object {
        /** Default values — used as fallback when settings aren't available. */
        val DEFAULTS = State()

        fun getInstance(project: Project): AgentSettings {
            return project.service<AgentSettings>()
        }
    }
}
