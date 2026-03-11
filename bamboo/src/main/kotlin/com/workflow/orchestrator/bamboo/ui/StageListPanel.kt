package com.workflow.orchestrator.bamboo.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.model.StageState
import java.awt.BorderLayout
import javax.swing.*

class StageListPanel : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<StageState>()
    val stageList = JBList(listModel).apply {
        cellRenderer = StageListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    var onRunStage: ((StageState) -> Unit)? = null

    init {
        border = JBUI.Borders.empty()
        add(JBScrollPane(stageList), BorderLayout.CENTER)

        // Double-click on manual stage triggers run
        stageList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val index = stageList.locationToIndex(e.point)
                    if (index >= 0) {
                        val stage = listModel.getElementAt(index)
                        if (stage.manual && stage.status != BuildStatus.IN_PROGRESS) {
                            onRunStage?.invoke(stage)
                        }
                    }
                }
            }
        })
    }

    fun updateStages(stages: List<StageState>) {
        val selectedIndex = stageList.selectedIndex
        listModel.clear()
        stages.forEach { listModel.addElement(it) }
        if (selectedIndex in 0 until listModel.size()) {
            stageList.selectedIndex = selectedIndex
        }
    }

    private inner class StageListCellRenderer : ColoredListCellRenderer<StageState>() {

        private val spinnerIcon = AnimatedIcon.Default()

        override fun customizeCellRenderer(
            list: JList<out StageState>,
            value: StageState?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            value ?: return
            border = JBUI.Borders.empty(4, 8)

            icon = when (value.status) {
                BuildStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
                BuildStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
                BuildStatus.IN_PROGRESS -> spinnerIcon
                BuildStatus.PENDING -> AllIcons.RunConfigurations.TestNotRan
                BuildStatus.UNKNOWN -> AllIcons.RunConfigurations.TestNotRan
            }

            append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Duration
            val duration = value.durationMs?.let { formatDuration(it) } ?: "--"
            append("  $duration", SimpleTextAttributes.GRAYED_ATTRIBUTES)

            // Manual indicator
            if (value.manual && value.status != BuildStatus.IN_PROGRESS) {
                append("  [Run]", SimpleTextAttributes.LINK_ATTRIBUTES)
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
