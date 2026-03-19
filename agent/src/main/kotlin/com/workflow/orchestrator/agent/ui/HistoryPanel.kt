package com.workflow.orchestrator.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.agent.service.GlobalSessionIndex
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent

/**
 * Panel showing past agent sessions across all projects.
 *
 * Displays session title, project name, message count, status, and relative time.
 * Supports resuming interrupted sessions via [onResumeSession] callback.
 */
class HistoryPanel : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<GlobalSessionIndex.SessionEntry>()
    private val sessionList = JBList(listModel)
    private var allSessions: List<GlobalSessionIndex.SessionEntry> = emptyList()

    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.setText("Search sessions...")
    }

    /** Callback invoked when the user wants to resume a session. Receives sessionId. */
    var onResumeSession: ((String) -> Unit)? = null

    init {
        border = JBUI.Borders.empty(8)

        // Header with search field and refresh button
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            val titleRow = JPanel(BorderLayout()).apply {
                isOpaque = false
                val title = JBLabel("Session History").apply {
                    font = JBUI.Fonts.label(14f).asBold()
                    border = JBUI.Borders.emptyBottom(8)
                }
                add(title, BorderLayout.WEST)

                val refreshBtn = JButton("Refresh").apply {
                    icon = AllIcons.Actions.Refresh
                    addActionListener { refresh() }
                }
                add(refreshBtn, BorderLayout.EAST)
            }
            add(titleRow, BorderLayout.NORTH)
            add(searchField, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)

        // Wire search filtering
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterSessions(searchField.text.trim())
            }
        })

        // Auto-refresh timer (30 seconds)
        val refreshTimer = Timer(30_000) { if (isShowing) refresh() }
        refreshTimer.isRepeats = true
        refreshTimer.start()

        // Session list with custom renderer
        sessionList.cellRenderer = SessionCellRenderer()
        sessionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionList.emptyText.text = "No past sessions."
        sessionList.emptyText.appendLine("Start a conversation in the Agent tab.")

        // Double-click to resume
        sessionList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val entry = sessionList.selectedValue ?: return
                    onResumeSession?.invoke(entry.sessionId)
                }
            }
        })

        add(JBScrollPane(sessionList), BorderLayout.CENTER)

        // Bottom action bar
        val actionBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)

            val deleteBtn = JButton("Delete").apply {
                icon = AllIcons.Actions.GC
                addActionListener { deleteSelected() }
            }
            add(deleteBtn)

            val cleanupBtn = JButton("Cleanup Old").apply {
                icon = AllIcons.Actions.ProfileCPU
                toolTipText = "Remove sessions older than 30 days"
                addActionListener {
                    try {
                        GlobalSessionIndex.getInstance().cleanup()
                        refresh()
                    } catch (_: Exception) { /* best effort */ }
                }
            }
            add(cleanupBtn)
        }
        add(actionBar, BorderLayout.SOUTH)

        // Load initial data
        refresh()
    }

    /**
     * Reload session list from the global index.
     */
    fun refresh() {
        allSessions = try {
            GlobalSessionIndex.getInstance().getSessions(100)
        } catch (_: Exception) { emptyList() }
        filterSessions(searchField.text.trim())
    }

    private fun filterSessions(query: String) {
        val filtered = if (query.isBlank()) allSessions
        else allSessions.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.projectName.contains(query, ignoreCase = true)
        }
        listModel.clear()
        filtered.forEach { listModel.addElement(it) }
    }

    private fun deleteSelected() {
        val entry = sessionList.selectedValue ?: return
        try {
            GlobalSessionIndex.getInstance().deleteSession(entry.sessionId)
            refresh()
        } catch (_: Exception) { /* best effort */ }
    }

    // -----------------------------------------------------------------
    // Cell renderer
    // -----------------------------------------------------------------

    private inner class SessionCellRenderer : ListCellRenderer<GlobalSessionIndex.SessionEntry> {

        override fun getListCellRendererComponent(
            list: JList<out GlobalSessionIndex.SessionEntry>?,
            value: GlobalSessionIndex.SessionEntry?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value == null) return JPanel()

            val statusIcon = when (value.status) {
                "completed" -> AllIcons.RunConfigurations.TestPassed
                "failed" -> AllIcons.RunConfigurations.TestFailed
                "interrupted" -> AllIcons.RunConfigurations.TestTerminated
                "active" -> AllIcons.Actions.Execute
                else -> AllIcons.RunConfigurations.TestUnknown
            }

            val statusColor = when (value.status) {
                "completed" -> JBColor(Color(0x16A34A), Color(0x22C55E))
                "failed" -> JBColor(Color(0xDC2626), Color(0xEF4444))
                "interrupted" -> JBColor(Color(0xD97706), Color(0xF59E0B))
                else -> JBColor.foreground()
            }

            val timeAgo = formatTimeAgo(value.lastMessageAt)

            val panel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(6, 8)
            }

            // Left: icon + text
            val iconLabel = JBLabel(statusIcon).apply {
                border = JBUI.Borders.emptyRight(8)
            }

            val titleLabel = JBLabel(value.title.ifBlank { "Untitled session" }).apply {
                font = JBUI.Fonts.label(13f).asBold()
            }

            val detailLabel = JBLabel("${value.projectName} \u00B7 ${value.messageCount} messages \u00B7 $timeAgo").apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor.GRAY
            }

            val statusLabel = JBLabel(value.status.replaceFirstChar { it.uppercase() }).apply {
                foreground = statusColor
                font = JBUI.Fonts.smallFont()
            }

            val textPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(titleLabel)
                add(Box.createVerticalStrut(2))
                add(detailLabel)
            }

            val leftPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(iconLabel, BorderLayout.WEST)
                add(textPanel, BorderLayout.CENTER)
            }

            panel.add(leftPanel, BorderLayout.CENTER)
            panel.add(statusLabel, BorderLayout.EAST)

            if (isSelected) {
                panel.background = list?.selectionBackground
                panel.isOpaque = true
                titleLabel.foreground = list?.selectionForeground
                detailLabel.foreground = list?.selectionForeground
            } else {
                panel.isOpaque = false
            }

            return panel
        }

        private fun formatTimeAgo(timestamp: Long): String {
            if (timestamp <= 0) return "unknown"
            val diff = System.currentTimeMillis() - timestamp
            return when {
                diff < 60_000 -> "just now"
                diff < 3_600_000 -> "${diff / 60_000}m ago"
                diff < 86_400_000 -> "${diff / 3_600_000}h ago"
                diff < 604_800_000 -> "${diff / 86_400_000}d ago"
                else -> "${diff / 604_800_000}w ago"
            }
        }
    }
}
