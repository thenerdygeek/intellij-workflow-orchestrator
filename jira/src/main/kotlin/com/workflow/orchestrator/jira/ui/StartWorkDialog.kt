package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.ui.ComboBox
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.DefaultComboBoxModel

data class StartWorkResult(
    val sourceBranch: String,
    val branchName: String,
    val useExisting: Boolean = false,
    val selectedRepoIndex: Int = 0
)

/**
 * Dialog for "Start Work" flow.
 *
 * Dual-mode when existing branches are found:
 *   - "Use existing branch" (radio, default) with dropdown if multiple
 *   - "Create new branch" (radio) with source branch + branch name fields
 *
 * Single-mode (no existing branches): shows create flow only (same as before).
 */
class StartWorkDialog(
    private val project: Project,
    private val ticketKey: String,
    private val defaultBranchName: String,
    initialRemoteBranches: List<BitbucketBranch>,
    initialDefaultSourceBranch: String,
    private val repoDisplay: String,
    private val needsAiGeneration: Boolean,
    private val fallbackBranchName: String,
    initialExistingBranches: List<String> = emptyList(),
    private val repos: List<RepoConfig> = emptyList(),
    private val initialRepoIndex: Int = 0,
    /**
     * Fired when the user picks a different repo from the dropdown. Caller is expected to
     * refetch remote branches + linked branches for the new repo on a background thread,
     * then call [applyNewRepoData] on the EDT. When null (single-repo projects or tests),
     * the combo is still shown but no refetch is triggered.
     */
    private val onRepoChanged: ((Int) -> Unit)? = null
) : DialogWrapper(project, true) {

    private val log = Logger.getInstance(StartWorkDialog::class.java)
    // Always build the dual-mode panel when we might show existing branches for ANY repo,
    // so switching repos can toggle the "Use existing" section without re-creating the dialog.
    private val isDualMode = initialExistingBranches.isNotEmpty() || (repos.size > 1 && onRepoChanged != null)
    private val showRepoSelector = repos.size > 1
    private var selectedRepoIdx = initialRepoIndex

    // Mutable state \u2014 updated by [applyNewRepoData] when the user switches repo.
    private var remoteBranches: List<BitbucketBranch> = initialRemoteBranches
    private var existingBranches: List<String> = initialExistingBranches
    private var defaultSourceBranch: String = initialDefaultSourceBranch
    private var ignoreComboEvents = false

    // Dual-mode components
    private var useExistingRadio: JRadioButton? = null
    private var createNewRadio: JRadioButton? = null
    private var existingBranchCombo: JComboBox<String>? = null
    private var existingBranchLabel: JBLabel? = null
    private var noExistingBranchesLabel: JBLabel? = null
    private var createPanel: JPanel? = null
    private var repoLoadingLabel: JBLabel? = null

    // Create-mode components
    private var selectedSourceBranch: String = initialDefaultSourceBranch
    private val branchNameField = JBTextField(40)
    private var sourceBranchCombo: JComboBox<String>? = null
    private val loadingPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    private val loadingLabel = JBLabel("AI generating branch name\u2026")
    private val loadingIcon = JBLabel(AnimatedIcon.Default())
    private val errorLabel = JBLabel().apply {
        foreground = StatusColors.ERROR
        isVisible = false
    }

    var result: StartWorkResult? = null
        private set

    init {
        title = "Start Work \u2014 $ticketKey"
        setOKButtonText(if (isDualMode) "Start Work" else "Create Branch")
        init()

        if (!isDualMode) {
            if (needsAiGeneration) {
                log.info("[Jira:StartWork] Dialog opened with AI generation pending for $ticketKey")
                setAiLoading(true)
            } else {
                log.info("[Jira:StartWork] Dialog opened with static branch name for $ticketKey")
                branchNameField.text = defaultBranchName
            }
        } else {
            log.info("[Jira:StartWork] Dialog opened in dual-mode with ${existingBranches.size} existing branches for $ticketKey")
            branchNameField.text = defaultBranchName
            if (existingBranches.isEmpty()) {
                // Dual-mode layout is built (so repo switching can enable it later) but no
                // branches exist yet — force create-new mode until [applyNewRepoData] fills it.
                createNewRadio?.isSelected = true
                useExistingRadio?.isEnabled = false
                updateCreatePanelEnabled(true)
                if (needsAiGeneration) setAiLoading(true)
            } else {
                updateCreatePanelEnabled(false)
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val hasUncommittedChanges = checkUncommittedChanges()

        val mainPanel = JPanel()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)
        mainPanel.border = JBUI.Borders.empty(8)

        // Uncommitted changes warning
        if (hasUncommittedChanges) {
            val warningPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                val warningLabel = JBLabel(
                    "Warning: You have uncommitted changes. Commit or stash them before switching branches."
                ).apply {
                    foreground = StatusColors.WARNING
                    icon = com.intellij.icons.AllIcons.General.Warning
                }
                add(warningLabel)
            }
            mainPanel.add(warningPanel)
            mainPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
            mainPanel.add(JSeparator())
            mainPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        // Repository row — combo selector for multi-repo, static label for single
        val repoRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        repoRow.add(JBLabel("Repository:"))
        if (showRepoSelector) {
            val repoCombo = ComboBox(repos.map { it.displayLabel }.toTypedArray()).apply {
                selectedIndex = initialRepoIndex.coerceIn(0, repos.size - 1)
                addActionListener {
                    if (ignoreComboEvents) return@addActionListener
                    val newIdx = selectedIndex
                    if (newIdx == selectedRepoIdx) return@addActionListener
                    selectedRepoIdx = newIdx
                    log.info("[Jira:StartWork] Repo changed to index $newIdx (${repos.getOrNull(newIdx)?.displayLabel}) — refetching branches")
                    onRepoChanged?.invoke(newIdx)
                }
            }
            repoRow.add(repoCombo)
            repoLoadingLabel = JBLabel("").apply {
                foreground = StatusColors.SECONDARY_TEXT
                font = font.deriveFont(font.size2D - 1f)
                icon = null
                isVisible = false
            }
            repoRow.add(repoLoadingLabel)
            mainPanel.add(repoRow)
            if (onRepoChanged == null) {
                val hint = JBLabel("Branches listed are from the initially-detected repo").apply {
                    foreground = StatusColors.SECONDARY_TEXT
                    font = font.deriveFont(font.size2D - 1f)
                }
                mainPanel.add(Box.createVerticalStrut(JBUI.scale(2)))
                mainPanel.add(hint)
            }
        } else {
            repoRow.add(JBLabel(repoDisplay).apply {
                foreground = StatusColors.LINK
            })
            mainPanel.add(repoRow)
        }
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        if (isDualMode) {
            buildDualModePanel(mainPanel)
        } else {
            buildCreateOnlyPanel(mainPanel)
        }

        return mainPanel
    }

    private fun buildDualModePanel(mainPanel: JPanel) {
        val radioGroup = ButtonGroup()

        // --- Use existing branch radio ---
        useExistingRadio = JRadioButton("Use existing branch", true).apply {
            addActionListener { onRadioChanged() }
        }
        radioGroup.add(useExistingRadio)
        mainPanel.add(useExistingRadio)
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        // Existing branch selector (indented). Always build both the combo and the
        // "no linked branches" label — [applyNewRepoData] swaps visibility when the
        // user switches repo.
        val existingRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.emptyLeft(JBUI.scale(24))
        }
        existingBranchCombo = JComboBox(existingBranches.toTypedArray()).apply {
            isVisible = existingBranches.isNotEmpty()
        }
        existingRow.add(existingBranchCombo)
        noExistingBranchesLabel = JBLabel("No linked branches for this repo").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(font.size2D - 1f)
            isVisible = existingBranches.isEmpty()
        }
        existingRow.add(noExistingBranchesLabel)
        mainPanel.add(existingRow)
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // --- Create new branch radio ---
        createNewRadio = JRadioButton("Create new branch", false).apply {
            addActionListener { onRadioChanged() }
        }
        radioGroup.add(createNewRadio)
        mainPanel.add(createNewRadio)
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        // Create panel (indented, contains source + branch name)
        createPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyLeft(JBUI.scale(24))
        }
        addCreateFields(createPanel!!)
        mainPanel.add(createPanel)

        mainPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        val comment = JBLabel("Branch will be checked out locally").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(font.size2D - 1f)
            border = JBUI.Borders.emptyLeft(JBUI.scale(8))
        }
        mainPanel.add(comment)
    }

    private fun buildCreateOnlyPanel(mainPanel: JPanel) {
        addCreateFields(mainPanel)

        mainPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        val branchComment = JBLabel("Branch will be created on Bitbucket and checked out locally").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(font.size2D - 1f)
            border = JBUI.Borders.emptyLeft(JBUI.scale(8))
        }
        mainPanel.add(branchComment)
    }

    private fun addCreateFields(panel: JPanel) {
        // Source branch row
        val sourceRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        sourceRow.add(JBLabel("Source branch:"))
        val branchNames = remoteBranches.map { it.displayId }
        sourceBranchCombo = JComboBox(branchNames.toTypedArray()).apply {
            selectedItem = defaultSourceBranch
            addActionListener {
                if (ignoreComboEvents) return@addActionListener
                selectedSourceBranch = selectedItem as? String ?: defaultSourceBranch
            }
        }
        sourceRow.add(sourceBranchCombo)
        panel.add(sourceRow)
        panel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val sourceComment = JBLabel("Branch to create from").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(font.size2D - 1f)
            border = JBUI.Borders.emptyLeft(JBUI.scale(8))
        }
        panel.add(sourceComment)
        panel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Branch name row
        val branchRow = JPanel(BorderLayout(JBUI.scale(8), 0))
        branchRow.add(JBLabel("New branch name:"), BorderLayout.WEST)
        branchRow.add(branchNameField, BorderLayout.CENTER)
        panel.add(branchRow)

        // Loading indicator
        loadingPanel.apply {
            isOpaque = false
            add(loadingIcon)
            add(loadingLabel)
            isVisible = false
        }
        panel.add(loadingPanel)

        // Error label
        errorLabel.border = JBUI.Borders.emptyLeft(JBUI.scale(8))
        panel.add(errorLabel)
    }

    private fun onRadioChanged() {
        val useExisting = useExistingRadio?.isSelected == true
        updateCreatePanelEnabled(!useExisting)

        // Trigger AI generation when switching to "Create new" if needed
        if (!useExisting && needsAiGeneration && branchNameField.text.isBlank()) {
            setAiLoading(true)
        }
    }

    private fun updateCreatePanelEnabled(enabled: Boolean) {
        branchNameField.isEnabled = enabled
        existingBranchCombo?.isEnabled = !enabled
        existingBranchLabel?.isEnabled = !enabled

        // Enable/disable all components in createPanel recursively
        createPanel?.let { setEnabledRecursive(it, enabled) }
    }

    private fun setEnabledRecursive(component: java.awt.Component, enabled: Boolean) {
        component.isEnabled = enabled
        if (component is java.awt.Container) {
            component.components.forEach { setEnabledRecursive(it, enabled) }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (isDualMode && useExistingRadio?.isSelected == true) {
            if (existingBranches.isEmpty()) {
                return ValidationInfo("No linked branches for this repo — pick Create new branch instead")
            }
            // Using existing — no validation needed beyond having a selection
            return null
        }

        val name = branchNameField.text.orEmpty()
        if (name.isBlank()) {
            return ValidationInfo("Branch name cannot be empty", branchNameField)
        }
        if (name.contains(" ")) {
            return ValidationInfo("Branch name cannot contain spaces", branchNameField)
        }
        if (selectedSourceBranch.isBlank()) {
            return ValidationInfo("Select a source branch")
        }
        return null
    }

    override fun doOKAction() {
        if (isDualMode && useExistingRadio?.isSelected == true) {
            val selectedBranch = existingBranchCombo?.selectedItem as? String
                ?: existingBranches.firstOrNull()
                ?: return

            result = StartWorkResult(
                sourceBranch = "",
                branchName = selectedBranch,
                useExisting = true,
                selectedRepoIndex = selectedRepoIdx
            )
            log.info("[Jira:StartWork] User chose existing branch: '$selectedBranch' on repo index $selectedRepoIdx")
        } else {
            result = StartWorkResult(
                sourceBranch = selectedSourceBranch,
                branchName = branchNameField.text.orEmpty().trim(),
                useExisting = false,
                selectedRepoIndex = selectedRepoIdx
            )
            log.info("[Jira:StartWork] User confirmed new branch: '${result?.branchName}' from '${result?.sourceBranch}' on repo index $selectedRepoIdx")
        }
        super.doOKAction()
    }

    fun setAiLoading(loading: Boolean) {
        log.info("[Jira:StartWork] setAiLoading($loading)")
        branchNameField.isEnabled = !loading
        if (loading) {
            branchNameField.text = ""
            branchNameField.emptyText.setText("Waiting for AI\u2026")
        }
        loadingPanel.isVisible = loading
        // Only disable OK when loading AND create-new is the active mode
        if (!isDualMode || createNewRadio?.isSelected == true) {
            isOKActionEnabled = !loading
        }
    }

    fun setAiResult(branchName: String) {
        log.info("[Jira:StartWork] AI generated branch name: '$branchName'")
        branchNameField.text = branchName
        branchNameField.isEnabled = createNewRadio?.isSelected != false
        loadingPanel.isVisible = false
        errorLabel.isVisible = false
        isOKActionEnabled = true
    }

    fun setAiFailed(errorMessage: String) {
        log.warn("[Jira:StartWork] AI generation failed: $errorMessage — falling back to '$fallbackBranchName'")
        branchNameField.text = fallbackBranchName
        branchNameField.isEnabled = createNewRadio?.isSelected != false
        loadingPanel.isVisible = false
        errorLabel.text = "AI branch generation failed: $errorMessage. Using fallback name."
        errorLabel.isVisible = true
        isOKActionEnabled = true
    }

    /**
     * Toggle the "refreshing branches for new repo" state. OK button is disabled + the
     * source-branch and existing-branch combos are disabled while loading, so the user
     * can't click OK against stale-for-the-new-repo data.
     */
    fun setBranchesLoading(loading: Boolean) {
        log.info("[Jira:StartWork] setBranchesLoading($loading)")
        repoLoadingLabel?.apply {
            text = if (loading) "Refreshing branches…" else ""
            isVisible = loading
        }
        sourceBranchCombo?.isEnabled = !loading
        existingBranchCombo?.isEnabled = !loading
        useExistingRadio?.isEnabled = !loading && existingBranches.isNotEmpty()
        createNewRadio?.isEnabled = !loading
        isOKActionEnabled = !loading
    }

    /**
     * Swap the dialog's branch data for a newly-picked repo. Called by the caller after a
     * successful background refetch. Rebuilds the source-branch combo items, replaces the
     * linked-branch list, and toggles the "Use existing branch" radio based on whether any
     * linked branches exist for the new repo.
     *
     * Everything but the ticket-level state (branch name, AI result) is replaced — the
     * branch name stays so the user's edits aren't lost when they hop between repos.
     */
    fun applyNewRepoData(
        newRemoteBranches: List<BitbucketBranch>,
        newExistingBranches: List<String>,
        newDefaultSourceBranch: String
    ) {
        log.info(
            "[Jira:StartWork] applyNewRepoData: ${newRemoteBranches.size} remote branches, " +
                "${newExistingBranches.size} linked branches, defaultSource='$newDefaultSourceBranch'"
        )
        remoteBranches = newRemoteBranches
        existingBranches = newExistingBranches
        defaultSourceBranch = newDefaultSourceBranch
        selectedSourceBranch = newDefaultSourceBranch

        // Rebuild combos while suppressing their actionListeners so we don't overwrite
        // selectedSourceBranch/selectedRepoIdx mid-rebuild.
        ignoreComboEvents = true
        try {
            sourceBranchCombo?.let { combo ->
                combo.model = DefaultComboBoxModel(newRemoteBranches.map { it.displayId }.toTypedArray())
                combo.selectedItem = newDefaultSourceBranch
            }
            existingBranchCombo?.let { combo ->
                combo.model = DefaultComboBoxModel(newExistingBranches.toTypedArray())
                combo.isVisible = newExistingBranches.isNotEmpty()
                if (newExistingBranches.isNotEmpty()) combo.selectedIndex = 0
            }
            noExistingBranchesLabel?.isVisible = newExistingBranches.isEmpty()
        } finally {
            ignoreComboEvents = false
        }

        if (isDualMode) {
            if (newExistingBranches.isEmpty()) {
                // No linked branches on the new repo — force create-new mode.
                useExistingRadio?.isEnabled = false
                createNewRadio?.isSelected = true
                updateCreatePanelEnabled(true)
            } else {
                useExistingRadio?.isEnabled = true
                // Preserve the user's current radio choice; if they had "use existing" selected
                // before switching, it re-populates with the new repo's branches.
            }
        }

        setBranchesLoading(false)
    }

    private fun checkUncommittedChanges(): Boolean {
        return try {
            val changeListManager = ChangeListManager.getInstance(project)
            changeListManager.allChanges.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
