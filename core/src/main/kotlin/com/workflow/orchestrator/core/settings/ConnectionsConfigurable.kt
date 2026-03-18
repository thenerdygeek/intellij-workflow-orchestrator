package com.workflow.orchestrator.core.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.auth.AuthTestService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.runBlocking
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.SwingUtilities

class ConnectionsConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private val connSettings = ConnectionSettings.getInstance()
    private val credentialStore = CredentialStore()
    private val authTestService = AuthTestService()

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    // Deferred credential saves — only written on apply(), not on keystroke
    private val pendingTokens = mutableMapOf<ServiceType, String>()
    private var pendingNexusPassword: String? = null
    private var pendingNexusUsername: String? = null

    override fun getId(): String = "workflow.orchestrator.connections"
    override fun getDisplayName(): String = "Connections"

    override fun createComponent(): JComponent {
        val innerPanel = panel {
            serviceGroup("Jira Connection", ServiceType.JIRA,
                { connSettings.state.jiraUrl }, { connSettings.state.jiraUrl = it })
            serviceGroup("Bamboo Connection", ServiceType.BAMBOO,
                { connSettings.state.bambooUrl }, { connSettings.state.bambooUrl = it })
            serviceGroup("Bitbucket Connection", ServiceType.BITBUCKET,
                { connSettings.state.bitbucketUrl }, { connSettings.state.bitbucketUrl = it })
            serviceGroup("SonarQube Connection", ServiceType.SONARQUBE,
                { connSettings.state.sonarUrl }, { connSettings.state.sonarUrl = it })
            serviceGroup("Cody Enterprise", ServiceType.SOURCEGRAPH,
                { connSettings.state.sourcegraphUrl }, { connSettings.state.sourcegraphUrl = it })
            serviceGroup("Nexus Docker Registry", ServiceType.NEXUS,
                { connSettings.state.nexusUrl }, { connSettings.state.nexusUrl = it })
        }
        dialogPanel = innerPanel

        // Wrap in scroll pane so all 6 collapsible groups are accessible
        // even when multiple are expanded simultaneously
        return JBScrollPane(innerPanel).apply {
            border = null
        }
    }

    override fun isModified(): Boolean {
        if (pendingTokens.isNotEmpty() || pendingNexusPassword != null || pendingNexusUsername != null) return true
        return dialogPanel?.isModified() ?: false
    }

    override fun apply() {
        dialogPanel?.apply()

        // Save credentials only on explicit Apply — not on every keystroke
        for ((serviceType, token) in pendingTokens) {
            if (token.isNotBlank()) {
                credentialStore.storeToken(serviceType, token)
            }
        }
        pendingTokens.clear()

        pendingNexusPassword?.let { password ->
            if (password.isNotBlank()) {
                credentialStore.storeNexusPassword(password)
            }
        }
        pendingNexusPassword = null

        pendingNexusUsername?.let { username ->
            connSettings.state.nexusUsername = username
        }
        pendingNexusUsername = null

        pendingBitbucketUsername?.let { username ->
            connSettings.state.bitbucketUsername = username
        }
        pendingBitbucketUsername = null
    }

    override fun reset() {
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    private var pendingBitbucketUsername: String? = null

    private fun Panel.serviceGroup(
        title: String,
        serviceType: ServiceType,
        urlGetter: () -> String,
        urlSetter: (String) -> Unit
    ) {
        if (serviceType == ServiceType.NEXUS) {
            nexusServiceGroup(title, urlGetter, urlSetter)
        } else if (serviceType == ServiceType.BITBUCKET) {
            bitbucketServiceGroup(title, urlGetter, urlSetter)
        } else {
            tokenServiceGroup(title, serviceType, urlGetter, urlSetter)
        }
    }

    /**
     * Standard service group with a single Access Token field.
     * Used by Jira, Bamboo, Bitbucket, SonarQube, Sourcegraph.
     */
    private fun Panel.tokenServiceGroup(
        title: String,
        serviceType: ServiceType,
        urlGetter: () -> String,
        urlSetter: (String) -> Unit
    ) {
        val statusLabel = JLabel("")
        var currentUrl = urlGetter()
        var currentToken = ""
        var tokenField: JPasswordField? = null

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
                        tokenField = this
                    }
                    .onChanged { field ->
                        val newToken = String(field.password)
                        currentToken = newToken
                        pendingTokens[serviceType] = newToken
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
                                is ApiResult.Success -> "\u2713 Connected successfully"
                                is ApiResult.Error -> "\u2717 ${result.message}"
                            }
                        }
                    }
                }
                cell(statusLabel)
            }
        }

        // Load existing token in background to avoid blocking EDT on PasswordSafe read
        ApplicationManager.getApplication().executeOnPooledThread {
            val existingToken = credentialStore.getToken(serviceType) ?: ""
            if (existingToken.isNotBlank()) {
                currentToken = existingToken
                SwingUtilities.invokeLater {
                    tokenField?.text = existingToken
                }
            }
        }
    }

    /**
     * Bitbucket service group with URL + Token + Username fields.
     * Username is required for PR list filtering (role.1 + username.1).
     */
    private fun Panel.bitbucketServiceGroup(
        title: String,
        urlGetter: () -> String,
        urlSetter: (String) -> Unit
    ) {
        val existingUsername = connSettings.state.bitbucketUsername
        val statusLabel = JLabel("")
        var currentUrl = urlGetter()
        var currentToken = ""
        var tokenField: JPasswordField? = null

        collapsibleGroup(title) {
            row("Server URL:") {
                textField()
                    .columns(40)
                    .bindText(urlGetter, urlSetter)
                    .onChanged { field -> currentUrl = field.text }
                    .comment("e.g., https://bitbucket.company.com")
            }
            row("Access Token:") {
                passwordField()
                    .columns(40)
                    .applyToComponent { tokenField = this }
                    .onChanged { field ->
                        val newToken = String(field.password)
                        currentToken = newToken
                        pendingTokens[ServiceType.BITBUCKET] = newToken
                    }
            }
            row("Username:") {
                textField()
                    .columns(40)
                    .applyToComponent { text = existingUsername }
                    .onChanged { field -> pendingBitbucketUsername = field.text }
                    .comment("Your Bitbucket username (used for filtering PRs by author/reviewer)")
            }
            row {
                button("Test Connection") {
                    val url = currentUrl.trim()
                    val token = currentToken.ifBlank { credentialStore.getToken(ServiceType.BITBUCKET) }
                    if (url.isBlank() || token.isNullOrBlank()) {
                        statusLabel.text = "Please enter URL and token"
                        return@button
                    }
                    statusLabel.text = "Testing..."
                    runBackgroundableTask("Testing $title", project, false) {
                        val result = runBlocking {
                            authTestService.testConnection(ServiceType.BITBUCKET, url, token)
                        }
                        SwingUtilities.invokeLater {
                            statusLabel.text = when (result) {
                                is ApiResult.Success -> "\u2713 Connected successfully"
                                is ApiResult.Error -> "\u2717 ${result.message}"
                            }
                        }
                    }
                }
                cell(statusLabel)
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val existingToken = credentialStore.getToken(ServiceType.BITBUCKET) ?: ""
            if (existingToken.isNotBlank()) {
                currentToken = existingToken
                SwingUtilities.invokeLater { tokenField?.text = existingToken }
            }
        }
    }

    /**
     * Nexus-specific service group with Username + Password fields.
     * Nexus Docker Registry uses Basic auth (username:password), not a single access token.
     */
    private fun Panel.nexusServiceGroup(
        title: String,
        urlGetter: () -> String,
        urlSetter: (String) -> Unit
    ) {
        val existingUsername = connSettings.state.nexusUsername
        val statusLabel = JLabel("")
        var currentUrl = urlGetter()
        var currentUsername = existingUsername
        var currentPassword = ""
        var passwordField: JPasswordField? = null

        collapsibleGroup(title) {
            row("Registry URL:") {
                textField()
                    .columns(40)
                    .bindText(urlGetter, urlSetter)
                    .onChanged { field -> currentUrl = field.text }
                    .comment("e.g., https://nexus.company.com/repository/docker-hosted")
            }
            row("Username:") {
                textField()
                    .columns(40)
                    .applyToComponent {
                        text = existingUsername
                    }
                    .onChanged { field ->
                        currentUsername = field.text
                        pendingNexusUsername = field.text
                    }
            }
            row("Password:") {
                passwordField()
                    .columns(40)
                    .applyToComponent {
                        passwordField = this
                    }
                    .onChanged { field ->
                        val newPassword = String(field.password)
                        currentPassword = newPassword
                        pendingNexusPassword = newPassword
                    }
            }
            row {
                button("Test Connection") {
                    val url = currentUrl.trim()
                    val user = currentUsername.trim()
                    val pass = currentPassword.ifBlank { credentialStore.getNexusPassword() }
                    if (url.isBlank() || user.isBlank() || pass.isNullOrBlank()) {
                        statusLabel.text = "Please enter URL, username, and password"
                        return@button
                    }
                    statusLabel.text = "Testing..."
                    runBackgroundableTask("Testing $title", project, false) {
                        val result = runBlocking {
                            authTestService.testConnection(ServiceType.NEXUS, url, pass, username = user)
                        }
                        SwingUtilities.invokeLater {
                            statusLabel.text = when (result) {
                                is ApiResult.Success -> "\u2713 Connected successfully"
                                is ApiResult.Error -> "\u2717 ${result.message}"
                            }
                        }
                    }
                }
                cell(statusLabel)
            }
        }

        // Load existing password in background to avoid blocking EDT on PasswordSafe read
        ApplicationManager.getApplication().executeOnPooledThread {
            val existingPassword = credentialStore.getNexusPassword() ?: ""
            if (existingPassword.isNotBlank()) {
                currentPassword = existingPassword
                SwingUtilities.invokeLater {
                    passwordField?.text = existingPassword
                }
            }
        }
    }
}
