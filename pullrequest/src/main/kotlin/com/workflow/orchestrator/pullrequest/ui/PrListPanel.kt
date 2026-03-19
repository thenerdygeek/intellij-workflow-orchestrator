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

        override fun getListCellRendererComponent(
            list: JList<out PrListItem>,
            value: PrListItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value.isHeader) {
                return createHeaderCell(value)
            }
            return createPrCell(value, isSelected)
        }

        private fun createHeaderCell(item: PrListItem): JPanel {
            return JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(8, 8, 4, 8)
                val label = JBLabel(item.title).apply {
                    font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                    foreground = SECONDARY_TEXT
                }
                add(label, BorderLayout.WEST)
            }
        }

        private fun createPrCell(item: PrListItem, isSelected: Boolean): JPanel {
            return object : JPanel(BorderLayout()) {
                init {
                    isOpaque = false
                    border = JBUI.Borders.empty(6, 8, 6, 8)
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    if (isSelected) {
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
            }.apply {
                // Top row: PR # + title
                val topRow = JPanel(BorderLayout()).apply {
                    isOpaque = false
                }
                val idLabel = JBLabel("#${item.id}").apply {
                    font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
                    foreground = if (isSelected) JBColor.foreground() else LINK_COLOR
                }
                val titleLabel = JBLabel(truncate(item.title, 45)).apply {
                    font = font.deriveFont(JBUI.scale(12).toFloat())
                    foreground = JBColor.foreground()
                    border = JBUI.Borders.emptyLeft(6)
                    if (item.title.length > 45) toolTipText = item.title
                }
                val topLeft = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(idLabel)
                    add(titleLabel)
                }
                topRow.add(topLeft, BorderLayout.CENTER)

                // Status badge
                topRow.add(createStatusBadge(item.status), BorderLayout.EAST)
                add(topRow, BorderLayout.NORTH)

                // Bottom row: author, branch -> branch, time
                val bottomRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyTop(2)
                }
                bottomRow.add(JBLabel(item.authorName).apply {
                    font = font.deriveFont(JBUI.scale(10).toFloat())
                    foreground = SECONDARY_TEXT
                })
                bottomRow.add(JBLabel("${truncate(item.fromBranch, 20)} \u2192 ${truncate(item.toBranch, 20)}").apply {
                    font = font.deriveFont(JBUI.scale(10).toFloat())
                    foreground = BRANCH_TEXT
                    if (item.fromBranch.length > 20 || item.toBranch.length > 20) {
                        toolTipText = "${item.fromBranch} \u2192 ${item.toBranch}"
                    }
                })
                if (item.reviewerCount > 0) {
                    bottomRow.add(JBLabel("${item.reviewerCount} reviewers").apply {
                        font = font.deriveFont(JBUI.scale(10).toFloat())
                        foreground = SECONDARY_TEXT
                    })
                }
                if (item.updatedDate > 0) {
                    val (relativeText, absoluteText) = TimeFormatter.relativeWithTooltip(item.updatedDate)
                    bottomRow.add(JBLabel(relativeText).apply {
                        font = font.deriveFont(JBUI.scale(10).toFloat())
                        foreground = SECONDARY_TEXT
                        toolTipText = absoluteText
                    })
                }
                add(bottomRow, BorderLayout.CENTER)
            }
        }

        private fun createStatusBadge(status: String): JPanel {
            val color = when (status.uppercase()) {
                "OPEN" -> STATUS_OPEN
                "MERGED" -> STATUS_MERGED
                "DECLINED" -> STATUS_DECLINED
                else -> SECONDARY_TEXT
            }
            val text = status.uppercase()

            return object : JPanel() {
                init {
                    isOpaque = false
                    val fm = getFontMetrics(font.deriveFont(Font.BOLD, JBUI.scale(9).toFloat()))
                    val textW = fm.stringWidth(text)
                    preferredSize = Dimension(
                        textW + JBUI.scale(10),
                        fm.height + JBUI.scale(4)
                    )
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
                    g2.color = color
                    g2.fill(RoundRectangle2D.Float(
                        0f, 0f, width.toFloat(), height.toFloat(),
                        JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
                    ))
                    g2.color = JBColor.WHITE
                    g2.font = font.deriveFont(Font.BOLD, JBUI.scale(9).toFloat())
                    val fm = g2.fontMetrics
                    val textX = (width - fm.stringWidth(text)) / 2
                    val textY = (height + fm.ascent - fm.descent) / 2
                    g2.drawString(text, textX, textY)
                    g2.dispose()
                }
            }
        }
    }

    companion object {
        private val SECONDARY_TEXT = StatusColors.SECONDARY_TEXT
        private val LINK_COLOR = StatusColors.LINK
        private val BRANCH_TEXT = JBColor(0x656D76, 0x768390)
        private val SELECTION_BG = JBColor(0xDEE9FC, 0x2D3548)
        private val STATUS_OPEN = StatusColors.OPEN
        private val STATUS_MERGED = StatusColors.MERGED
        private val STATUS_DECLINED = StatusColors.DECLINED

        fun truncate(text: String, maxLength: Int): String {
            return if (text.length <= maxLength) text
            else text.substring(0, maxLength - 1) + "\u2026"
        }
    }
}
