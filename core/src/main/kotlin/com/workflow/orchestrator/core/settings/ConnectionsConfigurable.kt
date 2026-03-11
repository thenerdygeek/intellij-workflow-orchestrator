package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*
import com.intellij.openapi.progress.runBackgroundableTask
import com.workflow.orchestrator.core.auth.AuthTestService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.runBlocking
import javax.swing.JLabel
import javax.swing.SwingUtilities

class ConnectionsConfigurable(
    private val project: Project
) : BoundSearchableConfigurable("Connections", "workflow.orchestrator.connections") {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val authTestService = AuthTestService()

    override fun createPanel() = panel {
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

    private fun Panel.serviceGroup(
        title: String,
        serviceType: ServiceType,
        urlGetter: () -> String,
        urlSetter: (String) -> Unit
    ) {
        val existingToken = credentialStore.getToken(serviceType) ?: ""
        val statusLabel = JLabel("")

        collapsibleGroup(title) {
            row("Server URL:") {
                textField()
                    .columns(40)
                    .bindText(urlGetter, urlSetter)
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
                        if (newToken.isNotBlank()) {
                            credentialStore.storeToken(serviceType, newToken)
                        }
                    }
            }
            row {
                button("Test Connection") {
                    val url = urlGetter()
                    val token = credentialStore.getToken(serviceType)
                    if (url.isBlank() || token.isNullOrBlank()) {
                        statusLabel.text = "Please enter URL and token"
                        return@button
                    }
                    statusLabel.text = "Testing..."
                    // Run on background thread to avoid blocking EDT
                    runBackgroundableTask("Testing $title", project, false) {
                        val result = runBlocking {
                            authTestService.testConnection(serviceType, url, token)
                        }
                        SwingUtilities.invokeLater {
                            statusLabel.text = when (result) {
                                is ApiResult.Success -> "Connected successfully"
                                is ApiResult.Error -> "Failed: ${result.message}"
                            }
                        }
                    }
                }
                cell(statusLabel)
            }
        }
    }
}
