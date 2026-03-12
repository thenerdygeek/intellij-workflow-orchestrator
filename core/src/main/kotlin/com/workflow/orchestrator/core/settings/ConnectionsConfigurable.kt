package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.openapi.progress.runBackgroundableTask
import com.workflow.orchestrator.core.auth.AuthTestService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.runBlocking
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities

class ConnectionsConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val authTestService = AuthTestService()

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    override fun getId(): String = "workflow.orchestrator.connections"
    override fun getDisplayName(): String = "Connections"

    override fun createComponent(): JComponent {
        val innerPanel = panel {
            serviceGroup("Jira Connection", ServiceType.JIRA,
                { settings.state.jiraUrl.orEmpty() }, { settings.state.jiraUrl = it })
            serviceGroup("Bamboo Connection", ServiceType.BAMBOO,
                { settings.state.bambooUrl.orEmpty() }, { settings.state.bambooUrl = it })
            serviceGroup("Bitbucket Connection", ServiceType.BITBUCKET,
                { settings.state.bitbucketUrl.orEmpty() }, { settings.state.bitbucketUrl = it })
            serviceGroup("SonarQube Connection", ServiceType.SONARQUBE,
                { settings.state.sonarUrl.orEmpty() }, { settings.state.sonarUrl = it })
            serviceGroup("Cody Enterprise", ServiceType.SOURCEGRAPH,
                { settings.state.sourcegraphUrl.orEmpty() }, { settings.state.sourcegraphUrl = it })
            serviceGroup("Nexus Docker Registry", ServiceType.NEXUS,
                { settings.state.nexusUrl.orEmpty() }, { settings.state.nexusUrl = it })
        }
        dialogPanel = innerPanel

        // Wrap in scroll pane so all 6 collapsible groups are accessible
        // even when multiple are expanded simultaneously
        return JBScrollPane(innerPanel).apply {
            border = null
        }
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() ?: false

    override fun apply() {
        dialogPanel?.apply()
    }

    override fun reset() {
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    private fun Panel.serviceGroup(
        title: String,
        serviceType: ServiceType,
        urlGetter: () -> String,
        urlSetter: (String) -> Unit
    ) {
        val existingToken = credentialStore.getToken(serviceType) ?: ""
        val statusLabel = JLabel("")
        // Track the current field values so Test Connection uses what's in the UI,
        // not the last-saved settings value
        var currentUrl = urlGetter()
        var currentToken = existingToken

        collapsibleGroup(title) {
            row("Server URL:") {
                textField()
                    .columns(40)
                    .bindText(urlGetter, urlSetter)
                    .onChanged { field -> currentUrl = field.text }
                    .comment("e.g., https://${serviceType.name.lowercase()}.company.com")
            }
            row("Access Token:") {
                passwordField()
                    .columns(40)
                    .applyToComponent {
                        text = existingToken
                    }
                    .onChanged { field ->
                        val newToken = String(field.password)
                        currentToken = newToken
                        if (newToken.isNotBlank()) {
                            credentialStore.storeToken(serviceType, newToken)
                        }
                    }
            }
            row {
                button("Test Connection") {
                    val url = currentUrl.trim()
                    val token = currentToken.ifBlank { credentialStore.getToken(serviceType) }
                    if (url.isBlank() || token.isNullOrBlank()) {
                        statusLabel.text = "Please enter URL and token"
                        return@button
                    }
                    statusLabel.text = "Testing..."
                    runBackgroundableTask("Testing $title", project, false) {
                        val result = runBlocking {
                            authTestService.testConnection(serviceType, url, token)
                        }
                        SwingUtilities.invokeLater {
                            statusLabel.text = when (result) {
                                is ApiResult.Success -> "✓ Connected successfully"
                                is ApiResult.Error -> "✗ ${result.message}"
                            }
                        }
                    }
                }
                cell(statusLabel)
            }
        }
    }
}
