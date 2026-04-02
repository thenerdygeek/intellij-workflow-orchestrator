package com.workflow.orchestrator.core.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.auth.AuthTestService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import kotlinx.coroutines.runBlocking
import javax.swing.*
import javax.swing.table.DefaultTableModel

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

    // Guard against false modification during initial token load
    private var isInitializing = true

    // --- Repository table state ---
    private val repoTableColumnNames = arrayOf("Name", "Bitbucket", "Bamboo Plan", "SonarQube", "Primary")
    private val repoTableModel = object : DefaultTableModel(repoTableColumnNames, 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 4) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private var repoTable: JBTable? = null
    private val repoStatusLabel = JBLabel("")

    // In-memory working copy of repos for the table (applied on save)
    private val editedRepos = mutableListOf<RepoConfig>()

    override fun getId(): String = "workflow.orchestrator.general"
    override fun getDisplayName(): String = "General"

    override fun createComponent(): JComponent {
        // Load existing repos into working copy
        editedRepos.clear()
        editedRepos.addAll(pluginSettings.getRepos())
        refreshRepoTable()

        isInitializing = true

        val innerPanel = panel {
            // ========== Section 1: Connections ==========
            connectionsSection()

            // ========== Section 2: Repository ==========
            repositorySection()

            // ========== Section 3: Module Visibility ==========
            moduleVisibilitySection()
        }
        dialogPanel = innerPanel

        isInitializing = false

        return JBScrollPane(innerPanel).apply {
            border = null
        }
    }

    override fun isModified(): Boolean {
        // Credential changes
        if (pendingTokens.isNotEmpty() || pendingNexusPassword != null || pendingNexusUsername != null || pendingBitbucketUsername != null) return true

        // Repository table changes
        val savedRepos = pluginSettings.getRepos()
        if (editedRepos.size != savedRepos.size) return true
        for (i in editedRepos.indices) {
            val edited = editedRepos[i]
            val saved = savedRepos[i]
            if (edited.name != saved.name ||
                edited.bitbucketProjectKey != saved.bitbucketProjectKey ||
                edited.bitbucketRepoSlug != saved.bitbucketRepoSlug ||
                edited.bambooPlanKey != saved.bambooPlanKey ||
                edited.sonarProjectKey != saved.sonarProjectKey ||
                edited.dockerTagKey != saved.dockerTagKey ||
                edited.defaultTargetBranch != saved.defaultTargetBranch ||
                edited.isPrimary != saved.isPrimary
            ) return true
        }

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

        // Save repository table to settings
        pluginSettings.state.repos.clear()
        for (repo in editedRepos) {
            val copy = RepoConfig().apply {
                name = repo.name ?: ""
                bitbucketProjectKey = repo.bitbucketProjectKey ?: ""
                bitbucketRepoSlug = repo.bitbucketRepoSlug ?: ""
                bambooPlanKey = repo.bambooPlanKey ?: ""
                sonarProjectKey = repo.sonarProjectKey ?: ""
                dockerTagKey = repo.dockerTagKey ?: ""
                defaultTargetBranch = repo.defaultTargetBranch ?: "develop"
                localVcsRootPath = repo.localVcsRootPath ?: ""
                isPrimary = repo.isPrimary
            }
            pluginSettings.state.repos.add(copy)
        }

        log.info("[Settings:General] Saved ${editedRepos.size} repos, primary='${(editedRepos.find { it.isPrimary } ?: editedRepos.firstOrNull())?.displayLabel}'")
    }

    override fun reset() {
        dialogPanel?.reset()
        editedRepos.clear()
        editedRepos.addAll(pluginSettings.getRepos())
        refreshRepoTable()
    }

    override fun disposeUIResources() {
        dialogPanel = null
        repoTable = null
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
        serviceGroup("Sourcegraph", ServiceType.SOURCEGRAPH,
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

    private fun Panel.repositorySection() {
        collapsibleGroup("Repository") {
            row {
                comment("Configure which repositories this IDE project maps to. The primary repo is used as default context.")
            }

            row {
                val table = JBTable(repoTableModel).apply {
                    setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                    preferredScrollableViewportSize = JBUI.size(600, 120)
                    tableHeader.reorderingAllowed = false
                    columnModel.getColumn(4).preferredWidth = JBUI.scale(60)
                    columnModel.getColumn(4).maxWidth = JBUI.scale(70)
                }
                repoTable = table
                val scrollPane = JBScrollPane(table)
                cell(scrollPane)
                    .align(AlignX.FILL)
            }

            row {
                button("Add") { onAddRepo() }
                button("Edit") { onEditRepo() }
                button("Remove") { onRemoveRepo() }
                button("Auto-Detect") { onAutoDetectRepos() }
                button("Clear Branch Overrides") {
                    DefaultBranchResolver.getInstance(project).clearAllOverrides()
                    repoStatusLabel.text = "Branch target overrides cleared"
                    repoStatusLabel.foreground = StatusColors.SUCCESS
                }
                cell(repoStatusLabel)
            }
        }
    }

    private fun refreshRepoTable() {
        repoTableModel.rowCount = 0
        for (repo in editedRepos) {
            repoTableModel.addRow(
                arrayOf<Any>(
                    repo.name ?: "",
                    "${repo.bitbucketProjectKey ?: ""}/${repo.bitbucketRepoSlug ?: ""}",
                    repo.bambooPlanKey ?: "",
                    repo.sonarProjectKey ?: "",
                    repo.isPrimary
                )
            )
        }
    }

    private fun onAddRepo() {
        val dialog = RepoConfigDialog(project, null)
        if (dialog.showAndGet()) {
            val newRepo = dialog.toRepoConfig()
            if (newRepo.isPrimary) {
                editedRepos.forEach { it.isPrimary = false }
            }
            editedRepos.add(newRepo)
            refreshRepoTable()
        }
    }

    private fun onEditRepo() {
        val selectedRow = repoTable?.selectedRow ?: -1
        if (selectedRow < 0 || selectedRow >= editedRepos.size) {
            repoStatusLabel.text = "Select a row to edit"
            return
        }
        val existing = editedRepos[selectedRow]
        val dialog = RepoConfigDialog(project, existing)
        if (dialog.showAndGet()) {
            val updated = dialog.toRepoConfig()
            updated.localVcsRootPath = existing.localVcsRootPath ?: ""
            if (updated.isPrimary) {
                editedRepos.forEach { it.isPrimary = false }
            }
            editedRepos[selectedRow] = updated
            refreshRepoTable()
            repoTable?.setRowSelectionInterval(selectedRow, selectedRow)
        }
    }

    private fun onRemoveRepo() {
        val selectedRow = repoTable?.selectedRow ?: -1
        if (selectedRow < 0 || selectedRow >= editedRepos.size) {
            repoStatusLabel.text = "Select a row to remove"
            return
        }
        val repo = editedRepos[selectedRow]
        val answer = Messages.showYesNoDialog(
            project,
            "Remove repository '${repo.displayLabel}' from configuration?",
            "Remove Repository",
            Messages.getQuestionIcon()
        )
        if (answer == Messages.YES) {
            editedRepos.removeAt(selectedRow)
            refreshRepoTable()
            repoStatusLabel.text = "Removed '${repo.displayLabel}'"
        }
    }

    private fun onAutoDetectRepos() {
        repoStatusLabel.text = "Detecting repositories from VCS roots..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolver = RepoContextResolver.getInstance(project)
            val detected = resolver.autoDetectRepos()
            SwingUtilities.invokeLater {
                if (detected.isEmpty()) {
                    repoStatusLabel.text = "No repositories detected from git remotes"
                    return@invokeLater
                }
                var added = 0
                for (repo in detected) {
                    val alreadyExists = editedRepos.any {
                        it.bitbucketProjectKey.equals(repo.bitbucketProjectKey, ignoreCase = true) &&
                            it.bitbucketRepoSlug.equals(repo.bitbucketRepoSlug, ignoreCase = true)
                    }
                    if (!alreadyExists) {
                        if (repo.isPrimary && editedRepos.any { it.isPrimary }) {
                            repo.isPrimary = false
                        }
                        editedRepos.add(repo)
                        added++
                    }
                }
                refreshRepoTable()
                repoStatusLabel.text = if (added > 0) {
                    "Added $added new repo(s) from ${detected.size} detected"
                } else {
                    "All ${detected.size} detected repos already configured"
                }
            }
        }
    }

    // ========== Repo Config Dialog ==========

    private class RepoConfigDialog(
        project: Project,
        private val existing: RepoConfig?
    ) : DialogWrapper(project, false) {

        private val nameField = JBTextField(existing?.name ?: "", 30)
        private val bbProjectField = JBTextField(existing?.bitbucketProjectKey ?: "", 30)
        private val bbRepoField = JBTextField(existing?.bitbucketRepoSlug ?: "", 30)
        private val bambooField = JBTextField(existing?.bambooPlanKey ?: "", 30)
        private val sonarField = JBTextField(existing?.sonarProjectKey ?: "", 30)
        private val dockerField = JBTextField(existing?.dockerTagKey ?: "", 30)
        private val branchField = JBTextField(existing?.defaultTargetBranch ?: "develop", 30)
        private val primaryCheckbox = JCheckBox("Primary repository", existing?.isPrimary ?: false)

        init {
            title = if (existing != null) "Edit Repository" else "Add Repository"
            init()
        }

        override fun createCenterPanel(): JComponent {
            return panel {
                row("Name:") { cell(nameField).align(AlignX.FILL) }
                row("Bitbucket Project Key:") { cell(bbProjectField).align(AlignX.FILL) }
                row("Bitbucket Repo Slug:") { cell(bbRepoField).align(AlignX.FILL) }
                row("Bamboo Plan Key:") { cell(bambooField).align(AlignX.FILL) }
                row("SonarQube Project Key:") { cell(sonarField).align(AlignX.FILL) }
                row("Docker Tag Key:") { cell(dockerField).align(AlignX.FILL) }
                row("Default Target Branch:") { cell(branchField).align(AlignX.FILL) }
                row { cell(primaryCheckbox) }
            }
        }

        fun toRepoConfig(): RepoConfig = RepoConfig().apply {
            name = nameField.text.trim()
            bitbucketProjectKey = bbProjectField.text.trim()
            bitbucketRepoSlug = bbRepoField.text.trim()
            bambooPlanKey = bambooField.text.trim()
            sonarProjectKey = sonarField.text.trim()
            dockerTagKey = dockerField.text.trim()
            defaultTargetBranch = branchField.text.trim().ifBlank { "develop" }
            isPrimary = primaryCheckbox.isSelected
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
