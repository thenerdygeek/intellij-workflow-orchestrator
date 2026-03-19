package com.workflow.orchestrator.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
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
 * Chat-first agent dashboard with JCEF (Chromium) rendering when available,
 * falling back to JEditorPane for environments without JCEF.
 *
 * Layout:
 * ┌──────────────────────────────────────────────────┐
 * │ [Stop] [New Chat]                     🪙 tokens  │
 * ├──────────────────────────────────────────────────┤
 * │  JCEF Browser (CSS3, animations, rounded badges) │
 * │  OR JEditorPane fallback (HTML 3.2)              │
 * ├──────────────────────────────────────────────────┤
 * │ Type a message...                        [Send]  │
 * │ Enter to send · Shift+Enter for new line         │
 * └──────────────────────────────────────────────────┘
 */
class AgentDashboardPanel(
    private val parentDisposable: Disposable? = null
) : JPanel(BorderLayout()) {

    companion object {
        private val LOG = Logger.getInstance(AgentDashboardPanel::class.java)
    }

    // ── Output panel: JCEF or fallback ──
    private val cefPanel: AgentCefPanel? = if (AgentCefPanel.isAvailable() && parentDisposable != null) {
        try {
            AgentCefPanel(parentDisposable).also {
                LOG.info("AgentDashboardPanel: using JCEF (Chromium) renderer")
            }
        } catch (e: Exception) {
            LOG.warn("AgentDashboardPanel: JCEF init failed, falling back to JEditorPane", e)
            null
        }
    } else {
        LOG.info("AgentDashboardPanel: JCEF not available, using JEditorPane fallback")
        null
    }
    private val fallbackPanel: RichStreamingPanel? = if (cefPanel == null) RichStreamingPanel() else null
    val usingJcef: Boolean get() = cefPanel != null

    // ── UI components ──
    private val tokenWidget = TokenBudgetWidget()

    private val chatInput = JBTextArea(2, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        font = JBUI.Fonts.label(13f)
        emptyText.setText("Ask the agent to do something...")
    }

    val sendButton = JButton("Send").apply {
        icon = AllIcons.Actions.Execute
        putClientProperty("JButton.buttonType", "roundRect")
    }
    val cancelButton = JButton("Stop").apply {
        icon = AllIcons.Actions.Suspend
        isEnabled = false
        putClientProperty("JButton.buttonType", "roundRect")
    }
    val newChatButton = JButton("New Chat").apply {
        icon = AllIcons.General.Add
        putClientProperty("JButton.buttonType", "roundRect")
    }
    val settingsLink = JBLabel("<html><a href=''>Settings</a></html>").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = JBUI.Fonts.smallFont()
    }

    var onSendMessage: ((String) -> Unit)? = null

    init {
        border = JBUI.Borders.empty()

        // ── North: toolbar ──
        val toolbar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.merge(
                JBUI.Borders.empty(4, 8),
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0), true
            )
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            left.add(cancelButton)
            left.add(newChatButton)
            add(left, BorderLayout.WEST)
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            right.add(tokenWidget)
            right.add(settingsLink)
            add(right, BorderLayout.EAST)
        }
        add(toolbar, BorderLayout.NORTH)

        // ── Center: JCEF or fallback ──
        val outputComponent = cefPanel ?: fallbackPanel!!
        add(outputComponent, BorderLayout.CENTER)

        // ── South: chat input ──
        add(buildInputBar(), BorderLayout.SOUTH)

        // ── Wire Enter key ──
        chatInput.addKeyListener(object : KeyListener {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume(); submitMessage()
                }
            }
            override fun keyReleased(e: KeyEvent) {}
            override fun keyTyped(e: KeyEvent) {
                if (e.keyChar == '\n' && !e.isShiftDown) e.consume()
            }
        })
        sendButton.addActionListener { submitMessage() }
    }

    private fun buildInputBar(): JPanel {
        val bar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.merge(
                JBUI.Borders.empty(6, 8),
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), true
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
        val hint = JBLabel("Enter to send · Shift+Enter for new line").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyTop(4)
        }
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(inputScroll, BorderLayout.CENTER)
        val sendPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyLeft(6)
            add(sendButton, BorderLayout.SOUTH)
        }
        bottomPanel.add(sendPanel, BorderLayout.EAST)
        bar.add(bottomPanel, BorderLayout.CENTER)
        bar.add(hint, BorderLayout.SOUTH)
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
    //  Delegate API — routes to JCEF or fallback
    // ═══════════════════════════════════════════════════

    fun startSession(task: String) {
        cefPanel?.startSession(task) ?: fallbackPanel?.startSession(task)
    }

    fun completeSession(
        tokensUsed: Int, iterations: Int, filesModified: List<String>,
        durationMs: Long, status: RichStreamingPanel.SessionStatus
    ) {
        cefPanel?.completeSession(tokensUsed, iterations, filesModified, durationMs, status)
            ?: fallbackPanel?.completeSession(tokensUsed, iterations, filesModified, durationMs, status)
    }

    fun appendStreamToken(token: String) {
        cefPanel?.appendStreamToken(token) ?: fallbackPanel?.appendStreamToken(token)
    }

    fun flushStreamBuffer() {
        cefPanel?.flushStreamBuffer() ?: fallbackPanel?.flushStreamBuffer()
    }

    fun appendToolCall(
        toolName: String, args: String = "",
        status: RichStreamingPanel.ToolCallStatus = RichStreamingPanel.ToolCallStatus.RUNNING
    ) {
        cefPanel?.appendToolCall(toolName, args, status) ?: fallbackPanel?.appendToolCall(toolName, args, status)
    }

    fun updateLastToolCall(
        status: RichStreamingPanel.ToolCallStatus, result: String = "", durationMs: Long = 0
    ) {
        cefPanel?.updateLastToolCall(status, result, durationMs)
            ?: fallbackPanel?.updateLastToolCall(status, result, durationMs)
    }

    fun appendEditDiff(filePath: String, oldText: String, newText: String, accepted: Boolean? = null) {
        cefPanel?.appendEditDiff(filePath, oldText, newText, accepted)
            ?: fallbackPanel?.appendEditDiff(filePath, oldText, newText, accepted)
    }

    fun appendStatus(message: String, type: RichStreamingPanel.StatusType = RichStreamingPanel.StatusType.INFO) {
        cefPanel?.appendStatus(message, type) ?: fallbackPanel?.appendStatus(message, type)
    }

    fun appendError(message: String) {
        cefPanel?.appendError(message) ?: fallbackPanel?.appendError(message)
    }

    fun updateProgress(step: String, tokensUsed: Int, maxTokens: Int) = runOnEdt {
        tokenWidget.update(tokensUsed, maxTokens)
    }

    fun showResult(text: String) = runOnEdt {
        cefPanel?.setText(text) ?: fallbackPanel?.setText(text)
        cancelButton.isEnabled = false
        sendButton.isEnabled = true
        chatInput.isEnabled = true
    }

    fun reset() = runOnEdt {
        cefPanel?.clear() ?: fallbackPanel?.clear()
        tokenWidget.update(0, 0)
        cancelButton.isEnabled = false
        sendButton.isEnabled = true
        chatInput.isEnabled = true
        chatInput.text = ""
    }

    fun showOrchestrationPlan(tasks: List<AgentTask>) = runOnEdt {
        appendStatus("Plan with ${tasks.size} tasks created. Executing...", RichStreamingPanel.StatusType.INFO)
    }

    fun setBusy(busy: Boolean) = runOnEdt {
        chatInput.isEnabled = !busy
        sendButton.isEnabled = !busy
        cancelButton.isEnabled = busy
        if (!busy) chatInput.requestFocusInWindow()
    }

    fun focusInput() = runOnEdt { chatInput.requestFocusInWindow() }

    // Legacy compat
    fun getRichPanel(): RichStreamingPanel = fallbackPanel ?: RichStreamingPanel()
    fun getStreamingPanel(): StreamingOutputPanel = StreamingOutputPanel()
    fun getPlanRenderer(): PlanMarkdownRenderer = PlanMarkdownRenderer()

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else SwingUtilities.invokeLater(action)
    }
}
