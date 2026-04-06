package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JPanel
import com.intellij.openapi.application.invokeLater
import javax.swing.SwingUtilities

/**
 * Chat-first agent dashboard — thin JCEF wrapper.
 *
 * All toolbar, input bar, and token budget UI lives inside the JCEF panel
 * (React webview). This Kotlin panel just hosts the browser component
 * and delegates API calls through to [AgentCefPanel].
 *
 * Mirror panels can be registered via [addMirror] to receive every output call
 * (e.g. the "View in Editor" editor tab). Mirrors are autonomous panels with
 * their own JCEF instances; they do NOT mirror wiring/callback calls, only output.
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

    /** Secondary panels (e.g. editor tabs) that receive all output calls. */
    private val mirrors = CopyOnWriteArrayList<AgentDashboardPanel>()

    /**
     * Cached state for replaying to late-joining mirrors (e.g. editor tab opened after
     * the conversation has already started). Stores the last known value for each
     * idempotent state setter so mirrors can be brought up to speed immediately.
     */
    @Volatile var cachedModelName: String? = null; private set
    @Volatile var cachedModelListJson: String? = null; private set
    @Volatile var cachedSkillsJson: String? = null; private set
    @Volatile var cachedPlanMode: Boolean = false; private set
    @Volatile var cachedDebugLogVisible: Boolean = false; private set
    @Volatile var cachedBusy: Boolean = false; private set
    @Volatile var cachedInputLocked: Boolean = false; private set

    /** Ordered log of output calls to replay chat content to late-joining mirrors. */
    private val replayLog = java.util.concurrent.CopyOnWriteArrayList<(AgentDashboardPanel) -> Unit>()
    private val maxReplayLogSize = 5000

    fun addMirror(panel: AgentDashboardPanel) {
        mirrors.add(panel)
        replayStateTo(panel)
    }
    fun removeMirror(panel: AgentDashboardPanel) { mirrors.remove(panel) }

    /**
     * Replay cached state + chat history to a late-joining mirror panel.
     */
    private fun replayStateTo(panel: AgentDashboardPanel) {
        // Replay idempotent state
        cachedModelListJson?.let { panel.updateModelList(it) }
        cachedModelName?.let { panel.setModelName(it) }
        cachedSkillsJson?.let { panel.updateSkillsList(it) }
        if (cachedPlanMode) panel.setPlanMode(true)
        if (cachedDebugLogVisible) panel.setDebugLogVisible(true)
        if (cachedBusy) panel.setBusy(true)
        if (cachedInputLocked) panel.setInputLocked(true)
        // Replay chat content log
        for (action in replayLog) {
            try { action(panel) } catch (_: Exception) {}
        }
    }

    private fun recordReplay(action: (AgentDashboardPanel) -> Unit) {
        if (replayLog.size < maxReplayLogSize) {
            replayLog.add(action)
        }
    }

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
        onToggleRalphLoop: (Boolean) -> Unit = {},
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
        cefPanel?.onToggleRalphLoop = onToggleRalphLoop
        cefPanel?.onActivateSkill = onActivateSkill
        cefPanel?.onRequestFocusIde = onRequestFocusIde
        cefPanel?.onOpenSettings = onOpenSettings
        cefPanel?.onOpenToolsPanel = onOpenToolsPanel
    }

    /** Wire the "View in Editor" toolbar button. */
    fun setOnViewInEditor(action: () -> Unit) {
        cefPanel?.onViewInEditor = action
    }

    // ═══════════════════════════════════════════════════
    //  Delegated state methods — route to JCEF
    // ═══════════════════════════════════════════════════

    fun setBusy(busy: Boolean) {
        cachedBusy = busy
        runOnEdt { cefPanel?.setBusy(busy) }
        mirrors.forEach { it.setBusy(busy) }
    }

    fun setSteeringMode(enabled: Boolean) {
        runOnEdt { cefPanel?.setSteeringMode(enabled) }
        mirrors.forEach { it.setSteeringMode(enabled) }
    }

    fun updateProgress(step: String, tokensUsed: Int, maxTokens: Int) {
        runOnEdt { cefPanel?.updateTokenBudget(tokensUsed, maxTokens) }
        mirrors.forEach { it.updateProgress(step, tokensUsed, maxTokens) }
    }

    fun setModelName(name: String) {
        cachedModelName = name
        runOnEdt {
            val shortName = name.substringAfterLast("::").ifBlank { name }
            cefPanel?.setModelName(shortName)
        }
        mirrors.forEach { it.setModelName(name) }
    }

    fun updateModelList(modelsJson: String) {
        cachedModelListJson = modelsJson
        runOnEdt { cefPanel?.updateModelList(modelsJson) }
        mirrors.forEach { it.updateModelList(modelsJson) }
    }

    fun setInputLocked(locked: Boolean) {
        cachedInputLocked = locked
        runOnEdt { cefPanel?.setInputLocked(locked) }
        mirrors.forEach { it.setInputLocked(locked) }
    }

    fun showRetryButton(lastMessage: String) {
        cefPanel?.showRetryButton(lastMessage)
        mirrors.forEach { it.showRetryButton(lastMessage) }
    }

    fun focusInput() {
        runOnEdt { cefPanel?.focusInput() }
        mirrors.forEach { it.focusInput() }
    }

    fun updateSkillsList(skillsJson: String) {
        cachedSkillsJson = skillsJson
        cefPanel?.updateSkillsList(skillsJson)
        mirrors.forEach { it.updateSkillsList(skillsJson) }
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

    fun setCefRetryCallback(onRetry: () -> Unit) {
        cefPanel?.onRetryLastTask = onRetry
    }

    fun setCefPlanCallbacks(onApprove: () -> Unit, onRevise: (String) -> Unit) {
        cefPanel?.onPlanApproved = onApprove
        cefPanel?.onPlanRevised = onRevise
    }

    fun showToolsPanel(toolsJson: String) {
        cefPanel?.showToolsPanel(toolsJson)
        mirrors.forEach { it.showToolsPanel(toolsJson) }
    }

    fun setCefToolToggleCallback(onToggle: (String, Boolean) -> Unit) {
        cefPanel?.onToolToggled = onToggle
    }

    fun renderPlan(planJson: String) {
        cefPanel?.renderPlan(planJson)
            ?: fallbackPanel?.appendStatus("Plan created — approve in the chat panel", RichStreamingPanel.StatusType.INFO)
        mirrors.forEach { it.renderPlan(planJson) }
        recordReplay { p -> p.renderPlan(planJson) }
    }

    fun approvePlanInUi() {
        cefPanel?.approvePlanInUi()
        mirrors.forEach { it.approvePlanInUi() }
    }

    fun updatePlanStep(stepId: String, status: String) {
        cefPanel?.updatePlanStep(stepId, status)
        mirrors.forEach { it.updatePlanStep(stepId, status) }
    }

    fun replaceExecutionSteps(stepsJson: String) {
        cefPanel?.replaceExecutionSteps(stepsJson)
        mirrors.forEach { it.replaceExecutionSteps(stepsJson) }
    }

    fun setPlanCommentCount(count: Int) {
        cefPanel?.setPlanCommentCount(count)
        mirrors.forEach { it.setPlanCommentCount(count) }
    }

    fun updatePlanSummary(summary: String) {
        cefPanel?.updatePlanSummary(summary)
        mirrors.forEach { it.updatePlanSummary(summary) }
    }

    // ── Question wizard delegation ──

    fun showQuestions(questionsJson: String) {
        cefPanel?.showQuestions(questionsJson)
        mirrors.forEach { it.showQuestions(questionsJson) }
    }

    fun showQuestion(index: Int) {
        cefPanel?.showQuestion(index)
        mirrors.forEach { it.showQuestion(index) }
    }

    fun showQuestionSummary(summaryJson: String) {
        cefPanel?.showQuestionSummary(summaryJson)
        mirrors.forEach { it.showQuestionSummary(summaryJson) }
    }

    fun enableChatInput() {
        cefPanel?.enableChatInput()
        mirrors.forEach { it.enableChatInput() }
    }

    // ── Skill banner delegation ──

    fun showSkillBanner(name: String) {
        cefPanel?.showSkillBanner(name)
        mirrors.forEach { it.showSkillBanner(name) }
    }

    fun hideSkillBanner() {
        cefPanel?.hideSkillBanner()
        mirrors.forEach { it.hideSkillBanner() }
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
        mirrors.forEach { it.appendJiraCard(cardJson) }
    }

    fun appendSonarBadge(badgeJson: String) {
        cefPanel?.appendSonarBadge(badgeJson)
        mirrors.forEach { it.appendSonarBadge(badgeJson) }
    }

    fun showApproval(toolName: String, riskLevel: String, description: String, metadataJson: String, diffContent: String? = null) {
        cefPanel?.showApproval(toolName, riskLevel, description, metadataJson, diffContent)
        mirrors.forEach { it.showApproval(toolName, riskLevel, description, metadataJson, diffContent) }
    }

    fun showProcessInput(processId: String, description: String, prompt: String, command: String) {
        cefPanel?.showProcessInput(processId, description, prompt, command)
        mirrors.forEach { it.showProcessInput(processId, description, prompt, command) }
    }

    fun setCefProcessInputCallbacks(onInput: (String) -> Unit) {
        cefPanel?.onProcessInputResolved = onInput
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

    fun setCefKillCallback(onKill: (String) -> Unit) {
        cefPanel?.onKillToolCall = onKill
    }

    fun setCefEditorTabCallback(onOpen: (String) -> Unit) {
        cefPanel?.onOpenInEditorTab = onOpen
    }

    fun setCefFocusPlanEditorCallback(onFocus: () -> Unit) {
        cefPanel?.onFocusPlanEditor = onFocus
    }

    fun setCefRevisePlanFromEditorCallback(onRevise: () -> Unit) {
        cefPanel?.onRevisePlanFromEditor = onRevise
    }

    // ═══════════════════════════════════════════════════
    //  Delegate API — routes to JCEF or fallback
    // ═══════════════════════════════════════════════════

    fun startSession(task: String) {
        cefPanel?.startSession(task) ?: fallbackPanel?.startSession(task)
        mirrors.forEach { it.startSession(task) }
        recordReplay { p -> p.startSession(task) }
    }

    fun startSessionWithMentions(task: String, mentionsJson: String) {
        cefPanel?.startSessionWithMentions(task, mentionsJson)
            ?: fallbackPanel?.startSession(task)
        mirrors.forEach { it.startSession(task) }
        recordReplay { p -> p.startSession(task) }
    }

    fun appendUserMessage(text: String) {
        cefPanel?.appendUserMessage(text) ?: fallbackPanel?.appendUserMessage(text)
        mirrors.forEach { it.appendUserMessage(text) }
        recordReplay { p -> p.appendUserMessage(text) }
    }

    fun appendUserMessageWithMentions(text: String, mentionsJson: String) {
        cefPanel?.appendUserMessageWithMentions(text, mentionsJson)
            ?: fallbackPanel?.appendUserMessage(text)
        mirrors.forEach { it.appendUserMessage(text) }
        recordReplay { p -> p.appendUserMessage(text) }
    }

    fun completeSession(
        tokensUsed: Int, iterations: Int, filesModified: List<String>,
        durationMs: Long, status: RichStreamingPanel.SessionStatus
    ) {
        cefPanel?.completeSession(tokensUsed, iterations, filesModified, durationMs, status)
            ?: fallbackPanel?.completeSession(tokensUsed, iterations, filesModified, durationMs, status)
        mirrors.forEach { it.completeSession(tokensUsed, iterations, filesModified, durationMs, status) }
        recordReplay { p -> p.completeSession(tokensUsed, iterations, filesModified, durationMs, status) }
    }

    fun appendStreamToken(token: String) {
        cefPanel?.appendStreamToken(token) ?: fallbackPanel?.appendStreamToken(token)
        mirrors.forEach { it.appendStreamToken(token) }
        recordReplay { p -> p.appendStreamToken(token) }
    }

    fun flushStreamBuffer() {
        cefPanel?.flushStreamBuffer() ?: fallbackPanel?.flushStreamBuffer()
        mirrors.forEach { it.flushStreamBuffer() }
        recordReplay { p -> p.flushStreamBuffer() }
    }

    fun finalizeToolChain() {
        cefPanel?.finalizeToolChain()
        mirrors.forEach { it.finalizeToolChain() }
        recordReplay { p -> p.finalizeToolChain() }
    }

    fun appendCompletionSummary(result: String, verifyCommand: String? = null) {
        cefPanel?.appendCompletionSummary(result, verifyCommand)
            ?: fallbackPanel?.appendStatus("Task completed: $result", RichStreamingPanel.StatusType.SUCCESS)
        mirrors.forEach { it.appendCompletionSummary(result, verifyCommand) }
        recordReplay { p -> p.appendCompletionSummary(result, verifyCommand) }
    }

    fun appendToolCall(
        toolCallId: String = "",
        toolName: String, args: String = "",
        status: RichStreamingPanel.ToolCallStatus = RichStreamingPanel.ToolCallStatus.RUNNING
    ) {
        cefPanel?.appendToolCall(toolCallId, toolName, args, status) ?: fallbackPanel?.appendToolCall(toolName, args, status)
        mirrors.forEach { it.appendToolCall(toolCallId, toolName, args, status) }
        recordReplay { p -> p.appendToolCall(toolCallId, toolName, args, status) }
    }

    fun updateLastToolCall(
        status: RichStreamingPanel.ToolCallStatus, result: String = "", durationMs: Long = 0,
        toolName: String = "", output: String? = null, diff: String? = null
    ) {
        cefPanel?.updateLastToolCall(status, result, durationMs, toolName, output, diff)
            ?: fallbackPanel?.updateLastToolCall(status, result, durationMs)
        mirrors.forEach { it.updateLastToolCall(status, result, durationMs, toolName, output, diff) }
        recordReplay { p -> p.updateLastToolCall(status, result, durationMs, toolName, output, diff) }
    }

    fun appendToolOutput(toolCallId: String, chunk: String) {
        cefPanel?.appendToolOutput(toolCallId, chunk)
        mirrors.forEach { it.appendToolOutput(toolCallId, chunk) }
    }

    fun appendEditDiff(filePath: String, oldText: String, newText: String, accepted: Boolean? = null) {
        cefPanel?.appendEditDiff(filePath, oldText, newText, accepted)
            ?: fallbackPanel?.appendEditDiff(filePath, oldText, newText, accepted)
        mirrors.forEach { it.appendEditDiff(filePath, oldText, newText, accepted) }
        recordReplay { p -> p.appendEditDiff(filePath, oldText, newText, accepted) }
    }

    fun appendDiffExplanation(title: String, diffSource: String) {
        cefPanel?.appendDiffExplanation(title, diffSource)
        mirrors.forEach { it.appendDiffExplanation(title, diffSource) }
        recordReplay { p -> p.appendDiffExplanation(title, diffSource) }
    }

    fun appendStatus(message: String, type: RichStreamingPanel.StatusType = RichStreamingPanel.StatusType.INFO) {
        cefPanel?.appendStatus(message, type) ?: fallbackPanel?.appendStatus(message, type)
        mirrors.forEach { it.appendStatus(message, type) }
        recordReplay { p -> p.appendStatus(message, type) }
    }

    fun appendError(message: String) {
        cefPanel?.appendError(message) ?: fallbackPanel?.appendError(message)
        mirrors.forEach { it.appendError(message) }
        recordReplay { p -> p.appendError(message) }
    }

    /**
     * Push a debug log entry to the JCEF debug log panel.
     * No-op if JCEF is unavailable (fallback panel has no debug log).
     */
    fun setDebugLogVisible(visible: Boolean) {
        cachedDebugLogVisible = visible
        runOnEdt { cefPanel?.updateDebugLogVisibility(visible) }
        mirrors.forEach { it.setDebugLogVisible(visible) }
    }

    fun pushDebugLogEntry(level: String, event: String, detail: String, meta: Map<String, Any?>? = null) {
        runOnEdt { cefPanel?.pushDebugLogEntry(level, event, detail, meta) }
        mirrors.forEach { it.pushDebugLogEntry(level, event, detail, meta) }
    }

    fun reset() {
        replayLog.clear()
        runOnEdt { cefPanel?.clear() ?: fallbackPanel?.clear() }
        mirrors.forEach { it.reset() }
    }

    // ── Plan mode delegation ──

    fun setPlanMode(enabled: Boolean) {
        cachedPlanMode = enabled
        runOnEdt { cefPanel?.setPlanMode(enabled) }
        mirrors.forEach { it.setPlanMode(enabled) }
    }

    fun setRalphLoop(enabled: Boolean) {
        runOnEdt { cefPanel?.setRalphLoop(enabled) }
    }

    // ── Sub-Agent boundary card delegation ──

    fun spawnSubAgent(agentId: String, label: String) {
        runOnEdt { cefPanel?.spawnSubAgent(agentId, label) }
        mirrors.forEach { it.spawnSubAgent(agentId, label) }
    }

    fun updateSubAgentIteration(agentId: String, iteration: Int) {
        runOnEdt { cefPanel?.updateSubAgentIteration(agentId, iteration) }
        mirrors.forEach { it.updateSubAgentIteration(agentId, iteration) }
    }

    fun addSubAgentToolCall(agentId: String, toolName: String, toolArgs: String) {
        runOnEdt { cefPanel?.addSubAgentToolCall(agentId, toolName, toolArgs) }
        mirrors.forEach { it.addSubAgentToolCall(agentId, toolName, toolArgs) }
    }

    fun updateSubAgentToolCall(agentId: String, toolName: String, result: String, durationMs: Long, isError: Boolean) {
        runOnEdt { cefPanel?.updateSubAgentToolCall(agentId, toolName, result, durationMs, isError) }
        mirrors.forEach { it.updateSubAgentToolCall(agentId, toolName, result, durationMs, isError) }
    }

    fun updateSubAgentMessage(agentId: String, textContent: String) {
        runOnEdt { cefPanel?.updateSubAgentMessage(agentId, textContent) }
        mirrors.forEach { it.updateSubAgentMessage(agentId, textContent) }
    }

    fun completeSubAgent(agentId: String, textContent: String, tokensUsed: Int, isError: Boolean) {
        runOnEdt { cefPanel?.completeSubAgent(agentId, textContent, tokensUsed, isError) }
        mirrors.forEach { it.completeSubAgent(agentId, textContent, tokensUsed, isError) }
    }

    fun renderArtifact(title: String, source: String) {
        runOnEdt { cefPanel?.renderArtifact(title, source) }
        mirrors.forEach { it.renderArtifact(title, source) }
    }

    fun setCefKillSubAgentCallback(onKill: (String) -> Unit) {
        cefPanel?.onKillSubAgent = onKill
    }

    // ── Edit stats + checkpoint delegation ──

    fun updateEditStats(added: Int, removed: Int, files: Int) {
        runOnEdt { cefPanel?.updateEditStats(added, removed, files) }
        mirrors.forEach { it.updateEditStats(added, removed, files) }
    }

    fun updateCheckpoints(checkpointsJson: String) {
        runOnEdt { cefPanel?.updateCheckpoints(checkpointsJson) }
        mirrors.forEach { it.updateCheckpoints(checkpointsJson) }
    }

    fun notifyRollback(rollbackJson: String) {
        runOnEdt { cefPanel?.notifyRollback(rollbackJson) }
        mirrors.forEach { it.notifyRollback(rollbackJson) }
    }

    fun setSmartWorkingPhrase(phrase: String) {
        runOnEdt { cefPanel?.setSmartWorkingPhrase(phrase) }
        mirrors.forEach { it.setSmartWorkingPhrase(phrase) }
    }

    fun setSessionTitle(title: String) {
        runOnEdt { cefPanel?.setSessionTitle(title) }
        mirrors.forEach { it.setSessionTitle(title) }
    }

    fun setCefRevertCheckpointCallback(onRevert: (String) -> Unit) {
        cefPanel?.onRevertCheckpoint = onRevert
    }

    // ── Queued steering message delegation ──

    fun addQueuedSteeringMessage(id: String, text: String) {
        runOnEdt { cefPanel?.addQueuedSteeringMessage(id, text) }
        mirrors.forEach { it.addQueuedSteeringMessage(id, text) }
    }

    fun removeQueuedSteeringMessage(id: String) {
        runOnEdt { cefPanel?.removeQueuedSteeringMessage(id) }
        mirrors.forEach { it.removeQueuedSteeringMessage(id) }
    }

    fun promoteQueuedSteeringMessages(ids: List<String>) {
        runOnEdt { cefPanel?.promoteQueuedSteeringMessages(ids) }
        mirrors.forEach { it.promoteQueuedSteeringMessages(ids) }
    }

    fun restoreInputText(text: String) {
        runOnEdt { cefPanel?.restoreInputText(text) }
        mirrors.forEach { it.restoreInputText(text) }
    }

    fun setCefCancelSteeringCallback(onCancel: (String) -> Unit) {
        cefPanel?.onCancelSteering = onCancel
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else invokeLater { action() }
    }
}
