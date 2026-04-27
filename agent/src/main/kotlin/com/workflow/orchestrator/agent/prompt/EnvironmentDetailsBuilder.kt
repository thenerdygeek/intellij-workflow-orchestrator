package com.workflow.orchestrator.agent.prompt

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.workflow.orchestrator.agent.loop.ContextManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds lightweight environment_details appended to every user message.
 * Port of Cline's getEnvironmentDetails() — only instant, local data.
 * No network calls, no heavy IO.
 */
object EnvironmentDetailsBuilder {

    suspend fun build(
        project: Project,
        planModeEnabled: Boolean,
        contextManager: ContextManager?,
        activeTicketId: String? = null,
        activeTicketSummary: String? = null,
        defaultTargetBranch: String? = null,
        repoBranches: List<Pair<String, String>> = emptyList(),
    ): String {
        val sb = StringBuilder()
        sb.appendLine("<environment_details>")

        // 1. Current Mode
        sb.appendLine("# Current Mode")
        sb.appendLine(if (planModeEnabled) "PLAN MODE" else "ACT MODE")
        sb.appendLine()

        // 2. Current Time
        sb.appendCurrentTime()

        // 3. VCS State (per-repo branches + dirty files)
        sb.appendVcsState(project, defaultTargetBranch, repoBranches)

        // 4. Active Editor (file, cursor, selection range)
        sb.appendActiveEditor(project)

        // 5. Open Tabs
        sb.appendOpenTabs(project)

        // 6. Context Window Usage
        sb.appendContextUsage(contextManager)

        // 7. Active Plan
        val planPath = contextManager?.getActivePlanPath()
        if (planPath != null) {
            sb.appendLine("# Active Plan")
            sb.appendLine(planPath)
            sb.appendLine()
        }

        // 8. Workflow Context (Phase 5 T17) — canonical state from WorkflowContextService.
        sb.appendWorkflowContext(project)

        // 9. Active Ticket (legacy — passed in by caller from :jira ActiveTicketService;
        //    coexists with §8 during the §5.3 dual-write window).
        if (!activeTicketId.isNullOrBlank()) {
            sb.appendLine("# Active Ticket")
            val summary = if (!activeTicketSummary.isNullOrBlank()) " — $activeTicketSummary" else ""
            sb.appendLine("$activeTicketId$summary")
            sb.appendLine()
        }

        sb.append("</environment_details>")
        return sb.toString()
    }

    /**
     * Phase 5 T17: appends a `<workflow_context>` block with the canonical
     * workflow state (active ticket, branch, repo, focused PR, interaction mode).
     * Block omitted when all relevant fields are null. Spec §6.1.
     */
    private fun StringBuilder.appendWorkflowContext(project: Project) {
        val service = try {
            com.workflow.orchestrator.core.workflow.WorkflowContextService.getInstance(project)
        } catch (_: Exception) { return }
        val s = try { service.state.value } catch (_: Exception) { return }
        if (s.activeTicket == null && s.focusPr == null && s.activeBranch == null &&
            s.activeRepo == null && s.editorModule == null && s.projectModules.isEmpty()) return

        appendLine("<workflow_context>")
        s.activeTicket?.let { appendLine("Active ticket: ${it.key} — \"${it.summary}\"") }
        s.activeBranch?.let { appendLine("Active branch: $it") }
        s.activeRepo?.let { appendLine("Active repo: ${it.name}") }
        s.editorModule?.let { appendLine("Editor module: ${it.name}") }
        if (s.projectModules.isNotEmpty()) {
            appendLine("Project modules: ${s.projectModules.joinToString(", ") { it.name }}")
        }
        s.focusPr?.let { appendLine("Focused PR: #${it.prId} (${it.fromBranch} -> ${it.toBranch})") }
        appendLine("Interaction mode: ${s.interactionMode}")
        appendLine("</workflow_context>")
        appendLine()
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

    /**
     * Editor APIs (`caretModel`, `selectionModel`, `document`) are EDT-affine — they
     * must be touched on the EDT. We hop to `Dispatchers.EDT` and acquire the read
     * lock via `readActionBlocking` (the EDT-callable suspend variant).
     */
    private suspend fun StringBuilder.appendActiveEditor(project: Project) {
        try {
            val editorData = withContext(Dispatchers.EDT) {
                readActionBlocking {
                    val fem = FileEditorManager.getInstance(project)
                    val editor = fem.selectedTextEditor ?: return@readActionBlocking null
                    val file = editor.virtualFile ?: return@readActionBlocking null
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

    /**
     * `FileEditorManager.openFiles` is an index-free snapshot — plain `readAction` is
     * sufficient (no EDT requirement, no smart-mode requirement).
     */
    private suspend fun StringBuilder.appendOpenTabs(project: Project) {
        try {
            val tabs = readAction {
                val fem = FileEditorManager.getInstance(project)
                val files = fem.openFiles
                if (files.isEmpty()) return@readAction null
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
        defaultTargetBranch: String?,
        repoBranches: List<Pair<String, String>>
    ) {
        // Branches block — flat per-repo list, no "primary" framing. The agent picks the
        // right repo per-call from user-action signals (checked changes, focused PR, file
        // arg) — environment_details only enumerates what is currently checked out where.
        if (repoBranches.isNotEmpty()) {
            val target = if (defaultTargetBranch != null) " (default target: $defaultTargetBranch)" else ""
            if (repoBranches.size == 1) {
                // Single-repo project: keep the original "<branch>" form for compactness.
                val (_, branch) = repoBranches.single()
                appendLine("# Current Branch")
                appendLine("$branch$target")
            } else {
                appendLine("# Branches$target")
                for ((label, branch) in repoBranches) {
                    appendLine("- $label: $branch")
                }
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
