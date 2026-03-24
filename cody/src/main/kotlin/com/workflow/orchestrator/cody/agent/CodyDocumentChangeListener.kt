package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.ProtocolTextDocument
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class CodyDocumentChangeListener(
    private val project: Project,
    private val fileUri: String
) : DocumentListener {

    private val log = Logger.getInstance(CodyDocumentChangeListener::class.java)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cody-debounce").apply { isDaemon = true }
    }
    private var pendingTask: ScheduledFuture<*>? = null

    override fun documentChanged(event: DocumentEvent) {
        // Capture document reference on EDT — the event itself is only valid during this callback
        val document = event.document
        pendingTask?.cancel(false)
        pendingTask = scheduler.schedule(
            { sendDidChange(document) },
            DEBOUNCE_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun isIntegratedMode(): Boolean = try {
        CodyAgentProviderService.getInstance(project).isIntegratedMode
    } catch (e: Exception) {
        false
    }

    private fun sendDidChange(document: Document) {
        // Skip document sync when the official Cody plugin handles it
        if (isIntegratedMode()) return

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
        pendingTask?.cancel(false)
        pendingTask = null
        scheduler.shutdownNow()
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
