package com.workflow.orchestrator.jira.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * "Currently Working On" section at the top of the Sprint tab left panel.
 * Shows the active ticket with branch name, status, and quick stats.
 */
class CurrentWorkSection(
    private val project: Project,
    private val onTicketClicked: ((String) -> Unit)? = null
) : JPanel(BorderLayout()) {

    private val settings get() = PluginSettings.getInstance(project)

    private val ticketKeyLabel = JBLabel("").apply {
        font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(13).toFloat())
        foreground = StatusColors.LINK
    }
    private val summaryLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
    }
    private val branchLabel = JBLabel("").apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(JBUI.scale(10).toFloat())
    }
    private val targetArrowLabel = JBLabel("").apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(JBUI.scale(10).toFloat())
    }
    private val editTargetLabel = JBLabel(AllIcons.Actions.Edit).apply {
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        toolTipText = "Change target branch"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statusBadge = JBLabel("")
    private val emptyPanel = JPanel(BorderLayout())

    companion object {
        private val EMPTY_BG = StatusColors.CARD_BG
    }

    init {
        buildEmptyState()
    }

    fun refresh() {
        val ticketId = settings.state.activeTicketId.orEmpty()
        val ticketSummary = settings.state.activeTicketSummary.orEmpty()

        if (ticketId.isBlank()) {
            buildEmptyState()
        } else {
            buildActiveState(ticketId, ticketSummary)
        }
        revalidate()
        repaint()
    }

    private fun buildActiveState(ticketId: String, summary: String) {
        removeAll()
        background = StatusColors.SUCCESS_BG
        border = JBUI.Borders.customLine(StatusColors.SUCCESS, 0, 0, 1, 0)

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10)
        }

        // Section header
        val headerLabel = JBLabel("Currently Working On").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(9).toFloat())
        }
        inner.add(headerLabel)
        inner.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))

        // Ticket key + summary
        ticketKeyLabel.text = ticketId
        inner.add(ticketKeyLabel)
        summaryLabel.text = summary
        inner.add(summaryLabel)
        inner.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))

        // Branch + status row
        val metaRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
        }

        metaRow.add(branchLabel)
        metaRow.add(targetArrowLabel)
        metaRow.add(editTargetLabel)
        inner.add(metaRow)

        editTargetLabel.mouseListeners.forEach { editTargetLabel.removeMouseListener(it) }
        editTargetLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                showBranchPicker()
            }
        })

        scope.launch {
            val resolver = RepoContextResolver.getInstance(project)
            val repoConfig = resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
            val repos = GitRepositoryManager.getInstance(project).repositories
            val targetRepo = repos.find { it.root.path == repoConfig?.localVcsRootPath } ?: repos.firstOrNull()
            val currentBranch = targetRepo?.currentBranchName ?: ""
            val targetBranch = targetRepo?.let {
                DefaultBranchResolver.getInstance(project).resolve(it)
            } ?: ""

            invokeLater {
                if (currentBranch.isNotBlank()) {
                    branchLabel.text = currentBranch
                    branchLabel.icon = AllIcons.Vcs.Branch
                }
                if (targetBranch.isNotBlank()) {
                    targetArrowLabel.text = "→ $targetBranch"
                }
                revalidate()
                repaint()
            }
        }
        add(inner, BorderLayout.CENTER)

        // Click to select ticket in the list
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        mouseListeners.forEach { removeMouseListener(it) }
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                onTicketClicked?.invoke(ticketId)
            }
        })
    }

    private fun showBranchPicker() {
        val repos = GitRepositoryManager.getInstance(project).repositories
        val repo = repos.firstOrNull() ?: return
        val branches = repo.branches.remoteBranches
            .map { it.nameForRemoteOperations }
            .filter { it != "HEAD" }
            .sorted()

        val list = com.intellij.ui.components.JBList(branches)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION

        val popup = JBPopupFactory.getInstance()
            .createListPopupBuilder(list)
            .setTitle("Select Target Branch")
            .setFilterAlwaysVisible(true)
            .setItemChoosenCallback {
                val selected = list.selectedValue as? String ?: return@setItemChoosenCallback
                val resolver = DefaultBranchResolver.getInstance(project)
                val repoPath = repo.root.path
                resolver.setOverride(repoPath, repo.currentBranchName ?: "", selected)
                targetArrowLabel.text = "→ $selected"
                revalidate()
                repaint()
            }
            .createPopup()

        popup.showUnderneathOf(editTargetLabel)
    }

    private fun buildEmptyState() {
        removeAll()
        background = EMPTY_BG
        border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10)
        }

        inner.add(JBLabel("Currently Working On").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(9).toFloat())
        })
        inner.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        inner.add(JBLabel("No active ticket").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(JBUI.scale(11).toFloat())
        })
        inner.add(JBLabel("Select a ticket and click Start Work").apply {
            foreground = StatusColors.LINK
            font = font.deriveFont(JBUI.scale(10).toFloat())
        })

        add(inner, BorderLayout.CENTER)
    }
}
