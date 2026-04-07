package com.workflow.orchestrator.core.ai

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "WorkflowAiSettings", storages = [Storage("workflow-ai.xml")])
class AiSettings : PersistentStateComponent<AiSettings.State> {

    class State : BaseState() {
        var sourcegraphChatModel by string(null)
    }

    private var myState = State()
    override fun getState() = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        fun getInstance(project: Project): AiSettings =
            project.getService(AiSettings::class.java)
    }
}
