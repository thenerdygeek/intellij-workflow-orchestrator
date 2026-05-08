package com.workflow.orchestrator.handover.ui.tabs

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.handover.model.BuildSummary
import com.workflow.orchestrator.handover.model.HandoverState
import com.workflow.orchestrator.handover.model.SuiteResult
import java.awt.*
import javax.swing.*

/**
 * First tab of the redesigned Handover panel.
 *
 * Contains two cards stacked vertically:
 *   1. Pre-handoff status checks — 8-row grid (icon + label + meta).
 *   2. Ritual checklist — 4 items with colored dot + DONE/PENDING badge.
 *
 * The tab does NOT subscribe to HandoverStateService.stateFlow itself.
 * The parent panel collects once and fans state out via [updateState].
 */
class ChecksTab(private val project: Project) : JPanel(BorderLayout()), Disposable {

    // -----------------------------------------------------------------------
    // Status row labels (meta column, updated on each updateState call)
    // -----------------------------------------------------------------------
    private val rowMeta = Array(8) {
        JBLabel("—").apply {
            font = font.deriveFont(JBUI.scale(11).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
        }
    }
    private val rowStatus = Array(8) {
        JBLabel("").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
        }
    }

    // -----------------------------------------------------------------------
    // Checklist row panels (rebuilt on each updateState)
    // -----------------------------------------------------------------------
    private val checklistPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    // Row indices — kept as constants so the mapping is explicit and testable.
    companion object {
        private const val ROW_COPYRIGHT = 0
        private const val ROW_PR = 1
        private const val ROW_BUILD = 2
        private const val ROW_QUALITY = 3
        private const val ROW_SUITE_API_SMOKE = 4
        private const val ROW_SUITE_API_INT = 5
        private const val ROW_SUITE_WEB_E2E = 6
        private const val ROW_DOCKER = 7

        private val ROW_LABELS = arrayOf(
            "Copyright headers",
            "Pull request",
            "Build",
            "Quality gate",
            "Suite: API smoke",
            "Suite: API integration",
            "Suite: Web E2E",
            "Docker tags"
        )

        private val CHECKLIST_LABELS = arrayOf(
            "Copyright fixed",
            "PR created",
            "Jira comment posted",
            "Time logged"
        )
    }

