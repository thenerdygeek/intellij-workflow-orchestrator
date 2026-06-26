package com.workflow.orchestrator.core.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.auth.AuthTestService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.security.BaseUrlValidator
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
        if (pendingTokens.isNotEmpty() || pendingBitbucketUsername != null) return true

        // Dialog panel bindings (URLs, timeouts)
        return dialogPanel?.isModified() ?: false
    }

    override fun apply() {
        dialogPanel?.apply()

        // Validate service base URLs before persisting — blocks SSRF via crafted workspace settings.
        val urlsToValidate = listOf(
            "Jira" to connSettings.state.jiraUrl,
            "Bamboo" to connSettings.state.bambooUrl,
            "Bitbucket" to connSettings.state.bitbucketUrl,
            "SonarQube" to connSettings.state.sonarUrl,
        )
        val invalidUrlErrors = mutableListOf<String>()
        for ((name, url) in urlsToValidate) {
            if (url.isBlank()) continue  // blank = not configured, skip validation
            when (val result = BaseUrlValidator.validate(url)) {
                is BaseUrlValidator.ValidationResult.Invalid -> {
                    // Collect, do NOT silently `return`. The old early-return aborted apply() before
                    // saving tokens AND before clearing pendingTokens — so one bad URL silently
                    // dropped every credential and left the panel stuck "modified". We throw
                    // ConfigurationException below instead: the platform surfaces the error and keeps
                    // the dialog OPEN, and pendingTokens stays intact so the user can fix the URL and
                    // re-Apply without re-entering any tokens.
                    log.warn("[Settings:Connections] $name URL rejected by SSRF guard: ${result.reason}")
                    invalidUrlErrors += "$name: ${result.reason}"
                }
                is BaseUrlValidator.ValidationResult.SoftWarning -> {
                    log.warn("[Settings:Connections] $name URL soft-warning: ${result.warning}")
                    com.intellij.notification.NotificationGroupManager.getInstance()
                        .getNotificationGroup("Workflow Orchestrator")
                        ?.createNotification(
                            "$name URL warning",
                            result.warning,
                            com.intellij.notification.NotificationType.WARNING
                        )
                        // Actionable link back to this settings page so the user can fix the
                        // URL without hunting for it (audit core:F-13).
                        ?.addAction(com.intellij.notification.NotificationAction.createSimple("Open Connection settings") {
                            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                                .showSettingsDialog(project, ConnectionsConfigurable::class.java)
                        })
                        ?.notify(project)
                    // Non-blocking — allow save to continue
                }
                BaseUrlValidator.ValidationResult.Valid -> { /* all good */ }
            }
        }
        if (invalidUrlErrors.isNotEmpty()) {
            // Visible, blocking error that keeps the settings dialog open. Nothing below runs, so no
            // tokens are saved and pendingTokens is preserved for the retry after the user fixes the URL.
            throw ConfigurationException(
                invalidUrlErrors.joinToString("\n"),
                "Invalid Connection URL",
            )
        }

        // Save credentials only on explicit Apply — not on every keystroke.
        // storeToken verifies the write landed; surface a failure instead of silently dropping it.
        for ((serviceType, token) in pendingTokens) {
            if (token.isNotBlank() && !credentialStore.storeToken(serviceType, token)) {
                notifyTokenSaveFailure(serviceType)
            }
        }
        pendingTokens.clear()

        pendingBitbucketUsername?.let { username ->
            connSettings.state.bitbucketUsername = username
        }
        pendingBitbucketUsername = null

        // Invalidate the URL-keyed token cache so a URL change takes effect immediately
        // rather than waiting for the 1-hour TTL to expire (F-8 fix defence-in-depth).
        CredentialStore.clearGlobalCache()
        log.info("[Settings:Connections] Applied connection settings")
    }

    override fun reset() {
        dialogPanel?.reset()
        pendingTokens.clear()
        pendingBitbucketUsername = null
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    private fun notifyTokenSaveFailure(service: ServiceType) {
        log.warn("[Settings:Connections] Token write verification failed for ${service.name}")
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Workflow Orchestrator")
            ?.createNotification(
                "${service.displayName} token not saved",
                "Your IDE password storage did not persist the token. Check " +
                    "Settings → Appearance & Behavior → System Settings → Passwords, then try again.",
                com.intellij.notification.NotificationType.ERROR
            )
            ?.notify(project)
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
                    val typedToken = currentToken
                    if (url.isBlank()) {
                        statusLabel.text = "Please enter URL and token"
                        return@button
                    }
                    statusLabel.text = "Testing..."
                    runBackgroundableTask("Testing $title", project, false) {
                        // P2-9: PasswordSafe fallback read stays OFF the EDT — first access
                        // on Windows can take seconds (credential vault unlock).
                        val token = typedToken.ifBlank { credentialStore.getToken(serviceType) }
                        if (token.isNullOrBlank()) {
                            invokeLater { statusLabel.text = "Please enter URL and token" }
                            return@runBackgroundableTask
                        }
                        val result = runBlockingCancellable {
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
                    val typedToken = currentToken
                    if (url.isBlank()) {
                        statusLabel.text = "Please enter URL and token"
                        return@button
                    }
                    statusLabel.text = "Testing..."
                    runBackgroundableTask("Testing $title", project, false) {
                        // P2-9: PasswordSafe fallback read stays OFF the EDT — first access
                        // on Windows can take seconds (credential vault unlock).
                        val token = typedToken.ifBlank { credentialStore.getToken(ServiceType.BITBUCKET) }
                        if (token.isNullOrBlank()) {
                            invokeLater { statusLabel.text = "Please enter URL and token" }
                            return@runBackgroundableTask
                        }
                        val result = runBlockingCancellable {
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

}
