package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.handover.model.CopyrightFileEntry
import com.workflow.orchestrator.handover.model.CopyrightStatus
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel

class CopyrightPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<CopyrightFileEntry>()
    private val fileList = JBList(listModel)
    private val fixAllButton = JButton("Fix All")
    private val rescanButton = JButton("Rescan")

    init {
        border = JBUI.Borders.empty(8)

        val header = JBLabel("Copyright Header Status").apply {
            font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
        }

        val buttonPanel = JPanel().apply {
            add(fixAllButton)
            add(rescanButton)
        }

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(fileList), BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)
    }

    fun setEntries(entries: List<CopyrightFileEntry>) {
        listModel.clear()
        entries.forEach { listModel.addElement(it) }
    }
}
