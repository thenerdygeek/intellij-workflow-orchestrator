package com.workflow.orchestrator.core.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level (global) settings for service connection URLs.
 * Shared across all projects so users only configure URLs once.
 *
 * Tokens/passwords are already global via PasswordSafe.
 * Project-specific settings (board ID, repo slug, etc.) stay in PluginSettings.
 */
@Service(Service.Level.APP)
@State(
    name = "WorkflowOrchestratorConnections",
    storages = [Storage("workflowOrchestratorConnections.xml")]
)
class ConnectionSettings : PersistentStateComponent<ConnectionSettings.State> {

    data class State(
        var jiraUrl: String = "",
        var bambooUrl: String = "",
        var bitbucketUrl: String = "",
        var sonarUrl: String = "",
        var sourcegraphUrl: String = "",
        var nexusUrl: String = "",
        var nexusUsername: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): ConnectionSettings =
            ApplicationManager.getApplication().getService(ConnectionSettings::class.java)
    }
}
