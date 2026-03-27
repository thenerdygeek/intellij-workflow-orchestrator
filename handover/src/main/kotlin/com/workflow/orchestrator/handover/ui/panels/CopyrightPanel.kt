package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.handover.model.CopyrightFileEntry
import com.workflow.orchestrator.handover.model.CopyrightStatus
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

class CopyrightPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<CopyrightFileEntry>()
    private val fileList = JBList(listModel)
    private val fixAllButton = JButton("Fix All")
    private val rescanButton = JButton("Rescan")
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val emptyLabel = JBLabel("No files to check.").apply {
        foreground = StatusColors.SECONDARY_TEXT
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    init {
        border = JBUI.Borders.empty(8)

        val headerLabel = JBLabel("COPYRIGHT HEADER STATUS").apply {
            font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(12).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.emptyLeft(8)
        }
        val header = JPanel(BorderLayout()).apply {
            add(headerLabel, BorderLayout.CENTER)
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(StatusColors.BORDER, 0, 0, 1, 0),
                BorderFactory.createCompoundBorder(
                    JBUI.Borders.customLine(StatusColors.LINK, 0, 2, 0, 0),
                    JBUI.Borders.empty(6, 0, 6, 0)
                )
            )
            isOpaque = false
        }

        fixAllButton.isEnabled = false
        fixAllButton.toolTipText = "Coming soon"
        rescanButton.isEnabled = false
        rescanButton.toolTipText = "Coming soon"

        val buttonPanel = JPanel().apply {
            add(fixAllButton)
            add(rescanButton)
        }

        cardPanel.add(JBScrollPane(fileList), "list")
        cardPanel.add(emptyLabel, "empty")
        cardLayout.show(cardPanel, "empty")

        add(header, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)
    }

    fun setEntries(entries: List<CopyrightFileEntry>) {
        listModel.clear()
        entries.forEach { listModel.addElement(it) }
        cardLayout.show(cardPanel, if (entries.isEmpty()) "empty" else "list")
    }
}
