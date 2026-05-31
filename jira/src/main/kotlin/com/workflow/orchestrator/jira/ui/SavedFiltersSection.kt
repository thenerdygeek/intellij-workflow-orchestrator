package com.workflow.orchestrator.jira.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.jira.FilterData
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.util.StringUtils
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * "Saved Filters" section shown above the active-sprint list in
 * [SprintDashboardPanel].  Renders the user's Jira favourite filters as
 * clickable rows.  Section visibility follows R-ADD-7's spec:
 *
 *  - empty list  → hide entire section (no "No favourites" empty state)
 *  - load error  → hide entire section (favourites are an enhancement)
 *  - non-empty   → show one row per filter (icon + name + truncated description)
 *
 * Click handler is wired by the parent panel via [onFilterClicked] so that
 * the section stays decoupled from JiraService and the result-rendering area.
 */
class SavedFiltersSection(
    private val onFilterClicked: (FilterData) -> Unit
) : JPanel(BorderLayout()) {

    private val rowsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(2, 4)
    }

    init {
        isOpaque = false
        add(rowsPanel, BorderLayout.CENTER)
        isVisible = false
    }

    /**
     * Apply a fresh [ToolResult] from `JiraService.getFavouriteFilters()`.  Decides
     * visibility per [shouldShowSection] and rebuilds rows when visible.
     */
    fun update(result: ToolResult<List<FilterData>>) {
        if (!shouldShowSection(result)) {
            isVisible = false
            rowsPanel.removeAll()
            revalidate()
            repaint()
            return
        }
        rowsPanel.removeAll()
        for (filter in result.data ?: emptyList()) {
            rowsPanel.add(buildRow(filter))
        }
        isVisible = true
        revalidate()
        repaint()
    }

    private fun buildRow(filter: FilterData): JPanel {
        val descSuffix = filter.description
            ?.takeIf { it.isNotBlank() }
            ?.let { " · " + StringUtils.truncate(it, MAX_DESC_CHARS) }
            ?: ""
        val text = filter.name + descSuffix

        val iconLabel = JBLabel(AllIcons.General.Filter).apply {
            border = JBUI.Borders.emptyRight(4)
        }
        val textLabel = JBLabel(text).apply {
            foreground = StatusColors.LINK
        }

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(2, 4)
            add(iconLabel)
            add(textLabel)
        }
        val click = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onFilterClicked(filter)
            }
        }
        row.addMouseListener(click)
        // Click on either label still triggers the row handler.
        iconLabel.addMouseListener(click)
        textLabel.addMouseListener(click)
        return row
    }

    companion object {
        private const val MAX_DESC_CHARS = 50

        /**
         * Visibility decision for the section.  Pulled out of the Swing class so
         * `SprintFavouritesRenderingTest` can exercise it without spinning the EDT:
         *
         *  - error  → false (favourites are not load-bearing, hide the section)
         *  - empty  → false (no "No favourites" empty state per spec)
         *  - else   → true
         */
        @JvmStatic
        fun shouldShowSection(result: ToolResult<List<FilterData>>): Boolean {
            if (result.isError) return false
            return (result.data ?: emptyList()).isNotEmpty()
        }
    }
}
