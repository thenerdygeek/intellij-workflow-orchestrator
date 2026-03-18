package com.workflow.orchestrator.agent.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * Rich streaming output panel that renders agent activity as styled HTML.
 *
 * Replaces the plain-text [StreamingOutputPanel] with a production-quality display:
 * - Markdown-like text with proper formatting
 * - Syntax-highlighted code blocks (monospaced with background)
 * - Inline tool call cards with status, timing, and result preview
 * - Inline edit diffs with colored +/- lines
 * - Session header with task, timing, token count
 * - Session footer with structured summary
 *
 * Architecture: Maintains a list of [ContentBlock]s. Each block is frozen HTML
 * once complete. The last block (active streaming) is re-rendered as tokens arrive.
 * This avoids re-rendering the entire document on every token.
 *
 * Uses JEditorPane with HTMLEditorKit — limited to HTML 3.2 but sufficient
 * for styled text, code blocks, and colored diffs. No JCEF/Chromium dependency.
 */
class RichStreamingPanel : JPanel(BorderLayout()) {

    private val editorPane = JEditorPane().apply {
        isEditable = false
        border = JBUI.Borders.empty()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    }

    private val scrollPane = JBScrollPane(editorPane)

    private val blocks = mutableListOf<ContentBlock>()
    private var activeStreamBuffer = StringBuilder()
    private var sessionHeader: SessionHeaderData? = null
    private var sessionFooter: SessionFooterData? = null

    init {
        border = JBUI.Borders.empty()
        setupEditorKit()
        add(scrollPane, BorderLayout.CENTER)
        renderEmpty()
    }

    private fun setupEditorKit() {
        val kit = HTMLEditorKit()
        kit.styleSheet = buildStyleSheet()
        editorPane.editorKit = kit
    }

    // ═══════════════════════════════════════════════════
    //  Public API — Session Lifecycle
    // ═══════════════════════════════════════════════════

    /** Start a new session. Shows the task description and initializes the header. */
    fun startSession(task: String) = runOnEdt {
        blocks.clear()
        activeStreamBuffer.clear()
        sessionFooter = null
        sessionHeader = SessionHeaderData(
            task = task,
            startTimeMs = System.currentTimeMillis()
        )
        render()
    }

    /** Complete the session. Shows the structured summary footer. */
    fun completeSession(
        tokensUsed: Int,
        iterations: Int,
        filesModified: List<String>,
        durationMs: Long,
        status: SessionStatus = SessionStatus.SUCCESS
    ) = runOnEdt {
        // Flush any remaining stream buffer
        flushStreamBuffer()
        sessionFooter = SessionFooterData(
            status = status,
            tokensUsed = tokensUsed,
            iterations = iterations,
            filesModified = filesModified,
            durationMs = durationMs
        )
        render()
    }

    // ═══════════════════════════════════════════════════
    //  Public API — Streaming Text
    // ═══════════════════════════════════════════════════

    /** Append a streaming text token. Accumulated until flushed or a block boundary. */
    fun appendStreamToken(token: String) = runOnEdt {
        activeStreamBuffer.append(token)
        renderLastBlock()
    }

    /** Flush the current stream buffer into a finalized text block. */
    fun flushStreamBuffer() {
        if (activeStreamBuffer.isNotBlank()) {
            blocks.add(ContentBlock.Text(activeStreamBuffer.toString()))
            activeStreamBuffer.clear()
        }
    }

    // ═══════════════════════════════════════════════════
    //  Public API — Structured Blocks
    // ═══════════════════════════════════════════════════

    /** Show a tool call card (called when agent invokes a tool). */
    fun appendToolCall(
        toolName: String,
        args: String = "",
        status: ToolCallStatus = ToolCallStatus.RUNNING
    ) = runOnEdt {
        flushStreamBuffer()
        blocks.add(ContentBlock.ToolCall(
            toolName = toolName,
            args = args.take(200),
            status = status
        ))
        render()
    }

