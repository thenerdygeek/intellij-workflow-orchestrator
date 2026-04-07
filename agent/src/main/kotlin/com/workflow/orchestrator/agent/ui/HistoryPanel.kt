package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.session.Session
import com.workflow.orchestrator.agent.session.SessionStatus
import java.awt.BorderLayout
import java.awt.Component
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*

/**
 * Session history panel — lists past agent sessions for resume.
 * Gap 9: Wire to SessionStore and controller's resumeSession.
 */
class HistoryPanel(private val project: Project? = null) : JPanel(BorderLayout()) {

    /** Callback invoked when the user wants to resume a session. Receives sessionId. */
    var onResumeSession: ((String) -> Unit)? = null

    private val listModel = DefaultListModel<Session>()
    private val sessionList = JBList(listModel)
    private val emptyLabel = JBLabel("No past sessions.")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    init {
        border = JBUI.Borders.empty(8)

        sessionList.cellRenderer = SessionCellRenderer()
        sessionList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // Double-click to resume
        sessionList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = sessionList.selectedValue ?: return
                    onResumeSession?.invoke(selected.id)
                }
            }
        })

        val scrollPane = JBScrollPane(sessionList)
        add(scrollPane, BorderLayout.CENTER)

        // Resume button at bottom
        val resumeButton = JButton("Resume Selected").apply {
            addActionListener {
                val selected = sessionList.selectedValue ?: return@addActionListener
                onResumeSession?.invoke(selected.id)
            }
        }
        val buttonPanel = JPanel().apply {
            add(resumeButton)
        }
        add(buttonPanel, BorderLayout.SOUTH)

        refresh()
    }

    private fun refresh() {
        listModel.clear()
        val service = try {
            project?.let { AgentService.getInstance(it) }
        } catch (_: Exception) {
            null
        }

        if (service == null) {
            listModel.addElement(Session(id = "", title = "Agent service not available"))
            return
        }

        val sessions = service.sessionStore.list()
            .sortedByDescending { it.lastMessageAt }

        if (sessions.isEmpty()) {
            // Show empty state
            removeAll()
            add(emptyLabel, BorderLayout.CENTER)
        } else {
            sessions.forEach { listModel.addElement(it) }
        }

        revalidate()
        repaint()
    }

    private inner class SessionCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is Session && value.id.isNotBlank()) {
                val statusIcon = when (value.status) {
                    SessionStatus.COMPLETED -> "[done]"
                    SessionStatus.FAILED -> "[fail]"
                    SessionStatus.CANCELLED -> "[cancelled]"
                    SessionStatus.ACTIVE -> "[active]"
                }
                val date = dateFormat.format(Date(value.lastMessageAt))
                val title = value.title.ifBlank { "(untitled)" }
                text = "$statusIcon $title  ($date, ${value.messageCount} msgs)"
            }
            return component
        }
    }
}
