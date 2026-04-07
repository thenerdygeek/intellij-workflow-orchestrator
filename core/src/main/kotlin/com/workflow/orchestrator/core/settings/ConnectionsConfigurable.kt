package com.workflow.orchestrator.core.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
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

/**
 * "Connections" settings page — one-stop credential setup for all 6 services.
 * First thing a user sees after install.
 *
 * Ported verbatim from the Connections section of GeneralConfigurable. Write tools
 * and test buttons are preserved as-is. Network timeouts are included at the bottom
 * in a collapsible "Network (Advanced)" group.
 */
class ConnectionsConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private val log = Logger.getInstance(ConnectionsConfigurable::class.java)
    private val connSettings = ConnectionSettings.getInstance()
    private val pluginSettings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val authTestService = AuthTestService()

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    // --- Deferred credential saves ---
    private val pendingTokens = mutableMapOf<ServiceType, String>()
    private var pendingNexusPassword: String? = null
    private var pendingNexusUsername: String? = null
    private var pendingBitbucketUsername: String? = null

    // Guard against false modification during initial token load
    private var isInitializing = true

    override fun getId(): String = "workflow.orchestrator.connections"
    override fun getDisplayName(): String = "Connections"

    override fun createComponent(): JComponent {
        isInitializing = true

        val innerPanel = panel {
            // Required services (plain group — always expanded)
            tokenServiceGroup(
                "Jira Connection",
                ServiceType.JIRA,
                { connSettings.state.jiraUrl },
                { connSettings.state.jiraUrl = it },
                collapsible = false
            )
            tokenServiceGroup(
                "Bamboo Connection",
                ServiceType.BAMBOO,
                { connSettings.state.bambooUrl },
                { connSettings.state.bambooUrl = it },
                collapsible = false
            )
            bitbucketServiceGroup(
                "Bitbucket Connection",
                { connSettings.state.bitbucketUrl },
                { connSettings.state.bitbucketUrl = it },
                collapsible = false
            )
            tokenServiceGroup(
                "SonarQube Connection",
                ServiceType.SONARQUBE,
                { connSettings.state.sonarUrl },
                { connSettings.state.sonarUrl = it },
                collapsible = false
            )

            // Optional services (collapsible — start collapsed)
            tokenServiceGroup(
                "Sourcegraph",
                ServiceType.SOURCEGRAPH,
                { connSettings.state.sourcegraphUrl },
                { connSettings.state.sourcegraphUrl = it },
                collapsible = true
            )
            nexusServiceGroup(
                "Nexus Docker Registry",
                { connSettings.state.nexusUrl },
                { connSettings.state.nexusUrl = it }
            )

            // Network (Advanced) — relocated from AiAdvancedConfigurable
            collapsibleGroup("Network (Advanced)") {
                row("Connect timeout (seconds):") {
                    intTextField(range = 1..300)
                        .bindIntText(pluginSettings.state::httpConnectTimeoutSeconds)
                }
                row("Read timeout (seconds):") {
                    intTextField(range = 1..600)
                        .bindIntText(pluginSettings.state::httpReadTimeoutSeconds)
                }
            }
        }
        dialogPanel = innerPanel

        isInitializing = false

        return JBScrollPane(innerPanel).apply {
            border = null
        }
    }

    override fun isModified(): Boolean {
        // Credential changes
        if (pendingTokens.isNotEmpty() ||
            pendingNexusPassword != null ||
            pendingNexusUsername != null ||
            pendingBitbucketUsername != null
        ) return true

        // Dialog panel bindings (URLs, timeouts)
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

        log.info("[Settings:Connections] Applied connection settings")
    }

    override fun reset() {
        dialogPanel?.reset()
        pendingTokens.clear()
        pendingNexusPassword = null
        pendingNexusUsername = null
        pendingBitbucketUsername = null
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    // ========== Service group helpers ==========

    /**
     * Standard service group with a single Access Token field.
     * Used by Jira, Bamboo, SonarQube, Sourcegraph.
     */
    private fun Panel.tokenServiceGroup(
        title: String,
        serviceType: ServiceType,
        urlGetter: () -> String,
        urlSetter: (String) -> Unit,
        collapsible: Boolean
    ) {
        val statusLabel = JLabel("")
        var currentUrl = urlGetter()
        var currentToken = ""
        var tokenField: JPasswordField? = null

        val body: Panel.() -> Unit = {
            row("Server URL:") {
                textField()
                    .columns(40)
                    .bindText(urlGetter, urlSetter)
                    .onChanged { field -> currentUrl = field.text }
                    .validationOnApply {
                        val url = it.text.trim()
                        if (url.isNotBlank() && !url.startsWith("https://")) {
                            warning("Using HTTP is insecure. Credentials will be sent in plaintext. Use HTTPS instead.")
                        } else null
                    }
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
                        if (!isInitializing) {
                            pendingTokens[serviceType] = newToken
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
                        invokeLater {
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

        if (collapsible) {
            collapsibleGroup(title) { body() }
        } else {
            group(title) { body() }
        }

        // Load existing token in background to avoid blocking EDT on PasswordSafe read
        ApplicationManager.getApplication().executeOnPooledThread {
            val existingToken = credentialStore.getToken(serviceType) ?: ""
            if (existingToken.isNotBlank()) {
                currentToken = existingToken
                invokeLater {
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
        urlSetter: (String) -> Unit,
        collapsible: Boolean
    ) {
        val existingUsername = connSettings.state.bitbucketUsername
        val statusLabel = JLabel("")
        var currentUrl = urlGetter()
        var currentToken = ""
        var tokenField: JPasswordField? = null
        var usernameField: javax.swing.JTextField? = null

        val body: Panel.() -> Unit = {
            row("Server URL:") {
                textField()
                    .columns(40)
                    .bindText(urlGetter, urlSetter)
                    .onChanged { field -> currentUrl = field.text }
                    .validationOnApply {
                        val url = it.text.trim()
                        if (url.isNotBlank() && !url.startsWith("https://")) {
                            warning("Using HTTP is insecure. Credentials will be sent in plaintext. Use HTTPS instead.")
                        } else null
                    }
                    .comment("e.g., https://bitbucket.company.com")
            }
            row("Access Token:") {
                passwordField()
                    .columns(40)
                    .applyToComponent { tokenField = this }
                    .onChanged { field ->
                        val newToken = String(field.password)
                        currentToken = newToken
                        if (!isInitializing) {
                            pendingTokens[ServiceType.BITBUCKET] = newToken
                        }
                    }
            }
            row("Username:") {
                textField()
                    .columns(40)
                    .applyToComponent {
                        text = existingUsername
                        usernameField = this
                    }
                    .onChanged { field ->
                        if (!isInitializing) {
                            pendingBitbucketUsername = field.text
                        }
                    }
                    .comment("Auto-detected on Test Connection, or enter manually")
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

                        // On success, auto-detect username via whoami
                        if (result is ApiResult.Success) {
                            val detectedUsername = authTestService.fetchBitbucketUsername(url, token)
                            if (!detectedUsername.isNullOrBlank()) {
                                invokeLater {
                                    usernameField?.text = detectedUsername
                                    pendingBitbucketUsername = detectedUsername
                                    statusLabel.text = "\u2713 Connected as $detectedUsername"
                                }
                                return@runBackgroundableTask
                            }
                        }

                        invokeLater {
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

        if (collapsible) {
            collapsibleGroup(title) { body() }
        } else {
            group(title) { body() }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val existingToken = credentialStore.getToken(ServiceType.BITBUCKET) ?: ""
            if (existingToken.isNotBlank()) {
                currentToken = existingToken
                invokeLater { tokenField?.text = existingToken }
            }
        }
    }

    /**
     * Nexus-specific service group with Username + Password fields.
     * Nexus Docker Registry uses Basic auth (username:password), not a single access token.
     * Always collapsible (optional service).
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
                    .validationOnApply {
                        val url = it.text.trim()
                        if (url.isNotBlank() && !url.startsWith("https://")) {
                            warning("Using HTTP is insecure. Credentials will be sent in plaintext. Use HTTPS instead.")
                        } else null
                    }
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
                        if (!isInitializing) {
                            pendingNexusUsername = field.text
                        }
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
                        if (!isInitializing) {
                            pendingNexusPassword = newPassword
                        }
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
                        invokeLater {
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
                invokeLater {
                    passwordField?.text = existingPassword
                }
            }
        }
    }
}
