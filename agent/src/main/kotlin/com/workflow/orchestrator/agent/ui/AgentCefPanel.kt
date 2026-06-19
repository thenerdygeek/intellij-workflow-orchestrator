package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefClient
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import com.workflow.orchestrator.agent.tools.CompletionData
import com.workflow.orchestrator.agent.util.JsEscape
import com.workflow.orchestrator.core.model.ModelIdNormalizer
import kotlinx.serialization.encodeToString
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * JCEF-based (embedded Chromium) chat panel for the AI agent.
 *
 * Provides the S-tier visual experience: CSS3 animations, border-radius,
 * smooth streaming, color-coded tool badges, and proper diff rendering.
 *
 * Communication:
 * - Kotlin → JS: `executeJavaScript()` calls functions defined in the React webview (jcef-bridge.ts)
 * - JS → Kotlin: `JBCefJSQuery` bridges for user actions (send message, button clicks)
 *
 * Falls back to [RichStreamingPanel] (JEditorPane) if JCEF is not available.
 */
class AgentCefPanel(
    private val parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    companion object {
        private val LOG = Logger.getInstance(AgentCefPanel::class.java)
        private val JSON = Json { encodeDefaults = true }

        /** Check if JCEF is available in this IDE installation. */
        fun isAvailable(): Boolean = try { JBCefApp.isSupported() } catch (_: Exception) { false }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var browser: JBCefBrowser? = null

    /**
     * Tracks every [JBCefJSQuery] created via [registerQuery] so [dispose] can
     * tear them all down in one pass. This avoids the previous bug where new
     * queries would be added to [createBrowser] but forgotten in [dispose],
     * leaking native resources.
     */
    private val registeredQueries = mutableListOf<JBCefJSQuery>()

    private var undoQuery: JBCefJSQuery? = null
    private var traceQuery: JBCefJSQuery? = null
    private var promptQuery: JBCefJSQuery? = null
    private var planApproveQuery: JBCefJSQuery? = null
    private var planReviseQuery: JBCefJSQuery? = null
    private var planDismissQuery: JBCefJSQuery? = null
    private var toolToggleQuery: JBCefJSQuery? = null
    private var questionAnsweredQuery: JBCefJSQuery? = null
    private var questionSkippedQuery: JBCefJSQuery? = null
    private var chatAboutQuery: JBCefJSQuery? = null
    private var questionsSubmittedQuery: JBCefJSQuery? = null
    private var questionsCancelledQuery: JBCefJSQuery? = null
    private var editQuestionQuery: JBCefJSQuery? = null
    private var deactivateSkillQuery: JBCefJSQuery? = null
    private var navigateToFileQuery: JBCefJSQuery? = null
    private var validatePathsQuery: JBCefJSQuery? = null
    private var resolveSymbolsQuery: JBCefJSQuery? = null
    private var cancelTaskQuery: JBCefJSQuery? = null
    private var newChatQuery: JBCefJSQuery? = null
    private var sendMessageQuery: JBCefJSQuery? = null
    private var changeModelQuery: JBCefJSQuery? = null
    private var requestModelListQuery: JBCefJSQuery? = null
    private var togglePlanModeQuery: JBCefJSQuery? = null
    private var compactContextQuery: JBCefJSQuery? = null
    private var activateSkillQuery: JBCefJSQuery? = null
    private var requestFocusIdeQuery: JBCefJSQuery? = null
    private var openSettingsQuery: JBCefJSQuery? = null
    private var openMemorySettingsQuery: JBCefJSQuery? = null
    private var openToolsPanelQuery: JBCefJSQuery? = null
    private var searchMentionsQuery: JBCefJSQuery? = null
    private var searchTicketsQuery: JBCefJSQuery? = null
    private var validateTicketQuery: JBCefJSQuery? = null
    private var sendMessageWithMentionsQuery: JBCefJSQuery? = null
    private var openInEditorTabQuery: JBCefJSQuery? = null
    private var focusPlanEditorQuery: JBCefJSQuery? = null
    private var openApprovedPlanQuery: JBCefJSQuery? = null
    private var revisePlanFromEditorQuery: JBCefJSQuery? = null
    private var viewInEditorQuery: JBCefJSQuery? = null
    private var approveToolCallQuery: JBCefJSQuery? = null
    private var denyToolCallQuery: JBCefJSQuery? = null
    private var allowToolForSessionQuery: JBCefJSQuery? = null
    private var interactiveHtmlMessageQuery: JBCefJSQuery? = null
    private var acceptDiffHunkQuery: JBCefJSQuery? = null
    private var rejectDiffHunkQuery: JBCefJSQuery? = null
    private var killToolCallQuery: JBCefJSQuery? = null
    private var killSubAgentQuery: JBCefJSQuery? = null
    private var startIncomingDelegationQuery: JBCefJSQuery? = null
    private var revertToUserMessageQuery: JBCefJSQuery? = null
    private var revertFileToBaselineQuery: JBCefJSQuery? = null
    private var revertAllQuery: JBCefJSQuery? = null
    private var cancelSteeringQuery: JBCefJSQuery? = null
    private var retryLastTaskQuery: JBCefJSQuery? = null
    private var processInputQuery: JBCefJSQuery? = null
    private var artifactResultQuery: JBCefJSQuery? = null
    private var interactiveRenderResultQuery: JBCefJSQuery? = null
    private var showSessionQuery: JBCefJSQuery? = null
    private var deleteSessionQuery: JBCefJSQuery? = null
    private var toggleFavoriteQuery: JBCefJSQuery? = null
    private var startNewSessionQuery: JBCefJSQuery? = null
    private var bulkDeleteSessionsQuery: JBCefJSQuery? = null
    private var exportSessionQuery: JBCefJSQuery? = null
    private var exportAllSessionsQuery: JBCefJSQuery? = null
    private var requestHistoryQuery: JBCefJSQuery? = null
    private var resumeViewedSessionQuery: JBCefJSQuery? = null
    private var copyToClipboardQuery: JBCefJSQuery? = null
    private var openInsightsTabQuery: JBCefJSQuery? = null
    private var loadBackgroundSnapshotQuery: JBCefJSQuery? = null
    private var loadMonitorSnapshotQuery: JBCefJSQuery? = null
    private var attachmentExistsQuery: JBCefJSQuery? = null
    private var contextUsageQuery: JBCefJSQuery? = null
    private var imageSettingsQuery: JBCefJSQuery? = null
    private var pickAttachmentQuery: JBCefJSQuery? = null
    private var resolveLinkQuery: JBCefJSQuery? = null
    private var openLinkQuery: JBCefJSQuery? = null
    private var handoffForkQuery: JBCefJSQuery? = null
    private var handoffKeepQuery: JBCefJSQuery? = null
    var mentionSearchProvider: MentionSearchProvider? = null
    var onSendMessageWithMentions: ((String, String, String?) -> Unit)? = null  // (text, mentionsJson, attachmentsJson?)

    /**
     * Resolves the directory for the *currently active* session — used by the
     * Phase 5 image-attachment upload path to construct an [AttachmentStore]
     * fresh per request (never cached, per Phase 4's per-session-isolation
     * contract). Set by [AgentController] when a session starts/resumes,
     * cleared on new chat. May return null when no session is active (no
     * upload yet possible).
     */
    var currentSessionDirProvider: (() -> java.nio.file.Path?)? = null

    /**
     * Multimodal-agent Phase 7 — chat input usage indicator. Returns the live
     * `(used, max)` token pair for the active session's [ContextManager],
     * keyed against the live model. Wired by [AgentController]; null when no
     * session is active (the indicator renders `0 / 132K` until first turn).
     */
    var contextUsageProvider: (() -> Pair<Int, Int>?)? = null

    /**
     * Multimodal-agent Phase 7 followup F-P5-2 / F-P6-1 — pushes the current
     * [PluginSettings] image-input fields to the JS layer at page-load and
     * after every Settings.apply(). Returns a JSON string with shape
     * `{maxBytes, maxPerTurn, mimeWhitelist, enabled}` matching the JS-side
     * `IMAGE_DEFAULT_SETTINGS` literal. Wired by [AgentController].
     */
    var imageSettingsProvider: (() -> String)? = null

    /** Invoked when JS calls window._pickAttachment(); reads files off-EDT and ingests them. */
    var onPickAttachment: (() -> Unit)? = null

    /** Guards against double-installing the AWT DropTarget (createBrowser vs setter race). */
    private var dropTargetInstalled = false

    /**
     * Invoked with dropped OS files; reads bytes off-EDT and ingests them.
     *
     * The setter calls [maybeInstallDropTarget] immediately so the DropTarget is
     * installed even when AgentController assigns this AFTER [createBrowser] has
     * already returned (the common production path: init{} → createBrowser() eagerly,
     * then AgentController wires callbacks).  [createBrowser] also calls
     * [maybeInstallDropTarget] after `browser = b` to cover the rare inverse ordering.
     * [dropTargetInstalled] prevents double-installation regardless of which path wins.
     */
    var onDropTargetReady: ((List<java.io.File>) -> Unit)? = null
        set(value) { field = value; maybeInstallDropTarget() }

    private fun maybeInstallDropTarget() {
        if (dropTargetInstalled) return
        val comp = browser?.component ?: return
        val ready = onDropTargetReady ?: return
        AttachmentDropTarget(
            onDropActive = { active -> callJs("if (window._setDropActive) window._setDropActive($active);") },
            onFilesDropped = { files -> ready(files) },
        ).installOn(comp)
        dropTargetInstalled = true
    }

    /**
     * Bridge dispatcher: manages the pageLoaded/pendingCalls state machine.
     * Initialized lazily in [createBrowser] once the browser instance exists.
     * All callJs() calls delegate to this dispatcher.
     */
    private var bridgeDispatcher: JsBridgeDispatcher? = null

    /** Callback when user clicks "Undo" in the JCEF footer. */
    var onUndoRequested: (() -> Unit)? = null

    /** Callback when user clicks "View Trace" in the JCEF footer. */
    var onViewTraceRequested: (() -> Unit)? = null

    /** Callback when user clicks an example prompt in the JCEF welcome screen. */
    var onPromptSubmitted: ((String) -> Unit)? = null

    /** Callback when user clicks "Approve & Execute" on a plan card. */
    var onPlanApproved: (() -> Unit)? = null

    /** Callback when user clicks "Revise with Comments" on a plan card. JSON string of {stepId: comment}. */
    var onPlanRevised: ((String) -> Unit)? = null

    /** Callback when user clicks "Dismiss" on a plan card. */
    var onPlanDismissed: (() -> Unit)? = null

    /** Callback when user toggles a tool checkbox in the Tools panel. */
    var onToolToggled: ((String, Boolean) -> Unit)? = null

    // Question wizard callbacks
    /** Callback when user answers a question. Params: questionId, selectedOptionsJson. */
    var onQuestionAnswered: ((String, String) -> Unit)? = null
    /** Callback when user skips a question. Param: questionId. */
    var onQuestionSkipped: ((String) -> Unit)? = null
    /** Callback when user clicks "Chat about this" on an option. Params: questionId, optionLabel, message. */
    var onChatAboutOption: ((String, String, String) -> Unit)? = null
    /** Callback when user submits all question answers. */
    var onQuestionsSubmitted: (() -> Unit)? = null
    /** Callback when user cancels the question wizard. */
    var onQuestionsCancelled: (() -> Unit)? = null
    /** Callback when user edits a previously answered question. Param: questionId. */
    var onEditQuestion: ((String) -> Unit)? = null

    /** Callback when user dismisses the skill banner. */
    var onSkillDismissed: (() -> Unit)? = null

    /** Callback when user clicks a file path link in chat output. */
    var onNavigateToFile: ((String, Int) -> Unit)? = null

    /** Callback when JS requests batch path validation. Params: (pathsJson, callbackName). */
    var onValidatePaths: ((String, String) -> Unit)? = null

    var onResolveSymbols: ((String, String) -> Unit)? = null

    var onCancelTask: (() -> Unit)? = null
    var onRetryLastTask: (() -> Unit)? = null
    var onNewChat: (() -> Unit)? = null
    var onSendMessage: ((String) -> Unit)? = null
    var onChangeModel: ((String) -> Unit)? = null
    /** Callback when the React InputBar mounts and pulls the model list (covers cases where the initial Kotlin push was lost or returned empty). */
    var onRequestModelList: (() -> Unit)? = null
    var onTogglePlanMode: ((Boolean) -> Unit)? = null
    /** Callback when user clicks the Compact button in the TopBar. Param is `force` — true bypasses the 70% utilization floor. */
    var onCompactContext: ((Boolean) -> Unit)? = null
    var onActivateSkill: ((String) -> Unit)? = null
    var onRequestFocusIde: (() -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null
    /** Callback when user clicks the memory stats indicator in the TopBar — routes to Memory sub-page. */
    var onOpenMemorySettings: (() -> Unit)? = null
    var onOpenToolsPanel: (() -> Unit)? = null
    /** Callback when user clicks "Open in Tab" on a visualization. Params: type, content JSON payload. */
    var onOpenInEditorTab: ((String) -> Unit)? = null

    /** Callback when user clicks "View Implementation Plan" on the plan card. Focuses the existing plan editor tab. */
    var onFocusPlanEditor: (() -> Unit)? = null

    /** Callback when user clicks "Open Plan" on the approved-plan card (plan already approved, view it in editor). */
    var onOpenApprovedPlan: (() -> Unit)? = null

    /** Callback when user clicks "Revise" on the chat plan card. Triggers revise on the plan editor tab. */
    var onRevisePlanFromEditor: (() -> Unit)? = null

    /** Callback when user clicks "Start fresh session" on the new_task handoff preview card. */
    var onHandoffStartFresh: (() -> Unit)? = null
    /** Callback when user clicks "Keep chatting here" on the new_task handoff preview card. */
    var onHandoffKeepChatting: (() -> Unit)? = null

    /** Callback when user clicks "View in Editor" in the top toolbar to open the chat in a full editor tab. */
    var onViewInEditor: (() -> Unit)? = null

    /** Callback when user approves a tool call in the approval card. */
    var onApproveToolCall: (() -> Unit)? = null
    /** Callback when user denies a tool call in the approval card. */
    var onDenyToolCall: (() -> Unit)? = null
    /** Callback when user clicks "Allow for session" on a tool in the approval card. Param: toolName. */
    var onAllowToolForSession: ((String) -> Unit)? = null
    /** Callback when user interacts with an interactive HTML message. Param: JSON payload. */
    var onInteractiveHtmlMessage: ((String) -> Unit)? = null
    /** Callback when user accepts a diff hunk. Params: filePath, hunkIndex, editedContent (nullable). */
    var onAcceptDiffHunk: ((String, Int, String?) -> Unit)? = null
    /** Callback when user rejects a diff hunk. Params: filePath, hunkIndex. */
    var onRejectDiffHunk: ((String, Int) -> Unit)? = null
    /** Callback when user clicks "Kill" on a running tool call. Param: toolCallId. */
    var onKillToolCall: ((String) -> Unit)? = null
    /** Callback when user clicks the kill button on a running sub-agent boundary card. Param: agentId. */
    var onKillSubAgent: ((String) -> Unit)? = null
    /** Callback when user clicks Start on a busy-case incoming cross-IDE delegation top-bar prompt. Param: delegation key. */
    var onStartIncomingDelegation: ((String) -> Unit)? = null
    /** Callback when user submits process input from the ProcessInputView. Param: input string. */
    var onProcessInputResolved: ((String) -> Unit)? = null
    /** Callback when user clicks the time-travel button on a user message. Param: messageTs. */
    var onRevertToUserMessage: ((Long) -> Unit)? = null
    /** Callback when user clicks the per-file revert button. Param: absolutePath. */
    var onRevertFileToBaseline: ((String) -> Unit)? = null
    /** Callback when user clicks "Revert all". No params. */
    var onRevertAll: (() -> Unit)? = null
    /** Callback when user clicks "Cancel" on a queued steering message. Param: steeringId. */
    var onCancelSteering: ((String) -> Unit)? = null
    /**
     * Callback fired when the sandbox iframe reports an artifact render outcome back
     * to Kotlin via the `_reportArtifactResult` JS→Kotlin bridge. Param is the raw
     * JSON string from the bridge, containing at minimum `{ renderId, status }` plus
     * optional `phase`, `message`, `missingSymbols`, `line`, `heightPx`. Wired by
     * [AgentController] to [ArtifactResultRegistry.reportResult] so the
     * `render_artifact` tool's suspended coroutine resumes with a structured result.
     */
    var onArtifactResult: ((String) -> Unit)? = null
    /**
     * Callback fired when a JCEF interactive render (questions, plans, approvals)
     * reports its outcome back to Kotlin via the `_reportInteractiveRender` bridge.
     * Param is a JSON string: `{ "type": "question"|"plan"|"approval", "status": "ok"|"error", "message"?: "..." }`.
     */
    var onInteractiveRenderResult: ((String) -> Unit)? = null

    /** Callback when user clicks a session in the history list. Param: sessionId. */
    var onShowSession: ((String) -> Unit)? = null
    /** Callback when user clicks delete on a session in the history list. Param: sessionId. */
    var onDeleteSession: ((String) -> Unit)? = null
    /** Callback when user toggles favorite on a session in the history list. Param: sessionId. */
    var onToggleFavorite: ((String) -> Unit)? = null
    /** Callback when user clicks "New Session" from the history view. */
    var onStartNewSession: (() -> Unit)? = null
    /** Callback when user bulk-deletes sessions. Param: JSON array of session IDs. */
    var onBulkDeleteSessions: ((String) -> Unit)? = null
    /** Callback when user exports a single session. Param: sessionId. */
    var onExportSession: ((String) -> Unit)? = null
    /** Callback when user exports all sessions. */
    var onExportAllSessions: (() -> Unit)? = null
    /** Callback when user clicks the history button in TopBar. */
    var onRequestHistory: (() -> Unit)? = null
    /** Callback when user clicks "Resume" in the resume bar. */
    var onResumeViewedSession: (() -> Unit)? = null

    /** Callback when user clicks the cost chip to open the Insights tab. */
    var onOpenInsightsTab: (() -> Unit)? = null

    /**
     * Callback for the `_loadBackgroundSnapshot` bridge.
     * Called with the sessionId from JS; should return the serialized JSON array of
     * [com.workflow.orchestrator.core.events.BackgroundProcessSnapshotDto] for that session.
     */
    var onLoadBackgroundSnapshot: ((sessionId: String) -> String)? = null

    /**
     * Callback for the `_loadMonitorSnapshot` bridge.
     * Called with the sessionId from JS; should return the serialized JSON array of
     * [com.workflow.orchestrator.core.events.MonitorSnapshotDto] for that session.
     */
    var onLoadMonitorSnapshot: ((sessionId: String) -> String)? = null

    /**
     * Fired after the page is fully loaded and all bridges are injected.
     * Used by AgentController to re-push initial state (model, skills, memory)
     * so it arrives even if the page took longer than expected to load.
     */
    var onPageReady: (() -> Unit)? = null

    init {
        Disposer.register(parentDisposable) { scope.cancel() }
        try {
            createBrowser()
            Disposer.register(parentDisposable, this)
        } catch (e: Exception) {
            LOG.warn("AgentCefPanel: JCEF initialization failed", e)
        }
    }

    private fun createBrowser() {
        val b = JBCefBrowser.createBuilder()
            .setOffScreenRendering(true)
            .setEnableOpenDevToolsMenuItem(true)
            .build()

        // Set larger JS query pool for streaming
        b.jbCefClient.setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 200)

        // Initialize the bridge dispatcher — all callJs() calls go through this.
        // The executor calls directly into the CEF browser's JS engine.
        bridgeDispatcher = JsBridgeDispatcher(
            executor = { code ->
                try {
                    b.cefBrowser.executeJavaScript(code, CefResourceSchemeHandler.BASE_URL, 0)
                } catch (e: Exception) {
                    LOG.warn("AgentCefPanel: JS execution failed for ${code.take(60)}: ${e.message}")
                }
            }
        )

        // Register the JVM-global `http://workflow-agent` scheme handler via the
        // shared registrar (idempotent), then install our session-bound upload
        // handler factory. The factory dispatches by URL: `/upload/<sha256>`
        // POSTs go to a fresh AttachmentUploadHandler (which writes to the
        // active session's AttachmentStore), everything else serves bundled
        // webview assets via CefResourceSchemeHandler.
        //
        // The registrar exists because CefApp.registerSchemeHandlerFactory is a
        // single-slot global — pre-registrar, AgentPlanEditor and
        // AgentVisualizationEditor each registered their own static-asset-only
        // factory and silently stomped on this panel's upload-aware factory,
        // breaking image attachment. See WorkflowAgentSchemeRegistrar's KDoc.
        //
        // NOTE: loadURL is called AFTER all query registration + load handler setup
        // (see end of this method) to avoid a race where the page finishes loading
        // before onLoadingStateChange is registered, leaving pageLoaded=false forever.
        try {
            val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(
                com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                    ?: com.intellij.openapi.project.ProjectManager.getInstance().defaultProject
            )
            WorkflowAgentSchemeRegistrar.ensureRegistered()
            WorkflowAgentSchemeRegistrar.setUploadHandlerFactory {
                AttachmentUploadHandler(
                    attachmentStoreProvider = {
                        val dir = currentSessionDirProvider?.invoke()
                        if (dir != null) com.workflow.orchestrator.agent.session.AttachmentStore(dir) else null
                    },
                    settings = settings,
                )
            }
            // Read handler: serves <img src="http://workflow-agent/attachments/<sha>">
            // for thumbnails inside USER_MESSAGE bubbles. Same per-request session
            // resolution as the upload handler — bytes always come from the
            // active session's attachments/ dir.
            WorkflowAgentSchemeRegistrar.setReadHandlerFactory {
                AttachmentReadHandler(
                    attachmentStoreProvider = {
                        val dir = currentSessionDirProvider?.invoke()
                        if (dir != null) com.workflow.orchestrator.agent.session.AttachmentStore(dir) else null
                    },
                )
            }
            // Detach our session-bound factory when this panel disposes so a
            // stale closure can't outlive the panel's lifecycle. Safe even if
            // another chat panel has already overwritten the reference — set is
            // last-write-wins and we only clear when our reference is current
            // (best effort: the JVM-global slot has no read-modify-write API,
            // so we accept the rare race where two panels race-dispose).
            Disposer.register(parentDisposable) {
                WorkflowAgentSchemeRegistrar.setUploadHandlerFactory(null)
                WorkflowAgentSchemeRegistrar.setReadHandlerFactory(null)
            }
        } catch (e: Exception) {
            LOG.warn("AgentCefPanel: scheme handler registration failed", e)
        }

        // Create JS→Kotlin bridges for UI actions (undo, view-trace, example prompts).
        // Every query goes through registerQuery so dispose() can tear them all down
        // in one pass — never call JBCefJSQuery.create directly here.
        undoQuery = registerQuery(b) { _ -> onUndoRequested?.invoke(); JBCefJSQuery.Response("ok") }
        traceQuery = registerQuery(b) { _ -> onViewTraceRequested?.invoke(); JBCefJSQuery.Response("ok") }
        promptQuery = registerQuery(b) { text -> onPromptSubmitted?.invoke(text); JBCefJSQuery.Response("ok") }
        planApproveQuery = registerQuery(b) { _ -> onPlanApproved?.invoke(); JBCefJSQuery.Response("ok") }
        planReviseQuery = registerQuery(b) { commentsJson -> onPlanRevised?.invoke(commentsJson); JBCefJSQuery.Response("ok") }
        planDismissQuery = registerQuery(b) { _ -> onPlanDismissed?.invoke(); JBCefJSQuery.Response("ok") }
        toolToggleQuery = registerQuery(b) { data ->
            // data format: "tool_name:1" or "tool_name:0"
            val colonIdx = data.lastIndexOf(':')
            if (colonIdx > 0) {
                val toolName = data.substring(0, colonIdx)
                val enabled = data.substring(colonIdx + 1) == "1"
                onToolToggled?.invoke(toolName, enabled)
            }
            JBCefJSQuery.Response("ok")
        }

        // Question wizard bridges
        questionAnsweredQuery = registerQuery(b) { data ->
            // data format: "questionId:optionsJson"
            val sep = data.indexOf(':')
            if (sep > 0) onQuestionAnswered?.invoke(data.substring(0, sep), data.substring(sep + 1))
            JBCefJSQuery.Response("ok")
        }
        questionSkippedQuery = registerQuery(b) { data -> onQuestionSkipped?.invoke(data); JBCefJSQuery.Response("ok") }
        chatAboutQuery = registerQuery(b) { data ->
            // data format: "questionId\x1FoptionLabel\x1Fmessage" (unit separator avoids colon conflicts)
            val parts = data.split("\u001F")
            if (parts.size >= 3) onChatAboutOption?.invoke(parts[0], parts[1], parts.drop(2).joinToString("\u001F"))
            JBCefJSQuery.Response("ok")
        }
        questionsSubmittedQuery = registerQuery(b) { _ -> onQuestionsSubmitted?.invoke(); JBCefJSQuery.Response("ok") }
        questionsCancelledQuery = registerQuery(b) { _ -> onQuestionsCancelled?.invoke(); JBCefJSQuery.Response("ok") }
        editQuestionQuery = registerQuery(b) { data -> onEditQuestion?.invoke(data); JBCefJSQuery.Response("ok") }
        deactivateSkillQuery = registerQuery(b) { _ -> onSkillDismissed?.invoke(); JBCefJSQuery.Response("ok") }
        navigateToFileQuery = registerQuery(b) { data ->
            val colonIdx = data.lastIndexOf(':')
            val hasLine = colonIdx > 0 && data.substring(colonIdx + 1).toIntOrNull() != null
            val filePath = if (hasLine) data.substring(0, colonIdx) else data
            val line = if (hasLine) data.substring(colonIdx + 1).toInt() else 0
            onNavigateToFile?.invoke(filePath, line)
            JBCefJSQuery.Response("ok")
        }
        validatePathsQuery = registerQuery(b) { data ->
            // data format: "pathsJson|callbackName"
            val sepIdx = data.lastIndexOf('|')
            val pathsJson = if (sepIdx > 0) data.substring(0, sepIdx) else "[]"
            val callbackName = if (sepIdx > 0) data.substring(sepIdx + 1) else ""
            onValidatePaths?.invoke(pathsJson, callbackName)
            JBCefJSQuery.Response("ok")
        }

        resolveSymbolsQuery = registerQuery(b) { data ->
            val sepIdx = data.lastIndexOf('|')
            val hrefsJson = if (sepIdx > 0) data.substring(0, sepIdx) else "[]"
            val callbackName = if (sepIdx > 0) data.substring(sepIdx + 1) else ""
            onResolveSymbols?.invoke(hrefsJson, callbackName)
            JBCefJSQuery.Response("ok")
        }

        // Toolbar + input bar bridges
        cancelTaskQuery = registerQuery(b) { _ -> onCancelTask?.invoke(); JBCefJSQuery.Response("ok") }
        newChatQuery = registerQuery(b) { _ -> onNewChat?.invoke(); JBCefJSQuery.Response("ok") }
        sendMessageQuery = registerQuery(b) { text -> onSendMessage?.invoke(text); JBCefJSQuery.Response("ok") }
        changeModelQuery = registerQuery(b) { modelId -> onChangeModel?.invoke(modelId); JBCefJSQuery.Response("ok") }
        requestModelListQuery = registerQuery(b) { _ -> onRequestModelList?.invoke(); JBCefJSQuery.Response("ok") }
        togglePlanModeQuery = registerQuery(b) { enabled -> onTogglePlanMode?.invoke(enabled == "true"); JBCefJSQuery.Response("ok") }
        compactContextQuery = registerQuery(b) { force -> onCompactContext?.invoke(force == "true"); JBCefJSQuery.Response("ok") }
        activateSkillQuery = registerQuery(b) { name -> onActivateSkill?.invoke(name); JBCefJSQuery.Response("ok") }
        requestFocusIdeQuery = registerQuery(b) { _ -> onRequestFocusIde?.invoke(); JBCefJSQuery.Response("ok") }
        openSettingsQuery = registerQuery(b) { _ -> onOpenSettings?.invoke(); JBCefJSQuery.Response("ok") }
        openMemorySettingsQuery = registerQuery(b) { _ -> onOpenMemorySettings?.invoke(); JBCefJSQuery.Response("ok") }
        openToolsPanelQuery = registerQuery(b) { _ -> onOpenToolsPanel?.invoke(); JBCefJSQuery.Response("ok") }
        searchMentionsQuery = registerQuery(b) { data ->
            // data format: "type:query" e.g. "file:Login" or "categories:"
            val colonIdx = data.indexOf(':')
            val type = if (colonIdx > 0) data.substring(0, colonIdx) else data
            val query = if (colonIdx > 0) data.substring(colonIdx + 1) else ""
            // Search on IO thread, callback to JS. Echo the query alongside
            // results so JS can drop stale responses (out-of-order races
            // between rapid keystrokes and the 200ms bridge debounce).
            scope.launch {
                try {
                    withTimeout(15_000L) {
                        val results = mentionSearchProvider?.search(type, query) ?: "[]"
                        callJs("receiveMentionResults(${JsEscape.toJsString(query)}, ${JsEscape.toJsString(results)})")
                    }
                } catch (e: Exception) {
                    LOG.warn("searchMentions handler failed: ${e.message}")
                    callJs("receiveMentionResults(${JsEscape.toJsString(query)}, '[]')")
                }
            }
            JBCefJSQuery.Response("ok")
        }
        searchTicketsQuery = registerQuery(b) { query ->
            scope.launch {
                try {
                    withTimeout(15_000L) {
                        val results = mentionSearchProvider?.searchTickets(query) ?: "[]"
                        callJs("(window.__ticketSearchCallback)(${JsEscape.toJsString(results)})")
                    }
                } catch (e: Exception) {
                    LOG.warn("searchTickets handler failed: ${e.message}")
                    callJs("(window.__ticketSearchCallback)('[]')")
                }
            }
            JBCefJSQuery.Response("ok")
        }
        validateTicketQuery = registerQuery(b) { data ->
            // data format: "TICKETKEY|callbackName"
            val parts = data.split("|", limit = 2)
            val ticketKey = parts.getOrElse(0) { "" }
            val callbackName = parts.getOrElse(1) { "" }
            scope.launch {
                try {
                    withTimeout(15_000L) {
                        val result = mentionSearchProvider?.validateTicket(ticketKey)
                        val json = result ?: """{"valid":false}"""
                        callJs("(window[${JsEscape.toJsString(callbackName)}])(${JsEscape.toJsString(json)})")
                    }
                } catch (e: Exception) {
                    LOG.warn("validateTicket handler failed: ${e.message}")
                    callJs("(window[${JsEscape.toJsString(callbackName)}])(${JsEscape.toJsString("""{"valid":false}""")})")
                }
            }
            JBCefJSQuery.Response("ok")
        }
        sendMessageWithMentionsQuery = registerQuery(b) { payload ->
            try {
                val json = Json.parseToJsonElement(payload).jsonObject
                val text = json["text"]?.jsonPrimitive?.content ?: ""
                val mentionsJson = json["mentions"]?.toString() ?: "[]"
                // Multimodal-agent: attachments uploaded for this turn. Each entry
                // carries sha256/mime/size/originalFilename so AgentService can
                // build ContentBlock.ImageRef parts on the user ApiMessage.
                // Absent for non-image turns.
                val attachmentsJson = json["attachments"]?.toString()
                val handler = onSendMessageWithMentions
                if (handler != null) {
                    handler.invoke(text, mentionsJson, attachmentsJson)
                } else {
                    // Fallback: no mention handler wired — send plain text
                    onSendMessage?.invoke(text)
                }
            } catch (e: Exception) {
                // Fallback: treat entire payload as text
                onSendMessage?.invoke(payload)
            }
            JBCefJSQuery.Response("ok")
        }
        openInEditorTabQuery = registerQuery(b) { payload -> onOpenInEditorTab?.invoke(payload); JBCefJSQuery.Response("ok") }
        focusPlanEditorQuery = registerQuery(b) { _ -> onFocusPlanEditor?.invoke(); JBCefJSQuery.Response("ok") }
        openApprovedPlanQuery = registerQuery(b) { _ -> onOpenApprovedPlan?.invoke(); JBCefJSQuery.Response("ok") }
        revisePlanFromEditorQuery = registerQuery(b) { _ -> onRevisePlanFromEditor?.invoke(); JBCefJSQuery.Response("ok") }
        handoffForkQuery = registerQuery(b) { _ -> onHandoffStartFresh?.invoke(); JBCefJSQuery.Response("ok") }
        handoffKeepQuery = registerQuery(b) { _ -> onHandoffKeepChatting?.invoke(); JBCefJSQuery.Response("ok") }
        viewInEditorQuery = registerQuery(b) { _ -> onViewInEditor?.invoke(); JBCefJSQuery.Response("ok") }

        // Tool call approval bridges
        approveToolCallQuery = registerQuery(b) { _ -> onApproveToolCall?.invoke(); JBCefJSQuery.Response("ok") }
        denyToolCallQuery = registerQuery(b) { _ -> onDenyToolCall?.invoke(); JBCefJSQuery.Response("ok") }
        allowToolForSessionQuery = registerQuery(b) { toolName -> onAllowToolForSession?.invoke(toolName); JBCefJSQuery.Response("ok") }
        interactiveHtmlMessageQuery = registerQuery(b) { json -> onInteractiveHtmlMessage?.invoke(json); JBCefJSQuery.Response("ok") }
        acceptDiffHunkQuery = registerQuery(b) { data ->
            try {
                val obj = Json.parseToJsonElement(data).jsonObject
                val fp = obj["filePath"]?.jsonPrimitive?.content ?: ""
                val hi = obj["hunkIndex"]?.jsonPrimitive?.int ?: 0
                val ec = obj["editedContent"]?.jsonPrimitive?.content
                onAcceptDiffHunk?.invoke(fp, hi, ec)
            } catch (e: Exception) {
                LOG.warn("Failed to parse acceptDiffHunk data", e)
            }
            JBCefJSQuery.Response("ok")
        }
        rejectDiffHunkQuery = registerQuery(b) { data ->
            try {
                val obj = Json.parseToJsonElement(data).jsonObject
                val fp = obj["filePath"]?.jsonPrimitive?.content ?: ""
                val hi = obj["hunkIndex"]?.jsonPrimitive?.int ?: 0
                onRejectDiffHunk?.invoke(fp, hi)
            } catch (e: Exception) {
                LOG.warn("Failed to parse rejectDiffHunk data", e)
            }
            JBCefJSQuery.Response("ok")
        }
        killToolCallQuery = registerQuery(b) { toolCallId -> onKillToolCall?.invoke(toolCallId); JBCefJSQuery.Response("ok") }
        killSubAgentQuery = registerQuery(b) { agentId -> onKillSubAgent?.invoke(agentId); JBCefJSQuery.Response("ok") }
        startIncomingDelegationQuery = registerQuery(b) { key -> onStartIncomingDelegation?.invoke(key); JBCefJSQuery.Response("ok") }
        revertToUserMessageQuery = registerQuery(b) { tsStr ->
            val ts = tsStr.toLongOrNull(); if (ts != null) onRevertToUserMessage?.invoke(ts)
            JBCefJSQuery.Response("ok")
        }
        revertFileToBaselineQuery = registerQuery(b) { path -> onRevertFileToBaseline?.invoke(path); JBCefJSQuery.Response("ok") }
        revertAllQuery = registerQuery(b) { _ -> onRevertAll?.invoke(); JBCefJSQuery.Response("ok") }
        cancelSteeringQuery = registerQuery(b) { steeringId -> onCancelSteering?.invoke(steeringId); JBCefJSQuery.Response("ok") }
        retryLastTaskQuery = registerQuery(b) { _ -> onRetryLastTask?.invoke(); JBCefJSQuery.Response("ok") }
        processInputQuery = registerQuery(b) { input -> onProcessInputResolved?.invoke(input); JBCefJSQuery.Response("ok") }
        artifactResultQuery = registerQuery(b) { json -> onArtifactResult?.invoke(json); JBCefJSQuery.Response("ok") }
        interactiveRenderResultQuery = registerQuery(b) { json -> onInteractiveRenderResult?.invoke(json); JBCefJSQuery.Response("ok") }

        // Session history bridges
        showSessionQuery = registerQuery(b) { sessionId -> onShowSession?.invoke(sessionId); JBCefJSQuery.Response("ok") }
        deleteSessionQuery = registerQuery(b) { sessionId -> onDeleteSession?.invoke(sessionId); JBCefJSQuery.Response("ok") }
        toggleFavoriteQuery = registerQuery(b) { sessionId -> onToggleFavorite?.invoke(sessionId); JBCefJSQuery.Response("ok") }
        startNewSessionQuery = registerQuery(b) { _ -> onStartNewSession?.invoke(); JBCefJSQuery.Response("ok") }
        bulkDeleteSessionsQuery = registerQuery(b) { json -> onBulkDeleteSessions?.invoke(json); JBCefJSQuery.Response("ok") }
        exportSessionQuery = registerQuery(b) { sessionId -> onExportSession?.invoke(sessionId); JBCefJSQuery.Response("ok") }
        exportAllSessionsQuery = registerQuery(b) { _ -> onExportAllSessions?.invoke(); JBCefJSQuery.Response("ok") }
        requestHistoryQuery = registerQuery(b) { _ -> onRequestHistory?.invoke(); JBCefJSQuery.Response("ok") }
        resumeViewedSessionQuery = registerQuery(b) { _ -> onResumeViewedSession?.invoke(); JBCefJSQuery.Response("ok") }
        copyToClipboardQuery = registerQuery(b) { text ->
            com.workflow.orchestrator.core.ui.ClipboardUtil.copyToClipboard(text)
            JBCefJSQuery.Response("ok")
        }
        openInsightsTabQuery = registerQuery(b) { _ ->
            // TODO Phase 1.1: navigate to Insights tab
            LOG.info("openInsightsTab requested from agent chat")
            onOpenInsightsTab?.invoke()
            JBCefJSQuery.Response("ok")
        }
        loadBackgroundSnapshotQuery = registerQuery(b) { sessionId ->
            val json = onLoadBackgroundSnapshot?.invoke(sessionId) ?: "[]"
            JBCefJSQuery.Response(json)
        }
        loadMonitorSnapshotQuery = registerQuery(b) { sessionId ->
            val json = onLoadMonitorSnapshot?.invoke(sessionId) ?: "[]"
            JBCefJSQuery.Response(json)
        }
        // Phase 5: chunked-by-sha256 upload pre-flight. The webview's
        // AttachmentManager calls this BEFORE shipping bytes so we can skip
        // the upload entirely on a hash collision (within-session dedup).
        // P2-18: the directory scan used to run synchronously in this bridge
        // handler (per attached file, on the UI-blocking bridge thread). The
        // JS side awaits a Promise, so the handler now ACKs immediately and
        // resolves the promise asynchronously: the existence check runs on
        // [scope] (Dispatchers.IO) and delivers via the injected
        // `window.__resolveAttachmentExists(sha, exists)` callback. A wrong
        // `false` is safe (the upload path dedups by sha anyway); the JS side
        // also self-resolves `false` after a timeout so a dropped callback
        // can never hang the send. The multi-MB upload itself still goes
        // through AttachmentUploadHandler via
        // fetch('http://workflow-agent/upload/<sha256>').
        attachmentExistsQuery = registerQuery(b) { sha256 ->
            val dir = currentSessionDirProvider?.invoke()
            if (dir == null) {
                resolveAttachmentExists(sha256, exists = false)
            } else {
                scope.launch {
                    val exists = try {
                        // Cheap existence probe: directory scan only, no byte read.
                        com.workflow.orchestrator.agent.session.AttachmentStore(dir)
                            .findExtensionForBlocking(sha256) != null
                    } catch (e: Exception) {
                        LOG.warn("attachmentExistsQuery: existence check failed for $sha256", e)
                        false
                    }
                    resolveAttachmentExists(sha256, exists)
                }
            }
            JBCefJSQuery.Response("ok")
        }

        // Multimodal-agent Phase 7 — chat input usage indicator. JS calls
        // `window.workflowAgent.getContextUsage()` which dispatches into this
        // query; the React `<InputBar>` polls every 1s while the input is
        // mounted. Returns JSON: {"used": N, "max": M}. When no session is
        // active or the provider isn't wired, returns 0/FALLBACK so the indicator
        // doesn't crash (sub-second placeholder before the real provider is wired).
        contextUsageQuery = registerQuery(b) { _ ->
            val (used, max) = contextUsageProvider?.invoke()
                ?: (0 to com.workflow.orchestrator.agent.model.EffectiveContextWindow.FALLBACK)
            JBCefJSQuery.Response("""{"used":$used,"max":$max}""")
        }

        // Multimodal-agent Phase 7 followup F-P5-2 / F-P6-1 — push the live
        // PluginSettings image-input fields to JS. Called once on page-ready
        // and again after Settings.apply() (the AgentController invokes
        // `pushImageSettings()` from its settings-change listener). Returns a
        // raw JSON string that JS sets onto the AttachmentManager singleton
        // directly.
        imageSettingsQuery = registerQuery(b) { _ ->
            val payload = imageSettingsProvider?.invoke() ?: """{"maxBytes":5242880,"mimeWhitelist":["image/png","image/jpeg","image/webp"],"maxPerTurn":2,"enabled":true}"""
            JBCefJSQuery.Response(payload)
        }

        // File-attachment picker bridge. JS calls window._pickAttachment() to
        // open the native IntelliJ FileChooser; the actual pick + ingest runs
        // off-EDT via onPickAttachment (wired by AgentController in Task 8).
        pickAttachmentQuery = registerQuery(b) { _ ->
            onPickAttachment?.invoke()
            JBCefJSQuery.Response("ok")
        }

        // Phase 4 chat hyperlink bridges. The webview's <ChatLink> intercepts
        // every <a> click, calls _resolveLink to populate the confirmation
        // modal, and (on the user's confirm) calls _openLink to fire the
        // navigation through the :core resolver. The modal always shows the
        // verbatim `raw` href alongside the resolver's friendly description so
        // the user can spot spoofed labels before opening.
        resolveLinkQuery = registerQuery(b) { href ->
            val proj = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                ?: com.intellij.openapi.project.ProjectManager.getInstance().defaultProject
            val link = com.workflow.orchestrator.core.services.LinkParser.parse(href)
            val json = if (link == null) {
                """{"kind":"UNKNOWN","raw":${JsEscape.toJsonString(href)},"displayLabel":${JsEscape.toJsonString(href)},"targetDescription":${JsEscape.toJsonString("Unrecognised link")},"browserUrl":null}"""
            } else {
                val service = proj.service<com.workflow.orchestrator.core.services.LinkResolver>()
                val res = service.resolve(link)
                val browserUrlJson = res.browserUrl?.let { JsEscape.toJsonString(it) } ?: "null"
                """{"kind":${JsEscape.toJsonString(res.kind.name)},"raw":${JsEscape.toJsonString(res.raw)},"displayLabel":${JsEscape.toJsonString(res.displayLabel)},"targetDescription":${JsEscape.toJsonString(res.targetDescription)},"browserUrl":$browserUrlJson}"""
            }
            JBCefJSQuery.Response(json)
        }
        openLinkQuery = registerQuery(b) { href ->
            val proj = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                ?: com.intellij.openapi.project.ProjectManager.getInstance().defaultProject
            val link = com.workflow.orchestrator.core.services.LinkParser.parse(href)
            if (link != null) {
                val service = proj.service<com.workflow.orchestrator.core.services.LinkResolver>()
                service.open(link)
            } else if (href.startsWith("http://") || href.startsWith("https://")) {
                // Unparseable but http(s) — fall back to platform browser so the
                // user isn't silently stuck. Parseable web hrefs already route
                // through WebLinkResolver above.
                try { com.intellij.ide.BrowserUtil.browse(href) } catch (e: Exception) {
                    LOG.warn("openLinkQuery: BrowserUtil.browse failed for $href", e)
                }
            }
            JBCefJSQuery.Response("ok")
        }

        // Wait for page load before executing JS
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                browser: CefBrowser?, isLoading: Boolean,
                canGoBack: Boolean, canGoForward: Boolean
            ) {
                if (!isLoading) {
                    LOG.info("AgentCefPanel: onLoadingStateChange(isLoading=false) — injecting bridges")
                    // Each bridge injection is wrapped individually so a failure in one
                    // does not skip the rest. Without this, a single q.inject() exception
                    // would leave ALL subsequent window._xxx functions unregistered — the
                    // root cause of the "skills missing from input bar" regression.
                    var bridgeFailures = 0
                    fun injectBridge(name: String, block: () -> Unit) {
                        try { block() } catch (e: Exception) {
                            bridgeFailures++
                            LOG.warn("AgentCefPanel: bridge injection failed for $name: ${e.message}")
                        }
                    }
                    injectBridge("theme") { applyCurrentTheme() }
                    // Inject JS→Kotlin bridge functions for JCEF UI actions
                    injectBridge("_requestUndo") { undoQuery?.let { q ->
                        js("window._requestUndo = function() { ${q.inject("'undo'")} }")
                    } }
                    injectBridge("_requestViewTrace") { traceQuery?.let { q ->
                        js("window._requestViewTrace = function() { ${q.inject("'trace'")} }")
                    } }
                    injectBridge("_submitPrompt") { promptQuery?.let { q ->
                        js("window._submitPrompt = function(text) { ${q.inject("text")} }")
                    } }
                    // All bridges: (query, windowFnName, jsFnParams, injectExpr)
                    // Each is wrapped in injectBridge so one failure doesn't skip the rest.
                    injectBridge("_approvePlan") { planApproveQuery?.let { q -> js("window._approvePlan = function() { ${q.inject("'approve'")} }") } }
                    injectBridge("_revisePlan") { planReviseQuery?.let { q -> js("window._revisePlan = function(comments) { ${q.inject("comments")} }") } }
                    injectBridge("_dismissPlan") { planDismissQuery?.let { q -> js("window._dismissPlan = function() { ${q.inject("'dismiss'")} }") } }
                    injectBridge("_toggleTool") { toolToggleQuery?.let { q -> js("window._toggleTool = function(data) { ${q.inject("data")} }") } }
                    injectBridge("_questionAnswered") { questionAnsweredQuery?.let { q -> js("window._questionAnswered = function(qid, opts) { ${q.inject("qid + ':' + opts")} }") } }
                    injectBridge("_questionSkipped") { questionSkippedQuery?.let { q -> js("window._questionSkipped = function(qid) { ${q.inject("qid")} }") } }
                    injectBridge("_chatAboutOption") { chatAboutQuery?.let { q -> js("window._chatAboutOption = function(qid, label, msg) { ${q.inject("qid + '\\x1F' + label + '\\x1F' + msg")} }") } }
                    injectBridge("_questionsSubmitted") { questionsSubmittedQuery?.let { q -> js("window._questionsSubmitted = function() { ${q.inject("'submit'")} }") } }
                    injectBridge("_questionsCancelled") { questionsCancelledQuery?.let { q -> js("window._questionsCancelled = function() { ${q.inject("'cancel'")} }") } }
                    injectBridge("_editQuestion") { editQuestionQuery?.let { q -> js("window._editQuestion = function(qid) { ${q.inject("qid")} }") } }
                    injectBridge("_deactivateSkill") { deactivateSkillQuery?.let { q -> js("window._deactivateSkill = function() { ${q.inject("'dismiss'")} }") } }
                    injectBridge("_navigateToFile") { navigateToFileQuery?.let { q -> js("window._navigateToFile = function(path) { ${q.inject("path")} }") } }
                    injectBridge("_validatePaths") { validatePathsQuery?.let { q -> js("window._validatePaths = function(pathsJson, cb) { ${q.inject("pathsJson + '|' + cb")} }") } }
                    injectBridge("_resolveSymbols") { resolveSymbolsQuery?.let { q -> js("window._resolveSymbols = function(hrefsJson, cb) { ${q.inject("hrefsJson + '|' + cb")} }") } }
                    injectBridge("_cancelTask") { cancelTaskQuery?.let { q -> js("window._cancelTask = function() { ${q.inject("'cancel'")} }") } }
                    injectBridge("_newChat") { newChatQuery?.let { q -> js("window._newChat = function() { ${q.inject("'new'")} }") } }
                    injectBridge("_sendMessage") { sendMessageQuery?.let { q -> js("window._sendMessage = function(text) { ${q.inject("text")} }") } }
                    injectBridge("_changeModel") { changeModelQuery?.let { q -> js("window._changeModel = function(modelId) { ${q.inject("modelId")} }") } }
                    injectBridge("_requestModelList") { requestModelListQuery?.let { q -> js("window._requestModelList = function() { ${q.inject("'pull'")} }") } }
                    injectBridge("_togglePlanMode") { togglePlanModeQuery?.let { q -> js("window._togglePlanMode = function(enabled) { ${q.inject("String(enabled)")} }") } }
                    injectBridge("_compactContext") { compactContextQuery?.let { q -> js("window._compactContext = function(force) { ${q.inject("String(force)")} }") } }
                    injectBridge("_activateSkill") { activateSkillQuery?.let { q -> js("window._activateSkill = function(name) { ${q.inject("name")} }") } }
                    injectBridge("_requestFocusIde") { requestFocusIdeQuery?.let { q -> js("window._requestFocusIde = function() { ${q.inject("'focus'")} }") } }
                    injectBridge("_openSettings") { openSettingsQuery?.let { q -> js("window._openSettings = function() { ${q.inject("'settings'")} }") } }
                    injectBridge("_openMemorySettings") { openMemorySettingsQuery?.let { q -> js("window._openMemorySettings = function() { ${q.inject("'memorySettings'")} }") } }
                    injectBridge("_openToolsPanel") { openToolsPanelQuery?.let { q -> js("window._openToolsPanel = function() { ${q.inject("'tools'")} }") } }
                    injectBridge("_searchMentions") { searchMentionsQuery?.let { q -> js("window._searchMentions = function(data) { ${q.inject("data")} }") } }
                    injectBridge("_searchTickets") { searchTicketsQuery?.let { q -> js("window._searchTickets = function(query) { ${q.inject("query")} }") } }
                    injectBridge("_validateTicket") { validateTicketQuery?.let { q -> js("window._validateTicket = function(key, cb) { ${q.inject("key + '|' + cb")} }") } }
                    injectBridge("_sendMessageWithMentions") { sendMessageWithMentionsQuery?.let { q -> js("window._sendMessageWithMentions = function(payload) { ${q.inject("payload")} }") } }
                    injectBridge("_openInEditorTab") { openInEditorTabQuery?.let { q -> js("window._openInEditorTab = function(payload) { ${q.inject("payload")} }") } }
                    injectBridge("_focusPlanEditor") { focusPlanEditorQuery?.let { q -> js("window._focusPlanEditor = function() { ${q.inject("''")} }") } }
                    injectBridge("_openApprovedPlan") { openApprovedPlanQuery?.let { q -> js("window._openApprovedPlan = function() { ${q.inject("''")} }") } }
                    injectBridge("_revisePlanFromEditor") { revisePlanFromEditorQuery?.let { q -> js("window._revisePlanFromEditor = function() { ${q.inject("''")} }") } }
                    injectBridge("_handoffFork") { handoffForkQuery?.let { q -> js("window._handoffFork = function() { ${q.inject("''")} }") } }
                    injectBridge("_handoffKeep") { handoffKeepQuery?.let { q -> js("window._handoffKeep = function() { ${q.inject("''")} }") } }
                    injectBridge("_viewInEditor") { viewInEditorQuery?.let { q -> js("window._viewInEditor = function() { ${q.inject("''")} }") } }
                    injectBridge("_approveToolCall") { approveToolCallQuery?.let { q -> js("window._approveToolCall = function() { ${q.inject("'approve'")} }") } }
                    injectBridge("_denyToolCall") { denyToolCallQuery?.let { q -> js("window._denyToolCall = function() { ${q.inject("'deny'")} }") } }
                    injectBridge("_allowToolForSession") { allowToolForSessionQuery?.let { q -> js("window._allowToolForSession = function(toolName) { ${q.inject("toolName")} }") } }
                    injectBridge("_interactiveHtmlMessage") { interactiveHtmlMessageQuery?.let { q -> js("window._interactiveHtmlMessage = function(json) { ${q.inject("json")} }") } }
                    injectBridge("_acceptDiffHunk") { acceptDiffHunkQuery?.let { q -> js("window._acceptDiffHunk = function(fp,hi,ec) { ${q.inject("JSON.stringify({filePath:fp,hunkIndex:hi,editedContent:ec||null})")} }") } }
                    injectBridge("_rejectDiffHunk") { rejectDiffHunkQuery?.let { q -> js("window._rejectDiffHunk = function(fp,hi) { ${q.inject("JSON.stringify({filePath:fp,hunkIndex:hi})")} }") } }
                    injectBridge("_killToolCall") { killToolCallQuery?.let { q -> js("window._killToolCall = function(toolCallId) { ${q.inject("toolCallId")} }") } }
                    injectBridge("_killSubAgent") { killSubAgentQuery?.let { q -> js("window._killSubAgent = function(agentId) { ${q.inject("agentId")} }") } }
                    injectBridge("_startIncomingDelegation") { startIncomingDelegationQuery?.let { q -> js("window._startIncomingDelegation = function(key) { ${q.inject("key")} }") } }
                    injectBridge("_resolveProcessInput") { processInputQuery?.let { q -> js("window._resolveProcessInput = function(input) { ${q.inject("input")} }") } }
                    injectBridge("_revertToUserMessage") { revertToUserMessageQuery?.let { q -> js("window._revertToUserMessage = function(ts) { ${q.inject("String(ts)")} }") } }
                    injectBridge("_revertFileToBaseline") { revertFileToBaselineQuery?.let { q -> js("window._revertFileToBaseline = function(p) { ${q.inject("p")} }") } }
                    injectBridge("_revertAll") { revertAllQuery?.let { q -> js("window._revertAll = function() { ${q.inject("''")} }") } }
                    injectBridge("_cancelSteering") { cancelSteeringQuery?.let { q -> js("window._cancelSteering = function(id) { ${q.inject("id")} }") } }
                    injectBridge("_retryLastTask") { retryLastTaskQuery?.let { q -> js("window._retryLastTask = function() { ${q.inject("''")} }") } }
                    injectBridge("_reportArtifactResult") { artifactResultQuery?.let { q -> js("window._reportArtifactResult = function(json) { ${q.inject("json")} }") } }
                    injectBridge("_reportInteractiveRender") { interactiveRenderResultQuery?.let { q -> js("window._reportInteractiveRender = function(json) { ${q.inject("json")} }") } }
                    injectBridge("_showSession") { showSessionQuery?.let { q -> js("window._showSession = function(sessionId) { ${q.inject("sessionId")} }") } }
                    injectBridge("_deleteSession") { deleteSessionQuery?.let { q -> js("window._deleteSession = function(sessionId) { ${q.inject("sessionId")} }") } }
                    injectBridge("_toggleFavorite") { toggleFavoriteQuery?.let { q -> js("window._toggleFavorite = function(sessionId) { ${q.inject("sessionId")} }") } }
                    injectBridge("_startNewSession") { startNewSessionQuery?.let { q -> js("window._startNewSession = function() { ${q.inject("'new'")} }") } }
                    injectBridge("_bulkDeleteSessions") { bulkDeleteSessionsQuery?.let { q -> js("window._bulkDeleteSessions = function(json) { ${q.inject("json")} }") } }
                    injectBridge("_exportSession") { exportSessionQuery?.let { q -> js("window._exportSession = function(sessionId) { ${q.inject("sessionId")} }") } }
                    injectBridge("_exportAllSessions") { exportAllSessionsQuery?.let { q -> js("window._exportAllSessions = function() { ${q.inject("'all'")} }") } }
                    injectBridge("_requestHistory") { requestHistoryQuery?.let { q -> js("window._requestHistory = function() { ${q.inject("''")} }") } }
                    injectBridge("_resumeViewedSession") { resumeViewedSessionQuery?.let { q -> js("window._resumeViewedSession = function() { ${q.inject("''")} }") } }
                    injectBridge("_copyToClipboard") { copyToClipboardQuery?.let { q -> js("window._copyToClipboard = function(text) { ${q.inject("text")} }") } }
                    injectBridge("_openInsightsTab") { openInsightsTabQuery?.let { q -> js("window._openInsightsTab = function() { ${q.inject("'insights'")} }") } }
                    injectBridge("_loadBackgroundSnapshot") {
                        loadBackgroundSnapshotQuery?.let { q ->
                            js(
                                "window._loadBackgroundSnapshot = function(sessionId) {" +
                                    " return new Promise(function(resolve, reject) {" +
                                    " ${q.inject("sessionId", "function(r) { resolve(JSON.parse(r || '[]')); }", "function(err) { resolve([]); }")}" +
                                    " }); };"
                            )
                        }
                    }
                    injectBridge("_loadMonitorSnapshot") {
                        loadMonitorSnapshotQuery?.let { q ->
                            js(
                                "window._loadMonitorSnapshot = function(sessionId) {" +
                                    " return new Promise(function(resolve, reject) {" +
                                    " ${q.inject("sessionId", "function(r) { resolve(JSON.parse(r || '[]')); }", "function(err) { resolve([]); }")}" +
                                    " }); };"
                            )
                        }
                    }
                    // Multimodal-agent Phase 7 — chat input usage indicator.
                    // JS bridge returns a Promise resolving to {used, max}.
                    injectBridge("_getContextUsage") {
                        contextUsageQuery?.let { q ->
                            js(
                                "window._getContextUsage = function() {" +
                                    " return new Promise(function(resolve) {" +
                                    " ${q.inject("'pull'", "function(r) { try { resolve(JSON.parse(r)); } catch(e) { resolve({used:0,max:132000}); } }", "function(_) { resolve({used:0,max:132000}); }")}" +
                                    " }); };"
                            )
                        }
                    }
                    // Multimodal-agent Phase 7 followup F-P5-2 / F-P6-1 — push
                    // current image settings to JS. JS calls this once on
                    // page-ready and again after Settings.apply().
                    injectBridge("_getImageSettings") {
                        imageSettingsQuery?.let { q ->
                            js(
                                "window._getImageSettings = function() {" +
                                    " return new Promise(function(resolve) {" +
                                    " ${q.inject("'pull'", "function(r) { try { resolve(JSON.parse(r)); } catch(e) { resolve(null); } }", "function(_) { resolve(null); }")}" +
                                    " }); };"
                            )
                        }
                    }
                    // File-attachment picker — triggers the native IntelliJ
                    // FileChooser; JCEF cannot open an OS file dialog from HTML
                    // <input type=file>, so we handle it on the JVM side.
                    injectBridge("_pickAttachment") {
                        pickAttachmentQuery?.let { q ->
                            js("window._pickAttachment = function() { ${q.inject("'pick'")} };")
                        }
                    }
                    // Phase 5: image-attachment pre-flight. JS asks Kotlin
                    // whether bytes for a given sha256 already exist in the
                    // active session's attachments/ dir, so we can skip the
                    // upload on a hash collision.
                    // P2-18 async pattern: the query ACKs immediately ("ok");
                    // the real answer arrives later via
                    // window.__resolveAttachmentExists(sha, exists) pushed
                    // from Kotlin (resolveAttachmentExists → callJs) once the
                    // off-thread directory scan finishes. Waiters are keyed by
                    // sha (an array — concurrent calls for the same sha all
                    // resolve together) and self-resolve {exists:false} after
                    // 10s so a dropped callback degrades to a redundant upload
                    // instead of a hung send.
                    injectBridge("_attachmentExists") {
                        attachmentExistsQuery?.let { q ->
                            js(
                                "window.__attachmentExistsWaiters = window.__attachmentExistsWaiters || {};" +
                                    " window.__resolveAttachmentExists = function(sha, exists) {" +
                                    " var ws = window.__attachmentExistsWaiters[sha] || [];" +
                                    " delete window.__attachmentExistsWaiters[sha];" +
                                    " ws.forEach(function(w) { clearTimeout(w.t); w.resolve({exists: !!exists}); }); };" +
                                    " window._attachmentExists = function(sha256) {" +
                                    " return new Promise(function(resolve) {" +
                                    " var w = { resolve: resolve };" +
                                    " w.t = setTimeout(function() {" +
                                    " var ws = window.__attachmentExistsWaiters[sha256] || [];" +
                                    " var i = ws.indexOf(w); if (i >= 0) { ws.splice(i, 1); }" +
                                    " resolve({exists: false}); }, 10000);" +
                                    " (window.__attachmentExistsWaiters[sha256] = window.__attachmentExistsWaiters[sha256] || []).push(w);" +
                                    " ${q.inject("sha256", "function(r) {}", "function(err) { window.__resolveAttachmentExists(sha256, false); }")}" +
                                    " }); };"
                            )
                        }
                    }
                    // Phase 4 chat hyperlink — resolver returns the JSON
                    // payload as a string; JS parses it inside the modal.
                    // _openLink is fire-and-forget but we still go through the
                    // bridge so the resolver can fan out to file/class/jira/web.
                    injectBridge("_resolveLink") {
                        resolveLinkQuery?.let { q ->
                            js(
                                "window._resolveLink = function(href) {" +
                                    " return new Promise(function(resolve, reject) {" +
                                    " ${q.inject("href", "function(r) { resolve(r); }", "function(err) { reject(err); }")}" +
                                    " }); };"
                            )
                        }
                    }
                    injectBridge("_openLink") {
                        openLinkQuery?.let { q ->
                            js("window._openLink = function(href) { ${q.inject("href")} }")
                        }
                    }

                    if (bridgeFailures > 0) {
                        LOG.error("AgentCefPanel: $bridgeFailures bridge injection(s) FAILED — some JS→Kotlin callbacks may be missing")
                    } else {
                        LOG.info("AgentCefPanel: all bridges injected successfully")
                    }
                    // markLoaded flushes all buffered callJs calls. injectBridge catches
                    // per-bridge exceptions, so we always reach here.
                    LOG.info("AgentCefPanel: marking page loaded, flushing ${bridgeDispatcher?.pendingCallCount ?: 0} buffered calls")
                    bridgeDispatcher?.markLoaded()

                    // Notify controller so it can re-push initial state (model, skills,
                    // memory). This is a safety net: if the page loaded slowly and
                    // buffered calls already flushed, this is a harmless no-op. If
                    // anything was lost, this guarantees the UI gets the correct state.
                    onPageReady?.invoke()
                }
            }
        }, b.cefBrowser)

        browser = b
        add(b.component, BorderLayout.CENTER)

        // Install the OS drag-and-drop target.  maybeInstallDropTarget() is
        // idempotent (dropTargetInstalled flag) and handles both orderings:
        //   (a) createBrowser finishes BEFORE AgentController sets onDropTargetReady
        //       → setter fires maybeInstallDropTarget on assignment (common path).
        //   (b) onDropTargetReady set BEFORE createBrowser finishes (rare)
        //       → this call installs immediately.
        maybeInstallDropTarget()

        // Load the page LAST — after the load handler is registered.
        // If loadURL is called before addLoadHandler, the page can finish loading
        // before the handler is attached (local JAR resource = sub-millisecond load),
        // the onLoadingStateChange(isLoading=false) event fires with no listener,
        // pageLoaded stays false forever, and all callJs calls are buffered but never
        // flushed — causing the stuck-spinner bug.
        try {
            b.loadURL(CefResourceSchemeHandler.BASE_URL + "index.html")
        } catch (e: Exception) {
            LOG.warn("AgentCefPanel: loadURL failed, falling back to loadHTML", e)
            val htmlContent = javaClass.classLoader.getResource("webview/dist/index.html")?.readText()
            if (htmlContent != null) b.loadHTML(htmlContent)
            else LOG.error("AgentCefPanel: index.html not found in resources")
        }

        // Watchdog: warn if the page hasn't loaded within 15 seconds.
        // Do NOT force-flush (markLoaded) here — on slow machines the page may load
        // at 30-60s, and flushing JS calls into a non-ready page loses them permanently.
        // The real onLoadingStateChange handler will flush when the page is truly ready.
        val dispatcher = bridgeDispatcher
        if (dispatcher != null) {
            // Replace the previous java.util.Timer("cef-page-load-watchdog", true) with a
            // scope.launch + delay so the watchdog is automatically cancelled when dispose()
            // cancels `scope`. This avoids one daemon thread per createBrowser() call on
            // repeated open/close (audit finding agent-ui:F-4).
            scope.launch {
                kotlinx.coroutines.delay(15_000L)
                if (!dispatcher.isLoaded && !dispatcher.isDisposed) {
                    LOG.warn("AgentCefPanel: page not loaded after 15s — ${dispatcher.pendingCallCount} calls still buffered (will flush when page loads)")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  Public API — mirrors RichStreamingPanel
    // ═══════════════════════════════════════════════════

    fun startSession(task: String, attachmentsJson: String? = null) {
        if (!attachmentsJson.isNullOrBlank() && attachmentsJson != "[]") {
            callJs("startSessionWithAttachments(${JsEscape.toJsString(task)}, ${JsEscape.toJsString(attachmentsJson)})")
            return
        }
        callJs("startSession(${JsEscape.toJsString(task)})")
    }

    /**
     * Cross-IDE delegation (IDE-B leg-a): start a session whose opening user bubble
     * AND every subsequent assistant/tool bubble render the delegated tint + left
     * accent stripe + "delegated · {repo}" pill. [delegatorRepo] is the delegating
     * IDE's REPO NAME — never the raw "ide-$pid" identifier.
     */
    fun startSessionDelegated(task: String, delegatorRepo: String) {
        callJs("startSessionDelegated(${JsEscape.toJsString(task)}, ${JsEscape.toJsString(delegatorRepo)})")
    }

    fun startSessionWithMentions(task: String, mentionsJson: String, attachmentsJson: String? = null) {
        if (!attachmentsJson.isNullOrBlank() && attachmentsJson != "[]") {
            callJs("startSessionWithMentionsAndAttachments(${JsEscape.toJsString(task)}, ${JsEscape.toJsString(mentionsJson)}, ${JsEscape.toJsString(attachmentsJson)})")
            return
        }
        callJs("startSessionWithMentions(${JsEscape.toJsString(task)}, ${JsEscape.toJsString(mentionsJson)})")
    }

    fun appendUserMessage(text: String, attachmentsJson: String? = null) {
        if (!attachmentsJson.isNullOrBlank() && attachmentsJson != "[]") {
            callJs("appendUserMessageWithAttachments(${JsEscape.toJsString(text)}, ${JsEscape.toJsString(attachmentsJson)})")
            return
        }
        callJs("appendUserMessage(${JsEscape.toJsString(text)})")
    }

    fun appendPlanApprovedMessage(planMarkdown: String) {
        callJs("appendPlanApprovedMessage(${JsEscape.toJsString(planMarkdown)})")
    }

    fun appendUserMessageWithMentions(text: String, mentionsJson: String, attachmentsJson: String? = null) {
        if (!attachmentsJson.isNullOrBlank() && attachmentsJson != "[]") {
            callJs("appendUserMessageWithMentionsAndAttachments(${JsEscape.toJsString(text)}, ${JsEscape.toJsString(mentionsJson)}, ${JsEscape.toJsString(attachmentsJson)})")
            return
        }
        callJs("appendUserMessageWithMentions(${JsEscape.toJsString(text)}, ${JsEscape.toJsString(mentionsJson)})")
    }

    fun finalizeQuestionsAsMessage() {
        callJs("finalizeQuestionsAsMessage()")
    }

    fun completeSession(
        tokensUsed: Int, iterations: Int, filesModified: List<String>,
        durationMs: Long, status: RichStreamingPanel.SessionStatus
    ) {
        val filesArray = filesModified.joinToString(",") { JsEscape.toJsString(it) }
        callJs("endStream(); completeSession('${status.name}',$tokensUsed,$durationMs,$iterations,[$filesArray])")
    }

    fun appendStreamToken(token: String) {
        callJs("appendToken(${JsEscape.toJsString(token)})")
    }

    fun flushStreamBuffer() {
        callJs("endStream()")
    }

    fun finalizeToolChain() {
        callJs("finalizeToolChain()")
    }

    fun appendCompletionCard(data: CompletionData) {
        val json = JSON.encodeToString(data)
        callJs("window._appendCompletionCard(${JsEscape.toJsString(json)})")
    }

    fun appendToolCall(
        toolCallId: String = "",
        toolName: String, args: String = "",
        status: RichStreamingPanel.ToolCallStatus = RichStreamingPanel.ToolCallStatus.RUNNING,
        // Resolved per-call timeout (seconds) for tools that expose a configurable
        // wall-clock cap — currently only `run_command`. The webview renders this
        // as the "/ Nm Ss" suffix on the live elapsed-time indicator. Null for
        // tools without a meaningful displayable timeout; the webview suppresses
        // the suffix entirely in that case. Single source of truth lives in
        // Kotlin (`RunCommandTool.resolveTimeoutSeconds`) so the displayed cap
        // matches the actual cap that the in-tool monitor enforces.
        toolTimeoutSeconds: Long? = null
    ) {
        val timeoutArg = toolTimeoutSeconds?.toString() ?: "null"
        callJs("appendToolCall(${JsEscape.toJsString(toolCallId)},${JsEscape.toJsString(toolName)},${JsEscape.toJsString(args)},'${status.name}',$timeoutArg)")
    }

    fun updateLastToolCall(
        status: RichStreamingPanel.ToolCallStatus,
        result: String = "", durationMs: Long = 0,
        toolName: String = "", output: String? = null,
        diff: String? = null,
        toolCallId: String = "",
        // Multimodal-agent Phase 6 — tool-produced image metadata. Serialized as
        // a JSON array (sha256, mime, size, originalFilename) so the webview
        // can render the "N images attached from tool" badge. Empty for the
        // common no-image case; the JS side treats absent/empty identically.
        imageRefs: List<com.workflow.orchestrator.core.services.ToolResult.ImageRefData> = emptyList()
    ) {
        val statusStr = if (status == RichStreamingPanel.ToolCallStatus.FAILED) "ERROR" else "COMPLETED"
        val outputArg = if (output != null) JsEscape.toJsString(output) else "null"
        val diffArg = if (diff != null) JsEscape.toJsString(diff) else "null"
        val imageRefsJson = if (imageRefs.isEmpty()) "null" else JsEscape.toJsString(
            buildJsonArray {
                imageRefs.forEach { ref ->
                    add(buildJsonObject {
                        put("sha256", ref.sha256)
                        put("mime", ref.mime)
                        put("size", ref.size)
                        if (ref.originalFilename != null) put("originalFilename", ref.originalFilename)
                    })
                }
            }.toString()
        )
        callJs("updateToolResult(${JsEscape.toJsString(result)},$durationMs,${JsEscape.toJsString(toolName)},${JsEscape.toJsString(statusStr)},$outputArg,$diffArg,${JsEscape.toJsString(toolCallId)},$imageRefsJson)")
    }

    fun appendToolOutput(toolCallId: String, chunk: String) {
        if (bridgeDispatcher?.isLoaded == false) LOG.warn(
            "appendToolOutput[$toolCallId]: JS bridge not loaded yet — output buffered " +
            "(pendingCallCount=${bridgeDispatcher?.pendingCallCount}). " +
            "If this count keeps growing without markLoaded() firing, the webview load event was missed."
        )
        callJs("appendToolOutput(${JsEscape.toJsString(toolCallId)},${JsEscape.toJsString(chunk)})")
    }

    /**
     * Push a diff explanation to the chat — renders immediately as DiffHtml.
     */
    fun appendDiffExplanation(title: String, diffSource: String) {
        callJs("appendDiffExplanation(${JsEscape.toJsString(title)},${JsEscape.toJsString(diffSource)})")
    }

    fun appendEditDiff(
        filePath: String, oldText: String, newText: String, accepted: Boolean? = null
    ) {
        val oldLines = oldText.lines().take(100).joinToString(",") { JsEscape.toJsString(it) }
        val newLines = newText.lines().take(100).joinToString(",") { JsEscape.toJsString(it) }
        val acceptedJs = when (accepted) { true -> "true"; false -> "false"; null -> "null" }
        callJs("appendDiff(${JsEscape.toJsString(filePath)},[$oldLines],[$newLines],$acceptedJs)")
    }

    fun appendStatus(message: String, type: RichStreamingPanel.StatusType = RichStreamingPanel.StatusType.INFO) {
        callJs("appendStatus(${JsEscape.toJsString(message)},'${type.name}')")
    }

    fun appendError(message: String) = appendStatus(message, RichStreamingPanel.StatusType.ERROR)

    fun appendToThinking(text: String) {
        callJs("appendToThinking(${JsEscape.toJsString(text)})")
    }

    fun endThinking(durationMs: Long = 0L) {
        callJs("endThinking($durationMs)")
    }

    fun clear() {
        callJs("clearChat()")
    }

    fun showToolsPanel(toolsJson: String) {
        callJs("showToolsPanel(${JsEscape.toJsString(toolsJson)})")
    }

    fun hideToolsPanel() {
        callJs("closeToolsPanel()")
    }

    fun renderPlan(planJson: String) {
        callJs("renderPlan(${JsEscape.toJsString(planJson)})")
    }

    fun approvePlanInUi() {
        callJs("approvePlan()")
    }

    fun clearPlanInUi() {
        callJs("clearPlan()")
    }

    // ── Handoff card rendering (new_task confirm flow) ──

    fun renderHandoff(handoffJson: String) {
        callJs("renderHandoff(${JsEscape.toJsString(handoffJson)})")
    }

    fun clearHandoffInUi() {
        callJs("clearHandoff()")
    }

    fun setPlanCommentCount(count: Int) {
        callJs("setPlanCommentCount($count)")
    }

    fun updatePlanSummary(summary: String) {
        callJs("updatePlanSummary(${JsEscape.toJsString(summary)})")
    }

    // ── Question wizard rendering ──

    fun showQuestions(questionsJson: String) {
        callJs("showQuestions(${JsEscape.toJsString(questionsJson)})")
    }

    fun showQuestion(index: Int) {
        callJs("showQuestion($index)")
    }

    fun showQuestionSummary(summaryJson: String) {
        callJs("showQuestionSummary(${JsEscape.toJsString(summaryJson)})")
    }

    fun enableChatInput() {
        callJs("enableChatInput()")
    }

    // ── Toolbar + input bar control ──

    fun setBusy(busy: Boolean) {
        callJs("setBusy(${if (busy) "true" else "false"})")
    }

    /**
     * Manual compaction lifecycle. The webview shows a top banner with a spinner
     * and disables chat input + the compact button while [active] is true.
     * [phase] is a short user-facing label such as "Compacting context..."
     * or "Summarizing earlier conversation...". Pass empty string when [active]
     * is false.
     */
    fun setCompactionState(active: Boolean, phase: String) {
        val payload = buildJsonObject {
            put("active", active)
            put("phase", phase)
        }.toString()
        callJs("setCompactionState(${JsEscape.toJsString(payload)})")
    }

    /**
     * Insert a "context compacted" marker message into the chat scrollback so
     * the user can see the cutoff between pre- and post-compaction history.
     * Does NOT delete or hide messages above the marker — they stay visible;
     * the marker just shows "from here on, the LLM is working from a summary".
     */
    fun insertCompactionMarker(
        tokensBefore: Int,
        tokensAfter: Int,
        messagesBefore: Int,
        messagesAfter: Int,
        ranLlmSummary: Boolean,
    ) {
        val payload = buildJsonObject {
            put("tokensBefore", tokensBefore)
            put("tokensAfter", tokensAfter)
            put("messagesBefore", messagesBefore)
            put("messagesAfter", messagesAfter)
            put("ranLlmSummary", ranLlmSummary)
            put("ts", System.currentTimeMillis())
        }.toString()
        callJs("insertCompactionMarker(${JsEscape.toJsString(payload)})")
    }

    fun setSteeringMode(enabled: Boolean) {
        callJs("setSteeringMode(${if (enabled) "true" else "false"})")
    }

    fun setInputLocked(locked: Boolean) {
        callJs("setInputLocked(${if (locked) "true" else "false"})")
    }

    fun updateTokenBudget(used: Int, max: Int) {
        callJs("updateTokenBudget($used,$max)")
    }

    fun updateMemoryStats(coreChars: Int, archivalCount: Int) {
        callJs("updateMemoryStats($coreChars,$archivalCount)")
    }

    fun setModelName(name: String) {
        callJs("setModelName(${JsEscape.toJsString(name)})")
    }

    /**
     * Indicates whether the active model is the result of an automatic fallback
     * (e.g. network error caused a switch from Opus to Sonnet). The React side
     * renders a subtle amber indicator + icon + tooltip on the model chip when
     * [isFallback] is true. Pass [reason]=null when clearing.
     */
    fun setModelFallbackState(isFallback: Boolean, reason: String?) {
        val reasonJs = if (reason == null) "null" else JsEscape.toJsString(reason)
        callJs("setModelFallbackState(${if (isFallback) "true" else "false"}, $reasonJs)")
    }

    fun setPlanMode(enabled: Boolean) {
        callJs("setPlanMode(${if (enabled) "true" else "false"})")
    }

    // ── Sub-Agent boundary card bridge methods ──

    fun spawnSubAgent(agentId: String, label: String, model: String? = null) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("label", label)
            if (model != null) put("model", model)
        }.toString()
        callJs("spawnSubAgent(${JsEscape.toJsString(payload)})")
    }

    fun updateSubAgentIteration(agentId: String, iteration: Int, tokensUsed: Int = 0) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("iteration", iteration)
            put("tokensUsed", tokensUsed)
        }.toString()
        callJs("updateSubAgentIteration(${JsEscape.toJsString(payload)})")
    }

    /**
     * Nudge the chat-input context-usage bar to re-fetch immediately (it otherwise polls every 1s
     * and pauses when document.hidden). Fired after compaction + session handoff so the bar
     * reflects the new context size at once.
     */
    fun refreshContextUsage() {
        callJs("if (window.dispatchEvent) { window.dispatchEvent(new Event('wf-context-usage-refresh')); }")
    }

    /** Transient status note on the sub-agent card (retry / compaction). null clears it. */
    fun setSubAgentStatusNote(agentId: String, note: String?) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            if (note != null) put("note", note)
        }.toString()
        callJs("setSubAgentStatusNote(${JsEscape.toJsString(payload)})")
    }

    fun addSubAgentToolCall(agentId: String, toolCallId: String, toolName: String, toolArgs: String) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("toolCallId", toolCallId)
            put("toolName", toolName)
            put("toolArgs", toolArgs)
        }.toString()
        callJs("addSubAgentToolCall(${JsEscape.toJsString(payload)})")
    }

    fun updateSubAgentToolCall(
        agentId: String, toolCallId: String, toolName: String, result: String,
        output: String?, diff: String?,
        durationMs: Long, isError: Boolean,
        // Multimodal-agent Phase 6 — tool-produced image metadata, threaded
        // into the JSON payload so the React sub-agent view can render the
        // "N images attached from tool" badge.
        imageRefs: List<com.workflow.orchestrator.core.services.ToolResult.ImageRefData> = emptyList()
    ) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("toolCallId", toolCallId)
            put("toolName", toolName)
            // `result` is the short summary (ToolResult.summary) — 2KB cap is a defensive guard.
            put("toolResult", result.take(2000))
            // `output` is the full ToolResult.content used for the expanded view; no cap so
            // the LLM-relevant detail (e.g. run_command stdout) is not silently dropped.
            // Large payloads are already middle-truncated / spilled by the tool layer.
            if (output != null) put("toolOutput", output)
            if (diff != null) put("toolDiff", diff)
            put("toolDurationMs", durationMs)
            put("isError", isError)
            if (imageRefs.isNotEmpty()) {
                put("imageRefs", buildJsonArray {
                    imageRefs.forEach { ref ->
                        add(buildJsonObject {
                            put("sha256", ref.sha256)
                            put("mime", ref.mime)
                            put("size", ref.size)
                            if (ref.originalFilename != null) put("originalFilename", ref.originalFilename)
                        })
                    }
                })
            }
        }.toString()
        callJs("updateSubAgentToolCall(${JsEscape.toJsString(payload)})")
    }

    fun updateSubAgentMessage(agentId: String, textContent: String) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("textContent", textContent)
        }.toString()
        callJs("updateSubAgentMessage(${JsEscape.toJsString(payload)})")
    }

    fun appendSubAgentStreamDelta(agentId: String, delta: String) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("delta", delta)
        }.toString()
        callJs("appendSubAgentStreamDelta(${JsEscape.toJsString(payload)})")
    }

    /**
     * Append a thinking-block delta to the matching sub-agent's collapsible
     * <ThinkingView>. Mirrors the main agent's appendToThinking path. No-op if
     * the agentId is unknown to the webview.
     */
    fun appendSubAgentThinking(agentId: String, delta: String) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("delta", delta)
        }.toString()
        callJs("if (window._appendSubAgentThinking) window._appendSubAgentThinking(${JsEscape.toJsString(payload)})")
    }

    /**
     * Mark the close of the current <thinking> block for [agentId]. The webview
     * flushes the accumulated buffer into the card's collapsible REASONING bubble.
     */
    fun endSubAgentThinking(agentId: String) {
        val payload = buildJsonObject {
            put("agentId", agentId)
        }.toString()
        callJs("if (window._endSubAgentThinking) window._endSubAgentThinking(${JsEscape.toJsString(payload)})")
    }

    fun completeSubAgent(agentId: String, textContent: String, tokensUsed: Int, isError: Boolean) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("textContent", textContent)
            put("tokensUsed", tokensUsed)
            put("isError", isError)
        }.toString()
        callJs("completeSubAgent(${JsEscape.toJsString(payload)})")
    }

    fun renderArtifact(title: String, source: String, renderId: String) {
        val payload = buildJsonObject {
            put("title", title)
            put("source", source)
            put("renderId", renderId)
        }.toString()
        callJs("renderArtifact(${JsEscape.toJsString(payload)})")
    }

    fun updateModelList(modelsJson: String) {
        callJs("updateModelList(${JsEscape.toJsString(modelsJson)})")
    }

    fun updateSkillsList(skillsJson: String) {
        callJs("updateSkillsList(${JsEscape.toJsString(skillsJson)})")
    }

    fun showRetryButton(kind: String, caption: String) {
        callJs("showRetryButton(${JsEscape.toJsString(kind)}, ${JsEscape.toJsString(caption)})")
    }

    fun focusInput() {
        callJs("focusInput()")
    }

    // ── Skill banner rendering ──

    fun showSkillBanner(name: String) {
        callJs("showSkillBanner(${JsEscape.toJsString(name)})")
    }

    fun hideSkillBanner() {
        callJs("hideSkillBanner()")
    }

    // ── Chart.js support ──

    fun appendChart(chartConfigJson: String) {
        callJs("appendChart(${JsEscape.toJsString(chartConfigJson)})")
    }

    // ── ANSI, Skeleton, Toast, Table support ──

    fun appendAnsiOutput(text: String) {
        callJs("appendAnsiOutput(${JsEscape.toJsString(text)})")
    }

    fun showSkeleton() {
        callJs("showSkeleton()")
    }

    fun hideSkeleton() {
        callJs("hideSkeleton()")
    }

    fun showToast(message: String, type: String = "info", durationMs: Int = 3000) {
        callJs("showToast(${JsEscape.toJsString(message)},${JsEscape.toJsString(type)},$durationMs)")
    }

    // ── Tabbed content, timeline, progress bar ──

    fun appendTabs(tabsJson: String) {
        callJs("appendTabs(${JsEscape.toJsString(tabsJson)})")
    }

    fun appendTimeline(itemsJson: String) {
        callJs("appendTimeline(${JsEscape.toJsString(itemsJson)})")
    }

    fun appendProgressBar(percent: Int, type: String = "info") {
        callJs("appendProgressBar($percent,${JsEscape.toJsString(type)})")
    }

    // ── Jira card & Sonar badge ──

    fun appendJiraCard(cardJson: String) {
        callJs("appendJiraCard(${JsEscape.toJsString(cardJson)})")
    }

    fun appendSonarBadge(badgeJson: String) {
        callJs("appendSonarBadge(${JsEscape.toJsString(badgeJson)})")
    }

    // ── Tool call approval rendering ──

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
        val diffArg = if (diffContent != null) JsEscape.toJsString(diffContent) else "null"
        val previewArg = if (commandPreviewJson != null) JsEscape.toJsString(commandPreviewJson) else "null"
        val originAgentIdArg = if (originAgentId != null) JsEscape.toJsString(originAgentId) else "null"
        val originLabelArg = if (originLabel != null) JsEscape.toJsString(originLabel) else "null"
        val pathArg = if (path != null) JsEscape.toJsString(path) else "null"
        callJs(
            "showApproval(" +
                "${JsEscape.toJsString(toolName)}," +
                "${JsEscape.toJsString(riskLevel)}," +
                "${JsEscape.toJsString(description)}," +
                "${JsEscape.toJsString(metadataJson)}," +
                "$diffArg," +
                "$previewArg," +
                "$allowSessionApproval," +
                "$originAgentIdArg," +
                "$originLabelArg," +
                "$pathArg" +
                ")"
        )
    }

    fun showProcessInput(processId: String, description: String, prompt: String, command: String) {
        callJs("showProcessInput(${JsEscape.toJsString(processId)},${JsEscape.toJsString(description)},${JsEscape.toJsString(prompt)},${JsEscape.toJsString(command)})")
    }

    // ── Debug log panel ──

    /**
     * Push the current showDebugLog setting to the webview.
     * Called on panel initialization and whenever the setting changes.
     */
    fun updateDebugLogVisibility(show: Boolean) {
        callJs("setDebugLogVisible($show)")
    }

    /**
     * Serialize and push a debug log entry to the JCEF debug log panel.
     *
     * @param level  Severity: "info", "warn", or "error"
     * @param event  Short event tag, e.g. "tool_call", "iteration", "retry", "compression", "error"
     * @param detail Human-readable description (truncated to 300 chars)
     * @param meta   Optional key-value metadata (numbers and booleans emitted as JSON literals)
     */
    fun pushDebugLogEntry(level: String, event: String, detail: String, meta: Map<String, Any?>? = null) {
        val ts = System.currentTimeMillis()
        val truncatedDetail = detail.take(300)
        val metaStr = if (meta != null) {
            val pairs = meta.entries.mapNotNull { (k, v) ->
                when (v) {
                    null -> null
                    is Number -> "\"$k\":$v"
                    is Boolean -> "\"$k\":$v"
                    else -> "\"$k\":${JsEscape.toJsonString(v.toString().take(100))}"
                }
            }.joinToString(",")
            "{$pairs}"
        } else "null"
        val json = """{"ts":$ts,"level":"$level","event":"$event","detail":"$truncatedDetail","meta":$metaStr}"""
        callJs("addDebugLogEntry(${JsEscape.toJsString(json)})")
    }

    // ── Edit stats ──

    fun updateEditStats(added: Int, removed: Int, files: Int) {
        callJs("updateEditStats($added,$removed,$files)")
    }

    fun updateAggregateDiff(json: String) {
        callJs("updateAggregateDiff(${JsEscape.toJsString(json)})")
    }

    fun setSmartWorkingPhrase(phrase: String) {
        callJs("setSmartWorkingPhrase(${JsEscape.toJsString(phrase)})")
    }

    fun setSessionTitle(title: String) {
        callJs("setSessionTitle(${JsEscape.toJsString(title)})")
    }

    fun setSessionTitleAnimated(title: String) {
        callJs("setSessionTitleAnimated(${JsEscape.toJsString(title)})")
    }

    // ── Queued steering message rendering ──

    fun addQueuedSteeringMessage(id: String, text: String) {
        callJs("addQueuedSteeringMessage(${JsEscape.toJsString(id)},${JsEscape.toJsString(text)})")
    }

    fun removeQueuedSteeringMessage(id: String) {
        callJs("removeQueuedSteeringMessage(${JsEscape.toJsString(id)})")
    }

    fun promoteQueuedSteeringMessages(ids: List<String>) {
        val idsJson = ids.joinToString(",") { JsEscape.toJsString(it) }
        callJs("promoteQueuedSteeringMessages([$idsJson])")
    }

    fun restoreInputText(text: String) {
        callJs("restoreInputText(${JsEscape.toJsString(text)})")
    }

    /**
     * Push full session UI state to the webview for rehydration on resume.
     * Calls the `_loadSessionState` bridge function registered in jcef-bridge.ts.
     */
    fun loadSessionState(uiMessagesJson: String) {
        callJs("_loadSessionState(${JsEscape.toJsString(uiMessagesJson)})")
    }

    /**
     * Push the full task list to the webview, replacing the current chatStore.tasks.
     * Calls the `_setTasks` bridge function registered in jcef-bridge.ts.
     *
     * Used on session load / rehydration so [PlanProgressWidget] shows tasks
     * from the persisted TaskStore.
     */
    fun setTasks(tasksJson: String) {
        callJs("_setTasks(${JsEscape.toJsString(tasksJson)})")
    }

    /**
     * Push a single created task to the webview; the React chatStore appends it
     * to `tasks`. Calls the `_applyTaskCreate` bridge function in jcef-bridge.ts.
     */
    fun applyTaskCreate(taskJson: String) {
        LOG.info("[Tasks] applyTaskCreate dispatch (${taskJson.length} chars, dispatcher loaded=${bridgeDispatcher?.isLoaded == true}, pending=${bridgeDispatcher?.pendingCallCount ?: -1})")
        callJs("_applyTaskCreate(${JsEscape.toJsString(taskJson)})")
    }

    /**
     * Push a single updated task to the webview; the React chatStore replaces
     * the matching task by id. Calls the `_applyTaskUpdate` bridge function.
     */
    fun applyTaskUpdate(taskJson: String) {
        LOG.info("[Tasks] applyTaskUpdate dispatch (${taskJson.length} chars, dispatcher loaded=${bridgeDispatcher?.isLoaded == true}, pending=${bridgeDispatcher?.pendingCallCount ?: -1})")
        callJs("_applyTaskUpdate(${JsEscape.toJsString(taskJson)})")
    }

    /**
     * Push a full background-process snapshot to the webview.
     * Calls `window.__receiveBackgroundUpdate(snapshotJson)` which the top-bar
     * indicator registers in jcef-bridge.ts.
     */
    fun receiveBackgroundUpdate(snapshotJson: String) {
        callJs("window.__receiveBackgroundUpdate && window.__receiveBackgroundUpdate(${JsEscape.toJsString(snapshotJson)})")
    }

    /**
     * Push a full monitor snapshot to the webview.
     * Calls `window.__receiveMonitorUpdate(snapshotJson)` which the top-bar
     * monitor indicator registers in jcef-bridge.ts.
     */
    fun receiveMonitorUpdate(snapshotJson: String) {
        callJs("window.__receiveMonitorUpdate && window.__receiveMonitorUpdate(${JsEscape.toJsString(snapshotJson)})")
    }

    fun loadSessionHistory(historyItemsJson: String) {
        callJs("_loadSessionHistory(${JsEscape.toJsString(historyItemsJson)})")
    }

    fun showHistoryView() {
        callJs("_showHistoryView()")
    }

    fun showChatView() {
        callJs("_showChatView()")
    }

    fun showResumeBar(sessionId: String) {
        callJs("showResumeBar(${JsEscape.toJsString(sessionId)})")
    }

    fun hideResumeBar() {
        callJs("hideResumeBar()")
    }

    fun updateSessionStats(modelId: String?, tokensIn: Long, tokensOut: Long, costUsd: Double?) {
        val normalizedModel = modelId?.let { ModelIdNormalizer.normalize(it) }
        val json = buildJsonObject {
            if (normalizedModel != null) put("modelId", normalizedModel) else put("modelId", JsonNull)
            put("tokensIn", tokensIn)
            put("tokensOut", tokensOut)
            if (costUsd != null) put("estimatedCostUsd", costUsd) else put("estimatedCostUsd", JsonNull)
        }.toString()
        callJs("window._receiveSessionStats && window._receiveSessionStats(${JsEscape.toJsString(json)})")
    }

    // ═══════════════════════════════════════════════════
    //  Theme
    // ═══════════════════════════════════════════════════

    private fun applyCurrentTheme() {
        val isDark = UIUtil.isUnderDarcula()
        val c = AgentColors

        val vars = if (isDark) mapOf(
            "bg" to c.hex(c.panelBg), "fg" to c.hex(c.primaryText),
            "fg-secondary" to c.hex(c.secondaryText), "fg-muted" to c.hex(c.mutedText),
            "border" to c.hex(c.border), "user-bg" to c.hex(c.userMsgBg),
            "tool-bg" to c.hex(c.toolCallBg), "code-bg" to c.hex(c.codeBg),
            "thinking-bg" to c.hex(c.thinkingBg),
            "badge-read-bg" to c.hex(c.badgeRead), "badge-read-fg" to c.hex(c.badgeReadText),
            "badge-write-bg" to c.hex(c.badgeWrite), "badge-write-fg" to c.hex(c.badgeWriteText),
            "badge-edit-bg" to c.hex(c.badgeEdit), "badge-edit-fg" to c.hex(c.badgeEditText),
            "badge-cmd-bg" to c.hex(c.badgeCmd), "badge-cmd-fg" to c.hex(c.badgeCmdText),
            "badge-search-bg" to c.hex(c.badgeSearch), "badge-search-fg" to c.hex(c.badgeSearchText),
            "accent-read" to c.hex(c.accentRead), "accent-write" to c.hex(c.accentWrite),
            "accent-edit" to c.hex(c.accentEdit), "accent-cmd" to c.hex(c.accentCmd),
            "accent-search" to c.hex(c.accentSearch),
            "diff-add-bg" to c.hex(c.diffAddBg), "diff-add-fg" to c.hex(c.diffAddText),
            "diff-rem-bg" to c.hex(c.diffRemBg), "diff-rem-fg" to c.hex(c.diffRemText),
            "success" to c.hex(c.success), "error" to c.hex(c.error),
            "warning" to c.hex(c.warning), "link" to c.hex(c.linkText),
            "hover-overlay" to "rgba(255,255,255,0.04)",
            "hover-overlay-strong" to "rgba(255,255,255,0.07)",
            "divider-subtle" to "rgba(255,255,255,0.05)",
            "row-alt" to "rgba(255,255,255,0.02)",
            "input-bg" to "#3c3c3c",
            "input-border" to "rgba(255,255,255,0.08)",
            "toolbar-bg" to "#252526",
            "chip-bg" to "rgba(255,255,255,0.04)",
            "chip-border" to "rgba(255,255,255,0.08)"
        ) else mapOf(
            "bg" to "#FFFFFF", "fg" to "#1E1E1E",
            "fg-secondary" to "#616161", "fg-muted" to "#9E9E9E",
            "border" to "#E0E0E0", "user-bg" to "#F3F3F3",
            "tool-bg" to "#F8F8F8", "code-bg" to "#F3F3F3",
            "thinking-bg" to "#F8F8F8",
            "badge-read-bg" to "#D6ECFF", "badge-read-fg" to "#0451A5",
            "badge-write-bg" to "#D4EDDA", "badge-write-fg" to "#1B7742",
            "badge-edit-bg" to "#FFF3CD", "badge-edit-fg" to "#795E00",
            "badge-cmd-bg" to "#FDE2E2", "badge-cmd-fg" to "#CD3131",
            "badge-search-bg" to "#D4F4F4", "badge-search-fg" to "#16825D",
            "accent-read" to "#0451A5", "accent-write" to "#1B7742",
            "accent-edit" to "#795E00", "accent-cmd" to "#CD3131",
            "accent-search" to "#16825D",
            "diff-add-bg" to "#D4EDDA", "diff-add-fg" to "#1B7742",
            "diff-rem-bg" to "#FDE2E2", "diff-rem-fg" to "#CD3131",
            "success" to "#1B7742", "error" to "#CD3131",
            "warning" to "#795E00", "link" to "#0451A5",
            "hover-overlay" to "rgba(0,0,0,0.04)",
            "hover-overlay-strong" to "rgba(0,0,0,0.07)",
            "divider-subtle" to "rgba(0,0,0,0.05)",
            "row-alt" to "rgba(0,0,0,0.02)",
            "input-bg" to "#ffffff",
            "input-border" to "#e0e0e0",
            "toolbar-bg" to "#f3f3f3",
            "chip-bg" to "rgba(0,0,0,0.04)",
            "chip-border" to "#e0e0e0"
        )

        val jsObj = vars.entries.joinToString(",") { "'${it.key}':'${it.value}'" }
        js("applyTheme({$jsObj})")
        val isDarkJs = if (isDark) "true" else "false"
        js("setPrismTheme($isDarkJs)")
        js("setMermaidTheme($isDarkJs)")
    }

    // ═══════════════════════════════════════════════════
    //  Internal
    // ═══════════════════════════════════════════════════

    /** Execute JavaScript in the browser, queuing if page hasn't loaded yet. */
    internal fun callJs(code: String) {
        bridgeDispatcher?.dispatch(code)
    }

    /**
     * Multimodal-agent Phase 7 followup F-P5-2 / F-P6-1 — push the current
     * image settings JSON to the React webview. Called by [AgentController]
     * after `MultimodalSettingsConfigurable.apply()` fires; the React
     * `<InputBar>`'s `AttachmentManager` rebinds against the new payload, so
     * the user's setting changes take effect without a plugin restart.
     */
    fun pushImageSettings() {
        val payload = imageSettingsProvider?.invoke() ?: return
        // The webview side defines `window.__applyImageSettings(json)`; the
        // function rebinds the AttachmentManager singleton. Safe to call before
        // page-load — `callJs` queues until the dispatcher marks loaded.
        // Use the hardened JsEscape helper which handles backslash, single/double
        // quotes, newlines, CR, tab, and U+2028/U+2029 (audit finding agent-ui:F-3).
        val jsLiteral = JsEscape.toJsString(payload)   // returns 'escaped-content'
        callJs("if (window.__applyImageSettings) { window.__applyImageSettings($jsLiteral); }")
    }

    /**
     * P2-18: deliver the asynchronous answer for an `_attachmentExists` pre-flight query.
     * The bridge handler ACKs immediately and runs the directory scan off-thread; this
     * callback resolves the webview's pending Promise (see the `_attachmentExists`
     * injection — `window.__resolveAttachmentExists` consumes the waiter list for [sha256]).
     * Safe from any thread: [callJs] routes through the bridge dispatcher.
     */
    private fun resolveAttachmentExists(sha256: String, exists: Boolean) {
        callJs(
            "if (window.__resolveAttachmentExists) { " +
                "window.__resolveAttachmentExists(${JsEscape.toJsString(sha256)}, $exists); }"
        )
    }

    /**
     * Push one attachment chip's metadata to the webview. The JSON is wrapped as a
     * single-quoted JS string literal and parsed in the receiver — mirror the existing
     * `pushImageSettings()` escaping (uses `JsEscape.toJsString`) rather than embedding raw
     * JSON into the expression, which breaks on filenames containing quotes (review fix S7).
     */
    fun pushAttachmentChip(json: String) {
        val jsLiteral = JsEscape.toJsString(json)   // same helper as pushImageSettings
        callJs("if (window._addAttachmentChip) { window._addAttachmentChip(JSON.parse($jsLiteral)); }")
    }

    /**
     * Execute JavaScript directly on the browser, bypassing the dispatcher.
     * Used by [applyCurrentTheme] and bridge injection inside the load handler
     * (which runs before markLoaded, so the dispatcher would buffer them).
     */
    private fun js(code: String) {
        val b = browser ?: return
        try {
            b.cefBrowser.executeJavaScript(code, CefResourceSchemeHandler.BASE_URL, 0)
        } catch (e: Exception) {
            LOG.warn("AgentCefPanel: JS execution failed for ${code.take(60)}: ${e.message}")
        }
    }

    /**
     * Create a [JBCefJSQuery] bound to [b], install [handler], and track the
     * resulting query in [registeredQueries] so it gets disposed in [dispose].
     *
     * Always go through this helper instead of calling
     * `JBCefJSQuery.create(...)` directly — that is how we avoid leaking native
     * resources when new bridges are added.
     */
    private fun registerQuery(
        b: JBCefBrowser,
        handler: (String) -> JBCefJSQuery.Response?
    ): JBCefJSQuery {
        val q = JBCefJSQuery.create(b as JBCefBrowserBase)
        q.addHandler(handler)
        registeredQueries.add(q)
        return q
    }

    override fun dispose() {
        scope.cancel()
        bridgeDispatcher?.dispose()
        // Tear down every JBCefJSQuery created via registerQuery in one pass.
        // This automatically covers any new bridges added later — no risk of
        // forgetting one in dispose().
        registeredQueries.forEach {
            try {
                it.dispose()
            } catch (e: Exception) {
                LOG.debug("AgentCefPanel: query dispose failed: ${e.message}")
            }
        }
        registeredQueries.clear()
        browser?.dispose()
        browser = null
    }
}
