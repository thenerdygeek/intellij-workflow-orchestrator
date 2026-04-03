package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ai.AgentChatRedirect
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.model.sonar.SecurityHotspotData
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

    private val listModel = DefaultListModel<QualityListItem>()
    private val issueList = JBList(listModel).apply {
        cellRenderer = IssueListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val filterCombo = JComboBox(arrayOf("All", "Bug", "Vulnerability", "Code Smell", "Hotspot"))
    private val severityCombo = JComboBox(arrayOf("All", "Blocker", "Critical", "Major", "Minor", "Info"))
    private val countLabel = JBLabel("0 issues")

    private var allIssues: List<MappedIssue> = emptyList()
    private var allHotspots: List<SecurityHotspotData> = emptyList()
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
    private val detailPanel = IssueDetailPanel(project).also { Disposer.register(this, it) }

    /** Container for the list side (filter + scroll/empty). Used by applyFilters() for add/remove. */
    private val listContent = JPanel(BorderLayout())

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
        listContent.add(filterPanel, BorderLayout.NORTH)
        listContent.add(scrollPane, BorderLayout.CENTER)

        // Wrap list and detail in a horizontal splitter (false = horizontal split)
        val splitter = JBSplitter(false, 0.4f).apply {
            firstComponent = listContent
            secondComponent = detailPanel
        }
        add(splitter, BorderLayout.CENTER)

        filterCombo.addActionListener { applyFilters() }
        severityCombo.addActionListener { applyFilters() }

        // Selection listener drives detail panel
        issueList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = issueList.selectedValue
                if (selected != null) {
                    detailPanel.showItem(selected)
                } else {
                    detailPanel.showEmptyState()
                }
            }
        }

        // Wire prev/next navigation
        detailPanel.onNavigatePrev = {
            val idx = issueList.selectedIndex
            if (idx > 0) issueList.selectedIndex = idx - 1
        }
        detailPanel.onNavigateNext = {
            val idx = issueList.selectedIndex
            if (idx < listModel.size - 1) issueList.selectedIndex = idx + 1
        }

        // Double-click navigates to file
        issueList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val item = issueList.selectedValue ?: return
                    navigateToItem(item)
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
                val item = issueList.selectedValue ?: return

                val menu = JPopupMenu()
                when (item) {
                    is QualityListItem.IssueItem -> {
                        val agentAvailable = AgentChatRedirect.getInstance() != null || LlmBrainFactory.isAvailable()
                        menu.add(JMenuItem("Navigate to Issue").apply {
                            addActionListener { navigateToItem(item) }
                        })
                        menu.add(JMenuItem("Fix with AI Agent").apply {
                            isEnabled = agentAvailable
                            toolTipText = if (!agentAvailable) "AI Agent is not available" else null
                            addActionListener { fixWithAgent(item.issue) }
                        })
                    }
                    is QualityListItem.HotspotItem -> {
                        menu.add(JMenuItem("Navigate to Hotspot").apply {
                            addActionListener { navigateToItem(item) }
                        })
                    }
                }
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

    fun updateHotspots(hotspots: List<SecurityHotspotData>) {
        allHotspots = hotspots
        applyFilters()
    }

    private fun applyFilters() {
        val typeFilter = filterCombo.selectedItem as? String ?: "All"
        val severityFilter = severityCombo.selectedItem as? String ?: "All"

        // Merge issues and hotspots, then apply filters
        val merged = QualityListItem.merge(allIssues, allHotspots)
        val filtered = merged.filter { item ->
            item.matchesTypeFilter(typeFilter) && item.matchesSeverityFilter(severityFilter)
        }

        // Sort: IssueItems by severity ordinal first, HotspotItems by probability after
        val sorted = filtered.sortedWith(Comparator { a, b ->
            val orderA = sortOrder(a)
            val orderB = sortOrder(b)
            orderA.compareTo(orderB)
        })

        // Preserve selection across update
        val selectedItem = issueList.selectedValue
        val scrollPosition = scrollPane.verticalScrollBar.value

        // Only update if data actually changed
        val currentItems = (0 until listModel.size).map { listModel.getElementAt(it) }
        if (currentItems != sorted) {
            listModel.clear()
            sorted.forEach { listModel.addElement(it) }

            // Restore selection if the same item is still present
            if (selectedItem != null) {
                val newIndex = sorted.indexOf(selectedItem)
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
        val totalItems = merged.size
        countLabel.text = if (filtersActive && totalItems > 0) {
            "Showing ${filtered.size} of $totalItems items"
        } else {
            "${filtered.size} item(s)"
        }

        // Show empty state or list — operate on listContent, not this (splitter owns this)
        listContent.remove(scrollPane)
        listContent.remove(emptyLabel)
        listContent.remove(filteredEmptyLabel)
        if (sorted.isEmpty()) {
            if (filtersActive && totalItems > 0) {
                listContent.add(filteredEmptyLabel, BorderLayout.CENTER)
            } else {
                listContent.add(emptyLabel, BorderLayout.CENTER)
            }
        } else {
            listContent.add(scrollPane, BorderLayout.CENTER)
        }
        listContent.revalidate()
        listContent.repaint()
    }

    /** Sort order: IssueItems by severity ordinal (0-4), HotspotItems by probability (5-7). */
    private fun sortOrder(item: QualityListItem): Int = when (item) {
        is QualityListItem.IssueItem -> item.issue.severity.ordinal
        is QualityListItem.HotspotItem -> when (item.hotspot.probability) {
            "HIGH" -> 5
            "MEDIUM" -> 6
            else -> 7 // LOW
        }
    }

    private fun navigateToItem(item: QualityListItem) {
        when (item) {
            is QualityListItem.IssueItem -> navigateToIssue(item.issue)
            is QualityListItem.HotspotItem -> navigateToHotspot(item.hotspot)
        }
    }

    private fun navigateToIssue(issue: MappedIssue) {
        val basePath = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, issue.filePath).path) ?: return
        OpenFileDescriptor(project, vf, issue.startLine - 1, issue.startOffset).navigate(true)
    }

    private fun navigateToHotspot(hotspot: SecurityHotspotData) {
        val basePath = project.basePath ?: return
        // Extract file path from component: "project-key:src/main/java/Foo.java" -> "src/main/java/Foo.java"
        val filePath = hotspot.component.substringAfterLast(':')
        val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, filePath).path) ?: return
        val line = hotspot.line?.let { it - 1 } ?: 0
        OpenFileDescriptor(project, vf, line, 0).navigate(true)
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

