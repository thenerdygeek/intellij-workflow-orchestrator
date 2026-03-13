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
            text("Connect your development tools. You can change these later in<br>" +
                 "<b>Settings → Tools → Workflow Orchestrator → Connections</b>")
        }
        separator()

        connectionSection("Jira", ServiceType.JIRA) { settings.connections.jiraUrl = it }
        connectionSection("Bamboo", ServiceType.BAMBOO) { settings.connections.bambooUrl = it }
        connectionSection("Bitbucket", ServiceType.BITBUCKET) { settings.connections.bitbucketUrl = it }
        connectionSection("SonarQube", ServiceType.SONARQUBE) { settings.connections.sonarUrl = it }
        connectionSection("Cody Enterprise", ServiceType.SOURCEGRAPH) { settings.connections.sourcegraphUrl = it }
        nexusConnectionSection()
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

    /**
     * Nexus uses username + password (Basic auth), not a single access token.
     */
    private fun Panel.nexusConnectionSection() {
        val urlField = JTextField(20)
        val usernameField = JTextField(20)
        val passwordField = JPasswordField(20)
        val statusLabel = JLabel("")

        collapsibleGroup("Nexus Docker Registry") {
            row("Registry URL:") {
                cell(urlField).columns(COLUMNS_LARGE)
            }
            row("Username:") {
                cell(usernameField).columns(COLUMNS_LARGE)
            }
            row("Password:") {
                cell(passwordField).columns(COLUMNS_LARGE)
            }
            row {
                button("Test Connection") {
                    val url = urlField.text.trim()
                    val username = usernameField.text.trim()
                    val password = String(passwordField.password).trim()
                    if (url.isBlank() || username.isBlank() || password.isBlank()) {
                        statusLabel.text = "Enter URL, username, and password"
                        return@button
                    }
                    statusLabel.text = "Testing..."
                    runBackgroundableTask("Testing Nexus Docker Registry", project, false) {
                        val result = runBlocking {
                            authTestService.testConnection(ServiceType.NEXUS, url, password, username = username)
                        }
                        SwingUtilities.invokeLater {
                            when (result) {
                                is ApiResult.Success -> {
                                    statusLabel.text = "Connected!"
                                    settings.connections.nexusUrl = url
                                    settings.connections.nexusUsername = username
                                    credentialStore.storeNexusPassword(password)
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
