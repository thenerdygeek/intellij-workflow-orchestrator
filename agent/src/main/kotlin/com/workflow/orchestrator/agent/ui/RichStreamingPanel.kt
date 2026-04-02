package com.workflow.orchestrator.agent.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.agent.util.AgentStringUtils
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

/**
 * S-tier streaming output panel modeled after Claude Code, Cursor, and Cline.
 *
 * Key patterns from production tools:
 * - Color-coded tool badges: READ (blue), EDIT (amber), WRITE (green), COMMAND (red)
 * - User messages in bubbles (right-aligned background)
 * - Streaming via HTMLDocument.insertBeforeEnd() (no full re-render)
 * - Collapsible detail blocks
 * - Inline diffs with colored +/- lines
 * - Thinking blocks in muted italic with left border
 * - Session footer with structured metrics
 *
 * Uses JEditorPane with HTMLEditorKit. Limited to HTML 3.2/CSS1 but achieves
 * ~80% of the visual quality of JCEF-based tools through careful styling.
 */
class RichStreamingPanel : JPanel(BorderLayout()) {

    private val editorPane = JEditorPane().apply {
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = JBUI.Fonts.label(13f)
    }
    private val scrollPane = JBScrollPane(editorPane)
    private val htmlDoc get() = editorPane.document as HTMLDocument

    // Streaming state
    private var activeStreamId: String? = null
    private var streamIdCounter = 0

    init {
        border = JBUI.Borders.empty()
        setupEditor()
        add(scrollPane, BorderLayout.CENTER)
        renderEmpty()
    }

    private fun setupEditor() {
        val kit = HTMLEditorKit()
        editorPane.editorKit = kit
    }

    private fun buildBaseHtml(): String {
        val c = AgentColors
        return """
        <html><head><style>
            body { font-family: -apple-system, 'Segoe UI', system-ui, sans-serif; font-size: 13px; color: ${c.hex(c.primaryText)}; background: ${c.hex(c.panelBg)}; margin: 0; padding: 12px; line-height: 1.5; }
            .user-bubble { background: ${c.hex(c.userMsgBg)}; padding: 8px 12px; margin: 12px 0 12px 48px; }
            .user-label { font-size: 11px; font-weight: 600; color: ${c.hex(c.mutedText)}; margin-bottom: 2px; }
            .agent-msg { padding: 4px 0; margin: 8px 0; }
            .tool-card { background: ${c.hex(c.toolCallBg)}; margin: 10px 0; padding: 0; }
            .tool-header { padding: 6px 10px; }
            .tool-detail { padding: 4px 10px 8px 10px; font-size: 12px; color: ${c.hex(c.secondaryText)}; }
            .badge { font-size: 10px; font-weight: 600; padding: 1px 6px; letter-spacing: 0.5px; }
            .badge-read { background: ${c.hex(c.badgeRead)}; color: ${c.hex(c.badgeReadText)}; }
            .badge-write { background: ${c.hex(c.badgeWrite)}; color: ${c.hex(c.badgeWriteText)}; }
            .badge-edit { background: ${c.hex(c.badgeEdit)}; color: ${c.hex(c.badgeEditText)}; }
            .badge-cmd { background: ${c.hex(c.badgeCmd)}; color: ${c.hex(c.badgeCmdText)}; }
            .badge-search { background: ${c.hex(c.badgeSearch)}; color: ${c.hex(c.badgeSearchText)}; }
            .badge-ok { background: ${c.hex(c.badgeWrite)}; color: ${c.hex(c.badgeWriteText)}; }
            .badge-fail { background: ${c.hex(c.badgeCmd)}; color: ${c.hex(c.badgeCmdText)}; }
            .target { font-size: 12px; color: ${c.hex(c.primaryText)}; margin-left: 6px; }
            .timing { font-size: 11px; color: ${c.hex(c.mutedText)}; float: right; }
            .code-block { background: ${c.hex(c.codeBg)}; font-family: 'JetBrains Mono', Menlo, Consolas, monospace; font-size: 12px; padding: 10px 12px; margin: 8px 0; white-space: pre-wrap; word-wrap: break-word; }
            .thinking { color: ${c.hex(c.mutedText)}; font-style: italic; border-left: 2px solid ${c.hex(c.mutedText)}; padding-left: 10px; margin: 8px 0; font-size: 12px; }
            .diff-card { margin: 10px 0; border: 1px solid ${c.hex(c.border)}; }
            .diff-header { background: ${c.hex(c.toolCallBg)}; padding: 6px 10px; font-size: 12px; border-bottom: 1px solid ${c.hex(c.border)}; }
            .diff-add { background: ${c.hex(c.diffAddBg)}; color: ${c.hex(c.diffAddText)}; padding: 1px 10px; font-family: monospace; font-size: 12px; }
            .diff-rem { background: ${c.hex(c.diffRemBg)}; color: ${c.hex(c.diffRemText)}; padding: 1px 10px; font-family: monospace; font-size: 12px; }
            .status-info { color: ${c.hex(c.mutedText)}; font-size: 12px; padding: 4px 0; }
            .status-success { color: ${c.hex(c.success)}; font-size: 12px; padding: 4px 0; }
            .status-error { color: ${c.hex(c.error)}; font-size: 12px; font-weight: 600; padding: 4px 0; }
            .status-warning { color: ${c.hex(c.warning)}; font-size: 12px; padding: 4px 0; }
            .footer { margin-top: 16px; padding: 12px 0; border-top: 1px solid ${c.hex(c.border)}; }
            .footer-status { font-size: 13px; font-weight: 600; margin-bottom: 6px; }
            .footer-metrics { font-size: 11px; color: ${c.hex(c.mutedText)}; font-family: monospace; }
            .footer-files { font-size: 11px; color: ${c.hex(c.secondaryText)}; margin-top: 4px; }
            .inline-code { background: ${c.hex(c.codeBg)}; font-family: monospace; font-size: 12px; padding: 1px 4px; }
            .stream-cursor { color: ${c.hex(c.mutedText)}; }
            a { color: ${c.hex(c.linkText)}; text-decoration: none; }
        </style></head><body id="chatBody"></body></html>
        """.trimIndent()
    }

