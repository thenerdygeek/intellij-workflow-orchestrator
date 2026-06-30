package com.workflow.orchestrator.core.onboarding

import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.auth.AuthTestService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.util.ui.JBUI
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.JTextField
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater as platformInvokeLater

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

    /** Modality-aware EDT dispatch. Platform `invokeLater` defaults to NON_MODAL from a
     *  background thread, which is suspended while this modal dialog is open — UI updates
     *  scheduled that way never fire until the dialog closes. */
    private fun invokeLater(runnable: Runnable) {
        val cp = this.contentPane
        val modality = if (cp != null) ModalityState.stateForComponent(cp) else ModalityState.any()
        platformInvokeLater(modality) { runnable.run() }
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
    }

    private fun Panel.connectionSection(
        title: String,
        serviceType: ServiceType
    ) {
        val urlField = JTextField(20)
        val tokenField = JPasswordField(20)
        val statusLabel = JLabel("")
        // Jira-only: shows groups + applicationRoles after successful auth-test.
        // For other services it stays empty / hidden — the auth-test endpoints
        // don't expose this metadata.
        val jiraIdentityLabel = JLabel("").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(font.size2D - 1f).deriveFont(Font.PLAIN)
            border = JBUI.Borders.emptyTop(2)
            isVisible = false
        }

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
                    jiraIdentityLabel.isVisible = false
                    jiraIdentityLabel.text = ""
                    runBackgroundableTask("Testing $title", project, false) {
                        val result = runBlockingCancellable {
                            authTestService.testConnection(serviceType, url, token)
                        }
                        invokeLater {
                            when (result) {
                                is ApiResult.Success -> {
                                    statusLabel.text = "Connected!"
                                    successfulTests[serviceType] = TestResult(serviceType, url, token)
                                    if (serviceType == ServiceType.JIRA) {
                                        // Best-effort enrichment; failures (e.g. permission gaps)
                                        // silently leave the second line hidden.
                                        loadJiraIdentity(jiraIdentityLabel)
                                    }
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
            if (serviceType == ServiceType.JIRA) {
                row {
                    cell(jiraIdentityLabel)
                }
            }
        }
    }

    /** Truncate a string to [max] characters, appending an ellipsis when shortened. */
    private fun ellipsize(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    /**
     * Fetch the current Jira user with groups + applicationRoles expanded and render
     * a `"Member of: ... · Roles: ..."` line under the connection status.  Failures
     * (auth still valid but `myself` returns nothing useful, or insufficient permission)
     * leave the label hidden — the existing "Connected!" line carries forward.
     *
     * Threading: runBackgroundableTask hops to a pooled thread, runBlockingCancellable
     * suspends the JiraService call; UI updates run via `invokeLater` (modality-aware).
     */
    private fun loadJiraIdentity(label: JLabel) {
        runBackgroundableTask("Loading Jira identity", project, false) {
            val result = runBlockingCancellable {
                project.getService(JiraService::class.java).getMyselfExpanded()
            }
            invokeLater {
                if (result.isError) return@invokeLater
                val data = result.data!!
                val groups = data.groups.joinToString(", ")
                val roles = data.applicationRoles.joinToString(", ")
                if (groups.isBlank() && roles.isBlank()) return@invokeLater
                val combined = buildString {
                    if (groups.isNotBlank()) append("Member of: ").append(groups)
                    if (roles.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("Roles: ").append(roles)
                    }
                }
                label.text = ellipsize(combined, 80)
                label.isVisible = true
            }
        }
    }

    override fun doOKAction() {
        // Persist credentials only when user confirms with Finish Setup
        for ((_, result) in successfulTests) {
            when (result.serviceType) {
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
                ServiceType.WEB_SEARCH -> {
                    // WEB_SEARCH has no URL or credential in onboarding (configured via Settings)
                }
                ServiceType.ANTHROPIC -> {
                    // Anthropic is not part of the onboarding wizard (configured via AI Agent settings)
                }
            }
        }
        super.doOKAction()
    }
}
