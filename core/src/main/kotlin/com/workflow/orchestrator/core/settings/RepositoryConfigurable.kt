package com.workflow.orchestrator.core.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
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
import javax.swing.SwingUtilities

/**
 * Settings page for Bitbucket repository configuration.
 * Auto-detects project key and repo slug from git remote,
 * with manual override via dropdowns populated from Bitbucket API.
 */
class RepositoryConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private val log = Logger.getInstance(RepositoryConfigurable::class.java)
    private val settings = PluginSettings.getInstance(project)

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    private val projectKeyModel = DefaultComboBoxModel<String>()
    private val repoSlugModel = DefaultComboBoxModel<String>()
    private var projectKeyCombo: JComboBox<String>? = null
    private var repoSlugCombo: JComboBox<String>? = null
    private val statusLabel = JBLabel("")

    // Cache fetched data
    private var fetchedProjects: List<BitbucketProject> = emptyList()
    private var fetchedRepos: List<BitbucketRepo> = emptyList()

    override fun getId(): String = "workflow.orchestrator.repository"
    override fun getDisplayName(): String = "Repository"

    override fun createComponent(): JComponent {
        val currentProjectKey = settings.state.bitbucketProjectKey.orEmpty()
        val currentRepoSlug = settings.state.bitbucketRepoSlug.orEmpty()

        // Pre-populate combo models with current values
        if (currentProjectKey.isNotBlank()) {
            projectKeyModel.addElement(currentProjectKey)
            projectKeyModel.selectedItem = currentProjectKey
        }
        if (currentRepoSlug.isNotBlank()) {
            repoSlugModel.addElement(currentRepoSlug)
            repoSlugModel.selectedItem = currentRepoSlug
        }

        val panel = panel {
            group("Bitbucket Repository") {
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
                                    // When project key changes, refresh repos
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
                        autoDetect()
                    }
                    button("Fetch from Bitbucket") {
                        loadProjects()
                    }
                    cell(statusLabel)
                }
            }
        }
        dialogPanel = panel

        // Auto-detect on first open if not configured
        if (currentProjectKey.isBlank() && currentRepoSlug.isBlank()) {
            autoDetect()
        }

        return panel
    }

    override fun isModified(): Boolean {
        val currentProjectKey = settings.state.bitbucketProjectKey.orEmpty()
        val currentRepoSlug = settings.state.bitbucketRepoSlug.orEmpty()
        val selectedProject = projectKeyCombo?.selectedItem as? String ?: ""
        val selectedRepo = repoSlugCombo?.selectedItem as? String ?: ""
        return selectedProject != currentProjectKey || selectedRepo != currentRepoSlug
    }

    override fun apply() {
        settings.state.bitbucketProjectKey = (projectKeyCombo?.selectedItem as? String).orEmpty()
        settings.state.bitbucketRepoSlug = (repoSlugCombo?.selectedItem as? String).orEmpty()
        log.info("[Settings:Repo] Saved project='${settings.state.bitbucketProjectKey}', repo='${settings.state.bitbucketRepoSlug}'")
    }

    override fun reset() {
        projectKeyCombo?.selectedItem = settings.state.bitbucketProjectKey.orEmpty()
        repoSlugCombo?.selectedItem = settings.state.bitbucketRepoSlug.orEmpty()
    }

    override fun disposeUIResources() {
        dialogPanel = null
        projectKeyCombo = null
        repoSlugCombo = null
    }

    private fun autoDetect() {
        statusLabel.text = "Detecting from git remote..."
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

                    statusLabel.text = "Detected: $projKey / $repoSlug"
                } else {
                    statusLabel.text = "Could not detect from git remote"
                }
            }
        }
    }

    private fun loadProjects() {
        val connState = ConnectionSettings.getInstance().state
        val bitbucketUrl = connState.bitbucketUrl.trimEnd('/')
        if (bitbucketUrl.isBlank()) {
            statusLabel.text = "Configure Bitbucket URL in Connections first"
            return
        }

        statusLabel.text = "Fetching projects..."
        val credentialStore = CredentialStore()
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
                        statusLabel.text = "Found ${result.data.size} projects"

                        // If a project is selected, also load its repos
                        val selected = projectKeyCombo?.selectedItem as? String
                        if (!selected.isNullOrBlank()) {
                            loadRepos(selected)
                        }
                    }
                    is ApiResult.Error -> {
                        statusLabel.text = "Failed: ${result.message}"
                    }
                }
            }
        }
    }

    private fun loadRepos(projectKey: String) {
        val connState = ConnectionSettings.getInstance().state
        val bitbucketUrl = connState.bitbucketUrl.trimEnd('/')
        if (bitbucketUrl.isBlank()) return

        val credentialStore = CredentialStore()
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
                        statusLabel.text = "Found ${result.data.size} repos in $projectKey"
                    }
                    is ApiResult.Error -> {
                        statusLabel.text = "Failed to load repos: ${result.message}"
                    }
                }
            }
        }
    }
}
