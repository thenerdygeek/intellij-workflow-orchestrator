package com.workflow.orchestrator.agent.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Minimal stub — will be rewritten in Task 9.
 * Shows past agent sessions across all projects.
 */
class HistoryPanel : JPanel(BorderLayout()) {

    /** Callback invoked when the user wants to resume a session. Receives sessionId. */
    var onResumeSession: ((String) -> Unit)? = null

    init {
        border = JBUI.Borders.empty(8)
        add(JBLabel("Session history will be available after agent rewrite."), BorderLayout.CENTER)
    }

    fun refresh() {
        // no-op stub
    }
}