    // -----------------------------------------------------------------------
    // Card panels (built once in init)
    // -----------------------------------------------------------------------
    private val statusGridPanel: JPanel
    private val statusCard: JPanel
    private val checklistCard: JPanel

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8)

        statusGridPanel = buildStatusGrid()
        statusCard = wrapCard("Pre-handoff status checks", statusGridPanel)
        checklistCard = wrapCard("Ritual checklist", checklistPanel)

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(statusCard)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(checklistCard)
        }
        add(content, BorderLayout.NORTH)
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Update both cards from canonical state. Safe to call on EDT. */
    fun updateState(state: HandoverState) {
        applyCopyright(state)
        applyPr(state)
        applyBuild(state)
        applyQuality(state)
        applySuites(state)
        applyDocker(state)

        rebuildChecklist(state)

        revalidate()
        repaint()
    }

    override fun dispose() {
        // No coroutines or external resources owned here; parent disposes children.
    }

    // -----------------------------------------------------------------------
    // State applicators
    // -----------------------------------------------------------------------

    private fun applyCopyright(state: HandoverState) {
        if (state.copyrightFixed) {
            setRow(ROW_COPYRIGHT, ok = true, status = "OK", meta = "fixed")
        } else {
            setRow(ROW_COPYRIGHT, ok = null, status = "—", meta = "—", color = StatusColors.SECONDARY_TEXT)
        }
    }

    private fun applyPr(state: HandoverState) {
        if (state.prCreated) {
            val meta = if (!state.prUrl.isNullOrBlank()) state.prUrl else "created"
            setRow(ROW_PR, ok = true, status = "OK", meta = meta)
        } else {
            setRow(ROW_PR, ok = null, status = "—", meta = "—", color = StatusColors.SECONDARY_TEXT)
        }
    }

    private fun applyBuild(state: HandoverState) {
        val build = state.buildStatus
        if (build == null) {
            setRow(ROW_BUILD, ok = null, status = "—", meta = "—", color = StatusColors.SECONDARY_TEXT)
            return
        }
        val passed = build.status == WorkflowEvent.BuildEventStatus.SUCCESS
        val meta = "${build.planKey} #${build.buildNumber}"
        if (passed) {
            setRow(ROW_BUILD, ok = true, status = "OK", meta = meta)
        } else {
            setRow(ROW_BUILD, ok = false, status = "FAIL", meta = meta)
        }
    }

    private fun applyQuality(state: HandoverState) {
        when (state.qualityGatePassed) {
            true -> setRow(ROW_QUALITY, ok = true, status = "OK", meta = "PASSED")
            false -> setRow(ROW_QUALITY, ok = false, status = "FAIL", meta = "FAILED")
            null -> setRow(ROW_QUALITY, ok = null, status = "WARN", meta = "Unknown", color = StatusColors.WARNING)
        }
    }

    private fun applySuites(state: HandoverState) {
        val suites = state.suiteResults
        applyOneSuite(ROW_SUITE_API_SMOKE, suites.find { s ->
            s.suitePlanKey.contains("API-SMOKE", ignoreCase = true) ||
                s.suitePlanKey.endsWith("APISMOKE", ignoreCase = true)
        })
        applyOneSuite(ROW_SUITE_API_INT, suites.find { s ->
            s.suitePlanKey.contains("API-INT", ignoreCase = true) ||
                s.suitePlanKey.contains("APIINT", ignoreCase = true) ||
                s.suitePlanKey.contains("API-INTEGRATION", ignoreCase = true)
        })
        applyOneSuite(ROW_SUITE_WEB_E2E, suites.find { s ->
            s.suitePlanKey.contains("WEB-E2E", ignoreCase = true) ||
                s.suitePlanKey.contains("WEBE2E", ignoreCase = true) ||
                s.suitePlanKey.contains("E2E", ignoreCase = true)
        })
    }

    private fun applyOneSuite(rowIdx: Int, suite: SuiteResult?) {
        if (suite == null) {
            setRow(rowIdx, ok = null, status = "—", meta = "—", color = StatusColors.SECONDARY_TEXT)
            return
        }
        when (suite.passed) {
            true -> setRow(rowIdx, ok = true, status = "PASS", meta = suite.buildResultKey.ifBlank { "OK" })
            false -> setRow(rowIdx, ok = false, status = "FAIL", meta = suite.buildResultKey.ifBlank { "FAIL" })
            null -> setRow(rowIdx, ok = null, status = "WARN", meta = "running", color = StatusColors.WARNING)
        }
    }

    private fun applyDocker(state: HandoverState) {
        val dockerJson = state.suiteResults.lastOrNull()?.dockerTagsJson
        if (dockerJson.isNullOrBlank()) {
            setRow(ROW_DOCKER, ok = null, status = "—", meta = "—", color = StatusColors.SECONDARY_TEXT)
            return
        }
        // Count keys in JSON by counting `"key":` occurrences (lightweight heuristic)
        val repoCount = dockerJson.split("\":").size - 1
        val meta = if (repoCount > 0) "$repoCount repos" else "present"
        setRow(ROW_DOCKER, ok = true, status = "OK", meta = meta)
    }

    // -----------------------------------------------------------------------
    // Row setter
    // -----------------------------------------------------------------------

    /**
     * ok=true → SUCCESS green; ok=false → ERROR red; ok=null → use [color] (default SECONDARY_TEXT).
     */
    private fun setRow(
        rowIdx: Int,
        ok: Boolean?,
        status: String,
        meta: String,
        color: JBColor = StatusColors.SECONDARY_TEXT
    ) {
        val statusColor = when (ok) {
            true -> StatusColors.SUCCESS
            false -> StatusColors.ERROR
            null -> color
        }
        rowStatus[rowIdx].text = status
        rowStatus[rowIdx].foreground = statusColor
        rowMeta[rowIdx].text = meta
    }

    // -----------------------------------------------------------------------
    // Checklist
    // -----------------------------------------------------------------------

    private fun rebuildChecklist(state: HandoverState) {
        checklistPanel.removeAll()
        val doneFlags = booleanArrayOf(
            state.copyrightFixed,
            state.prCreated,
            state.jiraCommentPosted,
            state.todayWorkLogged
        )
        CHECKLIST_LABELS.forEachIndexed { i, label ->
            checklistPanel.add(checklistItem(label, doneFlags[i]))
            checklistPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        }
    }

    /**
     * One checklist row: dot (8px) + label + "DONE" / "PENDING" badge.
     *
     * Done dot: filled [StatusColors.SUCCESS].
     * Pending dot: outlined, transparent center, color [StatusColors.SECONDARY_TEXT].
     */
    private fun checklistItem(label: String, done: Boolean): JPanel {
        val dotColor = if (done) StatusColors.SUCCESS else StatusColors.SECONDARY_TEXT
        val badgeText = if (done) "DONE" else "PENDING"
        val badgeColor = if (done) StatusColors.SUCCESS else StatusColors.SECONDARY_TEXT

        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(22))

            // Dot widget
            add(object : JPanel() {
                init {
                    isOpaque = false
                    preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val dotSize = JBUI.scale(8)
                    val x = (width - dotSize) / 2
                    val y = (height - dotSize) / 2
                    if (done) {
                        g2.color = dotColor
                        g2.fillOval(x, y, dotSize, dotSize)
                    } else {
                        g2.color = dotColor
                        g2.stroke = BasicStroke(1.5f)
                        g2.drawOval(x, y, dotSize - 1, dotSize - 1)
                    }
                    g2.dispose()
                }
            })

            // Label
            add(JBLabel(label).apply {
                foreground = if (done) JBColor.foreground() else StatusColors.SECONDARY_TEXT
                font = font.deriveFont(JBUI.scale(11).toFloat())
                border = JBUI.Borders.emptyLeft(6)
            })

            // Badge
            add(JBLabel(badgeText).apply {
                foreground = badgeColor
                font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                border = JBUI.Borders.emptyLeft(6)
            })
        }
    }

    // -----------------------------------------------------------------------
    // Layout helpers
    // -----------------------------------------------------------------------

    private fun buildStatusGrid(): JPanel {
        val grid = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(JBUI.scale(2), JBUI.scale(4), JBUI.scale(2), JBUI.scale(4))
            anchor = GridBagConstraints.WEST
        }

        ROW_LABELS.forEachIndexed { i, label ->
            // Col 0: row label
            gbc.gridx = 0; gbc.gridy = i; gbc.weightx = 0.5
            grid.add(JBLabel(label).apply {
                font = font.deriveFont(JBUI.scale(11).toFloat())
                foreground = JBColor.foreground()
            }, gbc)

            // Col 1: status badge
            gbc.gridx = 1; gbc.weightx = 0.25
            grid.add(rowStatus[i], gbc)

            // Col 2: meta
            gbc.gridx = 2; gbc.weightx = 0.25
            grid.add(rowMeta[i], gbc)
        }
        return grid
    }

    private fun wrapCard(title: String, content: JPanel): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = StatusColors.CARD_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(StatusColors.BORDER, 1),
                JBUI.Borders.empty(8)
            )

            add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
                foreground = JBColor.foreground()
                border = JBUI.Borders.emptyBottom(6)
            }, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
    }
}
