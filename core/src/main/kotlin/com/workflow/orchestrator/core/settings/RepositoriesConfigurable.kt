package com.workflow.orchestrator.core.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * "Repositories" settings page — project repository configuration table.
 *
 * Ported verbatim from the Repository section of GeneralConfigurable. The JBTable,
 * RepoConfigDialog, and add/edit/remove/auto-detect helpers are preserved as-is
 * to avoid introducing regressions.
 */
class RepositoriesConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private val log = Logger.getInstance(RepositoriesConfigurable::class.java)
    private val pluginSettings = PluginSettings.getInstance(project)

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    // --- Repository table state ---
    private val repoTableColumnNames = arrayOf("Name", "Bitbucket", "Bamboo Plan", "Docker Tag", "SonarQube", "Primary")
    private val repoTableModel = object : DefaultTableModel(repoTableColumnNames, 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 5) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private var repoTable: JBTable? = null
    private val repoStatusLabel = JBLabel("")

    // In-memory working copy of repos for the table (applied on save)
    private val editedRepos = mutableListOf<RepoConfig>()

    override fun getId(): String = "workflow.orchestrator.repositories"
    override fun getDisplayName(): String = "Repositories"

    override fun createComponent(): JComponent {
        // Load existing repos into working copy
        editedRepos.clear()
        editedRepos.addAll(pluginSettings.getRepos())
        refreshRepoTable()

        val innerPanel = panel {
            repositorySection()
        }
        dialogPanel = innerPanel

        return JBScrollPane(innerPanel).apply {
            border = null
        }
    }

    override fun isModified(): Boolean {
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

        return dialogPanel?.isModified() ?: false
    }

    override fun apply() {
        dialogPanel?.apply()

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

        log.info("[Settings:Repositories] Saved ${editedRepos.size} repos, primary='${(editedRepos.find { it.isPrimary } ?: editedRepos.firstOrNull())?.displayLabel}'")
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

    // ========== Repository Section ==========

    private fun Panel.repositorySection() {
        group("Project Repositories") {
            row {
                comment("Mark one repo as 'primary' to use it as the default context for tabs.")
            }

            row {
                val table = JBTable(repoTableModel).apply {
                    setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                    preferredScrollableViewportSize = JBUI.size(600, 120)
                    tableHeader.reorderingAllowed = false
                    columnModel.getColumn(5).preferredWidth = JBUI.scale(60)
                    columnModel.getColumn(5).maxWidth = JBUI.scale(70)
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
                    repo.dockerTagKey ?: "",
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
        repoStatusLabel.foreground = StatusColors.INFO
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolver = RepoContextResolver.getInstance(project)
            val detected = resolver.autoDetectRepos()
            log.info("[Settings:Repos] VCS detection found ${detected.size} repo(s)")

            // Save detected repos to settings immediately so the orchestrator can use them.
            // For existing repos, merge missing fields (especially localVcsRootPath).
            val savedReposBefore = pluginSettings.getRepos().size
            for (repo in detected) {
                val existing = pluginSettings.getRepos().find {
                    it.bitbucketProjectKey.equals(repo.bitbucketProjectKey, ignoreCase = true) &&
                        it.bitbucketRepoSlug.equals(repo.bitbucketRepoSlug, ignoreCase = true)
                }
                if (existing != null) {
                    // Merge missing fields from VCS detection into existing repo
                    var merged = false
                    if (existing.localVcsRootPath.isNullOrBlank() && !repo.localVcsRootPath.isNullOrBlank()) {
                        existing.localVcsRootPath = repo.localVcsRootPath
                        merged = true
                    }
                    if (existing.name.isNullOrBlank() && !repo.name.isNullOrBlank()) {
                        existing.name = repo.name
                        merged = true
                    }
                    if (merged) {
                        log.info("[Settings:Repos] Merged VCS data into existing repo '${existing.displayLabel}': rootPath='${existing.localVcsRootPath}'")
                    }
                } else {
                    if (repo.isPrimary && pluginSettings.getRepos().any { it.isPrimary }) {
                        repo.isPrimary = false
                    }
                    pluginSettings.state.repos.add(repo)
                    log.info("[Settings:Repos] Saved new repo to settings: ${repo.displayLabel} (rootPath=${repo.localVcsRootPath})")
                }
            }
            log.info("[Settings:Repos] Settings repos: before=$savedReposBefore, after=${pluginSettings.getRepos().size}")

            // Show VCS detection results immediately in the UI
            invokeLater {
                editedRepos.clear()
                editedRepos.addAll(pluginSettings.getRepos())
                refreshRepoTable()
                repoStatusLabel.text = "Detecting project keys (Bamboo plan, SonarQube, Docker Tag)..."
            }

            // Run orchestrator for project key detection (Bamboo plan detection can be slow)
            log.info("[Settings:Repos] Running orchestrator.detectAll()...")
            val orchestrator = project.getService(
                com.workflow.orchestrator.core.autodetect.AutoDetectOrchestrator::class.java
            )
            val orchestratorResult = try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(60_000) {
                        orchestrator.detectAll()
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                log.warn("[Settings:Repos] Auto-detect timed out after 60s")
                null
            }
            log.info("[Settings:Repos] Orchestrator result: ${orchestratorResult?.filledFields ?: "timed out"}")

            // Log final repo state for debugging
            for (repo in pluginSettings.getRepos()) {
                log.info("[Settings:Repos] Final repo '${repo.displayLabel}': " +
                    "bambooPlan='${repo.bambooPlanKey}', dockerTag='${repo.dockerTagKey}', " +
                    "sonar='${repo.sonarProjectKey}', rootPath='${repo.localVcsRootPath}'")
            }
            log.info("[Settings:Repos] Final global state: " +
                "bambooPlan='${pluginSettings.state.bambooPlanKey}', dockerTag='${pluginSettings.state.dockerTagKey}', " +
                "sonar='${pluginSettings.state.sonarProjectKey}'")

            invokeLater {
                // Reload from settings to pick up values written by the orchestrator
                editedRepos.clear()
                editedRepos.addAll(pluginSettings.getRepos())
                refreshRepoTable()

                if (orchestratorResult == null) {
                    repoStatusLabel.text = "Detection timed out \u2014 Bamboo plan scan is slow. Try setting it manually via Edit."
                    repoStatusLabel.foreground = StatusColors.WARNING
                } else if (orchestratorResult.anyFilled) {
                    repoStatusLabel.text = "Filled: ${orchestratorResult.filledFields.joinToString(", ")}"
                    repoStatusLabel.foreground = StatusColors.SUCCESS
                } else {
                    repoStatusLabel.text = "No additional project keys detected"
                    repoStatusLabel.foreground = StatusColors.SECONDARY_TEXT
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
}
