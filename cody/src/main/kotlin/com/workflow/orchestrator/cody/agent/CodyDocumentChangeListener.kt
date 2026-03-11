package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.diagnostic.Logger
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
        debounceTimer?.cancel()
        debounceTimer = Timer("cody-debounce", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    sendDidChange(event)
                }
            }, DEBOUNCE_MS)
        }
    }

    private fun sendDidChange(event: DocumentEvent) {
        val manager = try {
            CodyAgentManager.getInstance(project)
        } catch (e: Exception) { return }

        if (manager.state.value !is CodyAgentManager.AgentState.Running) return

        try {
            val server = manager.getServerOrNull() ?: return
            server.textDocumentDidChange(
                ProtocolTextDocument(
                    uri = fileUri,
                    content = event.document.text
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