    // ═══════════════════════════════════════════════════
    //  Public API — Session Lifecycle
    // ═══════════════════════════════════════════════════

    fun startSession(task: String) = runOnEdt {
        editorPane.text = buildBaseHtml()
        appendUserMessage(task)
    }

    fun appendUserMessage(text: String) = runOnEdt {
        flushStreamBuffer()
        appendHtml("""<div class="user-bubble"><div class="user-label">You</div>${escapeHtml(text)}</div>""")
    }

    fun completeSession(
        tokensUsed: Int, iterations: Int, filesModified: List<String>,
        durationMs: Long, status: SessionStatus = SessionStatus.SUCCESS
    ) = runOnEdt {
        flushStreamBuffer()
        val (icon, color, label) = when (status) {
            SessionStatus.SUCCESS -> Triple("&#10003;", AgentColors.hex(AgentColors.success), "Completed")
            SessionStatus.FAILED -> Triple("&#10007;", AgentColors.hex(AgentColors.error), "Failed")
            SessionStatus.CANCELLED -> Triple("&#9724;", AgentColors.hex(AgentColors.mutedText), "Cancelled")
        }
        val files = if (filesModified.isNotEmpty()) {
            "<div class='footer-files'><b>Files:</b> ${filesModified.joinToString(", ") { escapeHtml(it.substringAfterLast('/')) }}</div>"
        } else ""

        appendHtml("""
            <div class="footer">
                <div class="footer-status" style="color:$color;">$icon $label</div>
                <div class="footer-metrics">${"%,d".format(tokensUsed)} tokens &middot; ${formatDuration(durationMs)} &middot; ${iterations} iterations</div>
                $files
            </div>
        """.trimIndent())
    }

    // ═══════════════════════════════════════════════════
    //  Public API — Streaming Text
    // ═══════════════════════════════════════════════════

    fun appendStreamToken(token: String) = runOnEdt {
        if (activeStreamId == null) {
            // Start a new streaming block
            activeStreamId = "stream-${streamIdCounter++}"
            appendHtml("""<div class="agent-msg" id="${activeStreamId}"></div>""")
        }
        // Append to the active stream element
        try {
            val streamEl = htmlDoc.getElement(activeStreamId)
            if (streamEl != null) {
                htmlDoc.insertBeforeEnd(streamEl, escapeHtml(token).replace("\n", "<br>"))
            }
        } catch (_: Exception) {
            // Fallback: just append to body
            appendHtml(escapeHtml(token).replace("\n", "<br>"))
        }
        scrollToBottom()
    }

