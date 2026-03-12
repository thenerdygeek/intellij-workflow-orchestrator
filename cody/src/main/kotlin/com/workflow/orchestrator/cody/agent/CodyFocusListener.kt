package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.TextDocumentIdentifier

class CodyFocusListener(private val project: Project) : FileEditorManagerListener {

    private val log = Logger.getInstance(CodyFocusListener::class.java)

    private fun isIntegratedMode(): Boolean = try {
        CodyAgentProviderService.getInstance(project).isIntegratedMode
    } catch (e: Exception) {
        false
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val newFile = event.newFile ?: return

        // Skip focus tracking when the official Cody plugin handles it
        if (isIntegratedMode()) return

        val manager = try {
            CodyAgentManager.getInstance(project)
        } catch (e: Exception) { return }

        if (manager.state.value !is CodyAgentManager.AgentState.Running) return

        val uri = newFile.url
        try {
            val server = manager.getServerOrNull() ?: return
            server.textDocumentDidFocus(TextDocumentIdentifier(uri))
            log.debug("Sent didFocus for $uri")
        } catch (e: Exception) {
            log.debug("Failed to send didFocus", e)
        }
    }
}