private class IssueListCellRenderer : JPanel(), ListCellRenderer<QualityListItem> {
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
        list: JList<out QualityListItem>, value: QualityListItem,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        when (value) {
            is QualityListItem.IssueItem -> renderIssue(list, value, isSelected)
            is QualityListItem.HotspotItem -> renderHotspot(list, value, isSelected)
        }

        // Selection background
        isOpaque = true
        background = if (isSelected) list.selectionBackground else list.background

        return this
    }

    private fun renderIssue(list: JList<out QualityListItem>, item: QualityListItem.IssueItem, isSelected: Boolean) {
        val issue = item.issue
        // Severity color — htmlColor resolves JBColor for current theme at render time
        val severityColor = when (issue.severity) {
            IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> StatusColors.ERROR
            IssueSeverity.MAJOR, IssueSeverity.MINOR -> StatusColors.WARNING
            IssueSeverity.INFO -> StatusColors.INFO
        }
        val htmlColor = StatusColors.htmlColor(severityColor as JBColor)
        val typeStr = issue.type.name.replace("_", " ")
        val fileName = item.displayFileName

        // Left accent border by severity (2px) + inner padding (Stitch design)
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 2, 0, 0, severityColor),
            JBUI.Borders.empty(4, 8)
        )

        // Main line: type + severity label in color + message + file:line
        mainLabel.text = "<html>$typeStr " +
            "<font color='$htmlColor'><b>[${issue.severity}]</b></font>" +
            "  ${issue.message} \u2014 $fileName:${issue.startLine}</html>"
        mainLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

        // Detail line: effort + age
        val effort = issue.effort
        val creationDate = issue.creationDate
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

        // Tooltip
        toolTipText = buildString {
            append("[${issue.rule}] ${issue.message} \u2014 ${issue.filePath}:${issue.startLine}")
            issue.effort?.let { append(" | Effort: $it") }
            append(" | Status: ${issue.status}")
        }
    }

    private fun renderHotspot(list: JList<out QualityListItem>, item: QualityListItem.HotspotItem, isSelected: Boolean) {
        val hotspot = item.hotspot
        // Probability color badge
        val probabilityColor = when (hotspot.probability) {
            "HIGH" -> StatusColors.ERROR
            "MEDIUM" -> StatusColors.WARNING
            else -> StatusColors.INFO // LOW
        }
        val htmlColor = StatusColors.htmlColor(probabilityColor as JBColor)
        val fileName = item.displayFileName
        val lineStr = hotspot.line?.toString() ?: "?"

        // Left accent border by probability (2px) + inner padding
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 2, 0, 0, probabilityColor),
            JBUI.Borders.empty(4, 8)
        )

        // Main line: SECURITY HOTSPOT + probability badge + message + file:line
        mainLabel.text = "<html>SECURITY HOTSPOT " +
            "<font color='$htmlColor'><b>[${hotspot.probability}]</b></font>" +
            "  ${hotspot.message} \u2014 $fileName:$lineStr</html>"
        mainLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

        // Detail line: review status + security category
        val statusText = when (hotspot.status) {
            "TO_REVIEW" -> "To Review"
            "REVIEWED" -> hotspot.resolution?.let { "Reviewed ($it)" } ?: "Reviewed"
            else -> hotspot.status
        }
        detailLabel.text = "  $statusText \u2022 ${hotspot.securityCategory.replace("-", " ")}"
        detailLabel.foreground = if (isSelected) list.selectionForeground else StatusColors.SECONDARY_TEXT
        detailLabel.isVisible = true

        // Tooltip
        toolTipText = buildString {
            append("[${hotspot.securityCategory}] ${hotspot.message}")
            append(" \u2014 ${hotspot.component}")
            hotspot.line?.let { append(":$it") }
            append(" | Probability: ${hotspot.probability}")
            append(" | Status: $statusText")
        }
    }
}
