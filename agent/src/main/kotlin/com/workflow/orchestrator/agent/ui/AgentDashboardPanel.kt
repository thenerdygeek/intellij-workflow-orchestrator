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

    var onSendMessage: ((String) -> Unit)? = null

    /** Secondary panels (e.g. editor tabs) that receive all output calls. */
    private val mirrors = CopyOnWriteArrayList<AgentDashboardPanel>()

    /**
     * Cached state for replaying to late-joining mirrors (e.g. editor tab opened after
     * the conversation has already started). Stores the last known value for each
     * idempotent state setter so mirrors can be brought up to speed immediately.
     */
    @Volatile private var cachedModelName: String? = null
    /** When non-null, the model is in a fallback state and the value is the reason. Empty string = fallback ON, no reason. */
    @Volatile private var cachedModelFallback: String? = null
    @Volatile private var cachedModelListJson: String? = null
    @Volatile private var cachedSkillsJson: String? = null
    @Volatile private var cachedPlanMode: Boolean = false
    @Volatile private var cachedDebugLogVisible: Boolean = false
    @Volatile private var cachedChatAnimationsEnabled: Boolean = true
    @Volatile private var cachedBusy: Boolean = false
    @Volatile private var cachedInputLocked: Boolean = false

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
        cachedModelFallback?.let { panel.setModelFallbackState(true, it.ifBlank { null }) }
        cachedSkillsJson?.let { panel.updateSkillsList(it) }
        if (cachedPlanMode) panel.setPlanMode(true)
        if (cachedDebugLogVisible) panel.setDebugLogVisible(true)
        if (!cachedChatAnimationsEnabled) panel.setChatAnimationsEnabled(false)
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

    /**
     * Forward an action to all mirror panels and (optionally) record it for replay
     * to late-joining mirrors. Use this from delegate methods after pushing to the
     * underlying CEF/fallback panel.
     */
    private inline fun broadcast(replay: Boolean = true, crossinline action: (AgentDashboardPanel) -> Unit) {
        mirrors.forEach { action(it) }
        if (replay) recordReplay { p -> action(p) }
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
        broadcast(replay = false) { it.setBusy(busy) }
    }

    fun setSteeringMode(enabled: Boolean) {
        runOnEdt { cefPanel?.setSteeringMode(enabled) }
        broadcast(replay = false) { it.setSteeringMode(enabled) }
    }

    fun updateProgress(step: String, tokensUsed: Int, maxTokens: Int) {
        runOnEdt { cefPanel?.updateTokenBudget(tokensUsed, maxTokens) }
        broadcast(replay = false) { it.updateProgress(step, tokensUsed, maxTokens) }
    }

    fun setModelName(name: String) {
        cachedModelName = name
        runOnEdt {
            val shortName = name.substringAfterLast("::").ifBlank { name }
            cefPanel?.setModelName(shortName)
        }
        broadcast(replay = false) { it.setModelName(name) }
    }

    /**
     * Marks the active model as a fallback (or clears the fallback state).
     * Forwarded to the React model chip which renders an amber border + Zap
     * icon + tooltip when [isFallback] is true.
     */
    fun setModelFallbackState(isFallback: Boolean, reason: String?) {
        cachedModelFallback = if (isFallback) reason ?: "" else null
        runOnEdt { cefPanel?.setModelFallbackState(isFallback, reason) }
        broadcast(replay = false) { it.setModelFallbackState(isFallback, reason) }
    }

    fun updateModelList(modelsJson: String) {
        cachedModelListJson = modelsJson
        runOnEdt { cefPanel?.updateModelList(modelsJson) }
        broadcast(replay = false) { it.updateModelList(modelsJson) }
    }

    fun setInputLocked(locked: Boolean) {
        cachedInputLocked = locked
        runOnEdt { cefPanel?.setInputLocked(locked) }
        broadcast(replay = false) { it.setInputLocked(locked) }
    }

    fun showRetryButton(lastMessage: String) {
        cefPanel?.showRetryButton(lastMessage)
        broadcast(replay = false) { it.showRetryButton(lastMessage) }
    }

    fun focusInput() {
        runOnEdt { cefPanel?.focusInput() }
        broadcast(replay = false) { it.focusInput() }
    }

    fun updateSkillsList(skillsJson: String) {
        cachedSkillsJson = skillsJson
        cefPanel?.updateSkillsList(skillsJson)
        broadcast(replay = false) { it.updateSkillsList(skillsJson) }
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
        broadcast(replay = false) { it.showToolsPanel(toolsJson) }
    }

    fun setCefToolToggleCallback(onToggle: (String, Boolean) -> Unit) {
        cefPanel?.onToolToggled = onToggle
    }

    fun renderPlan(planJson: String) {
        cefPanel?.renderPlan(planJson)
            ?: fallbackPanel?.appendStatus("Plan created — approve in the chat panel", RichStreamingPanel.StatusType.INFO)
        broadcast { it.renderPlan(planJson) }
    }

    fun approvePlanInUi() {
        cefPanel?.approvePlanInUi()
        broadcast(replay = false) { it.approvePlanInUi() }
    }

    fun updatePlanStep(stepId: String, status: String) {
        cefPanel?.updatePlanStep(stepId, status)
        broadcast(replay = false) { it.updatePlanStep(stepId, status) }
    }

    fun replaceExecutionSteps(stepsJson: String) {
        cefPanel?.replaceExecutionSteps(stepsJson)
        broadcast(replay = false) { it.replaceExecutionSteps(stepsJson) }
    }

    fun setPlanCommentCount(count: Int) {
        cefPanel?.setPlanCommentCount(count)
        broadcast(replay = false) { it.setPlanCommentCount(count) }
    }

    fun updatePlanSummary(summary: String) {
        cefPanel?.updatePlanSummary(summary)
        broadcast(replay = false) { it.updatePlanSummary(summary) }
    }

    // ── Question wizard delegation ──

    fun showQuestions(questionsJson: String) {
        cefPanel?.showQuestions(questionsJson)
        broadcast(replay = false) { it.showQuestions(questionsJson) }
    }

    fun showQuestion(index: Int) {
        cefPanel?.showQuestion(index)
        broadcast(replay = false) { it.showQuestion(index) }
    }

    fun showQuestionSummary(summaryJson: String) {
        cefPanel?.showQuestionSummary(summaryJson)
        broadcast(replay = false) { it.showQuestionSummary(summaryJson) }
    }

    fun enableChatInput() {
        cefPanel?.enableChatInput()
        broadcast(replay = false) { it.enableChatInput() }
    }

    // ── Skill banner delegation ──

    fun showSkillBanner(name: String) {
        cefPanel?.showSkillBanner(name)
        broadcast(replay = false) { it.showSkillBanner(name) }
    }

    fun hideSkillBanner() {
        cefPanel?.hideSkillBanner()
        broadcast(replay = false) { it.hideSkillBanner() }
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
        broadcast(replay = false) { it.appendJiraCard(cardJson) }
    }

    fun appendSonarBadge(badgeJson: String) {
        cefPanel?.appendSonarBadge(badgeJson)
        broadcast(replay = false) { it.appendSonarBadge(badgeJson) }
    }

    fun showApproval(toolName: String, riskLevel: String, description: String, metadataJson: String, diffContent: String? = null) {
        cefPanel?.showApproval(toolName, riskLevel, description, metadataJson, diffContent)
        broadcast(replay = false) { it.showApproval(toolName, riskLevel, description, metadataJson, diffContent) }
    }

    fun showProcessInput(processId: String, description: String, prompt: String, command: String) {
        cefPanel?.showProcessInput(processId, description, prompt, command)
        broadcast(replay = false) { it.showProcessInput(processId, description, prompt, command) }
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
        broadcast { it.startSession(task) }
    }

    fun startSessionWithMentions(task: String, mentionsJson: String) {
        cefPanel?.startSessionWithMentions(task, mentionsJson)
            ?: fallbackPanel?.startSession(task)
        broadcast { it.startSession(task) }
    }

    fun appendUserMessage(text: String) {
        cefPanel?.appendUserMessage(text) ?: fallbackPanel?.appendUserMessage(text)
        broadcast { it.appendUserMessage(text) }
    }

    fun appendUserMessageWithMentions(text: String, mentionsJson: String) {
        cefPanel?.appendUserMessageWithMentions(text, mentionsJson)
            ?: fallbackPanel?.appendUserMessage(text)
        broadcast { it.appendUserMessage(text) }
    }

    fun finalizeQuestionsAsMessage() {
        cefPanel?.finalizeQuestionsAsMessage()
        broadcast { it.finalizeQuestionsAsMessage() }
    }

    fun completeSession(
        tokensUsed: Int, iterations: Int, filesModified: List<String>,
        durationMs: Long, status: RichStreamingPanel.SessionStatus
    ) {
        cefPanel?.completeSession(tokensUsed, iterations, filesModified, durationMs, status)
            ?: fallbackPanel?.completeSession(tokensUsed, iterations, filesModified, durationMs, status)
        broadcast { it.completeSession(tokensUsed, iterations, filesModified, durationMs, status) }
    }

    fun appendStreamToken(token: String) {
        cefPanel?.appendStreamToken(token) ?: fallbackPanel?.appendStreamToken(token)
        broadcast { it.appendStreamToken(token) }
    }

    fun flushStreamBuffer() {
        cefPanel?.flushStreamBuffer() ?: fallbackPanel?.flushStreamBuffer()
        broadcast { it.flushStreamBuffer() }
    }

    fun finalizeToolChain() {
        cefPanel?.finalizeToolChain()
        broadcast { it.finalizeToolChain() }
    }

    fun appendCompletionSummary(result: String, verifyCommand: String? = null) {
        cefPanel?.appendCompletionSummary(result, verifyCommand)
            ?: fallbackPanel?.appendStatus("Task completed: $result", RichStreamingPanel.StatusType.SUCCESS)
        broadcast { it.appendCompletionSummary(result, verifyCommand) }
    }

    fun appendToolCall(
        toolCallId: String = "",
        toolName: String, args: String = "",
        status: RichStreamingPanel.ToolCallStatus = RichStreamingPanel.ToolCallStatus.RUNNING
    ) {
        cefPanel?.appendToolCall(toolCallId, toolName, args, status) ?: fallbackPanel?.appendToolCall(toolName, args, status)
        broadcast { it.appendToolCall(toolCallId, toolName, args, status) }
    }

    fun updateLastToolCall(
        status: RichStreamingPanel.ToolCallStatus, result: String = "", durationMs: Long = 0,
        toolName: String = "", output: String? = null, diff: String? = null
    ) {
        cefPanel?.updateLastToolCall(status, result, durationMs, toolName, output, diff)
            ?: fallbackPanel?.updateLastToolCall(status, result, durationMs)
        broadcast { it.updateLastToolCall(status, result, durationMs, toolName, output, diff) }
    }

    fun appendToolOutput(toolCallId: String, chunk: String) {
        cefPanel?.appendToolOutput(toolCallId, chunk)
        broadcast(replay = false) { it.appendToolOutput(toolCallId, chunk) }
    }

    fun appendEditDiff(filePath: String, oldText: String, newText: String, accepted: Boolean? = null) {
        cefPanel?.appendEditDiff(filePath, oldText, newText, accepted)
            ?: fallbackPanel?.appendEditDiff(filePath, oldText, newText, accepted)
        broadcast { it.appendEditDiff(filePath, oldText, newText, accepted) }
    }

    fun appendDiffExplanation(title: String, diffSource: String) {
        cefPanel?.appendDiffExplanation(title, diffSource)
        broadcast { it.appendDiffExplanation(title, diffSource) }
    }

    fun appendStatus(message: String, type: RichStreamingPanel.StatusType = RichStreamingPanel.StatusType.INFO) {
        cefPanel?.appendStatus(message, type) ?: fallbackPanel?.appendStatus(message, type)
        broadcast { it.appendStatus(message, type) }
    }

    fun appendError(message: String) {
        cefPanel?.appendError(message) ?: fallbackPanel?.appendError(message)
        broadcast { it.appendError(message) }
    }

    /**
     * Push a debug log entry to the JCEF debug log panel.
     * No-op if JCEF is unavailable (fallback panel has no debug log).
     */
    fun setDebugLogVisible(visible: Boolean) {
        cachedDebugLogVisible = visible
        runOnEdt { cefPanel?.updateDebugLogVisibility(visible) }
        broadcast(replay = false) { it.setDebugLogVisible(visible) }
    }

    fun pushDebugLogEntry(level: String, event: String, detail: String, meta: Map<String, Any?>? = null) {
        runOnEdt { cefPanel?.pushDebugLogEntry(level, event, detail, meta) }
        broadcast(replay = false) { it.pushDebugLogEntry(level, event, detail, meta) }
    }

    fun reset() {
        replayLog.clear()
        runOnEdt { cefPanel?.clear() ?: fallbackPanel?.clear() }
        broadcast(replay = false) { it.reset() }
    }

    // ── Plan mode delegation ──

    fun setPlanMode(enabled: Boolean) {
        cachedPlanMode = enabled
        runOnEdt { cefPanel?.setPlanMode(enabled) }
        broadcast(replay = false) { it.setPlanMode(enabled) }
    }

    fun setRalphLoop(enabled: Boolean) {
        runOnEdt { cefPanel?.setRalphLoop(enabled) }
    }

    fun setChatAnimationsEnabled(enabled: Boolean) {
        cachedChatAnimationsEnabled = enabled
        runOnEdt { cefPanel?.setChatAnimationsEnabled(enabled) }
        broadcast(replay = false) { it.setChatAnimationsEnabled(enabled) }
    }

    // ── Sub-Agent boundary card delegation ──

    fun spawnSubAgent(agentId: String, label: String) {
        runOnEdt { cefPanel?.spawnSubAgent(agentId, label) }
        broadcast(replay = false) { it.spawnSubAgent(agentId, label) }
    }

    fun updateSubAgentIteration(agentId: String, iteration: Int) {
        runOnEdt { cefPanel?.updateSubAgentIteration(agentId, iteration) }
        broadcast(replay = false) { it.updateSubAgentIteration(agentId, iteration) }
    }

    fun addSubAgentToolCall(agentId: String, toolName: String, toolArgs: String) {
        runOnEdt { cefPanel?.addSubAgentToolCall(agentId, toolName, toolArgs) }
        broadcast(replay = false) { it.addSubAgentToolCall(agentId, toolName, toolArgs) }
    }

    fun updateSubAgentToolCall(agentId: String, toolName: String, result: String, durationMs: Long, isError: Boolean) {
        runOnEdt { cefPanel?.updateSubAgentToolCall(agentId, toolName, result, durationMs, isError) }
        broadcast(replay = false) { it.updateSubAgentToolCall(agentId, toolName, result, durationMs, isError) }
    }

    fun updateSubAgentMessage(agentId: String, textContent: String) {
        runOnEdt { cefPanel?.updateSubAgentMessage(agentId, textContent) }
        broadcast(replay = false) { it.updateSubAgentMessage(agentId, textContent) }
    }

    fun completeSubAgent(agentId: String, textContent: String, tokensUsed: Int, isError: Boolean) {
        runOnEdt { cefPanel?.completeSubAgent(agentId, textContent, tokensUsed, isError) }
        broadcast(replay = false) { it.completeSubAgent(agentId, textContent, tokensUsed, isError) }
    }

    fun renderArtifact(title: String, source: String) {
        runOnEdt { cefPanel?.renderArtifact(title, source) }
        broadcast(replay = false) { it.renderArtifact(title, source) }
    }

    fun setCefKillSubAgentCallback(onKill: (String) -> Unit) {
        cefPanel?.onKillSubAgent = onKill
    }

    // ── Edit stats + checkpoint delegation ──

    fun updateEditStats(added: Int, removed: Int, files: Int) {
        runOnEdt { cefPanel?.updateEditStats(added, removed, files) }
        broadcast(replay = false) { it.updateEditStats(added, removed, files) }
    }

    fun updateCheckpoints(checkpointsJson: String) {
        runOnEdt { cefPanel?.updateCheckpoints(checkpointsJson) }
        broadcast(replay = false) { it.updateCheckpoints(checkpointsJson) }
    }

    fun notifyRollback(rollbackJson: String) {
        runOnEdt { cefPanel?.notifyRollback(rollbackJson) }
        broadcast(replay = false) { it.notifyRollback(rollbackJson) }
    }

    fun setSmartWorkingPhrase(phrase: String) {
        runOnEdt { cefPanel?.setSmartWorkingPhrase(phrase) }
        broadcast(replay = false) { it.setSmartWorkingPhrase(phrase) }
    }

    fun setSessionTitle(title: String) {
        runOnEdt { cefPanel?.setSessionTitle(title) }
        broadcast(replay = false) { it.setSessionTitle(title) }
    }

    fun setCefRevertCheckpointCallback(onRevert: (String) -> Unit) {
        cefPanel?.onRevertCheckpoint = onRevert
    }

    // ── Queued steering message delegation ──

    fun addQueuedSteeringMessage(id: String, text: String) {
        runOnEdt { cefPanel?.addQueuedSteeringMessage(id, text) }
        broadcast(replay = false) { it.addQueuedSteeringMessage(id, text) }
    }

    fun removeQueuedSteeringMessage(id: String) {
        runOnEdt { cefPanel?.removeQueuedSteeringMessage(id) }
        broadcast(replay = false) { it.removeQueuedSteeringMessage(id) }
    }

    fun promoteQueuedSteeringMessages(ids: List<String>) {
        runOnEdt { cefPanel?.promoteQueuedSteeringMessages(ids) }
        broadcast(replay = false) { it.promoteQueuedSteeringMessages(ids) }
    }

    fun restoreInputText(text: String) {
        runOnEdt { cefPanel?.restoreInputText(text) }
        broadcast(replay = false) { it.restoreInputText(text) }
    }

    fun setCefCancelSteeringCallback(onCancel: (String) -> Unit) {
        cefPanel?.onCancelSteering = onCancel
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else invokeLater { action() }
    }
}