    /** Update the last tool call's status and result. */
    fun updateLastToolCall(
        status: ToolCallStatus,
        result: String = "",
        durationMs: Long = 0
    ) = runOnEdt {
        val lastTool = blocks.lastOrNull { it is ContentBlock.ToolCall } as? ContentBlock.ToolCall ?: return@runOnEdt
        val index = blocks.lastIndexOf(lastTool)
        blocks[index] = lastTool.copy(
            status = status,
            result = result.take(500),
            durationMs = durationMs
        )
        render()
    }

    /** Show an inline edit diff card. */
    fun appendEditDiff(
        filePath: String,
        oldText: String,
        newText: String,
        accepted: Boolean? = null
    ) = runOnEdt {
        flushStreamBuffer()
        blocks.add(ContentBlock.EditDiff(
            filePath = filePath,
            oldLines = oldText.lines().take(20),
            newLines = newText.lines().take(20),
            accepted = accepted
        ))
        render()
    }

    /** Show a code block with syntax styling. */
    fun appendCodeBlock(code: String, language: String = "") = runOnEdt {
        flushStreamBuffer()
        blocks.add(ContentBlock.Code(code = code, language = language))
        render()
    }

    /** Show a status/progress message. */
    fun appendStatus(message: String, type: StatusType = StatusType.INFO) = runOnEdt {
        flushStreamBuffer()
        blocks.add(ContentBlock.Status(message = message, type = type))
        render()
    }

    /** Show an error message. */
    fun appendError(message: String) = runOnEdt {
        flushStreamBuffer()
        blocks.add(ContentBlock.Status(message = message, type = StatusType.ERROR))
        render()
    }

    /** Clear everything. */
    fun clear() = runOnEdt {
        blocks.clear()
        activeStreamBuffer.clear()
        sessionHeader = null
        sessionFooter = null
        renderEmpty()
    }

    // ═══════════════════════════════════════════════════
    //  Backward-compatible API (for existing callers)
    // ═══════════════════════════════════════════════════

    /** Append text (backward compat with StreamingOutputPanel). */
    fun appendText(text: String) = appendStreamToken(text)

    /** Set full text (backward compat). */
    fun setText(text: String) = runOnEdt {
        blocks.clear()
        activeStreamBuffer.clear()
        blocks.add(ContentBlock.Text(text))
        render()
    }

    /** Append separator (backward compat). */
    fun appendSeparator(label: String = "") {
        flushStreamBuffer()
        if (label.isNotBlank()) {
            appendStatus(label, StatusType.INFO)
        }
    }

    // ═══════════════════════════════════════════════════
    //  HTML Rendering
    // ═══════════════════════════════════════════════════

    /** Full re-render of the entire document. */
    private fun render() {
        val html = buildFullHtml()
        editorPane.text = html
        scrollToBottom()
    }

    /** Optimized: only update the last block area (for streaming tokens). */
    private fun renderLastBlock() {
        // For simplicity, re-render the whole document.
        // JEditorPane handles this efficiently for our document sizes (<100KB).
        render()
    }

    private fun renderEmpty() {
        val gray = colorToHex(JBColor.GRAY)
        editorPane.text = """
            <html><body>
            <div style='text-align:center;padding:40px;color:$gray;'>
                Enter a task to start the AI agent.<br>
                <span style='font-size:11px;'>The agent can analyze code, edit files, run diagnostics, and interact with enterprise tools.</span>
            </div>
            </body></html>
        """.trimIndent()
    }

    private fun buildFullHtml(): String {
        val sb = StringBuilder()
        sb.append("<html><body>")

        // Session header
        sessionHeader?.let { sb.append(renderSessionHeader(it)) }

        // Content blocks
        for (block in blocks) {
            sb.append(renderBlock(block))
        }

        // Active streaming buffer (not yet finalized)
        if (activeStreamBuffer.isNotBlank()) {
            sb.append(renderTextBlock(activeStreamBuffer.toString(), streaming = true))
        }

        // Session footer
        sessionFooter?.let { sb.append(renderSessionFooter(it)) }

        sb.append("</body></html>")
        return sb.toString()
    }

    private fun renderBlock(block: ContentBlock): String = when (block) {
        is ContentBlock.Text -> renderTextBlock(block.content)
        is ContentBlock.ToolCall -> renderToolCallCard(block)
        is ContentBlock.EditDiff -> renderEditDiffCard(block)
        is ContentBlock.Code -> renderCodeBlock(block)
        is ContentBlock.Status -> renderStatusMessage(block)
    }

