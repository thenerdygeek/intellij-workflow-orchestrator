package com.workflow.orchestrator.handover.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Persistent amber banner shown between the tab nav and tab content in the Handover panel
 * whenever one or more checks are red or in-progress.
 *
 * Usage:
 * ```
 * banner.setFailures(listOf(FailedCheck("quality.gate", "Quality gate FAILED", "Quality")))
 * banner.setFailures(emptyList()) // hides
 * ```
 *
 * When [project] is null (test mode), the link is rendered but clicking is a no-op.
 * Wired into HandoverPanel in T24.
 */
data class FailedCheck(
    /** Stable machine id, e.g. "quality.gate", "suite.web-e2e". */
    val id: String,
    /** Human-readable label shown in the comma list, e.g. "Quality gate FAILED". */
    val label: String,
    /** Title of the tab to navigate to, e.g. "Quality" / "Build" / "Automation". */
    val targetTab: String,
)

class HandoverOverrideBanner(private val project: Project? = null) : JPanel(BorderLayout()) {

    private val iconLabel = JBLabel(AllIcons.General.BalloonWarning)
    private val messageLabel = JBLabel("")
    private val viewTabLink = ActionLink("") {}

    init {
        isVisible = false
        isOpaque = true
        background = StatusColors.WARNING_BG

        // 3 px left amber accent
        val innerBorder = JBUI.Borders.empty(8, 12)
        val leftAccent = JBUI.Borders.customLine(StatusColors.WARNING, 0, 3, 0, 0)
        border = JBUI.Borders.merge(leftAccent, innerBorder, true)

        val content = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(iconLabel)
            add(messageLabel)
            add(viewTabLink)
        }
        add(content, BorderLayout.CENTER)
    }

    /**
     * Update the banner with the current set of failed/in-progress checks.
     * An empty list hides the banner.
     */
    fun setFailures(failed: List<FailedCheck>) {
        if (failed.isEmpty()) {
            isVisible = false
            return
        }

        val count = failed.size
        val labels = failed.joinToString(", ") { it.label }
        val firstTab = failed.first().targetTab

        // Build HTML text: bold "N check(s) not green:" then the label list, then trailing note.
        messageLabel.text = buildString {
            append("<html><b>")
            append(count)
            append(" check(s) not green:</b> ")
            append(labels)
            append(". You can still hand off, but consider fixing first.</html>")
        }

        viewTabLink.text = "View $firstTab tab"
        // Remove all existing action listeners to prevent stacking on repeated setFailures calls.
        viewTabLink.actionListeners.forEach { viewTabLink.removeActionListener(it) }

        // Re-wire the click action to the current first failure's target tab.
        val targetTab = firstTab
        viewTabLink.addActionListener {
            navigateToTab(targetTab)
        }

        isVisible = true
        revalidate()
        repaint()
    }

    private fun navigateToTab(tabTitle: String) {
        val proj = project ?: return
        val tw = ToolWindowManager.getInstance(proj).getToolWindow("Workflow") ?: return
        tw.activate {
            val cm = tw.contentManager
            val target = cm.contents.firstOrNull { it.displayName == tabTitle }
            if (target != null) cm.setSelectedContent(target)
        }
    }
}
