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
import com.workflow.orchestrator.core.ai.BranchNameAiGenerator
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.*

data class StartWorkResult(
    val sourceBranch: String,
    val branchName: String
)

/**
 * Dialog for "Start Work" flow — lets user pick source branch, edit branch name,
 * and shows Cody-generated branch names with loading/fallback states.
 */
class StartWorkDialog(
    private val project: Project,
    private val ticketKey: String,
    private val defaultBranchName: String,
    private val remoteBranches: List<BitbucketBranch>,
    private val defaultSourceBranch: String,
    private val repoDisplay: String,
    private val needsCodyGeneration: Boolean,
    private val fallbackBranchName: String
) : DialogWrapper(project, true) {

    private val log = Logger.getInstance(StartWorkDialog::class.java)

    private var selectedSourceBranch: String = defaultSourceBranch
    private val branchNameField = JBTextField(40)
    private val loadingPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    private val loadingLabel = JBLabel("Cody generating branch name\u2026")
    private val loadingIcon = JBLabel(AnimatedIcon.Default())
    private val errorLabel = JBLabel().apply {
        foreground = JBColor(Color(0xCC, 0x33, 0x33), Color(0xFF, 0x66, 0x66))
        isVisible = false
    }

    var result: StartWorkResult? = null
        private set

    init {
        title = "Start Work \u2014 $ticketKey"
        setOKButtonText("Create Branch")
        init()

        if (needsCodyGeneration) {
            log.info("[Jira:StartWork] Dialog opened with Cody generation pending for $ticketKey")
            setCodyLoading(true)
        } else {
            log.info("[Jira:StartWork] Dialog opened with static branch name for $ticketKey")
            branchNameField.text = defaultBranchName
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
                    foreground = JBColor(Color(0xCC, 0x66, 0x00), Color(0xFF, 0xAA, 0x33))
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
            foreground = JBColor(0x0969DA, 0x58A6FF)
        })
        mainPanel.add(repoRow)
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(8)))

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
        mainPanel.add(sourceRow)
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val sourceComment = JBLabel("Branch to create from").apply {
            foreground = JBColor(0x656D76, 0x8B949E)
            font = font.deriveFont(font.size2D - 1f)
            border = JBUI.Borders.emptyLeft(JBUI.scale(8))
        }
        mainPanel.add(sourceComment)
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Branch name row
        val branchRow = JPanel(BorderLayout(JBUI.scale(8), 0))
        branchRow.add(JBLabel("New branch name:"), BorderLayout.WEST)
        branchRow.add(branchNameField, BorderLayout.CENTER)
        mainPanel.add(branchRow)

        // Loading indicator (below branch name)
        loadingPanel.apply {
            isOpaque = false
            add(loadingIcon)
            add(loadingLabel)
            isVisible = false
        }
        mainPanel.add(loadingPanel)

        // Error label (below branch name)
        errorLabel.border = JBUI.Borders.emptyLeft(JBUI.scale(8))
        mainPanel.add(errorLabel)

        mainPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        val branchComment = JBLabel("Branch will be created on Bitbucket and checked out locally").apply {
            foreground = JBColor(0x656D76, 0x8B949E)
            font = font.deriveFont(font.size2D - 1f)
            border = JBUI.Borders.emptyLeft(JBUI.scale(8))
        }
        mainPanel.add(branchComment)

        return mainPanel
    }

    override fun doValidate(): ValidationInfo? {
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
        result = StartWorkResult(
            sourceBranch = selectedSourceBranch,
            branchName = branchNameField.text.orEmpty().trim()
        )
        log.info("[Jira:StartWork] User confirmed: branch='${result?.branchName}' from='${result?.sourceBranch}'")
        super.doOKAction()
    }

    /**
     * Show loading state: disable branch name field, show spinner.
     */
    fun setCodyLoading(loading: Boolean) {
        log.info("[Jira:StartWork] setCodyLoading($loading)")
        branchNameField.isEnabled = !loading
        if (loading) {
            branchNameField.text = ""
            branchNameField.emptyText.setText("Waiting for Cody\u2026")
        }
        loadingPanel.isVisible = loading
        isOKActionEnabled = !loading
    }

    /**
     * Called when Cody successfully generates a branch name.
     */
    fun setCodyResult(branchName: String) {
        log.info("[Jira:StartWork] Cody generated branch name: '$branchName'")
        branchNameField.text = branchName
        branchNameField.isEnabled = true
        loadingPanel.isVisible = false
        errorLabel.isVisible = false
        isOKActionEnabled = true
    }

    /**
     * Called when Cody generation fails. Falls back to summary-based name.
     */
    fun setCodyFailed(errorMessage: String) {
        log.warn("[Jira:StartWork] Cody generation failed: $errorMessage — falling back to '$fallbackBranchName'")
        branchNameField.text = fallbackBranchName
        branchNameField.isEnabled = true
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
