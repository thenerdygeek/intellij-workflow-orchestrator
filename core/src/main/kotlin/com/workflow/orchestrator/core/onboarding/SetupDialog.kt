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
import com.intellij.openapi.application.invokeLater

class SetupDialog(private val project: Project) : DialogWrapper(project) {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val authTestService = AuthTestService()

    /** Holds successfully tested credentials until user clicks Finish Setup. */
    private data class TestResult(val serviceType: ServiceType, val url: String, val token: String, val username: String? = null)
    private val successfulTests = mutableMapOf<ServiceType, TestResult>()

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

        connectionSection("Jira", ServiceType.JIRA)
        connectionSection("Bamboo", ServiceType.BAMBOO)
        connectionSection("Bitbucket", ServiceType.BITBUCKET)
        connectionSection("SonarQube", ServiceType.SONARQUBE)
        connectionSection("Sourcegraph", ServiceType.SOURCEGRAPH)
        nexusConnectionSection()
    }

    private fun Panel.connectionSection(
        title: String,
        serviceType: ServiceType
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
                        invokeLater {
                            when (result) {
                                is ApiResult.Success -> {
                                    statusLabel.text = "Connected!"
                                    successfulTests[serviceType] = TestResult(serviceType, url, token)
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
                        invokeLater {
                            when (result) {
                                is ApiResult.Success -> {
                                    statusLabel.text = "Connected!"
                                    successfulTests[ServiceType.NEXUS] = TestResult(ServiceType.NEXUS, url, password, username = username)
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

    override fun doOKAction() {
        // Persist credentials only when user confirms with Finish Setup
        for ((_, result) in successfulTests) {
            when (result.serviceType) {
                ServiceType.NEXUS -> {
                    settings.connections.nexusUrl = result.url
                    settings.connections.nexusUsername = result.username.orEmpty()
                    credentialStore.storeNexusPassword(result.token)
                }
                ServiceType.JIRA -> {
                    settings.connections.jiraUrl = result.url
                    credentialStore.storeToken(result.serviceType, result.token)
                }
                ServiceType.BAMBOO -> {
                    settings.connections.bambooUrl = result.url
                    credentialStore.storeToken(result.serviceType, result.token)
                }
                ServiceType.BITBUCKET -> {
                    settings.connections.bitbucketUrl = result.url
                    credentialStore.storeToken(result.serviceType, result.token)
                }
                ServiceType.SONARQUBE -> {
                    settings.connections.sonarUrl = result.url
                    credentialStore.storeToken(result.serviceType, result.token)
                }
                ServiceType.SOURCEGRAPH -> {
                    settings.connections.sourcegraphUrl = result.url
                    credentialStore.storeToken(result.serviceType, result.token)
                }
            }
        }
        super.doOKAction()
    }
}
