package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.ProtocolTextDocument
import java.util.Timer
import java.util.TimerTask

class CodyDocumentChangeListener(
    private val project: Project,
    private val fileUri: String
) : DocumentListener {

    private val log = Logger.getInstance(CodyDocumentChangeListener::class.java)
    private var debounceTimer: Timer? = null

    override fun documentChanged(event: DocumentEvent) {
        // Capture document reference on EDT — the event itself is only valid during this callback
        val document = event.document
        debounceTimer?.cancel()
        debounceTimer = Timer("cody-debounce", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    sendDidChange(document)
                }
            }, DEBOUNCE_MS)
        }
    }

    private fun sendDidChange(document: Document) {
        val manager = try {
            CodyAgentManager.getInstance(project)
        } catch (e: Exception) { return }

        if (manager.state.value !is CodyAgentManager.AgentState.Running) return

        try {
            val server = manager.getServerOrNull() ?: return
            // Document reads on non-EDT threads require a read action
            val content = ApplicationManager.getApplication().runReadAction<String> { document.text }
            server.textDocumentDidChange(
                ProtocolTextDocument(
                    uri = fileUri,
                    content = content
                )
            )
            log.debug("Sent didChange for $fileUri")
        } catch (e: Exception) {
            log.debug("Failed to send didChange", e)
        }
    }

    fun dispose() {
        debounceTimer?.cancel()
        debounceTimer = null
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
