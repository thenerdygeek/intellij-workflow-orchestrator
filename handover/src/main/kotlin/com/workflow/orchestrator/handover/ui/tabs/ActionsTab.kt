package com.workflow.orchestrator.handover.ui.tabs

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.handover.ui.cards.CopyrightFixCard
import com.workflow.orchestrator.handover.ui.cards.TimeLogCard
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Actions tab — composes the two small write-action cards: Copyright Fix + Time Log.
 *
 * Subscribes to nothing itself; the parent panel (HandoverPanel) owns the state-flow
 * collector and fans state updates into each child via their own subscriptions.
 */
class ActionsTab(project: Project) : JPanel(BorderLayout()), Disposable {

    private val copyright = CopyrightFixCard(project)
    private val timeLog = TimeLogCard(project)

    init {
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8)
        }
        column.add(copyright)
        column.add(Box.createVerticalStrut(JBUI.scale(12)))
        column.add(timeLog)
        column.add(Box.createVerticalGlue())  // pushes cards to top

        add(JBScrollPane(column).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
        }, BorderLayout.CENTER)

        // Cascade dispose so the children's coroutine scopes get cancelled
        // when the tab is removed from the panel.
        Disposer.register(this, copyright)
        Disposer.register(this, timeLog)
    }

    /**
     * Propagates the active ticket key to [TimeLogCard] so the Log Work button
     * becomes enabled. Called from [com.workflow.orchestrator.handover.ui.HandoverPanel.applyState]
     * on every state update.
     */
    fun updateTicket(ticketKey: String?, startWorkTimestamp: Long = 0L) {
        timeLog.setTicket(ticketKey)
        timeLog.setStartedTimestamp(startWorkTimestamp)
    }

    override fun dispose() { /* children disposed by Disposer */ }
}
