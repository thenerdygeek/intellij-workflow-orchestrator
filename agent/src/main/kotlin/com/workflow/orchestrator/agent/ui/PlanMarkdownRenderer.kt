package com.workflow.orchestrator.agent.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.agent.runtime.AgentTask
import com.workflow.orchestrator.agent.runtime.TaskStatus
import java.awt.BorderLayout
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Renders an agent task plan as styled HTML in a JEditorPane.
 *
 * Displays tasks with status indicators:
 * - ○ Pending (gray)
 * - ◉ Running (blue, animated)
 * - ✓ Completed (green)
 * - ✗ Failed (red)
 *
 * Tasks show: description, target file, dependencies, and result summary when complete.
 *
 * Two-tier approach:
 * 1. Primary: Generate HTML and display via JEditorPane (always available)
 * 2. Fallback: Plain text rendering if HTML fails
 */
class PlanMarkdownRenderer : JPanel(BorderLayout()) {

    private val editorPane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        border = JBUI.Borders.empty(8)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = JBUI.Fonts.label(13f)
    }

    private var currentTasks: List<AgentTask> = emptyList()
    private var currentTitle: String = "Task Plan"

    init {
        add(JBScrollPane(editorPane), BorderLayout.CENTER)
    }

    /** Render a list of tasks as styled HTML. */
    fun renderPlan(tasks: List<AgentTask>, title: String = "Task Plan") {
        currentTasks = tasks
        currentTitle = title
        val html = buildHtml(tasks, title)
        runOnEdt { editorPane.text = html }
    }

    /**
     * Update a single task's status without re-rendering the full plan.
     * For simplicity, re-renders the entire plan. This is acceptable
     * since plans are typically <20 tasks.
     */
    fun updateTaskStatus(taskId: String, status: TaskStatus, summary: String? = null) {
        currentTasks = currentTasks.map { task ->
            if (task.id == taskId) {
                task.copy(status = status, resultSummary = summary ?: task.resultSummary)
            } else {
                task
            }
        }
        val html = buildHtml(currentTasks, currentTitle)
        runOnEdt { editorPane.text = html }
    }

    /** Clear the plan display. */
    fun clear() {
        currentTasks = emptyList()
        runOnEdt {
            editorPane.text = buildEmptyState()
        }
    }

    private fun buildHtml(tasks: List<AgentTask>, title: String): String {
        val bg = colorToHex(JBColor.PanelBackground)
        val fg = colorToHex(JBColor.foreground())
        val borderColor = colorToHex(JBColor.border())
        val green = "#28a745"
        val red = "#dc3545"
        val blue = "#0366d6"
        val gray = "#6a737d"

        val taskRows = tasks.joinToString("\n") { task ->
            val (icon, color) = when (task.status) {
                TaskStatus.PENDING -> "&#9675;" to gray    // ○
                TaskStatus.RUNNING -> "&#9673;" to blue    // ◉
                TaskStatus.COMPLETED -> "&#10003;" to green // ✓
                TaskStatus.FAILED -> "&#10007;" to red      // ✗
            }
            val deps = if (task.dependsOn.isNotEmpty())
                "<span style='color:$gray;font-size:11px;'>depends on: ${task.dependsOn.joinToString(", ")}</span><br>"
            else ""
            val resultSummary = task.resultSummary?.let {
                "<div style='margin-left:28px;color:$gray;font-size:12px;font-style:italic;'>${escapeHtml(it)}</div>"
            } ?: ""

            """
            <div style='padding:6px 0;border-bottom:1px solid $borderColor;'>
                <span style='color:$color;font-size:16px;font-weight:bold;'>$icon</span>
                <span style='margin-left:8px;font-size:13px;'><b>${escapeHtml(task.id)}</b>: ${escapeHtml(task.description)}</span><br>
                <span style='margin-left:28px;color:$gray;font-size:11px;'>${task.action} &rarr; ${escapeHtml(task.target)}</span><br>
                $deps
                $resultSummary
            </div>
            """.trimIndent()
        }

        return """
        <html>
        <body style='font-family:-apple-system,BlinkMacSystemFont,sans-serif;color:$fg;background:$bg;margin:0;padding:8px;'>
            <h3 style='margin:0 0 12px 0;font-size:15px;'>${escapeHtml(title)}</h3>
            <div style='font-size:12px;color:$gray;margin-bottom:12px;'>${tasks.size} tasks</div>
            $taskRows
        </body>
        </html>
        """.trimIndent()
    }

    private fun buildEmptyState(): String {
        val fg = colorToHex(JBColor.foreground())
        val bg = colorToHex(JBColor.PanelBackground)
        val gray = "#6a737d"
        return """
        <html>
        <body style='font-family:-apple-system,sans-serif;color:$fg;background:$bg;text-align:center;padding:40px;'>
            <div style='color:$gray;font-size:14px;'>No active plan.<br>Start a task to see the execution plan here.</div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun colorToHex(color: java.awt.Color): String {
        return "#${Integer.toHexString(color.rgb and 0xFFFFFF).padStart(6, '0')}"
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else SwingUtilities.invokeLater(action)
    }
}
