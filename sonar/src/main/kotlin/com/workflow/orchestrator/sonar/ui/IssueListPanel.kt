package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ai.AgentChatRedirect
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.model.sonar.SecurityHotspotData
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.TimeFormatter
import com.workflow.orchestrator.core.util.PathLinkResolver
import com.workflow.orchestrator.sonar.model.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.*

class IssueListPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val pathLinkResolver = PathLinkResolver(project)

    private val listModel = DefaultListModel<QualityListItem>()
    private val issueList = JBList(listModel).apply {
        cellRenderer = IssueListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val filterCombo = ComboBox(arrayOf("All", "Bug", "Vulnerability", "Code Smell", "Hotspot"))
    private val severityCombo = ComboBox(arrayOf("All", "Blocker", "Critical", "Major", "Minor", "Info"))
    private val countLabel = JBLabel("0 issues")

    private var allIssues: List<MappedIssue> = emptyList()
    private var allHotspots: List<SecurityHotspotData> = emptyList()

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

    /**
     * Tracks which center component is currently shown in [listContent] so
     * [applyFilters] can skip the remove/add/revalidate cycle when the
     * empty-vs-non-empty state hasn't changed (P2-20).
     *
     * Values: null = nothing yet, "scroll" = scrollPane, "empty" = emptyLabel,
     * "filteredEmpty" = filteredEmptyLabel.
     */
    private var listContentCenter: String? = null

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

    /**
     * Applies a pre-filter to the issue list by selecting the appropriate type and resetting severity.
     * Used by GateStatusBanner to navigate directly to failing issue types.
     */
    fun applyPreFilter(type: IssueType?) {
        val typeIndex = when (type) {
            IssueType.BUG -> 1
            IssueType.VULNERABILITY -> 2
            IssueType.CODE_SMELL -> 3
            IssueType.SECURITY_HOTSPOT -> 4
            null -> 0
        }
        filterCombo.selectedIndex = typeIndex
        severityCombo.selectedIndex = 0
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
            invokeLater {
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

        // Show empty state or list — operate on listContent, not this (splitter owns this).
        // P2-20: skip the remove/add/revalidate cycle when the shown component hasn't changed.
        val wantCenter = when {
            sorted.isEmpty() && filtersActive && totalItems > 0 -> "filteredEmpty"
            sorted.isEmpty() -> "empty"
            else -> "scroll"
        }
        if (wantCenter != listContentCenter) {
            listContent.remove(scrollPane)
            listContent.remove(emptyLabel)
            listContent.remove(filteredEmptyLabel)
            when (wantCenter) {
                "filteredEmpty" -> listContent.add(filteredEmptyLabel, BorderLayout.CENTER)
                "empty" -> listContent.add(emptyLabel, BorderLayout.CENTER)
                else -> listContent.add(scrollPane, BorderLayout.CENTER)
            }
            listContentCenter = wantCenter
            listContent.revalidate()
            listContent.repaint()
        }
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
        // Pass "filePath:line" through PathLinkResolver as the security gate; fall back to plain
        // filePath if line decoration causes a reject (shouldn't happen for well-formed Sonar paths).
        val input = if (issue.startLine > 0) "${issue.filePath}:${issue.startLine}" else issue.filePath
        val validated = pathLinkResolver.resolveForOpen(input) ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(validated.canonicalPath) ?: return
        // Use resolver's 0-based line; preserve SonarQube's per-character startOffset as column.
        FileEditorManager.getInstance(project).openEditor(
            OpenFileDescriptor(project, vf, validated.line, issue.startOffset), true
        )
    }

    private fun navigateToHotspot(hotspot: SecurityHotspotData) {
        // Strip Sonar module prefix: "project-key:src/main/java/Foo.java" -> "src/main/java/Foo.java"
        val rawPath = hotspot.component.substringAfterLast(':')
        val line = hotspot.line ?: 0
        val input = if (line > 0) "$rawPath:$line" else rawPath
        val validated = pathLinkResolver.resolveForOpen(input) ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(validated.canonicalPath) ?: return
        FileEditorManager.getInstance(project).openEditor(
            OpenFileDescriptor(project, vf, validated.line, validated.column), true
        )
    }

    override fun dispose() {
        // No own resources to clean up — IssueDetailPanel (registered as child Disposer)
        // owns its own scope and handles all async fetches for the selected issue.
    }

    /**
     * Resolve the correct repo root for a Sonar issue in multi-repo projects (mirror of
     * IssueDetailPanel.resolveSonarRepoRoot — same sonar:F-12/F-13 pattern). `project.basePath`
     * alone is wrong when the issue's file lives in a submodule repo.
     */
    private fun resolveSonarRepoRoot(filePath: String, sonarProjectKey: String): String? {
        val repos = PluginSettings.getInstance(project).getRepos()
        return IssueDetailPanel.resolveRepoRoot(
            filePath = filePath,
            sonarProjectKey = sonarProjectKey,
            repoPairs = repos.mapNotNull { r ->
                r.sonarProjectKey?.takeIf { it.isNotBlank() }
                    ?.let { key -> Pair(key, r.localVcsRootPath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null) }
            },
            repoRoots = repos.mapNotNull { it.localVcsRootPath?.takeIf { it.isNotBlank() } },
            projectBasePath = project.basePath,
        )
    }

    private fun fixWithAgent(issue: MappedIssue) {
        val basePath = resolveSonarRepoRoot(issue.filePath, issue.projectKey) ?: return
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

/**
 * Two-line cell renderer for the issues/hotspots list.
 *
 * P1-19: the paint path is allocation-free where it matters —
 * - text is appended to [com.intellij.ui.SimpleColoredComponent]s (no HTML string
 *   building or HTML parsing per cell per paint, unlike the previous JBLabel +
 *   `<html>` implementation);
 * - accent borders and colored badge attributes are cached per color;
 * - creation-date PARSING is cached per issue (`Instant.parse` is the expensive
 *   part); the cheap [TimeFormatter.relative] arithmetic still runs per paint so
 *   the displayed age keeps advancing ("5 minutes ago" becomes "2 hours ago").
 *
 * All caches are EDT-confined (Swing renderers only run on the EDT), so plain
 * HashMaps suffice.
 */
private class IssueListCellRenderer : JPanel(), ListCellRenderer<QualityListItem> {
    private val mainLine = com.intellij.ui.SimpleColoredComponent().apply { isOpaque = false }
    private val detailLine = com.intellij.ui.SimpleColoredComponent().apply {
        isOpaque = false
        font = SMALL_FONT
    }

    /**
     * Parsed epoch millis keyed by the raw Sonar creationDate string;
     * [UNPARSEABLE_DATE] marks dates that failed parsing.
     */
    private val creationEpochCache = HashMap<String, Long>()

    companion object {
        private val SMALL_FONT by lazy { com.intellij.util.ui.JBFont.small() }

        /** Inner cell padding inside the accent border (Stitch design). */
        private const val CELL_PAD_TOP_BOTTOM = 4
        private const val CELL_PAD_LEFT_RIGHT = 8

        /** Safety valve so an unbounded stream of distinct dates cannot grow the cache forever. */
        private const val CREATION_EPOCH_CACHE_MAX = 512

        /** Sentinel cached for creationDate strings that fail [Instant.parse]. */
        private const val UNPARSEABLE_DATE = Long.MIN_VALUE

        /**
         * Pre-built borders keyed by accent color.  Borders are immutable once
         * constructed and safe to share across cells — allocating a fresh
         * CompoundBorder per cell per paint was the P1-19 hot allocation.
         * JBColor keys resolve light/dark at paint time, so cached borders stay
         * theme-correct across LAF switches.
         */
        private val borderCache = HashMap<JBColor, javax.swing.border.Border>()

        private fun accentBorder(color: JBColor): javax.swing.border.Border =
            borderCache.getOrPut(color) {
                javax.swing.BorderFactory.createCompoundBorder(
                    javax.swing.BorderFactory.createMatteBorder(0, 2, 0, 0, color),
                    JBUI.Borders.empty(CELL_PAD_TOP_BOTTOM, CELL_PAD_LEFT_RIGHT)
                )
            }

        /** Bold colored badge attributes, cached per color. */
        private val badgeAttrCache = HashMap<JBColor, com.intellij.ui.SimpleTextAttributes>()

        /**
         * Selection-aware attributes for the `[SEVERITY]` badge: selected rows use the
         * list's selection foreground (null fg falls through to the component foreground
         * set in the render methods); unselected rows use the cached severity color.
         */
        private fun badgeAttributes(
            color: JBColor,
            isSelected: Boolean
        ): com.intellij.ui.SimpleTextAttributes =
            if (isSelected) {
                com.intellij.ui.SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            } else {
                badgeAttrCache.getOrPut(color) {
                    com.intellij.ui.SimpleTextAttributes(
                        com.intellij.ui.SimpleTextAttributes.STYLE_BOLD,
                        color
                    )
                }
            }

        private val REGULAR = com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(mainLine)
        add(detailLine)
    }

    override fun getListCellRendererComponent(
        list: JList<out QualityListItem>, value: QualityListItem,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        mainLine.clear()
        detailLine.clear()
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
        val severityColor = when (issue.severity) {
            IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> StatusColors.ERROR
            IssueSeverity.MAJOR, IssueSeverity.MINOR -> StatusColors.WARNING
            IssueSeverity.INFO -> StatusColors.INFO
        }
        val typeStr = issue.type.name.replace("_", " ")
        val fileName = item.displayFileName

        // Left accent border by severity (2px) + inner padding (Stitch design).
        // P1-19: border is fetched from a per-color cache instead of being
        // allocated fresh on every cell paint.
        border = accentBorder(severityColor)

        // Main line: type + severity badge + optional impact badge + message + file:line
        mainLine.foreground = if (isSelected) list.selectionForeground else list.foreground
        mainLine.append("$typeStr ", REGULAR)
        mainLine.append("[${issue.severity}]", badgeAttributes(severityColor, isSelected))

        // Clean Code impacts badge (Sonar 9.6+) — colored by highest-severity impact.
        // Empty/older Sonar leaves `impacts` empty so the badge is suppressed.
        val impact = ImpactRendering.highest(issue.impacts)
        if (impact != null) {
            mainLine.append(
                " [${ImpactRendering.shortName(impact.softwareQuality)}/${impact.severity.name}]",
                badgeAttributes(ImpactRendering.colorFor(impact.severity), isSelected)
            )
        }
        mainLine.append("  ${issue.message} — $fileName:${issue.startLine}", REGULAR)

        // Detail line: effort + age (relative time cached per creation date — I1)
        val effort = issue.effort
        val relativeTime = issue.creationDate?.let { relativeTimeFor(it) }.orEmpty()
        if (effort != null || relativeTime.isNotEmpty()) {
            val sb = StringBuilder("  ")
            if (effort != null) sb.append(effort).append(" to fix")
            if (relativeTime.isNotEmpty()) {
                if (effort != null) sb.append(" • ")
                sb.append(relativeTime)
            }
            detailLine.foreground = if (isSelected) list.selectionForeground else StatusColors.SECONDARY_TEXT
            detailLine.append(sb.toString(), REGULAR)
            detailLine.isVisible = true
        } else {
            detailLine.isVisible = false
        }

        // Tooltip
        toolTipText = buildString {
            append("[${issue.rule}] ${issue.message} — ${issue.filePath}:${issue.startLine}")
            issue.effort?.let { append(" | Effort: $it") }
            append(" | Status: ${issue.status}")
        }
    }

    private fun renderHotspot(list: JList<out QualityListItem>, item: QualityListItem.HotspotItem, isSelected: Boolean) {
        val hotspot = item.hotspot
        val probabilityColor = when (hotspot.probability) {
            "HIGH" -> StatusColors.ERROR
            "MEDIUM" -> StatusColors.WARNING
            else -> StatusColors.INFO // LOW
        }
        val fileName = item.displayFileName
        val lineStr = hotspot.line?.toString() ?: "?"

        // Left accent border by probability (2px) + inner padding.
        // P1-19: fetched from cache — no per-render allocation.
        border = accentBorder(probabilityColor)

        // Main line: SECURITY HOTSPOT + probability badge + message + file:line
        mainLine.foreground = if (isSelected) list.selectionForeground else list.foreground
        mainLine.append("SECURITY HOTSPOT ", REGULAR)
        mainLine.append("[${hotspot.probability}]", badgeAttributes(probabilityColor, isSelected))
        mainLine.append("  ${hotspot.message} — $fileName:$lineStr", REGULAR)

        // Detail line: review status + security category
        val statusText = when (hotspot.status) {
            "TO_REVIEW" -> "To Review"
            "REVIEWED" -> hotspot.resolution?.let { "Reviewed ($it)" } ?: "Reviewed"
            else -> hotspot.status
        }
        detailLine.foreground = if (isSelected) list.selectionForeground else StatusColors.SECONDARY_TEXT
        detailLine.append("  $statusText • ${hotspot.securityCategory.replace("-", " ")}", REGULAR)
        detailLine.isVisible = true

        // Tooltip
        toolTipText = buildString {
            append("[${hotspot.securityCategory}] ${hotspot.message}")
            append(" — ${hotspot.component}")
            hotspot.line?.let { append(":$it") }
            append(" | Probability: ${hotspot.probability}")
            append(" | Status: $statusText")
        }
    }

    private fun relativeTimeFor(creationDate: String): String {
        if (creationEpochCache.size > CREATION_EPOCH_CACHE_MAX) creationEpochCache.clear()
        val epochMillis = creationEpochCache.getOrPut(creationDate) {
            try {
                Instant.parse(creationDate).toEpochMilli()
            } catch (_: Exception) {
                UNPARSEABLE_DATE // Sonar dates may be in a different format — skip if unparseable
            }
        }
        // Format per paint — cheap arithmetic, and the displayed age keeps advancing.
        return if (epochMillis == UNPARSEABLE_DATE) "" else TimeFormatter.relative(epochMillis)
    }
}
