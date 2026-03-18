package com.workflow.orchestrator.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.agent.runtime.AgentTask
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.*

/**
 * Chat-first agent dashboard — mirrors the UX of Claude Code, Cursor, and Cline.
 *
 * Layout:
 * ┌──────────────────────────────────────────────┐
 * │ [Stop] [New Chat]                 🪙 1.2K ▸ │  ← Compact toolbar
 * ├──────────────────────────────────────────────┤
 * │                                              │
 * │  (Rich streaming output — tool cards,        │  ← Chat history
 * │   diffs, code blocks, session summary)       │
 * │                                              │
 * ├──────────────────────────────────────────────┤
 * │ Ask the agent to do something...     [Send]  │  ← Chat input
 * └──────────────────────────────────────────────┘
 *
 * The chat input is always visible. Enter sends. Shift+Enter for newline.
 * No modal dialogs for task input — just type and go.
 */
class AgentDashboardPanel : JPanel(BorderLayout()) {

    private val richPanel = RichStreamingPanel()
    private val tokenWidget = TokenBudgetWidget()
    private val planRenderer = PlanMarkdownRenderer()

    // Keep legacy panel reference for backward compat
    @Deprecated("Use getRichPanel() instead")
    private val outputPanel = StreamingOutputPanel()

    // --- Chat input ---
    private val chatInput = JBTextArea(2, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        font = JBUI.Fonts.label(13f)
        emptyText.setText("Ask the agent to do something...")
    }

    val sendButton = JButton("Send").apply {
        icon = AllIcons.Actions.Execute
        font = JBUI.Fonts.label(12f)
        putClientProperty("JButton.buttonType", "roundRect")
    }

    val cancelButton = JButton("Stop").apply {
        icon = AllIcons.Actions.Suspend
        isEnabled = false
        font = JBUI.Fonts.label(12f)
        putClientProperty("JButton.buttonType", "roundRect")
    }

    val newChatButton = JButton("New Chat").apply {
        icon = AllIcons.General.Add
        font = JBUI.Fonts.label(12f)
        putClientProperty("JButton.buttonType", "roundRect")
    }

    val settingsLink = JBLabel("<html><a href=''>Settings</a></html>").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = JBUI.Fonts.smallFont()
    }

    /** Callback invoked when the user submits a message. Set by AgentController. */
    var onSendMessage: ((String) -> Unit)? = null

    init {
        border = JBUI.Borders.empty()

        // ── North: compact toolbar ──
        val toolbar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.merge(
                JBUI.Borders.empty(4, 8),
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                true
            )
            val leftButtons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            leftButtons.add(cancelButton)
            leftButtons.add(newChatButton)
            add(leftButtons, BorderLayout.WEST)

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            rightPanel.add(tokenWidget)
            rightPanel.add(settingsLink)
            add(rightPanel, BorderLayout.EAST)
        }
        add(toolbar, BorderLayout.NORTH)

        // ── Center: rich output (chat history) ──
        add(richPanel, BorderLayout.CENTER)

        // ── South: chat input bar ──
        val inputBar = buildInputBar()
        add(inputBar, BorderLayout.SOUTH)

        // ── Wire Enter key ──
        chatInput.addKeyListener(object : KeyListener {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    submitMessage()
                }
            }
            override fun keyReleased(e: KeyEvent) {}
            override fun keyTyped(e: KeyEvent) {
                if (e.keyChar == '\n' && !e.isShiftDown) {
                    e.consume()
                }
            }
        })

        sendButton.addActionListener { submitMessage() }
    }

    private fun buildInputBar(): JPanel {
        val bar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.merge(
                JBUI.Borders.empty(6, 8),
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                true
            )
        }

        val inputScroll = JScrollPane(chatInput).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty()
            )
            preferredSize = Dimension(0, JBUI.scale(60))
            minimumSize = Dimension(0, JBUI.scale(40))
        }

        val sendPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyLeft(6)
            add(sendButton, BorderLayout.SOUTH)
        }

        bar.add(inputScroll, BorderLayout.CENTER)
        bar.add(sendPanel, BorderLayout.EAST)
        return bar
    }

    private fun submitMessage() {
        val text = chatInput.text?.trim() ?: return
        if (text.isBlank()) return

        chatInput.text = ""
        chatInput.requestFocusInWindow()
        onSendMessage?.invoke(text)
    }

    // ═══════════════════════════════════════════════════
    //  Public API — used by AgentController
    // ═══════════════════════════════════════════════════

    /** Get the rich streaming panel for direct content rendering. */
    fun getRichPanel(): RichStreamingPanel = richPanel

    /** Provides backward-compatible access to old panel. */
    fun getStreamingPanel(): StreamingOutputPanel = outputPanel

    /** Get the plan renderer for orchestrated mode. */
    fun getPlanRenderer(): PlanMarkdownRenderer = planRenderer

    fun updateProgress(step: String, tokensUsed: Int, maxTokens: Int) = runOnEdt {
        tokenWidget.update(tokensUsed, maxTokens)
    }

    fun showResult(text: String) = runOnEdt {
        richPanel.setText(text)
        cancelButton.isEnabled = false
        sendButton.isEnabled = true
        chatInput.isEnabled = true
    }

    fun reset() = runOnEdt {
        richPanel.clear()
        outputPanel.clear()
        tokenWidget.update(0, 0)
        cancelButton.isEnabled = false
        sendButton.isEnabled = true
        chatInput.isEnabled = true
        chatInput.text = ""
    }

    /** Show orchestration plan in a panel (replaces chat temporarily). */
    fun showOrchestrationPlan(tasks: List<AgentTask>) = runOnEdt {
        planRenderer.renderPlan(tasks)
        // For orchestrated mode, we could show a split view
        // For now, show a status in the rich panel
        richPanel.appendStatus(
            "Plan with ${tasks.size} tasks created. Executing...",
            RichStreamingPanel.StatusType.INFO
        )
    }

    /** Set the input bar to busy state (disable input, enable stop). */
    fun setBusy(busy: Boolean) = runOnEdt {
        chatInput.isEnabled = !busy
        sendButton.isEnabled = !busy
        cancelButton.isEnabled = busy
        if (!busy) {
            chatInput.requestFocusInWindow()
        }
    }

    /** Focus the chat input. */
    fun focusInput() = runOnEdt {
        chatInput.requestFocusInWindow()
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else SwingUtilities.invokeLater(action)
    }
}
