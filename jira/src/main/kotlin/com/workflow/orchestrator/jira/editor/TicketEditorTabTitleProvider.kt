package com.workflow.orchestrator.jira.editor

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Appends the active Jira ticket ID to editor tab titles for modified files.
 * Only shows the badge on files that are in the current VCS changelist,
 * so unchanged files keep their normal tab title.
 */
class TicketEditorTabTitleProvider : EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        val ticketId = PluginSettings.getInstance(project).state.activeTicketId
        if (ticketId.isNullOrBlank()) return null

        // Only badge files that are modified (in changelist)
        val changeListManager = ChangeListManager.getInstance(project)
        val isModified = changeListManager.getChange(file) != null
        if (!isModified) return null

        return TicketTabTitleHelper.generateTitle(file.presentableName, ticketId)
    }
}

/** Pure helper — easily testable without IntelliJ dependencies. */
object TicketTabTitleHelper {
    fun generateTitle(fileName: String, ticketId: String?): String? {
        if (ticketId.isNullOrBlank()) return null
        return "$fileName [$ticketId]"
    }
}
