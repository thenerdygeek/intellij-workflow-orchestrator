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
        var ticketKeyRegex: String = "\\b([A-Z][A-Z0-9]+-\\d+)\\b",
        // Last-used Jira board, mirrored from PluginSettings on apply().
        // Fresh projects (e.g. new clones) hydrate from these when their
        // project-local jiraBoardId is still the default 0.
        var lastJiraBoardId: Int = 0,
        var lastJiraBoardName: String = "",
        var lastJiraBoardType: String = "",

        // ── Anthropic direct provider ─────────────────────────────────────────
        // Token lives in PasswordSafe under ServiceType.ANTHROPIC; URL here.
        var anthropicApiUrl: String = "https://api.anthropic.com",

        // ── Web search provider connection settings ───────────────────────────
        // API keys live in PasswordSafe under ServiceType.WEB_SEARCH.
        var webSearchSearxngUrl: String = "",
        var webSearchCustomUrl: String = "",
        var webSearchCustomMethod: String = "GET",
        var webSearchCustomHeaderName: String = "",
        // JSONPath expressions for custom provider response mapping
        var webSearchCustomResultsPath: String = "$.results",
        var webSearchCustomTitlePath: String = "$.title",
        var webSearchCustomUrlPath: String = "$.url",
        var webSearchCustomSnippetPath: String = "$.snippet",
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
