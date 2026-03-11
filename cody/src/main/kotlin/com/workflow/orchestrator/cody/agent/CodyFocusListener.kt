package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.TextDocumentIdentifier

class CodyFocusListener(private val project: Project) : FileEditorManagerListener {

    private val log = Logger.getInstance(CodyFocusListener::class.java)

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val newFile = event.newFile ?: return
        val manager = try {
            CodyAgentManager.getInstance(project)
        } catch (e: Exception) { return }

        if (manager.state.value !is CodyAgentManager.AgentState.Running) return

        val uri = "file://${newFile.path}"
        try {
            val server = manager.getServerOrNull() ?: return
            server.textDocumentDidFocus(TextDocumentIdentifier(uri))
            log.debug("Sent didFocus for $uri")
        } catch (e: Exception) {
            log.debug("Failed to send didFocus", e)
        }
    }
}
