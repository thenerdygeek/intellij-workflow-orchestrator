package com.workflow.orchestrator.agent.prompt

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.agent.loop.ContextManager
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds lightweight environment_details appended to every user message.
 * Port of Cline's getEnvironmentDetails() — only instant, local data.
 * No network calls, no heavy IO.
 */
object EnvironmentDetailsBuilder {

    fun build(
        project: Project,
        planModeEnabled: Boolean,
        contextManager: ContextManager?,
        activeTicketId: String? = null,
        activeTicketSummary: String? = null,
        currentBranch: String? = null,
        defaultTargetBranch: String? = null,
        primaryRepoLabel: String? = null,
        otherRepoBranches: List<Pair<String, String>> = emptyList(),
    ): String {
        return buildString {
            appendLine("<environment_details>")

            // 1. Current Mode
            appendLine("# Current Mode")
            appendLine(if (planModeEnabled) "PLAN MODE" else "ACT MODE")
            appendLine()

            // 2. Current Time
            appendCurrentTime()

            // 3. VCS State (branch + dirty files)
            appendVcsState(project, currentBranch, defaultTargetBranch, primaryRepoLabel, otherRepoBranches)

            // 4. Active Editor (file, cursor, selection range)
            appendActiveEditor(project)

            // 5. Open Tabs
            appendOpenTabs(project)

            // 6. Context Window Usage
            appendContextUsage(contextManager)

            // 7. Active Plan
            val planPath = contextManager?.getActivePlanPath()
            if (planPath != null) {
                appendLine("# Active Plan")
                appendLine(planPath)
                appendLine()
            }

            // 8. Active Ticket
            if (!activeTicketId.isNullOrBlank()) {
                appendLine("# Active Ticket")
                val summary = if (!activeTicketSummary.isNullOrBlank()) " — $activeTicketSummary" else ""
                appendLine("$activeTicketId$summary")
                appendLine()
            }

            append("</environment_details>")
        }
    }

    private fun StringBuilder.appendCurrentTime() {
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val offset = zone.rules.getOffset(now)
        val formatted = now.format(DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a"))
        appendLine("# Current Time")
        appendLine("$formatted (${zone.id}, UTC${offset})")
        appendLine()
    }

    private fun StringBuilder.appendActiveEditor(project: Project) {
        try {
            val editorData = ReadAction.compute<EditorSnapshot?, Exception> {
                val fem = FileEditorManager.getInstance(project)
                val editor = fem.selectedTextEditor ?: return@compute null
                val file = editor.virtualFile ?: return@compute null
                val basePath = project.basePath
                val relativePath = if (basePath != null && file.path.startsWith(basePath))
                    file.path.removePrefix("$basePath/") else file.path

                val caretLine = editor.caretModel.logicalPosition.line + 1  // 1-based
                val caretCol = editor.caretModel.logicalPosition.column + 1

                val selection = editor.selectionModel
                val hasSelection = selection.hasSelection()
                val startLine = if (hasSelection)
                    editor.document.getLineNumber(selection.selectionStart) + 1 else null
                val endLine = if (hasSelection)
                    editor.document.getLineNumber(selection.selectionEnd) + 1 else null

                EditorSnapshot(relativePath, caretLine, caretCol, startLine, endLine)
            }

            if (editorData != null) {
                appendLine("# Active Editor")
                appendLine("File: ${editorData.filePath}")
                appendLine("Cursor: line ${editorData.caretLine}, column ${editorData.caretCol}")
                if (editorData.selStartLine != null && editorData.selEndLine != null) {
                    val lineCount = editorData.selEndLine - editorData.selStartLine + 1
                    appendLine("Selected: lines ${editorData.selStartLine}-${editorData.selEndLine} ($lineCount lines)")
                }
                appendLine()
            }
        } catch (_: Exception) { /* EDT/read action not available */ }
    }

    private fun StringBuilder.appendOpenTabs(project: Project) {
        try {
            val tabs = ReadAction.compute<List<String>?, Exception> {
                val fem = FileEditorManager.getInstance(project)
                val files = fem.openFiles
                if (files.isEmpty()) return@compute null
                val basePath = project.basePath
                files.take(20).map { vf ->
                    val rel = if (basePath != null && vf.path.startsWith(basePath))
                        vf.path.removePrefix("$basePath/") else vf.path
                    rel
                }
            }
            if (tabs != null) {
                appendLine("# Open Tabs")
                tabs.forEach { appendLine(it) }
                appendLine()
            }
        } catch (_: Exception) {}
    }

    private fun StringBuilder.appendContextUsage(contextManager: ContextManager?) {
        if (contextManager == null) return
        try {
            val pct = contextManager.utilizationPercent()
            if (pct > 0) {
                val maxTokens = contextManager.maxInputTokens
                appendLine("# Context Window Usage")
                appendLine("${String.format("%.0f", pct)}% of ${maxTokens / 1000}K tokens used")
                appendLine()
            }
        } catch (_: Exception) {}
    }

    private fun StringBuilder.appendVcsState(
        project: Project,
        currentBranch: String?,
        defaultTargetBranch: String?,
        primaryRepoLabel: String?,
        otherRepoBranches: List<Pair<String, String>>
    ) {
        // Branch line — shown only when available.
        // Multi-repo projects: label the primary branch with its repo identity and list the
        // other repos below, so the LLM never confuses modules or picks the wrong branch for
        // tools like sonar(action=local_analysis, branch=...).
        if (currentBranch != null) {
            appendLine("# Current Branch")
            val target = if (defaultTargetBranch != null) " (target: $defaultTargetBranch)" else ""
            if (otherRepoBranches.isNotEmpty() && primaryRepoLabel != null) {
                appendLine("$primaryRepoLabel: $currentBranch$target")
                appendLine("Other repositories in project:")
                for ((label, branch) in otherRepoBranches) {
                    appendLine("- $label: $branch")
                }
            } else {
                appendLine("$currentBranch$target")
            }
            appendLine()
        }

        // Dirty files — instant from ChangeListManager, capped at 20
        try {
            val clm = ChangeListManager.getInstance(project)
            val all = clm.allChanges
            if (all.isNotEmpty()) {
                val changes = all.take(20).map { change ->
                    val type = when (change.type) {
                        com.intellij.openapi.vcs.changes.Change.Type.NEW -> "added"
                        com.intellij.openapi.vcs.changes.Change.Type.DELETED -> "deleted"
                        com.intellij.openapi.vcs.changes.Change.Type.MOVED -> "moved"
                        else -> "modified"
                    }
                    val path = change.virtualFile?.path
                        ?: change.afterRevision?.file?.path
                        ?: change.beforeRevision?.file?.path
                        ?: "unknown"
                    val basePath = project.basePath
                    val rel = if (basePath != null && path.startsWith(basePath))
                        path.removePrefix("$basePath/") else path
                    rel to type
                }
                val count = changes.size
                val plusSign = if (count == 20) "+" else ""
                appendLine("# Uncommitted Changes ($count$plusSign)")
                changes.forEach { (path, type) -> appendLine("[$type] $path") }
                appendLine()
            }
        } catch (_: Exception) { /* ChangeListManager not available in tests */ }
    }

    private data class EditorSnapshot(
        val filePath: String,
        val caretLine: Int,
        val caretCol: Int,
        val selStartLine: Int?,
        val selEndLine: Int?
    )
}
