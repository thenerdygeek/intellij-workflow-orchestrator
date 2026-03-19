package com.workflow.orchestrator.core.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.auth.AuthTestService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketProject
import com.workflow.orchestrator.core.bitbucket.BitbucketRepo
import com.workflow.orchestrator.core.bitbucket.GitRemoteParser
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.runBlocking
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.SwingUtilities

/**
 * Merged "General" settings page combining:
 * - Connections (all 6 service connections with Test Connection buttons)
 * - Repository (Bitbucket project/repo selection)
 * - Module Visibility (5 module enable/disable checkboxes)
 */
class GeneralConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private val log = Logger.getInstance(GeneralConfigurable::class.java)
    private val connSettings = ConnectionSettings.getInstance()
    private val pluginSettings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val authTestService = AuthTestService()

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    // --- Deferred credential saves (from ConnectionsConfigurable) ---
    private val pendingTokens = mutableMapOf<ServiceType, String>()
    private var pendingNexusPassword: String? = null
    private var pendingNexusUsername: String? = null
    private var pendingBitbucketUsername: String? = null

    // --- Repository state (from RepositoryConfigurable) ---
    private val projectKeyModel = DefaultComboBoxModel<String>()
    private val repoSlugModel = DefaultComboBoxModel<String>()
    private var projectKeyCombo: JComboBox<String>? = null
    private var repoSlugCombo: JComboBox<String>? = null
    private val repoStatusLabel = JBLabel("")
    private var fetchedProjects: List<BitbucketProject> = emptyList()
    private var fetchedRepos: List<BitbucketRepo> = emptyList()

    override fun getId(): String = "workflow.orchestrator.general"
    override fun getDisplayName(): String = "General"

    override fun createComponent(): JComponent {
        val currentProjectKey = pluginSettings.state.bitbucketProjectKey.orEmpty()
        val currentRepoSlug = pluginSettings.state.bitbucketRepoSlug.orEmpty()

        // Pre-populate repo combo models with current values
        if (currentProjectKey.isNotBlank()) {
            projectKeyModel.addElement(currentProjectKey)
            projectKeyModel.selectedItem = currentProjectKey
        }
        if (currentRepoSlug.isNotBlank()) {
            repoSlugModel.addElement(currentRepoSlug)
            repoSlugModel.selectedItem = currentRepoSlug
        }

        val innerPanel = panel {
            // ========== Section 1: Connections ==========
            connectionsSection()

            // ========== Section 2: Repository ==========
            repositorySection(currentProjectKey, currentRepoSlug)

            // ========== Section 3: Module Visibility ==========
            moduleVisibilitySection()
        }
        dialogPanel = innerPanel

        // Auto-detect repo on first open if not configured
        if (currentProjectKey.isBlank() && currentRepoSlug.isBlank()) {
            autoDetectRepo()
        }

        return JBScrollPane(innerPanel).apply {
            border = null
        }
    }

    override fun isModified(): Boolean {
        // Credential changes
        if (pendingTokens.isNotEmpty() || pendingNexusPassword != null || pendingNexusUsername != null || pendingBitbucketUsername != null) return true

        // Repository changes
        val currentProjectKey = pluginSettings.state.bitbucketProjectKey.orEmpty()
        val currentRepoSlug = pluginSettings.state.bitbucketRepoSlug.orEmpty()
        val selectedProject = projectKeyCombo?.selectedItem as? String ?: ""
        val selectedRepo = repoSlugCombo?.selectedItem as? String ?: ""
        if (selectedProject != currentProjectKey || selectedRepo != currentRepoSlug) return true

        // Dialog panel bindings (module toggles, etc.)
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

        // Save repository settings
        pluginSettings.state.bitbucketProjectKey = (projectKeyCombo?.selectedItem as? String).orEmpty()
        pluginSettings.state.bitbucketRepoSlug = (repoSlugCombo?.selectedItem as? String).orEmpty()
        log.info("[Settings:General] Saved project='${pluginSettings.state.bitbucketProjectKey}', repo='${pluginSettings.state.bitbucketRepoSlug}'")
    }

    override fun reset() {
        dialogPanel?.reset()
        projectKeyCombo?.selectedItem = pluginSettings.state.bitbucketProjectKey.orEmpty()
        repoSlugCombo?.selectedItem = pluginSettings.state.bitbucketRepoSlug.orEmpty()
    }

    override fun disposeUIResources() {
        dialogPanel = null
        projectKeyCombo = null
        repoSlugCombo = null
    }

    // ========== Connections Section ==========

    private fun Panel.connectionsSection() {
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
     * Used by Jira, Bamboo, SonarQube, Sourcegraph.
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
        var usernameField: javax.swing.JTextField? = null

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
                    .applyToComponent {
                        text = existingUsername
                        usernameField = this
                    }
                    .onChanged { field -> pendingBitbucketUsername = field.text }
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
                                SwingUtilities.invokeLater {
                                    usernameField?.text = detectedUsername
                                    pendingBitbucketUsername = detectedUsername
                                    statusLabel.text = "\u2713 Connected as $detectedUsername"
                                }
                                return@runBackgroundableTask
                            }
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

    // ========== Repository Section ==========

    private fun Panel.repositorySection(currentProjectKey: String, currentRepoSlug: String) {
        collapsibleGroup("Repository") {
            row {
                comment("Configure which Bitbucket project and repository this IDE project maps to.")
            }

            row("Project Key:") {
                projectKeyCombo = comboBox(projectKeyModel)
                    .applyToComponent {
                        isEditable = true
                        addActionListener {
                            val selected = selectedItem as? String ?: ""
                            if (selected.isNotBlank() && fetchedProjects.isNotEmpty()) {
                                loadRepos(selected)
                            }
                        }
                    }
                    .comment("Bitbucket project key (e.g., MYPROJ)")
                    .component
            }

            row("Repository:") {
                repoSlugCombo = comboBox(repoSlugModel)
                    .applyToComponent {
                        isEditable = true
                    }
                    .comment("Repository slug (e.g., my-service)")
                    .component
            }

            row {
                button("Auto-detect from Git Remote") {
                    autoDetectRepo()
                }
                button("Fetch from Bitbucket") {
                    loadProjects()
                }
                cell(repoStatusLabel)
            }
        }
    }

    private fun autoDetectRepo() {
        repoStatusLabel.text = "Detecting from git remote..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = GitRemoteParser.detectFromProject(project)
            SwingUtilities.invokeLater {
                if (result != null) {
                    val (projKey, repoSlug) = result
                    if (projectKeyModel.getIndexOf(projKey) < 0) {
                        projectKeyModel.addElement(projKey)
                    }
                    projectKeyCombo?.selectedItem = projKey

                    if (repoSlugModel.getIndexOf(repoSlug) < 0) {
                        repoSlugModel.addElement(repoSlug)
                    }
                    repoSlugCombo?.selectedItem = repoSlug

                    repoStatusLabel.text = "Detected: $projKey / $repoSlug"
                } else {
                    repoStatusLabel.text = "Could not detect from git remote"
                }
            }
        }
    }

    private fun loadProjects() {
        val bitbucketUrl = connSettings.state.bitbucketUrl.trimEnd('/')
        if (bitbucketUrl.isBlank()) {
            repoStatusLabel.text = "Configure Bitbucket URL in Connections first"
            return
        }

        repoStatusLabel.text = "Fetching projects..."
        val client = BitbucketBranchClient(
            baseUrl = bitbucketUrl,
            tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
        )

        runBackgroundableTask("Fetching Bitbucket projects", project, false) {
            val result = runBlocking { client.getProjects() }
            SwingUtilities.invokeLater {
                when (result) {
                    is ApiResult.Success -> {
                        fetchedProjects = result.data
                        val currentSelection = projectKeyCombo?.selectedItem as? String
                        projectKeyModel.removeAllElements()
                        for (proj in result.data) {
                            projectKeyModel.addElement(proj.key)
                        }
                        if (currentSelection != null && projectKeyModel.getIndexOf(currentSelection) >= 0) {
                            projectKeyCombo?.selectedItem = currentSelection
                        }
                        repoStatusLabel.text = "Found ${result.data.size} projects"

                        // If a project is selected, also load its repos
                        val selected = projectKeyCombo?.selectedItem as? String
                        if (!selected.isNullOrBlank()) {
                            loadRepos(selected)
                        }
                    }
                    is ApiResult.Error -> {
                        repoStatusLabel.text = "Failed: ${result.message}"
                    }
                }
            }
        }
    }

    private fun loadRepos(projectKey: String) {
        val bitbucketUrl = connSettings.state.bitbucketUrl.trimEnd('/')
        if (bitbucketUrl.isBlank()) return

        val client = BitbucketBranchClient(
            baseUrl = bitbucketUrl,
            tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
        )

        runBackgroundableTask("Fetching Bitbucket repos", project, false) {
            val result = runBlocking { client.getRepos(projectKey) }
            SwingUtilities.invokeLater {
                when (result) {
                    is ApiResult.Success -> {
                        fetchedRepos = result.data
                        val currentSelection = repoSlugCombo?.selectedItem as? String
                        repoSlugModel.removeAllElements()
                        for (repo in result.data) {
                            repoSlugModel.addElement(repo.slug)
                        }
                        if (currentSelection != null && repoSlugModel.getIndexOf(currentSelection) >= 0) {
                            repoSlugCombo?.selectedItem = currentSelection
                        }
                        repoStatusLabel.text = "Found ${result.data.size} repos in $projectKey"
                    }
                    is ApiResult.Error -> {
                        repoStatusLabel.text = "Failed to load repos: ${result.message}"
                    }
                }
            }
        }
    }

    // ========== Module Visibility Section ==========

    private fun Panel.moduleVisibilitySection() {
        collapsibleGroup("Module Visibility") {
            row {
                checkBox("Sprint (Jira)")
                    .bindSelected(
                        { pluginSettings.state.sprintModuleEnabled },
                        { pluginSettings.state.sprintModuleEnabled = it }
                    )
            }
            row {
                checkBox("Build (Bamboo)")
                    .bindSelected(
                        { pluginSettings.state.buildModuleEnabled },
                        { pluginSettings.state.buildModuleEnabled = it }
                    )
            }
            row {
                checkBox("Quality (SonarQube)")
                    .bindSelected(
                        { pluginSettings.state.qualityModuleEnabled },
                        { pluginSettings.state.qualityModuleEnabled = it }
                    )
            }
            row {
                checkBox("Automation (Docker/Bamboo)")
                    .bindSelected(
                        { pluginSettings.state.automationModuleEnabled },
                        { pluginSettings.state.automationModuleEnabled = it }
                    )
            }
            row {
                checkBox("Handover (Bitbucket PR)")
                    .bindSelected(
                        { pluginSettings.state.handoverModuleEnabled },
                        { pluginSettings.state.handoverModuleEnabled = it }
                    )
            }
        }
    }
}
