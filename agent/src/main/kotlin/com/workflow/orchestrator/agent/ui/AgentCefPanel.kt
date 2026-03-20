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
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * JCEF-based (embedded Chromium) chat panel for the AI agent.
 *
 * Provides the S-tier visual experience: CSS3 animations, border-radius,
 * smooth streaming, color-coded tool badges, and proper diff rendering.
 *
 * Communication:
 * - Kotlin → JS: `executeJavaScript()` calls functions defined in agent-chat.html
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

    private var browser: JBCefBrowser? = null
    private var undoQuery: JBCefJSQuery? = null
    private var traceQuery: JBCefJSQuery? = null
    private var promptQuery: JBCefJSQuery? = null
    private var planApproveQuery: JBCefJSQuery? = null
    private var planReviseQuery: JBCefJSQuery? = null
    private var pageLoaded = false
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

    init {
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

        // Load HTML from resources
        val htmlContent = javaClass.classLoader.getResource("webview/agent-chat.html")?.readText()
        if (htmlContent != null) {
            b.loadHTML(htmlContent)
        } else {
            LOG.error("AgentCefPanel: agent-chat.html not found in resources")
            return
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

        // Wait for page load before executing JS
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                browser: CefBrowser?, isLoading: Boolean,
                canGoBack: Boolean, canGoForward: Boolean
            ) {
                if (!isLoading) {
                    pageLoaded = true
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
                    // Execute any pending calls
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

    fun appendToolCall(
        toolName: String, args: String = "",
        status: RichStreamingPanel.ToolCallStatus = RichStreamingPanel.ToolCallStatus.RUNNING
    ) {
        callJs("appendToolCall(${jsonStr(toolName)},${jsonStr(args)},'${status.name}')")
    }

    fun updateLastToolCall(
        status: RichStreamingPanel.ToolCallStatus,
        result: String = "", durationMs: Long = 0
    ) {
        callJs("updateToolResult(${jsonStr(result)},$durationMs)")
    }

    fun appendEditDiff(
        filePath: String, oldText: String, newText: String, accepted: Boolean? = null
    ) {
        val oldLines = oldText.lines().take(20).joinToString(",") { jsonStr(it) }
        val newLines = newText.lines().take(20).joinToString(",") { jsonStr(it) }
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

    fun renderPlan(planJson: String) {
        callJs("renderPlan(${jsonStr(planJson)})")
    }

    fun updatePlanStep(stepId: String, status: String) {
        callJs("updatePlanStep(${jsonStr(stepId)}, ${jsonStr(status)})")
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
            "warning" to c.hex(c.warning), "link" to c.hex(c.linkText)
        ) else mapOf(
            "bg" to "#FFFFFF", "fg" to "#1E293B",
            "fg-secondary" to "#475569", "fg-muted" to "#64748B",
            "border" to "#E2E8F0", "user-bg" to "#F1F5F9",
            "tool-bg" to "#F8FAFC", "code-bg" to "#F1F5F9",
            "thinking-bg" to "#F9FAFB",
            "badge-read-bg" to "#DBEAFE", "badge-read-fg" to "#2563EB",
            "badge-write-bg" to "#DCFCE7", "badge-write-fg" to "#16A34A",
            "badge-edit-bg" to "#FEF3C7", "badge-edit-fg" to "#D97706",
            "badge-cmd-bg" to "#FEE2E2", "badge-cmd-fg" to "#DC2626",
            "badge-search-bg" to "#CFFAFE", "badge-search-fg" to "#0891B2",
            "accent-read" to "#3B82F6", "accent-write" to "#22C55E",
            "accent-edit" to "#F59E0B", "accent-cmd" to "#EF4444",
            "accent-search" to "#06B6D4",
            "diff-add-bg" to "#DCFCE7", "diff-add-fg" to "#166534",
            "diff-rem-bg" to "#FEE2E2", "diff-rem-fg" to "#991B1B",
            "success" to "#16A34A", "error" to "#DC2626",
            "warning" to "#D97706", "link" to "#2563EB"
        )

        val jsObj = vars.entries.joinToString(",") { "'${it.key}':'${it.value}'" }
        js("applyTheme({$jsObj})")
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
                pendingCalls.add(code)
            }
        }
    }

    private fun js(code: String) {
        try {
            browser?.cefBrowser?.executeJavaScript(code, "agent://bridge", 0)
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
        undoQuery?.dispose()
        traceQuery?.dispose()
        promptQuery?.dispose()
        planApproveQuery?.dispose()
        planReviseQuery?.dispose()
        browser?.dispose()
        undoQuery = null
        traceQuery = null
        promptQuery = null
        planApproveQuery = null
        planReviseQuery = null
        browser = null
    }
}
