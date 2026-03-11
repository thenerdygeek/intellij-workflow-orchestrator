package com.workflow.orchestrator.core.onboarding

import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.auth.AuthTestService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.runBlocking
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.SwingUtilities

class SetupDialog(private val project: Project) : DialogWrapper(project) {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val authTestService = AuthTestService()

    init {
        title = "Setup Workflow Orchestrator"
        setOKButtonText("Finish Setup")
        setCancelButtonText("Skip")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            text("Connect your development tools. You can configure any service later in Settings.")
        }
        separator()

        connectionSection("Jira", ServiceType.JIRA) { settings.state.jiraUrl = it }
        connectionSection("Bamboo", ServiceType.BAMBOO) { settings.state.bambooUrl = it }
        connectionSection("Bitbucket", ServiceType.BITBUCKET) { settings.state.bitbucketUrl = it }
        connectionSection("SonarQube", ServiceType.SONARQUBE) { settings.state.sonarUrl = it }
        connectionSection("Cody Enterprise", ServiceType.SOURCEGRAPH) { settings.state.sourcegraphUrl = it }
        connectionSection("Nexus Docker Registry", ServiceType.NEXUS) { settings.state.nexusUrl = it }
    }

    private fun Panel.connectionSection(
        title: String,
        serviceType: ServiceType,
        urlSaver: (String) -> Unit
    ) {
        val urlField = JTextField(20)
        val tokenField = JPasswordField(20)
        val statusLabel = JLabel("")

        collapsibleGroup(title) {
            row("Server URL:") {
                cell(urlField).columns(COLUMNS_LARGE)
            }
            row("Access Token:") {
                cell(tokenField).columns(COLUMNS_LARGE)
            }
            row {
                button("Test Connection") {
                    val url = urlField.text.trim()
                    val token = String(tokenField.password).trim()
                    if (url.isBlank() || token.isBlank()) {
                        statusLabel.text = "Enter URL and token"
                        return@button
                    }
                    statusLabel.text = "Testing..."
                    // Run on background thread to avoid blocking EDT
                    runBackgroundableTask("Testing $title", project, false) {
                        val result = runBlocking {
                            authTestService.testConnection(serviceType, url, token)
                        }
                        SwingUtilities.invokeLater {
                            when (result) {
                                is ApiResult.Success -> {
                                    statusLabel.text = "Connected!"
                                    credentialStore.storeToken(serviceType, token)
                                    urlSaver(url)
                                }
                                is ApiResult.Error -> {
                                    statusLabel.text = "Failed: ${result.message}"
                                }
                            }
                        }
                    }
                }
                cell(statusLabel)
            }
        }
    }
}