    fun flushStreamBuffer() {
        activeStreamId = null
    }

    // ═══════════════════════════════════════════════════
    //  Public API — Tool Calls (Color-Coded Badges)
    // ═══════════════════════════════════════════════════

    fun appendToolCall(
        toolName: String, args: String = "",
        status: ToolCallStatus = ToolCallStatus.RUNNING
    ) = runOnEdt {
        flushStreamBuffer()
        val (badge, accent) = toolToBadge(toolName)
        val target = extractTarget(toolName, args)
        val statusHtml = when (status) {
            ToolCallStatus.RUNNING -> "<span class='timing'>running...</span>"
            ToolCallStatus.SUCCESS -> "<span class='timing'>&#10003;</span>"
            ToolCallStatus.FAILED -> "<span class='timing' style='color:${AgentColors.hex(AgentColors.error)}'>&#10007; failed</span>"
        }
        val argsHtml = if (args.isNotBlank() && args.length > 2) {
            "<div class='tool-detail'>${escapeHtml(args.take(300))}</div>"
        } else ""

        appendHtml("""
            <div class="tool-card" style="border-left: 3px solid $accent;">
                <div class="tool-header">$badge<span class="target">$target</span>$statusHtml</div>
                $argsHtml
            </div>
        """.trimIndent())
    }

    fun updateLastToolCall(
        status: ToolCallStatus, result: String = "", durationMs: Long = 0
    ) = runOnEdt {
        // For simplicity, append the result as a detail line
        if (result.isNotBlank()) {
            appendHtml("<div style='font-size:12px;color:${AgentColors.hex(AgentColors.secondaryText)};padding:0 10px 6px 10px;'>${escapeHtml(result.take(300))}</div>")
        }
    }

    // ═══════════════════════════════════════════════════
    //  Public API — Edit Diffs
    // ═══════════════════════════════════════════════════

    fun appendEditDiff(
        filePath: String, oldText: String, newText: String, accepted: Boolean? = null
    ) = runOnEdt {
        flushStreamBuffer()
        val statusHtml = when (accepted) {
            true -> "<span class='badge badge-ok'>APPLIED</span>"
            false -> "<span class='badge badge-fail'>REJECTED</span>"
            null -> ""
        }
        val fileName = filePath.substringAfterLast('/')
        val diffLines = buildDiffHtml(oldText.lines().take(15), newText.lines().take(15))

        appendHtml("""
            <div class="diff-card">
                <div class="diff-header">
                    <span class="badge badge-edit">EDIT</span>
                    <span class="target">${escapeHtml(fileName)}</span>
                    $statusHtml
                </div>
                $diffLines
            </div>
        """.trimIndent())
    }

    // ═══════════════════════════════════════════════════
    //  Public API — Other Blocks
    // ═══════════════════════════════════════════════════

    fun appendCodeBlock(code: String, language: String = "") = runOnEdt {
        flushStreamBuffer()
        val langLabel = if (language.isNotBlank()) "<div style='font-size:10px;color:${AgentColors.hex(AgentColors.mutedText)};padding:4px 12px 0 12px;background:${AgentColors.hex(AgentColors.codeBg)};'>${escapeHtml(language)}</div>" else ""
        appendHtml("$langLabel<div class='code-block'>${escapeHtml(code)}</div>")
    }

    fun appendStatus(message: String, type: StatusType = StatusType.INFO) = runOnEdt {
        flushStreamBuffer()
        val cls = when (type) {
            StatusType.INFO -> "status-info"
            StatusType.SUCCESS -> "status-success"
            StatusType.WARNING -> "status-warning"
            StatusType.ERROR -> "status-error"
        }
        val icon = when (type) {
            StatusType.INFO -> "&#8505;"
            StatusType.SUCCESS -> "&#10003;"
            StatusType.WARNING -> "&#9888;"
            StatusType.ERROR -> "&#10007;"
        }
        appendHtml("<div class='$cls'>$icon ${escapeHtml(message)}</div>")
    }

    fun appendError(message: String) = appendStatus(message, StatusType.ERROR)

    fun appendThinking(text: String) = runOnEdt {
        flushStreamBuffer()
        appendHtml("<div class='thinking'>${escapeHtml(text)}</div>")
    }

