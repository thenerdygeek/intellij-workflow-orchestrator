package com.workflow.orchestrator.jira.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
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
 * - Left border accent colored by status (blue=in-progress, green=done)
 * - Line 1: Priority dot + ticket key (blue) + summary
 * - Line 2: Status pill + issue type badge + assignee chip + blocker count
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
        toolTipText = "${value.key}: ${value.fields.summary}"
        return this
    }

    private fun isHeader(issue: JiraIssue): Boolean = issue.id.startsWith("header-")

    private fun paintSectionHeader(g: Graphics, issue: JiraIssue) {
        val g2 = g.create() as Graphics2D
        com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)

        val insets = insets
        val x = insets.left
        val w = width - insets.left - insets.right
        val midY = height / 2

        val headerFont = g2.font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
        val metrics = g2.getFontMetrics(headerFont)

        // Extract display text — strip the "── " and " ──" decorators if present
        val text = issue.key
        val textWidth = metrics.stringWidth(text)
        val textX = (x + w - textWidth) / 2 // center the text

        val textY = midY + metrics.ascent / 2

        // Draw line before text
        val lineY = midY
        val lineStartX = x + JBUI.scale(10)
        val lineEndBeforeText = textX - JBUI.scale(8)
        if (lineStartX < lineEndBeforeText) {
            g2.color = HEADER_LINE_COLOR
            g2.fillRect(lineStartX, lineY, lineEndBeforeText - lineStartX, 1)
        }

        // Draw header text (centered)
        g2.font = headerFont
        g2.color = StatusColors.SECONDARY_TEXT
        g2.drawString(text, textX, textY)

        // Draw line after text
        val lineStartAfterText = textX + textWidth + JBUI.scale(8)
        val lineEndX = x + w - JBUI.scale(10)
        if (lineStartAfterText < lineEndX) {
            g2.color = HEADER_LINE_COLOR
            g2.fillRect(lineStartAfterText, lineY, lineEndX - lineStartAfterText, 1)
        }

        g2.dispose()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val issue = this.issue ?: return

        if (isHeader(issue)) {
            paintSectionHeader(g, issue)
            return
        }

        val g2 = g.create() as Graphics2D
        com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)

        val insets = insets
        val x = insets.left
        val y = insets.top
        val w = width - insets.left - insets.right
        val h = height - insets.top - insets.bottom
        val cornerRadius = JBUI.scale(4).toFloat()
        val borderWidth = JBUI.scale(2)

        val statusCatKey = issue.fields.status.statusCategory?.key ?: ""
        val isDone = statusCatKey == "done"

        // -- Card background --
        val bgColor = when {
            isSelected -> CARD_SELECTED
            isHovered -> CARD_HOVER
            else -> CARD_BACKGROUND
        }
        g2.color = bgColor
        g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), cornerRadius, cornerRadius))

        // -- Left border accent by status --
        val borderColor = when (statusCatKey) {
            "indeterminate" -> StatusColors.LINK   // blue for in-progress
            "done" -> StatusColors.SUCCESS          // green for done
            else -> null                            // no border for to-do
        }
        if (borderColor != null) {
            g2.color = borderColor
            // Clip to the card shape so the border follows the rounded corner
            val clip = g2.clip
            g2.clip(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), cornerRadius, cornerRadius))
            g2.fillRect(x, y, borderWidth, h)
            g2.clip = clip
        }

        val leftPad = JBUI.scale(12) + if (borderColor != null) borderWidth else 0
        val contentX = x + leftPad
        val rightPad = JBUI.scale(10)
        val contentW = w - leftPad - rightPad

        // -- Fonts --
        val keyFont = KEY_FONT
        val summaryFont = SUMMARY_FONT
        val smallFont = SMALL_FONT
        val pillFont = PILL_FONT

        val keyMetrics = g2.getFontMetrics(keyFont)
        val summaryMetrics = g2.getFontMetrics(summaryFont)
        val smallMetrics = g2.getFontMetrics(smallFont)
        val pillMetrics = g2.getFontMetrics(pillFont)

        // -- LINE 1: Priority dot + Key + Summary --
        val line1Y = y + JBUI.scale(10) + keyMetrics.ascent
        var cursorX = contentX

        // Priority dot (6px)
        val circleSize = JBUI.scale(6)
        val circleY = line1Y - keyMetrics.ascent + (keyMetrics.height - circleSize) / 2
        g2.color = getPriorityColor(issue.fields.priority?.name)
        g2.fill(Ellipse2D.Float(cursorX.toFloat(), circleY.toFloat(), circleSize.toFloat(), circleSize.toFloat()))
        cursorX += circleSize + JBUI.scale(6)

        // Ticket key (bold, blue when not selected)
        g2.font = keyFont
        g2.color = if (isSelected) JBColor.foreground() else StatusColors.LINK
        g2.drawString(issue.key, cursorX, line1Y)
        cursorX += keyMetrics.stringWidth(issue.key) + JBUI.scale(8)

        // Summary (truncated, dimmer; strikethrough if done)
        g2.font = summaryFont
        g2.color = if (isSelected) JBColor.foreground() else StatusColors.SECONDARY_TEXT
        val maxSummaryWidth = (contentX + contentW) - cursorX
        val truncatedSummary = truncateText(issue.fields.summary, summaryMetrics, maxSummaryWidth)
        g2.drawString(truncatedSummary, cursorX, line1Y)

        // Strikethrough for done tickets
        if (isDone && truncatedSummary.isNotEmpty()) {
            val strikeY = line1Y - summaryMetrics.ascent / 3
            val summaryWidth = summaryMetrics.stringWidth(truncatedSummary)
            g2.color = StatusColors.SECONDARY_TEXT
            g2.fillRect(cursorX, strikeY, summaryWidth, 1)
        }

        // -- LINE 2: Status pill + Issue type + Assignee chip + Blockers --
        val line2Y = line1Y + keyMetrics.descent + JBUI.scale(6) + smallMetrics.ascent
        cursorX = contentX + circleSize + JBUI.scale(6) // align with text above

        // Status pill (compact, 9pt)
        val statusName = issue.fields.status.name
        val pillColor = getStatusColor(statusCatKey)
        val pillText = statusName.uppercase()
        g2.font = pillFont
        val pillTextWidth = pillMetrics.stringWidth(pillText)
        val pillPadH = JBUI.scale(5)
        val pillPadV = JBUI.scale(2)
        val pillW = pillTextWidth + pillPadH * 2
        val pillH = pillMetrics.height + pillPadV * 2
        val pillY = line2Y - pillMetrics.ascent - pillPadV

        g2.color = pillColor
        g2.fill(RoundRectangle2D.Float(
            cursorX.toFloat(), pillY.toFloat(),
            pillW.toFloat(), pillH.toFloat(),
            JBUI.scale(3).toFloat(), JBUI.scale(3).toFloat()
        ))
        g2.color = JBColor.WHITE
        g2.drawString(pillText, cursorX + pillPadH, line2Y)
        cursorX += pillW + JBUI.scale(6)

        // Issue type badge (smaller)
        val issueType = issue.fields.issuetype?.name ?: "Task"
        g2.font = smallFont
        val typeBadgeW = smallMetrics.stringWidth(issueType) + JBUI.scale(8)
        val typeBadgeH = smallMetrics.height + JBUI.scale(2)
        val typeBadgeY = line2Y - smallMetrics.ascent - JBUI.scale(1)
        g2.color = TYPE_BADGE_BG
        g2.fill(RoundRectangle2D.Float(
            cursorX.toFloat(), typeBadgeY.toFloat(),
            typeBadgeW.toFloat(), typeBadgeH.toFloat(),
            JBUI.scale(3).toFloat(), JBUI.scale(3).toFloat()
        ))
        g2.color = if (isSelected) JBColor.foreground() else StatusColors.SECONDARY_TEXT
        g2.drawString(issueType, cursorX + JBUI.scale(4), line2Y)
        cursorX += typeBadgeW + JBUI.scale(6)

        // Assignee chip (rounded background)
        val assigneeName = issue.fields.assignee?.displayName ?: "Unassigned"
        val assigneeChipPadH = JBUI.scale(4)
        val assigneeW = smallMetrics.stringWidth(assigneeName) + assigneeChipPadH * 2
        val assigneeH = smallMetrics.height + JBUI.scale(2)
        val assigneeY = line2Y - smallMetrics.ascent - JBUI.scale(1)
        g2.color = ASSIGNEE_CHIP_BG
        g2.fill(RoundRectangle2D.Float(
            cursorX.toFloat(), assigneeY.toFloat(),
            assigneeW.toFloat(), assigneeH.toFloat(),
            JBUI.scale(3).toFloat(), JBUI.scale(3).toFloat()
        ))
        g2.color = if (isSelected) JBColor.foreground() else StatusColors.SECONDARY_TEXT
        g2.drawString(assigneeName, cursorX + assigneeChipPadH, line2Y)
        cursorX += assigneeW + JBUI.scale(6)

        // Blocker count
        val blockerCount = issue.fields.issuelinks.count { link ->
            link.type.inward.contains("block", ignoreCase = true) && link.inwardIssue != null
        }
        if (blockerCount > 0) {
            g2.color = StatusColors.ERROR
            val blockerText = "\u26A0 $blockerCount"
            g2.font = smallFont
            g2.drawString(blockerText, cursorX, line2Y)
        }

        g2.dispose()
    }

    companion object {
        /** Client property key for per-list hover tracking. Set by [SprintDashboardPanel]. */
        const val HOVERED_INDEX_KEY = "workflow.sprint.hoveredIndex"

        private const val ROW_HEIGHT = 56

        // -- Card colors --
        private val CARD_BACKGROUND = JBColor(0xF7F8FA, 0x1E2025)
        private val CARD_HOVER = StatusColors.HIGHLIGHT_BG
        private val CARD_SELECTED = JBColor(0xD4E4FA, 0x2E436E)

        // -- Badge/chip colors --
        private val TYPE_BADGE_BG = JBColor(0xE8EAED, 0x2D3035)
        private val ASSIGNEE_CHIP_BG = JBColor(0xEEF0F2, 0x25282C)
        private val HEADER_LINE_COLOR = JBColor(0xE0E0E0, 0x333640)

        // -- Cached fonts --
        private val KEY_FONT by lazy { JBFont.regular().deriveFont(Font.BOLD, JBUI.scale(11).toFloat()) }
        private val SUMMARY_FONT by lazy { JBFont.regular().deriveFont(Font.PLAIN, JBUI.scale(11).toFloat()) }
        private val SMALL_FONT by lazy { JBFont.regular().deriveFont(Font.PLAIN, JBUI.scale(10).toFloat()) }
        private val PILL_FONT by lazy { JBFont.regular().deriveFont(Font.BOLD, JBUI.scale(9).toFloat()) }

        @JvmStatic
        fun getStatusColor(categoryKey: String): Color = when (categoryKey) {
            "done" -> StatusColors.SUCCESS
            "indeterminate" -> StatusColors.LINK
            else -> StatusColors.INFO
        }

        @JvmStatic
        fun getPriorityColor(priorityName: String?): Color = when {
            priorityName == null -> StatusColors.WARNING
            priorityName.equals("Highest", ignoreCase = true) || priorityName.equals("Blocker", ignoreCase = true) -> StatusColors.ERROR
            priorityName.equals("High", ignoreCase = true) || priorityName.equals("Critical", ignoreCase = true) -> StatusColors.ERROR
            priorityName.equals("Medium", ignoreCase = true) -> StatusColors.WARNING
            priorityName.equals("Low", ignoreCase = true) || priorityName.equals("Minor", ignoreCase = true) -> StatusColors.SUCCESS
            priorityName.equals("Lowest", ignoreCase = true) || priorityName.equals("Trivial", ignoreCase = true) -> StatusColors.SUCCESS
            else -> StatusColors.WARNING
        }

        private fun truncateText(text: String, metrics: FontMetrics, maxWidth: Int): String {
            if (maxWidth <= 0) return ""
            if (metrics.stringWidth(text) <= maxWidth) return text
            val ellipsis = "\u2026"
            val ellipsisWidth = metrics.stringWidth(ellipsis)
            val available = maxWidth - ellipsisWidth
            if (available <= 0) return ellipsis
            var lo = 0
            var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (metrics.stringWidth(text.substring(0, mid)) <= available) {
                    lo = mid
                } else {
                    hi = mid - 1
                }
            }
            return if (lo > 0) text.substring(0, lo) + ellipsis else ellipsis
        }
    }
}