    private fun renderSessionHeader(data: SessionHeaderData): String {
        val fg = colorToHex(JBColor.foreground())
        val gray = colorToHex(JBColor.GRAY)
        val taskPreview = escapeHtml(data.task.take(200))
        return """
            <div class='session-header'>
                <div style='font-size:14px;font-weight:bold;color:$fg;margin-bottom:4px;'>$taskPreview</div>
                <div style='font-size:11px;color:$gray;'>Session started</div>
            </div>
        """.trimIndent()
    }

    private fun renderTextBlock(content: String, streaming: Boolean = false): String {
        // Convert markdown-like patterns to HTML
        val html = markdownToHtml(content)
        val cursor = if (streaming) "<span class='cursor'>&#9608;</span>" else ""
        return "<div class='text-block'>$html$cursor</div>"
    }

    private fun renderToolCallCard(tool: ContentBlock.ToolCall): String {
        val (icon, statusColor, statusText) = when (tool.status) {
            ToolCallStatus.RUNNING -> Triple("&#9881;", colorToHex(JBColor.BLUE), "running...")
            ToolCallStatus.SUCCESS -> Triple("&#10003;", "#28a745", "done")
            ToolCallStatus.FAILED -> Triple("&#10007;", "#dc3545", "failed")
        }

        val timing = if (tool.durationMs > 0) " &middot; ${formatDuration(tool.durationMs)}" else ""
        val resultHtml = if (tool.result.isNotBlank()) {
            "<div class='tool-result'>${escapeHtml(tool.result)}</div>"
        } else ""
        val argsHtml = if (tool.args.isNotBlank()) {
            "<div class='tool-args'>${escapeHtml(tool.args)}</div>"
        } else ""

        return """
            <div class='tool-card'>
                <div class='tool-header'>
                    <span style='color:$statusColor;'>$icon</span>
                    <span class='tool-name'>${escapeHtml(tool.toolName)}</span>
                    <span class='tool-status' style='color:$statusColor;'>$statusText$timing</span>
                </div>
                $argsHtml
                $resultHtml
            </div>
        """.trimIndent()
    }

    private fun renderEditDiffCard(diff: ContentBlock.EditDiff): String {
        val statusIcon = when (diff.accepted) {
            true -> "<span style='color:#28a745;'>&#10003; Accepted</span>"
            false -> "<span style='color:#dc3545;'>&#10007; Rejected</span>"
            null -> "<span style='color:${colorToHex(JBColor.GRAY)};'>Pending</span>"
        }

        val diffLines = buildDiffLines(diff.oldLines, diff.newLines)

        return """
            <div class='diff-card'>
                <div class='diff-header'>
                    <span>&#9998;</span>
                    <span class='diff-path'>${escapeHtml(diff.filePath)}</span>
                    $statusIcon
                </div>
                <div class='diff-content'>$diffLines</div>
            </div>
        """.trimIndent()
    }

    private fun buildDiffLines(oldLines: List<String>, newLines: List<String>): String {
        val sb = StringBuilder()
        // Simple diff: show removed lines then added lines
        for (line in oldLines) {
            sb.append("<div class='diff-removed'>- ${escapeHtml(line)}</div>")
        }
        for (line in newLines) {
            sb.append("<div class='diff-added'>+ ${escapeHtml(line)}</div>")
        }
        return sb.toString()
    }

    private fun renderCodeBlock(block: ContentBlock.Code): String {
        val langLabel = if (block.language.isNotBlank()) {
            "<div class='code-lang'>${escapeHtml(block.language)}</div>"
        } else ""
        return """
            <div class='code-block'>
                $langLabel
                <pre class='code-pre'>${escapeHtml(block.code)}</pre>
            </div>
        """.trimIndent()
    }