    fun clear() = runOnEdt {
        activeStreamId = null
        renderEmpty()
    }

    // ═══════════════════════════════════════════════════
    //  Private — HTML Helpers
    // ═══════════════════════════════════════════════════

    private fun appendHtml(html: String) {
        try {
            val body = htmlDoc.getElement("chatBody")
            if (body != null) {
                htmlDoc.insertBeforeEnd(body, html)
            }
        } catch (_: Exception) {
            // Fallback
        }
        scrollToBottom()
    }

    private fun renderEmpty() {
        editorPane.text = buildBaseHtml()
        appendHtml("""
            <div style="text-align:center;padding:60px 20px;color:${AgentColors.hex(AgentColors.mutedText)};">
                <div style="font-size:24px;margin-bottom:12px;">&#9881;</div>
                <div style="font-size:14px;font-weight:600;margin-bottom:4px;">AI Agent</div>
                <div style="font-size:12px;">Type a message below to start. The agent can read, edit, search code, and interact with enterprise tools.</div>
            </div>
        """.trimIndent())
    }

    private fun toolToBadge(toolName: String): Pair<String, String> {
        val c = AgentColors
        return when {
            toolName.contains("read") || toolName.contains("find") || toolName.contains("structure") ||
            toolName.contains("hierarchy") || toolName.contains("references") ||
            toolName.contains("diagnostics") || toolName.contains("spring") ->
                "<span class='badge badge-read'>READ</span>" to c.hex(c.accentRead)

            toolName.contains("edit") ->
                "<span class='badge badge-edit'>EDIT</span>" to c.hex(c.accentEdit)

            toolName.contains("write") || toolName.contains("create") || toolName.contains("bitbucket") ->
                "<span class='badge badge-write'>WRITE</span>" to c.hex(c.accentWrite)

            toolName.contains("command") || toolName.contains("run") ->
                "<span class='badge badge-cmd'>CMD</span>" to c.hex(c.accentCmd)

            toolName.contains("search") ->
                "<span class='badge badge-search'>SEARCH</span>" to c.hex(c.accentSearch)

            toolName.contains("jira") || toolName.contains("bamboo") || toolName.contains("sonar") ->
                "<span class='badge badge-read'>API</span>" to c.hex(c.accentRead)

            else ->
                "<span class='badge badge-read'>${toolName.take(6).uppercase()}</span>" to c.hex(c.accentRead)
        }
    }

    private fun extractTarget(toolName: String, args: String): String {
        // Try to extract file path or meaningful target from args
        val pathMatch = AgentStringUtils.JSON_FILE_PATH_REGEX.find(args)
        if (pathMatch != null) return pathMatch.groupValues[1].substringAfterLast('/')

        val queryMatch = Regex(""""query"\s*:\s*"([^"]+)"""").find(args)
        if (queryMatch != null) return "\"${queryMatch.groupValues[1].take(40)}\""

        return toolName.replace("_", " ")
    }

    private fun buildDiffHtml(oldLines: List<String>, newLines: List<String>): String {
        val sb = StringBuilder()
        for (line in oldLines) {
            sb.append("<div class='diff-rem'>- ${escapeHtml(line)}</div>")
        }
        for (line in newLines) {
            sb.append("<div class='diff-add'>+ ${escapeHtml(line)}</div>")
        }
        return sb.toString()
    }

    private fun markdownToHtml(text: String): String {
        var html = text
        html = html.replace(Regex("```(\\w*)\n([\\s\\S]*?)```")) { m ->
            "<div class='code-block'>${m.groupValues[2].trimEnd()}</div>"
        }
        html = html.replace(Regex("`([^`]+)`")) { "<span class='inline-code'>${it.groupValues[1]}</span>" }
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
        html = html.replace("\n", "<br>")
        return html
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            editorPane.caretPosition = editorPane.document.length
        }
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "%.1fs".format(ms / 1000.0)
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else SwingUtilities.invokeLater(action)
    }

    // ═══════════════════════════════════════════════════
    //  Data Models
    // ═══════════════════════════════════════════════════

    enum class ToolCallStatus { RUNNING, SUCCESS, FAILED }
    enum class StatusType { INFO, SUCCESS, WARNING, ERROR }
    enum class SessionStatus { SUCCESS, FAILED, CANCELLED }
}
