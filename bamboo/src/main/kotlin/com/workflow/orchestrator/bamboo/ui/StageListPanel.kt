package com.workflow.orchestrator.bamboo.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.TimeFormatter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.model.StageState
import java.awt.*
import javax.swing.*

class StageListPanel : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<StageState>()
    val stageList = JBList(listModel).apply {
        cellRenderer = StageListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    var onRunStage: ((StageState) -> Unit)? = null

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val emptyLabel = JBLabel("No stages found.").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    init {
        border = JBUI.Borders.empty()
        cardPanel.add(JBScrollPane(stageList), "list")
        cardPanel.add(emptyLabel, "empty")
        cardLayout.show(cardPanel, "empty")
        add(cardPanel, BorderLayout.CENTER)

        // Double-click on manual stage triggers run — gated by StageRunnabilityPolicy
        // (PR 7 audit P1 #2). Greyed-out rows ignore the double-click and surface a
        // tooltip pointing at the first prior stage that needs to finish.
        stageList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val index = stageList.locationToIndex(e.point)
                    if (index >= 0) {
                        val stage = listModel.getElementAt(index)
                        val allStages = (0 until listModel.size()).map { listModel.getElementAt(it) }
                        if (StageRunnabilityPolicy.isNextRunnable(allStages, stage)) {
                            onRunStage?.invoke(stage)
                        }
                    }
                }
            }
        })

        // Tooltip surface: when the cursor is over a non-runnable manual stage,
        // explain WHY it's not runnable (which prior stage blocks it).
        stageList.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val index = stageList.locationToIndex(e.point)
                if (index < 0) {
                    stageList.toolTipText = null
                    return
                }
                val stage = listModel.getElementAt(index)
                if (StageRunnabilityPolicy.isHeader(stage) || !stage.manual) {
                    stageList.toolTipText = null
                    return
                }
                val allStages = (0 until listModel.size()).map { listModel.getElementAt(it) }
                if (StageRunnabilityPolicy.isNextRunnable(allStages, stage)) {
                    stageList.toolTipText = "Double-click to run"
                } else {
                    val blocker = StageRunnabilityPolicy.firstBlockingStage(allStages, stage)
                    stageList.toolTipText = if (blocker != null) {
                        "Run prior stages first (Stage \"$blocker\")"
                    } else {
                        "Stage cannot be run right now"
                    }
                }
            }
        })
    }

    /** Delegates to [StageRunnabilityPolicy.isHeader] — kept private for the
     *  renderer's existing call sites. */
    private fun isHeader(state: StageState): Boolean = StageRunnabilityPolicy.isHeader(state)

    fun updateStages(stages: List<StageState>) {
        val selectedIndex = stageList.selectedIndex
        listModel.clear()

        if (stages.isEmpty()) {
            cardLayout.show(cardPanel, "empty")
            return
        }

        cardLayout.show(cardPanel, "list")

        // Insert stage headers before jobs, grouped by stageName
        var currentStageName = ""
        for (stage in stages) {
            if (stage.stageName.isNotBlank() && stage.stageName != currentStageName) {
                currentStageName = stage.stageName
                // Add a header item
                listModel.addElement(StageState(
                    name = "§${stage.stageName}",
                    status = BuildStatus.UNKNOWN,
                    manual = false,
                    durationMs = null,
                    stageName = stage.stageName
                ))
            }
            listModel.addElement(stage)
        }

        if (selectedIndex in 0 until listModel.size()) {
            stageList.selectedIndex = selectedIndex
        }
    }

    /**
     * Stitch design: uppercase group headers with line extending right,
     * left border accent on job items colored by status, monospace job names.
     */
    private inner class StageListCellRenderer : ColoredListCellRenderer<StageState>() {

        private val spinnerIcon = AnimatedIcon.Default()
        private val headerAttributes = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD,
            StatusColors.SECONDARY_TEXT
        )

        override fun customizeCellRenderer(
            list: JList<out StageState>,
            value: StageState?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            value ?: return

            // Stage header (non-selectable group label) — uppercase, 10pt bold, SECONDARY_TEXT
            if (value.name.startsWith("§")) {
                border = JBUI.Borders.empty(8, 4, 2, 4)
                icon = null
                val headerText = value.name.removePrefix("§").uppercase()
                append(headerText, headerAttributes)
                // The line extending right is rendered via a custom bottom border tint
                // using tonal background shift (the header row itself acts as a separator)
                return
            }

            // Job item — left border accent colored by status
            val statusColor = when (value.status) {
                BuildStatus.SUCCESS -> StatusColors.SUCCESS
                BuildStatus.FAILED -> StatusColors.ERROR
                BuildStatus.IN_PROGRESS -> StatusColors.WARNING
                BuildStatus.PENDING -> StatusColors.SECONDARY_TEXT
                BuildStatus.UNKNOWN -> StatusColors.SECONDARY_TEXT
            }

            border = javax.swing.border.CompoundBorder(
                StitchLeftAccentBorder(statusColor, JBUI.scale(3)),
                JBUI.Borders.empty(4, 12, 4, 4)
            )

            icon = when (value.status) {
                BuildStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
                BuildStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
                BuildStatus.IN_PROGRESS -> spinnerIcon
                BuildStatus.PENDING -> AllIcons.RunConfigurations.TestNotRan
                BuildStatus.UNKNOWN -> AllIcons.RunConfigurations.TestNotRan
            }

            // Job name in monospace bold
            append(value.name, SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD,
                null
            ))

            // Duration
            val duration = value.durationMs?.let { TimeFormatter.formatDurationMillis(it) } ?: "--"
            append("  $duration", SimpleTextAttributes.GRAYED_ATTRIBUTES)

            // Manual indicator — link-style when the stage is the next runnable
            // step, grayed when blocked by prior stages (PR 7 audit P1 #2).
            if (value.manual && value.status != BuildStatus.IN_PROGRESS) {
                val allStages = (0 until listModel.size()).map { listModel.getElementAt(it) }
                val runnable = StageRunnabilityPolicy.isNextRunnable(allStages, value)
                val attrs = if (runnable) SimpleTextAttributes.LINK_ATTRIBUTES
                            else SimpleTextAttributes.GRAYED_ATTRIBUTES
                append("  [Run]", attrs)
            }
        }
    }
}
