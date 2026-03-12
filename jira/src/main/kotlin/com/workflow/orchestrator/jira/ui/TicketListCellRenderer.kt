package com.workflow.orchestrator.jira.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import java.awt.*
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Custom card-style cell renderer for Jira issues in the Sprint Dashboard.
 *
 * Each issue is rendered as a two-line card with:
 * - Line 1: Priority indicator + ticket key (bold) + summary
 * - Line 2: Status pill + issue type badge + assignee + blocker count
 */
class TicketListCellRenderer : JPanel(), ListCellRenderer<JiraIssue> {

    private var issue: JiraIssue? = null
    private var isSelected: Boolean = false
    private var isHovered: Boolean = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 6)
    }

    override fun getListCellRendererComponent(
        list: JList<out JiraIssue>,
        value: JiraIssue,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        this.issue = value
        this.isSelected = isSelected
        val listHoveredIndex = (list.getClientProperty(HOVERED_INDEX_KEY) as? Int) ?: -1
        this.isHovered = !isSelected && index == listHoveredIndex

        preferredSize = Dimension(list.width, JBUI.scale(ROW_HEIGHT))
        return this
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val issue = this.issue ?: return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val insets = insets
        val x = insets.left
        val y = insets.top
        val w = width - insets.left - insets.right
        val h = height - insets.top - insets.bottom
        val cornerRadius = JBUI.scale(6).toFloat()

        // -- Card background --
        val bgColor = when {
            isSelected -> CARD_SELECTED
            isHovered -> CARD_HOVER
            else -> CARD_BACKGROUND
        }
        g2.color = bgColor
        g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), cornerRadius, cornerRadius))

        val padding = JBUI.scale(10)
        val contentX = x + padding
        val contentW = w - padding * 2

        // -- Fonts --
        val keyFont = g2.font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
        val summaryFont = g2.font.deriveFont(Font.PLAIN, JBUI.scale(13).toFloat())
        val smallFont = g2.font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        val pillFont = g2.font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())

        val keyMetrics = g2.getFontMetrics(keyFont)
        val summaryMetrics = g2.getFontMetrics(summaryFont)
        val smallMetrics = g2.getFontMetrics(smallFont)
        val pillMetrics = g2.getFontMetrics(pillFont)

        // -- LINE 1: Priority circle + Key + Summary --
        val line1Y = y + padding + keyMetrics.ascent
        var cursorX = contentX

        // Priority circle
        val circleSize = JBUI.scale(8)
        val circleY = line1Y - keyMetrics.ascent + (keyMetrics.height - circleSize) / 2
        g2.color = getPriorityColor(issue.fields.priority?.name)
        g2.fill(Ellipse2D.Float(cursorX.toFloat(), circleY.toFloat(), circleSize.toFloat(), circleSize.toFloat()))
        cursorX += circleSize + JBUI.scale(6)

        // Ticket key (bold)
        g2.font = keyFont
        g2.color = if (isSelected) JBColor.foreground() else KEY_TEXT_COLOR
        g2.drawString(issue.key, cursorX, line1Y)
        cursorX += keyMetrics.stringWidth(issue.key) + JBUI.scale(8)

        // Summary (regular, truncated)
        g2.font = summaryFont
        g2.color = if (isSelected) JBColor.foreground() else SUMMARY_TEXT_COLOR
        val maxSummaryWidth = (contentX + contentW) - cursorX
        val truncatedSummary = truncateText(issue.fields.summary, summaryMetrics, maxSummaryWidth)
        g2.drawString(truncatedSummary, cursorX, line1Y)

        // -- LINE 2: Status pill + Issue type + Assignee + Blockers --
        val line2Y = line1Y + keyMetrics.descent + JBUI.scale(6) + smallMetrics.ascent
        cursorX = contentX + circleSize + JBUI.scale(6) // align with text above

        // Status pill
        val statusName = issue.fields.status.name
        val statusCatKey = issue.fields.status.statusCategory?.key ?: ""
        val pillColor = getStatusColor(statusCatKey)
        val pillText = statusName.uppercase()
        g2.font = pillFont
        val pillTextWidth = pillMetrics.stringWidth(pillText)
        val pillPadH = JBUI.scale(6)
        val pillPadV = JBUI.scale(2)
        val pillW = pillTextWidth + pillPadH * 2
        val pillH = pillMetrics.height + pillPadV * 2
        val pillY = line2Y - pillMetrics.ascent - pillPadV

        g2.color = pillColor
        g2.fill(RoundRectangle2D.Float(
            cursorX.toFloat(), pillY.toFloat(),
            pillW.toFloat(), pillH.toFloat(),
            JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
        ))
        g2.color = JBColor.WHITE
        g2.drawString(pillText, cursorX + pillPadH, line2Y)
        cursorX += pillW + JBUI.scale(8)

        // Issue type badge
        val issueType = issue.fields.issuetype?.name ?: "Task"
        g2.font = smallFont
        g2.color = TYPE_BADGE_BG
        val typeBadgeW = smallMetrics.stringWidth(issueType) + JBUI.scale(8)
        val typeBadgeH = smallMetrics.height + JBUI.scale(2)
        val typeBadgeY = line2Y - smallMetrics.ascent - JBUI.scale(1)
        g2.fill(RoundRectangle2D.Float(
            cursorX.toFloat(), typeBadgeY.toFloat(),
            typeBadgeW.toFloat(), typeBadgeH.toFloat(),
            JBUI.scale(3).toFloat(), JBUI.scale(3).toFloat()
        ))
        g2.color = if (isSelected) JBColor.foreground() else TYPE_TEXT_COLOR
        g2.drawString(issueType, cursorX + JBUI.scale(4), line2Y)
        cursorX += typeBadgeW + JBUI.scale(8)

        // Assignee
        val assigneeName = issue.fields.assignee?.displayName ?: "Unassigned"
        g2.color = if (isSelected) JBColor.foreground() else SECONDARY_TEXT_COLOR
        g2.drawString(assigneeName, cursorX, line2Y)
        cursorX += smallMetrics.stringWidth(assigneeName)

        // Blocker count
        val blockerCount = issue.fields.issuelinks.count { link ->
            link.type.inward.contains("block", ignoreCase = true) && link.inwardIssue != null
        }
        if (blockerCount > 0) {
            cursorX += JBUI.scale(6)
            g2.color = SECONDARY_TEXT_COLOR
            g2.drawString("\u2022", cursorX, line2Y) // bullet
            cursorX += smallMetrics.stringWidth("\u2022") + JBUI.scale(4)

            g2.color = BLOCKER_TEXT
            val blockerText = "$blockerCount blocker${if (blockerCount > 1) "s" else ""}"
            g2.drawString(blockerText, cursorX, line2Y)
        }

        g2.dispose()
    }

    companion object {
        /** Client property key for per-list hover tracking. Set by [SprintDashboardPanel]. */
        const val HOVERED_INDEX_KEY = "workflow.sprint.hoveredIndex"

        private const val ROW_HEIGHT = 52

        // -- Card colors --
        private val CARD_BACKGROUND = JBColor(0xF7F8FA, 0x2B2D30)
        private val CARD_HOVER = JBColor(0xEBEDF0, 0x353739)
        private val CARD_SELECTED = JBColor(0xD4E4FA, 0x2E436E)

        // -- Text colors --
        private val KEY_TEXT_COLOR = JBColor(0x1F2328, 0xE6EDF3)
        private val SUMMARY_TEXT_COLOR = JBColor(0x1F2328, 0xCDD9E5)
        private val SECONDARY_TEXT_COLOR = JBColor(0x656D76, 0x8B949E)
        private val TYPE_TEXT_COLOR = JBColor(0x1F2328, 0xCDD9E5)
        private val TYPE_BADGE_BG = JBColor(0xE8EAED, 0x3D4043)
        private val BLOCKER_TEXT = JBColor(0xCF222E, 0xF85149)

        // -- Status pill colors --
        private val STATUS_DONE = JBColor(0x1B7F37, 0x3FB950)
        private val STATUS_IN_PROGRESS = JBColor(0x0969DA, 0x58A6FF)
        private val STATUS_TODO = JBColor(0x656D76, 0x8B949E)

        // -- Priority colors --
        private val PRIORITY_HIGHEST = JBColor(0xCF222E, 0xF85149)
        private val PRIORITY_HIGH = JBColor(0xBF5700, 0xDB6D28)
        private val PRIORITY_MEDIUM = JBColor(0x0969DA, 0x58A6FF)
        private val PRIORITY_LOW = JBColor(0x1B7F37, 0x3FB950)
        private val PRIORITY_LOWEST = JBColor(0x656D76, 0x8B949E)

        @JvmStatic
        fun getStatusColor(categoryKey: String): Color = when (categoryKey) {
            "done" -> STATUS_DONE
            "indeterminate" -> STATUS_IN_PROGRESS
            else -> STATUS_TODO
        }

        @JvmStatic
        fun getPriorityColor(priorityName: String?): Color = when {
            priorityName == null -> PRIORITY_MEDIUM
            priorityName.equals("Highest", ignoreCase = true) || priorityName.equals("Blocker", ignoreCase = true) -> PRIORITY_HIGHEST
            priorityName.equals("High", ignoreCase = true) || priorityName.equals("Critical", ignoreCase = true) -> PRIORITY_HIGH
            priorityName.equals("Medium", ignoreCase = true) -> PRIORITY_MEDIUM
            priorityName.equals("Low", ignoreCase = true) || priorityName.equals("Minor", ignoreCase = true) -> PRIORITY_LOW
            priorityName.equals("Lowest", ignoreCase = true) || priorityName.equals("Trivial", ignoreCase = true) -> PRIORITY_LOWEST
            else -> PRIORITY_MEDIUM
        }

        private fun truncateText(text: String, metrics: FontMetrics, maxWidth: Int): String {
            if (maxWidth <= 0) return ""
            if (metrics.stringWidth(text) <= maxWidth) return text
            val ellipsis = "\u2026"
            val ellipsisWidth = metrics.stringWidth(ellipsis)
            val available = maxWidth - ellipsisWidth
            if (available <= 0) return ellipsis
            var end = text.length
            while (end > 0 && metrics.stringWidth(text.substring(0, end)) > available) {
                end--
            }
            return text.substring(0, end) + ellipsis
        }
    }
}
