package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ai.TextGenerationService
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.sonar.model.*
import kotlinx.coroutines.*
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val emptyLabel = JBLabel("No issues found. Click Refresh to update.").apply {
        foreground = com.intellij.util.ui.JBUI.CurrentTheme.Label.disabledForeground()
        horizontalAlignment = javax.swing.SwingConstants.CENTER
        verticalAlignment = javax.swing.SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    private val filteredEmptyLabel = JBLabel("No issues match the current filters.").apply {
        foreground = com.intellij.util.ui.JBUI.CurrentTheme.Label.disabledForeground()
        horizontalAlignment = javax.swing.SwingConstants.CENTER
        verticalAlignment = javax.swing.SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    private val scrollPane = JBScrollPane(issueList)

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
        add(scrollPane, BorderLayout.CENTER)

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

        // Right-click context menu
        issueList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = showContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = showContextMenu(e)

            private fun showContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val index = issueList.locationToIndex(e.point) ?: return
                issueList.selectedIndex = index
                val issue = issueList.selectedValue ?: return

                val codyService = TextGenerationService.getInstance()

                val menu = JPopupMenu()
                menu.add(JMenuItem("Navigate to Issue").apply {
                    addActionListener { navigateToIssue(issue) }
                })
                menu.add(JMenuItem("Fix with Cody").apply {
                    isEnabled = codyService != null
                    toolTipText = if (codyService == null) "Cody agent is not running" else null
                    addActionListener { fixWithCody(issue, codyService!!) }
                })
                menu.show(issueList, e.x, e.y)
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

        // Update count label — show "Showing X of Y" when filters are active
        val filtersActive = filterCombo.selectedIndex != 0 || severityCombo.selectedIndex != 0
        countLabel.text = if (filtersActive && allIssues.isNotEmpty()) {
            "Showing ${filtered.size} of ${allIssues.size} issues"
        } else {
            "${filtered.size} issue(s)"
        }

        // Show empty state or list
        remove(scrollPane)
        remove(emptyLabel)
        remove(filteredEmptyLabel)
        if (filtered.isEmpty()) {
            if (filtersActive && allIssues.isNotEmpty()) {
                add(filteredEmptyLabel, BorderLayout.CENTER)
            } else {
                add(emptyLabel, BorderLayout.CENTER)
            }
        } else {
            add(scrollPane, BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    private fun navigateToIssue(issue: MappedIssue) {
        val basePath = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, issue.filePath).path) ?: return
        OpenFileDescriptor(project, vf, issue.startLine - 1, issue.startOffset).navigate(true)
    }

    private fun fixWithCody(issue: MappedIssue, codyService: TextGenerationService) {
        val basePath = project.basePath ?: return
        val absolutePath = java.io.File(basePath, issue.filePath).absolutePath

        // Navigate to the issue location first
        navigateToIssue(issue)

        val prompt = "Fix this SonarQube issue: [${issue.rule}] ${issue.message} at line ${issue.startLine} in ${issue.filePath}"

        scope.launch {
            val result = codyService.generateText(
                project = project,
                prompt = prompt,
                contextFilePaths = listOf(absolutePath)
            )
            withContext(Dispatchers.Main) {
                val notificationService = WorkflowNotificationService.getInstance(project)
                if (result != null) {
                    notificationService.notifyInfo(
                        WorkflowNotificationService.GROUP_QUALITY,
                        "Cody Fix Suggestion",
                        result
                    )
                } else {
                    notificationService.notifyError(
                        WorkflowNotificationService.GROUP_QUALITY,
                        "Fix with Cody Failed",
                        "Cody could not generate a fix suggestion for ${issue.rule}"
                    )
                }
            }
        }
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
        label.toolTipText = "[${value.rule}] ${value.message} — ${value.filePath}:${value.startLine}"
        if (isSelected) {
            label.background = list.selectionBackground
            label.foreground = list.selectionForeground
            label.isOpaque = true
        }
        return label
    }
}
