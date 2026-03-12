package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.sonar.model.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class IssueListPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<MappedIssue>()
    private val issueList = JBList(listModel).apply {
        cellRenderer = IssueListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val filterCombo = JComboBox(arrayOf("All", "Bug", "Vulnerability", "Code Smell", "Hotspot"))
    private val severityCombo = JComboBox(arrayOf("All", "Blocker", "Critical", "Major", "Minor", "Info"))
    private val countLabel = JBLabel("0 issues")

    private var allIssues: List<MappedIssue> = emptyList()

    init {
        border = JBUI.Borders.empty(8)

        // Filter toolbar
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JBLabel("Type:"))
            add(filterCombo)
            add(JBLabel("Severity:"))
            add(severityCombo)
            add(countLabel)
        }
        add(filterPanel, BorderLayout.NORTH)
        add(JBScrollPane(issueList), BorderLayout.CENTER)

        filterCombo.addActionListener { applyFilters() }
        severityCombo.addActionListener { applyFilters() }

        // Double-click navigates to file
        issueList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val issue = issueList.selectedValue ?: return
                    navigateToIssue(issue)
                }
            }
        })
    }

    fun update(issues: List<MappedIssue>) {
        allIssues = issues
        applyFilters()
    }

    private fun applyFilters() {
        var filtered = allIssues

        when (filterCombo.selectedIndex) {
            1 -> filtered = filtered.filter { it.type == IssueType.BUG }
            2 -> filtered = filtered.filter { it.type == IssueType.VULNERABILITY }
            3 -> filtered = filtered.filter { it.type == IssueType.CODE_SMELL }
            4 -> filtered = filtered.filter { it.type == IssueType.SECURITY_HOTSPOT }
        }

        when (severityCombo.selectedIndex) {
            1 -> filtered = filtered.filter { it.severity == IssueSeverity.BLOCKER }
            2 -> filtered = filtered.filter { it.severity == IssueSeverity.CRITICAL }
            3 -> filtered = filtered.filter { it.severity == IssueSeverity.MAJOR }
            4 -> filtered = filtered.filter { it.severity == IssueSeverity.MINOR }
            5 -> filtered = filtered.filter { it.severity == IssueSeverity.INFO }
        }

        listModel.clear()
        filtered.sortedBy { it.severity.ordinal }.forEach { listModel.addElement(it) }
        countLabel.text = "${filtered.size} issue(s)"
    }

    private fun navigateToIssue(issue: MappedIssue) {
        val basePath = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, issue.filePath).path) ?: return
        OpenFileDescriptor(project, vf, issue.startLine - 1, issue.startOffset).navigate(true)
    }
}

private class IssueListCellRenderer : ListCellRenderer<MappedIssue> {
    override fun getListCellRendererComponent(
        list: JList<out MappedIssue>, value: MappedIssue,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val label = JBLabel()
        val color = when (value.severity) {
            IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> "#ff4444"
            IssueSeverity.MAJOR -> "#e68a00"
            IssueSeverity.MINOR -> "#ffaa00"
            IssueSeverity.INFO -> "#888888"
        }
        val typeStr = value.type.name.replace("_", " ")
        val fileName = java.io.File(value.filePath).name
        label.text = "<html><font color='$color'>\u25CF</font> $typeStr " +
            "<font color='$color'>${value.severity}</font> ${value.message} — $fileName:${value.startLine}</html>"
        label.border = JBUI.Borders.empty(4, 8)
        if (isSelected) {
            label.background = list.selectionBackground
            label.foreground = list.selectionForeground
            label.isOpaque = true
        }
        return label
    }
}