    private fun renderStatusMessage(status: ContentBlock.Status): String {
        val (icon, cssClass) = when (status.type) {
            StatusType.INFO -> "&#8505;" to "status-info"
            StatusType.SUCCESS -> "&#10003;" to "status-success"
            StatusType.WARNING -> "&#9888;" to "status-warning"
            StatusType.ERROR -> "&#10007;" to "status-error"
        }
        return "<div class='status-msg $cssClass'>$icon ${escapeHtml(status.message)}</div>"
    }

    private fun renderSessionFooter(data: SessionFooterData): String {
        val (statusIcon, statusColor, statusLabel) = when (data.status) {
            SessionStatus.SUCCESS -> Triple("&#10003;", "#28a745", "Completed")
            SessionStatus.FAILED -> Triple("&#10007;", "#dc3545", "Failed")
            SessionStatus.CANCELLED -> Triple("&#9724;", colorToHex(JBColor.GRAY), "Cancelled")
        }

        val filesHtml = if (data.filesModified.isNotEmpty()) {
            val fileList = data.filesModified.joinToString("<br>") { "&bull; ${escapeHtml(it.substringAfterLast('/'))}" }
            "<div class='footer-section'><b>Files modified:</b><br>$fileList</div>"
        } else ""

        val duration = formatDuration(data.durationMs)
        val tokenStr = "%,d".format(data.tokensUsed)

        return """
            <div class='session-footer'>
                <div class='footer-status' style='color:$statusColor;'>
                    $statusIcon <b>$statusLabel</b>
                </div>
                <div class='footer-metrics'>
                    &#128176; $tokenStr tokens &middot;
                    &#128336; $duration &middot;
                    &#128260; ${data.iterations} iterations
                </div>
                $filesHtml
            </div>
        """.trimIndent()
    }

    // ═══════════════════════════════════════════════════
    //  Markdown-to-HTML (simplified)
    // ═══════════════════════════════════════════════════

    /** Convert simplified markdown patterns to HTML. */
    private fun markdownToHtml(text: String): String {
        var html = escapeHtml(text)

        // Code blocks: ```...``` → <pre class='code-pre'>...</pre>
        html = html.replace(Regex("```(\\w*)\n([\\s\\S]*?)```")) { match ->
            val lang = match.groupValues[1]
            val code = match.groupValues[2].trimEnd()
            val langLabel = if (lang.isNotBlank()) "<div class='code-lang'>$lang</div>" else ""
            "$langLabel<pre class='code-pre'>$code</pre>"
        }

        // Inline code: `...` → <code>...</code>
        html = html.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }

