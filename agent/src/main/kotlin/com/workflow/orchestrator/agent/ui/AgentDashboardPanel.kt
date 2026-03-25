package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Chat-first agent dashboard — thin JCEF wrapper.
 *
 * All toolbar, input bar, and token budget UI lives inside the JCEF panel
 * (React webview). This Kotlin panel just hosts the browser component
 * and delegates API calls through to [AgentCefPanel].
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

    var onSendMessage: ((String) -> Unit)? = null

    init {
        border = JBUI.Borders.empty()
        val outputComponent = cefPanel ?: fallbackPanel!!
        add(outputComponent, BorderLayout.CENTER)
    }

    // ═══════════════════════════════════════════════════
    //  JCEF action bridge — single entry point for controller
    // ═══════════════════════════════════════════════════

    fun setCefActionCallbacks(
        onCancel: () -> Unit,
        onNewChat: () -> Unit,
        onSendMessage: (String) -> Unit,
        onChangeModel: (String) -> Unit,
        onTogglePlanMode: (Boolean) -> Unit,
        onActivateSkill: (String) -> Unit,
        onRequestFocusIde: () -> Unit,
        onOpenSettings: () -> Unit,
        onOpenToolsPanel: () -> Unit
    ) {
        cefPanel?.onCancelTask = onCancel
        cefPanel?.onNewChat = onNewChat
        cefPanel?.onSendMessage = onSendMessage
        cefPanel?.onChangeModel = onChangeModel
        cefPanel?.onTogglePlanMode = onTogglePlanMode
        cefPanel?.onActivateSkill = onActivateSkill
        cefPanel?.onRequestFocusIde = onRequestFocusIde
        cefPanel?.onOpenSettings = onOpenSettings
        cefPanel?.onOpenToolsPanel = onOpenToolsPanel
    }

    // ═══════════════════════════════════════════════════
    //  Delegated state methods — route to JCEF
    // ═══════════════════════════════════════════════════

    fun setBusy(busy: Boolean) = runOnEdt {
        cefPanel?.setBusy(busy)
        // No fallback needed — RichStreamingPanel has no input controls to disable
    }

    fun updateProgress(step: String, tokensUsed: Int, maxTokens: Int) = runOnEdt {
        cefPanel?.updateTokenBudget(tokensUsed, maxTokens)
    }

    fun setModelName(name: String) = runOnEdt {
        val shortName = name.substringAfterLast("::").ifBlank { name }
        cefPanel?.setModelName(shortName)
    }

    fun updateModelList(modelsJson: String) = runOnEdt {
        cefPanel?.updateModelList(modelsJson)
    }

    fun setInputLocked(locked: Boolean) = runOnEdt {
        cefPanel?.setInputLocked(locked)
        // No fallback needed — RichStreamingPanel has no input controls
    }

    fun showRetryButton(lastMessage: String) {
        cefPanel?.showRetryButton(lastMessage)
    }

    fun focusInput() = runOnEdt {
        cefPanel?.focusInput()
    }

    fun updateSkillsList(skillsJson: String) {
        cefPanel?.updateSkillsList(skillsJson)
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

    // ── Skill banner delegation ──

    fun showSkillBanner(name: String) {
        cefPanel?.showSkillBanner(name)
    }

    fun hideSkillBanner() {
        cefPanel?.hideSkillBanner()
    }

    fun setCefMentionCallbacks(onSendWithMentions: (String, String) -> Unit) {
        cefPanel?.onSendMessageWithMentions = onSendWithMentions
    }

    fun setMentionSearchProvider(provider: MentionSearchProvider) {
        cefPanel?.mentionSearchProvider = provider
    }

    fun setCefNavigationCallbacks(onNavigateToFile: (String, Int) -> Unit) {
        cefPanel?.onNavigateToFile = onNavigateToFile
    }

    fun appendJiraCard(cardJson: String) {
        cefPanel?.appendJiraCard(cardJson)
    }

    fun appendSonarBadge(badgeJson: String) {
        cefPanel?.appendSonarBadge(badgeJson)
    }

    fun showApproval(toolName: String, riskLevel: String, description: String, metadataJson: String) {
        cefPanel?.showApproval(toolName, riskLevel, description, metadataJson)
    }

    fun setCefSkillCallbacks(onDismiss: () -> Unit) {
        cefPanel?.onSkillDismissed = onDismiss
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

    fun setCefApprovalCallbacks(onApprove: () -> Unit, onDeny: () -> Unit, onAllowForSession: ((String) -> Unit)? = null) {
        cefPanel?.onApproveToolCall = onApprove
        cefPanel?.onDenyToolCall = onDeny
        cefPanel?.onAllowToolForSession = onAllowForSession
    }

    fun setCefInteractiveHtmlCallback(onMessage: (String) -> Unit) {
        cefPanel?.onInteractiveHtmlMessage = onMessage
    }

    fun setCefDiffHunkCallbacks(onAccept: (String, Int, String?) -> Unit, onReject: (String, Int) -> Unit) {
        cefPanel?.onAcceptDiffHunk = onAccept
        cefPanel?.onRejectDiffHunk = onReject
    }

    fun setCefEditorTabCallback(onOpen: (String) -> Unit) {
        cefPanel?.onOpenInEditorTab = onOpen
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

    fun finalizeToolChain() {
        cefPanel?.finalizeToolChain()
    }

    fun appendToolCall(
        toolName: String, args: String = "",
        status: RichStreamingPanel.ToolCallStatus = RichStreamingPanel.ToolCallStatus.RUNNING
    ) {
        cefPanel?.appendToolCall(toolName, args, status) ?: fallbackPanel?.appendToolCall(toolName, args, status)
    }

    fun updateLastToolCall(
        status: RichStreamingPanel.ToolCallStatus, result: String = "", durationMs: Long = 0,
        toolName: String = ""
    ) {
        cefPanel?.updateLastToolCall(status, result, durationMs, toolName)
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

    /**
     * Push a debug log entry to the JCEF debug log panel.
     * No-op if JCEF is unavailable (fallback panel has no debug log).
     */
    fun setDebugLogVisible(visible: Boolean) = runOnEdt {
        cefPanel?.updateDebugLogVisibility(visible)
    }

    fun pushDebugLogEntry(level: String, event: String, detail: String, meta: Map<String, Any?>? = null) = runOnEdt {
        cefPanel?.pushDebugLogEntry(level, event, detail, meta)
    }

    fun showResult(text: String) = runOnEdt {
        cefPanel?.setText(text) ?: fallbackPanel?.setText(text)
    }

    fun reset() = runOnEdt {
        cefPanel?.clear() ?: fallbackPanel?.clear()
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else SwingUtilities.invokeLater(action)
    }
}
