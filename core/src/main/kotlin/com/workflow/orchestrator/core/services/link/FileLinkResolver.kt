package com.workflow.orchestrator.core.services.link

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.core.model.ChatLink
import com.workflow.orchestrator.core.model.LinkResolution
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileLinkResolver(private val project: Project) {

    fun resolve(link: ChatLink.FileLink): LinkResolution {
        val label = buildString {
            append(link.path.substringAfterLast('/'))
            if (link.line != null) {
                append(':')
                append(link.line)
                if (link.endLine != null && link.endLine != link.line) {
                    append('-')
                    append(link.endLine)
                }
            }
        }
        val description = when {
            link.line == null -> "Opens file"
            link.endLine != null && link.endLine != link.line ->
                "Opens file at lines ${link.line}–${link.endLine}"
            else -> "Opens file at line ${link.line}"
        }
        return LinkResolution(
            kind = LinkResolution.Kind.FILE,
            raw = link.raw,
            displayLabel = label,
            targetDescription = description,
        )
    }

    suspend fun open(link: ChatLink.FileLink) {
        val vfile = findVirtualFile(link)
        if (vfile == null) {
            notifyMissing("File not found: ${link.path}")
            return
        }
        withContext(Dispatchers.EDT) {
            val targetLine = (link.line ?: 1) - 1
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, vfile, targetLine, 0), true)
            if (editor != null && link.line != null && link.endLine != null && link.endLine != link.line) {
                val doc = editor.document
                val startLineIdx = (link.line - 1).coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
                val endLineIdx = (link.endLine - 1).coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
                val startOffset = doc.getLineStartOffset(startLineIdx)
                val endOffset = doc.getLineEndOffset(endLineIdx)
                editor.selectionModel.setSelection(startOffset, endOffset)
            }
        }
    }

    private suspend fun findVirtualFile(link: ChatLink.FileLink): VirtualFile? {
        val basePath = project.basePath
        if (basePath != null) {
            val candidate = LocalFileSystem.getInstance().findFileByPath("$basePath/${link.path}")
            if (candidate != null && candidate.isValid) return candidate
        }
        val name = link.path.substringAfterLast('/')
        if (name.isEmpty()) return null
        return readAction {
            FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
                .firstOrNull()
        }
    }

    private fun notifyMissing(message: String) {
        WorkflowNotificationService.getInstance(project)
            .notifyWarning(WorkflowNotificationService.GROUP_AGENT, "Link", message)
    }
}
