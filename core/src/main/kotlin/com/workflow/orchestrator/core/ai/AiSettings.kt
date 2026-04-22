package com.workflow.orchestrator.core.ai

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "WorkflowAiSettings", storages = [Storage("workflow-ai.xml")])
class AiSettings : PersistentStateComponent<AiSettings.State> {

    class State : BaseState() {
        var sourcegraphChatModel by string(null)
        /**
         * Max output tokens for non-agent text generation (PR titles, PR descriptions, commit
         * messages). Must be > the selected model's thinking.budget_tokens — Sourcegraph
         * rejects thinking-capable models with HTTP 400 ("max_tokens must be greater than
         * thinking.budget_tokens") when this is unset or too small. 16000 safely covers the
         * typical 4000–10000 thinking budget while keeping responses concise.
         */
        var maxOutputTokens by property(16_000)
    }

    private var myState = State()
    override fun getState() = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        fun getInstance(project: Project): AiSettings =
            project.getService(AiSettings::class.java)
    }
}
