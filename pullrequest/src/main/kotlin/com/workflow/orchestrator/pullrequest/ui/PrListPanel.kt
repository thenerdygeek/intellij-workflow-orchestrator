package com.workflow.orchestrator.pullrequest.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.TimeFormatter
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Lightweight data class representing a PR in the list.
 * Decoupled from the Bitbucket DTO so the UI layer doesn't import :core DTOs directly.
 */
data class PrListItem(
    val id: Int,
    val title: String,
    val authorName: String,
    val status: String,
    val reviewerCount: Int,
    val updatedDate: Long,
    val fromBranch: String,
    val toBranch: String,
    /** true = section header (non-selectable), false = normal PR row */
    val isHeader: Boolean = false,
    val version: Int = 0
)

/**
 * Left panel in the PR dashboard showing a list of pull requests
 * split into "My Pull Requests" and "Reviewing" sections.
 */
class PrListPanel : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<PrListItem>()
    val prList = JBList(listModel).apply {
        cellRenderer = PrListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = JBUI.scale(62)
        border = JBUI.Borders.empty()
        isOpaque = false
    }

    private val emptyLabel = JBLabel("No pull requests found. Click Refresh to update.").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    var onPrSelected: ((prId: Int) -> Unit)? = null

    init {
        isOpaque = false
        background = JBColor.PanelBackground

        val scrollPane = JBScrollPane(prList).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }
        add(scrollPane, BorderLayout.CENTER)

        prList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = prList.selectedValue
                if (selected != null && !selected.isHeader) {
                    onPrSelected?.invoke(selected.id)
                }
            }
        }

        showEmpty()
    }

    fun showEmpty() {
        removeAll()
        add(emptyLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * Update the list with two sections. Pass empty lists for either section.
     * Uses incremental update to preserve scroll position and selection.
     */
    fun updatePrs(myPrs: List<PrListItem>, reviewingPrs: List<PrListItem>) {
        if (myPrs.isEmpty() && reviewingPrs.isEmpty()) {
            showEmpty()
            return
        }

        // Build the new items list
        val newItems = mutableListOf<PrListItem>()
        if (myPrs.isNotEmpty()) {
            newItems.add(PrListItem(
                id = -1, title = "My Pull Requests (${myPrs.size})", authorName = "",
                status = "", reviewerCount = 0, updatedDate = 0,
                fromBranch = "", toBranch = "", isHeader = true
            ))
            newItems.addAll(myPrs)
        }
        if (reviewingPrs.isNotEmpty()) {
            newItems.add(PrListItem(
                id = -2, title = "Reviewing (${reviewingPrs.size})", authorName = "",
                status = "", reviewerCount = 0, updatedDate = 0,
                fromBranch = "", toBranch = "", isHeader = true
            ))
            newItems.addAll(reviewingPrs)
        }

        // Check if data actually changed (compare by id + version to detect updates)
        val currentItems = (0 until listModel.size).map { listModel.getElementAt(it) }
        if (currentItems == newItems) return // No change — skip update

        // Save selection before update
        val selectedId = getSelectedPr()?.id

        // Ensure scroll pane is showing (not empty state)
        if (componentCount == 0 || getComponent(0) !is JBScrollPane) {
            removeAll()
            add(JBScrollPane(prList).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }, BorderLayout.CENTER)
        }

        listModel.clear()
        newItems.forEach { listModel.addElement(it) }

        // Restore selection
        if (selectedId != null) {
            for (i in 0 until listModel.size) {
                val item = listModel.getElementAt(i)
                if (!item.isHeader && item.id == selectedId) {
                    prList.selectedIndex = i
                    break
                }
            }
        }

        revalidate()
        repaint()
    }

    /**
     * Returns the currently selected PrListItem, or null if nothing is selected.
     */
    fun getSelectedPr(): PrListItem? {
        val selected = prList.selectedValue ?: return null
        return if (selected.isHeader) null else selected
    }

    // ---------------------------------------------------------------
    // Cell renderer
    // ---------------------------------------------------------------

    private class PrListCellRenderer : ListCellRenderer<PrListItem> {

        // Cached header cell components
        private val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 8, 4, 8)
        }
        private val headerLabel = JBLabel().apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            foreground = SECONDARY_TEXT
        }

        // Cached PR cell components
        private var selectedState = false
        private val prPanel = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(6, 8, 6, 8)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (selectedState) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = SELECTION_BG
                    g2.fill(RoundRectangle2D.Float(
                        JBUI.scale(2).toFloat(), 0f,
                        (width - JBUI.scale(4)).toFloat(), height.toFloat(),
                        JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
                    ))
                    g2.dispose()
                }
            }
        }
        private val topRow = JPanel(BorderLayout()).apply { isOpaque = false }
        private val topLeft = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
        private val idLabel = JBLabel().apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        }
        private val titleLabel = JBLabel().apply {
            font = font.deriveFont(JBUI.scale(12).toFloat())
            border = JBUI.Borders.emptyLeft(6)
        }
        private val statusBadgePanel = StatusBadgePanel()
        private val bottomRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(2)
        }
        private val authorLabel = JBLabel().apply {
            font = font.deriveFont(JBUI.scale(10).toFloat())
            foreground = SECONDARY_TEXT
        }
        private val branchLabel = JBLabel().apply {
            font = font.deriveFont(JBUI.scale(10).toFloat())
            foreground = BRANCH_TEXT
        }
        private val reviewerLabel = JBLabel().apply {
            font = font.deriveFont(JBUI.scale(10).toFloat())
            foreground = SECONDARY_TEXT
        }
        private val timeLabel = JBLabel().apply {
            font = font.deriveFont(JBUI.scale(10).toFloat())
            foreground = SECONDARY_TEXT
        }

        init {
            headerPanel.add(headerLabel, BorderLayout.WEST)

            topLeft.add(idLabel)
            topLeft.add(titleLabel)
            topRow.add(topLeft, BorderLayout.CENTER)
            topRow.add(statusBadgePanel, BorderLayout.EAST)
            prPanel.add(topRow, BorderLayout.NORTH)
            prPanel.add(bottomRow, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out PrListItem>,
            value: PrListItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value.isHeader) {
                headerLabel.text = value.title
                return headerPanel
            }

            selectedState = isSelected

            idLabel.text = "#${value.id}"
            idLabel.foreground = if (isSelected) JBColor.foreground() else LINK_COLOR

            titleLabel.text = truncate(value.title, 45)
            titleLabel.foreground = JBColor.foreground()
            titleLabel.toolTipText = if (value.title.length > 45) value.title else null

            statusBadgePanel.update(value.status)

            bottomRow.removeAll()
            authorLabel.text = value.authorName
            bottomRow.add(authorLabel)

            branchLabel.text = "${truncate(value.fromBranch, 20)} \u2192 ${truncate(value.toBranch, 20)}"
            branchLabel.toolTipText = if (value.fromBranch.length > 20 || value.toBranch.length > 20) {
                "${value.fromBranch} \u2192 ${value.toBranch}"
            } else null
            bottomRow.add(branchLabel)

            if (value.reviewerCount > 0) {
                reviewerLabel.text = "${value.reviewerCount} reviewers"
                bottomRow.add(reviewerLabel)
            }
            if (value.updatedDate > 0) {
                val (relativeText, absoluteText) = TimeFormatter.relativeWithTooltip(value.updatedDate)
                timeLabel.text = relativeText
                timeLabel.toolTipText = absoluteText
                bottomRow.add(timeLabel)
            }

            return prPanel
        }

        /** Cached status badge that repaints with updated color/text. */
        private class StatusBadgePanel : JPanel() {
            private var badgeColor: Color = SECONDARY_TEXT
            private var badgeText: String = ""

            init {
                isOpaque = false
            }

            fun update(status: String) {
                badgeText = status.uppercase()
                badgeColor = when (badgeText) {
                    "OPEN" -> STATUS_OPEN
                    "MERGED" -> STATUS_MERGED
                    "DECLINED" -> STATUS_DECLINED
                    else -> SECONDARY_TEXT
                }
                val fm = getFontMetrics(font.deriveFont(Font.BOLD, JBUI.scale(9).toFloat()))
                val textW = fm.stringWidth(badgeText)
                preferredSize = Dimension(textW + JBUI.scale(10), fm.height + JBUI.scale(4))
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (badgeText.isEmpty()) return
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val desktopHints = java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") as? Map<*, *>
                if (desktopHints != null) {
                    desktopHints.forEach { (k, v) -> if (k is java.awt.RenderingHints.Key && v != null) g2.setRenderingHint(k, v) }
                } else {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                }
                g2.color = badgeColor
                g2.fill(RoundRectangle2D.Float(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
                ))
                g2.color = JBColor.WHITE
                g2.font = font.deriveFont(Font.BOLD, JBUI.scale(9).toFloat())
                val fm = g2.fontMetrics
                val textX = (width - fm.stringWidth(badgeText)) / 2
                val textY = (height + fm.ascent - fm.descent) / 2
                g2.drawString(badgeText, textX, textY)
                g2.dispose()
            }
        }
    }

    companion object {
        private val SECONDARY_TEXT = StatusColors.SECONDARY_TEXT
        private val LINK_COLOR = StatusColors.LINK
        private val BRANCH_TEXT = StatusColors.SECONDARY_TEXT
        private val SELECTION_BG get() = UIManager.getColor("List.selectionBackground") ?: StatusColors.HIGHLIGHT_BG
        private val STATUS_OPEN = StatusColors.OPEN
        private val STATUS_MERGED = StatusColors.MERGED
        private val STATUS_DECLINED = StatusColors.DECLINED

        fun truncate(text: String, maxLength: Int): String {
            return if (text.length <= maxLength) text
            else text.substring(0, maxLength - 1) + "\u2026"
        }
    }
}
