package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the IntelliJ native file chooser (multi-select, all files) and returns
 * the selected files. Used instead of the webview's HTML <input type=file>,
 * which JCEF does not bridge to an OS dialog.
 *
 * `FileChooser.chooseFiles` is a modal EDT API, so [choose] is a suspend fun
 * that hops to `Dispatchers.EDT`. Do NOT call it via `invokeAndWait` from
 * `Dispatchers.IO` — that blocks an IO pool thread on the EDT queue and risks a
 * deadlock (review fix B4).
 */
class AttachmentPicker(private val project: Project) {
    suspend fun choose(): List<VirtualFile> = withContext(Dispatchers.EDT) {
        val descriptor = FileChooserDescriptor(true, false, true, true, false, true)
            .withTitle("Attach files")
            .withDescription("Select images or documents to attach to the chat")
        FileChooser.chooseFiles(descriptor, project, null).toList()
    }
}
