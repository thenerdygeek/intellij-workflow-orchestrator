package com.workflow.orchestrator.jira.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ai.BranchNameAiGenerator
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import git4idea.repo.GitRepositoryManager
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.api.dto.JiraIssueFields
import com.workflow.orchestrator.jira.api.dto.JiraSprint
import com.workflow.orchestrator.jira.api.dto.JiraStatus
import com.workflow.orchestrator.jira.listeners.BranchChangeTicketDetector
import com.workflow.orchestrator.jira.service.ActiveTicketService
import com.workflow.orchestrator.jira.service.BranchNameValidator
import com.workflow.orchestrator.jira.service.BranchingService
import com.workflow.orchestrator.jira.service.SprintService
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.SwingConstants

/**
 * Main Sprint Dashboard panel that composes the ticket list, detail panel,
 * search/filter, sprint header, and toolbar into a single tab view.
 */
class SprintDashboardPanel(
    private val project: Project,
    private val sprintService: SprintService,
    private val activeTicketService: ActiveTicketService,
    private val branchingService: BranchingService
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(SprintDashboardPanel::class.java)

    // -- Models and list --
    private val listModel = DefaultListModel<JiraIssue>()
    private val ticketList = JBList(listModel).apply {
        cellRenderer = TicketListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = JBUI.scale(56)
        border = JBUI.Borders.empty()
        isOpaque = false
    }

    private val detailPanel = TicketDetailPanel(project)

    // -- Search --
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.setText("Filter by key or summary\u2026")
    }

    // -- Sprint header --
    private val sprintNameLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
        foreground = JBColor.foreground()
    }
    private val sprintMetaLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(10).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val ticketCountLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(10).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(StatusColors.BORDER, 1, true),
            JBUI.Borders.empty(1, 6)
        )
    }
    private val sprintTimeBar = SprintTimeBar()

    // -- Sprint selector --
    private val sprintSelector = ComboBox<JiraSprint>().apply {
        renderer = SimpleListCellRenderer.create("") { sprint ->
            sprint.name + if (sprint.state == "active") " (Active)" else ""
        }
        isVisible = false // Hidden until sprints are loaded
    }
    private var availableSprints: List<JiraSprint> = emptyList()
    private var sprintSelectorLoading = false

    // -- Status bar --
    private val statusLabel = JBLabel("Ready").apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(JBUI.scale(11).toFloat())
        border = JBUI.Borders.empty(4, 8)
    }
    private val loadingIcon = JBLabel(AnimatedIcon.Default()).apply {
        border = JBUI.Borders.emptyRight(4)
        isVisible = false
    }

    // -- Empty state --
    private val emptyLabel = JBLabel("No tickets in sprint.").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }
    private lateinit var listCardLayout: CardLayout
    private lateinit var listCardPanel: JPanel

    // -- Sort control --
    private val sortByCombo = ComboBox(arrayOf("Default", "Priority", "Status", "Updated", "Key"))

    // -- Detection banner --
    private val detectionBanner = JPanel(BorderLayout()).apply {
        background = StatusColors.INFO_BG
        border = JBUI.Borders.empty(4, 8)
        isVisible = false
    }
    private val detectionLabel = JBLabel().apply {
        foreground = StatusColors.LINK
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private var lastDetectedTicketKey: String? = null
    private var lastDetectedSummary: String? = null
    private var lastDetectedSprint: String? = null
    private var lastDetectedAssignee: String? = null
    private var lastDetectedBranchName: String? = null

    // -- State --
    private var allIssues: List<JiraIssue> = emptyList()
    private var showAllUsers: Boolean = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val searchDebounce = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private lateinit var currentWorkSection: CurrentWorkSection
    private lateinit var sprintCollapsible: CollapsibleSection

    /** Check if a JiraIssue is a section header (used for assignee grouping). */
    private fun isHeader(issue: JiraIssue): Boolean = issue.id.startsWith("header-")

    init {
        background = JBColor.PanelBackground
        isOpaque = true

        // Restore persisted sort preference
        val initSettings = PluginSettings.getInstance(project)
        sortByCombo.selectedItem = initSettings.state.sprintSortBy ?: "Default"

        setupDetectionBanner()
        setupLayout()
        setupListeners()
        listenForTicketDetection()

        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch {
            searchDebounce.debounce(250).collect {
                withContext(Dispatchers.EDT) { applyFilter() }
            }
        }
    }

    // ---------------------------------------------------------------
    // Detection Banner
    // ---------------------------------------------------------------

    private fun setupDetectionBanner() {
        detectionBanner.add(detectionLabel, BorderLayout.CENTER)

        val dismissBannerLabel = JBLabel("x").apply {
            foreground = StatusColors.SECONDARY_TEXT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.emptyLeft(8)
        }
        dismissBannerLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                detectionBanner.isVisible = false
                revalidate()
            }
        })
        detectionBanner.add(dismissBannerLabel, BorderLayout.EAST)

        detectionLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val key = lastDetectedTicketKey ?: return
                val summary = lastDetectedSummary ?: return
                val frame = WindowManager.getInstance().getFrame(project) ?: return

                TicketDetectionPopup(
                    ticketKey = key,
                    summary = summary,
                    sprint = lastDetectedSprint,
                    assignee = lastDetectedAssignee,
                    onAccept = {
                        val settings = PluginSettings.getInstance(project)
                        settings.state.activeTicketId = key
                        settings.state.activeTicketSummary = summary
                        ActiveTicketService.getInstance(project).setActiveTicket(key, summary)
                        detectionBanner.isVisible = false
                        // Remove this branch from dismissed so it won't re-trigger banner
                        lastDetectedBranchName?.let { BranchChangeTicketDetector.dismissedBranches.remove(it) }
                        revalidate()
                    },
                    onDismiss = {
                        detectionBanner.isVisible = false
                        revalidate()
                    }
                ).show(frame)
            }
        })
    }

    private fun listenForTicketDetection() {
        val eventBus = project.getService(EventBus::class.java)
        scope.launch {
            eventBus.events.collect { event ->
                if (event is WorkflowEvent.TicketDetected) {
                    lastDetectedTicketKey = event.ticketKey
                    lastDetectedSummary = event.ticketSummary
                    lastDetectedSprint = event.sprint
                    lastDetectedAssignee = event.assignee
                    lastDetectedBranchName = event.branchName
                    withContext(Dispatchers.EDT) {
                        detectionLabel.text = "Detected: ${event.ticketKey} \u2014 ${event.ticketSummary}"
                        detectionBanner.isVisible = true
                        revalidate()
                    }
                }
            }
        }
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
            add(ToggleAllUsersAction())
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
        nameRow.add(sprintSelector)
        nameRow.add(sprintNameLabel)
        nameRow.add(ticketCountLabel)
        sprintInfoPanel.add(nameRow)
        sprintInfoPanel.add(sprintMetaLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        sprintHeaderPanel.add(sprintInfoPanel, BorderLayout.CENTER)

        // Sprint time bar (time remaining + ticket breakdown)
        sprintHeaderPanel.add(sprintTimeBar, BorderLayout.SOUTH)

        topPanel.add(sprintHeaderPanel, BorderLayout.CENTER)

        // Wrap topPanel with detection banner above it
        val northWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        northWrapper.add(detectionBanner, BorderLayout.NORTH)
        northWrapper.add(topPanel, BorderLayout.CENTER)
        add(northWrapper, BorderLayout.NORTH)

        // -- Center: collapsible sections + detail in splitter --
        currentWorkSection = CurrentWorkSection(project) { ticketId ->
            // Find and select the ticket in the list
            for (i in 0 until listModel.size()) {
                val issue = listModel.getElementAt(i)
                if (issue.key == ticketId) {
                    ticketList.selectedIndex = i
                    ticketList.ensureIndexIsVisible(i)
                    detailPanel.showIssue(issue)
                    break
                }
            }
        }
        val currentWorkCollapsible = CollapsibleSection(
            title = "CURRENTLY WORKING ON",
            content = currentWorkSection,
            initiallyExpanded = true
        )

        // Sprint ticket list with search + sort/group controls + empty state
        val sprintListInner = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        searchField.preferredSize = Dimension(0, JBUI.scale(28))

        // Sort/Group controls row
        val sortGroupPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
        }
        sortByCombo.preferredSize = Dimension(JBUI.scale(140), JBUI.scale(24))
        sortByCombo.toolTipText = "Sort by"
        sortGroupPanel.add(sortByCombo)

        val topControlsPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        topControlsPanel.add(searchField, BorderLayout.NORTH)
        topControlsPanel.add(sortGroupPanel, BorderLayout.SOUTH)

        sprintListInner.add(topControlsPanel, BorderLayout.NORTH)

        listCardLayout = CardLayout()
        listCardPanel = JPanel(listCardLayout).apply { isOpaque = false }
        listCardPanel.add(JBScrollPane(ticketList).apply {
            border = JBUI.Borders.emptyTop(4)
            isOpaque = false
            viewport.isOpaque = false
        }, "list")
        listCardPanel.add(emptyLabel, "empty")
        listCardLayout.show(listCardPanel, "list")

        sprintListInner.add(listCardPanel, BorderLayout.CENTER)

        sprintCollapsible = CollapsibleSection(
            title = "SPRINT TICKETS",
            content = sprintListInner,
            initiallyExpanded = true,
            count = 0
        )

        // Left panel: stacked collapsible sections
        val leftPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 8, 4, 0)
        }
        val sectionsPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            isOpaque = false
        }
        sectionsPanel.add(currentWorkCollapsible)
        sectionsPanel.add(sprintCollapsible)

        // Current work section is fixed at top, sprint fills remaining space
        leftPanel.add(currentWorkCollapsible, BorderLayout.NORTH)
        leftPanel.add(sprintCollapsible, BorderLayout.CENTER)

        // Refresh current work on init
        currentWorkSection.refresh()

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
            border = JBUI.Borders.customLine(StatusColors.BORDER, 1, 0, 0, 0)
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
                if (selected != null && !isHeader(selected)) {
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
                    // Repaint only the old and new hovered cells, not the entire list
                    val oldRect = if (current >= 0) ticketList.getCellBounds(current, current) else null
                    val newRect = if (index >= 0) ticketList.getCellBounds(index, index) else null
                    ticketList.putClientProperty(TicketListCellRenderer.HOVERED_INDEX_KEY, index)
                    oldRect?.let { ticketList.repaint(it) }
                    newRect?.let { ticketList.repaint(it) }
                }
            }
        })

        ticketList.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                val current = ticketList.getClientProperty(TicketListCellRenderer.HOVERED_INDEX_KEY) as? Int ?: -1
                if (current != -1) {
                    // Repaint only the previously hovered cell
                    val oldRect = ticketList.getCellBounds(current, current)
                    ticketList.putClientProperty(TicketListCellRenderer.HOVERED_INDEX_KEY, -1)
                    oldRect?.let { ticketList.repaint(it) }
                }
            }
        })

        // Sprint selector change
        sprintSelector.addActionListener {
            if (sprintSelectorLoading) return@addActionListener
            val selectedSprint = sprintSelector.selectedItem as? JiraSprint ?: return@addActionListener
            loadSprintBySelection(selectedSprint)
        }

        // Sort combo change triggers filter reapply and persists selection
        sortByCombo.addActionListener {
            val settings = PluginSettings.getInstance(project)
            settings.state.sprintSortBy = sortByCombo.selectedItem as? String ?: "Default"
            applyFilter()
        }

        // Search/filter (debounced — see searchDebounce collector in init)
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { searchDebounce.tryEmit(Unit) }
            override fun removeUpdate(e: DocumentEvent) { searchDebounce.tryEmit(Unit) }
            override fun changedUpdate(e: DocumentEvent) { searchDebounce.tryEmit(Unit) }
        })
    }

    // ---------------------------------------------------------------
    // Data loading
    // ---------------------------------------------------------------

    fun loadData() {
        val settings = PluginSettings.getInstance(project)
        val boardId = settings.state.jiraBoardId.takeIf { it > 0 }
        val boardType = settings.state.jiraBoardType ?: ""
        val boardName = settings.state.jiraBoardName ?: ""

        setLoading(true, "Loading sprint tickets\u2026")

        scope.launch {
            val result = sprintService.loadSprintIssues(boardId, boardType, showAllUsers, boardName)

            // Load available sprints for the selector (scrum boards only)
            val resolvedBoardId = sprintService.discoveredBoard?.id
            val isScrumBoard = sprintService.discoveredBoard?.type == "scrum"
            val sprints = if (isScrumBoard && resolvedBoardId != null) {
                sprintService.loadAvailableSprints(resolvedBoardId)
            } else {
                emptyList()
            }

            withContext(Dispatchers.EDT) {
                when (result) {
                    is ApiResult.Success -> {
                        allIssues = result.data
                        updateList(allIssues)
                        updateSprintHeader()
                        populateSprintSelector(sprints)
                        setLoading(false, "${allIssues.size} tickets loaded")
                        log.info("[Jira:UI] Sprint dashboard loaded ${allIssues.size} tickets")
                    }
                    is ApiResult.Error -> {
                        allIssues = emptyList()
                        updateList(emptyList())
                        populateSprintSelector(emptyList())
                        setLoading(false, "Error: ${result.message}")
                        log.warn("[Jira:UI] Sprint load failed: ${result.message}")
                    }
                }
            }
        }
    }

    private fun populateSprintSelector(sprints: List<JiraSprint>) {
        availableSprints = sprints
        sprintSelectorLoading = true
        try {
            sprintSelector.removeAllItems()
            if (sprints.size > 1) {
                for (sprint in sprints) {
                    sprintSelector.addItem(sprint)
                }
                // Auto-select the active sprint
                val activeSprint = sprintService.activeSprint
                if (activeSprint != null) {
                    val activeIndex = sprints.indexOfFirst { it.id == activeSprint.id }
                    if (activeIndex >= 0) {
                        sprintSelector.selectedIndex = activeIndex
                    }
                }
                sprintSelector.isVisible = true
                sprintNameLabel.isVisible = false // Hide label when selector is shown
            } else {
                sprintSelector.isVisible = false
                sprintNameLabel.isVisible = true
            }
        } finally {
            sprintSelectorLoading = false
        }
    }

    private fun loadSprintBySelection(sprint: JiraSprint) {
        setLoading(true, "Loading ${sprint.name}\u2026")

        scope.launch {
            val result = sprintService.loadIssuesForSprint(sprint.id, showAllUsers)
            withContext(Dispatchers.EDT) {
                when (result) {
                    is ApiResult.Success -> {
                        allIssues = result.data
                        updateList(allIssues)
                        updateSprintHeaderForSprint(sprint)
                        setLoading(false, "${allIssues.size} tickets loaded")
                        log.info("[Jira:UI] Loaded ${allIssues.size} tickets for sprint ${sprint.name}")
                    }
                    is ApiResult.Error -> {
                        allIssues = emptyList()
                        updateList(emptyList())
                        setLoading(false, "Error: ${result.message}")
                        log.warn("[Jira:UI] Sprint load failed for ${sprint.name}: ${result.message}")
                    }
                }
            }
        }
    }

    private fun updateSprintHeaderForSprint(sprint: JiraSprint) {
        val dateRange = buildString {
            sprint.startDate?.take(10)?.let { append(it) }
            sprint.endDate?.take(10)?.let {
                if (isNotEmpty()) append(" \u2192 ")
                append(it)
            }
        }
        sprintMetaLabel.text = if (dateRange.isNotEmpty()) dateRange else sprint.state
        ticketCountLabel.text = "(${allIssues.size} tickets)"
        sprintTimeBar.updateFromIssues(sprint, allIssues)
    }

    private fun updateList(issues: List<JiraIssue>) {
        ticketList.clearSelection()
        listModel.clear()

        val sortBy = sortByCombo.selectedItem as? String ?: "Default"
        val sorted = sortIssues(issues, sortBy)

        for (issue in sorted) {
            listModel.addElement(issue)
        }

        // Update collapsible section count
        sprintCollapsible.updateCount(issues.size)

        if (issues.isEmpty()) {
            detailPanel.showEmpty()
            listCardLayout.show(listCardPanel, "empty")
        } else {
            listCardLayout.show(listCardPanel, "list")
        }
    }

    private fun sortIssues(issues: List<JiraIssue>, sortBy: String): List<JiraIssue> {
        return when (sortBy) {
            "Priority" -> issues.sortedBy { priorityOrder(it.fields.priority?.name) }
            "Status" -> issues.sortedBy { statusOrder(it.fields.status.statusCategory?.key) }
            "Updated" -> issues.sortedByDescending { it.fields.updated ?: "" }
            "Key" -> issues.sortedBy { it.key }
            else -> issues
        }
    }

    private fun priorityOrder(name: String?): Int = when (name?.lowercase()) {
        "highest", "blocker" -> 0
        "high", "critical" -> 1
        "medium" -> 2
        "low" -> 3
        "lowest" -> 4
        else -> 5
    }

    private fun statusOrder(categoryKey: String?): Int = when (categoryKey) {
        "indeterminate" -> 0  // In Progress first
        "new" -> 1            // To Do
        "done" -> 2           // Done last
        else -> 3
    }

    private fun updateSprintHeader() {
        val sprint = sprintService.activeSprint
        val board = sprintService.discoveredBoard
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
        } else if (board != null) {
            // Kanban or board without active sprint
            sprintNameLabel.text = board.name
            sprintMetaLabel.text = board.type.replaceFirstChar { it.uppercase() } + " board"
            ticketCountLabel.text = "(${allIssues.size} tickets)"
        } else {
            sprintNameLabel.text = "No board found"
            sprintMetaLabel.text = ""
            ticketCountLabel.text = ""
        }
        sprintTimeBar.updateFromIssues(sprint, allIssues)
    }

    private fun setLoading(loading: Boolean, message: String) {
        loadingIcon.isVisible = loading
        statusLabel.text = message
    }

    // ---------------------------------------------------------------
    // Filter
    // ---------------------------------------------------------------

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase()
        val displayed = if (query.isEmpty()) {
            allIssues
        } else {
            allIssues.filter { issue ->
                issue.key.lowercase().contains(query) ||
                        issue.fields.summary.lowercase().contains(query) ||
                        (issue.fields.assignee?.displayName?.lowercase()?.contains(query) == true)
            }
        }
        updateList(displayed)
        // Update ticket count to reflect filtered results
        ticketCountLabel.text = if (query.isEmpty()) "(${allIssues.size} tickets)"
        else "(${displayed.size}/${allIssues.size} tickets)"
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

    private inner class ToggleAllUsersAction : AnAction(
        "All Tickets",
        "Toggle between my tickets and all team tickets",
        AllIcons.Actions.GroupByModule
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            showAllUsers = !showAllUsers
            loadData()
        }

        override fun update(e: AnActionEvent) {
            if (showAllUsers) {
                e.presentation.text = "My Tickets"
                e.presentation.description = "Show only tickets assigned to me"
                e.presentation.icon = AllIcons.Actions.GroupByModule
            } else {
                e.presentation.text = "All Tickets"
                e.presentation.description = "Show all team tickets grouped by assignee"
                e.presentation.icon = AllIcons.Actions.GroupByModule
            }
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    private inner class StartWorkAction : AnAction(
        "Start Work",
        "Create branch on Bitbucket and transition selected ticket to In Progress",
        AllIcons.Actions.Execute
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val selectedIssue = ticketList.selectedValue ?: return
            if (isHeader(selectedIssue)) return
            val settings = PluginSettings.getInstance(project)
            val pattern = settings.state.branchPattern ?: "feature/{ticketId}-{summary}"
            val bitbucketUrl = settings.connections.bitbucketUrl.orEmpty().trimEnd('/')

            val allRepos = settings.getRepos().filter { it.isConfigured }
            val resolver = com.workflow.orchestrator.core.settings.RepoContextResolver.getInstance(project)

            if (allRepos.isEmpty()) {
                setLoading(false, "Configure at least one repository in Settings first")
                return
            }

            val credentialStore = CredentialStore()

            val needsCody = BranchNameValidator.requiresCodySummary(pattern)
            log.info("[Jira:StartWork] Pattern='$pattern', needsCody=$needsCody")

            // Fallback branch name (uses {summary} in place of {cody-summary} when Cody fails)
            val fallbackPattern = pattern.replace("{cody-summary}", "{summary}")
            val fallbackBranchName = branchingService.generateBranchName(
                selectedIssue, fallbackPattern, settings.state.branchMaxSummaryLength
            )

            // If no Cody needed, generate the name immediately
            val staticBranchName = if (!needsCody) {
                branchingService.generateBranchName(
                    selectedIssue, pattern, settings.state.branchMaxSummaryLength
                )
            } else ""

            setLoading(true, "Fetching branches\u2026")

            scope.launch {
                // Resolve repo context off-EDT to avoid synchronous VCS repository update
                val detectedRepo = com.intellij.openapi.application.ReadAction.compute<com.workflow.orchestrator.core.settings.RepoConfig?, Throwable> {
                    resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
                }
                val detectedIndex = allRepos.indexOfFirst {
                    it.bitbucketProjectKey == detectedRepo?.bitbucketProjectKey &&
                        it.bitbucketRepoSlug == detectedRepo?.bitbucketRepoSlug
                }.takeIf { it >= 0 } ?: 0

                val initialRepo = allRepos[detectedIndex]
                val projectKey = initialRepo.bitbucketProjectKey.orEmpty()
                val repoSlug = initialRepo.bitbucketRepoSlug.orEmpty()
                val repoDisplay = initialRepo.displayLabel

                if (bitbucketUrl.isBlank() || projectKey.isBlank() || repoSlug.isBlank()) {
                    withContext(Dispatchers.EDT) {
                        setLoading(false, "Configure Bitbucket URL, project key, and repo slug in Settings first")
                    }
                    return@launch
                }

                val branchClient = BitbucketBranchClient(
                    baseUrl = bitbucketUrl,
                    tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
                )

                val gitRepo = com.intellij.openapi.application.ReadAction.compute<git4idea.repo.GitRepository?, Throwable> {
                    GitRepositoryManager.getInstance(project).repositories.firstOrNull()
                }
                val defaultSource = gitRepo?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
                val branchesResult = branchingService.fetchRemoteBranches(branchClient, projectKey, repoSlug)

                val branches = when (branchesResult) {
                    is ApiResult.Success -> branchesResult.data
                    is ApiResult.Error -> {
                        withContext(Dispatchers.EDT) {
                            setLoading(false, "Failed to fetch branches: ${branchesResult.message}")
                        }
                        return@launch
                    }
                }

                // Fetch linked branches from Jira dev-status (with Bitbucket fallback)
                val linkedBranches = branchingService.fetchLinkedBranches(selectedIssue, branches)

                withContext(Dispatchers.EDT) {
                    setLoading(false, "")

                    // Show dialog on EDT
                    val dialog = StartWorkDialog(
                        project = project,
                        ticketKey = selectedIssue.key,
                        defaultBranchName = staticBranchName,
                        remoteBranches = branches,
                        defaultSourceBranch = defaultSource,
                        repoDisplay = repoDisplay,
                        needsCodyGeneration = needsCody,
                        fallbackBranchName = fallbackBranchName,
                        existingBranches = linkedBranches,
                        repos = allRepos,
                        initialRepoIndex = detectedIndex
                    )

                    // If Cody is needed, launch generation in background AFTER dialog is shown
                    if (needsCody) {
                        scope.launch {
                            log.info("[Jira:StartWork] Launching Cody branch name generation for ${selectedIssue.key}")
                            try {
                                val generator = BranchNameAiGenerator.getInstance()
                                if (generator == null) {
                                    log.warn("[Jira:StartWork] No BranchNameAiGenerator registered — Cody not available")
                                    withContext(Dispatchers.EDT) {
                                        dialog.setCodyFailed("Cody AI not available")
                                    }
                                } else {
                                    log.info("[Jira:StartWork] BranchNameAiGenerator found: ${generator.javaClass.name}")
                                    val slug = generator.generateBranchSlug(
                                        project = project,
                                        ticketKey = selectedIssue.key,
                                        title = selectedIssue.fields.summary,
                                        description = selectedIssue.fields.description
                                    )

                                    if (slug != null) {
                                        val codyBranchName = branchingService.generateBranchName(
                                            selectedIssue, pattern, settings.state.branchMaxSummaryLength,
                                            codySummary = slug
                                        )
                                        log.info("[Jira:StartWork] Cody generated full branch name: '$codyBranchName'")
                                        withContext(Dispatchers.EDT) {
                                            dialog.setCodyResult(codyBranchName)
                                        }
                                    } else {
                                        log.warn("[Jira:StartWork] Cody returned null slug")
                                        withContext(Dispatchers.EDT) {
                                            dialog.setCodyFailed("Cody returned empty response")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                log.error("[Jira:StartWork] Cody branch generation failed with exception", e)
                                withContext(Dispatchers.EDT) {
                                    dialog.setCodyFailed(e.message ?: "Unknown error")
                                }
                            }
                        }
                    }

                    if (!dialog.showAndGet()) return@withContext
                    val dialogResult = dialog.result ?: return@withContext

                    // Resolve the selected repo — may differ from initial if user changed it
                    val selectedRepo = allRepos.getOrElse(dialogResult.selectedRepoIndex) { initialRepo }
                    val finalProjectKey = selectedRepo.bitbucketProjectKey.orEmpty()
                    val finalRepoSlug = selectedRepo.bitbucketRepoSlug.orEmpty()
                    val finalBranchClient = if (dialogResult.selectedRepoIndex != detectedIndex) {
                        // User switched repo — create a new branch client isn't needed,
                        // but we need to use the correct project/repo for the API call
                        branchClient // same HTTP client, different projectKey/repoSlug passed below
                    } else branchClient

                    if (dialogResult.useExisting) {
                        setLoading(true, "Checking out branch\u2026")

                        scope.launch {
                            val result = branchingService.useExistingBranch(
                                issue = selectedIssue,
                                branchName = dialogResult.branchName
                            )
                            withContext(Dispatchers.EDT) {
                                when (result) {
                                    is ApiResult.Success -> {
                                        setLoading(false, "Checked out: ${result.data}")
                                        log.info("[Jira:UI] Started work on ${selectedIssue.key}, existing branch: ${result.data}")
                                        currentWorkSection.refresh()
                                        loadData()
                                    }
                                    is ApiResult.Error -> {
                                        setLoading(false, "Start Work failed: ${result.message}")
                                        log.warn("[Jira:UI] Start Work failed for ${selectedIssue.key}: ${result.message}")
                                    }
                                }
                            }
                        }
                    } else {
                        setLoading(true, "Creating branch on Bitbucket\u2026")

                        scope.launch {
                            val result = branchingService.startWork(
                                issue = selectedIssue,
                                branchName = dialogResult.branchName,
                                sourceBranch = dialogResult.sourceBranch,
                                branchClient = finalBranchClient,
                                projectKey = finalProjectKey,
                                repoSlug = finalRepoSlug
                            )
                            withContext(Dispatchers.EDT) {
                                when (result) {
                                    is ApiResult.Success -> {
                                        setLoading(false, "Branch created: ${result.data}")
                                        log.info("[Jira:UI] Started work on ${selectedIssue.key}, branch: ${result.data}")
                                        if (!dialogResult.useExisting && dialogResult.sourceBranch.isNotBlank()) {
                                            val resolver = DefaultBranchResolver.getInstance(project)
                                            val repoPath = gitRepo?.root?.path ?: ""
                                            if (repoPath.isNotBlank()) {
                                                resolver.setOverride(repoPath, dialogResult.branchName, dialogResult.sourceBranch)
                                            }
                                        }
                                        currentWorkSection.refresh()
                                        loadData()
                                    }
                                    is ApiResult.Error -> {
                                        setLoading(false, "Start Work failed: ${result.message}")
                                        log.warn("[Jira:UI] Start Work failed for ${selectedIssue.key}: ${result.message}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun update(e: AnActionEvent) {
            val selected = ticketList.selectedValue
            e.presentation.isEnabled = selected != null && !isHeader(selected)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    // ---------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------

    override fun dispose() {
        scope.cancel()
    }

    companion object
}
