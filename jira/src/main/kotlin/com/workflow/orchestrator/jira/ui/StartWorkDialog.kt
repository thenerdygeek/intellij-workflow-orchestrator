package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import java.awt.Color
import javax.swing.JComponent

data class StartWorkResult(
    val sourceBranch: String,
    val branchName: String
)

class StartWorkDialog(
    private val project: Project,
    private val ticketKey: String,
    private val defaultBranchName: String,
    private val remoteBranches: List<BitbucketBranch>,
    private val defaultSourceBranch: String
) : DialogWrapper(project, true) {

    private var branchName: String = defaultBranchName
    private var selectedSourceBranch: String = defaultSourceBranch

    var result: StartWorkResult? = null
        private set

    init {
        title = "Start Work — $ticketKey"
        setOKButtonText("Create Branch")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val hasUncommittedChanges = checkUncommittedChanges()

        return panel {
            if (hasUncommittedChanges) {
                row {
                    val warningLabel = JBLabel(
                        "Warning: You have uncommitted changes. Commit or stash them before switching branches."
                    ).apply {
                        foreground = JBColor(Color(0xCC, 0x66, 0x00), Color(0xFF, 0xAA, 0x33))
                        icon = com.intellij.icons.AllIcons.General.Warning
                    }
                    cell(warningLabel)
                }
                separator()
            }

            row("Source branch:") {
                val branchNames = remoteBranches.map { it.displayId }
                comboBox(branchNames)
                    .applyToComponent {
                        selectedItem = defaultSourceBranch
                        addActionListener {
                            selectedSourceBranch = selectedItem as? String ?: defaultSourceBranch
                        }
                    }
                    .comment("Branch to create from")
            }
            row("New branch name:") {
                textField()
                    .applyToComponent { columns = 40 }
                    .bindText(::branchName)
                    .comment("Branch will be created on Bitbucket and checked out locally")
            }
        }.apply {
            border = JBUI.Borders.empty(8)
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (branchName.isBlank()) {
            return ValidationInfo("Branch name cannot be empty")
        }
        if (branchName.contains(" ")) {
            return ValidationInfo("Branch name cannot contain spaces")
        }
        if (selectedSourceBranch.isBlank()) {
            return ValidationInfo("Select a source branch")
        }
        return null
    }

    override fun doOKAction() {
        result = StartWorkResult(
            sourceBranch = selectedSourceBranch,
            branchName = branchName
        )
        super.doOKAction()
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
