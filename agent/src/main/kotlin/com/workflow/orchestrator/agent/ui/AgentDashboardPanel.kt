package com.workflow.orchestrator.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
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
    private val modelLabel = JBLabel("").apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor.GRAY
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Current LLM model \u2014 click to change in Settings"
    }

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
    val planModeToggle = JToggleButton("Plan").apply {
        icon = AllIcons.Actions.ListFiles
        toolTipText = "Plan mode \u2014 forces the agent to create an implementation plan before making changes"
        putClientProperty("JButton.buttonType", "roundRect")
        font = JBUI.Fonts.smallFont()
    }
    val toolsButton = JButton("Tools").apply {
        icon = AllIcons.Nodes.Plugin
        putClientProperty("JButton.buttonType", "roundRect")
        toolTipText = "View available agent tools"
    }
    val tracesButton = JButton("Traces").apply {
        icon = AllIcons.Actions.ListFiles
        putClientProperty("JButton.buttonType", "roundRect")
        toolTipText = "Open debug traces for recent agent sessions"
    }
    val settingsLink = JBLabel("<html><a href=''>Settings</a></html>").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = JBUI.Fonts.smallFont()
    }

    val isPlanMode: Boolean get() = planModeToggle.isSelected

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
            left.add(planModeToggle)
            left.add(toolsButton)
            add(left, BorderLayout.WEST)
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            right.add(tracesButton)
            right.add(modelLabel)
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
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    e.consume()
                    // Trigger cancel if agent is running
                    cancelButton.doClick()
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
    //  JCEF callback wiring — bridges JS actions to controller
    // ═══════════════════════════════════════════════════

    /**
     * Wire JCEF JS→Kotlin callbacks for undo, view-trace, and example prompts.
     * Called by [AgentController] after construction.
     */
    fun setCefCallbacks(
        onUndo: () -> Unit,
        onViewTrace: () -> Unit,
        onPromptSubmitted: (String) -> Unit
    ) {
        cefPanel?.onUndoRequested = onUndo
        cefPanel?.onViewTraceRequested = onViewTrace
        cefPanel?.onPromptSubmitted = onPromptSubmitted
    }

    fun setCefPlanCallbacks(onApprove: () -> Unit, onRevise: (String) -> Unit) {
        cefPanel?.onPlanApproved = onApprove
        cefPanel?.onPlanRevised = onRevise
    }

    fun showToolsPanel(toolsJson: String) {
        cefPanel?.showToolsPanel(toolsJson)
    }

    fun setCefToolToggleCallback(onToggle: (String, Boolean) -> Unit) {
        cefPanel?.onToolToggled = onToggle
    }

    fun renderPlan(planJson: String) {
        cefPanel?.renderPlan(planJson)
            ?: fallbackPanel?.appendStatus("Plan created — approve in the chat panel", RichStreamingPanel.StatusType.INFO)
    }

    fun updatePlanStep(stepId: String, status: String) {
        cefPanel?.updatePlanStep(stepId, status)
    }

    // ── Question wizard delegation ──

    fun showQuestions(questionsJson: String) {
        cefPanel?.showQuestions(questionsJson)
    }

    fun showQuestion(index: Int) {
        cefPanel?.showQuestion(index)
    }

    fun showQuestionSummary(summaryJson: String) {
        cefPanel?.showQuestionSummary(summaryJson)
    }

    fun enableChatInput() {
        cefPanel?.enableChatInput()
    }

    /** Disable the Swing chat input while JCEF question wizard is active. */
    fun disableChatInput() = runOnEdt {
        chatInput.isEnabled = false
        sendButton.isEnabled = false
        chatInput.text = ""
    }

    // ── Skill banner delegation ──

    fun showSkillBanner(name: String) {
        cefPanel?.showSkillBanner(name)
    }

    fun hideSkillBanner() {
        cefPanel?.hideSkillBanner()
    }

    /** Re-enable the Swing chat input after the question wizard completes. */
    fun enableSwingChatInput() = runOnEdt {
        chatInput.isEnabled = true
        sendButton.isEnabled = true
        chatInput.requestFocusInWindow()
    }

    fun setCefQuestionCallbacks(
        onAnswered: (String, String) -> Unit,
        onSkipped: (String) -> Unit,
        onChatAbout: (String, String, String) -> Unit,
        onSubmitted: () -> Unit,
        onCancelled: () -> Unit,
        onEdit: (String) -> Unit
    ) {
        cefPanel?.onQuestionAnswered = onAnswered
        cefPanel?.onQuestionSkipped = onSkipped
        cefPanel?.onChatAboutOption = onChatAbout
        cefPanel?.onQuestionsSubmitted = onSubmitted
        cefPanel?.onQuestionsCancelled = onCancelled
        cefPanel?.onEditQuestion = onEdit
    }

    // ═══════════════════════════════════════════════════
    //  Delegate API — routes to JCEF or fallback
    // ═══════════════════════════════════════════════════

    fun startSession(task: String) {
        cefPanel?.startSession(task) ?: fallbackPanel?.startSession(task)
    }

    fun appendUserMessage(text: String) {
        cefPanel?.appendUserMessage(text) ?: fallbackPanel?.appendUserMessage(text)
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

    fun setBusy(busy: Boolean) = runOnEdt {
        chatInput.isEnabled = true   // Always enabled — user can type mid-loop
        sendButton.isEnabled = true  // Always enabled — user can send mid-loop
        cancelButton.isEnabled = busy
        if (!busy) chatInput.requestFocusInWindow()
    }

    fun setModelName(name: String) = runOnEdt {
        // Show just the model name part (strip provider prefix)
        val shortName = name.substringAfterLast("::").ifBlank { name }
        modelLabel.text = shortName
    }

    fun focusInput() = runOnEdt { chatInput.requestFocusInWindow() }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else SwingUtilities.invokeLater(action)
    }
}
