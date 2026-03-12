package com.workflow.orchestrator.jira.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.service.ActiveTicketService
import com.workflow.orchestrator.jira.service.BranchingService
import com.workflow.orchestrator.jira.service.SprintService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Main Sprint Dashboard panel that composes the ticket list, detail panel,
 * search/filter, sprint header, and toolbar into a single tab view.
 */
class SprintDashboardPanel(
    private val project: Project,
    private val sprintService: SprintService,
    private val activeTicketService: ActiveTicketService,
    private val branchingService: BranchingService
) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(SprintDashboardPanel::class.java)

    // -- Models and list --
    private val listModel = DefaultListModel<JiraIssue>()
    private val ticketList = JBList(listModel).apply {
        cellRenderer = TicketListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = JBUI.scale(52)
        border = JBUI.Borders.empty()
        isOpaque = false
    }

    private val detailPanel = TicketDetailPanel()

    // -- Search --
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.setText("Filter by key or summary\u2026")
    }

    // -- Sprint header --
    private val sprintNameLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(14).toFloat())
        foreground = JBColor.foreground()
    }
    private val sprintMetaLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
        foreground = SECONDARY_TEXT
    }
    private val ticketCountLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
        foreground = SECONDARY_TEXT
    }
    private val progressBar = SprintProgressBar()

    // -- Status bar --
    private val statusLabel = JBLabel("Ready").apply {
        foreground = SECONDARY_TEXT
        font = font.deriveFont(JBUI.scale(11).toFloat())
        border = JBUI.Borders.empty(4, 8)
    }
    private val loadingIcon = JBLabel(AllIcons.Process.Step_1).apply {
        border = JBUI.Borders.emptyRight(4)
    }

    // -- State --
    private var allIssues: List<JiraIssue> = emptyList()

    init {
        background = JBColor.PanelBackground
        isOpaque = true

        setupLayout()
        setupListeners()
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    private fun setupLayout() {
        // -- Top: toolbar + sprint header --
        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 0, 8)
        }

        // Toolbar
        val actionGroup = DefaultActionGroup().apply {
            add(RefreshAction())
            add(StartWorkAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("SprintDashboard", actionGroup, true)
        toolbar.targetComponent = this
        topPanel.add(toolbar.component, BorderLayout.NORTH)

        // Sprint header
        val sprintHeaderPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 2, 4, 2)
        }
        val sprintInfoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        val nameRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
        }
        nameRow.add(sprintNameLabel)
        nameRow.add(ticketCountLabel)
        sprintInfoPanel.add(nameRow)
        sprintInfoPanel.add(sprintMetaLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        sprintHeaderPanel.add(sprintInfoPanel, BorderLayout.CENTER)

        // Progress bar
        progressBar.preferredSize = Dimension(0, JBUI.scale(6))
        sprintHeaderPanel.add(progressBar, BorderLayout.SOUTH)

        topPanel.add(sprintHeaderPanel, BorderLayout.CENTER)

        add(topPanel, BorderLayout.NORTH)

        // -- Center: search + list + detail in splitter --
        val leftPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 4, 0)
        }
        searchField.preferredSize = Dimension(0, JBUI.scale(28))
        leftPanel.add(searchField, BorderLayout.NORTH)
        leftPanel.add(JBScrollPane(ticketList).apply {
            border = JBUI.Borders.emptyTop(4)
            isOpaque = false
            viewport.isOpaque = false
        }, BorderLayout.CENTER)

        val splitter = JBSplitter(false, 0.4f).apply {
            setSplitterProportionKey("workflow.sprint.splitter")
            firstComponent = leftPanel
            secondComponent = detailPanel
            isOpaque = false
        }
        add(splitter, BorderLayout.CENTER)

        // -- Bottom: status bar --
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.customLine(BORDER_COLOR, 1, 0, 0, 0)
        }
        bottomPanel.add(loadingIcon)
        bottomPanel.add(statusLabel)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    // ---------------------------------------------------------------
    // Listeners
    // ---------------------------------------------------------------

    private fun setupListeners() {
        // List selection -> detail panel
        ticketList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = ticketList.selectedValue
                if (selected != null) {
                    detailPanel.showIssue(selected)
                } else {
                    detailPanel.showEmpty()
                }
            }
        }

        // Hover tracking for cell renderer (per-list via client property)
        ticketList.putClientProperty(TicketListCellRenderer.HOVERED_INDEX_KEY, -1)
        ticketList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = ticketList.locationToIndex(e.point)
                val current = ticketList.getClientProperty(TicketListCellRenderer.HOVERED_INDEX_KEY) as? Int ?: -1
                if (index != current) {
                    ticketList.putClientProperty(TicketListCellRenderer.HOVERED_INDEX_KEY, index)
                    ticketList.repaint()
                }
            }
        })

        ticketList.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                val current = ticketList.getClientProperty(TicketListCellRenderer.HOVERED_INDEX_KEY) as? Int ?: -1
                if (current != -1) {
                    ticketList.putClientProperty(TicketListCellRenderer.HOVERED_INDEX_KEY, -1)
                    ticketList.repaint()
                }
            }
        })

        // Search/filter
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
    }

    // ---------------------------------------------------------------
    // Data loading
    // ---------------------------------------------------------------

    fun loadData() {
        val settings = PluginSettings.getInstance(project)
        val boardId = settings.state.jiraBoardId.takeIf { it > 0 }

        setLoading(true, "Loading sprint tickets\u2026")

        runBackgroundableTask("Loading Sprint", project, false) {
            val result = runBlocking(Dispatchers.IO) { sprintService.loadSprintIssues(boardId) }
            invokeLater {
                when (result) {
                    is ApiResult.Success -> {
                        allIssues = result.data
                        updateList(allIssues)
                        updateSprintHeader()
                        setLoading(false, "${allIssues.size} tickets loaded")
                        log.info("[Jira:UI] Sprint dashboard loaded ${allIssues.size} tickets")
                    }
                    is ApiResult.Error -> {
                        allIssues = emptyList()
                        updateList(emptyList())
                        setLoading(false, "Error: ${result.message}")
                        log.warn("[Jira:UI] Sprint load failed: ${result.message}")
                    }
                }
            }
        }
    }

    private fun updateList(issues: List<JiraIssue>) {
        listModel.clear()
        for (issue in issues) {
            listModel.addElement(issue)
        }
        if (issues.isEmpty()) {
            detailPanel.showEmpty()
        }
    }

    private fun updateSprintHeader() {
        val sprint = sprintService.activeSprint
        if (sprint != null) {
            sprintNameLabel.text = sprint.name
            val dateRange = buildString {
                sprint.startDate?.take(10)?.let { append(it) }
                sprint.endDate?.take(10)?.let {
                    if (isNotEmpty()) append(" \u2192 ")
                    append(it)
                }
            }
            sprintMetaLabel.text = if (dateRange.isNotEmpty()) dateRange else sprint.state
            ticketCountLabel.text = "(${allIssues.size} tickets)"
        } else {
            sprintNameLabel.text = "No active sprint"
            sprintMetaLabel.text = ""
            ticketCountLabel.text = ""
        }
        progressBar.updateFromIssues(allIssues)
    }

    private fun setLoading(loading: Boolean, message: String) {
        loadingIcon.icon = if (loading) AllIcons.Process.Step_1 else null
        statusLabel.text = message
    }

    // ---------------------------------------------------------------
    // Filter
    // ---------------------------------------------------------------

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase()
        if (query.isEmpty()) {
            updateList(allIssues)
        } else {
            val filtered = allIssues.filter { issue ->
                issue.key.lowercase().contains(query) ||
                        issue.fields.summary.lowercase().contains(query)
            }
            updateList(filtered)
        }
    }

    // ---------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------

    private inner class RefreshAction : AnAction(
        "Refresh Sprint",
        "Reload sprint tickets from Jira",
        AllIcons.Actions.Refresh
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            loadData()
        }
    }

    private inner class StartWorkAction : AnAction(
        "Start Work",
        "Create branch and transition selected ticket to In Progress",
        AllIcons.Actions.Execute
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val selectedIssue = ticketList.selectedValue ?: return
            val settings = PluginSettings.getInstance(project)
            val pattern = settings.state.branchPattern ?: "feature/{ticketId}-{summary}"

            setLoading(true, "Starting work on ${selectedIssue.key}\u2026")

            runBackgroundableTask("Start Work: ${selectedIssue.key}", project, false) {
                val result = runBlocking(Dispatchers.IO) { branchingService.startWork(selectedIssue, pattern) }
                invokeLater {
                    when (result) {
                        is ApiResult.Success -> {
                            setLoading(false, "Branch created: ${result.data}")
                            log.info("[Jira:UI] Started work on ${selectedIssue.key}, branch: ${result.data}")
                        }
                        is ApiResult.Error -> {
                            setLoading(false, "Start Work failed: ${result.message}")
                            log.warn("[Jira:UI] Start Work failed for ${selectedIssue.key}: ${result.message}")
                        }
                    }
                }
            }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = ticketList.selectedValue != null
        }
    }

    // ---------------------------------------------------------------
    // Sprint progress bar (custom painted)
    // ---------------------------------------------------------------

    /**
     * Horizontal bar showing proportions of tickets by status category:
     * green = done, blue = in-progress, gray = todo.
     */
    private class SprintProgressBar : JPanel() {
        private var doneRatio: Float = 0f
        private var inProgressRatio: Float = 0f
        private var todoRatio: Float = 1f

        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(6))
            minimumSize = Dimension(0, JBUI.scale(6))
        }

        fun updateFromIssues(issues: List<JiraIssue>) {
            if (issues.isEmpty()) {
                doneRatio = 0f
                inProgressRatio = 0f
                todoRatio = 1f
            } else {
                val total = issues.size.toFloat()
                val done = issues.count { it.fields.status.statusCategory?.key == "done" }
                val inProgress = issues.count { it.fields.status.statusCategory?.key == "indeterminate" }
                val todo = issues.size - done - inProgress
                doneRatio = done / total
                inProgressRatio = inProgress / total
                todoRatio = todo / total
            }
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val barHeight = height.toFloat()
            val barWidth = width.toFloat()
            val cornerRadius = barHeight / 2

            // Background
            g2.color = PROGRESS_BG
            g2.fill(RoundRectangle2D.Float(0f, 0f, barWidth, barHeight, cornerRadius, cornerRadius))

            // Clip to rounded rect for segment painting
            g2.clip = java.awt.geom.Area(
                RoundRectangle2D.Float(0f, 0f, barWidth, barHeight, cornerRadius, cornerRadius)
            )

            var x = 0f

            // Done segment (green)
            if (doneRatio > 0f) {
                val segW = barWidth * doneRatio
                g2.color = PROGRESS_DONE
                g2.fillRect(x.toInt(), 0, segW.toInt().coerceAtLeast(1), height)
                x += segW
            }

            // In-progress segment (blue)
            if (inProgressRatio > 0f) {
                val segW = barWidth * inProgressRatio
                g2.color = PROGRESS_IN_PROGRESS
                g2.fillRect(x.toInt(), 0, segW.toInt().coerceAtLeast(1), height)
                x += segW
            }

            // Todo segment (gray) — fills remaining space
            if (todoRatio > 0f) {
                val segW = barWidth * todoRatio
                g2.color = PROGRESS_TODO
                g2.fillRect(x.toInt(), 0, segW.toInt().coerceAtLeast(1), height)
            }

            g2.dispose()
        }
    }

    // ---------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------

    companion object {
        private val SECONDARY_TEXT = JBColor(0x656D76, 0x8B949E)
        private val BORDER_COLOR = JBColor(0xD1D9E0, 0x444D56)

        private val PROGRESS_BG = JBColor(0xE8EAED, 0x3D4043)
        private val PROGRESS_DONE = JBColor(0x1B7F37, 0x3FB950)
        private val PROGRESS_IN_PROGRESS = JBColor(0x0969DA, 0x58A6FF)
        private val PROGRESS_TODO = JBColor(0x656D76, 0x8B949E)
    }
}
