package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefClient
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import com.workflow.orchestrator.agent.util.JsEscape
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

        /** Check if JCEF is available in this IDE installation. */
        fun isAvailable(): Boolean = try { JBCefApp.isSupported() } catch (_: Exception) { false }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var browser: JBCefBrowser? = null

    /**
     * Tracks every [JBCefJSQuery] created via [registerQuery] so [dispose] can
     * tear them all down in one pass. This avoids the previous bug where new
     * queries would be added to [createBrowser] but forgotten in [dispose],
     * leaking native resources (e.g. `toggleRalphLoopQuery`).
     */
    private val registeredQueries = mutableListOf<JBCefJSQuery>()

    private var undoQuery: JBCefJSQuery? = null
    private var traceQuery: JBCefJSQuery? = null
    private var promptQuery: JBCefJSQuery? = null
    private var planApproveQuery: JBCefJSQuery? = null
    private var planReviseQuery: JBCefJSQuery? = null
    private var toolToggleQuery: JBCefJSQuery? = null
    private var questionAnsweredQuery: JBCefJSQuery? = null
    private var questionSkippedQuery: JBCefJSQuery? = null
    private var chatAboutQuery: JBCefJSQuery? = null
    private var questionsSubmittedQuery: JBCefJSQuery? = null
    private var questionsCancelledQuery: JBCefJSQuery? = null
    private var editQuestionQuery: JBCefJSQuery? = null
    private var deactivateSkillQuery: JBCefJSQuery? = null
    private var navigateToFileQuery: JBCefJSQuery? = null
    private var cancelTaskQuery: JBCefJSQuery? = null
    private var newChatQuery: JBCefJSQuery? = null
    private var sendMessageQuery: JBCefJSQuery? = null
    private var changeModelQuery: JBCefJSQuery? = null
    private var togglePlanModeQuery: JBCefJSQuery? = null
    private var toggleRalphLoopQuery: JBCefJSQuery? = null
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
    private var revertCheckpointQuery: JBCefJSQuery? = null
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
    var mentionSearchProvider: MentionSearchProvider? = null
    var onSendMessageWithMentions: ((String, String) -> Unit)? = null  // (text, mentionsJson)

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

    var onCancelTask: (() -> Unit)? = null
    var onRetryLastTask: (() -> Unit)? = null
    var onNewChat: (() -> Unit)? = null
    var onSendMessage: ((String) -> Unit)? = null
    var onChangeModel: ((String) -> Unit)? = null
    var onTogglePlanMode: ((Boolean) -> Unit)? = null
    var onToggleRalphLoop: ((Boolean) -> Unit)? = null
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

    /** Callback when user clicks "Revise" on the chat plan card. Triggers revise on the plan editor tab. */
    var onRevisePlanFromEditor: (() -> Unit)? = null

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
    /** Callback when user submits process input from the ProcessInputView. Param: input string. */
    var onProcessInputResolved: ((String) -> Unit)? = null
    /** Callback when user clicks "Revert" on a checkpoint in the timeline. Param: checkpointId. */
    var onRevertCheckpoint: ((String) -> Unit)? = null
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

        // Register scheme handler factory for serving resources from JAR.
        // NOTE: loadURL is called AFTER all query registration + load handler setup
        // (see end of this method) to avoid a race where the page finishes loading
        // before onLoadingStateChange is registered, leaving pageLoaded=false forever.
        try {
            val factory = org.cef.callback.CefSchemeHandlerFactory { _, _, _, _ ->
                CefResourceSchemeHandler()
            }
            org.cef.CefApp.getInstance().registerSchemeHandlerFactory(
                CefResourceSchemeHandler.SCHEME,
                CefResourceSchemeHandler.AUTHORITY,
                factory
            )
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

        // Toolbar + input bar bridges
        cancelTaskQuery = registerQuery(b) { _ -> onCancelTask?.invoke(); JBCefJSQuery.Response("ok") }
        newChatQuery = registerQuery(b) { _ -> onNewChat?.invoke(); JBCefJSQuery.Response("ok") }
        sendMessageQuery = registerQuery(b) { text -> onSendMessage?.invoke(text); JBCefJSQuery.Response("ok") }
        changeModelQuery = registerQuery(b) { modelId -> onChangeModel?.invoke(modelId); JBCefJSQuery.Response("ok") }
        togglePlanModeQuery = registerQuery(b) { enabled -> onTogglePlanMode?.invoke(enabled == "true"); JBCefJSQuery.Response("ok") }
        toggleRalphLoopQuery = registerQuery(b) { enabled -> onToggleRalphLoop?.invoke(enabled == "true"); JBCefJSQuery.Response("ok") }
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
            // Search on IO thread, callback to JS
            scope.launch {
                try {
                    withTimeout(15_000L) {
                        val results = mentionSearchProvider?.search(type, query) ?: "[]"
                        callJs("receiveMentionResults(${JsEscape.toJsString(results)})")
                    }
                } catch (e: Exception) {
                    LOG.warn("searchMentions handler failed: ${e.message}")
                    callJs("receiveMentionResults('[]')")
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
                val handler = onSendMessageWithMentions
                if (handler != null) {
                    handler.invoke(text, mentionsJson)
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
        revisePlanFromEditorQuery = registerQuery(b) { _ -> onRevisePlanFromEditor?.invoke(); JBCefJSQuery.Response("ok") }
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
        revertCheckpointQuery = registerQuery(b) { checkpointId -> onRevertCheckpoint?.invoke(checkpointId); JBCefJSQuery.Response("ok") }
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
                    injectBridge("_toggleTool") { toolToggleQuery?.let { q -> js("window._toggleTool = function(data) { ${q.inject("data")} }") } }
                    injectBridge("_questionAnswered") { questionAnsweredQuery?.let { q -> js("window._questionAnswered = function(qid, opts) { ${q.inject("qid + ':' + opts")} }") } }
                    injectBridge("_questionSkipped") { questionSkippedQuery?.let { q -> js("window._questionSkipped = function(qid) { ${q.inject("qid")} }") } }
                    injectBridge("_chatAboutOption") { chatAboutQuery?.let { q -> js("window._chatAboutOption = function(qid, label, msg) { ${q.inject("qid + '\\x1F' + label + '\\x1F' + msg")} }") } }
                    injectBridge("_questionsSubmitted") { questionsSubmittedQuery?.let { q -> js("window._questionsSubmitted = function() { ${q.inject("'submit'")} }") } }
                    injectBridge("_questionsCancelled") { questionsCancelledQuery?.let { q -> js("window._questionsCancelled = function() { ${q.inject("'cancel'")} }") } }
                    injectBridge("_editQuestion") { editQuestionQuery?.let { q -> js("window._editQuestion = function(qid) { ${q.inject("qid")} }") } }
                    injectBridge("_deactivateSkill") { deactivateSkillQuery?.let { q -> js("window._deactivateSkill = function() { ${q.inject("'dismiss'")} }") } }
                    injectBridge("_navigateToFile") { navigateToFileQuery?.let { q -> js("window._navigateToFile = function(path) { ${q.inject("path")} }") } }
                    injectBridge("_cancelTask") { cancelTaskQuery?.let { q -> js("window._cancelTask = function() { ${q.inject("'cancel'")} }") } }
                    injectBridge("_newChat") { newChatQuery?.let { q -> js("window._newChat = function() { ${q.inject("'new'")} }") } }
                    injectBridge("_sendMessage") { sendMessageQuery?.let { q -> js("window._sendMessage = function(text) { ${q.inject("text")} }") } }
                    injectBridge("_changeModel") { changeModelQuery?.let { q -> js("window._changeModel = function(modelId) { ${q.inject("modelId")} }") } }
                    injectBridge("_togglePlanMode") { togglePlanModeQuery?.let { q -> js("window._togglePlanMode = function(enabled) { ${q.inject("String(enabled)")} }") } }
                    injectBridge("_toggleRalphLoop") { toggleRalphLoopQuery?.let { q -> js("window._toggleRalphLoop = function(enabled) { ${q.inject("String(enabled)")} }") } }
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
                    injectBridge("_revisePlanFromEditor") { revisePlanFromEditorQuery?.let { q -> js("window._revisePlanFromEditor = function() { ${q.inject("''")} }") } }
                    injectBridge("_viewInEditor") { viewInEditorQuery?.let { q -> js("window._viewInEditor = function() { ${q.inject("''")} }") } }
                    injectBridge("_approveToolCall") { approveToolCallQuery?.let { q -> js("window._approveToolCall = function() { ${q.inject("'approve'")} }") } }
                    injectBridge("_denyToolCall") { denyToolCallQuery?.let { q -> js("window._denyToolCall = function() { ${q.inject("'deny'")} }") } }
                    injectBridge("_allowToolForSession") { allowToolForSessionQuery?.let { q -> js("window._allowToolForSession = function(toolName) { ${q.inject("toolName")} }") } }
                    injectBridge("_interactiveHtmlMessage") { interactiveHtmlMessageQuery?.let { q -> js("window._interactiveHtmlMessage = function(json) { ${q.inject("json")} }") } }
                    injectBridge("_acceptDiffHunk") { acceptDiffHunkQuery?.let { q -> js("window._acceptDiffHunk = function(fp,hi,ec) { ${q.inject("JSON.stringify({filePath:fp,hunkIndex:hi,editedContent:ec||null})")} }") } }
                    injectBridge("_rejectDiffHunk") { rejectDiffHunkQuery?.let { q -> js("window._rejectDiffHunk = function(fp,hi) { ${q.inject("JSON.stringify({filePath:fp,hunkIndex:hi})")} }") } }
                    injectBridge("_killToolCall") { killToolCallQuery?.let { q -> js("window._killToolCall = function(toolCallId) { ${q.inject("toolCallId")} }") } }
                    injectBridge("_killSubAgent") { killSubAgentQuery?.let { q -> js("window._killSubAgent = function(agentId) { ${q.inject("agentId")} }") } }
                    injectBridge("_resolveProcessInput") { processInputQuery?.let { q -> js("window._resolveProcessInput = function(input) { ${q.inject("input")} }") } }
                    injectBridge("_revertCheckpoint") { revertCheckpointQuery?.let { q -> js("window._revertCheckpoint = function(id) { ${q.inject("id")} }") } }
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
            java.util.Timer("cef-page-load-watchdog", true).schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (!dispatcher.isLoaded && !dispatcher.isDisposed) {
                        LOG.warn("AgentCefPanel: page not loaded after 15s — ${dispatcher.pendingCallCount} calls still buffered (will flush when page loads)")
                    }
                }
            }, 15_000L)
        }
    }

    // ═══════════════════════════════════════════════════
    //  Public API — mirrors RichStreamingPanel
    // ═══════════════════════════════════════════════════

    fun startSession(task: String) {
        callJs("startSession(${JsEscape.toJsString(task)})")
    }

    fun startSessionWithMentions(task: String, mentionsJson: String) {
        callJs("startSessionWithMentions(${JsEscape.toJsString(task)}, ${JsEscape.toJsString(mentionsJson)})")
    }

    fun appendUserMessage(text: String) {
        callJs("appendUserMessage(${JsEscape.toJsString(text)})")
    }

    fun appendUserMessageWithMentions(text: String, mentionsJson: String) {
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

    fun appendCompletionSummary(result: String, verifyCommand: String? = null) {
        val cmdArg = if (verifyCommand != null) JsEscape.toJsString(verifyCommand) else "undefined"
        callJs("appendCompletionSummary(${JsEscape.toJsString(result)},$cmdArg)")
    }

    fun appendToolCall(
        toolCallId: String = "",
        toolName: String, args: String = "",
        status: RichStreamingPanel.ToolCallStatus = RichStreamingPanel.ToolCallStatus.RUNNING
    ) {
        callJs("appendToolCall(${JsEscape.toJsString(toolCallId)},${JsEscape.toJsString(toolName)},${JsEscape.toJsString(args)},'${status.name}')")
    }

    fun updateLastToolCall(
        status: RichStreamingPanel.ToolCallStatus,
        result: String = "", durationMs: Long = 0,
        toolName: String = "", output: String? = null,
        diff: String? = null,
        toolCallId: String = ""
    ) {
        val statusStr = if (status == RichStreamingPanel.ToolCallStatus.FAILED) "ERROR" else "COMPLETED"
        val outputArg = if (output != null) JsEscape.toJsString(output) else "null"
        val diffArg = if (diff != null) JsEscape.toJsString(diff) else "null"
        callJs("updateToolResult(${JsEscape.toJsString(result)},$durationMs,${JsEscape.toJsString(toolName)},${JsEscape.toJsString(statusStr)},$outputArg,$diffArg,${JsEscape.toJsString(toolCallId)})")
    }

    fun appendToolOutput(toolCallId: String, chunk: String) {
        callJs("appendToolOutput(${JsEscape.toJsString(toolCallId)},${JsEscape.toJsString(chunk)})")
    }

    /**
     * Push a diff explanation to the chat — renders immediately as DiffHtml.
     * Used by generate_explanation tool to show the diff without waiting for LLM response.
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

    fun appendThinking(text: String) {
        callJs("appendThinking(${JsEscape.toJsString(text)})")
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

    fun updatePlanStep(stepId: String, status: String) {
        callJs("updatePlanStep(${JsEscape.toJsString(stepId)}, ${JsEscape.toJsString(status)})")
    }

    fun replaceExecutionSteps(stepsJson: String) {
        callJs("replaceExecutionSteps(${JsEscape.toJsString(stepsJson)})")
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

    fun setRalphLoop(enabled: Boolean) {
        callJs("setRalphLoop(${if (enabled) "true" else "false"})")
    }

    // ── Sub-Agent boundary card bridge methods ──

    fun spawnSubAgent(agentId: String, label: String) {
        val payload = buildJsonObject { put("agentId", agentId); put("label", label) }.toString()
        callJs("spawnSubAgent(${JsEscape.toJsString(payload)})")
    }

    fun updateSubAgentIteration(agentId: String, iteration: Int) {
        val payload = buildJsonObject { put("agentId", agentId); put("iteration", iteration) }.toString()
        callJs("updateSubAgentIteration(${JsEscape.toJsString(payload)})")
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
        durationMs: Long, isError: Boolean
    ) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("toolCallId", toolCallId)
            put("toolName", toolName)
            put("toolResult", result.take(2000))   // guard against huge results in the payload
            put("toolDurationMs", durationMs)
            put("isError", isError)
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

    fun showRetryButton(lastMessage: String) {
        callJs("showRetryButton(${JsEscape.toJsString(lastMessage)})")
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

    fun showApproval(toolName: String, riskLevel: String, description: String, metadataJson: String, diffContent: String? = null) {
        val diffArg = if (diffContent != null) JsEscape.toJsString(diffContent) else "null"
        callJs("showApproval(${JsEscape.toJsString(toolName)},${JsEscape.toJsString(riskLevel)},${JsEscape.toJsString(description)},${JsEscape.toJsString(metadataJson)},$diffArg)")
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

    // ── Edit stats + checkpoints ──

    fun updateEditStats(added: Int, removed: Int, files: Int) {
        callJs("updateEditStats($added,$removed,$files)")
    }

    fun updateCheckpoints(checkpointsJson: String) {
        callJs("updateCheckpoints(${JsEscape.toJsString(checkpointsJson)})")
    }

    fun notifyRollback(rollbackJson: String) {
        callJs("notifyRollback(${JsEscape.toJsString(rollbackJson)})")
    }

    fun setSmartWorkingPhrase(phrase: String) {
        callJs("setSmartWorkingPhrase(${JsEscape.toJsString(phrase)})")
    }

    fun setSessionTitle(title: String) {
        callJs("setSessionTitle(${JsEscape.toJsString(title)})")
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
    private fun callJs(code: String) {
        bridgeDispatcher?.dispatch(code)
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
        // forgetting one in dispose() (the previous toggleRalphLoopQuery leak).
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
