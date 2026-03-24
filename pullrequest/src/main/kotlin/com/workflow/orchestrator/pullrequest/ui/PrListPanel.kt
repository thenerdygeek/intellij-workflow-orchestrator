package com.workflow.orchestrator.pullrequest.ui

import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.TimeFormatter
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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
    val version: Int = 0,
    /** Source repo name — displayed as badge when multiple repos are configured */
    val repoName: String = ""
)

/**
 * Left panel in the PR dashboard showing a list of pull requests
 * split into "My Pull Requests" and "Reviewing" sections.
 */
class PrListPanel : JPanel(BorderLayout()) {

    private var allItems: List<PrListItem> = emptyList()
    private var filterDebounceTimer: Timer? = null
    /** When true, repo badge is shown on each PR row (set by dashboard when multiple repos configured) */
    var showRepoBadge: Boolean = false

    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Filter by title, author, or branch..."
        addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleFilter()
            override fun removeUpdate(e: DocumentEvent) = scheduleFilter()
            override fun changedUpdate(e: DocumentEvent) = scheduleFilter()
        })
    }

    private fun scheduleFilter() {
        filterDebounceTimer?.stop()
        filterDebounceTimer = Timer(250) { applyFilter(searchField.text.orEmpty()) }.apply {
            isRepeats = false
            start()
        }
    }

    private val listModel = DefaultListModel<PrListItem>()
    val prList = JBList(listModel).apply {
        cellRenderer = PrListCellRenderer { showRepoBadge }
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

        val searchPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 4, 8)
            add(searchField, BorderLayout.CENTER)
        }
        add(searchPanel, BorderLayout.NORTH)

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
        allItems = emptyList()
        // Keep search panel in NORTH, replace CENTER with empty label
        val centerComponent = (layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
        if (centerComponent != null) remove(centerComponent)
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

        // Store all items for filtering
        allItems = newItems

        // Ensure scroll pane is showing (not empty state)
        val centerComponent = (layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
        if (centerComponent !is JBScrollPane) {
            if (centerComponent != null) remove(centerComponent)
            add(JBScrollPane(prList).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }, BorderLayout.CENTER)
        }

        applyFilter(searchField.text.orEmpty())
    }

    private fun applyFilter(text: String) {
        val filtered = if (text.isBlank()) {
            allItems
        } else {
            val result = mutableListOf<PrListItem>()
            var lastHeader: PrListItem? = null
            for (item in allItems) {
                if (item.isHeader) {
                    lastHeader = item
                } else if (item.title.contains(text, ignoreCase = true)
                    || item.authorName.contains(text, ignoreCase = true)
                    || item.fromBranch.contains(text, ignoreCase = true)
                    || item.toBranch.contains(text, ignoreCase = true)
                    || item.repoName.contains(text, ignoreCase = true)
                ) {
                    if (lastHeader != null) {
                        result.add(lastHeader)
                        lastHeader = null
                    }
                    result.add(item)
                }
            }
            result
        }

        // Check if data actually changed
        val currentItems = (0 until listModel.size).map { listModel.getElementAt(it) }
        if (currentItems == filtered) return

        // Save selection before update
        val selectedId = getSelectedPr()?.id

        listModel.clear()
        filtered.forEach { listModel.addElement(it) }

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

    private class PrListCellRenderer(private val showRepoBadgeProvider: () -> Boolean) : ListCellRenderer<PrListItem> {

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
        private val repoBadgePanel = RepoBadgePanel()
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
        private var bottomRowInitialized = false

        init {
            headerPanel.add(headerLabel, BorderLayout.WEST)

            topLeft.add(repoBadgePanel)
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

            // Repo badge — only visible when multiple repos configured
            if (showRepoBadgeProvider() && value.repoName.isNotBlank()) {
                repoBadgePanel.update(value.repoName)
                repoBadgePanel.isVisible = true
            } else {
                repoBadgePanel.isVisible = false
            }

            idLabel.text = "#${value.id}"
            idLabel.foreground = if (isSelected) JBColor.foreground() else LINK_COLOR

            titleLabel.text = truncate(value.title, 45)
            titleLabel.foreground = JBColor.foreground()
            titleLabel.toolTipText = if (value.title.length > 45) value.title else null

            statusBadgePanel.update(value.status)

            // Add all labels once, then toggle visibility instead of removeAll/add
            if (!bottomRowInitialized) {
                bottomRow.add(authorLabel)
                bottomRow.add(branchLabel)
                bottomRow.add(reviewerLabel)
                bottomRow.add(timeLabel)
                bottomRowInitialized = true
            }

            authorLabel.text = value.authorName
            authorLabel.isVisible = true

            branchLabel.text = "${truncate(value.fromBranch, 20)} \u2192 ${truncate(value.toBranch, 20)}"
            branchLabel.toolTipText = if (value.fromBranch.length > 20 || value.toBranch.length > 20) {
                "${value.fromBranch} \u2192 ${value.toBranch}"
            } else null
            branchLabel.isVisible = true

            if (value.reviewerCount > 0) {
                reviewerLabel.text = "${value.reviewerCount} reviewers"
                reviewerLabel.isVisible = true
            } else {
                reviewerLabel.isVisible = false
            }
            if (value.updatedDate > 0) {
                val (relativeText, absoluteText) = TimeFormatter.relativeWithTooltip(value.updatedDate)
                timeLabel.text = relativeText
                timeLabel.toolTipText = absoluteText
                timeLabel.isVisible = true
            } else {
                timeLabel.isVisible = false
            }

            return prPanel
        }

        /** Repo name badge shown when multiple repos are configured. */
        private class RepoBadgePanel : JPanel() {
            private var badgeText: String = ""

            companion object {
                private val BADGE_FONT by lazy { JBUI.Fonts.smallFont().deriveFont(Font.BOLD, JBUI.scale(9).toFloat()) }
                private var cachedFontMetrics: FontMetrics? = null
            }

            init {
                isOpaque = false
                border = JBUI.Borders.emptyRight(4)
            }

            private fun getBadgeFontMetrics(): FontMetrics {
                return cachedFontMetrics ?: getFontMetrics(BADGE_FONT).also { cachedFontMetrics = it }
            }

            fun update(repoName: String) {
                badgeText = repoName
                val fm = getBadgeFontMetrics()
                val textW = fm.stringWidth(badgeText)
                preferredSize = Dimension(textW + JBUI.scale(10), fm.height + JBUI.scale(4))
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (badgeText.isEmpty()) return
                val g2 = g.create() as Graphics2D
                com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)
                g2.color = StatusColors.INFO
                g2.fill(RoundRectangle2D.Float(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
                ))
                g2.color = JBColor.WHITE
                g2.font = BADGE_FONT
                val fm = g2.fontMetrics
                val textX = (width - fm.stringWidth(badgeText)) / 2
                val textY = (height + fm.ascent - fm.descent) / 2
                g2.drawString(badgeText, textX, textY)
                g2.dispose()
            }
        }

        /** Cached status badge that repaints with updated color/text. */
        private class StatusBadgePanel : JPanel() {
            private var badgeColor: Color = SECONDARY_TEXT
            private var badgeText: String = ""

            companion object {
                private val BADGE_FONT by lazy { JBUI.Fonts.smallFont().deriveFont(Font.BOLD, JBUI.scale(9).toFloat()) }
                private var cachedFontMetrics: FontMetrics? = null
            }

            init {
                isOpaque = false
            }

            private fun getBadgeFontMetrics(): FontMetrics {
                return cachedFontMetrics ?: getFontMetrics(BADGE_FONT).also { cachedFontMetrics = it }
            }

            fun update(status: String) {
                badgeText = status.uppercase()
                badgeColor = when (badgeText) {
                    "OPEN" -> STATUS_OPEN
                    "MERGED" -> STATUS_MERGED
                    "DECLINED" -> STATUS_DECLINED
                    else -> SECONDARY_TEXT
                }
                val fm = getBadgeFontMetrics()
                val textW = fm.stringWidth(badgeText)
                preferredSize = Dimension(textW + JBUI.scale(10), fm.height + JBUI.scale(4))
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (badgeText.isEmpty()) return
                val g2 = g.create() as Graphics2D
                com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)
                g2.color = badgeColor
                g2.fill(RoundRectangle2D.Float(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
                ))
                g2.color = JBColor.WHITE
                g2.font = BADGE_FONT
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
