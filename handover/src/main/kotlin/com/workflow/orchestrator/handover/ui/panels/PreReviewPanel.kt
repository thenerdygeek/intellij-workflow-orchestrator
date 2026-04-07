package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.handover.model.ReviewFinding
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel

class PreReviewPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<ReviewFinding>()
    private val findingsList = JBList(listModel)
    private val analyzeButton = JButton("Analyze with AI").apply {
        isEnabled = false
        toolTipText = "Coming soon"
    }
    private val statusLabel = JBLabel("Click Analyze to run AI pre-review").apply {
        foreground = StatusColors.SECONDARY_TEXT
    }

    init {
        border = JBUI.Borders.empty(8)

        add(handoverPanelHeader("AI PRE-REVIEW", analyzeButton), BorderLayout.NORTH)
        add(JBScrollPane(findingsList), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    fun setFindings(findings: List<ReviewFinding>) {
        listModel.clear()
        findings.forEach { listModel.addElement(it) }
        statusLabel.text = "${findings.size} finding(s)"
    }
}
