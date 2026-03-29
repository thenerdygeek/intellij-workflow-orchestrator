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
    private var activateSkillQuery: JBCefJSQuery? = null
    private var requestFocusIdeQuery: JBCefJSQuery? = null
    private var openSettingsQuery: JBCefJSQuery? = null
    private var openToolsPanelQuery: JBCefJSQuery? = null
    private var searchMentionsQuery: JBCefJSQuery? = null
    private var searchTicketsQuery: JBCefJSQuery? = null
    private var validateTicketQuery: JBCefJSQuery? = null
    private var sendMessageWithMentionsQuery: JBCefJSQuery? = null
    private var openInEditorTabQuery: JBCefJSQuery? = null
    private var approveToolCallQuery: JBCefJSQuery? = null
    private var denyToolCallQuery: JBCefJSQuery? = null
    private var allowToolForSessionQuery: JBCefJSQuery? = null
    private var interactiveHtmlMessageQuery: JBCefJSQuery? = null
    private var acceptDiffHunkQuery: JBCefJSQuery? = null
    private var rejectDiffHunkQuery: JBCefJSQuery? = null
    private var killToolCallQuery: JBCefJSQuery? = null
    private var killSubAgentQuery: JBCefJSQuery? = null
    private var processInputQuery: JBCefJSQuery? = null
    var mentionSearchProvider: MentionSearchProvider? = null
    var onSendMessageWithMentions: ((String, String) -> Unit)? = null  // (text, mentionsJson)
    @Volatile private var pageLoaded = false
    private val pendingCalls = mutableListOf<String>()

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
    var onNewChat: (() -> Unit)? = null
    var onSendMessage: ((String) -> Unit)? = null
    var onChangeModel: ((String) -> Unit)? = null
    var onTogglePlanMode: ((Boolean) -> Unit)? = null
    var onActivateSkill: ((String) -> Unit)? = null
    var onRequestFocusIde: (() -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null
    var onOpenToolsPanel: (() -> Unit)? = null
    /** Callback when user clicks "Open in Tab" on a visualization. Params: type, content JSON payload. */
    var onOpenInEditorTab: ((String) -> Unit)? = null

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

        // Register scheme handler factory for serving resources from JAR
        try {
            val factory = org.cef.callback.CefSchemeHandlerFactory { _, _, _, _ ->
                CefResourceSchemeHandler()
            }
            org.cef.CefApp.getInstance().registerSchemeHandlerFactory(
                CefResourceSchemeHandler.SCHEME,
                CefResourceSchemeHandler.AUTHORITY,
                factory
            )
            // Load via scheme URL — all relative paths in HTML resolve via our handler
            b.loadURL(CefResourceSchemeHandler.BASE_URL + "index.html")
        } catch (e: Exception) {
            // Fallback: if CefApp registration fails, load HTML directly
            LOG.warn("AgentCefPanel: scheme handler registration failed, falling back to loadHTML", e)
            val htmlContent = javaClass.classLoader.getResource("webview/dist/index.html")?.readText()
            if (htmlContent != null) b.loadHTML(htmlContent)
            else {
                LOG.error("AgentCefPanel: index.html not found in resources")
                return
            }
        }

        // Create JS→Kotlin bridges for UI actions (undo, view-trace, example prompts)
        undoQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onUndoRequested?.invoke(); JBCefJSQuery.Response("ok") }
        }
        traceQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onViewTraceRequested?.invoke(); JBCefJSQuery.Response("ok") }
        }
        promptQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { text -> onPromptSubmitted?.invoke(text); JBCefJSQuery.Response("ok") }
        }
        planApproveQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onPlanApproved?.invoke(); JBCefJSQuery.Response("ok") }
        }
        planReviseQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { commentsJson -> onPlanRevised?.invoke(commentsJson); JBCefJSQuery.Response("ok") }
        }
        toolToggleQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { data ->
                // data format: "tool_name:1" or "tool_name:0"
                val colonIdx = data.lastIndexOf(':')
                if (colonIdx > 0) {
                    val toolName = data.substring(0, colonIdx)
                    val enabled = data.substring(colonIdx + 1) == "1"
                    onToolToggled?.invoke(toolName, enabled)
                }
                JBCefJSQuery.Response("ok")
            }
        }

        // Question wizard bridges
        questionAnsweredQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { data ->
                // data format: "questionId:optionsJson"
                val sep = data.indexOf(':')
                if (sep > 0) onQuestionAnswered?.invoke(data.substring(0, sep), data.substring(sep + 1))
                JBCefJSQuery.Response("ok")
            }
        }
        questionSkippedQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { data -> onQuestionSkipped?.invoke(data); JBCefJSQuery.Response("ok") }
        }
        chatAboutQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { data ->
                // data format: "questionId\x1FoptionLabel\x1Fmessage" (unit separator avoids colon conflicts)
                val parts = data.split("\u001F")
                if (parts.size >= 3) onChatAboutOption?.invoke(parts[0], parts[1], parts.drop(2).joinToString("\u001F"))
                JBCefJSQuery.Response("ok")
            }
        }
        questionsSubmittedQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onQuestionsSubmitted?.invoke(); JBCefJSQuery.Response("ok") }
        }
        questionsCancelledQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onQuestionsCancelled?.invoke(); JBCefJSQuery.Response("ok") }
        }
        editQuestionQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { data -> onEditQuestion?.invoke(data); JBCefJSQuery.Response("ok") }
        }
        deactivateSkillQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onSkillDismissed?.invoke(); JBCefJSQuery.Response("ok") }
        }
        navigateToFileQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { data ->
                val colonIdx = data.lastIndexOf(':')
                val hasLine = colonIdx > 0 && data.substring(colonIdx + 1).toIntOrNull() != null
                val filePath = if (hasLine) data.substring(0, colonIdx) else data
                val line = if (hasLine) data.substring(colonIdx + 1).toInt() else 0
                onNavigateToFile?.invoke(filePath, line)
                JBCefJSQuery.Response("ok")
            }
        }

        // Toolbar + input bar bridges
        cancelTaskQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onCancelTask?.invoke(); JBCefJSQuery.Response("ok") }
        }
        newChatQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onNewChat?.invoke(); JBCefJSQuery.Response("ok") }
        }
        sendMessageQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { text -> onSendMessage?.invoke(text); JBCefJSQuery.Response("ok") }
        }
        changeModelQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { modelId -> onChangeModel?.invoke(modelId); JBCefJSQuery.Response("ok") }
        }
        togglePlanModeQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { enabled -> onTogglePlanMode?.invoke(enabled == "true"); JBCefJSQuery.Response("ok") }
        }
        activateSkillQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { name -> onActivateSkill?.invoke(name); JBCefJSQuery.Response("ok") }
        }
        requestFocusIdeQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onRequestFocusIde?.invoke(); JBCefJSQuery.Response("ok") }
        }
        openSettingsQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onOpenSettings?.invoke(); JBCefJSQuery.Response("ok") }
        }
        openToolsPanelQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onOpenToolsPanel?.invoke(); JBCefJSQuery.Response("ok") }
        }
        searchMentionsQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { data ->
                // data format: "type:query" e.g. "file:Login" or "categories:"
                val colonIdx = data.indexOf(':')
                val type = if (colonIdx > 0) data.substring(0, colonIdx) else data
                val query = if (colonIdx > 0) data.substring(colonIdx + 1) else ""
                // Search on IO thread, callback to JS
                scope.launch {
                    try {
                        withTimeout(5000L) {
                            val results = mentionSearchProvider?.search(type, query) ?: "[]"
                            callJs("receiveMentionResults(${jsonStr(results)})")
                        }
                    } catch (e: Exception) {
                        LOG.debug("searchMentions handler failed: ${e.message}")
                        callJs("receiveMentionResults('[]')")
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }
        searchTicketsQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { query ->
                scope.launch {
                    try {
                        withTimeout(5000L) {
                            val results = mentionSearchProvider?.searchTickets(query) ?: "[]"
                            callJs("(window.__ticketSearchCallback)(${jsonStr(results)})")
                        }
                    } catch (e: Exception) {
                        LOG.debug("searchTickets handler failed: ${e.message}")
                        callJs("(window.__ticketSearchCallback)('[]')")
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }
        validateTicketQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { data ->
                // data format: "TICKETKEY|callbackName"
                val parts = data.split("|", limit = 2)
                val ticketKey = parts.getOrElse(0) { "" }
                val callbackName = parts.getOrElse(1) { "" }
                scope.launch {
                    try {
                        withTimeout(5000L) {
                            val result = mentionSearchProvider?.validateTicket(ticketKey)
                            val json = result ?: """{"valid":false}"""
                            callJs("(window[${jsonStr(callbackName)}])(${jsonStr(json)})")
                        }
                    } catch (e: Exception) {
                        LOG.debug("validateTicket handler failed: ${e.message}")
                        callJs("(window[${jsonStr(callbackName)}])(${jsonStr("""{"valid":false}""")})")
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }
        sendMessageWithMentionsQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { payload ->
                try {
                    val json = Json.parseToJsonElement(payload).jsonObject
                    val text = json["text"]?.jsonPrimitive?.content ?: ""
                    val mentionsJson = json["mentions"]?.toString() ?: "[]"
                    onSendMessageWithMentions?.invoke(text, mentionsJson)
                } catch (e: Exception) {
                    // Fallback: treat entire payload as text
                    onSendMessage?.invoke(payload)
                }
                JBCefJSQuery.Response("ok")
            }
        }
        openInEditorTabQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { payload -> onOpenInEditorTab?.invoke(payload); JBCefJSQuery.Response("ok") }
        }

        // Tool call approval bridges
        approveToolCallQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onApproveToolCall?.invoke(); JBCefJSQuery.Response("ok") }
        }
        denyToolCallQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { _ -> onDenyToolCall?.invoke(); JBCefJSQuery.Response("ok") }
        }
        allowToolForSessionQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { toolName -> onAllowToolForSession?.invoke(toolName); JBCefJSQuery.Response("ok") }
        }
        interactiveHtmlMessageQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { json -> onInteractiveHtmlMessage?.invoke(json); JBCefJSQuery.Response("ok") }
        }
        acceptDiffHunkQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { data ->
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
        }
        rejectDiffHunkQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { data ->
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
        }
        killToolCallQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { toolCallId -> onKillToolCall?.invoke(toolCallId); JBCefJSQuery.Response("ok") }
        }
        killSubAgentQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { agentId -> onKillSubAgent?.invoke(agentId); JBCefJSQuery.Response("ok") }
        }
        processInputQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { input -> onProcessInputResolved?.invoke(input); JBCefJSQuery.Response("ok") }
        }

        // Wait for page load before executing JS
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                browser: CefBrowser?, isLoading: Boolean,
                canGoBack: Boolean, canGoForward: Boolean
            ) {
                if (!isLoading) {
                    applyCurrentTheme()
                    // Inject JS→Kotlin bridge functions for JCEF UI actions
                    undoQuery?.let { q ->
                        val undoJs = q.inject("'undo'")
                        js("window._requestUndo = function() { $undoJs }")
                    }
                    traceQuery?.let { q ->
                        val traceJs = q.inject("'trace'")
                        js("window._requestViewTrace = function() { $traceJs }")
                    }
                    promptQuery?.let { q ->
                        val promptJs = q.inject("text")
                        js("window._submitPrompt = function(text) { $promptJs }")
                    }
                    planApproveQuery?.let { q ->
                        val planApproveJs = q.inject("'approve'")
                        js("window._approvePlan = function() { $planApproveJs }")
                    }
                    planReviseQuery?.let { q ->
                        val planReviseJs = q.inject("comments")
                        js("window._revisePlan = function(comments) { $planReviseJs }")
                    }
                    toolToggleQuery?.let { q ->
                        val toolToggleJs = q.inject("data")
                        js("window._toggleTool = function(data) { $toolToggleJs }")
                    }
                    // Question wizard bridges
                    questionAnsweredQuery?.let { q ->
                        val qaJs = q.inject("qid + ':' + opts")
                        js("window._questionAnswered = function(qid, opts) { $qaJs }")
                    }
                    questionSkippedQuery?.let { q ->
                        val qsJs = q.inject("qid")
                        js("window._questionSkipped = function(qid) { $qsJs }")
                    }
                    chatAboutQuery?.let { q ->
                        val caJs = q.inject("qid + '\\x1F' + label + '\\x1F' + msg")
                        js("window._chatAboutOption = function(qid, label, msg) { $caJs }")
                    }
                    questionsSubmittedQuery?.let { q ->
                        val qsubJs = q.inject("'submit'")
                        js("window._questionsSubmitted = function() { $qsubJs }")
                    }
                    questionsCancelledQuery?.let { q ->
                        val qcanJs = q.inject("'cancel'")
                        js("window._questionsCancelled = function() { $qcanJs }")
                    }
                    editQuestionQuery?.let { q ->
                        val eqJs = q.inject("qid")
                        js("window._editQuestion = function(qid) { $eqJs }")
                    }
                    deactivateSkillQuery?.let { q ->
                        val dsJs = q.inject("'dismiss'")
                        js("window._deactivateSkill = function() { $dsJs }")
                    }
                    navigateToFileQuery?.let { q ->
                        val navJs = q.inject("path")
                        js("window._navigateToFile = function(path) { $navJs }")
                    }
                    // Toolbar + input bar bridges
                    cancelTaskQuery?.let { q ->
                        val cancelJs = q.inject("'cancel'")
                        js("window._cancelTask = function() { $cancelJs }")
                    }
                    newChatQuery?.let { q ->
                        val newJs = q.inject("'new'")
                        js("window._newChat = function() { $newJs }")
                    }
                    sendMessageQuery?.let { q ->
                        val sendJs = q.inject("text")
                        js("window._sendMessage = function(text) { $sendJs }")
                    }
                    changeModelQuery?.let { q ->
                        val modelJs = q.inject("modelId")
                        js("window._changeModel = function(modelId) { $modelJs }")
                    }
                    togglePlanModeQuery?.let { q ->
                        val planJs = q.inject("String(enabled)")
                        js("window._togglePlanMode = function(enabled) { $planJs }")
                    }
                    activateSkillQuery?.let { q ->
                        val skillJs = q.inject("name")
                        js("window._activateSkill = function(name) { $skillJs }")
                    }
                    requestFocusIdeQuery?.let { q ->
                        val focusJs = q.inject("'focus'")
                        js("window._requestFocusIde = function() { $focusJs }")
                    }
                    openSettingsQuery?.let { q ->
                        val settingsJs = q.inject("'settings'")
                        js("window._openSettings = function() { $settingsJs }")
                    }
                    openToolsPanelQuery?.let { q ->
                        val toolsJs = q.inject("'tools'")
                        js("window._openToolsPanel = function() { $toolsJs }")
                    }
                    searchMentionsQuery?.let { q ->
                        val searchJs = q.inject("data")
                        js("window._searchMentions = function(data) { $searchJs }")
                    }
                    searchTicketsQuery?.let { q ->
                        val ticketJs = q.inject("query")
                        js("window._searchTickets = function(query) { $ticketJs }")
                    }
                    validateTicketQuery?.let { q ->
                        val validateJs = q.inject("key + '|' + cb")
                        js("window._validateTicket = function(key, cb) { $validateJs }")
                    }
                    sendMessageWithMentionsQuery?.let { q ->
                        val sendJs = q.inject("payload")
                        js("window._sendMessageWithMentions = function(payload) { $sendJs }")
                    }
                    openInEditorTabQuery?.let { q ->
                        val tabJs = q.inject("payload")
                        js("window._openInEditorTab = function(payload) { $tabJs }")
                    }
                    // Tool call approval + diff hunk + interactive HTML bridges
                    approveToolCallQuery?.let { q ->
                        val approveJs = q.inject("'approve'")
                        js("window._approveToolCall = function() { $approveJs }")
                    }
                    denyToolCallQuery?.let { q ->
                        val denyJs = q.inject("'deny'")
                        js("window._denyToolCall = function() { $denyJs }")
                    }
                    allowToolForSessionQuery?.let { q ->
                        val allowJs = q.inject("toolName")
                        js("window._allowToolForSession = function(toolName) { $allowJs }")
                    }
                    interactiveHtmlMessageQuery?.let { q ->
                        val htmlJs = q.inject("json")
                        js("window._interactiveHtmlMessage = function(json) { $htmlJs }")
                    }
                    acceptDiffHunkQuery?.let { q ->
                        val acceptJs = q.inject("JSON.stringify({filePath:fp,hunkIndex:hi,editedContent:ec||null})")
                        js("window._acceptDiffHunk = function(fp,hi,ec) { $acceptJs }")
                    }
                    rejectDiffHunkQuery?.let { q ->
                        val rejectJs = q.inject("JSON.stringify({filePath:fp,hunkIndex:hi})")
                        js("window._rejectDiffHunk = function(fp,hi) { $rejectJs }")
                    }
                    killToolCallQuery?.let { q ->
                        val killJs = q.inject("toolCallId")
                        js("window._killToolCall = function(toolCallId) { $killJs }")
                    }
                    killSubAgentQuery?.let { q ->
                        val killSaJs = q.inject("agentId")
                        js("window._killSubAgent = function(agentId) { $killSaJs }")
                    }
                    processInputQuery?.let { q ->
                        val inputJs = q.inject("input")
                        js("window._resolveProcessInput = function(input) { $inputJs }")
                    }
                    // Set pageLoaded AFTER bridges are injected
                    pageLoaded = true
                    // Then flush pending calls (they can now execute)
                    synchronized(pendingCalls) {
                        pendingCalls.forEach { js(it) }
                        pendingCalls.clear()
                    }
                }
            }
        }, b.cefBrowser)

        browser = b
        add(b.component, BorderLayout.CENTER)
    }

    // ═══════════════════════════════════════════════════
    //  Public API — mirrors RichStreamingPanel
    // ═══════════════════════════════════════════════════

    fun startSession(task: String) {
        callJs("startSession(${jsonStr(task)})")
    }

    fun appendUserMessage(text: String) {
        callJs("appendUserMessage(${jsonStr(text)})")
    }

    fun completeSession(
        tokensUsed: Int, iterations: Int, filesModified: List<String>,
        durationMs: Long, status: RichStreamingPanel.SessionStatus
    ) {
        val filesArray = filesModified.joinToString(",") { jsonStr(it) }
        callJs("endStream(); completeSession('${status.name}',$tokensUsed,$durationMs,$iterations,[$filesArray])")
    }

    fun appendStreamToken(token: String) {
        callJs("appendToken(${jsonStr(token)})")
    }

    fun flushStreamBuffer() {
        callJs("endStream()")
    }

    fun finalizeToolChain() {
        callJs("finalizeToolChain()")
    }

    fun appendCompletionSummary(result: String, verifyCommand: String? = null) {
        val cmdArg = if (verifyCommand != null) jsonStr(verifyCommand) else "undefined"
        callJs("appendCompletionSummary(${jsonStr(result)},$cmdArg)")
    }

    fun appendToolCall(
        toolName: String, args: String = "",
        status: RichStreamingPanel.ToolCallStatus = RichStreamingPanel.ToolCallStatus.RUNNING
    ) {
        callJs("appendToolCall(${jsonStr(toolName)},${jsonStr(args)},'${status.name}')")
    }

    fun updateLastToolCall(
        status: RichStreamingPanel.ToolCallStatus,
        result: String = "", durationMs: Long = 0,
        toolName: String = "", output: String? = null
    ) {
        val statusStr = if (status == RichStreamingPanel.ToolCallStatus.FAILED) "ERROR" else "COMPLETED"
        val outputArg = if (output != null) jsonStr(output) else "null"
        callJs("updateToolResult(${jsonStr(result)},$durationMs,${jsonStr(toolName)},${jsonStr(statusStr)},$outputArg)")
    }

    fun appendToolOutput(toolCallId: String, chunk: String) {
        callJs("appendToolOutput(${jsonStr(toolCallId)},${jsonStr(chunk)})")
    }

    fun appendEditDiff(
        filePath: String, oldText: String, newText: String, accepted: Boolean? = null
    ) {
        val oldLines = oldText.lines().take(100).joinToString(",") { jsonStr(it) }
        val newLines = newText.lines().take(100).joinToString(",") { jsonStr(it) }
        val acceptedJs = when (accepted) { true -> "true"; false -> "false"; null -> "null" }
        callJs("appendDiff(${jsonStr(filePath)},[$oldLines],[$newLines],$acceptedJs)")
    }

    fun appendStatus(message: String, type: RichStreamingPanel.StatusType = RichStreamingPanel.StatusType.INFO) {
        callJs("appendStatus(${jsonStr(message)},'${type.name}')")
    }

    fun appendError(message: String) = appendStatus(message, RichStreamingPanel.StatusType.ERROR)

    fun appendThinking(text: String) {
        callJs("appendThinking(${jsonStr(text)})")
    }

    fun clear() {
        callJs("clearChat()")
    }

    fun showToolsPanel(toolsJson: String) {
        callJs("showToolsPanel(${jsonStr(toolsJson)})")
    }

    fun hideToolsPanel() {
        callJs("closeToolsPanel()")
    }

    fun renderPlan(planJson: String) {
        callJs("renderPlan(${jsonStr(planJson)})")
    }

    fun updatePlanStep(stepId: String, status: String) {
        callJs("updatePlanStep(${jsonStr(stepId)}, ${jsonStr(status)})")
    }

    // ── Question wizard rendering ──

    fun showQuestions(questionsJson: String) {
        callJs("showQuestions(${jsonStr(questionsJson)})")
    }

    fun showQuestion(index: Int) {
        callJs("showQuestion($index)")
    }

    fun showQuestionSummary(summaryJson: String) {
        callJs("showQuestionSummary(${jsonStr(summaryJson)})")
    }

    fun enableChatInput() {
        callJs("enableChatInput()")
    }

    // ── Toolbar + input bar control ──

    fun setBusy(busy: Boolean) {
        callJs("setBusy(${if (busy) "true" else "false"})")
    }

    fun setInputLocked(locked: Boolean) {
        callJs("setInputLocked(${if (locked) "true" else "false"})")
    }

    fun updateTokenBudget(used: Int, max: Int) {
        callJs("updateTokenBudget($used,$max)")
    }

    fun setModelName(name: String) {
        callJs("setModelName(${jsonStr(name)})")
    }

    fun setPlanMode(enabled: Boolean) {
        callJs("setPlanMode(${if (enabled) "true" else "false"})")
    }

    // ── Sub-Agent boundary card bridge methods ──

    fun spawnSubAgent(agentId: String, label: String) {
        val payload = buildJsonObject { put("agentId", agentId); put("label", label) }.toString()
        callJs("spawnSubAgent(${jsonStr(payload)})")
    }

    fun updateSubAgentIteration(agentId: String, iteration: Int) {
        val payload = buildJsonObject { put("agentId", agentId); put("iteration", iteration) }.toString()
        callJs("updateSubAgentIteration(${jsonStr(payload)})")
    }

    fun addSubAgentToolCall(agentId: String, toolName: String, toolArgs: String) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("toolName", toolName)
            put("toolArgs", toolArgs)
        }.toString()
        callJs("addSubAgentToolCall(${jsonStr(payload)})")
    }

    fun updateSubAgentToolCall(
        agentId: String, toolName: String, result: String,
        durationMs: Long, isError: Boolean
    ) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("toolName", toolName)
            put("toolResult", result.take(2000))   // guard against huge results in the payload
            put("toolDurationMs", durationMs)
            put("isError", isError)
        }.toString()
        callJs("updateSubAgentToolCall(${jsonStr(payload)})")
    }

    fun updateSubAgentMessage(agentId: String, textContent: String) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("textContent", textContent)
        }.toString()
        callJs("updateSubAgentMessage(${jsonStr(payload)})")
    }

    fun completeSubAgent(agentId: String, textContent: String, tokensUsed: Int, isError: Boolean) {
        val payload = buildJsonObject {
            put("agentId", agentId)
            put("textContent", textContent)
            put("tokensUsed", tokensUsed)
            put("isError", isError)
        }.toString()
        callJs("completeSubAgent(${jsonStr(payload)})")
    }

    fun updateModelList(modelsJson: String) {
        callJs("updateModelList(${jsonStr(modelsJson)})")
    }

    fun updateSkillsList(skillsJson: String) {
        callJs("updateSkillsList(${jsonStr(skillsJson)})")
    }

    fun showRetryButton(lastMessage: String) {
        callJs("showRetryButton(${jsonStr(lastMessage)})")
    }

    fun focusInput() {
        callJs("focusInput()")
    }

    // ── Skill banner rendering ──

    fun showSkillBanner(name: String) {
        callJs("showSkillBanner(${jsonStr(name)})")
    }

    fun hideSkillBanner() {
        callJs("hideSkillBanner()")
    }

    // ── Chart.js support ──

    fun appendChart(chartConfigJson: String) {
        callJs("appendChart(${jsonStr(chartConfigJson)})")
    }

    // ── ANSI, Skeleton, Toast, Table support ──

    fun appendAnsiOutput(text: String) {
        callJs("appendAnsiOutput(${jsonStr(text)})")
    }

    fun showSkeleton() {
        callJs("showSkeleton()")
    }

    fun hideSkeleton() {
        callJs("hideSkeleton()")
    }

    fun showToast(message: String, type: String = "info", durationMs: Int = 3000) {
        callJs("showToast(${jsonStr(message)},${jsonStr(type)},$durationMs)")
    }

    // ── Tabbed content, timeline, progress bar ──

    fun appendTabs(tabsJson: String) {
        callJs("appendTabs(${jsonStr(tabsJson)})")
    }

    fun appendTimeline(itemsJson: String) {
        callJs("appendTimeline(${jsonStr(itemsJson)})")
    }

    fun appendProgressBar(percent: Int, type: String = "info") {
        callJs("appendProgressBar($percent,${jsonStr(type)})")
    }

    // ── Jira card & Sonar badge ──

    fun appendJiraCard(cardJson: String) {
        callJs("appendJiraCard(${jsonStr(cardJson)})")
    }

    fun appendSonarBadge(badgeJson: String) {
        callJs("appendSonarBadge(${jsonStr(badgeJson)})")
    }

    // ── Tool call approval rendering ──

    fun showApproval(toolName: String, riskLevel: String, description: String, metadataJson: String, diffContent: String? = null) {
        val diffArg = if (diffContent != null) jsonStr(diffContent) else "null"
        callJs("showApproval(${jsonStr(toolName)},${jsonStr(riskLevel)},${jsonStr(description)},${jsonStr(metadataJson)},$diffArg)")
    }

    fun showProcessInput(processId: String, description: String, prompt: String, command: String) {
        callJs("showProcessInput(${jsonStr(processId)},${jsonStr(description)},${jsonStr(prompt)},${jsonStr(command)})")
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
                    else -> {
                        val escaped = v.toString().take(100)
                            .replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                        "\"$k\":\"$escaped\""
                    }
                }
            }.joinToString(",")
            "{$pairs}"
        } else "null"
        val json = """{"ts":$ts,"level":"$level","event":"$event","detail":"$truncatedDetail","meta":$metaStr}"""
        callJs("addDebugLogEntry(${jsonStr(json)})")
    }

    // Backward compat
    fun appendText(text: String) = appendStreamToken(text)
    fun setText(text: String) {
        callJs("clearChat(); appendToken(${jsonStr(text)}); endStream()")
    }
    fun appendSeparator(label: String = "") {
        if (label.isNotBlank()) appendStatus(label)
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
        if (pageLoaded) {
            js(code)
        } else {
            synchronized(pendingCalls) {
                if (pendingCalls.size >= 10_000) {
                    LOG.warn("Pending JS calls queue exceeded 10K items, dropping call")
                    return
                }
                pendingCalls.add(code)
            }
        }
    }

    private fun js(code: String) {
        try {
            browser?.cefBrowser?.executeJavaScript(code, CefResourceSchemeHandler.BASE_URL, 0)
        } catch (e: Exception) {
            LOG.debug("AgentCefPanel: JS execution failed: ${e.message}")
        }
    }

    /** Escape a string for safe injection into JavaScript. */
    private fun jsonStr(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "'$escaped'"
    }

    override fun dispose() {
        scope.cancel()
        undoQuery?.dispose()
        traceQuery?.dispose()
        promptQuery?.dispose()
        planApproveQuery?.dispose()
        planReviseQuery?.dispose()
        toolToggleQuery?.dispose()
        questionAnsweredQuery?.dispose()
        questionSkippedQuery?.dispose()
        chatAboutQuery?.dispose()
        questionsSubmittedQuery?.dispose()
        questionsCancelledQuery?.dispose()
        editQuestionQuery?.dispose()
        deactivateSkillQuery?.dispose()
        navigateToFileQuery?.dispose()
        cancelTaskQuery?.dispose()
        newChatQuery?.dispose()
        sendMessageQuery?.dispose()
        changeModelQuery?.dispose()
        togglePlanModeQuery?.dispose()
        activateSkillQuery?.dispose()
        requestFocusIdeQuery?.dispose()
        openSettingsQuery?.dispose()
        openToolsPanelQuery?.dispose()
        searchMentionsQuery?.dispose()
        searchTicketsQuery?.dispose()
        validateTicketQuery?.dispose()
        sendMessageWithMentionsQuery?.dispose()
        openInEditorTabQuery?.dispose()
        approveToolCallQuery?.dispose()
        denyToolCallQuery?.dispose()
        allowToolForSessionQuery?.dispose()
        interactiveHtmlMessageQuery?.dispose()
        acceptDiffHunkQuery?.dispose()
        rejectDiffHunkQuery?.dispose()
        killToolCallQuery?.dispose()
        killSubAgentQuery?.dispose()
        processInputQuery?.dispose()
        browser?.dispose()
        undoQuery = null
        traceQuery = null
        promptQuery = null
        planApproveQuery = null
        planReviseQuery = null
        toolToggleQuery = null
        questionAnsweredQuery = null
        questionSkippedQuery = null
        chatAboutQuery = null
        questionsSubmittedQuery = null
        questionsCancelledQuery = null
        editQuestionQuery = null
        deactivateSkillQuery = null
        navigateToFileQuery = null
        cancelTaskQuery = null
        newChatQuery = null
        sendMessageQuery = null
        changeModelQuery = null
        togglePlanModeQuery = null
        activateSkillQuery = null
        requestFocusIdeQuery = null
        openSettingsQuery = null
        openToolsPanelQuery = null
        searchMentionsQuery = null
        searchTicketsQuery = null
        validateTicketQuery = null
        sendMessageWithMentionsQuery = null
        openInEditorTabQuery = null
        approveToolCallQuery = null
        denyToolCallQuery = null
        allowToolForSessionQuery = null
        interactiveHtmlMessageQuery = null
        acceptDiffHunkQuery = null
        rejectDiffHunkQuery = null
        killToolCallQuery = null
        killSubAgentQuery = null
        processInputQuery = null
        browser = null
    }
}
