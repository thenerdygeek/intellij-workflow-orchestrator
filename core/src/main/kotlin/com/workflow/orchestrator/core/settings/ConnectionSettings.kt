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
        var bitbucketUsername: String = "",
        var sonarUrl: String = "",
        var sourcegraphUrl: String = "",
        var nexusUrl: String = "",
        // SEC-16: nexusUsername stored in plaintext XML — accepted risk (LOW).
        // Username alone without password is non-sensitive. Password is in PasswordSafe.
        // Full migration to PasswordSafe deferred to avoid multi-file refactor.
        var nexusUsername: String = "",
        var ticketKeyRegex: String = "\\b([A-Z][A-Z0-9]+-\\d+)\\b"
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
