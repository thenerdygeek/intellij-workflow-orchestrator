package com.workflow.orchestrator.handover.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.handover.model.HandoverState
import java.awt.*
import javax.swing.*

/**
 * Handover context sidebar panel styled after the Stitch mockup design.
 *
 * Vertical navigation with icon + label items, left border accent on active item,
 * tonal background architecture, and a bottom checklist section with colored dots.
 */
class HandoverContextPanel : JPanel(BorderLayout()) {

    // -- Tonal surface colors (matching Stitch mockup) --
    private val baseSurface = JBColor(0xF7F8FA, 0x0B1326)
    private val containerSurface = JBColor(0xEEF0F2, 0x171F33)
    private val accentColor = StatusColors.SUCCESS
    private val hoverSurface = JBColor(0xE8EBF0, 0x1C2740)
    private val activeBorder = StatusColors.SUCCESS

    // -- Data labels (updated reactively via updateState) --
    private val ticketIdLabel = JBLabel("").apply {
        font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scale(13))
        foreground = accentColor
    }
    private val ticketSummaryLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val ticketStatusLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(10).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val transitionComboBox = com.intellij.openapi.ui.ComboBox<String>()
    private val transitionButton = JButton("Transition").apply {
        isEnabled = false
        toolTipText = "Coming soon"
    }
    private val prStatusLabel = JBLabel("")
    private val buildStatusLabel = JBLabel("")
    private val qualityLabel = JBLabel("")
    private val dockerTagLabel = JBLabel("").apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(10))
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val suiteSectionPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val actionsSectionPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    // -- Navigation state --
    private var activeNavIndex = 0
    private val navItems = mutableListOf<NavItemPanel>()

    // -- Content cards (one per nav section, shown based on active nav) --
    private val sectionPanels = mutableListOf<JPanel>()

    init {
        isOpaque = true
        background = baseSurface
        buildLayout()
    }

    private fun buildLayout() {
        val mainPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        // -- Top: ticket header --
        val headerPanel = createTicketHeader()
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // -- Center: nav items + content area --
        val centerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        val navPanel = createNavPanel()
        centerPanel.add(navPanel, BorderLayout.CENTER)

        // -- Bottom: checklist section --
        val checklistPanel = createChecklistSection()
        centerPanel.add(checklistPanel, BorderLayout.SOUTH)

        mainPanel.add(centerPanel, BorderLayout.CENTER)

        val scrollPane = JBScrollPane(mainPanel).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scrollPane, BorderLayout.CENTER)

        // Select first nav item by default
        setActiveNav(0)
    }

    /**
     * Top section: ticket ID in green/monospace, summary in muted text,
     * plus a SYNC STATUS gradient button.
     */
    private fun createTicketHeader(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = baseSurface
            border = JBUI.Borders.empty(12, 12, 8, 12)

            // Ticket ID row
            ticketIdLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(ticketIdLabel)

            add(Box.createVerticalStrut(JBUI.scale(2)))

            // Ticket summary
            ticketSummaryLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(ticketSummaryLabel)

            add(Box.createVerticalStrut(JBUI.scale(4)))

            // Status
            ticketStatusLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(ticketStatusLabel)

            add(Box.createVerticalStrut(JBUI.scale(8)))

            // SYNC STATUS button with gradient
            val syncButton = createSyncStatusButton()
            syncButton.alignmentX = Component.LEFT_ALIGNMENT
            add(syncButton)

            add(Box.createVerticalStrut(JBUI.scale(8)))

            // Bottom separator using tonal shift instead of border
            val sep = JPanel().apply {
                isOpaque = true
                background = containerSurface
                preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(1))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(1))
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(sep)
        }
    }

    /**
     * SYNC STATUS button with gradient background (primary to primary-container).
     */
    private fun createSyncStatusButton(): JPanel {
        val gradientStart = JBColor(0x1A73E8, 0x004786)
        val gradientEnd = JBColor(0x1565C0, 0x005FB0)

        return object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(4, 12)

                val label = JBLabel("SYNC STATUS").apply {
                    font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                    foreground = JBColor(Color.WHITE, Color(0xE0, 0xE6, 0xF0))
                    horizontalAlignment = SwingConstants.CENTER
                }
                add(label, BorderLayout.CENTER)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val gp = GradientPaint(0f, 0f, gradientStart, width.toFloat(), 0f, gradientEnd)
                g2.paint = gp
                // Sharp/square borders (0px border-radius per Stitch mockup)
                g2.fillRect(0, 0, width, height)
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }

    /**
     * Navigation panel with icon + label items.
     * Active item has a 2px left border accent + highlighted background.
     */
    private fun createNavPanel(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
        }

        data class NavDef(val label: String, val icon: Icon, val detailLabel: JBLabel?, val detailPanel: JPanel?)

        val navDefs = listOf(
            NavDef("Context", AllIcons.General.Information, null, createContextDetailPanel()),
            NavDef("PR Details", AllIcons.Vcs.Merge, prStatusLabel, null),
            NavDef("Builds", AllIcons.Actions.Execute, buildStatusLabel, null),
            NavDef("Quality", AllIcons.General.InspectionsOK, qualityLabel, null),
            NavDef("Docker", AllIcons.Nodes.Deploy, dockerTagLabel, null),
            NavDef("Suites", AllIcons.RunConfigurations.TestState.Run, null, suiteSectionPanel)
        )

        navItems.clear()
        sectionPanels.clear()

        for ((index, def) in navDefs.withIndex()) {
            val navItem = NavItemPanel(def.label, def.icon, index)
            navItems.add(navItem)
            panel.add(navItem)

            // Create the detail content for each section
            val detailContent = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(4, 16, 8, 12)
                alignmentX = Component.LEFT_ALIGNMENT
                isVisible = false
            }

            if (def.detailLabel != null) {
                def.detailLabel.alignmentX = Component.LEFT_ALIGNMENT
                detailContent.add(def.detailLabel)
            }
            if (def.detailPanel != null) {
                def.detailPanel.alignmentX = Component.LEFT_ALIGNMENT
                detailContent.add(def.detailPanel)
            }

            sectionPanels.add(detailContent)
            panel.add(detailContent)
        }

        return panel
    }

    /**
     * Bottom checklist section with colored dots instead of checkmark icons.
     */
    private fun createChecklistSection(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = baseSurface
            border = JBUI.Borders.empty(8, 12, 12, 12)

            // Separator
            val sep = JPanel().apply {
                isOpaque = true
                background = containerSurface
                preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(1))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(1))
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(sep)

            add(Box.createVerticalStrut(JBUI.scale(8)))

            // Section header
            val header = JBLabel("CHECKLIST").apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(header)

            add(Box.createVerticalStrut(JBUI.scale(6)))

            actionsSectionPanel.alignmentX = Component.LEFT_ALIGNMENT
            add(actionsSectionPanel)
        }
    }

    /**
     * A single navigation item: icon + label, with left border accent when active.
     */
    private inner class NavItemPanel(
        private val label: String,
        private val icon: Icon,
        private val index: Int
    ) : JPanel(BorderLayout()) {

        private var isActive = false

        private val leftAccent = JPanel().apply {
            isOpaque = true
            preferredSize = Dimension(JBUI.scale(2), 0)
            background = baseSurface // initially hidden
        }

        private val iconLabel = JBLabel(icon).apply {
            border = JBUI.Borders.empty(0, 10, 0, 8)
        }

        private val textLabel = JBLabel(label).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
        }

        init {
            isOpaque = true
            background = baseSurface
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            alignmentX = Component.LEFT_ALIGNMENT

            add(leftAccent, BorderLayout.WEST)

            val center = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 0)
            }
            center.add(iconLabel)
            center.add(textLabel)
            add(center, BorderLayout.CENTER)

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    setActiveNav(index)
                }

                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    if (!isActive) {
                        background = hoverSurface
                    }
                }

                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    if (!isActive) {
                        background = baseSurface
                    }
                }
            })
        }

        fun setActive(active: Boolean) {
            isActive = active
            if (active) {
                leftAccent.background = activeBorder
                background = containerSurface
                textLabel.foreground = JBColor.foreground()
                textLabel.font = textLabel.font.deriveFont(Font.BOLD)
            } else {
                leftAccent.background = baseSurface
                background = baseSurface
                textLabel.foreground = StatusColors.SECONDARY_TEXT
                textLabel.font = textLabel.font.deriveFont(Font.PLAIN)
            }
            repaint()
        }
    }

    private fun setActiveNav(index: Int) {
        activeNavIndex = index
        for ((i, nav) in navItems.withIndex()) {
            nav.setActive(i == index)
        }
        for ((i, panel) in sectionPanels.withIndex()) {
            panel.isVisible = (i == index)
        }
        revalidate()
        repaint()
    }

    // =======================================================================
    // Public API (unchanged)
    // =======================================================================

    fun updateState(state: HandoverState) {
        // -- Ticket header --
        ticketIdLabel.text = state.ticketId.ifEmpty { "NO ACTIVE TICKET" }
        ticketIdLabel.foreground = if (state.ticketId.isEmpty()) StatusColors.SECONDARY_TEXT else accentColor
        ticketSummaryLabel.text = state.ticketSummary.uppercase()
        ticketStatusLabel.text = "Status: ${state.currentStatusName ?: "Unknown"}"

        // -- Context detail panel (mirrors header ticket info) --
        contextDetailLabels?.let { labels ->
            labels.ticketId.text = ticketIdLabel.text
            labels.ticketId.foreground = ticketIdLabel.foreground
            labels.summary.text = ticketSummaryLabel.text
            labels.status.text = ticketStatusLabel.text
        }

        // -- PR Details --
        prStatusLabel.text = if (state.prCreated) "PR created" else "No PR yet"
        prStatusLabel.icon = if (state.prCreated) AllIcons.General.InspectionsOK else null
        prStatusLabel.foreground = if (state.prCreated) StatusColors.SUCCESS else StatusColors.SECONDARY_TEXT
        prStatusLabel.font = prStatusLabel.font.deriveFont(JBUI.scale(11).toFloat())

        // -- Builds --
        buildStatusLabel.text = state.buildStatus?.let { build ->
            "${build.planKey} #${build.buildNumber} — ${build.status.name}"
        } ?: "No build data"
        buildStatusLabel.icon = when (state.buildStatus?.status) {
            WorkflowEvent.BuildEventStatus.SUCCESS -> AllIcons.General.InspectionsOK
            WorkflowEvent.BuildEventStatus.FAILED -> AllIcons.General.Error
            null -> null
        }
        buildStatusLabel.foreground = when (state.buildStatus?.status) {
            WorkflowEvent.BuildEventStatus.SUCCESS -> StatusColors.SUCCESS
            WorkflowEvent.BuildEventStatus.FAILED -> StatusColors.ERROR
            null -> StatusColors.SECONDARY_TEXT
        }
        buildStatusLabel.font = buildStatusLabel.font.deriveFont(JBUI.scale(11).toFloat())

        // -- Quality --
        qualityLabel.text = when (state.qualityGatePassed) {
            true -> "PASSED"
            false -> "FAILED"
            null -> "Unknown"
        }
        qualityLabel.icon = when (state.qualityGatePassed) {
            true -> AllIcons.General.InspectionsOK
            false -> AllIcons.General.Error
            null -> null
        }
        qualityLabel.foreground = when (state.qualityGatePassed) {
            true -> StatusColors.SUCCESS
            false -> StatusColors.ERROR
            null -> StatusColors.SECONDARY_TEXT
        }
        qualityLabel.font = qualityLabel.font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())

        // -- Docker tags --
        val latestSuite = state.suiteResults.lastOrNull()
        dockerTagLabel.text = latestSuite?.dockerTagsJson?.take(50) ?: "No docker tags"

        // -- Automation suites --
        suiteSectionPanel.removeAll()
        if (state.suiteResults.isEmpty()) {
            suiteSectionPanel.add(JBLabel("No suites run").apply {
                foreground = StatusColors.SECONDARY_TEXT
                font = font.deriveFont(JBUI.scale(11).toFloat())
            })
        } else {
            for (suite in state.suiteResults) {
                val icon = when (suite.passed) {
                    true -> AllIcons.General.InspectionsOK
                    false -> AllIcons.General.Error
                    null -> AllIcons.Process.Step_1
                }
                val color = when (suite.passed) {
                    true -> StatusColors.SUCCESS
                    false -> StatusColors.ERROR
                    null -> StatusColors.WARNING
                }
                val statusText = when (suite.passed) {
                    true -> "PASS"
                    false -> "FAIL"
                    null -> "running"
                }
                suiteSectionPanel.add(JBLabel("${suite.suitePlanKey}: $statusText", icon, SwingConstants.LEFT).apply {
                    foreground = color
                    font = font.deriveFont(JBUI.scale(11).toFloat())
                    border = JBUI.Borders.emptyBottom(2)
                })
            }
        }

        // -- Checklist (colored dots instead of checkmark icons) --
        actionsSectionPanel.removeAll()
        actionsSectionPanel.add(checklistDotItem("Copyright fixed", state.copyrightFixed))
        actionsSectionPanel.add(checklistDotItem("PR created", state.prCreated))
        actionsSectionPanel.add(checklistDotItem("Jira comment", state.jiraCommentPosted))
        actionsSectionPanel.add(checklistDotItem("Jira transitioned", state.jiraTransitioned))
        actionsSectionPanel.add(checklistDotItem("Time logged", state.todayWorkLogged))

        revalidate()
        repaint()
    }

    fun setTransitions(transitions: List<String>) {
        transitionComboBox.removeAllItems()
        transitions.forEach { transitionComboBox.addItem(it) }
    }

    fun getSelectedTransition(): String? {
        return transitionComboBox.selectedItem as? String
    }

    // =======================================================================
    // Context detail panel (wired to Context nav item)
    // =======================================================================

    /**
     * Creates the detail panel for the "Context" nav item, showing ticket info
     * (ID, summary, status) and the transition controls.
     */
    private fun createContextDetailPanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false

            // Ticket ID
            val idCopy = JBLabel().apply {
                font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scale(13))
                foreground = accentColor
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(idCopy)
            add(Box.createVerticalStrut(JBUI.scale(4)))

            // Ticket summary
            val summaryCopy = JBLabel().apply {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(summaryCopy)
            add(Box.createVerticalStrut(JBUI.scale(4)))

            // Ticket status
            val statusCopy = JBLabel().apply {
                font = font.deriveFont(JBUI.scale(11).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(statusCopy)
            add(Box.createVerticalStrut(JBUI.scale(8)))

            // Transition controls
            val transitionRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            }
            transitionRow.add(transitionComboBox)
            transitionRow.add(transitionButton)
            add(transitionRow)

            // Bind these labels to the shared data labels so they stay in sync
            contextDetailLabels = ContextDetailLabels(idCopy, summaryCopy, statusCopy)
        }
    }

    /** Labels inside the Context nav detail panel, kept in sync with header labels. */
    private data class ContextDetailLabels(
        val ticketId: JBLabel,
        val summary: JBLabel,
        val status: JBLabel
    )

    private var contextDetailLabels: ContextDetailLabels? = null

    // =======================================================================
    // Private helpers
    // =======================================================================

    /**
     * Checklist item with a small colored dot instead of a checkmark icon.
     * Green dot for done, dim grey dot for pending.
     */
    private fun checklistDotItem(text: String, done: Boolean): JPanel {
        val dotColor = if (done) StatusColors.SUCCESS else StatusColors.SECONDARY_TEXT
        val textColor = if (done) JBColor.foreground() else StatusColors.SECONDARY_TEXT

        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
            border = JBUI.Borders.emptyBottom(1)

            // Colored dot
            add(object : JPanel() {
                init {
                    isOpaque = false
                    preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = dotColor
                    val dotSize = JBUI.scale(6)
                    val x = (width - dotSize) / 2
                    val y = (height - dotSize) / 2
                    g2.fillOval(x, y, dotSize, dotSize)
                    g2.dispose()
                }
            })

            // Label text
            add(JBLabel(text).apply {
                foreground = textColor
                font = font.deriveFont(JBUI.scale(11).toFloat())
                border = JBUI.Borders.emptyLeft(4)
            })
        }
    }
}
