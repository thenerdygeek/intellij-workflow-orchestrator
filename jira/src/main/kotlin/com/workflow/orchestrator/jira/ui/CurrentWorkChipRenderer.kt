package com.workflow.orchestrator.jira.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.workflow.LocateResult
import com.workflow.orchestrator.core.workflow.TicketRepoBranch
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * Stateless renderer for the multi-repo branch chip rows in
 * [CurrentWorkSection]. Takes a host [JPanel] and a [LocateResult]; mutates the
 * host so it shows one row per [TicketRepoBranch], or an appropriate empty
 * state.
 *
 * **Why a separate object?** Testing through `CurrentWorkSection` requires
 * `BasePlatformTestCase`, which runs test bodies on the EDT. The chip's render
 * happens inside `invokeLater { … }` after an async locator call, and the only
 * ways to await that EDT work from within `BasePlatformTestCase`'s EDT-bound
 * test body deadlock the queue. Extracting the renderer into a stateless object
 * lets us unit-test it with plain JUnit 5 — no `Project`, no async, no EDT
 * scheduling.
 *
 * The host panel is mutated in place so callers can keep their existing layout
 * graph; the renderer doesn't manage layout itself.
 */
internal object CurrentWorkChipRenderer {

    /**
     * Replace the contents of [host] with the rendering for [result]. After
     * return, [host] contains either a settings prompt, an empty-state hint, or
     * one row per [TicketRepoBranch].
     *
     * - [ticketId] is included in the empty-state copy ("No branches found for ABC-123").
     * - [onSettingsClick] fires when the `NoReposConfigured` link is clicked.
     * - [onSwitchClick] fires when a row's "Switch" link is clicked. Receives
     *   the row whose branch should be checked out. Only invoked for rows where
     *   `isPathMounted && !isCheckedOut`.
     */
    fun render(
        host: JPanel,
        result: LocateResult,
        ticketId: String,
        onSettingsClick: () -> Unit,
        onSwitchClick: (TicketRepoBranch) -> Unit,
    ) {
        host.removeAll()
        when (result) {
            is LocateResult.NoReposConfigured -> {
                host.add(JBLabel("Configure repositories in Settings → Workflow Orchestrator").apply {
                    foreground = StatusColors.LINK
                    font = font.deriveFont(JBUI.scale(10).toFloat())
                    alignmentX = Component.LEFT_ALIGNMENT
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) { onSettingsClick() }
                    })
                })
            }
            is LocateResult.Configured -> {
                if (result.rows.isEmpty()) {
                    host.add(JBLabel("No branches found for $ticketId").apply {
                        foreground = StatusColors.SECONDARY_TEXT
                        font = font.deriveFont(JBUI.scale(10).toFloat())
                        alignmentX = Component.LEFT_ALIGNMENT
                    })
                } else {
                    result.rows.forEach { host.add(buildBranchRow(it, onSwitchClick)) }
                }
            }
        }
    }

    private fun buildBranchRow(row: TicketRepoBranch, onSwitchClick: (TicketRepoBranch) -> Unit): JPanel {
        val rowPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val checkMark = JBLabel(if (row.isCheckedOut) "✓" else " ").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            foreground = if (row.isCheckedOut) StatusColors.SUCCESS else StatusColors.SECONDARY_TEXT
        }
        rowPanel.add(checkMark)

        val target = row.targetBranchDisplayId?.let { " → $it" } ?: ""
        val branchRowLabel = JBLabel("${row.repo.displayLabel}: ${row.branchDisplayId}$target").apply {
            icon = AllIcons.Vcs.Branch
            font = font.deriveFont(JBUI.scale(10).toFloat())
            foreground = if (row.isCheckedOut) StatusColors.LINK else StatusColors.SECONDARY_TEXT
            toolTipText = when {
                !row.isPathMounted -> "Local path not mounted in this project — re-add the repo in Settings."
                row.additionalMatchCount > 0 -> "+${row.additionalMatchCount} more matching branch(es) in ${row.repo.displayLabel}"
                else -> null
            }
        }
        rowPanel.add(branchRowLabel)

        if (!row.isPathMounted) {
            rowPanel.add(JBLabel("(repo not mounted)").apply {
                font = font.deriveFont(JBUI.scale(9).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
            })
        } else if (!row.isCheckedOut) {
            val switchLink = JBLabel("Switch").apply {
                font = font.deriveFont(JBUI.scale(10).toFloat())
                foreground = StatusColors.LINK
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Check out ${row.branchDisplayId} in ${row.repo.displayLabel}"
            }
            switchLink.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) { onSwitchClick(row) }
            })
            rowPanel.add(switchLink)
        }
        return rowPanel
    }
}
