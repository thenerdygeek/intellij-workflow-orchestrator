package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.agent.tools.CompletionData
import com.workflow.orchestrator.agent.tools.subagent.AgentConfigLoader
import com.workflow.orchestrator.agent.tools.subagent.SubagentToolName
import java.awt.BorderLayout
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JPanel
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
 *
 * Lifecycle (P0-6/B4): the panel is [Disposable]. When [parentDisposable] is null
 * (the tool-window tab path) the panel parents the JCEF browser to ITSELF, so the
 * tool-window factory's `content.setDisposer(panel)` wiring cascades
 * Content.dispose() -> panel -> AgentCefPanel (Chromium) + everything the CEF
 * panel registered against its parent (scope cancel, scheme-handler detach).
 * Callers that pass an explicit [parentDisposable] (e.g. AgentChatEditorTab's
 * editor-scoped disposer) keep ownership of the CEF lifecycle, unchanged.
 */
class AgentDashboardPanel(
    parentDisposable: Disposable? = null
) : JPanel(BorderLayout()), Disposable {

    companion object {
        private val LOG = Logger.getInstance(AgentDashboardPanel::class.java)
    }

    /** True when this panel owns the JCEF lifecycle (no external parent was supplied). */
    private val ownsCefLifecycle: Boolean = parentDisposable == null

    /** Disposer parent handed to [AgentCefPanel] -- external owner, or this panel itself. */
    private val cefLifecycleParent: Disposable = parentDisposable ?: this

    // ── Output panel: JCEF or fallback ──
    private val cefPanel: AgentCefPanel? = if (AgentCefPanel.isAvailable()) {
        try {
            AgentCefPanel(cefLifecycleParent).also {
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
        onActivateSkill: (String) -> Unit,
        onRequestFocusIde: () -> Unit,
        onOpenSettings: () -> Unit,
        onOpenMemorySettings: () -> Unit = {},
        onOpenToolsPanel: () -> Unit,
        onRequestModelList: () -> Unit = {}
    ) {
        cefPanel?.onCancelTask = onCancel
        cefPanel?.onNewChat = onNewChat
        cefPanel?.onSendMessage = onSendMessage
        cefPanel?.onChangeModel = onChangeModel
        cefPanel?.onTogglePlanMode = onTogglePlanMode
        cefPanel?.onActivateSkill = onActivateSkill
        cefPanel?.onRequestFocusIde = onRequestFocusIde
        cefPanel?.onOpenSettings = onOpenSettings
        cefPanel?.onOpenMemorySettings = onOpenMemorySettings
        cefPanel?.onOpenToolsPanel = onOpenToolsPanel
        cefPanel?.onRequestModelList = onRequestModelList
    }

    /** Wire the "View in Editor" toolbar button. */
    fun setOnViewInEditor(action: () -> Unit) {
        cefPanel?.onViewInEditor = action
    }

    /** Wire the "Compact context" toolbar button. Param `force` bypasses the 70% utilization floor. */
    fun setOnCompactContext(action: (Boolean) -> Unit) {
        cefPanel?.onCompactContext = action
    }

    // ═══════════════════════════════════════════════════
    //  Delegated state methods — route to JCEF
    // ═══════════════════════════════════════════════════

    fun setBusy(busy: Boolean) {
        LOG.info("[UI State] setBusy($busy)")
        cachedBusy = busy
        runOnEdt { cefPanel?.setBusy(busy) }
        broadcast(replay = false) { it.setBusy(busy) }
    }

    fun setCompactionState(active: Boolean, phase: String) {
        LOG.info("[UI State] setCompactionState(active=$active, phase='$phase')")
        runOnEdt { cefPanel?.setCompactionState(active, phase) }
        broadcast(replay = false) { it.setCompactionState(active, phase) }
    }

    fun insertCompactionMarker(
        tokensBefore: Int,
        tokensAfter: Int,
        messagesBefore: Int,
        messagesAfter: Int,
        ranLlmSummary: Boolean,
    ) {
        runOnEdt { cefPanel?.insertCompactionMarker(tokensBefore, tokensAfter, messagesBefore, messagesAfter, ranLlmSummary) }
        broadcast(replay = false) { it.insertCompactionMarker(tokensBefore, tokensAfter, messagesBefore, messagesAfter, ranLlmSummary) }
    }

    fun setSteeringMode(enabled: Boolean) {
        LOG.info("[UI State] setSteeringMode($enabled)")
        runOnEdt { cefPanel?.setSteeringMode(enabled) }
        broadcast(replay = false) { it.setSteeringMode(enabled) }
    }

    fun updateProgress(step: String, tokensUsed: Int, maxTokens: Int) {
        runOnEdt { cefPanel?.updateTokenBudget(tokensUsed, maxTokens) }
        broadcast(replay = false) { it.updateProgress(step, tokensUsed, maxTokens) }
    }

    fun updateSessionStats(modelId: String?, tokensIn: Long, tokensOut: Long, costUsd: Double?) {
        runOnEdt { cefPanel?.updateSessionStats(modelId, tokensIn, tokensOut, costUsd) }
    }

    /**
     * Push current memory stats (core memory total chars + archival entry count) to the
     * TopBar memory indicator in the chat UI. Clicking the indicator opens Settings.
     */
    fun updateMemoryStats(coreChars: Int, archivalCount: Int) {
        runOnEdt { cefPanel?.updateMemoryStats(coreChars, archivalCount) }
        broadcast(replay = false) { it.updateMemoryStats(coreChars, archivalCount) }
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
        LOG.info("[UI State] setInputLocked($locked)")
        cachedInputLocked = locked
        runOnEdt { cefPanel?.setInputLocked(locked) }
        broadcast(replay = false) { it.setInputLocked(locked) }
    }

    fun showRetryButton(kind: String, caption: String) {
        cefPanel?.showRetryButton(kind, caption)
        broadcast(replay = false) { it.showRetryButton(kind, caption) }
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

    fun setCefPlanDismissCallback(onDismiss: () -> Unit) {
        cefPanel?.onPlanDismissed = onDismiss
    }

    fun clearPlanInUi() {
        cefPanel?.clearPlanInUi()
        broadcast(replay = false) { it.clearPlanInUi() }
    }

    // ── Handoff card delegation (new_task confirm flow) ──

    fun setCefHandoffCallbacks(onStartFresh: () -> Unit, onKeepChatting: () -> Unit) {
        cefPanel?.onHandoffStartFresh = onStartFresh
        cefPanel?.onHandoffKeepChatting = onKeepChatting
    }

    fun renderHandoff(handoffJson: String) {
        cefPanel?.renderHandoff(handoffJson)
        broadcast { it.renderHandoff(handoffJson) }
    }

    fun clearHandoffInUi() {
        cefPanel?.clearHandoffInUi()
        broadcast(replay = false) { it.clearHandoffInUi() }
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

    fun showToast(message: String, type: String = "info", durationMs: Int = 3000) {
        runOnEdt { cefPanel?.showToast(message, type, durationMs) }
        broadcast(replay = false) { it.showToast(message, type, durationMs) }
    }

    fun setCefMentionCallbacks(onSendWithMentions: (String, String, String?) -> Unit) {
        cefPanel?.onSendMessageWithMentions = onSendWithMentions
    }

    fun setMentionSearchProvider(provider: MentionSearchProvider) {
        cefPanel?.mentionSearchProvider = provider
    }

    fun setCefNavigationCallbacks(onNavigateToFile: (String, Int) -> Unit) {
        cefPanel?.onNavigateToFile = onNavigateToFile
    }

    /** Wire the batch path-validation bridge. Params: (pathsJson, callbackName). */
    var onValidatePaths: ((String, String) -> Unit)?
        get() = cefPanel?.onValidatePaths
        set(value) { cefPanel?.onValidatePaths = value }

    var onResolveSymbols: ((String, String) -> Unit)?
        get() = cefPanel?.onResolveSymbols
        set(value) { cefPanel?.onResolveSymbols = value }

    /**
     * Execute raw JavaScript in the JCEF browser, queueing it until the page is loaded.
     * Used to push async results (e.g. path validation) back to a named window callback.
     */
    fun callJs(code: String) {
        cefPanel?.callJs(code)
    }

    fun appendJiraCard(cardJson: String) {
        cefPanel?.appendJiraCard(cardJson)
        broadcast(replay = false) { it.appendJiraCard(cardJson) }
    }

    fun appendSonarBadge(badgeJson: String) {
        cefPanel?.appendSonarBadge(badgeJson)
        broadcast(replay = false) { it.appendSonarBadge(badgeJson) }
    }

    fun showApproval(
        toolName: String,
        riskLevel: String,
        description: String,
        metadataJson: String,
        diffContent: String? = null,
        commandPreviewJson: String? = null,
        allowSessionApproval: Boolean = true,
        originAgentId: String? = null,
        originLabel: String? = null,
        path: String? = null,
    ) {
        cefPanel?.showApproval(toolName, riskLevel, description, metadataJson, diffContent, commandPreviewJson, allowSessionApproval, originAgentId, originLabel, path)
        broadcast(replay = false) { it.showApproval(toolName, riskLevel, description, metadataJson, diffContent, commandPreviewJson, allowSessionApproval, originAgentId, originLabel, path) }
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

    fun setCefArtifactResultCallback(onResult: (String) -> Unit) {
        cefPanel?.onArtifactResult = onResult
    }

    fun setCefInteractiveRenderCallback(onResult: (String) -> Unit) {
        cefPanel?.onInteractiveRenderResult = onResult
    }

    fun setCefEditorTabCallback(onOpen: (String) -> Unit) {
        cefPanel?.onOpenInEditorTab = onOpen
    }

    fun setCefFocusPlanEditorCallback(onFocus: () -> Unit) {
        cefPanel?.onFocusPlanEditor = onFocus
    }

    fun setCefOpenApprovedPlanCallback(onOpen: () -> Unit) {
        cefPanel?.onOpenApprovedPlan = onOpen
    }

    fun setCefRevisePlanFromEditorCallback(onRevise: () -> Unit) {
        cefPanel?.onRevisePlanFromEditor = onRevise
    }

    // ═══════════════════════════════════════════════════
    //  Delegate API — routes to JCEF or fallback
    // ═══════════════════════════════════════════════════

    fun startSession(task: String, attachmentsJson: String? = null) {
        cefPanel?.startSession(task, attachmentsJson) ?: fallbackPanel?.startSession(task)
        broadcast { it.startSession(task) }
    }

    /**
     * Cross-IDE delegation (IDE-B leg-a): start a session whose opening user bubble AND
     * all subsequent assistant/tool bubbles are flagged delegated (distinct tint + left
     * accent stripe + "delegated · {repo}" pill). [delegatorRepo] is the delegating IDE's
     * REPO NAME — never the raw "ide-$pid" identifier.
     */
    fun startSessionDelegated(task: String, delegatorRepo: String) {
        cefPanel?.startSessionDelegated(task, delegatorRepo) ?: fallbackPanel?.startSession(task)
        broadcast { it.startSession(task) }
    }

    fun startSessionWithMentions(task: String, mentionsJson: String, attachmentsJson: String? = null) {
        cefPanel?.startSessionWithMentions(task, mentionsJson, attachmentsJson)
            ?: fallbackPanel?.startSession(task)
        broadcast { it.startSession(task) }
    }

    fun appendUserMessage(text: String, attachmentsJson: String? = null) {
        cefPanel?.appendUserMessage(text, attachmentsJson) ?: fallbackPanel?.appendUserMessage(text)
        broadcast { it.appendUserMessage(text) }
    }

    fun appendUserMessageWithMentions(text: String, mentionsJson: String, attachmentsJson: String? = null) {
        cefPanel?.appendUserMessageWithMentions(text, mentionsJson, attachmentsJson)
            ?: fallbackPanel?.appendUserMessage(text)
        broadcast { it.appendUserMessage(text) }
    }

    fun appendPlanApprovedMessage(planMarkdown: String) {
        cefPanel?.appendPlanApprovedMessage(planMarkdown) ?: fallbackPanel?.appendUserMessage("Implementation plan approved")
        broadcast { it.appendUserMessage("Implementation plan approved") }
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

    // Per-panel buffer used to aggregate streaming thinking deltas when the
    // fallback (non-JCEF) panel is active — the fallback renders the whole
    // block in one shot, while JCEF streams it live via appendToThinking.
    private val thinkingFallbackBuffer = StringBuilder()

    fun appendToThinking(text: String) {
        cefPanel?.appendToThinking(text)
        if (cefPanel == null) thinkingFallbackBuffer.append(text)
        broadcast { it.appendToThinking(text) }
    }

    fun endThinking(durationMs: Long = 0L) {
        cefPanel?.endThinking(durationMs)
        if (cefPanel == null && thinkingFallbackBuffer.isNotEmpty()) {
            fallbackPanel?.appendThinking(thinkingFallbackBuffer.toString())
            thinkingFallbackBuffer.setLength(0)
        }
        broadcast { it.endThinking(durationMs) }
    }

    fun appendCompletionCard(data: CompletionData) {
        cefPanel?.appendCompletionCard(data)
            ?: fallbackPanel?.appendStatus("Task completed: ${data.result}", RichStreamingPanel.StatusType.SUCCESS)
        broadcast { it.appendCompletionCard(data) }
    }

    fun appendToolCall(
        toolCallId: String = "",
        toolName: String, args: String = "",
        status: RichStreamingPanel.ToolCallStatus = RichStreamingPanel.ToolCallStatus.RUNNING,
        toolTimeoutSeconds: Long? = null
    ) {
        val displayName = resolveToolDisplayName(toolName)
        cefPanel?.appendToolCall(toolCallId, displayName, args, status, toolTimeoutSeconds)
            ?: fallbackPanel?.appendToolCall(displayName, args, status)
        broadcast { it.appendToolCall(toolCallId, displayName, args, status, toolTimeoutSeconds) }
    }

    fun updateLastToolCall(
        status: RichStreamingPanel.ToolCallStatus, result: String = "", durationMs: Long = 0,
        toolName: String = "", output: String? = null, diff: String? = null,
        toolCallId: String = "",
        // Multimodal-agent Phase 6 — pass tool-produced image metadata through
        // to the CEF panel so the webview can render the badge. Empty default
        // keeps every existing call site (and the fallback Swing panel, which
        // doesn't surface images) source-compatible.
        imageRefs: List<com.workflow.orchestrator.core.services.ToolResult.ImageRefData> = emptyList()
    ) {
        val displayName = resolveToolDisplayName(toolName)
        cefPanel?.updateLastToolCall(status, result, durationMs, displayName, output, diff, toolCallId, imageRefs)
            ?: fallbackPanel?.updateLastToolCall(status, result, durationMs)
        broadcast { it.updateLastToolCall(status, result, durationMs, displayName, output, diff, toolCallId, imageRefs) }
    }

    /**
     * Resolves a tool name to a user-friendly display name.
     * Maps `use_subagent_code_reviewer` → `agent (code-reviewer)`.
     * Non-subagent tool names pass through unchanged.
     */
    private fun resolveToolDisplayName(toolName: String): String {
        if (!SubagentToolName.isSubagentToolName(toolName)) return toolName
        val agentName = try {
            AgentConfigLoader.getInstance().resolveSubagentNameForTool(toolName)
        } catch (_: Exception) { null }
        return if (agentName != null) "agent ($agentName)" else toolName
    }

    fun appendToolOutput(toolCallId: String, chunk: String) {
        if (cefPanel == null) LOG.warn(
            "appendToolOutput[$toolCallId]: cefPanel is null — streaming output dropped. " +
            "JCEF may not be initialised (check AgentCefPanel.isAvailable())."
        )
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

    // ── Sub-Agent boundary card delegation ──

    fun spawnSubAgent(agentId: String, label: String, model: String? = null) {
        runOnEdt { cefPanel?.spawnSubAgent(agentId, label, model) }
        broadcast(replay = false) { it.spawnSubAgent(agentId, label, model) }
    }

    fun updateSubAgentIteration(agentId: String, iteration: Int, tokensUsed: Int = 0) {
        runOnEdt { cefPanel?.updateSubAgentIteration(agentId, iteration, tokensUsed) }
        broadcast(replay = false) { it.updateSubAgentIteration(agentId, iteration, tokensUsed) }
    }

    fun setSubAgentStatusNote(agentId: String, note: String?) {
        runOnEdt { cefPanel?.setSubAgentStatusNote(agentId, note) }
        broadcast(replay = false) { it.setSubAgentStatusNote(agentId, note) }
    }

    fun refreshContextUsage() {
        runOnEdt { cefPanel?.refreshContextUsage() }
        broadcast(replay = false) { it.refreshContextUsage() }
    }

    fun addSubAgentToolCall(agentId: String, toolCallId: String, toolName: String, toolArgs: String) {
        runOnEdt { cefPanel?.addSubAgentToolCall(agentId, toolCallId, toolName, toolArgs) }
        broadcast(replay = false) { it.addSubAgentToolCall(agentId, toolCallId, toolName, toolArgs) }
    }

    fun updateSubAgentToolCall(
        agentId: String,
        toolCallId: String,
        toolName: String,
        result: String,
        output: String?,
        diff: String?,
        durationMs: Long,
        isError: Boolean,
        // Multimodal-agent Phase 6 — tool-produced image metadata; passed
        // through to the CEF panel so the sub-agent tool-result row renders
        // the badge identically to the main agent path.
        imageRefs: List<com.workflow.orchestrator.core.services.ToolResult.ImageRefData> = emptyList()
    ) {
        runOnEdt { cefPanel?.updateSubAgentToolCall(agentId, toolCallId, toolName, result, output, diff, durationMs, isError, imageRefs) }
        broadcast(replay = false) {
            it.updateSubAgentToolCall(agentId, toolCallId, toolName, result, output, diff, durationMs, isError, imageRefs)
        }
    }

    fun updateSubAgentMessage(agentId: String, textContent: String) {
        runOnEdt { cefPanel?.updateSubAgentMessage(agentId, textContent) }
        broadcast(replay = false) { it.updateSubAgentMessage(agentId, textContent) }
    }

    fun appendSubAgentStreamDelta(agentId: String, delta: String) {
        runOnEdt { cefPanel?.appendSubAgentStreamDelta(agentId, delta) }
        broadcast(replay = false) { it.appendSubAgentStreamDelta(agentId, delta) }
    }

    fun appendSubAgentThinking(agentId: String, delta: String) {
        runOnEdt { cefPanel?.appendSubAgentThinking(agentId, delta) }
        broadcast(replay = false) { it.appendSubAgentThinking(agentId, delta) }
    }

    fun endSubAgentThinking(agentId: String) {
        runOnEdt { cefPanel?.endSubAgentThinking(agentId) }
        broadcast(replay = false) { it.endSubAgentThinking(agentId) }
    }

    fun completeSubAgent(agentId: String, textContent: String, tokensUsed: Int, isError: Boolean) {
        runOnEdt { cefPanel?.completeSubAgent(agentId, textContent, tokensUsed, isError) }
        broadcast(replay = false) { it.completeSubAgent(agentId, textContent, tokensUsed, isError) }
    }

    fun renderArtifact(title: String, source: String, renderId: String) {
        runOnEdt { cefPanel?.renderArtifact(title, source, renderId) }
        broadcast(replay = false) { it.renderArtifact(title, source, renderId) }
    }

    fun setCefKillSubAgentCallback(onKill: (String) -> Unit) {
        cefPanel?.onKillSubAgent = onKill
    }

    fun setCefStartIncomingDelegationCallback(onStart: (String) -> Unit) {
        cefPanel?.onStartIncomingDelegation = onStart
    }

    // ── Edit stats delegation ──

    fun updateEditStats(added: Int, removed: Int, files: Int) {
        runOnEdt { cefPanel?.updateEditStats(added, removed, files) }
        broadcast(replay = false) { it.updateEditStats(added, removed, files) }
    }

    fun updateAggregateDiff(json: String) {
        runOnEdt { cefPanel?.updateAggregateDiff(json) }
        broadcast(replay = false) { it.updateAggregateDiff(json) }
    }

    fun setSmartWorkingPhrase(phrase: String) {
        runOnEdt { cefPanel?.setSmartWorkingPhrase(phrase) }
        broadcast(replay = false) { it.setSmartWorkingPhrase(phrase) }
    }

    fun setSessionTitle(title: String) {
        runOnEdt { cefPanel?.setSessionTitle(title) }
        broadcast(replay = false) { it.setSessionTitle(title) }
    }

    /** Same as [setSessionTitle] but asks the frontend to play the scramble-transition
     *  animation when the new title replaces the old one. Used after Haiku's
     *  on-completion title re-evaluation. */
    fun setSessionTitleAnimated(title: String) {
        runOnEdt { cefPanel?.setSessionTitleAnimated(title) }
        broadcast(replay = false) { it.setSessionTitleAnimated(title) }
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

    /**
     * Push full session UI state to the webview for rehydration on resume.
     * Does not replay to mirrors — session state is loaded once at resume time.
     */
    fun loadSessionState(uiMessagesJson: String) {
        runOnEdt { cefPanel?.loadSessionState(uiMessagesJson) }
        broadcast(replay = false) { it.loadSessionState(uiMessagesJson) }
    }

    /**
     * Push the full TaskStore snapshot to the webview (Phase 5 task system).
     * Used on session load so the React PlanProgressWidget shows persisted tasks.
     */
    fun setTasks(tasksJson: String) {
        runOnEdt { cefPanel?.setTasks(tasksJson) }
        broadcast(replay = false) { it.setTasks(tasksJson) }
    }

    /** Push a single newly-created task to the webview (appends in chatStore). */
    fun applyTaskCreate(taskJson: String) {
        runOnEdt { cefPanel?.applyTaskCreate(taskJson) }
        broadcast(replay = false) { it.applyTaskCreate(taskJson) }
    }

    /** Push a single updated task to the webview (replaces by id in chatStore). */
    fun applyTaskUpdate(taskJson: String) {
        runOnEdt { cefPanel?.applyTaskUpdate(taskJson) }
        broadcast(replay = false) { it.applyTaskUpdate(taskJson) }
    }

    /**
     * Push a full background-process snapshot to the webview top-bar indicator.
     * Calls the `window.__receiveBackgroundUpdate` global registered in jcef-bridge.ts.
     */
    fun receiveBackgroundUpdate(snapshotJson: String) {
        runOnEdt { cefPanel?.receiveBackgroundUpdate(snapshotJson) }
        broadcast(replay = false) { it.receiveBackgroundUpdate(snapshotJson) }
    }

    /**
     * Push a full monitor snapshot to the webview top-bar indicator.
     * Calls the `window.__receiveMonitorUpdate` global registered in jcef-bridge.ts.
     */
    fun receiveMonitorUpdate(snapshotJson: String) {
        runOnEdt { cefPanel?.receiveMonitorUpdate(snapshotJson) }
        broadcast(replay = false) { it.receiveMonitorUpdate(snapshotJson) }
    }

    /**
     * Wire the `_loadMonitorSnapshot` JCEF bridge.
     * [handler] receives a sessionId string and must return a JSON array
     * of [com.workflow.orchestrator.core.events.MonitorSnapshotDto].
     */
    fun setCefLoadMonitorSnapshotCallback(handler: (sessionId: String) -> String) {
        cefPanel?.onLoadMonitorSnapshot = handler
    }

    fun showResumeBar(sessionId: String) {
        runOnEdt { cefPanel?.showResumeBar(sessionId) }
        broadcast(replay = false) { it.showResumeBar(sessionId) }
    }

    fun hideResumeBar() {
        runOnEdt { cefPanel?.hideResumeBar() }
        broadcast(replay = false) { it.hideResumeBar() }
    }

    fun loadSessionHistory(historyItemsJson: String) {
        runOnEdt { cefPanel?.loadSessionHistory(historyItemsJson) }
        broadcast(replay = false) { it.loadSessionHistory(historyItemsJson) }
    }

    fun showHistoryView() {
        runOnEdt { cefPanel?.showHistoryView() }
        broadcast(replay = false) { it.showHistoryView() }
    }

    fun showChatView() {
        runOnEdt { cefPanel?.showChatView() }
        broadcast(replay = false) { it.showChatView() }
    }

    fun setCefHistoryCallbacks(
        onShowSession: (String) -> Unit,
        onDeleteSession: (String) -> Unit,
        onToggleFavorite: (String) -> Unit,
        onStartNewSession: () -> Unit,
        onBulkDeleteSessions: (String) -> Unit = {},
        onExportSession: (String) -> Unit = {},
        onExportAllSessions: () -> Unit = {},
        onRequestHistory: () -> Unit = {},
        onResumeViewedSession: () -> Unit = {},
    ) {
        cefPanel?.onShowSession = onShowSession
        cefPanel?.onDeleteSession = onDeleteSession
        cefPanel?.onToggleFavorite = onToggleFavorite
        cefPanel?.onStartNewSession = onStartNewSession
        cefPanel?.onBulkDeleteSessions = onBulkDeleteSessions
        cefPanel?.onExportSession = onExportSession
        cefPanel?.onExportAllSessions = onExportAllSessions
        cefPanel?.onRequestHistory = onRequestHistory
        cefPanel?.onResumeViewedSession = onResumeViewedSession
    }

    fun setCefPageReadyCallback(onReady: () -> Unit) {
        cefPanel?.onPageReady = onReady
    }

    /**
     * Phase 5: wires the current-session-directory resolver used by the
     * image-attachment upload path. Each upload constructs a fresh
     * [com.workflow.orchestrator.agent.session.AttachmentStore] from this
     * provider so per-session isolation is preserved (Phase 4 contract).
     */
    fun setCurrentSessionDirProvider(provider: () -> java.nio.file.Path?) {
        cefPanel?.currentSessionDirProvider = provider
    }

    /**
     * Multimodal-agent Phase 7 — wire the chat input usage indicator's data
     * source. Provider returns `(used, max)` from the active session's
     * [com.workflow.orchestrator.agent.loop.ContextManager], or null when no
     * session is live.
     */
    fun setContextUsageProvider(provider: () -> Pair<Int, Int>?) {
        cefPanel?.contextUsageProvider = provider
    }

    /**
     * Multimodal-agent Phase 7 followup F-P5-2 / F-P6-1 — wire the image
     * settings JSON provider. Returned JSON: `{maxBytes, maxPerTurn,
     * mimeWhitelist, enabled}`.
     */
    fun setImageSettingsProvider(provider: () -> String) {
        cefPanel?.imageSettingsProvider = provider
    }

    /**
     * Multimodal-agent Phase 7 followup F-P5-2 / F-P6-1 — push current image
     * settings to JS now (used by Settings.apply() so user changes take
     * effect without an IDE restart).
     */
    fun pushImageSettings() {
        cefPanel?.pushImageSettings()
    }

    // ── File-attachment wiring (Task 8 / Plan 2026-05-27) ──────────────────
    // Mirror the setImageSettingsProvider / pushImageSettings forwarder pattern.

    fun setOnPickAttachment(fn: () -> Unit) { cefPanel?.onPickAttachment = fn }
    fun setOnDropTargetReady(fn: (List<java.io.File>) -> Unit) { cefPanel?.onDropTargetReady = fn }
    fun pushAttachmentChip(json: String) { cefPanel?.pushAttachmentChip(json) }

    fun setCefCancelSteeringCallback(onCancel: (String) -> Unit) {
        cefPanel?.onCancelSteering = onCancel
    }

    fun setCefRevertToUserMessageCallback(onRevert: (Long) -> Unit) {
        cefPanel?.onRevertToUserMessage = onRevert
    }

    fun setCefRevertFileToBaselineCallback(onRevert: (String) -> Unit) {
        cefPanel?.onRevertFileToBaseline = onRevert
    }

    fun setCefRevertAllCallback(onRevert: () -> Unit) {
        cefPanel?.onRevertAll = onRevert
    }

    /**
     * Wire the `_loadBackgroundSnapshot` JCEF bridge.
     * [handler] receives a sessionId string and must return a JSON array
     * of [com.workflow.orchestrator.core.events.BackgroundProcessSnapshotDto].
     */
    fun setCefLoadBackgroundSnapshotCallback(handler: (sessionId: String) -> String) {
        cefPanel?.onLoadBackgroundSnapshot = handler
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else invokeLater { action() }
    }

    /**
     * Tears down everything this panel owns (P0-6/B4).
     *
     * When disposed through Disposer (the tool-window path: Content.dispose() ->
     * setDisposer(this)), the registered children -- [AgentCefPanel] (Chromium),
     * its CoroutineScope cancel, the scheme-handler detach, plus anything callers
     * chained via `Disposer.register(panel, ...)` (AgentController, EventBus scope)
     * -- are disposed by the tree walk BEFORE this method runs. The explicit
     * [AgentCefPanel] dispose below covers direct `dispose()` calls that bypass
     * Disposer (defense-in-depth; guarded so the tree path doesn't double-dispose).
     */
    override fun dispose() {
        if (ownsCefLifecycle) {
            // AgentCefPanel.dispose() is idempotent (scope.cancel, idempotent
            // JsBridgeDispatcher.dispose, query list cleared, browser nulled), so a
            // second call after the Disposer tree walk already disposed it is harmless.
            cefPanel?.let { Disposer.dispose(it) }
        }
        mirrors.clear()
        replayLog.clear()
        thinkingFallbackBuffer.setLength(0)
        onSendMessage = null
        LOG.info("AgentDashboardPanel disposed (ownsCefLifecycle=$ownsCefLifecycle)")
    }
}
