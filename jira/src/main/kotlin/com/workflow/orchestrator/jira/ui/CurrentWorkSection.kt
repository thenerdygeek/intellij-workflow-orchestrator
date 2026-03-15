package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.settings.PluginSettings
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JPanel

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
        foreground = JBColor(0x0969DA, 0x58A6FF)
    }
    private val summaryLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
    }
    private val branchLabel = JBLabel("").apply {
        foreground = JBColor(0x656D76, 0x8B949E)
        font = font.deriveFont(JBUI.scale(10).toFloat())
    }
    private val statusBadge = JBLabel("")
    private val emptyPanel = JPanel(BorderLayout())

    companion object {
        private val GREEN_BG = JBColor(Color(0xE8, 0xF5, 0xE9), Color(0x1a, 0x3d, 0x1a))
        private val GREEN_BORDER = JBColor(Color(0x66, 0xBB, 0x6A), Color(0xa6, 0xe3, 0xa1))
        private val EMPTY_BG = JBColor(Color(0xF5, 0xF5, 0xF5), Color(0x25, 0x27, 0x2e))
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
        background = GREEN_BG
        border = JBUI.Borders.customLine(GREEN_BORDER, 0, 0, 1, 0)

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10)
        }

        // Section header
        val headerLabel = JBLabel("Currently Working On").apply {
            foreground = JBColor(0x656D76, 0x6c7086)
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

        val repos = GitRepositoryManager.getInstance(project).repositories
        val currentBranch = repos.firstOrNull()?.currentBranchName ?: ""
        if (currentBranch.isNotBlank()) {
            branchLabel.text = "🔀 $currentBranch"
            metaRow.add(branchLabel)
        }

        inner.add(metaRow)
        add(inner, BorderLayout.CENTER)

        // Click to select ticket in the list
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                onTicketClicked?.invoke(ticketId)
            }
        })
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
            foreground = JBColor(0x656D76, 0x6c7086)
            font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(9).toFloat())
        })
        inner.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        inner.add(JBLabel("No active ticket").apply {
            foreground = JBColor(0x999999, 0x585b70)
            font = font.deriveFont(JBUI.scale(11).toFloat())
        })
        inner.add(JBLabel("Select a ticket and click Start Work").apply {
            foreground = JBColor(0x0969DA, 0x89b4fa)
            font = font.deriveFont(JBUI.scale(10).toFloat())
        })

        add(inner, BorderLayout.CENTER)
    }
}
