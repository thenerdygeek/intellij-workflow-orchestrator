package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.*

data class StartWorkResult(
    val sourceBranch: String,
    val branchName: String,
    val useExisting: Boolean = false
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
    private val remoteBranches: List<BitbucketBranch>,
    private val defaultSourceBranch: String,
    private val repoDisplay: String,
    private val needsCodyGeneration: Boolean,
    private val fallbackBranchName: String,
    private val existingBranches: List<String> = emptyList()
) : DialogWrapper(project, true) {

    private val log = Logger.getInstance(StartWorkDialog::class.java)
    private val isDualMode = existingBranches.isNotEmpty()

    // Dual-mode components
    private var useExistingRadio: JRadioButton? = null
    private var createNewRadio: JRadioButton? = null
    private var existingBranchCombo: JComboBox<String>? = null
    private var existingBranchLabel: JBLabel? = null
    private var createPanel: JPanel? = null

    // Create-mode components
    private var selectedSourceBranch: String = defaultSourceBranch
    private val branchNameField = JBTextField(40)
    private val loadingPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    private val loadingLabel = JBLabel("Cody generating branch name\u2026")
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
            if (needsCodyGeneration) {
                log.info("[Jira:StartWork] Dialog opened with Cody generation pending for $ticketKey")
                setCodyLoading(true)
            } else {
                log.info("[Jira:StartWork] Dialog opened with static branch name for $ticketKey")
                branchNameField.text = defaultBranchName
            }
        } else {
            log.info("[Jira:StartWork] Dialog opened in dual-mode with ${existingBranches.size} existing branches for $ticketKey")
            // Default: use existing selected, create fields disabled
            branchNameField.text = defaultBranchName
            updateCreatePanelEnabled(false)
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

        // Repository row
        val repoRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        repoRow.add(JBLabel("Repository:"))
        repoRow.add(JBLabel(repoDisplay).apply {
            foreground = StatusColors.LINK
        })
        mainPanel.add(repoRow)
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

        // Existing branch selector (indented)
        val existingRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.emptyLeft(JBUI.scale(24))
        }
        if (existingBranches.size == 1) {
            existingBranchLabel = JBLabel(existingBranches.first()).apply {
                foreground = StatusColors.LINK
            }
            existingRow.add(existingBranchLabel)
        } else {
            existingBranchCombo = JComboBox(existingBranches.toTypedArray())
            existingRow.add(existingBranchCombo)
        }
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
        val comboBox = JComboBox(branchNames.toTypedArray()).apply {
            selectedItem = defaultSourceBranch
            addActionListener {
                selectedSourceBranch = selectedItem as? String ?: defaultSourceBranch
            }
        }
        sourceRow.add(comboBox)
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

        // Trigger Cody generation when switching to "Create new" if needed
        if (!useExisting && needsCodyGeneration && branchNameField.text.isBlank()) {
            setCodyLoading(true)
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
                useExisting = true
            )
            log.info("[Jira:StartWork] User chose existing branch: '$selectedBranch'")
        } else {
            result = StartWorkResult(
                sourceBranch = selectedSourceBranch,
                branchName = branchNameField.text.orEmpty().trim(),
                useExisting = false
            )
            log.info("[Jira:StartWork] User confirmed new branch: '${result?.branchName}' from '${result?.sourceBranch}'")
        }
        super.doOKAction()
    }

    fun setCodyLoading(loading: Boolean) {
        log.info("[Jira:StartWork] setCodyLoading($loading)")
        branchNameField.isEnabled = !loading
        if (loading) {
            branchNameField.text = ""
            branchNameField.emptyText.setText("Waiting for Cody\u2026")
        }
        loadingPanel.isVisible = loading
        // Only disable OK when loading AND create-new is the active mode
        if (!isDualMode || createNewRadio?.isSelected == true) {
            isOKActionEnabled = !loading
        }
    }

    fun setCodyResult(branchName: String) {
        log.info("[Jira:StartWork] Cody generated branch name: '$branchName'")
        branchNameField.text = branchName
        branchNameField.isEnabled = createNewRadio?.isSelected != false
        loadingPanel.isVisible = false
        errorLabel.isVisible = false
        isOKActionEnabled = true
    }

    fun setCodyFailed(errorMessage: String) {
        log.warn("[Jira:StartWork] Cody generation failed: $errorMessage — falling back to '$fallbackBranchName'")
        branchNameField.text = fallbackBranchName
        branchNameField.isEnabled = createNewRadio?.isSelected != false
        loadingPanel.isVisible = false
        errorLabel.text = "Cody branch generation failed: $errorMessage. Using fallback name."
        errorLabel.isVisible = true
        isOKActionEnabled = true
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