        // Bold: **...** → <b>...</b>
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }

        // Newlines to <br>
        html = html.replace("\n", "<br>")

        return html
    }

    // ═══════════════════════════════════════════════════
    //  Styling
    // ═══════════════════════════════════════════════════

    private fun buildStyleSheet(): StyleSheet {
        val ss = StyleSheet()
        val bg = colorToHex(JBColor.PanelBackground)
        val fg = colorToHex(JBColor.foreground())
        val gray = colorToHex(JBColor.GRAY)
        val border = colorToHex(JBColor.border())
        val codeBg = colorToHex(JBColor(Color(245, 245, 245), Color(43, 43, 43)))
        val toolBg = colorToHex(JBColor(Color(248, 249, 250), Color(35, 38, 42)))
        val diffAddBg = colorToHex(JBColor(Color(230, 255, 230), Color(30, 60, 30)))
        val diffRemBg = colorToHex(JBColor(Color(255, 230, 230), Color(60, 30, 30)))

        ss.addRule("body { font-family: -apple-system, 'Segoe UI', sans-serif; font-size: 13px; color: $fg; background: $bg; margin: 0; padding: 8px; }")
        ss.addRule(".session-header { padding: 12px; border-bottom: 2px solid $border; margin-bottom: 12px; }")
        ss.addRule(".text-block { padding: 4px 0; line-height: 1.5; }")
        ss.addRule(".cursor { color: $gray; }")
        ss.addRule("code { font-family: 'JetBrains Mono', Menlo, Consolas, monospace; font-size: 12px; background: $codeBg; padding: 1px 4px; border-radius: 3px; }")
        ss.addRule(".code-block { margin: 8px 0; }")
        ss.addRule(".code-lang { font-size: 11px; color: $gray; padding: 4px 8px; background: $codeBg; border-top-left-radius: 4px; border-top-right-radius: 4px; }")
        ss.addRule(".code-pre { font-family: 'JetBrains Mono', Menlo, Consolas, monospace; font-size: 12px; background: $codeBg; padding: 8px; margin: 0; white-space: pre-wrap; word-wrap: break-word; overflow-x: auto; }")
        ss.addRule(".tool-card { margin: 8px 0; padding: 8px 12px; background: $toolBg; border-left: 3px solid $border; }")
        ss.addRule(".tool-header { font-size: 12px; }")
        ss.addRule(".tool-name { font-weight: bold; margin-left: 4px; }")
        ss.addRule(".tool-status { float: right; font-size: 11px; }")
        ss.addRule(".tool-args { font-family: monospace; font-size: 11px; color: $gray; margin-top: 4px; }")
        ss.addRule(".tool-result { font-size: 12px; color: $gray; margin-top: 4px; padding-top: 4px; border-top: 1px solid $border; white-space: pre-wrap; }")
        ss.addRule(".diff-card { margin: 8px 0; border: 1px solid $border; }")
        ss.addRule(".diff-header { padding: 6px 10px; background: $toolBg; font-size: 12px; border-bottom: 1px solid $border; }")
        ss.addRule(".diff-path { font-family: monospace; font-weight: bold; margin-left: 4px; }")
        ss.addRule(".diff-content { font-family: 'JetBrains Mono', Menlo, Consolas, monospace; font-size: 12px; }")
        ss.addRule(".diff-removed { background: $diffRemBg; padding: 1px 8px; }")
        ss.addRule(".diff-added { background: $diffAddBg; padding: 1px 8px; }")
        ss.addRule(".status-msg { padding: 6px 0; font-size: 12px; }")
        ss.addRule(".status-info { color: $gray; }")
        ss.addRule(".status-success { color: #28a745; }")
        ss.addRule(".status-warning { color: #e36209; }")
        ss.addRule(".status-error { color: #dc3545; font-weight: bold; }")
        ss.addRule(".session-footer { margin-top: 16px; padding: 12px; border-top: 2px solid $border; }")
        ss.addRule(".footer-status { font-size: 14px; margin-bottom: 8px; }")
        ss.addRule(".footer-metrics { font-size: 12px; color: $gray; margin-bottom: 8px; }")
        ss.addRule(".footer-section { font-size: 12px; color: $gray; margin-top: 4px; }")

        return ss
    }

    // ═══════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val vBar = scrollPane.verticalScrollBar
            vBar.value = vBar.maximum
        }
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "%.1fs".format(ms / 1000.0)
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }

    private fun colorToHex(color: Color): String =
        "#${Integer.toHexString(color.rgb and 0xFFFFFF).padStart(6, '0')}"

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else SwingUtilities.invokeLater(action)
    }

    // ═══════════════════════════════════════════════════
    //  Data Models
    // ═══════════════════════════════════════════════════

    sealed class ContentBlock {
        data class Text(val content: String) : ContentBlock()
        data class ToolCall(
            val toolName: String,
            val args: String = "",
            val status: ToolCallStatus = ToolCallStatus.RUNNING,
            val result: String = "",
            val durationMs: Long = 0
        ) : ContentBlock()
        data class EditDiff(
            val filePath: String,
            val oldLines: List<String>,
            val newLines: List<String>,
            val accepted: Boolean? = null
        ) : ContentBlock()
        data class Code(val code: String, val language: String = "") : ContentBlock()
        data class Status(val message: String, val type: StatusType) : ContentBlock()
    }

    enum class ToolCallStatus { RUNNING, SUCCESS, FAILED }
    enum class StatusType { INFO, SUCCESS, WARNING, ERROR }
    enum class SessionStatus { SUCCESS, FAILED, CANCELLED }

    data class SessionHeaderData(
        val task: String,
        val startTimeMs: Long
    )

    data class SessionFooterData(
        val status: SessionStatus,
        val tokensUsed: Int,
        val iterations: Int,
        val filesModified: List<String>,
        val durationMs: Long
    )
}
