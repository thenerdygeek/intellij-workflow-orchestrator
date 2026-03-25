package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ai.AgentChatRedirect
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.TimeFormatter
import com.workflow.orchestrator.sonar.model.*
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.*

class IssueListPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

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

    private val paginationWarning = JBLabel().apply {
        foreground = StatusColors.WARNING
        font = font.deriveFont(Font.ITALIC, JBUI.scale(10).toFloat())
        border = JBUI.Borders.empty(2, 8)
        isVisible = false
    }

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
        val filterPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val filterRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(JBLabel("TYPE").apply {
                    font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(10).toFloat())
                    foreground = StatusColors.SECONDARY_TEXT
                })
                add(filterCombo)
                add(JBLabel("SEVERITY").apply {
                    font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(10).toFloat())
                    foreground = StatusColors.SECONDARY_TEXT
                })
                add(severityCombo)
                add(countLabel)
            }
            add(filterRow)
            add(paginationWarning)
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

                val agentAvailable = AgentChatRedirect.getInstance() != null || LlmBrainFactory.isAvailable()

                val menu = JPopupMenu()
                menu.add(JMenuItem("Navigate to Issue").apply {
                    addActionListener { navigateToIssue(issue) }
                })
                menu.add(JMenuItem("Fix with AI Agent").apply {
                    isEnabled = agentAvailable
                    toolTipText = if (!agentAvailable) "AI Agent is not available" else null
                    addActionListener { fixWithAgent(issue) }
                })
                menu.show(issueList, e.x, e.y)
            }
        })
    }

    fun update(issues: List<MappedIssue>, totalCount: Int? = null) {
        allIssues = issues
        // Show pagination warning when results are truncated
        if (totalCount != null && totalCount > issues.size) {
            paginationWarning.text = "\u26A0 Showing first ${issues.size} of $totalCount issues. More exist on the server."
            paginationWarning.isVisible = true
        } else {
            paginationWarning.isVisible = false
        }
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

        val sorted = filtered.sortedBy { it.severity.ordinal }

        // Preserve selection across update
        val selectedIssue = issueList.selectedValue
        val scrollPosition = scrollPane.verticalScrollBar.value

        // Only update if data actually changed
        val currentItems = (0 until listModel.size).map { listModel.getElementAt(it) }
        if (currentItems != sorted) {
            listModel.clear()
            sorted.forEach { listModel.addElement(it) }

            // Restore selection if the same issue is still present
            if (selectedIssue != null) {
                val newIndex = sorted.indexOf(selectedIssue)
                if (newIndex >= 0) {
                    issueList.selectedIndex = newIndex
                }
            }

            // Restore scroll position
            SwingUtilities.invokeLater {
                scrollPane.verticalScrollBar.value = scrollPosition
            }
        }

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
        if (sorted.isEmpty()) {
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

    override fun dispose() {
        scope.cancel()
    }

    private fun fixWithAgent(issue: MappedIssue) {
        val basePath = project.basePath ?: return
        val absolutePath = java.io.File(basePath, issue.filePath).absolutePath
        navigateToIssue(issue)

        val prompt = buildString {
            appendLine("Fix this SonarQube issue:")
            appendLine()
            appendLine("**Rule:** ${issue.rule}")
            appendLine("**Message:** ${issue.message}")
            appendLine("**File:** ${issue.filePath}")
            appendLine("**Line:** ${issue.startLine}")
            appendLine()
            appendLine("Read the file, understand the context, apply a minimal fix that resolves the issue without changing behavior, and verify it compiles with diagnostics.")
        }

        val redirect = com.workflow.orchestrator.core.ai.AgentChatRedirect.getInstance()
        if (redirect != null) {
            redirect.sendToAgent(project, prompt, listOf(absolutePath))
        } else {
            com.workflow.orchestrator.core.notifications.WorkflowNotificationService.getInstance(project).notifyError(
                com.workflow.orchestrator.core.notifications.WorkflowNotificationService.GROUP_QUALITY,
                "AI Agent Not Available",
                "Enable the Agent tab in Settings to use AI-powered fixes."
            )
        }
    }
}

private class IssueListCellRenderer : JPanel(), ListCellRenderer<MappedIssue> {
    private val mainLabel = JBLabel()
    private val detailLabel = JBLabel()

    companion object {
        private val SMALL_FONT by lazy { com.intellij.util.ui.JBFont.small() }
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(mainLabel)
        add(detailLabel.apply { font = SMALL_FONT })
    }

    override fun getListCellRendererComponent(
        list: JList<out MappedIssue>, value: MappedIssue,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        // Severity color — htmlColor resolves JBColor for current theme at render time
        val severityColor = when (value.severity) {
            IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> StatusColors.ERROR
            IssueSeverity.MAJOR, IssueSeverity.MINOR -> StatusColors.WARNING
            IssueSeverity.INFO -> StatusColors.INFO
        }
        val htmlColor = StatusColors.htmlColor(severityColor as JBColor)
        val typeStr = value.type.name.replace("_", " ")
        val fileName = java.io.File(value.filePath).name

        // Left accent border by severity (2px) + inner padding (Stitch design)
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 2, 0, 0, severityColor),
            JBUI.Borders.empty(4, 8)
        )

        // Main line: type + severity label in color + message + file:line
        mainLabel.text = "<html>$typeStr " +
            "<font color='$htmlColor'><b>[${value.severity}]</b></font>" +
            "  ${value.message} \u2014 $fileName:${value.startLine}</html>"
        mainLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

        // Detail line: effort + age
        val effort = value.effort
        val creationDate = value.creationDate
        if (effort != null || creationDate != null) {
            val sb = StringBuilder("  ")
            if (effort != null) sb.append(effort).append(" to fix")
            if (creationDate != null) {
                try {
                    val relativeTime = TimeFormatter.relative(Instant.parse(creationDate).toEpochMilli())
                    if (relativeTime.isNotEmpty()) {
                        if (effort != null) sb.append(" \u2022 ")
                        sb.append(relativeTime)
                    }
                } catch (_: Exception) {
                    // Sonar dates may be in different format — skip if unparseable
                }
            }
            detailLabel.text = sb.toString()
            detailLabel.foreground = if (isSelected) list.selectionForeground else StatusColors.SECONDARY_TEXT
            detailLabel.isVisible = true
        } else {
            detailLabel.isVisible = false
        }

        // Selection background
        isOpaque = true
        background = if (isSelected) list.selectionBackground else list.background

        // Tooltip
        toolTipText = buildString {
            append("[${value.rule}] ${value.message} \u2014 ${value.filePath}:${value.startLine}")
            value.effort?.let { append(" | Effort: $it") }
            append(" | Status: ${value.status}")
        }

        return this
    }
}
